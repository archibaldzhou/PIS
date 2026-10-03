from pathlib import Path
import subprocess,uuid,time
root=Path(__file__).resolve().parents[4];name='pis-t27-'+uuid.uuid4().hex[:8]
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
 sql(fmt("INSERT INTO quality_event(material_id,version,action,assessment_id,reason,actor_id) VALUES('{m}',0,'ASSESS','{a}','Synthetic','{u}')"))
 sql(fmt("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_qc) VALUES('{u}','{scope}',true,true)"))
 sql(fmt("INSERT INTO statistics_grant(user_id,scope_id,qualification,can_drill,valid_until) VALUES('{u}','{scope}','SYN-STATS-1',true,statement_timestamp()+interval '1 day')"))
 source=(root/'backend/src/main/java/com/pis/statistics/StatisticsService.java').read_text()
 projection=source.split('public static final String FACTS="""')[1].split('""";')[0]
 values=["'"+ids['u']+"'","'"+ids['scope']+"'","statement_timestamp()","'QC,'","statement_timestamp()-interval '1 day'","statement_timestamp()+interval '1 day'","statement_timestamp()-interval '1 day'","statement_timestamp()+interval '1 day'"]
 for value in values:projection=projection.replace('?',value,1)
 assert '?' not in projection
 assert sql(projection)=='' # unauthorized resources never enter aggregation
 sql(fmt("INSERT INTO statistics_resource_grant(user_id,scope_id,request_id,valid_until) VALUES('{u}','{scope}','{r}',statement_timestamp()+interval '1 day')"))
 assert sql(projection).split('|')[-1]=='PASS'
 sql('CREATE TEMP TABLE unused(n int)')
 # Copy one committed MVCC projection to immutable facts, then change the live source.
 snap=str(uuid.uuid4());ids['snap']=snap
 sql(fmt("INSERT INTO statistics_snapshot(id,actor_id,scope_id,definition,from_date,to_date,zone,starts_at,ends_at,cutoff,permission_mask,facts_hash,fact_count) VALUES('{snap}','{u}','{scope}','SYN-STATS-1',CURRENT_DATE,CURRENT_DATE,'UTC',CURRENT_DATE,CURRENT_DATE+1,statement_timestamp(),'QC,',repeat('a',64),1)"))
 sql(fmt("INSERT INTO statistics_fact(snapshot_id,metric,entity_id,request_id,start_event,start_at,status,source_basis) SELECT '{snap}','QC','{m}','{r}',id,occurred_at,'PASS','synthetic probe' FROM material_event WHERE material_id='{m}'"))
 sql("UPDATE statistics_fact SET status='UNKNOWN'",False);sql("DELETE FROM statistics_snapshot",False)
 sql(fmt("UPDATE quality_head SET state='REVOKED',version=version+1 WHERE material_id='{m}'"))
 sql(fmt("INSERT INTO quality_event(material_id,version,action,assessment_id,reason,actor_id) VALUES('{m}',1,'REVOKE','{a}','Synthetic','{u}')"))
 assert sql(projection).split('|')[-1]=='UNKNOWN'
 assert sql('SELECT status FROM statistics_fact')=='PASS'
 sql("UPDATE statistics_resource_grant SET revoked_at=statement_timestamp()")
 assert sql(projection)==''
 sql("BEGIN;INSERT INTO statistics_snapshot(id,actor_id,scope_id,definition,from_date,to_date,zone,starts_at,ends_at,cutoff,permission_mask,facts_hash,fact_count) SELECT gen_random_uuid(),actor_id,scope_id,definition,from_date,to_date,zone,starts_at,ends_at,cutoff,permission_mask,facts_hash,0 FROM statistics_snapshot;ROLLBACK;")
 assert sql('SELECT count(*) FROM statistics_snapshot')=='1'
 # Two real sessions contend for the same actor quota lock; no counter oversubscription.
 lock_key='STATISTICS_SNAPSHOT_V1:'+ids['u']
 blocker=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 blocker.stdin.write("BEGIN;SELECT pg_advisory_xact_lock(hashtextextended('"+lock_key+"',0));\n");blocker.stdin.flush()
 assert blocker.stdout.readline().strip()=='BEGIN';blocker.stdout.readline()
 assert sql("SELECT pg_try_advisory_xact_lock(hashtextextended('"+lock_key+"',0))")=='f'
 blocker.stdin.write('ROLLBACK;\n');blocker.stdin.close();assert blocker.wait(timeout=5)==0
 assert sql("SELECT pg_try_advisory_xact_lock(hashtextextended('"+lock_key+"',0))")=='t'
 outside=str(uuid.uuid4())
 sql(fmt("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES('")+outside+fmt("','{h}','{p}','{s}','SYN-UNSCOPED')"))
 sql("INSERT INTO statistics_fact(snapshot_id,metric,entity_id,request_id,status,source_basis) VALUES('"+snap+"','QC',gen_random_uuid(),'"+outside+"','UNKNOWN','{}')",False)
 print('PG17 T27: V1-V25 migrations, actual authorized SQL projection, QC revoke, frozen facts, rollback, actor quota contention, source scope and immutable history PASS')
finally:
 run(['docker','rm','-f',name])
