from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t24-'+uuid.uuid4().hex[:8]
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
 assert sql('SELECT count(*) FROM stain_grant')=='0'
 sql(fmt("""BEGIN;
 INSERT INTO stain_scheme(id,scope_id,kind,project_code,project_version,scheme_code,scheme_version,metadata) VALUES('{scheme}','{scope}','IHC','SYN-P',1,'SYN-S',1,'Synthetic metadata only');
 INSERT INTO stain_batch(id,hospital_id,request_id,case_id,patient_id,scope_id,scheme_id,reagent_lot,expires_on,control_id,control_reference,state,version,created_by) VALUES('{b}','{h}','{r}','{k}','{p}','{scope}','{scheme}','SYN-LOT','2099-12-31','{control}','Synthetic non-patient control','DRAFT',0,'{u}');
 INSERT INTO stain_order(id,batch_id,hospital_id,request_id,case_id,patient_id,source_id,source_version,initial_dependency,created_by) VALUES('{o}','{b}','{h}','{r}','{k}','{p}','{m}',0,stain_source_snapshot('{m}'),'{u}');
 INSERT INTO stain_event(batch_id,version,action,content,reason,actor_id) VALUES('{b}',0,'CREATE','','Synthetic','{u}');COMMIT;"""))
 sql("UPDATE stain_scheme SET metadata='changed'",False)
 import json,re
 source=(root/'backend/src/main/java/com/pis/material/StainService.java').read_text()
 query=json.loads(re.search(r'("INSERT INTO material_entity[^\n]*?")',source).group(1))
 def statement(q,args):
  parts=q.split('?');return 'PREPARE probe AS '+parts[0]+''.join('$'+str(i)+p for i,p in enumerate(parts[1:],1))+';EXECUTE probe('+args+');'
 transfer=fmt("""UPDATE material_entity SET state='VOID',version=1 WHERE id='{m}' AND version=0;
 INSERT INTO stain_member(order_id,batch_id,source_id,output_id,frozen_version,source_dependency) VALUES('{o}','{b}','{m}','{output}',1,stain_source_snapshot('{m}'));
 """)+statement(query,fmt("'{output}','{h}','{p}','{r}','{k}','DEV-S-OUTPUT','S000000000000000000000000000000002',NULL,NULL,'{c}',NULL,'{m}',NULL,'{o}','{u}'"))+fmt("""
 INSERT INTO label_identity(material_id,hospital_id,request_id,barcode) VALUES('{output}','{h}','{r}','S000000000000000000000000000000002');
 INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES('{m}',1,'STAIN_TRANSFER','{output}','Synthetic','{u}');
 INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES('{output}',0,'CREATE','{m}','Synthetic','{u}');
 UPDATE stain_batch SET state='FROZEN',version=1,frozen_version=1 WHERE id='{b}' AND version=0;
 INSERT INTO stain_event(batch_id,version,action,frozen_version,content,reason,actor_id) VALUES('{b}',1,'FREEZE',1,'','Synthetic','{u}');
 """)
 sql("CREATE FUNCTION reject_stain_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic audit outage';END $$;CREATE TRIGGER reject_stain_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_stain_audit();")
 audit=fmt("INSERT INTO audit_event(id,actor_user_id,actor_auth_version,hospital_id,operation_code,resource_type,resource_id,result_version,trace_id) VALUES(gen_random_uuid(),'{u}',0,'{h}','STAIN_FREEZE_V1','STAIN_BATCH','{b}',1,'{trace}');")
 sql('BEGIN;'+transfer+audit+'COMMIT;',False)
 assert sql(fmt("SELECT state||version FROM material_entity WHERE id='{m}'"))=='ACTIVE0'
 assert sql('SELECT count(*) FROM stain_member')=='0';assert sql('SELECT count(*) FROM label_identity')=='0'
 sql('DROP TRIGGER reject_stain_audit ON audit_event;DROP FUNCTION reject_stain_audit();')
 sql('BEGIN;'+transfer+audit+'COMMIT;')
 assert sql(fmt("SELECT state FROM stain_material_gate WHERE id='{output}'"))=='SOURCE_QUARANTINED'
 sql(fmt("INSERT INTO stain_order(id,batch_id,hospital_id,request_id,case_id,patient_id,source_id,source_version,initial_dependency,created_by) VALUES(gen_random_uuid(),'{b}','{h}','{r}','{k}','{p}','{m}',0,'Synthetic','{u}');"),False)
 sql(fmt("BEGIN;UPDATE stain_batch SET state='PASS',version=2,control_event_id='{pass}' WHERE id='{b}';INSERT INTO stain_event(id,batch_id,version,action,frozen_version,content,reason,actor_id) VALUES('{pass}','{b}',2,'CONTROL_PASS',1,'Synthetic explicit control observation','Synthetic','{u}');COMMIT;"))
 assert sql(fmt("SELECT state FROM stain_material_gate WHERE id='{output}'"))=='SOURCE_QUARANTINED'
 # Execute actual read and report-dependency SQL, not hand-written equivalents.
 detail_query=json.loads(re.search(r'("SELECT o\.\*,sm.output_id[^\n]*?")',source).group(1))
 assert 'CONTROL_MISSING' not in sql(statement(detail_query,fmt("'{b}'")))
 quality=(root/'backend/src/main/java/com/pis/quality/QualityGate.java').read_text()
 snapshot=json.loads(re.search(r'("SELECT jsonb_build_object[^\n]*?"),String.class,request,request,request,request,request',quality).group(1))
 snapshot_args=','.join("'"+ids['r']+"'" for _ in range(5))
 previous_snapshot=sql(statement(snapshot,snapshot_args))
 # Deterministically prove acceptance and source-QC invalidation in a rollback-only transaction,
 # independently of which racer wins below. Preserve the head for the subsequent race.
 accepted=fmt("BEGIN;UPDATE stain_batch SET version=3 WHERE id='{b}';INSERT INTO stain_event(id,batch_id,version,action,order_id,frozen_version,control_event_id,technical_qc,content,reason,actor_id) VALUES('{result}','{b}',3,'RESULT','{o}',1,'{pass}','TECH_PASS','Synthetic manual observation','Synthetic','{u}');")
 accepted+=fmt("DO $$ BEGIN IF (SELECT state FROM stain_material_gate WHERE id='{output}')<>'PASS' THEN RAISE EXCEPTION 'Expected accepted result';END IF;END $$;")
 accepted+=fmt("UPDATE quality_head SET state='REVOKED',version=1 WHERE material_id='{m}';DO $$ BEGIN IF (SELECT state FROM stain_material_gate WHERE id='{output}')<>'SOURCE_QUARANTINED' THEN RAISE EXCEPTION 'Expected revoked source isolation';END IF;END $$;ROLLBACK;")
 sql(accepted)
 # Observe actual root-lock contention before racing result acceptance against control revocation.
 blocker=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 blocker.stdin.write(fmt("BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;\n"));blocker.stdin.flush();assert blocker.stdout.readline().strip()=='BEGIN';assert blocker.stdout.readline().strip()==ids['r']
 racers=[]
 for action in ['RESULT','REVOKE']:
  event=ids['result'] if action=='RESULT' else ids['revoke']; state='PASS' if action=='RESULT' else 'REVOKED'; control=ids['pass'] if action=='RESULT' else ids['revoke']
  cmd=fmt("SET application_name='stain-race';BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;")+"UPDATE stain_batch SET state='"+state+"',version=3,control_event_id='"+control+fmt("' WHERE id='{b}' AND version=2;DO $$ BEGIN IF EXISTS(SELECT 1 FROM stain_event WHERE batch_id='{b}' AND version=3) THEN RAISE EXCEPTION 'VERSION_CONFLICT';END IF;END $$;")
  cmd+=fmt("INSERT INTO stain_event(id,batch_id,version,action,order_id,frozen_version,control_event_id,technical_qc,content,reason,actor_id) VALUES('")+event+fmt("','{b}',3,'")+action+"',"+("'"+ids['o']+"'" if action=='RESULT' else 'NULL')+",1,"+("'"+ids['pass']+"','TECH_PASS'" if action=='RESULT' else 'NULL,NULL')+fmt(",'Synthetic manual evidence','Synthetic','{u}');COMMIT;")
  proc=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);proc.stdin.write(cmd);proc.stdin.close();racers.append(proc)
 observed=False
 for _ in range(80):
  if sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='stain-race' AND wait_event_type='Lock'")=='2':observed=True;break
  time.sleep(.025)
 blocker.stdin.write('COMMIT;\n');blocker.stdin.close();blocker.wait(timeout=5);assert observed
 results=[p.wait(timeout=5) for p in racers];assert sorted(results)==[0,3],results
 if sql(fmt("SELECT state FROM stain_batch WHERE id='{b}'"))=='PASS':
  assert sql(fmt("SELECT state FROM stain_material_gate WHERE id='{output}'"))=='PASS'
  old=sql(fmt("SELECT to_jsonb(e)::text FROM stain_event e WHERE id='{result}'"))
  sql(fmt("BEGIN;UPDATE stain_batch SET state='REVOKED',version=4,control_event_id='{revoke}' WHERE id='{b}';INSERT INTO stain_event(id,batch_id,version,action,frozen_version,content,reason,actor_id) VALUES('{revoke}','{b}',4,'REVOKE',1,'Synthetic revoked evidence','Synthetic','{u}');COMMIT;"))
  assert sql(fmt("SELECT to_jsonb(e)::text FROM stain_event e WHERE id='{result}'"))==old
 assert sql(fmt("SELECT state FROM stain_material_gate WHERE id='{output}'"))=='SOURCE_QUARANTINED'
 assert previous_snapshot!=sql(statement(snapshot,snapshot_args))
 assert 'CONTROL_REVOKED' in sql(statement(detail_query,fmt("'{b}'")))
 sql("UPDATE stain_batch SET state='PASS',version=version+1",False)
 sql("UPDATE stain_event SET content='replacement'",False);sql("DELETE FROM stain_member",False)
 print('PG17 exact production transfer SQL, one-for-one identities/labels, control gates, frozen members, observed result/revoke CAS race and atomic audit rollback: PASS')
 print('PG17 V1–V22 SQL migrations: PASS')
finally:
 r=run(['docker','rm','-f',name]);print('Temporary container cleanup:',r.returncode)
