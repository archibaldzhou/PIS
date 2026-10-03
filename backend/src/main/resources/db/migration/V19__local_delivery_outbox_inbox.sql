CREATE TABLE report_delivery (
 id uuid PRIMARY KEY, case_id uuid NOT NULL, artifact_id uuid NOT NULL,
 destination text NOT NULL CHECK(destination='LOCAL_SIM'), predecessor uuid,
 created_by uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(case_id,id), UNIQUE(artifact_id,destination), FOREIGN KEY(case_id,artifact_id) REFERENCES report_artifact(case_id,id),
 FOREIGN KEY(case_id,predecessor) REFERENCES report_review_event(case_id,id)
);
CREATE TRIGGER trg_delivery_immutable BEFORE UPDATE OR DELETE ON report_delivery FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_delivery_outbox (
 delivery_id uuid PRIMARY KEY REFERENCES report_delivery(id), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 state text NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','ATTEMPTING','RETRY_WAIT','ACKED','RECONCILED','REJECTED','DEAD')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3), attempt_id uuid,
 lease_until timestamptz, next_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((state='ATTEMPTING')=(lease_until IS NOT NULL)), CHECK((attempts=0)=(attempt_id IS NULL))
);
CREATE FUNCTION protect_delivery_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Persistent outbox cannot be deleted' USING ERRCODE='23514'; END IF;
 IF NEW.delivery_id<>OLD.delivery_id OR NEW.version<>OLD.version+1 OR NEW.attempts<OLD.attempts OR NEW.attempts>OLD.attempts+1 THEN RAISE EXCEPTION 'Invalid outbox CAS' USING ERRCODE='23514'; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER trg_delivery_outbox BEFORE UPDATE OR DELETE ON report_delivery_outbox FOR EACH ROW EXECUTE FUNCTION protect_delivery_outbox();
CREATE TABLE report_delivery_event (
 id uuid PRIMARY KEY, delivery_id uuid NOT NULL REFERENCES report_delivery(id), version bigint NOT NULL,
 action text NOT NULL, state text NOT NULL, attempt_id uuid, actor_id uuid NOT NULL REFERENCES app_user(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000), occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(delivery_id,version)
);
CREATE TRIGGER trg_delivery_event_immutable BEFORE UPDATE OR DELETE ON report_delivery_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_local_inbox (
 delivery_id uuid PRIMARY KEY REFERENCES report_delivery(id), case_id uuid NOT NULL, artifact_id uuid NOT NULL,
 signature_id uuid NOT NULL, revision_id uuid NOT NULL, sha256 text NOT NULL, destination text NOT NULL CHECK(destination='LOCAL_SIM'),
 pdf bytea NOT NULL CHECK(octet_length(pdf) BETWEEN 100 AND 4194304), received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(case_id,delivery_id) REFERENCES report_delivery(case_id,id), FOREIGN KEY(case_id,artifact_id) REFERENCES report_artifact(case_id,id), CHECK(sha256=encode(sha256(pdf),'hex'))
);
CREATE FUNCTION validate_local_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM report_delivery d JOIN report_artifact a ON a.id=d.artifact_id WHERE d.id=NEW.delivery_id AND d.case_id=NEW.case_id AND a.id=NEW.artifact_id AND a.signature_id=NEW.signature_id AND a.revision_id=NEW.revision_id AND a.sha256=NEW.sha256 AND d.destination=NEW.destination AND a.pdf=NEW.pdf) THEN RAISE EXCEPTION 'Inbox binding mismatch' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER trg_local_inbox_binding BEFORE INSERT ON report_local_inbox FOR EACH ROW EXECUTE FUNCTION validate_local_inbox();
CREATE TRIGGER trg_local_inbox_immutable BEFORE UPDATE OR DELETE ON report_local_inbox FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_local_receiver (
 case_id uuid PRIMARY KEY REFERENCES pathology_case(id), delivery_id uuid NOT NULL,
 FOREIGN KEY(case_id,delivery_id) REFERENCES report_delivery(case_id,id), FOREIGN KEY(delivery_id) REFERENCES report_local_inbox(delivery_id)
);
CREATE FUNCTION enforce_delivery_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT ((OLD.state IN ('QUEUED','RETRY_WAIT') AND NEW.state='ATTEMPTING' AND NEW.attempts=OLD.attempts+1 AND NEW.attempt_id IS DISTINCT FROM OLD.attempt_id)
 OR (OLD.state='ATTEMPTING' AND NEW.state IN ('ATTEMPTING','RETRY_WAIT','ACKED','REJECTED','DEAD') AND NEW.attempts=OLD.attempts AND NEW.attempt_id=OLD.attempt_id)
 OR (OLD.state='ACKED' AND NEW.state IN ('RECONCILED','REJECTED') AND NEW.attempts=OLD.attempts AND NEW.attempt_id=OLD.attempt_id)) THEN
 RAISE EXCEPTION 'Invalid delivery transition' USING ERRCODE='23514'; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER trg_delivery_transition BEFORE UPDATE ON report_delivery_outbox FOR EACH ROW EXECUTE FUNCTION enforce_delivery_transition();
CREATE FUNCTION validate_local_receiver_advance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior uuid; expected uuid;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Receiver history cannot be reset' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND NEW.case_id<>OLD.case_id THEN RAISE EXCEPTION 'Receiver identity is immutable' USING ERRCODE='23514'; END IF;
 SELECT a.signature_id INTO prior FROM report_local_receiver r JOIN report_delivery d ON d.id=r.delivery_id JOIN report_artifact a ON a.id=d.artifact_id WHERE r.case_id=NEW.case_id;
 SELECT predecessor INTO expected FROM report_delivery WHERE id=NEW.delivery_id AND case_id=NEW.case_id;
 IF expected IS DISTINCT FROM prior THEN RAISE EXCEPTION 'Receiver predecessor mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_local_receiver BEFORE INSERT OR UPDATE OR DELETE ON report_local_receiver FOR EACH ROW EXECUTE FUNCTION validate_local_receiver_advance();
CREATE TABLE report_delivery_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL REFERENCES hospital(id), case_id uuid NOT NULL REFERENCES pathology_case(id),
 actor_id uuid NOT NULL REFERENCES app_user(id), action text NOT NULL, code text NOT NULL,
 trace_id uuid NOT NULL, occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER trg_delivery_rejection_immutable BEFORE UPDATE OR DELETE ON report_delivery_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
ALTER TABLE report_delivery_rejection ADD CONSTRAINT fk_delivery_rejection_scope FOREIGN KEY(hospital_id,case_id) REFERENCES pathology_case(hospital_id,id);
