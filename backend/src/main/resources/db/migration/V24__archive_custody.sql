-- Synthetic custody registration only. No disposal, external sharing or physical confirmation.
ALTER TABLE material_entity ADD UNIQUE(case_id,id);
CREATE TABLE archive_grant (
 user_id uuid NOT NULL REFERENCES app_user(id), scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 qualification text NOT NULL CHECK(qualification='SYN-ARCHIVE-1'), can_request boolean NOT NULL DEFAULT false,
 can_approve boolean NOT NULL DEFAULT false, can_manage boolean NOT NULL DEFAULT false,
 valid_until timestamptz NOT NULL, revoked_at timestamptz, PRIMARY KEY(user_id,scope_id)
);
CREATE TABLE archive_book (
 request_id uuid PRIMARY KEY, hospital_id uuid NOT NULL, scope_id uuid NOT NULL, case_id uuid NOT NULL, patient_id uuid NOT NULL,
 version bigint NOT NULL DEFAULT -1 CHECK(version>=-1),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
 FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id),
 UNIQUE(request_id,case_id), UNIQUE(request_id,scope_id)
);
CREATE TABLE archive_location (
 id uuid PRIMARY KEY, scope_id uuid NOT NULL REFERENCES workflow_scope(id), code text NOT NULL CHECK(code ~ '^SYN-[A-Z0-9-]{1,60}$'),
 created_by uuid NOT NULL REFERENCES app_user(id), UNIQUE(scope_id,code), UNIQUE(scope_id,id)
);
CREATE TABLE archive_item (
 id uuid PRIMARY KEY, request_id uuid NOT NULL, case_id uuid NOT NULL, scope_id uuid NOT NULL, material_id uuid, artifact_id uuid,
 kind text NOT NULL CHECK(kind IN ('BLOCK','SLIDE','REPORT')), barcode text NOT NULL, source_version bigint NOT NULL CHECK(source_version>=0),
 artifact_hash text, revision_id uuid, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 location_id uuid, condition text NOT NULL DEFAULT 'RECORDED' CHECK(condition IN ('RECORDED','DAMAGED','LOST','FOUND_PENDING')),
 policy_label text NOT NULL CHECK(policy_label ~ '^SYN-[A-Z0-9-]{1,60}$'), legal_hold boolean NOT NULL,
 FOREIGN KEY(request_id,case_id) REFERENCES archive_book(request_id,case_id), FOREIGN KEY(request_id,scope_id) REFERENCES archive_book(request_id,scope_id),
 FOREIGN KEY(scope_id,location_id) REFERENCES archive_location(scope_id,id),
 FOREIGN KEY(case_id,material_id) REFERENCES material_entity(case_id,id),
 FOREIGN KEY(case_id,artifact_id) REFERENCES report_artifact(case_id,id), FOREIGN KEY(case_id,revision_id) REFERENCES report_revision(case_id,id),
 UNIQUE(request_id,id), UNIQUE(material_id), UNIQUE(artifact_id), UNIQUE(barcode), UNIQUE(location_id),
 CHECK((kind='REPORT' AND material_id IS NULL AND artifact_id IS NOT NULL AND revision_id IS NOT NULL AND artifact_hash IS NOT NULL AND artifact_hash ~ '^[a-f0-9]{64}$') OR (kind IN ('BLOCK','SLIDE') AND material_id IS NOT NULL AND artifact_id IS NULL AND revision_id IS NULL AND artifact_hash IS NULL))
);
CREATE TABLE archive_loan (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES archive_book(request_id), applicant_id uuid NOT NULL REFERENCES app_user(id), borrower_id uuid NOT NULL REFERENCES app_user(id),
 purpose text NOT NULL CHECK(char_length(purpose) BETWEEN 1 AND 1000 AND btrim(purpose)<>''), due_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(), approver_id uuid REFERENCES app_user(id),
 state text NOT NULL CHECK(state IN ('REQUESTED','APPROVED','CLOSED','CANCELLED','REJECTED')), UNIQUE(request_id,id),
 CHECK(due_at>created_at), CHECK(approver_id IS NULL OR approver_id<>applicant_id)
);
CREATE TABLE archive_loan_item (
 request_id uuid NOT NULL, loan_id uuid NOT NULL, item_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('RESERVED','OUT','RETURNED','RELEASED')),
 PRIMARY KEY(loan_id,item_id), FOREIGN KEY(request_id,loan_id) REFERENCES archive_loan(request_id,id), FOREIGN KEY(request_id,item_id) REFERENCES archive_item(request_id,id)
);
CREATE UNIQUE INDEX uq_archive_active_loan ON archive_loan_item(item_id) WHERE state IN ('RESERVED','OUT');
CREATE TABLE archive_inventory (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES archive_book(request_id), book_version bigint NOT NULL CHECK(book_version>=0),
 created_by uuid NOT NULL REFERENCES app_user(id), recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(), UNIQUE(request_id,id)
);
CREATE TABLE archive_inventory_item (
 inventory_id uuid NOT NULL, request_id uuid NOT NULL, item_id uuid NOT NULL, item_version bigint NOT NULL,
 location_id uuid REFERENCES archive_location(id), condition text NOT NULL, loan_state text,
 PRIMARY KEY(inventory_id,item_id), FOREIGN KEY(request_id,inventory_id) REFERENCES archive_inventory(request_id,id), FOREIGN KEY(request_id,item_id) REFERENCES archive_item(request_id,id)
);
CREATE TABLE archive_event (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES archive_book(request_id), version bigint NOT NULL CHECK(version>=0),
 action text NOT NULL, item_id uuid, loan_id uuid, inventory_id uuid, reference_id uuid,
 actor_id uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 detail jsonb NOT NULL CHECK(jsonb_typeof(detail)='object'), recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 physical_confirmation text NOT NULL DEFAULT 'UNVERIFIED' CHECK(physical_confirmation='UNVERIFIED'),
 UNIQUE(request_id,version), UNIQUE(request_id,id), FOREIGN KEY(request_id,item_id) REFERENCES archive_item(request_id,id),
 FOREIGN KEY(request_id,loan_id) REFERENCES archive_loan(request_id,id), FOREIGN KEY(request_id,inventory_id) REFERENCES archive_inventory(request_id,id),
 FOREIGN KEY(request_id,reference_id) REFERENCES archive_event(request_id,id)
);
ALTER TABLE archive_book ADD FOREIGN KEY(request_id,version) REFERENCES archive_event(request_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER trg_archive_event BEFORE UPDATE OR DELETE ON archive_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_archive_snapshot BEFORE UPDATE OR DELETE ON archive_inventory FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_archive_snapshot_item BEFORE UPDATE OR DELETE ON archive_inventory_item FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_archive_location BEFORE UPDATE OR DELETE ON archive_location FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_archive_item() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Archive cannot be disposed' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['version','location_id','condition']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','location_id','condition']) OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Archive identity immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_archive_item BEFORE UPDATE OR DELETE ON archive_item FOR EACH ROW EXECUTE FUNCTION protect_archive_item();
CREATE FUNCTION validate_archive_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.material_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM material_entity m WHERE m.id=NEW.material_id AND m.case_id=NEW.case_id AND m.kind=NEW.kind AND m.barcode=NEW.barcode AND m.version=NEW.source_version) THEN RAISE EXCEPTION 'Archive material binding mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.artifact_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM report_artifact a WHERE a.id=NEW.artifact_id AND a.case_id=NEW.case_id AND a.version=NEW.source_version AND a.sha256=NEW.artifact_hash AND a.revision_id=NEW.revision_id AND NEW.barcode='SYN-PDF-'||a.id::text) THEN RAISE EXCEPTION 'Archive artifact binding mismatch' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_archive_source BEFORE INSERT ON archive_item FOR EACH ROW EXECUTE FUNCTION validate_archive_source();
CREATE FUNCTION protect_archive_book() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Archive cannot be disposed' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-'version') IS DISTINCT FROM (to_jsonb(OLD)-'version') OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Archive version conflict' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_archive_book BEFORE UPDATE OR DELETE ON archive_book FOR EACH ROW EXECUTE FUNCTION protect_archive_book();
CREATE TRIGGER trg_archive_loan_no_delete BEFORE DELETE ON archive_loan FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_archive_loan_item_no_delete BEFORE DELETE ON archive_loan_item FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();

