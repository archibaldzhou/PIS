CREATE TABLE storage_grant (
 user_id uuid NOT NULL REFERENCES app_user(id),scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 qualification text NOT NULL CHECK(qualification='SYN-STORAGE-1'),all_cases boolean NOT NULL DEFAULT false,
 can_write boolean NOT NULL DEFAULT false,can_capacity boolean NOT NULL DEFAULT false,
 valid_until timestamptz NOT NULL,revoked_at timestamptz,PRIMARY KEY(user_id,scope_id)
);
CREATE TABLE storage_case_grant (
 user_id uuid NOT NULL,scope_id uuid NOT NULL,case_id uuid NOT NULL REFERENCES pathology_case(id),
 valid_until timestamptz NOT NULL,revoked_at timestamptz,PRIMARY KEY(user_id,case_id),
 FOREIGN KEY(user_id,scope_id) REFERENCES storage_grant(user_id,scope_id)
);
CREATE TABLE storage_quota (
 hospital_id uuid PRIMARY KEY REFERENCES hospital(id),reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes BETWEEN 0 AND 536870912)
);
CREATE TABLE storage_asset (
 id uuid PRIMARY KEY,hospital_id uuid NOT NULL,request_id uuid NOT NULL,case_id uuid NOT NULL,scope_id uuid NOT NULL,
 head bigint NOT NULL DEFAULT -1 CHECK(head>=-1),UNIQUE(id,hospital_id,request_id,case_id,scope_id),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id)
);
CREATE TABLE storage_version (
 id uuid PRIMARY KEY,asset_id uuid NOT NULL,hospital_id uuid NOT NULL,request_id uuid NOT NULL,case_id uuid NOT NULL,scope_id uuid NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>=0),root_id uuid NOT NULL,
 purpose text NOT NULL CHECK(purpose='SYNTHETIC_ORIGINAL'),media_type text NOT NULL CHECK(media_type='application/octet-stream'),
 byte_size bigint NOT NULL CHECK(byte_size BETWEEN 25 AND 67108864),sha256 text NOT NULL CHECK(sha256~'^[a-f0-9]{64}$'),
 actor_id uuid NOT NULL REFERENCES app_user(id),created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 state text NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','UPLOADING','STAGED','FINALIZING','READY','FAILED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),UNIQUE(asset_id,ordinal),
 FOREIGN KEY(asset_id,hospital_id,request_id,case_id,scope_id) REFERENCES storage_asset(id,hospital_id,request_id,case_id,scope_id)
);
CREATE TABLE storage_finalize (
 version_id uuid PRIMARY KEY REFERENCES storage_version(id),state text NOT NULL CHECK(state IN ('PENDING','DONE')),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE TABLE storage_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),version_id uuid NOT NULL REFERENCES storage_version(id),
 version bigint NOT NULL,action text NOT NULL,actor_id uuid NOT NULL REFERENCES app_user(id),occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(version_id,version)
);
CREATE TABLE storage_read_budget (
 user_id uuid NOT NULL REFERENCES app_user(id),minute timestamptz NOT NULL,requests integer NOT NULL CHECK(requests BETWEEN 0 AND 60),
 bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 33554432),PRIMARY KEY(user_id,minute)
);
CREATE INDEX ix_storage_version_request ON storage_version(request_id,created_at,id);
CREATE FUNCTION protect_storage_version() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable original metadata' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version']) OR NEW.version<>OLD.version+1 OR
 NOT ((OLD.state='RESERVED' AND NEW.state='UPLOADING') OR (OLD.state='UPLOADING' AND NEW.state IN ('STAGED','FAILED')) OR
 (OLD.state='STAGED' AND NEW.state='FINALIZING') OR (OLD.state='FINALIZING' AND NEW.state='READY')) THEN
 RAISE EXCEPTION 'Invalid immutable storage transition' USING ERRCODE='23514'; END IF;RETURN NEW; END $$;
CREATE TRIGGER trg_storage_version BEFORE UPDATE OR DELETE ON storage_version FOR EACH ROW EXECUTE FUNCTION protect_storage_version();
CREATE FUNCTION protect_storage_asset() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable asset' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-'head') IS DISTINCT FROM (to_jsonb(OLD)-'head') OR NEW.head<>OLD.head+1 THEN RAISE EXCEPTION 'Invalid asset version' USING ERRCODE='23514'; END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_storage_asset BEFORE UPDATE OR DELETE ON storage_asset FOR EACH ROW EXECUTE FUNCTION protect_storage_asset();
CREATE TRIGGER trg_storage_event BEFORE UPDATE OR DELETE ON storage_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();

CREATE FUNCTION protect_storage_finalize() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Preserve finalize history' USING ERRCODE='23514'; END IF;
 IF NEW.version_id<>OLD.version_id OR NEW.created_at<>OLD.created_at OR OLD.state<>'PENDING' OR NEW.state<>'DONE' THEN RAISE EXCEPTION 'Invalid finalize transition' USING ERRCODE='23514'; END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_storage_finalize BEFORE UPDATE OR DELETE ON storage_finalize FOR EACH ROW EXECUTE FUNCTION protect_storage_finalize();
CREATE FUNCTION check_storage_case_scope() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pathology_case c JOIN request_workflow w ON w.request_id=c.request_id WHERE c.id=NEW.case_id AND w.scope_id=NEW.scope_id) THEN RAISE EXCEPTION 'Storage grant scope mismatch' USING ERRCODE='23514'; END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_storage_case_scope BEFORE INSERT OR UPDATE ON storage_case_grant FOR EACH ROW EXECUTE FUNCTION check_storage_case_scope();
