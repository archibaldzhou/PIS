ALTER TABLE workflow_grant ADD COLUMN can_material boolean NOT NULL DEFAULT false CHECK(NOT can_material OR can_read);
CREATE TABLE material_entity (
 id uuid PRIMARY KEY,
 hospital_id uuid NOT NULL,
 patient_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('BLOCK','SLIDE')),
 route text NOT NULL CHECK(route IN ('CASSETTE','BLOCK_BASED','DIRECT_CYTOLOGY')),
 operation text NOT NULL CHECK(operation IN ('ORIGINAL','RECUT','DEEPER')),
 display_number text NOT NULL UNIQUE,
 barcode text NOT NULL UNIQUE CHECK(char_length(barcode)=34 AND barcode ~ '^S[0-9A-F]{32}[0-9A-Z. $/+%-]$'),
 record_id uuid,
 cassette_id uuid,
 container_id uuid,
 block_id uuid,
 source_slide_id uuid,
 technical_task_id uuid,
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','VOID')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 block_kind text GENERATED ALWAYS AS ('BLOCK'::text) STORED,
 slide_kind text GENERATED ALWAYS AS ('SLIDE'::text) STORED,
 CHECK((kind='BLOCK' AND route='CASSETTE' AND operation='ORIGINAL' AND record_id IS NOT NULL AND cassette_id IS NOT NULL AND technical_task_id IS NOT NULL AND container_id IS NULL AND block_id IS NULL AND source_slide_id IS NULL)
 OR (kind='SLIDE' AND route='BLOCK_BASED' AND record_id IS NOT NULL AND cassette_id IS NOT NULL AND technical_task_id IS NOT NULL AND container_id IS NULL AND block_id IS NOT NULL AND ((operation='ORIGINAL' AND source_slide_id IS NULL) OR (operation IN ('RECUT','DEEPER') AND source_slide_id IS NOT NULL)))
 OR (kind='SLIDE' AND route='DIRECT_CYTOLOGY' AND operation='ORIGINAL' AND container_id IS NOT NULL AND record_id IS NULL AND cassette_id IS NULL AND block_id IS NULL AND source_slide_id IS NULL AND technical_task_id IS NULL)),
 CHECK(block_id IS NULL OR block_id<>id), CHECK(source_slide_id IS NULL OR source_slide_id<>id),
 UNIQUE(id,barcode), UNIQUE(id,kind), UNIQUE(hospital_id,request_id,id), UNIQUE(block_id,id),
 UNIQUE(hospital_id,request_id,case_id,record_id,cassette_id,id),
 FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id) REFERENCES gross_cassette(hospital_id,request_id,case_id,record_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,container_id) REFERENCES specimen_container(hospital_id,request_id,case_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id,technical_task_id) REFERENCES technical_task(hospital_id,request_id,case_id,record_id,cassette_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id,block_id) REFERENCES material_entity(hospital_id,request_id,case_id,record_id,cassette_id,id),
 FOREIGN KEY(block_id,block_kind) REFERENCES material_entity(id,kind),
 FOREIGN KEY(source_slide_id,slide_kind) REFERENCES material_entity(id,kind),
 FOREIGN KEY(block_id,source_slide_id) REFERENCES material_entity(block_id,id)
);
CREATE UNIQUE INDEX uq_material_block_task ON material_entity(technical_task_id) WHERE kind='BLOCK';
CREATE INDEX ix_material_request ON material_entity(request_id,created_at,id);
CREATE INDEX ix_material_block ON material_entity(block_id);
CREATE TABLE material_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 material_id uuid NOT NULL REFERENCES material_entity(id),
 material_version bigint NOT NULL CHECK(material_version>=0),
 action text NOT NULL CHECK(action IN ('CREATE','DERIVE','RECUT','DEEPER','VOID','SOURCE_VOIDED')),
 related_id uuid REFERENCES material_entity(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id),
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(material_id,material_version)
);
CREATE TRIGGER trg_material_identity BEFORE UPDATE ON material_entity FOR EACH ROW EXECUTE FUNCTION protect_gross_identity();
CREATE TRIGGER trg_material_event_immutable BEFORE UPDATE OR DELETE ON material_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
-- Add material targets without renaming/dropping the existing container contract or data.
ALTER TABLE label_identity ADD COLUMN material_id uuid;
ALTER TABLE label_identity ADD COLUMN target_id uuid GENERATED ALWAYS AS (coalesce(container_id,material_id)) STORED;
ALTER TABLE label_identity DROP CONSTRAINT label_identity_pkey;
ALTER TABLE label_identity ALTER COLUMN container_id DROP NOT NULL;
ALTER TABLE label_identity ADD PRIMARY KEY(target_id);
ALTER TABLE label_identity ADD UNIQUE(container_id);
ALTER TABLE label_identity ADD UNIQUE(material_id,barcode);
ALTER TABLE label_identity ADD UNIQUE(hospital_id,request_id,material_id);
ALTER TABLE label_identity ADD CHECK(num_nonnulls(container_id,material_id)=1);
ALTER TABLE label_identity ADD FOREIGN KEY(material_id,barcode) REFERENCES material_entity(id,barcode);
ALTER TABLE label_identity ADD FOREIGN KEY(hospital_id,request_id,material_id) REFERENCES material_entity(hospital_id,request_id,id);
ALTER TABLE label_job ADD COLUMN material_id uuid;
ALTER TABLE label_job ADD COLUMN target_id uuid GENERATED ALWAYS AS (coalesce(container_id,material_id)) STORED;
ALTER TABLE label_job ALTER COLUMN container_id DROP NOT NULL;
ALTER TABLE label_job DROP CONSTRAINT label_job_template_version_check;
ALTER TABLE label_job ADD CHECK((container_id IS NOT NULL AND material_id IS NULL AND template_version='SYN-CONTAINER-1') OR (material_id IS NOT NULL AND container_id IS NULL AND template_version='SYN-MATERIAL-1'));
ALTER TABLE label_job ADD UNIQUE(target_id,id);
ALTER TABLE label_job ADD FOREIGN KEY(material_id,barcode) REFERENCES label_identity(material_id,barcode);
ALTER TABLE label_job ADD FOREIGN KEY(hospital_id,request_id,material_id) REFERENCES label_identity(hospital_id,request_id,material_id);
ALTER TABLE label_job ADD FOREIGN KEY(target_id,parent_job_id) REFERENCES label_job(target_id,id);
CREATE UNIQUE INDEX uq_label_target_root ON label_job(target_id) WHERE parent_job_id IS NULL;
CREATE INDEX ix_label_target_created ON label_job(target_id,created_at DESC,id DESC);
