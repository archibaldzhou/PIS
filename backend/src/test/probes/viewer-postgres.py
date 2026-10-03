from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t31-'+uuid.uuid4().hex[:8]
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
 for p in sorted((root/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:])):sql('BEGIN;'+p.read_text().replace('${flyway:defaultSchema}','public')+'COMMIT;')
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
  results=list(pool.map(lambda _:sql("UPDATE viewer_read_budget SET requests=requests+1 WHERE requests<240 RETURNING requests"),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 print('PASS PG17 V1-V29: immutable viewer bindings, rollback, exact source hash and concurrent read budget cap')
finally:
 run(['docker','rm','-f',name])
