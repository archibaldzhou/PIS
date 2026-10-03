CREATE TABLE cytology_grant (
 scope_id uuid NOT NULL REFERENCES workflow_scope(id), user_id uuid NOT NULL REFERENCES app_user(id),
 qualification text NOT NULL CHECK(qualification='SYN-CYTOLOGY-1'), can_prepare boolean NOT NULL DEFAULT false, can_qc boolean NOT NULL DEFAULT false,
 valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until timestamptz, revoked_at timestamptz,
 PRIMARY KEY(scope_id,user_id), CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TABLE cytology_specimen (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, patient_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL,
 container_id uuid NOT NULL UNIQUE, sample_description text NOT NULL CHECK(char_length(sample_description) BETWEEN 1 AND 1000 AND btrim(sample_description)<>''),
 unit text NOT NULL CHECK(unit='SYN_PORTION'), initial_quantity integer NOT NULL CHECK(initial_quantity BETWEEN 1 AND 99),
 remaining integer NOT NULL CHECK(remaining>=0 AND remaining<=initial_quantity), version bigint NOT NULL CHECK(version>=0),
 qc_state text NOT NULL CHECK(qc_state IN ('PENDING','PASS','FAIL','IDENTITY_MISMATCH')), qc_version bigint NOT NULL CHECK(qc_version>=-1),
 UNIQUE(hospital_id,request_id,case_id,id),
 FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,container_id) REFERENCES specimen_container(hospital_id,request_id,case_id,id)
);
CREATE INDEX ix_cytology_specimen_request ON cytology_specimen(request_id,id);
CREATE TABLE cytology_preparation (
 id uuid PRIMARY KEY, specimen_id uuid NOT NULL, hospital_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL,
 path text NOT NULL CHECK(path IN ('DIRECT_SMEAR','LIQUID_BASED','CELL_BLOCK')),
 metadata text NOT NULL CHECK(char_length(metadata) BETWEEN 1 AND 1000 AND btrim(metadata)<>''),
 transferred integer NOT NULL CHECK(transferred BETWEEN 1 AND 99), source_qc_version bigint NOT NULL CHECK(source_qc_version>=0),
 repeat_of uuid, state text NOT NULL CHECK(state IN ('RESERVED','COMPLETED','FAILED')), version bigint NOT NULL CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(hospital_id,request_id,case_id,id), UNIQUE(specimen_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,specimen_id) REFERENCES cytology_specimen(hospital_id,request_id,case_id,id),
 FOREIGN KEY(specimen_id,repeat_of) REFERENCES cytology_preparation(specimen_id,id), CHECK(repeat_of IS NULL OR repeat_of<>id)
);
CREATE TABLE cytology_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), specimen_id uuid NOT NULL REFERENCES cytology_specimen(id), version bigint NOT NULL CHECK(version>=0),
 preparation_id uuid, action text NOT NULL CHECK(action IN ('REGISTER','QC_PASS','QC_FAIL','IDENTITY_MISMATCH','PREPARE','COMPLETE','FAIL')),
 transferred integer NOT NULL DEFAULT 0 CHECK(transferred BETWEEN 0 AND 99), consumed integer NOT NULL DEFAULT 0 CHECK(consumed BETWEEN 0 AND 99),
 discarded integer NOT NULL DEFAULT 0 CHECK(discarded BETWEEN 0 AND 99), returned integer NOT NULL DEFAULT 0 CHECK(returned BETWEEN 0 AND 99), slides integer NOT NULL DEFAULT 0 CHECK(slides BETWEEN 0 AND 20),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''), actor_id uuid NOT NULL REFERENCES app_user(id), recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(specimen_id,version), FOREIGN KEY(specimen_id,preparation_id) REFERENCES cytology_preparation(specimen_id,id),
 CHECK(action NOT IN ('COMPLETE','FAIL') OR transferred=consumed+discarded+returned),
 CHECK(action<>'COMPLETE' OR slides BETWEEN 1 AND consumed), CHECK(action<>'FAIL' OR (consumed=0 AND slides=0))
);
CREATE UNIQUE INDEX uq_cytology_terminal_event ON cytology_event(preparation_id) WHERE action IN ('COMPLETE','FAIL');
ALTER TABLE cytology_specimen ADD FOREIGN KEY(id,version) REFERENCES cytology_event(specimen_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER trg_cytology_event_immutable BEFORE UPDATE OR DELETE ON cytology_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_cytology_preparation_identity AFTER UPDATE ON cytology_preparation FOR EACH ROW EXECUTE FUNCTION protect_gross_identity();
CREATE TRIGGER trg_cytology_preparation_delete BEFORE DELETE ON cytology_preparation FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_cytology_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Cytology identity is immutable' USING ERRCODE='23514'; END IF;
 IF (OLD.qc_state='IDENTITY_MISMATCH' AND NEW.qc_state<>OLD.qc_state) OR (NEW.qc_state<>OLD.qc_state AND NEW.qc_version<>OLD.qc_version+1) THEN RAISE EXCEPTION 'Source QC generation or quarantine cannot be bypassed' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['remaining','version','qc_state','qc_version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['remaining','version','qc_state','qc_version']) OR NEW.version<>OLD.version+1 OR NEW.qc_version NOT IN (OLD.qc_version,OLD.qc_version+1) THEN
 RAISE EXCEPTION 'Cytology identity/version is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_cytology_inventory BEFORE UPDATE OR DELETE ON cytology_specimen FOR EACH ROW EXECUTE FUNCTION protect_cytology_inventory();
ALTER TABLE material_entity ADD COLUMN cytology_preparation_id uuid;
ALTER TABLE material_entity ADD FOREIGN KEY(hospital_id,request_id,case_id,cytology_preparation_id) REFERENCES cytology_preparation(hospital_id,request_id,case_id,id);
ALTER TABLE material_entity ADD UNIQUE(cytology_preparation_id,id);
ALTER TABLE material_entity ADD FOREIGN KEY(cytology_preparation_id,block_id) REFERENCES material_entity(cytology_preparation_id,id);
ALTER TABLE material_entity DROP CONSTRAINT material_entity_route_check;
ALTER TABLE material_entity ADD CHECK(route IN ('CASSETTE','BLOCK_BASED','DIRECT_CYTOLOGY','CYTOLOGY_BLOCK','CYTOLOGY_SLIDE'));
ALTER TABLE material_entity DROP CONSTRAINT material_entity_check;
ALTER TABLE material_entity ADD CHECK((cytology_preparation_id IS NULL AND ((kind='BLOCK' AND route='CASSETTE' AND operation='ORIGINAL' AND record_id IS NOT NULL AND cassette_id IS NOT NULL AND technical_task_id IS NOT NULL AND container_id IS NULL AND block_id IS NULL AND source_slide_id IS NULL)
 OR (kind='SLIDE' AND route='BLOCK_BASED' AND record_id IS NOT NULL AND cassette_id IS NOT NULL AND technical_task_id IS NOT NULL AND container_id IS NULL AND block_id IS NOT NULL AND ((operation='ORIGINAL' AND source_slide_id IS NULL) OR (operation IN ('RECUT','DEEPER') AND source_slide_id IS NOT NULL)))
 OR (kind='SLIDE' AND route='DIRECT_CYTOLOGY' AND operation='ORIGINAL' AND container_id IS NOT NULL AND record_id IS NULL AND cassette_id IS NULL AND block_id IS NULL AND source_slide_id IS NULL AND technical_task_id IS NULL))) OR (cytology_preparation_id IS NOT NULL AND operation='ORIGINAL' AND container_id IS NOT NULL AND record_id IS NULL AND cassette_id IS NULL AND technical_task_id IS NULL AND source_slide_id IS NULL AND ((kind='BLOCK' AND route='CYTOLOGY_BLOCK' AND block_id IS NULL) OR (kind='SLIDE' AND route='CYTOLOGY_SLIDE'))));
CREATE VIEW cytology_material_gate AS
SELECT m.id, m.request_id,
 CASE WHEN s.qc_state='PASS' AND p.source_qc_version=s.qc_version AND p.state='COMPLETED' THEN 'PASS' ELSE 'SOURCE_QUARANTINED' END AS state
FROM material_entity m JOIN cytology_preparation p ON p.id=m.cytology_preparation_id JOIN cytology_specimen s ON s.id=p.specimen_id;
CREATE OR REPLACE VIEW workflow_quality_projection AS
SELECT m.id,m.request_id,m.patient_id,m.cassette_id,m.created_at,m.state AS material_state,
 coalesce(h.version,-1) AS version,
 CASE WHEN EXISTS(SELECT 1 FROM cytology_specimen isolated WHERE isolated.request_id=m.request_id AND isolated.qc_state='IDENTITY_MISMATCH') THEN 'SOURCE_QUARANTINED' WHEN cg.state IS NOT NULL AND cg.state<>'PASS' THEN 'SOURCE_QUARANTINED' WHEN m.state<>'ACTIVE' THEN 'INVALIDATED'
 WHEN m.block_id IS NOT NULL AND (b.state<>'ACTIVE'
   OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=b.technical_task_id)
   OR (bh.material_id IS NOT NULL AND (bh.state<>'PASS' OR bh.material_version<>b.version OR bh.task_version IS DISTINCT FROM bt.version))) THEN 'SOURCE_QUARANTINED'
 WHEN h.material_id IS NULL THEN CASE WHEN m.technical_task_id IS NOT NULL AND (t.state<>'SIMULATED_DONE' OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=t.id)) THEN 'INVALIDATED' ELSE 'NOT_ASSESSED' END
 WHEN h.state<>'PASS' THEN h.state
 WHEN h.material_version<>m.version OR h.task_version IS DISTINCT FROM t.version OR (m.technical_task_id IS NOT NULL AND (t.state<>'SIMULATED_DONE' OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=t.id))) THEN 'INVALIDATED'
 ELSE 'PASS' END AS state
