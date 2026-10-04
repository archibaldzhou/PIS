#!/usr/bin/env python3
"""Bounded, offline PG17/private-object recovery rehearsal. No external targets accepted.
No credentials, real users, online backup, encryption or hospital RPO/RTO claims.
All subprocess output stays private; evidence contains only fixed codes/counts/hashes.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'postgres:17.11-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652'
LIMIT = 16 * 1024 * 1024

def digest(data):
    return hashlib.sha256(data).hexdigest()

def run(args, data=None):
    p = subprocess.run(args, input=data, capture_output=True, timeout=40)
    if p.returncode:
        # SQL/server errors might echo input: do not log them.
        raise RuntimeError('SYNTHETIC_RECOVERY_SUBPROCESS_FAILED')
    if len(p.stdout) > LIMIT:
        raise RuntimeError('SYNTHETIC_RECOVERY_OUTPUT_LIMIT')
    return p.stdout

def reject(fn):
    try:
        fn()
    except (ValueError, FileNotFoundError):
        return
    raise AssertionError('Expected fail-closed rejection')

def file_bytes(p):
    if p.is_symlink() or not p.is_file() or p.stat().st_size > LIMIT:
        raise ValueError('INVALID_FILE')
    with p.open('rb') as f:
        data = f.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError('SIZE_LIMIT')
    return data

def verify(bundle, expected_migrations):
    m = json.loads(file_bytes(bundle / 'manifest.json'))
    if m.get('schema') != 'SYN-RECOVERY-1' or m.get('state') != 'COMPLETE' or m.get('migrations') != expected_migrations:
        raise ValueError('INCOMPLETE_OR_VERSION_MISMATCH')
    if m.get('mode') != 'OFFLINE_SYNTHETIC' or len(m.get('objects', [])) != 1:
        raise ValueError('INVALID_SCOPE')
    if digest(file_bytes(bundle / 'database.dump')) != m['databaseSha256']:
        raise ValueError('DATABASE_HASH')
    if str(uuid.UUID(m['rootId'])) != m['rootId'] or file_bytes(bundle / '.pis-storage-root-v1').decode() != m['rootId']:
        raise ValueError('ROOT_ID_MISMATCH')
    expected = {'manifest.json', 'database.dump', '.pis-storage-root-v1'}
    for o in m['objects']:
        if not re.fullmatch('[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}', o['id']):
            raise ValueError('INVALID_OBJECT_ID')
        name = o['id'] + '.blob'
        data = file_bytes(bundle / name)
        if len(data) != o['size'] or digest(data) != o['sha256'] or not data.startswith(b'PIS-SYNTHETIC-STORAGE-V1\n'):
            raise ValueError('OBJECT_HASH')
        expected.add(name)
    if {p.name for p in bundle.iterdir()} != expected:
        raise ValueError('UNMANIFESTED_FILE')
    return m

def restore_files(bundle, target, owner, migrations):
    # Never accept an existing directory, a symlink, or a target outside this run's private root.
    if target.parent != owner or target.exists() or target.is_symlink() or not target.name.startswith('restore-'):
        raise ValueError('UNSAFE_RESTORE_TARGET')
    m = verify(bundle, migrations)
    target.mkdir(mode=0o700)
    (target / '.pis-storage-root-v1').write_text(m['rootId'])
    for o in m['objects']:
        name = o['id'] + '.blob'
        with (target / name).open('xb') as out:
            out.write(file_bytes(bundle / name))
    return m

def main():
    import sys
    if len(sys.argv) != 1:
        raise ValueError('NO_EXTERNAL_TARGETS_ACCEPTED')
    def expired(_signum, _frame):
        raise TimeoutError('SYNTHETIC_RECOVERY_TOTAL_LIMIT')
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(150)
    os.umask(0o077)
    name = 'pis-recovery-' + uuid.uuid4().hex
    started = time.monotonic()
    # This process owns this entire ephemeral tree/container; never traverses a user target.
    with tempfile.TemporaryDirectory(prefix='pis-synthetic-recovery-') as tmp:
        owner = Path(tmp)
        bundle = owner / 'bundle'
        source = owner / 'source'
        bundle.mkdir(mode=0o700)
        source.mkdir(mode=0o700)
        def sql(statement, db='source'):
            return run(['docker','exec','-i',name,'psql','-U','postgres','-d',db,'-X','-v','ON_ERROR_STOP=1','-At'],statement.encode()).decode().strip()
        try:
            run(['docker','run','-d','--rm','--name',name,'--network','none','--memory','512m','--cpus','1','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_HOST_AUTH_METHOD=trust',IMAGE])
            for _ in range(100):
                check = subprocess.run(['docker','exec',name,'pg_isready','-U','postgres'],capture_output=True,timeout=5)
                if check.returncode == 0:
                    # pg_isready can observe init server; require final container process too.
                    logs = subprocess.run(['docker','logs',name],capture_output=True,timeout=5)
                    if b'PostgreSQL init process complete' in logs.stdout:
                        break
                time.sleep(.1)
            else:
                raise RuntimeError('SYNTHETIC_POSTGRES_NOT_READY')
            sql('CREATE DATABASE source;', 'postgres')
            files = sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
            assert [int(p.name.split('__')[0][1:]) for p in files] == list(range(1,38))
            migrations = {p.name:digest(p.read_bytes()) for p in files}
            for p in files:
                sql('BEGIN;'+p.read_text().replace('${flyway:defaultSchema}','public')+'COMMIT;')
            ids = {k:str(uuid.uuid4()) for k in ['h','p','s','r','c','u','campus','dept','scope','asset','version','rootid','audit','command','trace']}
            payload = b'PIS-SYNTHETIC-STORAGE-V1\n' + bytes(range(256))*8192
            ids.update(size=len(payload), sha=digest(payload))
            # Fixed synthetic identities only, disabled unusable accounts: no password/credential material.
            sql((ROOT/'scripts/synthetic-recovery-seed.sql').read_text().format(**ids))
            original = source / (ids['version']+'.blob')
            original.write_bytes(payload)
            (source/'.pis-storage-root-v1').write_text(ids['rootid'])
            (bundle/'.pis-storage-root-v1').write_text(ids['rootid'])
            # Source is exclusively owned and offline: no application or other writers exist.
            # pg_dump supplies one consistent PG snapshot. Object list comes from that frozen source.
            refs = sql("SELECT id||':'||sha256||':'||byte_size FROM storage_version WHERE state='READY'")
            assert refs == f"{ids['version']}:{ids['sha']}:{ids['size']}"
            assert sql("SELECT count(*) FROM app_user WHERE enabled OR NOT synthetic_only OR password_hash<>'UNUSABLE_SYNTHETIC_ACCOUNT'") == '0'
            dump = run(['docker','exec',name,'pg_dump','-U','postgres','-d','source','-Fc','--no-owner','--no-acl'])
            (bundle/'database.dump').write_bytes(dump)
            shutil.copyfile(original,bundle/original.name)
            manifest = {'schema':'SYN-RECOVERY-1','state':'INCOMPLETE','mode':'OFFLINE_SYNTHETIC','migrations':migrations,
                        'rootId':ids['rootid'],'databaseSha256':digest(dump),'objects':[{'id':ids['version'],'size':len(payload),'sha256':digest(payload)}]}
            def save():
                (bundle/'manifest.json').write_text(json.dumps(manifest,sort_keys=True))
            save()
            reject(lambda: verify(bundle,migrations)) # interruption before manifest commit
            manifest['state']='COMPLETE'; save()
            verify(bundle,migrations)
            reject(lambda: restore_files(bundle,source,owner,migrations))
            reject(lambda: restore_files(bundle,Path('/tmp/restore-unsafe'),owner,migrations))
            reject(lambda: verify(bundle,{}))
            (bundle/original.name).write_bytes(b'corrupt')
            reject(lambda: verify(bundle,migrations))
            (bundle/original.name).unlink() # only this script's temporary copy
            reject(lambda: verify(bundle,migrations))
            (bundle/original.name).symlink_to(original)
            reject(lambda: verify(bundle,migrations))
            (bundle/original.name).unlink()
            shutil.copyfile(original,bundle/original.name)
            (bundle/'unexpected').write_bytes(b'synthetic')
            reject(lambda: verify(bundle,migrations)); (bundle/'unexpected').unlink()
            (bundle/'database.dump').write_bytes(dump[:-5])
            reject(lambda: verify(bundle,migrations)); (bundle/'database.dump').write_bytes(dump)
            target = owner/'restore-objects'
            restored = restore_files(bundle,target,owner,migrations)
            reject(lambda: restore_files(bundle,target,owner,migrations)) # interrupted target not reused
            # Fresh database creation fails if it already exists. No DROP or --clean anywhere.
            # Deterministic process interruption: block the first restore DDL on an advisory lock,
            # terminate that backend, and prove --single-transaction left no restored tables.
            sql('CREATE DATABASE interrupted;', 'postgres')
            sql("CREATE FUNCTION recovery_gate() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(74040); END $$; CREATE EVENT TRIGGER recovery_gate ON ddl_command_start EXECUTE FUNCTION recovery_gate();", 'interrupted')
            holder=subprocess.Popen(['docker','exec','-i',name,'psql','-U','postgres','-d','interrupted','-X','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            import select
            try:
                holder.stdin.write(b"SELECT pg_advisory_lock(74040);\n");holder.stdin.flush()
                if not select.select([holder.stdout],[],[],5)[0]:
                    raise RuntimeError('RESTORE_GATE_TIMEOUT')
                holder.stdout.readline()
                with (bundle/'database.dump').open('rb') as incoming:
                    interrupted=subprocess.Popen(['docker','exec','-i',name,'pg_restore','-U','postgres','-d','interrupted','--exit-on-error','--single-transaction','--no-owner','--no-acl'],stdin=incoming,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                    try:
                        deadline=time.monotonic()+10
                        while time.monotonic()<deadline:
                            pid=sql("SELECT pid FROM pg_stat_activity WHERE datname='interrupted' AND application_name='pg_restore' AND wait_event='advisory'",'postgres')
                            if pid:
                                assert pid.isdecimal()
                                assert sql('SELECT pg_terminate_backend('+pid+')','postgres')=='t'
                                break
                            time.sleep(.05) # bounded state poll; no timing-based success assumption
                        else:
                            raise RuntimeError('RESTORE_DID_NOT_REACH_INTERRUPTION_GATE')
                        interrupted.communicate(timeout=10)
                        assert interrupted.returncode!=0
                    finally:
                        if interrupted.poll() is None:
                            interrupted.kill();interrupted.communicate(timeout=5)
                assert sql("SELECT count(*) FROM pg_tables WHERE schemaname='public'",'interrupted')=='0'
            finally:
                holder.stdin.close();holder.wait(timeout=5)
            # Interrupted database is quarantined for this run, never reused as a restore target.
            sql('CREATE DATABASE restored;', 'postgres')
            run(['docker','exec','-i',name,'pg_restore','-U','postgres','-d','restored','--exit-on-error','--single-transaction','--no-owner','--no-acl'],dump)
            tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
            queries = []
            for table in tables:
                if not re.fullmatch('[a-z_]+',table):
                    raise ValueError('INVALID_TABLE')
                queries.append(f"SELECT '{table}',md5(coalesce(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text),'')) FROM public.{table} t")
            # Compare every restored table in two bounded database calls (including empty tables).
            query = ' UNION ALL '.join(queries)
            left, right = sorted(sql(query).splitlines()), sorted(sql(query,'restored').splitlines())
            if left != right:
                names = [line.split('|')[0] for line in left if line not in right]
                raise AssertionError('RESTORED_ROWS_DIFFER tables=' + ','.join(names))
            assert sql("SELECT count(*) FROM pathology_case c JOIN pathology_request r ON r.id=c.request_id JOIN patient p ON p.id=r.patient_id WHERE c.hospital_id=r.hospital_id AND p.hospital_id=r.hospital_id",'restored') == '1'
            assert sql("SELECT count(*) FROM audit_event",'restored') == '1'
            assert sql("SELECT count(*) FROM idempotency_command WHERE state='SUCCEEDED'",'restored') == '1'
            assert sql("SELECT id||':'||sha256||':'||byte_size FROM storage_version WHERE state='READY'",'restored') == refs
            for o in restored['objects']:
                assert digest(file_bytes(target/(o['id']+'.blob'))) == o['sha256']
            assert sql("SELECT DISTINCT root_id FROM storage_version",'restored')==restored['rootId']
            duplicate = subprocess.run(['docker','exec','-i',name,'psql','-U','postgres','-d','restored','-X','-v','ON_ERROR_STOP=1','-At'],input=b"INSERT INTO idempotency_command SELECT * FROM idempotency_command;",capture_output=True,timeout=5)
            assert duplicate.returncode!=0
            assert sql("SELECT count(*) FROM idempotency_command WHERE state='SUCCEEDED'",'restored')=='1'
            assert sql("SELECT count(*) FROM audit_event",'restored')=='1'
            assert file_bytes(original)==payload
            print(json.dumps({'contract':'SYN-RECOVERY-1','status':'PASS','postgresMajor':17,'migrations':37,'tablesCompared':len(tables),'objectBytes':len(payload),'objectSha256':digest(payload),'negativeChecks':10,'elapsedSeconds':round(time.monotonic()-started,2),'encryption':'NOT_CONFIGURED','offsite':'NOT_CONFIGURED','onlineRecovery':'UNTESTED','interruptedRestore':'ROLLED_BACK_QUARANTINED'},sort_keys=True))
        finally:
            # Only the exact random container created above; never a supplied name/target.
            signal.alarm(0)
            subprocess.run(['docker','rm','-f',name],capture_output=True,timeout=15)

if __name__ == '__main__':
    main()
