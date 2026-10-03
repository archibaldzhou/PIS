CREATE TABLE stain_grant (
 scope_id uuid NOT NULL REFERENCES workflow_scope(id), user_id uuid NOT NULL REFERENCES app_user(id), qualification text NOT NULL CHECK(qualification='SYN-STAIN-1'),
 can_request boolean NOT NULL DEFAULT false, can_execute boolean NOT NULL DEFAULT false, can_qc boolean NOT NULL DEFAULT false,
 valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until timestamptz, revoked_at timestamptz,
 PRIMARY KEY(scope_id,user_id), CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TABLE stain_scheme (
 id uuid PRIMARY KEY, scope_id uuid NOT NULL REFERENCES workflow_scope(id), kind text NOT NULL CHECK(kind IN ('SPECIAL','IHC')),
 project_code text NOT NULL CHECK(project_code ~ '^SYN-[A-Z0-9-]{1,40}$'), project_version integer NOT NULL CHECK(project_version BETWEEN 1 AND 9999),
 scheme_code text NOT NULL CHECK(scheme_code ~ '^SYN-[A-Z0-9-]{1,40}$'), scheme_version integer NOT NULL CHECK(scheme_version BETWEEN 1 AND 9999),
 metadata text NOT NULL CHECK(char_length(metadata) BETWEEN 1 AND 1000 AND btrim(metadata)<>''),
 UNIQUE(scope_id,kind,project_code,project_version,scheme_code,scheme_version), UNIQUE(scope_id,id)
);
ALTER TABLE request_workflow ADD UNIQUE(request_id,scope_id);
CREATE TABLE stain_batch (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL, patient_id uuid NOT NULL, scope_id uuid NOT NULL,
 scheme_id uuid NOT NULL, reagent_lot text NOT NULL CHECK(char_length(reagent_lot) BETWEEN 1 AND 120 AND btrim(reagent_lot)<>''), expires_on date NOT NULL,
 control_id uuid NOT NULL UNIQUE, control_reference text NOT NULL CHECK(char_length(control_reference) BETWEEN 1 AND 255 AND btrim(control_reference)<>''),
 rerun_of uuid, state text NOT NULL CHECK(state IN ('DRAFT','FROZEN','PASS','FAIL','REVOKED')), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 frozen_version bigint, control_event_id uuid, created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(hospital_id,request_id,case_id,patient_id,id), UNIQUE(request_id,id), UNIQUE(id,frozen_version),
 FOREIGN KEY(hospital_id,scope_id) REFERENCES workflow_scope(hospital_id,id), FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id),
 FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(scope_id,scheme_id) REFERENCES stain_scheme(scope_id,id), FOREIGN KEY(request_id,rerun_of) REFERENCES stain_batch(request_id,id),
 CHECK(rerun_of IS NULL OR rerun_of<>id), CHECK((state='DRAFT' AND frozen_version IS NULL) OR (state<>'DRAFT' AND frozen_version IS NOT NULL AND frozen_version<=version))
);
CREATE TABLE stain_order (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL, hospital_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL, patient_id uuid NOT NULL,
 source_id uuid NOT NULL, source_version bigint NOT NULL CHECK(source_version>=0), initial_dependency text NOT NULL CHECK(char_length(initial_dependency) BETWEEN 1 AND 16000), created_by uuid NOT NULL REFERENCES app_user(id),
 UNIQUE(batch_id,source_id), UNIQUE(batch_id,id), UNIQUE(id,source_id), UNIQUE(hospital_id,request_id,case_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,patient_id,batch_id) REFERENCES stain_batch(hospital_id,request_id,case_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,patient_id,source_id) REFERENCES material_entity(hospital_id,request_id,case_id,patient_id,id)
);
CREATE TABLE stain_member (
 order_id uuid PRIMARY KEY REFERENCES stain_order(id), batch_id uuid NOT NULL, source_id uuid NOT NULL UNIQUE, output_id uuid NOT NULL UNIQUE,
 frozen_version bigint NOT NULL, source_dependency text NOT NULL CHECK(char_length(source_dependency) BETWEEN 1 AND 16000),
 UNIQUE(order_id,output_id), FOREIGN KEY(order_id,source_id) REFERENCES stain_order(id,source_id),
 FOREIGN KEY(batch_id,frozen_version) REFERENCES stain_batch(id,frozen_version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(batch_id,order_id) REFERENCES stain_order(batch_id,id),
 FOREIGN KEY(output_id) REFERENCES material_entity(id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE stain_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), batch_id uuid NOT NULL REFERENCES stain_batch(id), version bigint NOT NULL,
 action text NOT NULL CHECK(action IN ('CREATE','ADD','FREEZE','CONTROL_PASS','CONTROL_FAIL','REVOKE','RESULT')),
 order_id uuid, frozen_version bigint, control_event_id uuid,
 technical_qc text CHECK(technical_qc IN ('TECH_PASS','TECH_FAIL')),
 content text NOT NULL CHECK(char_length(content)<=4000), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id), recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(batch_id,version), UNIQUE(batch_id,id), FOREIGN KEY(batch_id,order_id) REFERENCES stain_order(batch_id,id),
 FOREIGN KEY(batch_id,control_event_id) REFERENCES stain_event(batch_id,id),
 CHECK(action<>'RESULT' OR (order_id IS NOT NULL AND frozen_version IS NOT NULL AND control_event_id IS NOT NULL AND technical_qc IS NOT NULL AND btrim(content)<>'')),
 CHECK(action NOT IN ('CONTROL_PASS','CONTROL_FAIL','REVOKE') OR btrim(content)<>'')
);
CREATE UNIQUE INDEX uq_stain_result ON stain_event(order_id) WHERE action='RESULT';
ALTER TABLE stain_batch ADD FOREIGN KEY(id,version) REFERENCES stain_event(batch_id,version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE stain_batch ADD FOREIGN KEY(id,control_event_id) REFERENCES stain_event(batch_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ix_stain_batch_request ON stain_batch(request_id,created_at,id);
ALTER TABLE material_entity ADD COLUMN stain_order_id uuid;
ALTER TABLE material_entity ADD FOREIGN KEY(stain_order_id,id) REFERENCES stain_member(order_id,output_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE material_entity ADD FOREIGN KEY(hospital_id,request_id,case_id,patient_id,stain_order_id) REFERENCES stain_order(hospital_id,request_id,case_id,patient_id,id);
ALTER TABLE material_entity DROP CONSTRAINT material_entity_route_check;
ALTER TABLE material_entity ADD CHECK(route IN ('CASSETTE','BLOCK_BASED','DIRECT_CYTOLOGY','CYTOLOGY_BLOCK','CYTOLOGY_SLIDE','STAINED_SLIDE'));
DO $$ DECLARE old_check text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint WHERE conrelid='material_entity'::regclass AND conname='material_entity_check';
 ALTER TABLE material_entity DROP CONSTRAINT material_entity_check;
 EXECUTE 'ALTER TABLE material_entity ADD CONSTRAINT material_entity_check CHECK ((stain_order_id IS NULL AND '||substring(old_check FROM 7)||') OR (stain_order_id IS NOT NULL AND route=''STAINED_SLIDE'' AND kind=''SLIDE'' AND operation=''ORIGINAL'' AND source_slide_id IS NOT NULL AND cytology_preparation_id IS NULL))';
END $$;
ALTER TABLE material_event DROP CONSTRAINT material_event_action_check;
ALTER TABLE material_event ADD CHECK(action IN ('CREATE','DERIVE','RECUT','DEEPER','VOID','SOURCE_VOIDED','STAIN_TRANSFER'));
CREATE FUNCTION stain_source_snapshot(source uuid) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT jsonb_build_object('material',to_jsonb(m),'qc',to_jsonb(h),'task',to_jsonb(t),'block',to_jsonb(b),'blockQc',to_jsonb(bh),'blockTask',to_jsonb(bt),'cytology',to_jsonb(cs),'preparation',to_jsonb(cp))::text
 FROM material_entity m LEFT JOIN quality_head h ON h.material_id=m.id LEFT JOIN technical_task t ON t.id=m.technical_task_id
 LEFT JOIN material_entity b ON b.id=m.block_id LEFT JOIN quality_head bh ON bh.material_id=b.id LEFT JOIN technical_task bt ON bt.id=b.technical_task_id
 LEFT JOIN cytology_preparation cp ON cp.id=m.cytology_preparation_id LEFT JOIN cytology_specimen cs ON cs.id=cp.specimen_id WHERE m.id=source
$$;
CREATE VIEW stain_material_gate AS
 SELECT sm.output_id AS id,o.request_id,
 CASE WHEN om.state='ACTIVE' AND b.state='PASS' AND e.control_event_id=b.control_event_id AND e.frozen_version=b.frozen_version AND e.technical_qc='TECH_PASS'
 AND sm.source_dependency=stain_source_snapshot(sm.source_id) THEN 'PASS' ELSE 'SOURCE_QUARANTINED' END AS state
 FROM stain_member sm JOIN material_entity om ON om.id=sm.output_id JOIN stain_order o ON o.id=sm.order_id JOIN stain_batch b ON b.id=sm.batch_id
 LEFT JOIN stain_event e ON e.order_id=o.id AND e.action='RESULT';
CREATE VIEW stain_quality_base AS
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
CREATE OR REPLACE VIEW workflow_quality_projection AS
 SELECT q.id,q.request_id,q.patient_id,q.cassette_id,q.created_at,q.material_state,q.version,
 CASE WHEN sg.id IS NOT NULL AND sg.state<>'PASS' THEN 'SOURCE_QUARANTINED' ELSE q.state END AS state
 FROM stain_quality_base q LEFT JOIN stain_material_gate sg ON sg.id=q.id;
CREATE FUNCTION protect_stain_batch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable stain batch' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','version','frozen_version','control_event_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','frozen_version','control_event_id']) OR NEW.version<>OLD.version+1
 OR (OLD.frozen_version IS NOT NULL AND NEW.frozen_version IS DISTINCT FROM OLD.frozen_version)
 OR NOT ((OLD.state='DRAFT' AND NEW.state IN ('DRAFT','FROZEN')) OR (OLD.state='FROZEN' AND NEW.state IN ('PASS','FAIL')) OR (OLD.state='PASS' AND NEW.state IN ('PASS','REVOKED'))) THEN RAISE EXCEPTION 'Immutable batch dependency' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER trg_stain_batch BEFORE UPDATE OR DELETE ON stain_batch FOR EACH ROW EXECUTE FUNCTION protect_stain_batch();
CREATE FUNCTION check_stain_material() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE s material_entity; o stain_order; BEGIN
 IF NEW.stain_order_id IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO STRICT o FROM stain_order WHERE id=NEW.stain_order_id; SELECT * INTO STRICT s FROM material_entity WHERE id=o.source_id;
 IF NEW.source_slide_id<>s.id OR s.state<>'VOID' OR s.version<>o.source_version+1 OR s.route='STAINED_SLIDE'
 OR ROW(NEW.block_id,NEW.record_id,NEW.cassette_id,NEW.container_id,NEW.technical_task_id) IS DISTINCT FROM ROW(s.block_id,s.record_id,s.cassette_id,s.container_id,s.technical_task_id)
 THEN RAISE EXCEPTION 'Invalid stain transfer lineage' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER trg_stain_material BEFORE INSERT ON material_entity FOR EACH ROW EXECUTE FUNCTION check_stain_material();
CREATE TABLE stain_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id),
 action text NOT NULL, code text NOT NULL, trace_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY(hospital_id,request_id) REFERENCES pathology_request(hospital_id,id)
);
CREATE TRIGGER trg_stain_scheme_immutable BEFORE UPDATE OR DELETE ON stain_scheme FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_stain_order_immutable BEFORE UPDATE OR DELETE ON stain_order FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_stain_member_immutable BEFORE UPDATE OR DELETE ON stain_member FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_stain_event_immutable BEFORE UPDATE OR DELETE ON stain_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_stain_rejection_immutable BEFORE UPDATE OR DELETE ON stain_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();

CREATE FUNCTION check_stain_order_open() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM stain_batch WHERE id=NEW.batch_id AND state='DRAFT') THEN RAISE EXCEPTION 'Batch members are frozen' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER trg_stain_order_open BEFORE INSERT ON stain_order FOR EACH ROW EXECUTE FUNCTION check_stain_order_open();