CREATE FUNCTION protect_archive_loan() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (to_jsonb(NEW)-ARRAY['state','approver_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','approver_id']) OR NOT ((OLD.state='REQUESTED' AND NEW.state IN ('APPROVED','CANCELLED','REJECTED')) OR (OLD.state='APPROVED' AND NEW.state IN ('CLOSED','CANCELLED','REJECTED'))) OR (OLD.approver_id IS NOT NULL AND NEW.approver_id IS DISTINCT FROM OLD.approver_id) THEN RAISE EXCEPTION 'Loan identity or terminal state immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_archive_loan BEFORE UPDATE ON archive_loan FOR EACH ROW EXECUTE FUNCTION protect_archive_loan();
CREATE FUNCTION protect_archive_loan_item() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.request_id,NEW.loan_id,NEW.item_id) IS DISTINCT FROM ROW(OLD.request_id,OLD.loan_id,OLD.item_id) OR NOT ((OLD.state='RESERVED' AND NEW.state IN ('OUT','RELEASED')) OR (OLD.state='OUT' AND NEW.state='RETURNED')) THEN RAISE EXCEPTION 'Loan item history immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_archive_loan_item BEFORE UPDATE ON archive_loan_item FOR EACH ROW EXECUTE FUNCTION protect_archive_loan_item();
CREATE UNIQUE INDEX uq_archive_correction ON archive_event(reference_id) WHERE action='CORRECT';
CREATE TABLE archive_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id),
 action text NOT NULL, code text NOT NULL, trace_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY(hospital_id,request_id) REFERENCES pathology_request(hospital_id,id)
);
CREATE TRIGGER trg_archive_rejection BEFORE UPDATE OR DELETE ON archive_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