FROM material_entity m LEFT JOIN cytology_material_gate cg ON cg.id=m.id LEFT JOIN quality_head h ON h.material_id=m.id LEFT JOIN technical_task t ON t.id=m.technical_task_id
LEFT JOIN material_entity b ON b.id=m.block_id LEFT JOIN quality_head bh ON bh.material_id=b.id LEFT JOIN technical_task bt ON bt.id=b.technical_task_id;


CREATE TABLE cytology_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, container_id uuid NOT NULL, request_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES app_user(id), action varchar(40) NOT NULL,
 code varchar(80) NOT NULL, trace_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY(hospital_id,request_id,container_id) REFERENCES specimen_container(hospital_id,request_id,id)
);
CREATE TRIGGER cytology_rejection_immutable BEFORE UPDATE OR DELETE ON cytology_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
-- A nullable block is valid only for an explicit direct/liquid preparation, never a fallback.
CREATE FUNCTION check_cytology_material_path() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p cytology_preparation; s cytology_specimen;
BEGIN
 IF NEW.cytology_preparation_id IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO STRICT p FROM cytology_preparation WHERE id=NEW.cytology_preparation_id;
 SELECT * INTO STRICT s FROM cytology_specimen WHERE id=p.specimen_id;
 IF NEW.container_id<>s.container_id OR NEW.patient_id<>s.patient_id OR p.state<>'COMPLETED'
 OR s.qc_state<>'PASS' OR s.qc_version<>p.source_qc_version
 OR (p.path<>'CELL_BLOCK' AND (NEW.kind<>'SLIDE' OR NEW.block_id IS NOT NULL))
 OR (p.path='CELL_BLOCK' AND NEW.kind='SLIDE' AND NEW.block_id IS NULL) THEN
 RAISE EXCEPTION 'Invalid cytology preparation path or source' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_cytology_material_path BEFORE INSERT ON material_entity FOR EACH ROW EXECUTE FUNCTION check_cytology_material_path();
CREATE UNIQUE INDEX uq_cytology_preparation_block ON material_entity(cytology_preparation_id) WHERE kind='BLOCK' AND cytology_preparation_id IS NOT NULL;

CREATE FUNCTION protect_cytology_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state<>'RESERVED' OR NEW.state NOT IN ('COMPLETED','FAILED') OR NEW.version<>OLD.version+1 THEN
 RAISE EXCEPTION 'Preparation completion is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_cytology_terminal BEFORE UPDATE ON cytology_preparation FOR EACH ROW EXECUTE FUNCTION protect_cytology_terminal();
