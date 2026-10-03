-- Frozen revisions/events/PDF bytes are never rewritten. Original report is implicit chain version 0.
CREATE TABLE report_amendment (
 id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES pathology_case(id), version bigint NOT NULL CHECK(version>0),
 kind text NOT NULL CHECK(kind IN ('ADDENDUM','CORRECTION')), parent_id uuid,
 base_signature_id uuid NOT NULL UNIQUE, base_revision_id uuid NOT NULL, base_draft_version bigint NOT NULL,
 start_revision_id uuid NOT NULL UNIQUE, start_version bigint NOT NULL CHECK(start_version=base_draft_version+1),
 actor_id uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(case_id,version,id), UNIQUE(case_id,id), UNIQUE(case_id,version), UNIQUE(parent_id),
 FOREIGN KEY(case_id,parent_id) REFERENCES report_amendment(case_id,id),
 FOREIGN KEY(case_id,base_signature_id) REFERENCES report_review_event(case_id,id),
 FOREIGN KEY(case_id,base_draft_version,base_revision_id) REFERENCES report_revision(case_id,version,id),
 FOREIGN KEY(case_id,start_version,start_revision_id) REFERENCES report_revision(case_id,version,id)
);
CREATE TABLE report_chain_head (
 case_id uuid PRIMARY KEY REFERENCES pathology_case(id), version bigint NOT NULL CHECK(version>0), amendment_id uuid NOT NULL,
 FOREIGN KEY(case_id,version,amendment_id) REFERENCES report_amendment(case_id,version,id)
);
CREATE FUNCTION validate_report_amendment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h report_chain_head; s report_review_event;
BEGIN
 SELECT * INTO h FROM report_chain_head WHERE case_id=NEW.case_id;
 SELECT e.* INTO s FROM report_review_head rh JOIN report_review_event e ON e.id=rh.event_id WHERE rh.case_id=NEW.case_id;
 IF NEW.version<>coalesce(h.version,0)+1 OR NEW.parent_id IS DISTINCT FROM h.amendment_id OR s.action IS DISTINCT FROM 'SIMULATE_SIGN' OR s.id IS DISTINCT FROM NEW.base_signature_id OR s.revision_id IS DISTINCT FROM NEW.base_revision_id OR s.draft_version IS DISTINCT FROM NEW.base_draft_version
 OR NOT EXISTS(SELECT 1 FROM report_draft d WHERE d.case_id=NEW.case_id AND d.revision_id=NEW.base_revision_id AND d.version=NEW.base_draft_version)
 OR (h.amendment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM report_amendment a WHERE a.id=h.amendment_id AND s.draft_version>=a.start_version)) THEN
 RAISE EXCEPTION 'Amendment requires exact current frozen predecessor' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_amendment_binding BEFORE INSERT ON report_amendment FOR EACH ROW EXECUTE FUNCTION validate_report_amendment();
CREATE TRIGGER trg_report_amendment_immutable BEFORE UPDATE OR DELETE ON report_amendment FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_report_chain_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Chain head cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.case_id<>OLD.case_id OR NEW.version<>OLD.version+1 OR NOT EXISTS(SELECT 1 FROM report_amendment WHERE id=NEW.amendment_id AND parent_id=OLD.amendment_id)) THEN
 RAISE EXCEPTION 'Chain requires exact successor' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' AND NEW.version<>1 THEN RAISE EXCEPTION 'Chain starts at one' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_chain_head BEFORE INSERT OR UPDATE OR DELETE ON report_chain_head FOR EACH ROW EXECUTE FUNCTION protect_report_chain_head();
-- Advance a mutable pointer only via an explicit, validated amendment. Original snapshots stay immutable.
CREATE OR REPLACE FUNCTION protect_simulated_signed_draft() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s report_review_event; a report_amendment;
BEGIN
 SELECT e.* INTO s FROM report_review_head h JOIN report_review_event e ON e.id=h.event_id WHERE h.case_id=OLD.case_id;
 IF s.action='SIMULATE_SIGN' THEN
  SELECT n.* INTO a FROM report_chain_head h JOIN report_amendment n ON n.id=h.amendment_id WHERE h.case_id=OLD.case_id;
  IF TG_OP='DELETE' OR a.base_signature_id IS DISTINCT FROM s.id OR a.start_version<=s.draft_version OR (OLD.version<a.start_version AND NEW.revision_id<>a.start_revision_id) THEN
   RAISE EXCEPTION 'Frozen draft requires explicit successor' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END; $$;
CREATE OR REPLACE FUNCTION protect_report_review_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.case_id<>OLD.case_id OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Invalid review CAS' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM report_review_event WHERE id=OLD.event_id AND action='SIMULATE_SIGN') AND NOT EXISTS(
  SELECT 1 FROM report_chain_head h JOIN report_amendment a ON a.id=h.amendment_id JOIN report_review_event e ON e.id=NEW.event_id
  WHERE h.case_id=NEW.case_id AND a.base_signature_id=OLD.event_id AND e.draft_version>=a.start_version AND e.action IN ('APPROVE','RETURN')) THEN
 RAISE EXCEPTION 'Frozen review requires new amendment review' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TABLE report_replacement (
 amendment_id uuid PRIMARY KEY REFERENCES report_amendment(id), case_id uuid NOT NULL,
 old_signature_id uuid NOT NULL UNIQUE, new_signature_id uuid NOT NULL UNIQUE,
 downstream_state text NOT NULL DEFAULT 'PENDING_NOT_SENT' CHECK(downstream_state='PENDING_NOT_SENT'),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(case_id,old_signature_id) REFERENCES report_review_event(case_id,id),
 FOREIGN KEY(case_id,new_signature_id) REFERENCES report_review_event(case_id,id), CHECK(old_signature_id<>new_signature_id)
);
CREATE FUNCTION validate_report_replacement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM report_amendment a JOIN report_chain_head h ON h.amendment_id=a.id JOIN report_review_event e ON e.id=NEW.new_signature_id JOIN report_review_head r ON r.event_id=e.id
 WHERE a.id=NEW.amendment_id AND a.case_id=NEW.case_id AND a.base_signature_id=NEW.old_signature_id AND e.case_id=a.case_id AND e.action='SIMULATE_SIGN' AND e.draft_version>=a.start_version) THEN
 RAISE EXCEPTION 'Replacement must bind new frozen branch' USING ERRCODE='23514'; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_replacement_binding BEFORE INSERT ON report_replacement FOR EACH ROW EXECUTE FUNCTION validate_report_replacement();
CREATE TRIGGER trg_report_replacement_immutable BEFORE UPDATE OR DELETE ON report_replacement FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
