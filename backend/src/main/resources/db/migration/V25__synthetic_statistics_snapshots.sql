CREATE TABLE statistics_grant (
 user_id uuid NOT NULL REFERENCES app_user(id),scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 qualification text NOT NULL CHECK(qualification='SYN-STATS-1'),all_requests boolean NOT NULL DEFAULT false,
 can_drill boolean NOT NULL DEFAULT false,can_report boolean NOT NULL DEFAULT false,
 valid_until timestamptz NOT NULL,revoked_at timestamptz,PRIMARY KEY(user_id,scope_id)
);
CREATE TABLE statistics_resource_grant (
 user_id uuid NOT NULL,scope_id uuid NOT NULL,request_id uuid NOT NULL,valid_until timestamptz NOT NULL,revoked_at timestamptz,
 PRIMARY KEY(user_id,request_id),FOREIGN KEY(user_id,scope_id) REFERENCES statistics_grant(user_id,scope_id),
 FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id)
);
CREATE TABLE statistics_snapshot (
 id uuid PRIMARY KEY,actor_id uuid NOT NULL REFERENCES app_user(id),scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 definition text NOT NULL CHECK(definition='SYN-STATS-1'),from_date date NOT NULL,to_date date NOT NULL,zone text NOT NULL,
 starts_at timestamptz NOT NULL,ends_at timestamptz NOT NULL,cutoff timestamptz NOT NULL,
 permission_mask text NOT NULL, facts_hash text NOT NULL CHECK(facts_hash ~ '^[a-f0-9]{64}$'),fact_count integer NOT NULL CHECK(fact_count BETWEEN 0 AND 5000),
 CHECK(to_date>=from_date AND to_date-from_date<92),CHECK(ends_at>starts_at)
);
CREATE TABLE statistics_fact (
 snapshot_id uuid NOT NULL REFERENCES statistics_snapshot(id),metric text NOT NULL CHECK(metric IN ('RECEPTION','TECHNICAL','REPORT','QC')),
 entity_id uuid NOT NULL,request_id uuid NOT NULL REFERENCES pathology_request(id),
 start_event uuid,end_event uuid,start_at timestamptz,end_at timestamptz,
 status text NOT NULL CHECK(status IN ('COMPLETED','OPEN','UNKNOWN','EXCLUDED','PASS','FAIL')),
 source_basis text NOT NULL, duration_seconds bigint CHECK(duration_seconds>=0),PRIMARY KEY(snapshot_id,metric,entity_id),
 CHECK((status IN ('COMPLETED','OPEN'))=(duration_seconds IS NOT NULL)),CHECK(status<>'OPEN' OR (start_at IS NOT NULL AND end_at IS NULL)),CHECK(status<>'COMPLETED' OR (start_at IS NOT NULL AND end_at IS NOT NULL AND end_at>=start_at))
);
CREATE INDEX ix_statistics_snapshot_actor ON statistics_snapshot(actor_id,scope_id,cutoff);
CREATE TRIGGER trg_statistics_snapshot BEFORE UPDATE OR DELETE ON statistics_snapshot FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_statistics_fact BEFORE UPDATE OR DELETE ON statistics_fact FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
-- Public versioned event projection: identifiers/times/codes only; no clinical text or patient demographics.
CREATE VIEW statistics_source_event_v1 AS
 SELECT 'RECEPTION'::text metric,r.id entity_id,r.id request_id,a.id event_id,a.occurred_at event_at,'START'::text phase,NULL::text outcome,a.result_version version
 FROM audit_event a JOIN pathology_request r ON r.id=a.resource_id WHERE a.operation_code='REQUEST_SUBMIT_V1' AND a.resource_type='PATHOLOGY_REQUEST'
 UNION ALL SELECT 'RECEPTION',e.request_id,e.request_id,e.id,e.occurred_at,'END',NULL,e.request_version FROM reception_event e WHERE e.action='RECEIVE'
 UNION ALL SELECT 'RECEPTION',e.request_id,e.request_id,e.id,e.occurred_at,'EXCLUDE',NULL,e.request_version FROM reception_event e WHERE e.action='RETURN'
 UNION ALL SELECT 'TECHNICAL',t.id,t.request_id,e.id,e.occurred_at,CASE e.action WHEN 'CREATE' THEN 'START' WHEN 'FINISH_SIMULATION' THEN 'END' ELSE 'EXCLUDE' END,NULL,e.task_version FROM technical_event e JOIN technical_task t ON t.id=e.task_id WHERE e.action IN ('CREATE','FINISH_SIMULATION','ABORT')
 UNION ALL SELECT 'REPORT',c.id,c.request_id,e.id,e.occurred_at,'START',NULL,e.request_version FROM reception_event e JOIN pathology_case c ON c.request_id=e.request_id WHERE e.action='RECEIVE'
 UNION ALL SELECT 'REPORT',e.case_id,c.request_id,e.id,e.occurred_at,'END',NULL,e.version FROM report_review_event e JOIN pathology_case c ON c.id=e.case_id WHERE e.action='SIMULATE_SIGN'
 UNION ALL SELECT 'QC',m.id,m.request_id,e.id,e.occurred_at,CASE WHEN e.material_version=0 THEN 'START' ELSE 'CHANGE' END,CASE WHEN e.action IN ('VOID','SOURCE_VOIDED','STAIN_TRANSFER') THEN 'EXCLUDED' ELSE 'UNKNOWN' END,e.material_version FROM material_event e JOIN material_entity m ON m.id=e.material_id
 UNION ALL SELECT 'QC',m.id,m.request_id,e.id,e.occurred_at,'ASSESS',CASE WHEN e.action='ASSESS' THEN CASE a.outcome WHEN 'PASS' THEN 'PASS' WHEN 'FAIL' THEN 'FAIL' WHEN 'IDENTITY_MISMATCH' THEN 'FAIL' ELSE 'UNKNOWN' END ELSE 'UNKNOWN' END,e.version FROM quality_event e JOIN material_entity m ON m.id=e.material_id JOIN quality_assessment a ON a.id=e.assessment_id;

CREATE FUNCTION check_statistics_fact_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM statistics_snapshot s JOIN request_workflow w ON w.scope_id=s.scope_id WHERE s.id=NEW.snapshot_id AND w.request_id=NEW.request_id) THEN
  RAISE EXCEPTION 'Statistics source scope mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_statistics_fact_scope BEFORE INSERT ON statistics_fact FOR EACH ROW EXECUTE FUNCTION check_statistics_fact_scope();
