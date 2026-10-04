from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t36-'+uuid.uuid4().hex[:8]
image='postgres@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652'
def run(args,**kw):return subprocess.run(args,text=True,capture_output=True,**kw)
def sql(s,ok=True):
 r=run(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],input=s)
 if ok and r.returncode:raise RuntimeError(r.stderr)
 if not ok and not r.returncode:raise RuntimeError('Expected rejection')
 return r.stdout.strip()
try:
 r=run(['docker','run','-d','--rm','--name',name,'--network','none','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_HOST_AUTH_METHOD=trust',image]);assert r.returncode==0,r.stderr
 for _ in range(60):
  if 'PostgreSQL init process complete' in run(['docker','logs',name]).stdout and run(['docker','exec',name,'pg_isready','-U','postgres']).returncode==0:break
  time.sleep(.2)
 migrations=sorted((root/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
 assert [int(p.name.split('__')[0][1:]) for p in migrations]==list(range(1,38))
 for p in migrations:sql('BEGIN;'+p.read_text().replace('${flyway:defaultSchema}','public')+'COMMIT;')
 ids={k:str(uuid.uuid4()) for k in ['h','p','s','r','c','u','k','m','campus','dept','scope','scheme','b','o','output','control','pass','result','revoke','a','trace']}
 def fmt(s):return s.format(**ids)
 sql(fmt("""
 INSERT INTO hospital(id,code,name) VALUES('{h}','SYN','Synthetic');
 INSERT INTO patient(id,hospital_id,display_name) VALUES('{p}','{h}','Synthetic');
 INSERT INTO source_system(id,hospital_id,code,name) VALUES('{s}','{h}','SYN','Synthetic');
 INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES('{r}','{h}','{p}','{s}','SYN-R');
 INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES('{k}','{h}','{r}','SYN','SYN-K');
 INSERT INTO specimen_container(id,hospital_id,request_id,case_id) VALUES('{c}','{h}','{r}','{k}');
 INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES('{u}','synthetic','Synthetic','unused',true);
 INSERT INTO campus(id,hospital_id,code,name) VALUES('{campus}','{h}','SYN','Synthetic');
 INSERT INTO department(id,hospital_id,code,name) VALUES('{dept}','{h}','SYN','Synthetic');
 INSERT INTO department_campus(hospital_id,campus_id,department_id) VALUES('{h}','{campus}','{dept}');
 INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES('{scope}','{h}','{campus}','{dept}','{s}','Synthetic',true);
 INSERT INTO request_workflow(request_id,hospital_id,scope_id,state,created_by,clinical_history) VALUES('{r}','{h}','{scope}','DRAFT','{u}','Synthetic');
 INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,created_by) VALUES('{m}','{h}','{p}','{r}','{k}','SLIDE','DIRECT_CYTOLOGY','ORIGINAL','DEV-S-SYN','S000000000000000000000000000000001','{c}','{u}');
 INSERT INTO material_event(material_id,material_version,action,reason,actor_id) VALUES('{m}',0,'CREATE','Synthetic','{u}');
 INSERT INTO quality_head(material_id,hospital_id,request_id,case_id,patient_id,material_version,state,version) VALUES('{m}','{h}','{r}','{k}','{p}',0,'PASS',0);
 INSERT INTO quality_assessment(id,material_id,material_version,standard_version,outcome,reason,actor_id) VALUES('{a}','{m}',0,'SYN-MATERIAL-QC-1','PASS','Synthetic','{u}');
 UPDATE quality_head SET assessment_id='{a}' WHERE material_id='{m}';
 """))

 ids.update(asset=str(uuid.uuid4()),version=str(uuid.uuid4()),rootid=str(uuid.uuid4()))
 sql(fmt("INSERT INTO storage_grant(user_id,scope_id,qualification,can_write,valid_until) VALUES('{u}','{scope}','SYN-STORAGE-1',true,statement_timestamp()+interval '1 day')"))
 sql(fmt("INSERT INTO storage_case_grant(user_id,scope_id,case_id,valid_until) VALUES('{u}','{scope}','{k}',statement_timestamp()+interval '1 day')"))
 sql(fmt("INSERT INTO storage_asset(id,hospital_id,request_id,case_id,scope_id) VALUES('{asset}','{h}','{r}','{k}','{scope}')"))
 sql(fmt("UPDATE storage_asset SET head=0 WHERE id='{asset}'"))
 sql(fmt("INSERT INTO storage_version(id,asset_id,hospital_id,request_id,case_id,scope_id,ordinal,root_id,purpose,media_type,byte_size,sha256,actor_id) VALUES('{version}','{asset}','{h}','{r}','{k}','{scope}',0,'{rootid}','SYNTHETIC_ORIGINAL','application/octet-stream',65536,repeat('a',64),'{u}')"))
 assert sql("SELECT count(*) FROM information_schema.columns WHERE table_name LIKE 'storage_%' AND data_type='bytea'")=='0'
 sql("UPDATE storage_version SET sha256=repeat('b',64),version=version+1",False)
 sql("UPDATE storage_version SET state='READY',version=version+1",False)
 sql("DELETE FROM storage_version",False)
 sql("UPDATE storage_version SET state='UPLOADING',version=version+1")
 sql("UPDATE storage_version SET state='STAGED',version=version+1")
 # DB failure rolls back finalize command; file I/O is separately checked by Java probe.
 sql(fmt("BEGIN;UPDATE storage_version SET state='FINALIZING',version=version+1;INSERT INTO storage_finalize(version_id,state) VALUES('{version}','PENDING');ROLLBACK;"))
 assert sql("SELECT state FROM storage_version")=='STAGED'
 assert sql("SELECT count(*) FROM storage_finalize")=='0'
 sql(fmt("BEGIN;UPDATE storage_version SET state='FINALIZING',version=version+1;INSERT INTO storage_finalize(version_id,state) VALUES('{version}','PENDING');COMMIT;"))
 sql("BEGIN;UPDATE storage_version SET state='READY',version=version+1;UPDATE storage_finalize SET state='DONE';COMMIT;")
 sql("UPDATE storage_finalize SET state='PENDING'",False);sql("DELETE FROM storage_finalize",False)
 sql(fmt("INSERT INTO storage_event(version_id,version,action,actor_id) VALUES('{version}',4,'READY','{u}')"))
 sql("UPDATE storage_event SET action='OTHER'",False)
 sql(fmt("INSERT INTO storage_quota(hospital_id,reserved_bytes) VALUES('{h}',402653184)"))

 ids.update(scan=str(uuid.uuid4()),lease=str(uuid.uuid4()))
 sql(fmt("INSERT INTO scan_series(slide_id,head) VALUES('{m}',0)"))
 sql(fmt("INSERT INTO scan_import(id,hospital_id,request_id,case_id,patient_id,scope_id,slide_id,object_id,object_hash,object_size,ordinal,source_basis,barcode,source_code,scanner_code,reason,state,error_code,claimed_patient_id,claimed_case_id) VALUES('{scan}','{h}','{r}','{k}','{p}','{scope}','{m}','{version}',repeat('a',64),65536,0,repeat('b',64),'SYNTHETIC','SYN-LOCAL','SYN-SCANNER','Synthetic','QUEUED','','{p}','{k}')"))
 sql("UPDATE scan_import SET state='PENDING_DIGITAL_QC',version=version+1",False)
 sql(fmt("UPDATE scan_import SET state='RUNNING',version=version+1,attempts=1,lease_id='{lease}',lease_actor='{u}',lease_until=statement_timestamp()+interval '30 seconds'"))
 sql("BEGIN;UPDATE scan_import SET state='PENDING_DIGITAL_QC',version=version+1;ROLLBACK;")
 assert sql('SELECT state FROM scan_import')=='RUNNING'
 sql("UPDATE scan_import SET state='PENDING_DIGITAL_QC',width=32,height=32,version=version+1 WHERE version=1")
 sql(fmt("INSERT INTO digital_qc_head(scan_id) VALUES('{scan}')"))
 assessment=fmt("INSERT INTO digital_qc_assessment(scan_id,version,scan_version,object_id,object_hash,slide_id,source_basis,checklist,coverage,focus,missing,coverage_percent,missing_tiles,regions,note,passed,actor_id) VALUES('{scan}',0,2,'{version}',repeat('a',64),'{m}',repeat('b',64),'SYN-DIGITAL-QC-1','PASS','PASS','PASS',100,0,'[]','Synthetic',true,'{u}')")
 sql(assessment.replace("'PASS','PASS','PASS'","'PASS','UNKNOWN','PASS'"),False)
 sql(assessment.replace("repeat('a',64)","repeat('c',64)"),False)
 sql(assessment)
 sql(fmt("UPDATE digital_qc_head SET version=0,assessment_version=0,state='EVALUATED' WHERE scan_id='{scan}'"))
 sql(fmt("INSERT INTO digital_qc_event(scan_id,version,action,assessment_version,actor_id,reason) VALUES('{scan}',0,'EVALUATE',0,'{u}','Synthetic')"))
 sql("DELETE FROM digital_qc_assessment",False);sql("UPDATE digital_qc_event SET reason='changed'",False)
 sql("UPDATE digital_qc_head SET state='PUBLISHED'",False)
 sql("BEGIN;UPDATE digital_qc_head SET version=1,state='PUBLISHED';ROLLBACK;")
 assert sql("SELECT version||':'||state FROM digital_qc_head")=='0:EVALUATED'
 import concurrent.futures
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda state:sql("UPDATE digital_qc_head SET version=1,state='"+state+"' WHERE version=0 RETURNING state"),['PUBLISHED','REVOKED']))
 assert sum('UPDATE 1' in r for r in results)==1,results
 assert sql("SELECT count(*) FROM digital_qc_assessment")=='1'
 sql("DELETE FROM digital_qc_head",False)
 sql(fmt("INSERT INTO viewer_manifest(scan_id,object_id,object_hash,scan_version,provider,manifest_hash,manifest,created_by) VALUES('{scan}','{version}',repeat('c',64),2,'SYN-RGB-PYRAMID-1',repeat('d',64),'{{}}','{u}')"),False)
 sql(fmt("BEGIN;INSERT INTO viewer_manifest(scan_id,object_id,object_hash,scan_version,provider,manifest_hash,manifest,created_by) VALUES('{scan}','{version}',repeat('a',64),2,'SYN-RGB-PYRAMID-1',repeat('d',64),'{{}}','{u}');ROLLBACK;"))
 assert sql('SELECT count(*) FROM viewer_manifest')=='0'
 sql(fmt("INSERT INTO viewer_manifest(scan_id,object_id,object_hash,scan_version,provider,manifest_hash,manifest,created_by) VALUES('{scan}','{version}',repeat('a',64),2,'SYN-RGB-PYRAMID-1',repeat('d',64),'{{}}','{u}')"))
 sql("UPDATE viewer_manifest SET manifest_hash=repeat('e',64)",False);sql("DELETE FROM viewer_manifest",False)
 sql(fmt("INSERT INTO viewer_read_budget(user_id,minute,requests,bytes) VALUES('{u}',date_trunc('minute',statement_timestamp()),239,0)"))
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql("UPDATE viewer_read_budget SET requests=requests+1,bytes=bytes+0 WHERE requests+1<=240 AND bytes+0<=33554432 RETURNING requests"),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 # A paid physical request still accounts every byte; no extra request unit, no byte-limit bypass.
 assert 'UPDATE 1' in sql('UPDATE viewer_read_budget SET requests=requests+0,bytes=bytes+33554432 WHERE requests+0<=240 AND bytes+33554432<=33554432')
 assert 'UPDATE 0' in sql('UPDATE viewer_read_budget SET requests=requests+0,bytes=bytes+1 WHERE requests+0<=240 AND bytes+1<=33554432')
 assert sql("SELECT requests::text||','||bytes::text FROM viewer_read_budget")=='240,33554432'
 print('PASS viewer quota SQL: concurrent 239→240 admits one; paid bytes remain bounded at 33554432')
 sql(fmt("INSERT INTO roi_head(scan_id,manifest_hash) VALUES('{scan}',repeat('d',64))"))
 sql(fmt("INSERT INTO roi_head(scan_id,manifest_hash) VALUES('{scan}',repeat('f',64))"),False)
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql("UPDATE roi_head SET version=0 WHERE version=-1 RETURNING version"),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 sql(fmt("INSERT INTO roi_calibration(scan_id,version,mpp_x,mpp_y,basis,reason,actor_id) VALUES('{scan}',0,0.5,2,'SYNTHETIC_TEST','Synthetic anisotropic','{u}')"))
 sql(fmt("INSERT INTO roi_calibration(scan_id,version,mpp_x,mpp_y,basis,reason,actor_id) VALUES('{scan}',1,0,2,'SYNTHETIC_TEST','Synthetic invalid','{u}')"),False)
 sql(fmt("BEGIN;UPDATE roi_head SET version=1;INSERT INTO roi_revision(scan_id,roi_id,revision,collection_version,author_id,actor_id,kind,points,deleted,reason,calibration_version,measurement) VALUES('{scan}','{a}',0,1,'{u}','{u}','POINT','[{{\"x\":1,\"y\":2}}]',false,'Synthetic',0,'{{}}');ROLLBACK;"))
 assert sql('SELECT count(*) FROM roi_revision')=='0'
 assert sql('SELECT version FROM roi_head')=='0'
 sql(fmt("UPDATE roi_head SET version=1;INSERT INTO roi_revision(scan_id,roi_id,revision,collection_version,author_id,actor_id,kind,points,deleted,reason,calibration_version,measurement) VALUES('{scan}','{a}',0,1,'{u}','{u}','POINT','[{{\"x\":1,\"y\":2}}]',false,'Synthetic',0,'{{}}');"))
 sql("DELETE FROM roi_revision",False);sql("UPDATE roi_calibration SET mpp_x=1",False);sql("UPDATE roi_head SET version=9",False)
 ids.update(model=str(uuid.uuid4()),modelversion=str(uuid.uuid4()))
 sql(fmt("INSERT INTO ai_model_series(id,scope_id,hospital_id,code) VALUES('{model}','{scope}','{h}','SYN-MODEL')"))
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql("UPDATE ai_model_series SET head=0 WHERE head=-1 RETURNING head"),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 sql(fmt("INSERT INTO ai_model_version(id,model_id,ordinal,metadata,digest,actor_id,reason) VALUES('{modelversion}','{model}',0,jsonb_build_object('digest',repeat('a',64)),repeat('a',64),'{u}','Synthetic')"))
 sql(fmt("INSERT INTO ai_model_state(version_id) VALUES('{modelversion}')"))
 sql("UPDATE ai_model_state SET state='APPROVED',revision=1",False)
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql("UPDATE ai_model_state SET state='VALIDATION_ONLY',revision=1 WHERE revision=0 RETURNING revision"),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 sql("BEGIN;UPDATE ai_model_state SET state='DISABLED',revision=2;ROLLBACK;")
 assert sql("SELECT state FROM ai_model_state")=='VALIDATION_ONLY'
 sql("UPDATE ai_model_state SET state='RETIRED',revision=2")
 sql("UPDATE ai_model_state SET state='VALIDATION_ONLY',revision=3",False)
 sql("UPDATE ai_model_version SET digest=repeat('b',64)",False)
 sql("DELETE FROM ai_model_version",False)
 sql(fmt("INSERT INTO ai_scan_profile(scan_id,version,publication_version,manifest_hash,profile,actor_id,reason) VALUES('{scan}',0,1,repeat('d',64),'{{}}','{u}','Synthetic')"))
 sql("UPDATE ai_scan_profile SET profile='{}'",False)
 sql(fmt("INSERT INTO ai_assessment(id,scope_id,scan_id,profile_version,model_version_id,snapshot,reason,actor_id) VALUES(gen_random_uuid(),'{scope}','{scan}',0,'{modelversion}','{{}}','Synthetic','{u}')"),False)
 sql(fmt("INSERT INTO ai_assessment(id,scope_id,scan_id,profile_version,model_version_id,snapshot,reason,actor_id) VALUES(gen_random_uuid(),'{scope}','{scan}',0,'{modelversion}',jsonb_build_object('scanId','{scan}','scopeId','{scope}','modelVersionId','{modelversion}','profileVersion',0,'executionAllowed',false),'Synthetic','{u}')"))
 sql("UPDATE ai_assessment SET snapshot='{}'",False);sql("DELETE FROM ai_assessment",False)
 assessment=sql("SELECT id FROM ai_assessment LIMIT 1")
 task=str(uuid.uuid4());lease=str(uuid.uuid4())
 sql(fmt("INSERT INTO ai_task(id,request_id,scope_id,hospital_id,scan_id,assessment_id,owner_id,owner_auth_version,binding,binding_hash,worker_schema,reason,state) VALUES('TASK','{r}','{scope}','{h}','{scan}','ASSESS','{u}',0,'{{}}',repeat('a',64),'SYN-CONTRACT-WORKER-1','Synthetic','QUEUED')").replace('TASK',task).replace('ASSESS',assessment))
 sql(f"INSERT INTO ai_task_outbox(task_id,state) VALUES('{task}','PENDING')")
 sql(f"UPDATE ai_task SET binding_hash=repeat('b',64),version=version+1 WHERE id='{task}'",False)
 sql(f"BEGIN;UPDATE ai_task SET state='CANCELLED',version=version+1 WHERE id='{task}';UPDATE ai_task_outbox SET state='DONE' WHERE task_id='{task}';ROLLBACK;")
 assert sql(f"SELECT state||':'||version FROM ai_task WHERE id='{task}'")=='QUEUED:0'
 command=f"UPDATE ai_task SET state='RUNNING',version=version+1,generation=1,lease_id='{lease}',lease_actor='{ids['u']}',lease_until=statement_timestamp()+interval '30 second',deadline=statement_timestamp()+interval '120 second' WHERE id='{task}' AND version=0 RETURNING id;"
 from concurrent.futures import ThreadPoolExecutor
 with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:sql(command),range(2)))
 assert sorted(task in x.splitlines() for x in results)==[False,True]
 sql(f"UPDATE ai_task SET state='CANCELLED',version=version+1 WHERE id='{task}'")
 sql(f"UPDATE ai_task SET state='RUNNING',version=version+1 WHERE id='{task}'",False)
 sql(f"DELETE FROM ai_task WHERE id='{task}'",False)
 # SQL-only V33 invariants. Synthetic rows are not application/worker execution evidence.
 result_id=str(uuid.uuid4());artifact=str(uuid.uuid4());worker=str(uuid.uuid4());accepted=str(uuid.uuid4())
 for ordinal,object_id,purpose in [(1,worker,'SYNTHETIC_WORKER_ARTIFACT'),(2,artifact,'SYNTHETIC_RESULT_ARTIFACT')]:
  sql(fmt("INSERT INTO storage_version(id,asset_id,hospital_id,request_id,case_id,scope_id,ordinal,root_id,purpose,media_type,byte_size,sha256,actor_id,state) VALUES('OBJECT','{asset}','{h}','{r}','{k}','{scope}',ORDINAL,'{rootid}','PURPOSE','application/octet-stream',256,repeat('a',64),'{u}','READY')").replace('OBJECT',object_id).replace('ORDINAL',str(ordinal)).replace('PURPOSE',purpose))
 sql(fmt("INSERT INTO ai_task(id,request_id,scope_id,hospital_id,scan_id,assessment_id,owner_id,owner_auth_version,binding,binding_hash,worker_schema,reason,state,artifact_id,version) VALUES('ACCEPTED','{r}','{scope}','{h}','{scan}','ASSESS','{u}',0,'{{}}',repeat('a',64),'SYN-CONTRACT-WORKER-1','Synthetic','SYNTHETIC_SUCCEEDED','WORKER',2)").replace('ACCEPTED',accepted).replace('ASSESS',assessment).replace("'WORKER'","'"+worker+"'"))
 def insert_result(id,source,version=2):return fmt("INSERT INTO ai_result(id,task_id,request_id,scope_id,hospital_id,scan_id,owner_id,task_version,source_hash,binding,generator,artifact_hash,artifact_size) VALUES('RESULT','SOURCE','{r}','{scope}','{h}','{scan}','{u}',VERSION,repeat('a',64),'{{}}','SYN-OVERLAY-1',repeat('a',64),256)").replace('RESULT',id).replace('SOURCE',source).replace('VERSION',str(version))
 sql(insert_result(str(uuid.uuid4()),task),False)
 sql(insert_result(str(uuid.uuid4()),accepted,1),False)
 sql(insert_result(result_id,accepted))
 sql(insert_result(str(uuid.uuid4()),accepted),False)
 sql(f"BEGIN;UPDATE ai_result SET state='READY',version=1,artifact_id='{artifact}' WHERE id='{result_id}';ROLLBACK;")
 assert sql(f"SELECT state FROM ai_result WHERE id='{result_id}'")=='BUILDING'
 sql(f"UPDATE ai_result SET state='READY',version=1,artifact_id='{worker}' WHERE id='{result_id}'",False)
 command=f"UPDATE ai_result SET state='READY',version=1,artifact_id='{artifact}' WHERE id='{result_id}' AND version=0 RETURNING id"
 with ThreadPoolExecutor(max_workers=2) as pool:outcomes=list(pool.map(lambda _:sql(command),range(2)))
 assert sorted(result_id in x.splitlines() for x in outcomes)==[False,True]
 sql(f"UPDATE ai_result SET artifact_hash=repeat('b',64) WHERE id='{result_id}'",False)
 sql(f"DELETE FROM ai_result WHERE id='{result_id}'",False)
 # V34: exact manual-field reference, CAS and atomic rollback; no clinical text generation.
 original=str(uuid.uuid4());adopted=str(uuid.uuid4());event=str(uuid.uuid4())
 sql("INSERT INTO report_template(code,version,title,schema_code) VALUES('SYN-REFERENCE',1,'Synthetic','SYN-TEXT-1')")
 for rev,ver in [(original,0),(adopted,1)]:
  sql(fmt("INSERT INTO report_revision(id,case_id,version,template_code,template_version,fields,assignment_version,author_id,reason) VALUES('REV','{k}',VER,'SYN-REFERENCE',1,'{{}}',0,'{u}','Synthetic unchanged')").replace('REV',rev).replace('VER',str(ver)))
 sql(fmt("INSERT INTO report_result_head(case_id,result_id) VALUES('{k}','RESULT')").replace('RESULT',result_id))
 advance=fmt("UPDATE report_result_head SET version=version+1 WHERE case_id='{k}' AND result_id='RESULT' AND version=-1 RETURNING version").replace('RESULT',result_id)
 with ThreadPoolExecutor(max_workers=2) as pool:winners=list(pool.map(lambda _:sql(advance),range(2)))
 assert sorted('0' in x.splitlines() for x in winners)==[False,True]
 insert=fmt("INSERT INTO report_result_decision(id,case_id,result_id,version,target_revision_id,target_version,assignment_version,action,reason,actor_id,binding,artifact_hash,adopted_revision_id) VALUES('EVENT','{k}','RESULT',0,'ORIGINAL',0,0,'ACCEPT_REFERENCE','Synthetic explicit','{u}','{{}}',repeat('a',64),'ADOPTED')").replace('EVENT',event).replace('RESULT',result_id).replace('ORIGINAL',original).replace('ADOPTED',adopted)
 sql('BEGIN;'+insert+';ROLLBACK;');assert sql('SELECT count(*) FROM report_result_decision')=='0'
 sql(insert.replace("repeat('a',64)","repeat('b',64)"),False)
 sql(insert.replace("'"+adopted+"'","'"+original+"'"),False)
 sql(insert);sql("UPDATE report_result_decision SET reason='Changed'",False);sql('DELETE FROM report_result_decision',False)
 assert sql('SELECT count(*) FROM report_result_decision')=='1'
 # V35: immutable, exact-decision impact reviews; current validity is application checked.
 import json
 review=str(uuid.uuid4());snapshot=json.dumps({'decisionId':event,'resultId':result_id,'epoch':'c'*64})
 sql(f"INSERT INTO report_impact_head(decision_id) VALUES('{event}')")
 cas=f"UPDATE report_impact_head SET version=version+1 WHERE decision_id='{event}' AND version=-1 RETURNING version"
 with ThreadPoolExecutor(max_workers=2) as pool:winners=list(pool.map(lambda _:sql(cas),range(2)))
 assert sorted('0' in x.splitlines() for x in winners)==[False,True]
 statement=f"INSERT INTO report_impact_review(id,decision_id,case_id,result_id,version,snapshot_hash,snapshot,action,reason,actor_id) VALUES('{review}','{event}','{ids['k']}','{result_id}',0,repeat('c',64),'{snapshot}'::jsonb,'ACKNOWLEDGE','Synthetic known invalid','{ids['u']}')"
 sql(statement.replace("repeat('c',64)","repeat('d',64)"),False)
 sql('BEGIN;'+statement+';ROLLBACK;');assert sql('SELECT count(*) FROM report_impact_review')=='0'
 sql(statement);sql(statement,False);sql("UPDATE report_impact_review SET reason='Changed'",False);sql('DELETE FROM report_impact_review',False)
 assert sql('SELECT count(*) FROM report_result_decision')=='1'
 # V36 synthetic adapter source binding, receipt uniqueness, CAS and rollback.
 message=str(uuid.uuid4());external='SYN-PROBE'
 insert=fmt("INSERT INTO adapter_message(id,request_id,case_id,patient_id,hospital_id,scope_id,source_id,adapter,external_id,sequence,schema_code,payload,payload_hash,actor_id) VALUES('MESSAGE','{r}','{k}','{p}','{h}','{scope}','{s}','HIS','SYN-PROBE',1,'SYN-HOSPITAL-1','{{}}',repeat('a',64),'{u}')").replace('MESSAGE',message)
 sql(insert);sql(insert.replace(message,str(uuid.uuid4())),False)
 sql(insert.replace(message,str(uuid.uuid4())).replace('SYN-PROBE','SYN-WRONG').replace(ids['p'],str(uuid.uuid4())),False)
 sql(f"INSERT INTO adapter_outbox(message_id) VALUES('{message}')")
 cas=f"UPDATE adapter_outbox SET version=version+1,state='ATTEMPTING',attempts=1,attempt_id=gen_random_uuid(),lease_until=statement_timestamp()+interval '30 seconds' WHERE message_id='{message}' AND version=0 RETURNING version"
 with ThreadPoolExecutor(max_workers=2) as pool:winners=list(pool.map(lambda _:sql(cas),range(2)))
 assert sorted('1' in x.splitlines() for x in winners)==[False,True]
 inbox=f"INSERT INTO adapter_inbox(message_id,payload_hash) VALUES('{message}',repeat('a',64))"
 sql(inbox.replace("repeat('a',64)","repeat('b',64)"),False)
 sql('BEGIN;'+inbox+';ROLLBACK;');assert sql('SELECT count(*) FROM adapter_inbox')=='0'
 sql(inbox);sql(inbox,False);sql("UPDATE adapter_message SET sequence=2",False);sql('DELETE FROM adapter_inbox',False)
 print('PASS V36: exact core/source identity, source-ID deduplication, exclusive outbox CAS, receipt digest and uniqueness, rollback, append-only history; SQL only')
 print('PASS V35: impact snapshot binding, exclusive CAS, rollback, immutable review and untouched original decision')
 print('PASS V34: exact result/hash/manual-fields binding, exclusive head CAS, rollback, append-only decisions')
 print('PASS PG17 V1-V33: result accepted-source/version/purpose binding, unique identity, READY CAS, rollback and immutable history; prior task/storage/scan/QC/ROI/AI invariants. SQL-only, not application E2E.')
finally:
 run(['docker','rm','-f',name])
