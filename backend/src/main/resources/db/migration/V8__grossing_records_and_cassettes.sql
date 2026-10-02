ALTER TABLE workflow_grant ADD COLUMN can_gross boolean NOT NULL DEFAULT false CHECK(NOT can_gross OR can_read);
ALTER TABLE specimen_container ADD CONSTRAINT uq_container_case_identity UNIQUE(hospital_id,request_id,case_id,id);
CREATE TABLE gross_record (
 id uuid PRIMARY KEY,
 hospital_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL UNIQUE,
 state text NOT NULL CHECK(state IN ('DRAFT','COMPLETED','CANCELLED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(hospital_id,request_id,case_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id)
);
CREATE TABLE gross_revision (
 record_id uuid NOT NULL REFERENCES gross_record(id),
 record_version bigint NOT NULL CHECK(record_version>=0),
 description text NOT NULL CHECK(char_length(description)<=8000),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(record_id,record_version)
);
CREATE TABLE gross_cassette (
 id uuid PRIMARY KEY,
 record_id uuid NOT NULL,
 hospital_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL,
 cassette_number text NOT NULL UNIQUE,
 site text NOT NULL CHECK(char_length(site) BETWEEN 1 AND 255 AND btrim(site)<>''),
 pieces integer NOT NULL CHECK(pieces BETWEEN 1 AND 99),
 state text NOT NULL CHECK(state IN ('PLANNED','CANCELLED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(hospital_id,request_id,case_id,record_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id) REFERENCES gross_record(hospital_id,request_id,case_id,id)
);
CREATE TABLE gross_cassette_source (
 cassette_id uuid NOT NULL,
 container_id uuid NOT NULL,
 record_id uuid NOT NULL,
 hospital_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL,
 PRIMARY KEY(cassette_id,container_id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id) REFERENCES gross_cassette(hospital_id,request_id,case_id,record_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,container_id) REFERENCES specimen_container(hospital_id,request_id,case_id,id)
);
CREATE TABLE gross_photo (
 id uuid PRIMARY KEY,
 record_id uuid NOT NULL,
 hospital_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL,
 container_id uuid NOT NULL,
 object_key text NOT NULL CHECK(object_key='grossing-assets/synthetic-v1.png'),
 sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 byte_count integer NOT NULL CHECK(byte_count BETWEEN 1 AND 16384),
 width integer NOT NULL CHECK(width=256), height integer NOT NULL CHECK(height=160),
 caption text NOT NULL CHECK(char_length(caption)<=255),
 created_by uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 withdrawn_at timestamptz,
 FOREIGN KEY(hospital_id,request_id,case_id,record_id) REFERENCES gross_record(hospital_id,request_id,case_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,container_id) REFERENCES specimen_container(hospital_id,request_id,case_id,id)
);
CREATE TABLE gross_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 record_id uuid NOT NULL REFERENCES gross_record(id),
 record_version bigint NOT NULL CHECK(record_version>=0),
 action text NOT NULL CHECK(action IN ('CREATE','SAVE','CORRECT','ADD_CASSETTE','CANCEL_CASSETTE','ADD_PHOTO','WITHDRAW_PHOTO','COMPLETE','CANCEL')),
 target_id uuid,
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id),
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(record_id,record_version)
);
CREATE FUNCTION protect_gross_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Grossing history and lineage are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER trg_gross_revision_immutable BEFORE UPDATE OR DELETE ON gross_revision FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_gross_event_immutable BEFORE UPDATE OR DELETE ON gross_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_gross_source_immutable BEFORE UPDATE OR DELETE ON gross_cassette_source FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_gross_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'state'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'version') THEN
  RAISE EXCEPTION 'Grossing identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_gross_record_identity BEFORE UPDATE ON gross_record FOR EACH ROW EXECUTE FUNCTION protect_gross_identity();
CREATE TRIGGER trg_gross_cassette_identity BEFORE UPDATE ON gross_cassette FOR EACH ROW EXECUTE FUNCTION protect_gross_identity();
CREATE FUNCTION protect_gross_photo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'withdrawn_at') IS DISTINCT FROM (to_jsonb(OLD)-'withdrawn_at') OR OLD.withdrawn_at IS NOT NULL THEN
  RAISE EXCEPTION 'Grossing photo metadata is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_gross_photo_immutable BEFORE UPDATE ON gross_photo FOR EACH ROW EXECUTE FUNCTION protect_gross_photo();
CREATE INDEX ix_gross_photo_record ON gross_photo(record_id);
CREATE INDEX ix_gross_cassette_record ON gross_cassette(record_id);
CREATE INDEX ix_gross_source_record ON gross_cassette_source(record_id,cassette_id,container_id);
