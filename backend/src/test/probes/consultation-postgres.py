from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t25-'+uuid.uuid4().hex[:8]
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
 ids.update({k:str(uuid.uuid4()) for k in ['consult','revision','reviewer','opinion','summary','adopted']})
 sql(fmt("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES('{reviewer}','synthetic-reviewer','Synthetic','unused',true);INSERT INTO report_template VALUES('SYN-CONSULT',1,'Synthetic','SYN-TEXT-1');"))
 sql(fmt("INSERT INTO report_revision(id,case_id,version,template_code,template_version,fields,assignment_version,author_id,reason) VALUES('{revision}','{k}',0,'SYN-CONSULT',1,'{{\"gross\":\"\",\"microscopy\":\"\",\"diagnosis\":\"Synthetic original\",\"notes\":\"Original note\"}}',0,'{u}','Synthetic');INSERT INTO report_draft(case_id,version,revision_id) VALUES('{k}',0,'{revision}');"))
 import json,re
 source=(root/'backend/src/main/java/com/pis/report/ConsultationService.java').read_text()
 query=json.loads(re.search(r'("INSERT INTO report_consultation[^\n]*?")',source).group(1))
 def statement(q,args):
  parts=q.split('?');return 'PREPARE probe AS '+parts[0]+''.join('$'+str(i)+p for i,p in enumerate(parts[1:],1))+';EXECUTE probe('+args+');'
 sql('BEGIN;'+statement(query,fmt("'{consult}','{k}','{h}','{r}','{scope}','{revision}',0,'Synthetic dependencies','[]','CONSULT','Synthetic purpose',statement_timestamp()+interval '1 day','{u}'"))+fmt("INSERT INTO consultation_member(consultation_id,user_id,qualification_snapshot,state) VALUES('{consult}','{reviewer}','Synthetic qualification','INVITED');INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,content,reason) VALUES(gen_random_uuid(),'{consult}','{k}',0,'CREATE','{u}','','Synthetic');COMMIT;"))
 sql("UPDATE report_consultation SET purpose='changed',version=1",False)
 sql(fmt("BEGIN;UPDATE consultation_member SET state='ACCEPTED' WHERE consultation_id='{consult}';UPDATE report_consultation SET version=1 WHERE id='{consult}' AND version=0;INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,content,reason) VALUES(gen_random_uuid(),'{consult}','{k}',1,'ACCEPT','{reviewer}','','Synthetic');COMMIT;"))
 # Verify production batched qualification SQL against the same synthetic scope and revocation.
 diagnosis=(root/'backend/src/main/java/com/pis/diagnosis/DiagnosisService.java').read_text()
 eligible=re.search(r'ELIGIBLE="""(.*?)""";',diagnosis,re.S).group(1)
 projection=json.loads(re.search(r'("SELECT u.id,jsonb_build_array[^"\n]*AS snapshot ")',diagnosis).group(1))
 qualified=projection+eligible+' AND d.can_diagnose AND u.id IN (?,?)'
 sql(fmt("UPDATE app_user SET synthetic_only=true WHERE id IN ('{u}','{reviewer}');INSERT INTO workflow_grant(user_id,scope_id,can_read) VALUES('{u}','{scope}',true),('{reviewer}','{scope}',true);INSERT INTO diagnosis_grant(user_id,scope_id,can_diagnose,can_assign,qualification) VALUES('{u}','{scope}',true,true,'SYN-DIAG-ASSIGNMENT-1'),('{reviewer}','{scope}',true,false,'SYN-DIAG-ASSIGNMENT-1');"))
 qualification_args=fmt("'{scope}','{u}','{reviewer}'")
 result=sql(statement(qualified,qualification_args));assert ids['u'] in result and ids['reviewer'] in result
 result=sql(fmt("BEGIN;UPDATE diagnosis_grant SET revoked_at=statement_timestamp(),version=version+1 WHERE user_id='{reviewer}';")+statement(qualified,qualification_args)+'ROLLBACK;');assert ids['u'] in result and ids['reviewer'] not in result
 # Observe two transactions waiting on the same application request lock before racing opinion CAS.
 blocker=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 blocker.stdin.write(fmt("BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;\n"));blocker.stdin.flush();assert blocker.stdout.readline().strip()=='BEGIN';assert blocker.stdout.readline().strip()==ids['r']
 racers=[]
 for n in range(2):
  event=str(uuid.uuid4())
  cmd=fmt("SET application_name='consult-race';BEGIN;SELECT id FROM pathology_request WHERE id='{r}' FOR UPDATE;UPDATE report_consultation SET version=2 WHERE id='{consult}' AND version=1;DO $$ BEGIN IF EXISTS(SELECT 1 FROM consultation_event WHERE consultation_id='{consult}' AND version=2) THEN RAISE EXCEPTION 'VERSION_CONFLICT';END IF;END $$;")
  cmd+=fmt("INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,disposition,content,reason) VALUES('")+event+fmt("','{consult}','{k}',2,'OPINION','{reviewer}','DISAGREE','Synthetic differing observation','Synthetic');UPDATE consultation_member SET opinion_id='")+event+fmt("' WHERE consultation_id='{consult}';COMMIT;")
  p=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);p.stdin.write(cmd);p.stdin.close();racers.append(p)
 observed=False
 for _ in range(80):
  if sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='consult-race' AND wait_event_type='Lock'")=='2':observed=True;break
  time.sleep(.025)
 blocker.stdin.write('COMMIT;\n');blocker.stdin.close();blocker.wait(timeout=5);assert observed
 assert sorted(p.wait(timeout=5) for p in racers)==[0,3]
 ids['opinion']=sql(fmt("SELECT opinion_id FROM consultation_member WHERE consultation_id='{consult}'"))
 basis=json.loads(re.search(r'("SELECT jsonb_agg\(jsonb_build_array[^\n]*?")',source).group(1))
 # Same exact SQL is used for summary binding and confirmation freshness.
 assert ids['opinion'] in sql(statement(basis,fmt("'{consult}'")))
 sql(fmt("BEGIN;INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,disposition,content,reason,basis) VALUES('{summary}','{consult}','{k}',3,'SUMMARY','{u}','RESOLVED','Synthetic manual reconciliation','Synthetic', (SELECT jsonb_agg(jsonb_build_array(user_id,state,opinion_id) ORDER BY user_id)::text FROM consultation_member WHERE consultation_id='{consult}'));UPDATE report_consultation SET version=3,summary_id='{summary}' WHERE id='{consult}';COMMIT;"))
 assert sql(fmt("SELECT count(*) FROM consultation_member WHERE consultation_id='{consult}' AND confirmed_summary_id='{summary}'"))=='0'
 sql(fmt("BEGIN;INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,content,reason,summary_id) VALUES(gen_random_uuid(),'{consult}','{k}',4,'CONFIRM','{reviewer}','','Synthetic explicit confirmation','{summary}');UPDATE consultation_member SET confirmed_summary_id='{summary}' WHERE consultation_id='{consult}';UPDATE report_consultation SET version=4 WHERE id='{consult}';COMMIT;"))
 # New personal opinion invalidates the old exact basis, without deleting it; rollback keeps adoption scenario ready.
 sql(fmt("UPDATE consultation_member SET opinion_id='{summary}' WHERE consultation_id='{consult}'"),False)
 old_report=sql(fmt("SELECT to_jsonb(r)::text FROM report_revision r WHERE id='{revision}'"))
 adoption=fmt("INSERT INTO report_revision(id,case_id,version,template_code,template_version,fields,assignment_version,author_id,reason) SELECT '{adopted}',case_id,1,template_code,template_version,jsonb_set(fields,'{{notes}}',to_jsonb((fields->>'notes')||E'\\nSynthetic adopted note')),assignment_version,'{u}','Synthetic adoption' FROM report_revision WHERE id='{revision}';UPDATE report_draft SET version=1,revision_id='{adopted}' WHERE case_id='{k}';INSERT INTO consultation_event(id,consultation_id,case_id,version,action,actor_id,content,reason,summary_id,adopted_revision_id) VALUES(gen_random_uuid(),'{consult}','{k}',5,'ADOPT','{u}','Synthetic adopted note','Synthetic','{summary}','{adopted}');UPDATE report_consultation SET version=5,state='ADOPTED' WHERE id='{consult}' AND version=4;")
 audit=fmt("INSERT INTO audit_event(id,actor_user_id,actor_auth_version,hospital_id,operation_code,resource_type,resource_id,result_version,trace_id) VALUES(gen_random_uuid(),'{u}',0,'{h}','CONSULT_ADOPT_V1','REPORT_CONSULTATION','{consult}',5,'{trace}');")
 sql("CREATE FUNCTION reject_consult_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic outage';END $$;CREATE TRIGGER reject_consult_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_consult_audit();")
 sql('BEGIN;'+adoption+audit+'COMMIT;',False)
 assert sql(fmt("SELECT revision_id FROM report_draft WHERE case_id='{k}'"))==ids['revision']
 assert sql(fmt("SELECT state||version FROM report_consultation WHERE id='{consult}'"))=='OPEN4'
 assert sql(fmt("SELECT count(*) FROM report_revision WHERE id='{adopted}'"))=='0'
 sql('DROP TRIGGER reject_consult_audit ON audit_event;DROP FUNCTION reject_consult_audit();')
 sql('BEGIN;'+adoption+audit+'COMMIT;')
 assert sql(fmt("SELECT to_jsonb(r)::text FROM report_revision r WHERE id='{revision}'"))==old_report
 assert sql(fmt("SELECT fields->>'diagnosis' FROM report_revision WHERE id='{adopted}'"))=='Synthetic original'
 assert 'Original note' in sql(fmt("SELECT fields->>'notes' FROM report_revision WHERE id='{adopted}'"))
 sql("UPDATE consultation_event SET content='overwrite'",False)
 sql("UPDATE report_consultation SET state='OPEN',version=6",False)
 sql("DELETE FROM consultation_member",False)
 print('PG17 V1–V23 SQL and actual consultation insert/basis query, observed opinion CAS, immutable history, explicit summary binding, adoption audit rollback and original-report preservation: PASS')
finally:
 r=run(['docker','rm','-f',name]);print('Temporary container cleanup:',r.returncode)
