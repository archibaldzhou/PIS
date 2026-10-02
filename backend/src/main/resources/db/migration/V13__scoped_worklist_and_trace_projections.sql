-- Read projections only. Every application query must join the current workflow grant.
-- No invented report/WSI/AI state and no patient names or free-text clinical payloads.
CREATE VIEW workflow_quality_projection AS
SELECT m.id,m.request_id,m.patient_id,m.cassette_id,m.created_at,m.state AS material_state,
 coalesce(h.version,-1) AS version,
 CASE WHEN m.state<>'ACTIVE' THEN 'INVALIDATED'
 WHEN m.block_id IS NOT NULL AND (b.state<>'ACTIVE'
   OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=b.technical_task_id)
   OR (bh.material_id IS NOT NULL AND (bh.state<>'PASS' OR bh.material_version<>b.version OR bh.task_version IS DISTINCT FROM bt.version))) THEN 'SOURCE_QUARANTINED'
 WHEN h.material_id IS NULL THEN CASE WHEN m.technical_task_id IS NOT NULL AND (t.state<>'SIMULATED_DONE' OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=t.id)) THEN 'INVALIDATED' ELSE 'NOT_ASSESSED' END
 WHEN h.state<>'PASS' THEN h.state
 WHEN h.material_version<>m.version OR h.task_version IS DISTINCT FROM t.version OR (m.technical_task_id IS NOT NULL AND (t.state<>'SIMULATED_DONE' OR EXISTS(SELECT 1 FROM technical_task child WHERE child.rework_of=t.id))) THEN 'INVALIDATED'
 ELSE 'PASS' END AS state
FROM material_entity m LEFT JOIN quality_head h ON h.material_id=m.id LEFT JOIN technical_task t ON t.id=m.technical_task_id
LEFT JOIN material_entity b ON b.id=m.block_id LEFT JOIN quality_head bh ON bh.material_id=b.id LEFT JOIN technical_task bt ON bt.id=b.technical_task_id;

CREATE VIEW workflow_work_item AS
SELECT 'REQUEST'::text AS kind,r.id AS entity_id,r.id AS request_id,r.patient_id,r.request_number,w.scope_id, w.state,r.version,r.created_at,NULL::uuid AS cassette_id,false AS blocked,w.state IN ('DRAFT','SUBMITTED') AS active,'READ'::text AS permission
FROM pathology_request r JOIN request_workflow w ON w.request_id=r.id
UNION ALL
SELECT 'RECEPTION',r.id,r.id,r.patient_id,r.request_number,w.scope_id,w.state,r.version,coalesce(w.submitted_at,r.created_at),NULL::uuid,false,w.state IN ('SUBMITTED','EXCEPTION'),'RECEIVE'
FROM pathology_request r JOIN request_workflow w ON w.request_id=r.id WHERE w.state<>'DRAFT'
UNION ALL
SELECT 'TECHNICAL',t.id,r.id,r.patient_id,r.request_number,w.scope_id,t.state,t.version,t.created_at,t.cassette_id, EXISTS(SELECT 1 FROM quality_head h WHERE h.request_id=t.request_id AND h.cassette_id=t.cassette_id AND h.state<>'PASS' AND (t.rework_of IS NULL OR h.repair_task_id IS DISTINCT FROM t.id)),
 t.state IN ('QUEUED','ACTIVE','HANDOFF_PENDING'),'PROCESS'
FROM technical_task t JOIN pathology_request r ON r.id=t.request_id JOIN request_workflow w ON w.request_id=r.id
UNION ALL
SELECT 'QUALITY',q.id,r.id,r.patient_id,r.request_number,w.scope_id,q.state,q.version,q.created_at,q.cassette_id, q.state NOT IN ('PASS','NOT_ASSESSED'),q.material_state='ACTIVE' AND q.state<>'PASS','QC'
FROM workflow_quality_projection q JOIN pathology_request r ON r.id=q.request_id JOIN request_workflow w ON w.request_id=r.id;

CREATE VIEW workflow_trace_event AS
SELECT a.id AS event_id,r.id AS request_id,'REQUEST'::text AS domain,r.id AS entity_id,a.result_version AS version,a.operation_code AS action, NULL::uuid AS related_id,NULL::text AS related_type,a.occurred_at,'READ'::text AS permission
FROM audit_event a JOIN pathology_request r ON r.id=a.resource_id AND r.hospital_id=a.hospital_id WHERE a.resource_type='PATHOLOGY_REQUEST' AND a.operation_code IN ('REQUEST_CREATE_V1','REQUEST_EDIT_V1','REQUEST_SUBMIT_V1')
UNION ALL
SELECT e.id,e.request_id,'RECEPTION',e.request_id,e.request_version,e.action,NULL::uuid,NULL::text,e.occurred_at,'RECEIVE' FROM reception_event e
UNION ALL
SELECT e.id,g.request_id,'GROSSING',g.id,e.record_version,e.action,e.target_id,'GROSS_RESOURCE',e.occurred_at,'GROSS' FROM gross_event e JOIN gross_record g ON g.id=e.record_id
UNION ALL
SELECT e.id,t.request_id,'TECHNICAL',t.id,e.task_version,e.action,e.related_task_id,'TECHNICAL_TASK',e.occurred_at,'PROCESS' FROM technical_event e JOIN technical_task t ON t.id=e.task_id
UNION ALL
SELECT e.id,m.request_id,'MATERIAL',m.id,e.material_version,e.action,e.related_id,'MATERIAL',e.occurred_at,'MATERIAL' FROM material_event e JOIN material_entity m ON m.id=e.material_id
UNION ALL
SELECT e.id,j.request_id,'LABEL',j.id,e.job_version,e.action,j.parent_job_id,'LABEL_JOB',e.occurred_at,'PRINT' FROM label_job_event e JOIN label_job j ON j.id=e.job_id
UNION ALL
SELECT e.id,h.request_id,'QUALITY',h.material_id,e.version,e.action,coalesce(e.related_task_id,e.assessment_id),CASE WHEN e.related_task_id IS NULL THEN 'QUALITY_ASSESSMENT' ELSE 'TECHNICAL_TASK' END,e.occurred_at,'QC' FROM quality_event e JOIN quality_head h ON h.material_id=e.material_id;
