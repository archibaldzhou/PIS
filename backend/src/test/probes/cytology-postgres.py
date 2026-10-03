# Run from any directory with Python 3 + Docker. Uses only synthetic data and a network-isolated disposable PG17.
# SQL/protocol probe, NOT Spring, Flyway, HTTP or complete backend verification.
from pathlib import Path
import subprocess,time,uuid
root=Path(__file__).resolve().parents[4]
name='pis-t23-'+uuid.uuid4().hex[:8]
image='postgres@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652'
def run(args,**kw):return subprocess.run(args,text=True,capture_output=True,**kw)
def sql(s,ok=True):
 r=run(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],input=s)
 if ok and r.returncode:raise RuntimeError(r.stderr)
 if not ok and not r.returncode:raise RuntimeError('Expected rejection')
 return r.stdout.strip()
try:
 r=run(['docker','run','-d','--rm','--name',name,'--network','none','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_HOST_AUTH_METHOD=trust',image]);assert r.returncode==0,r.stderr
 for _ in range(40):
  if 'PostgreSQL init process complete' in run(['docker','logs',name]).stdout and run(['docker','exec',name,'pg_isready','-U','postgres']).returncode==0:break
  time.sleep(.25)
 files=sorted((root/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
 for p in files:
  if int(p.name.split('__')[0][1:])>=17:continue
  sql('BEGIN;'+p.read_text().replace('${flyway:defaultSchema}','public')+'COMMIT;')
 ids={k:str(uuid.uuid4()) for k in ['h','p','s','r','c','u','k','m','j']}
 ids['k']='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
 def fmt(s):return s.format(**ids)
 sql(fmt("""
 INSERT INTO hospital(id,code,name) VALUES('{h}','SYN','Synthetic');
 INSERT INTO patient(id,hospital_id,display_name) VALUES('{p}','{h}','Synthetic');
 INSERT INTO source_system(id,hospital_id,code,name) VALUES('{s}','{h}','SYN','Synthetic');
 INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES('{r}','{h}','{p}','{s}','SYN-R');
 INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES('{k}','{h}','{r}','SYN','SYN-K');
 INSERT INTO specimen_container(id,hospital_id,request_id,case_id) VALUES('{c}','{h}','{r}','{k}');
 INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES('{u}','synthetic','Synthetic','unused',true);
 INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,created_by) VALUES('{m}','{h}','{p}','{r}','{k}','SLIDE','DIRECT_CYTOLOGY','ORIGINAL','DEV-S-SYN','S000000000000000000000000000000001','{c}','{u}');
 INSERT INTO material_event(material_id,material_version,action,reason,actor_id) VALUES('{m}',0,'CREATE','Synthetic','{u}');
 INSERT INTO label_identity(container_id,hospital_id,request_id,barcode) VALUES('{c}','{h}','{r}','S000000000000000000000000000000002');
 INSERT INTO label_job(id,hospital_id,request_id,container_id,barcode,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by) VALUES('{j}','{h}','{r}','{c}','S000000000000000000000000000000002','SYN-CONTAINER-1','PREVIEW_READY',0,0,'{p}','Synthetic','SYN-E','SYN-R','SYN-K','Synthetic','UNKNOWN','Synthetic','{u}');
 """))

 for n in range(17,21):sql('BEGIN;'+next(p for p in files if p.name.startswith('V'+str(n)+'__')).read_text()+'COMMIT;')
 old=sql('SELECT to_jsonb(m)::text FROM material_entity m;');label=sql('SELECT to_jsonb(j)::text FROM label_job j;')
 sql('BEGIN;'+next(p for p in files if p.name.startswith('V21__')).read_text()+'COMMIT;')
 assert sql("SELECT (to_jsonb(m)-'cytology_preparation_id')::text FROM material_entity m;")==old
 assert sql('SELECT to_jsonb(j)::text FROM label_job j;')==label
 assert sql('SELECT count(*) FROM cytology_grant')=='0'
 ids.update({k:str(uuid.uuid4()) for k in ['spec','prep','container2','slide','block']})
 sql(fmt("INSERT INTO specimen_container(id,hospital_id,request_id,case_id) VALUES('{container2}','{h}','{r}','{k}');"))
 sql(fmt("BEGIN; INSERT INTO cytology_specimen(id,hospital_id,patient_id,request_id,case_id,container_id,sample_description,unit,initial_quantity,remaining,version,qc_state,qc_version) VALUES('{spec}','{h}','{p}','{r}','{k}','{container2}','Synthetic','SYN_PORTION',3,3,0,'PENDING',-1); INSERT INTO cytology_event(specimen_id,version,action,reason,actor_id) VALUES('{spec}',0,'REGISTER','Synthetic','{u}');COMMIT;"))
 sql(fmt("BEGIN; UPDATE cytology_specimen SET qc_state='PASS',qc_version=0,version=1;INSERT INTO cytology_event(specimen_id,version,action,reason,actor_id) VALUES('{spec}',1,'QC_PASS','Synthetic','{u}');COMMIT;"))
 sql(fmt("BEGIN; UPDATE cytology_specimen SET remaining=1,version=2;INSERT INTO cytology_preparation(id,specimen_id,hospital_id,request_id,case_id,path,metadata,transferred,source_qc_version,state,version,created_by) VALUES('{prep}','{spec}','{h}','{r}','{k}','DIRECT_SMEAR','Synthetic method',2,0,'RESERVED',0,'{u}');INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,reason,actor_id) VALUES('{spec}',2,'{prep}','PREPARE',2,'Synthetic','{u}');COMMIT;"))
 sql("UPDATE cytology_preparation SET metadata='changed',version=1",False)
 sql("UPDATE cytology_event SET reason='changed'",False)
 sql("DELETE FROM cytology_preparation",False)
 sql(fmt("BEGIN;UPDATE cytology_preparation SET state='COMPLETED',version=1;UPDATE cytology_specimen SET remaining=2,version=3;INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,consumed,discarded,returned,slides,reason,actor_id) VALUES('{spec}',3,'{prep}','COMPLETE',2,1,0,1,1,'Synthetic','{u}');COMMIT;"))
 # Exact production material insert, including nullable block and stable prep source.
 import re,json
 source=(root/'backend/src/main/java/com/pis/material/CytologyService.java').read_text()
 query=json.loads(re.search(r'("INSERT INTO material_entity[^\n]*?")',source).group(1))
 def statement(query,args):
  parts=query.split('?');q=parts[0]+''.join('$'+str(i)+p for i,p in enumerate(parts[1:],1))
  return 'PREPARE probe AS '+q+';EXECUTE probe('+args+');'
 args=fmt("'{slide}','{h}','{p}','{r}','{k}','SLIDE','CYTOLOGY_SLIDE','DEV-S-Synthetic','S000000000000000000000000000000003','{container2}',NULL,'{prep}','{u}'")
 sql(statement(query,args))
 assert sql(fmt("SELECT state FROM cytology_material_gate WHERE id='{slide}'"))=='PASS'
 sql(fmt("BEGIN;UPDATE cytology_specimen SET qc_state='FAIL',qc_version=1,version=4;INSERT INTO cytology_event(specimen_id,version,action,reason,actor_id) VALUES('{spec}',4,'QC_FAIL','Synthetic','{u}');COMMIT;"))
 assert sql(fmt("SELECT state FROM workflow_quality_projection WHERE id='{slide}'"))=='SOURCE_QUARANTINED'
 sql(fmt("BEGIN;UPDATE cytology_specimen SET qc_state='PASS',qc_version=2,version=5;INSERT INTO cytology_event(specimen_id,version,action,reason,actor_id) VALUES('{spec}',5,'QC_PASS','Synthetic','{u}');COMMIT;"))
 assert sql(fmt("SELECT state FROM cytology_material_gate WHERE id='{slide}'"))=='SOURCE_QUARANTINED'
 sql('UPDATE cytology_specimen SET remaining=4,version=6',False)
 sql(fmt("INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,consumed,returned,slides,reason,actor_id) VALUES('{spec}',6,'{prep}','COMPLETE',2,2,1,2,'Synthetic','{u}');"),False)
 # Two independent transactions wait on the same application root lock, then CAS the source head.
 blocker=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 blocker.stdin.write(fmt("BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;\n"));blocker.stdin.flush()
 assert blocker.stdout.readline().strip()=='BEGIN';assert blocker.stdout.readline().strip()==ids['r']
 racers=[]
 for n in range(2):
  p=str(uuid.uuid4())
  command=fmt("SET application_name='cytology-race';BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;UPDATE cytology_specimen SET remaining=remaining-1,version=6 WHERE id='{spec}' AND version=5 AND remaining>=1;DO $$ BEGIN IF (SELECT version FROM cytology_specimen WHERE id='{spec}')<>6 OR EXISTS(SELECT 1 FROM cytology_event WHERE specimen_id='{spec}' AND version=6) THEN RAISE EXCEPTION 'VERSION_CONFLICT'; END IF; END $$;")
  command+=fmt("INSERT INTO cytology_preparation(id,specimen_id,hospital_id,request_id,case_id,path,metadata,transferred,source_qc_version,state,version,created_by) VALUES('")+p+fmt("','{spec}','{h}','{r}','{k}','LIQUID_BASED','Synthetic liquid medium and method',1,2,'RESERVED',0,'{u}');INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,reason,actor_id) VALUES('{spec}',6,'")+p+fmt("','PREPARE',1,'Synthetic race','{u}');COMMIT;")
  proc=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);proc.stdin.write(command);proc.stdin.close();racers.append(proc)
 observed=False
 for _ in range(80):
  if sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='cytology-race' AND wait_event_type='Lock'")=='2':observed=True;break
  time.sleep(.025)
 blocker.stdin.write('COMMIT;\n');blocker.stdin.close();blocker.wait(timeout=5)
 assert observed,'Root lock contention not observed'
 results=[p.wait(timeout=5) for p in racers];assert sorted(results)==[0,3],results
 assert sql(fmt("SELECT count(*) FROM cytology_preparation WHERE specimen_id='{spec}'"))=='2'
 ids['raceprep']=sql(fmt("SELECT id FROM cytology_preparation WHERE specimen_id='{spec}' AND state='RESERVED'"))
 sql(fmt("BEGIN;UPDATE cytology_preparation SET state='FAILED',version=1 WHERE id='{raceprep}';UPDATE cytology_specimen SET remaining=remaining+1,version=7 WHERE id='{spec}';INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,returned,reason,actor_id) VALUES('{spec}',7,'{raceprep}','FAIL',1,1,'Synthetic failure accounted','{u}');COMMIT;"))
 # Audit failure rolls back stock, new prep/event and attempted audit in one PG transaction.
 before=sql("SELECT jsonb_build_object('source',(SELECT jsonb_agg(to_jsonb(s)) FROM cytology_specimen s),'prep',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM cytology_preparation p),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM cytology_event e))::text")
 sql("CREATE FUNCTION reject_cyto_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic audit outage';END $$;CREATE TRIGGER reject_cyto_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_cyto_audit();")
 ids['attempt']=str(uuid.uuid4());ids['trace']=str(uuid.uuid4())
 sql(fmt("BEGIN;UPDATE cytology_specimen SET remaining=remaining-1,version=8 WHERE id='{spec}';INSERT INTO cytology_preparation(id,specimen_id,hospital_id,request_id,case_id,path,metadata,transferred,source_qc_version,state,version,created_by) VALUES('{attempt}','{spec}','{h}','{r}','{k}','DIRECT_SMEAR','Synthetic',1,2,'RESERVED',0,'{u}');INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,reason,actor_id) VALUES('{spec}',8,'{attempt}','PREPARE',1,'Synthetic','{u}');INSERT INTO audit_event(id,actor_user_id,actor_auth_version,hospital_id,operation_code,resource_type,resource_id,result_version,trace_id) VALUES(gen_random_uuid(),'{u}',0,'{h}','CYTOLOGY_PREPARE_V1','CYTOLOGY_CONTAINER','{container2}',8,'{trace}');COMMIT;"),False)
 after=sql("SELECT jsonb_build_object('source',(SELECT jsonb_agg(to_jsonb(s)) FROM cytology_specimen s),'prep',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM cytology_preparation p),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM cytology_event e))::text");assert before==after
 sql('DROP TRIGGER reject_cyto_audit ON audit_event;DROP FUNCTION reject_cyto_audit();')
 for n,path in enumerate(['LIQUID_BASED','CELL_BLOCK']):
  ids['nextprep']=str(uuid.uuid4());ids['nextslide']=str(uuid.uuid4());ids['nextblock']=str(uuid.uuid4());version=8+n*2
  sql(fmt("BEGIN;UPDATE cytology_specimen SET remaining=remaining-1,version=")+str(version)+fmt(" WHERE id='{spec}';INSERT INTO cytology_preparation(id,specimen_id,hospital_id,request_id,case_id,path,metadata,transferred,source_qc_version,repeat_of,state,version,created_by) VALUES('{nextprep}','{spec}','{h}','{r}','{k}','")+path+fmt("','Synthetic method and medium',1,2,'{prep}','RESERVED',0,'{u}');INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,reason,actor_id) VALUES('{spec}',")+str(version)+fmt(",'{nextprep}','PREPARE',1,'Synthetic repeat','{u}');COMMIT;"))
  body=fmt("BEGIN;UPDATE cytology_preparation SET state='COMPLETED',version=1 WHERE id='{nextprep}';UPDATE cytology_specimen SET version=")+str(version+1)+fmt(" WHERE id='{spec}';INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,consumed,slides,reason,actor_id) VALUES('{spec}',")+str(version+1)+fmt(",'{nextprep}','COMPLETE',1,1,1,'Synthetic','{u}');")
  if path=='CELL_BLOCK':body+=statement(query,fmt("'{nextblock}','{h}','{p}','{r}','{k}','BLOCK','CYTOLOGY_BLOCK','DEV-B-{nextblock}','")+('S'+uuid.uuid4().hex.upper()+'0')+fmt("','{container2}',NULL,'{nextprep}','{u}'"))+'DEALLOCATE probe;'
  block="NULL" if path=='LIQUID_BASED' else "'"+ids['nextblock']+"'"
  body+=statement(query,fmt("'{nextslide}','{h}','{p}','{r}','{k}','SLIDE','CYTOLOGY_SLIDE','DEV-S-{nextslide}','")+('S'+uuid.uuid4().hex.upper()+'0')+fmt("','{container2}',")+block+fmt(",'{nextprep}','{u}'"))+'COMMIT;'
  sql(body)
  assert sql(fmt("SELECT state FROM cytology_material_gate WHERE id='{nextslide}'"))=='PASS'
  invalid=fmt("'00000000-0000-4000-8000-000000000023','{h}','{p}','{r}','{k}','SLIDE','CYTOLOGY_SLIDE','DEV-S-Bad','S000000000000000000000000000000009','{container2}',")+("'"+ids['nextslide']+"'" if path=='LIQUID_BASED' else 'NULL')+fmt(",'{nextprep}','{u}'")
  sql(statement(query,invalid),False)
 assert sql(fmt("SELECT remaining FROM cytology_specimen WHERE id='{spec}'"))=='0'
 sql('BEGIN;'+(root/'backend/src/main/resources/db/migration/V22__stain_batches_and_controls.sql').read_text()+'COMMIT;')
 # A source isolated before producing any slides must still block the whole case.
 quality=(root/'backend/src/main/java/com/pis/quality/QualityGate.java').read_text()
 snapshot=json.loads(re.search(r'("SELECT jsonb_build_object[^\n]*?"),String.class,request,request,request,request,request',quality).group(1))
 bound=','.join("'"+ids['r']+"'" for _ in range(5))
 old_snapshot=sql(statement(snapshot,bound))
 sql(fmt("BEGIN;UPDATE cytology_specimen SET qc_state='IDENTITY_MISMATCH',qc_version=3,version=12 WHERE id='{spec}';INSERT INTO cytology_event(specimen_id,version,action,reason,actor_id) VALUES('{spec}',12,'IDENTITY_MISMATCH','Synthetic isolation','{u}');COMMIT;"))
 assert old_snapshot!=sql(statement(snapshot,bound))
 assert sql(fmt("SELECT state FROM workflow_quality_projection WHERE id='{m}'"))=='SOURCE_QUARANTINED'
 sql("UPDATE cytology_preparation SET state='RESERVED',version=version+1 WHERE state='COMPLETED'",False)
 sql("UPDATE cytology_specimen SET qc_state='PASS',qc_version=qc_version+1,version=version+1",False)
 print('PG17 observed concurrent root-lock/CAS, audit rollback, failure returns, repeats, liquid/cell-block paths and path-bypass rejection: PASS')
 print('PG17 V1–V21 SQL, legacy row/label preservation, direct no-block lineage, reservation/reconciliation, immutable history and stale source QC: PASS')
finally:
 r=run(['docker','rm','-f',name]);print('Temporary container cleanup:',r.returncode)
