-- Bounded synthetic PDF only: no demo records, external paths or physical printer acknowledgments.
CREATE TABLE report_artifact (
 id uuid PRIMARY KEY, case_id uuid NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version=0),
 signature_id uuid NOT NULL UNIQUE, signature_version bigint NOT NULL, review_id uuid NOT NULL,
 revision_id uuid NOT NULL, draft_version bigint NOT NULL,
 template_code text NOT NULL, template_version integer NOT NULL, schema_code text NOT NULL,
 field_snapshot jsonb NOT NULL CHECK(jsonb_typeof(field_snapshot)='object'), dependency_token text NOT NULL CHECK(dependency_token ~ '^[a-f0-9]{64}$'),
 renderer_version text NOT NULL CHECK(renderer_version='SYN-RASTER-PDF-1'), font_hash text NOT NULL CHECK(font_hash ~ '^[a-f0-9]{64}$'),
 pdf bytea NOT NULL CHECK(octet_length(pdf) BETWEEN 100 AND 4194304 AND substring(pdf FROM 1 FOR 8)=convert_to('%PDF-1.4','UTF8')),
 sha256 text NOT NULL CHECK(sha256=encode(sha256(pdf),'hex')), byte_size integer NOT NULL CHECK(byte_size=octet_length(pdf)),
 pages integer NOT NULL CHECK(pages BETWEEN 1 AND 16), creation_reason text NOT NULL CHECK(char_length(creation_reason) BETWEEN 1 AND 2000 AND btrim(creation_reason)<>''), created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(case_id,id), FOREIGN KEY(case_id,signature_version,signature_id) REFERENCES report_review_event(case_id,version,id),
 FOREIGN KEY(case_id,review_id) REFERENCES report_review_event(case_id,id),
 FOREIGN KEY(case_id,draft_version,revision_id) REFERENCES report_revision(case_id,version,id),
 FOREIGN KEY(template_code,template_version) REFERENCES report_template(code,version)
);
CREATE FUNCTION validate_report_artifact_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM report_review_event e JOIN report_revision r ON r.id=e.revision_id JOIN report_template t ON t.code=r.template_code AND t.version=r.template_version
 WHERE e.id=NEW.signature_id AND e.action='SIMULATE_SIGN' AND e.review_id=NEW.review_id AND e.dependency_snapshot=NEW.dependency_token AND r.id=NEW.revision_id AND r.fields=NEW.field_snapshot AND r.template_code=NEW.template_code AND r.template_version=NEW.template_version AND t.schema_code=NEW.schema_code) THEN
 RAISE EXCEPTION 'Artifact must bind exact simulated frozen snapshot' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_artifact_binding BEFORE INSERT ON report_artifact FOR EACH ROW EXECUTE FUNCTION validate_report_artifact_binding();
CREATE TRIGGER trg_report_artifact_immutable BEFORE UPDATE OR DELETE ON report_artifact FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_output_head (
 artifact_id uuid PRIMARY KEY REFERENCES report_artifact(id), version bigint NOT NULL DEFAULT -1 CHECK(version>=-1)
);
CREATE FUNCTION protect_report_output_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.artifact_id<>OLD.artifact_id OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Output version must advance by one' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER trg_report_output_head BEFORE UPDATE ON report_output_head FOR EACH ROW EXECUTE FUNCTION protect_report_output_head();
CREATE TABLE report_output_event (
 id uuid PRIMARY KEY, artifact_id uuid NOT NULL REFERENCES report_artifact(id), version bigint NOT NULL CHECK(version>=0),
 kind text NOT NULL CHECK(kind IN ('PREVIEW','DOWNLOAD','PRINT_REQUEST','REPRINT_REQUEST','USER_REPORTED_PRINTED','USER_REPORTED_FAILED','USER_REPORTED_CANCELLED')),
 request_id uuid, actor_id uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''), occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(artifact_id,version), UNIQUE(artifact_id,id), FOREIGN KEY(artifact_id,request_id) REFERENCES report_output_event(artifact_id,id),
 CHECK((kind IN ('REPRINT_REQUEST','USER_REPORTED_PRINTED','USER_REPORTED_FAILED','USER_REPORTED_CANCELLED'))=(request_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_report_output_result ON report_output_event(request_id) WHERE kind IN ('USER_REPORTED_PRINTED','USER_REPORTED_FAILED','USER_REPORTED_CANCELLED');
CREATE TRIGGER trg_report_output_event_immutable BEFORE UPDATE OR DELETE ON report_output_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
