#!/usr/bin/env python3
"""Immutable synthetic release manifest; exact schema compatibility, no DB downgrade."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile
from local_proxy import dist_manifest, Target

ROOT=Path(__file__).resolve().parents[2]
SCHEMA=37
MAX_JAR=256*1024*1024

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for part in iter(lambda:f.read(65536),b''): h.update(part)
    return h.hexdigest()

def canonical(value): return json.dumps(value,sort_keys=True,separators=(',',':')).encode()

def migrations():
    files=sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
    if [int(p.name.split('__')[0][1:]) for p in files]!=list(range(1,SCHEMA+1)): raise ValueError('REVIEW_MIGRATION_COMPATIBILITY')
    return {p.name:sha(p) for p in files}

def check_jar(jar, expected):
    if jar.is_symlink() or not jar.is_file() or not 1<=jar.stat().st_size<=MAX_JAR: raise ValueError('INVALID_JAR')
    with zipfile.ZipFile(jar) as z:
        names=z.namelist()
        if len(names)>20000 or len(names)!=len(set(names)) or 'BOOT-INF/classes/com/pis/PisApplication.class' not in names or any('testfixture' in n or '..' in n.split('/') for n in names): raise ValueError('INVALID_APPLICATION_PACKAGE')
        prefix='BOOT-INF/classes/db/migration/'
        present={n.removeprefix(prefix) for n in names if n.startswith(prefix) and n.endswith('.sql')}
        if present!=set(expected): raise ValueError('JAR_MIGRATION_SET')
        for name,h in expected.items():
            info=z.getinfo(prefix+name)
            if info.file_size>1024*1024 or hashlib.sha256(z.read(info)).hexdigest()!=h: raise ValueError('JAR_MIGRATION_HASH')

def build(jar, dist, output, revision):
    if not re.fullmatch('[a-f0-9]{40}',revision): raise ValueError('INVALID_REVISION')
    parent=output.absolute().parent
    if parent.parent!=Path(tempfile.gettempdir()).resolve() or not parent.name.startswith('pis-release-') or parent.is_symlink() or parent.stat().st_uid!=os.geteuid() or stat.S_IMODE(parent.stat().st_mode)!=0o700: raise ValueError('OWNED_PRIVATE_TEMP_TARGET_REQUIRED')
    if output.exists() or output.is_symlink(): raise ValueError('EXISTING_RELEASE_FORBIDDEN')
    source=migrations();check_jar(jar,source);files=dist_manifest(dist)
    # Fresh output only. A failure leaves an incomplete, unusable directory; never overwritten.
    output.mkdir(mode=0o700)
    shutil.copyfile(jar,output/'application.jar');shutil.copytree(dist,output/'dist')
    manifest={'contract':'SYN-RELEASE-1','sourceRevision':revision,'backendVersion':'0.0.1-SNAPSHOT',
        'schemaMin':SCHEMA,'schemaMax':SCHEMA,'migrations':source,'jarSha256':sha(output/'application.jar'),
        'dist':files,'frontendLockSha256':sha(ROOT/'frontend/package-lock.json'),
        'mode':'LOOPBACK_SYNTHETIC_ONLY','tls':'NOT_CONFIGURED','domain':'NOT_CONFIGURED'}
    (output/'release.json').write_bytes(canonical(manifest))
    (output/'release.sha256').write_text(hashlib.sha256(canonical(manifest)).hexdigest()+'\n')
    verify(output,SCHEMA)
    return manifest

def verify(root, schema):
    if root.is_symlink() or any(p.is_symlink() for p in root.parents): raise ValueError('RELEASE_LINK')
    if not root.is_dir() or root.stat().st_uid!=os.geteuid() or stat.S_IMODE(root.stat().st_mode)!=0o700: raise ValueError('PRIVATE_RELEASE_REQUIRED')
    p=root/'release.json'
    if p.is_symlink() or not p.is_file() or p.stat().st_size>262144: raise ValueError('MANIFEST_UNAVAILABLE')
    data=p.read_bytes();m=json.loads(data)
    if m.get('contract')!='SYN-RELEASE-1' or m.get('mode')!='LOOPBACK_SYNTHETIC_ONLY' or m.get('schemaMin')!=SCHEMA or m.get('schemaMax')!=SCHEMA or schema!=SCHEMA: raise ValueError('INCOMPATIBLE_SCHEMA')
    if (root/'release.sha256').is_symlink() or (root/'release.sha256').read_text().strip()!=hashlib.sha256(data).hexdigest(): raise ValueError('MANIFEST_HASH')
    if m['migrations']!=migrations(): raise ValueError('MIGRATION_DRIFT')
    if {p.name for p in root.iterdir()}!={'application.jar','dist','release.json','release.sha256'}: raise ValueError('UNMANIFESTED_RELEASE_FILE')
    check_jar(root/'application.jar',m['migrations'])
    if sha(root/'application.jar')!=m['jarSha256'] or dist_manifest(root/'dist')!=m['dist']: raise ValueError('RELEASE_HASH')
    return m

class Switch:
    """Only a caller's isolated application process can be selected; no migration rollback."""
    def __init__(self,active): self.active=active;self.current=None;self.verified={}
    def activate(self,root,port,schema,ready):
        verify(root,schema)
        if not ready(): raise ValueError('CANDIDATE_NOT_READY')
        verify(root,schema) # Revalidate after the readiness wait; no mutated candidate may activate.
        target=Target(root/'dist',port)
        self.active.set(target);self.current=root;self.verified[root]=port
    def rollback(self,root,schema,ready):
        if root not in self.verified: raise ValueError('UNVERIFIED_ROLLBACK')
        self.activate(root,self.verified[root],schema,ready)

def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--dist',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--revision',required=True);a=p.parse_args()
    if os.geteuid()==0: raise ValueError('NON_ROOT_REQUIRED')
    build(a.jar,a.dist,a.output,a.revision)
    print('SYN_RELEASE_CREATED_NOT_DEPLOYED')
if __name__=='__main__': main()
