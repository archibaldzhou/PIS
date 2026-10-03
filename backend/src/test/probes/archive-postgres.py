from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t26-'+uuid.uuid4().hex[:8]
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
 ids.update({k:str(uuid.uuid4()) for k in ['item','loc','loan','loan2','inventory']})
 sql(fmt("""BEGIN;
 INSERT INTO archive_book(request_id,hospital_id,scope_id,case_id,patient_id) VALUES('{r}','{h}','{scope}','{k}','{p}');
 INSERT INTO archive_location(id,scope_id,code,created_by) VALUES('{loc}','{scope}','SYN-SLOT','{u}');
 INSERT INTO archive_item(id,request_id,case_id,scope_id,material_id,kind,barcode,source_version,location_id,policy_label,legal_hold) VALUES('{item}','{r}','{k}','{scope}','{m}','SLIDE','S000000000000000000000000000000001',0,'{loc}','SYN-ONLY',true);
 INSERT INTO archive_event(id,request_id,version,action,item_id,actor_id,reason,detail) VALUES(gen_random_uuid(),'{r}',0,'REGISTER','{item}','{u}','Synthetic','{{}}');
 UPDATE archive_book SET version=0 WHERE request_id='{r}';COMMIT;
 """))
 sql("UPDATE archive_item SET barcode='wrong',version=version+1",False)
 sql("DELETE FROM archive_item",False)
 sql("UPDATE archive_event SET reason='changed'",False)
 sql(fmt("INSERT INTO archive_loan(id,request_id,applicant_id,borrower_id,purpose,due_at,state) VALUES('{loan}','{r}','{u}','{u}','Synthetic',statement_timestamp()+interval '1 day','REQUESTED'),('{loan2}','{r}','{u}','{u}','Synthetic',statement_timestamp()+interval '1 day','REQUESTED')"))
 # Real unique-index contention: two sessions reserve the same item; exactly one commits.
 blocker=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 blocker.stdin.write(fmt("BEGIN;INSERT INTO archive_loan_item(request_id,loan_id,item_id,state) VALUES('{r}','{loan}','{item}','RESERVED');\n"));blocker.stdin.flush();assert blocker.stdout.readline().strip()=='BEGIN';assert blocker.stdout.readline().strip()=='INSERT 0 1'
 race=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 race.stdin.write(fmt("SET application_name='archive-reserve-race';INSERT INTO archive_loan_item(request_id,loan_id,item_id,state) VALUES('{r}','{loan2}','{item}','RESERVED');"));race.stdin.close()
 observed=False
 for _ in range(80):
  if sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='archive-reserve-race' AND wait_event_type='Lock'")=='1':observed=True;break
  time.sleep(.025)
 assert observed
 blocker.stdin.write('COMMIT;\n');blocker.stdin.close();assert blocker.wait(timeout=10)==0;assert race.wait(timeout=10)!=0
 sql(fmt("INSERT INTO archive_inventory(id,request_id,book_version,created_by) VALUES('{inventory}','{r}',0,'{u}');INSERT INTO archive_inventory_item SELECT '{inventory}',request_id,id,version,location_id,condition,'RESERVED' FROM archive_item"))
 # Exact production item CAS SQL and snapshot INSERT are exercised without a Spring claim.
 import re,json
 source=(root/'backend/src/main/java/com/pis/archive/ArchiveService.java').read_text()
 update=json.loads(re.search(r'("UPDATE archive_item SET location_id=\?[^"\n]*")',source).group(1))
 def statement(q,args):
  parts=q.split('?');return 'PREPARE probe AS '+parts[0]+''.join('$'+str(i)+p for i,p in enumerate(parts[1:],1))+';EXECUTE probe('+args+');'
 assert 'UPDATE 1' in sql(statement(update,fmt("NULL,'RECORDED','{item}',0")))
 assert 'UPDATE 0' in sql(statement(update,fmt("NULL,'RECORDED','{item}',0")))
 assert sql("SELECT count(*) FROM archive_inventory_item s JOIN archive_item i ON i.id=s.item_id WHERE s.item_version<>i.version")=='1'
 sql("UPDATE archive_inventory_item SET item_version=1",False)
 sql("UPDATE archive_loan_item SET state='OUT'")
 sql("UPDATE archive_loan_item SET state='RESERVED'",False)
 # Failed atomic audit rolls back item movement, event, root CAS and reservation return.
 before=sql("SELECT row_to_json(i) FROM archive_item i")
 sql("CREATE FUNCTION archive_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic audit unavailable'; END $$;CREATE TRIGGER fail_archive_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION archive_fail_audit();")
 sql(fmt("BEGIN;UPDATE archive_item SET version=version+1,location_id='{loc}' WHERE id='{item}' AND version=1;UPDATE archive_loan_item SET state='RETURNED' WHERE item_id='{item}';INSERT INTO archive_event(id,request_id,version,action,item_id,actor_id,reason,detail) VALUES(gen_random_uuid(),'{r}',1,'RETURN','{item}','{u}','Synthetic','{{}}');UPDATE archive_book SET version=1 WHERE request_id='{r}' AND version=0;INSERT INTO audit_event(id,actor_user_id,actor_auth_version,hospital_id,operation_code,resource_type,resource_id,result_version,trace_id) VALUES(gen_random_uuid(),'{u}',0,'{h}','ARCHIVE_RETURN_V1','ARCHIVE_BOOK','{r}',1,gen_random_uuid());COMMIT;"),False)
 assert sql("SELECT row_to_json(i) FROM archive_item i")==before
 assert sql("SELECT state FROM archive_loan_item")=='OUT'
 assert sql("SELECT version FROM archive_book")=='0'
 assert sql("SELECT count(*) FROM archive_event")=='1'
 sql("DROP TRIGGER fail_archive_audit ON audit_event")
 sql("UPDATE archive_loan_item SET state='RETURNED'")
 sql(fmt("INSERT INTO archive_loan_item(request_id,loan_id,item_id,state) VALUES('{r}','{loan2}','{item}','RESERVED')"))
 print('PG17 V1-V24, immutable identity/snapshot/history, observed reservation race, production item CAS, stale snapshot, return rollback and new reservation after return: PASS')
finally:
 run(['docker','rm','-f',name])
