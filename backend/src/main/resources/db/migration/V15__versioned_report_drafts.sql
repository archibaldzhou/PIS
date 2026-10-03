-- Template metadata is installed explicitly in isolated synthetic fixtures, never implicitly approved.
CREATE TABLE report_template (
 code text NOT NULL CHECK(code ~ '^[A-Z][A-Z0-9_-]{0,63}$'),
 version integer NOT NULL CHECK(version>0), title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 128),
 schema_code text NOT NULL CHECK(schema_code IN ('SYN-TEXT-1','SYN-STRUCTURED-2')),
 PRIMARY KEY(code,version)
);
CREATE TRIGGER trg_report_template_immutable BEFORE UPDATE OR DELETE ON report_template FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_revision (
 id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES pathology_case(id), version bigint NOT NULL CHECK(version>=0),
 template_code text NOT NULL, template_version integer NOT NULL,
 fields jsonb NOT NULL CHECK(jsonb_typeof(fields)='object' AND octet_length(fields::text)<=40000),
 assignment_version bigint NOT NULL CHECK(assignment_version>=0), author_id uuid NOT NULL REFERENCES app_user(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(case_id,version), UNIQUE(case_id,version,id),
 FOREIGN KEY(template_code,template_version) REFERENCES report_template(code,version)
);
CREATE TABLE report_draft (
 case_id uuid PRIMARY KEY REFERENCES pathology_case(id), version bigint NOT NULL CHECK(version>=0), revision_id uuid NOT NULL,
 FOREIGN KEY(case_id,version,revision_id) REFERENCES report_revision(case_id,version,id)
);
CREATE TRIGGER trg_report_revision_immutable BEFORE UPDATE OR DELETE ON report_revision FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_report_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.case_id<>OLD.case_id OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Report draft identity/version is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_draft_version BEFORE UPDATE ON report_draft FOR EACH ROW EXECUTE FUNCTION protect_report_draft();
