import http.client
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from local_proxy import Active, Server, Target
from release import build, verify, migrations, Switch

class Fixture(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def do_GET(self):
        body=b'{"synthetic":true}'
        self.send_response(409 if self.path=='/api/conflict' else 200)
        self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)))
        self.send_header('Set-Cookie','PIS_SESSION=synthetic-only; HttpOnly; SameSite=Lax; Path=/')
        self.send_header('X-Trace-Id','synthetic-trace');self.end_headers();self.wfile.write(body)
    def do_POST(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        allowed=self.headers.get('X-CSRF-TOKEN')=='synthetic-csrf' and self.headers.get('Cookie')=='PIS_SESSION=synthetic-only'
        self.send_response(200 if allowed else 403);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)

class Contracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pis-release-contract-');self.root=Path(self.temp.name)
        self.dist=self.root/'dist';self.dist.mkdir();(self.dist/'assets').mkdir();(self.dist/'index.html').write_text('<html>synthetic dist</html>');(self.dist/'assets/app.js').write_text('/* synthetic contract */')
        self.upstream=ThreadingHTTPServer(('127.0.0.1',0),Fixture);self.upstream.daemon_threads=True
        self.thread=threading.Thread(target=self.upstream.serve_forever,daemon=True);self.thread.start()
        self.active=Active(Target(self.dist,self.upstream.server_port));self.proxy=Server(0,self.active)
        self.proxy_thread=threading.Thread(target=self.proxy.serve_forever,daemon=True);self.proxy_thread.start()
    def tearDown(self):
        self.proxy.shutdown();self.proxy.server_close();self.upstream.shutdown();self.upstream.server_close();self.temp.cleanup()
    def request(self,path,method='GET',headers=None,body=None):
        c=http.client.HTTPConnection('127.0.0.1',self.proxy.server_port,timeout=3)
        try:
            c.request(method,path,body=body,headers=headers or {});r=c.getresponse();return r.status,dict(r.getheaders()),r.read()
        finally:c.close()
    def jar(self):
        jar=self.root/'synthetic-fixture.jar'
        with zipfile.ZipFile(jar,'w') as z:
            z.writestr('BOOT-INF/classes/com/pis/PisApplication.class',b'CONTRACT_ONLY_NOT_EXECUTABLE')
            for n in migrations():z.write(Path(__file__).resolve().parents[2]/'backend/src/main/resources/db/migration'/n,'BOOT-INF/classes/db/migration/'+n)
        return jar
    def test_real_http_deep_link_assets_api_and_cookie_csrf_forwarding(self):
        status,headers,body=self.request('/nested/link');self.assertEqual(status,200);self.assertEqual(body,(self.dist/'index.html').read_bytes());self.assertEqual(headers['Cache-Control'],'no-store')
        self.assertEqual(self.request('/assets/app.js')[0],200);self.assertEqual(self.request('/assets/missing.js')[0],404)
        self.assertEqual(self.request('/actuator/env')[0],404)
        status,headers,body=self.request('/api/conflict');self.assertEqual(status,409);self.assertEqual(headers['Set-Cookie'],'PIS_SESSION=synthetic-only; HttpOnly; SameSite=Lax; Path=/');self.assertEqual(body,b'{"synthetic":true}')
        self.assertEqual(self.request('/api/action','POST',body='synthetic')[0],403)
        self.assertEqual(self.request('/api/action','POST',headers={'X-CSRF-TOKEN':'synthetic-csrf','Cookie':'PIS_SESSION=synthetic-only'},body='synthetic')[2],b'synthetic')
        self.assertEqual(self.request('/api/action','POST',headers={'X-CSRF-TOKEN':'synthetic-csrf','Cookie':'PIS_SESSION=synthetic-only'},body='synthetic')[0],200)
    def test_path_host_and_mutated_asset_fail_closed(self):
        for path in ('/%2e%2e/secret','//external.invalid/api','/assets/%252e%252e/secret','/a%5cb','/a#fragment'):
            with self.subTest(path=path): self.assertEqual(self.request(path)[0],400)
        self.assertEqual(self.request('/',headers={'Host':'external.invalid'})[0],400)
        (self.dist/'assets/app.js').write_text('changed');self.assertEqual(self.request('/assets/app.js')[0],503)
        (self.dist/'assets/app.js').unlink();(self.dist/'assets/app.js').symlink_to(self.dist/'index.html');self.assertEqual(self.request('/assets/app.js')[0],503)
    def test_down_upstream_is_502_not_spa_and_concurrency_is_bounded(self):
        with socket.socket() as s:s.bind(('127.0.0.1',0));unused=s.getsockname()[1]
        self.active.set(Target(self.dist,unused));self.assertEqual(self.request('/api/hello')[0],502)
        for _ in range(8):self.assertTrue(self.proxy.slots.acquire(timeout=2))
        try:
            status,headers,_=self.request('/');self.assertEqual(status,503);self.assertEqual(headers['Retry-After'],'1')
        finally:
            for _ in range(8):self.proxy.slots.release()
        self.assertEqual(self.request('/')[0],200)
    def test_manifest_reproducible_corruption_schema_and_failed_switch_keep_prior(self):
        jar=self.jar();a=self.root/'a';b=self.root/'b'
        build(jar,self.dist,a,'a'*40);build(jar,self.dist,b,'a'*40)
        self.assertEqual((a/'release.sha256').read_bytes(),(b/'release.sha256').read_bytes())
        with self.assertRaises(ValueError):build(jar,self.dist,a,'a'*40)
        unsafe=self.root/'nested';unsafe.mkdir(mode=0o700)
        with self.assertRaises(ValueError):build(jar,self.dist,unsafe/'release','a'*40)
        self.assertFalse((unsafe/'release').exists())
        with self.assertRaises(ValueError):verify(a,38)
        switch=Switch(self.active);switch.activate(a,self.upstream.server_port,37,lambda:True)
        with self.assertRaises(ValueError):switch.activate(b,self.upstream.server_port,37,lambda:False)
        self.assertEqual(switch.current,a)
        with self.assertRaises(ValueError):switch.rollback(b,37,lambda:True)
        switch.activate(b,self.upstream.server_port,37,lambda:True)
        with self.assertRaises(ValueError):switch.rollback(a,38,lambda:True)
        self.assertEqual(switch.current,b)
        switch.rollback(a,37,lambda:True);self.assertEqual(switch.current,a)
        (b/'dist/index.html').write_text('corrupt')
        with self.assertRaises(ValueError):switch.activate(b,self.upstream.server_port,37,lambda:True)
        self.assertEqual(switch.current,a)
        (b/'dist/index.html').write_bytes((self.dist/'index.html').read_bytes())
        def changed_during_readiness():
            (b/'dist/index.html').write_text('late mutation');return True
        with self.assertRaises(ValueError):switch.activate(b,self.upstream.server_port,37,changed_during_readiness)
        self.assertEqual(switch.current,a)
    def test_root_and_symlink_and_request_length_are_rejected(self):
        with patch('os.geteuid',return_value=0):
            with self.assertRaises(ValueError):Server(0,self.active)
        link=self.root/'link';link.symlink_to(self.dist)
        with self.assertRaises(ValueError):Target(link,8080)
        c=socket.create_connection(('127.0.0.1',self.proxy.server_port),timeout=3)
        with c:
            c.sendall(f'POST /api/action HTTP/1.1\r\nHost: 127.0.0.1:{self.proxy.server_port}\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx'.encode());self.assertIn(b'413',c.recv(1024))

if __name__=='__main__':unittest.main()
