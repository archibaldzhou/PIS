"""Real owned subprocesses; never discover or kill a process by port."""
import http.client
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

class Lifecycle(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pis-proxy-lifecycle-')
        self.dist=Path(self.temp.name);(self.dist/'index.html').write_text('synthetic');(self.dist/'app.js').write_text('synthetic')
        self.children=[]
        with socket.socket() as s:s.bind(('127.0.0.1',0));self.port=s.getsockname()[1]
    def tearDown(self):
        for p in self.children:
            if p.poll() is None:p.terminate()
            try:p.wait(timeout=5)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
            p.stderr.close()
        self.temp.cleanup()
    def start(self,dist=None,upstream=8080):
        p=subprocess.Popen([sys.executable,str(Path(__file__).with_name('local_proxy.py')),'--dist',str(dist or self.dist),'--port',str(self.port),'--upstream-port',str(upstream)],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        self.children.append(p);return p
    def request(self):
        c=http.client.HTTPConnection('127.0.0.1',self.port,timeout=.5)
        try:c.request('GET','/');r=c.getresponse();return r.status,r.read()
        finally:c.close()
    def ready(self,p):
        deadline=time.monotonic()+5
        while p.poll() is None and time.monotonic()<deadline:
            try:
                if self.request()==(200,b'synthetic'):return
            except OSError:pass
            time.sleep(.01) # Poll an observable readiness condition, never assume success after sleep.
        self.fail('Owned proxy never became ready')
    def stopped(self,p,sig):
        p.send_signal(sig);self.assertEqual(p.wait(timeout=5),128+sig)
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(('127.0.0.1',self.port)) # No live listener remains; TIME_WAIT is permitted.
    def test_successive_processes_reclaim_time_wait_without_reusing_server(self):
        for _ in range(3):
            p=self.start();self.ready(p);self.assertEqual(self.request()[0],200);self.stopped(p,signal.SIGTERM)
    def test_interrupt_cancels_owned_inflight_request_and_releases_listener(self):
        with socket.socket() as upstream:
            upstream.bind(('127.0.0.1',0));upstream.listen(1);upstream.settimeout(3)
            p=self.start(upstream=upstream.getsockname()[1]);self.ready(p)
            with socket.create_connection(('127.0.0.1',self.port),timeout=3) as client:
                client.sendall(f'POST /api/action HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\nContent-Length: 99\r\n\r\nx'.encode())
                connection,_=upstream.accept()
                with connection:
                    connection.settimeout(3)
                    self.assertIn(b'POST /api/action',connection.recv(4096))
                    self.stopped(p,signal.SIGINT)
        next_process=self.start();self.ready(next_process);self.stopped(next_process,signal.SIGTERM)
    def test_startup_failure_and_live_port_collision_never_stop_owner(self):
        failed=self.start(self.dist/'missing');self.assertNotEqual(failed.wait(timeout=5),0)
        owner=self.start();self.ready(owner)
        competing=self.start();self.assertNotEqual(competing.wait(timeout=5),0)
        self.assertIsNone(owner.poll());self.assertEqual(self.request(),(200,b'synthetic'))
        self.stopped(owner,signal.SIGTERM)
        replacement=self.start();self.ready(replacement);self.stopped(replacement,signal.SIGTERM)

if __name__=='__main__':unittest.main()
