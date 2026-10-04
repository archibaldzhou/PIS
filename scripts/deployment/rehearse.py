#!/usr/bin/env python3
"""Actual packaged application rehearsal. Requires existing local disposable PG17 _test DB."""
import argparse
import http.client
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile
from local_proxy import Active, Server, Target
from release import ROOT, build, verify, Switch

def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0));return s.getsockname()[1]

def get(number,path):
    c=http.client.HTTPConnection('127.0.0.1',number,timeout=2)
    try:
        c.request('GET',path);r=c.getresponse();return r.status,r.read(1048576)
    finally:c.close()

def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--dist',type=Path,required=True);p.add_argument('--revision',required=True);a=p.parse_args()
    if os.geteuid()==0:raise ValueError('NON_ROOT_REQUIRED')
    for key in ('PIS_TEST_DB_URL','PIS_TEST_DB_USERNAME','PIS_TEST_DB_PASSWORD'):
        if not os.environ.get(key):raise ValueError('EXISTING_TEST_DB_CONFIGURATION_REQUIRED')
    # T40 is a real, nonempty PG/object consistency gate, not an assertion of this empty app's backup.
    gate=subprocess.run(['python3',str(ROOT/'scripts/synthetic-recovery.py')],capture_output=True,text=True,timeout=170)
    if gate.returncode or json.loads(gate.stdout).get('status')!='PASS':raise ValueError('RECOVERY_GATE_FAILED')
    processes=[];logs=[];server=None;phase='PREPARE'
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='pis-release-rehearsal-') as temp:
        root=Path(temp);schema='pis_deploy_'+uuid.uuid4().hex
        try:
            first=root/'release-a';second=root/'release-b'
            build(a.jar,a.dist,first,a.revision);build(a.jar,a.dist,second,a.revision)
            assert (first/'release.sha256').read_bytes()==(second/'release.sha256').read_bytes()
            with zipfile.ZipFile(first/'application.jar') as z:
                drivers=[n for n in z.namelist() if n.startswith('BOOT-INF/lib/postgresql-') and n.endswith('.jar')]
                if len(drivers)!=1 or z.getinfo(drivers[0]).file_size>4*1024*1024:raise ValueError('PG_DRIVER_UNAVAILABLE')
                driver=root/'postgresql.jar';driver.write_bytes(z.read(drivers[0]))
            def database(action):
                r=subprocess.run(['java','--class-path',str(driver),str(ROOT/'scripts/deployment/PgRehearsal.java'),action,schema],capture_output=True,text=True,timeout=15)
                if r.returncode:raise ValueError('ISOLATED_DATABASE_CHECK_FAILED')
                return r.stdout.strip()
            assert database('create')=='CREATED'
            def schema_version():
                rows=database('state').splitlines()
                if len(rows)<2:raise ValueError('SCHEMA_NOT_READY')
                return int(rows[-2].split(':')[0])
            env=os.environ.copy();env.update(PIS_DB_URL=env['PIS_TEST_DB_URL'],PIS_DB_USERNAME=env['PIS_TEST_DB_USERNAME'],PIS_DB_PASSWORD=env['PIS_TEST_DB_PASSWORD'])
            def start(release,number,bad=False):
                verify(release,37)
                log=(root/('process-'+uuid.uuid4().hex+'.private-log')).open('wb');logs.append(log)
                args=['java','-Xmx256m','-jar',str(release/'application.jar'),'--server.address=127.0.0.1','--server.port='+str(number),'--spring.profiles.active=dev','--spring.datasource.hikari.schema='+schema,'--spring.flyway.default-schema='+schema,'--spring.flyway.schemas='+schema,'--spring.flyway.create-schemas=false','--pis.workflow.development-enabled=false','--pis.ai.synthetic-worker-enabled=false','--pis.security.development-account.enabled=false']
                if bad:args.append('--management.endpoints.web.exposure.include=health,env')
                process=subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT);processes.append(process)
                return process
            def ready(process,number,seconds=60):
                deadline=time.monotonic()+seconds
                while process.poll() is None and time.monotonic()<deadline:
                    try:
                        status,body=get(number,'/actuator/health/readiness')
                        if status==200 and json.loads(body).get('status')=='UP':return True
                    except (OSError,ValueError):pass # bounded readiness polling, not ignored test failures
                    time.sleep(.1)
                return False
            phase='COLD_START'
            pa,pb=port(),port()
            while pb==pa:pb=port()
            old=start(first,pa)
            active=Active(Target(first/'dist',pa));switch=Switch(active)
            switch.activate(first,pa,37,lambda:ready(old,pa) and schema_version()==37)
            baseline=database('state');assert baseline.splitlines()[-1]=='COUNTS:0:0:0';assert len(baseline.splitlines())==38
            server=Server(0,active);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            assert get(server.server_port,'/deep/link')[1]==(first/'dist/index.html').read_bytes()
            assert get(server.server_port,'/api/hello')[0]==401
            phase='CANDIDATE_OR_RESTART'
            if schema_version()!=37:raise ValueError('INCOMPATIBLE_LIVE_SCHEMA')
            newer=start(second,pb);switch.activate(second,pb,schema_version(),lambda:ready(newer,pb) and schema_version()==37)
            newer.terminate();newer.wait(timeout=15)
            assert get(server.server_port,'/api/hello')[0]==502
            phase='CANDIDATE_OR_RESTART'
            if schema_version()!=37:raise ValueError('INCOMPATIBLE_LIVE_SCHEMA')
            newer=start(second,pb);switch.activate(second,pb,schema_version(),lambda:ready(newer,pb) and schema_version()==37)
            phase='REJECT_FAILED_CANDIDATE'
            failed_port=port();failed=start(first,failed_port,True)
            try:switch.activate(first,failed_port,schema_version(),lambda:ready(failed,failed_port,40))
            except ValueError as e:assert str(e)=='CANDIDATE_NOT_READY'
            else:raise AssertionError('UNSAFE_CANDIDATE_ACCEPTED')
            assert switch.current==second and get(server.server_port,'/api/hello')[0]==401
            phase='ROLLBACK'
            switch.rollback(first,schema_version(),lambda:ready(old,pa,5) and schema_version()==37)
            assert switch.current==first and active.get().port==pa
            assert database('state')==baseline # No schema downgrade, identity, audit or object mutation.
            assert get(server.server_port,'/deep/link')[1]==(first/'dist/index.html').read_bytes()
            print(json.dumps({'contract':'SYN-DEPLOY-1','status':'PASS','releaseDigest':(first/'release.sha256').read_text().strip(),'schema':37,'coldStart':True,'restart':True,'failedCandidatePreservedActive':True,'rollbackToVerifiedInstance':True,'migrationAndRowsUnchanged':True,'sameBuiltArtifactTwoInstances':True,'nonemptyRecoveryGate':'T40_PASS','tls':'NOT_CONFIGURED','productionReady':False},sort_keys=True))
        except Exception:
            import sys
            classes=set()
            for log in logs:
                with open(log.name,'rb') as stream:
                    stream.seek(0,2);end=stream.tell();stream.seek(max(0,end-32768))
                    classes.update(re.findall(r'(?:[a-zA-Z_$]\w*\.)+[A-Z][\w$]*(?:Exception|Error)',stream.read(32768).decode('utf-8','replace')))
            print('SYN_DEPLOY_FAILURE '+json.dumps({'phase':phase,'exitCodes':[p.poll() for p in processes],'failureClasses':sorted(classes)[:12]}),file=sys.stderr)
            raise
        finally:
            if server:server.shutdown();server.server_close()
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    try:process.wait(timeout=10)
                    except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            for log in logs:log.close()
            # Only owned temp files removed; schema remains in disposable test DB, never DROP user data.
if __name__=='__main__':main()
