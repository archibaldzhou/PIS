from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t28-'+uuid.uuid4().hex[:8]
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
 # Two independent sessions contend on quota; only one 128MiB reservation fits.
 update=fmt("UPDATE storage_quota SET reserved_bytes=reserved_bytes+134217728 WHERE hospital_id='{h}' AND reserved_bytes+134217728<=536870912 RETURNING reserved_bytes;")
 import concurrent.futures
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql(update),range(2)))
 assert sum('536870912' in r for r in results)==1,results
 assert sql('SELECT reserved_bytes FROM storage_quota')=='536870912'
 # Same-head competing new versions cannot both advance the asset.
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  results=list(pool.map(lambda _:sql(fmt("UPDATE storage_asset SET head=head+1 WHERE id='{asset}' AND head=0 RETURNING head")),range(2)))
 assert sum('UPDATE 1' in r for r in results)==1,results
 sql("UPDATE storage_asset SET head=0",False)
 print('PASS PG17 V1-V26: exact migration sequence, no bytea, immutable metadata/history, outbox rollback, quota contention, head CAS')
finally:
 run(['docker','rm','-f',name])
