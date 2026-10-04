#!/usr/bin/env python3
"""Non-root, loopback-only dist rehearsal proxy. Not a production TLS terminator."""
import argparse
import hashlib
import http.client
import json
import mimetypes
import os
from pathlib import Path
import socket
import signal
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from urllib.parse import unquote, urlsplit

MAX_BODY = 64 * 1024 * 1024

def dist_manifest(root):
    root = Path(root).absolute()
    if root.is_symlink() or any(p.is_symlink() for p in root.parents) or not root.is_dir():
        raise ValueError('INVALID_DIST')
    files = {}
    total = 0
    for p in root.rglob('*'):
        if p.is_symlink():
            raise ValueError('DIST_LINK_FORBIDDEN')
        if p.is_file():
            name = p.relative_to(root).as_posix()
            total += p.stat().st_size
            if total > MAX_BODY: raise ValueError('DIST_TOTAL_LIMIT')
            if p.stat().st_size > MAX_BODY or len(files) >= 512 or not name.endswith(('.html','.js','.css','.png','.svg','.woff2','.ico')):
                raise ValueError('DIST_LIMIT_OR_TYPE')
            files[name] = hashlib.sha256(p.read_bytes()).hexdigest()
    if 'index.html' not in files or not any(n.endswith('.js') for n in files):
        raise ValueError('DIST_INCOMPLETE')
    return files

class Target:
    def __init__(self, root, port):
        if not 1024 <= port <= 65535:
            raise ValueError('INVALID_LOCAL_PORT')
        self.root = Path(root).absolute()
        self.hashes = dist_manifest(self.root)
        self.port = port

class Active:
    def __init__(self, target):
        self._target = target
        self.lock = threading.Lock()
    def get(self):
        with self.lock:
            return self._target
    def set(self, target):
        with self.lock:
            self._target = target

class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = False
    block_on_close = True
    # Reclaim TIME_WAIT only; no SO_REUSEPORT and no sharing a live listener.
    allow_reuse_address = True
    def __init__(self, port, active):
        if os.geteuid() == 0:
            raise ValueError('NON_ROOT_REQUIRED')
        self.handlers = set()
        self.handler_lock = threading.Lock()
        self.active = active
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(('127.0.0.1', port), Handler)
    def server_close(self):
        with self.handler_lock: handlers=list(self.handlers)
        for handler in handlers: handler.expire()
        super().server_close() # Join only this server's request threads.
    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nRetry-After: 1\r\nConnection: close\r\n\r\n')
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise
    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()
    def handle_error(self, request, address):
        # No raw request, URL, headers, identity or traceback in logs.
        pass

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'PIS-local-rehearsal'
    sys_version = ''
    def log_message(self, *args):
        pass
    def setup(self):
        super().setup()
        self.connection.settimeout(15)
        self.upstream_connection=None
        self.deadline=threading.Timer(20,self.expire)
        with self.server.handler_lock:self.server.handlers.add(self)
        self.deadline.daemon=True;self.deadline.start()
    def expire(self):
        # Absolute connection budget also interrupts slow-drip upstream reads.
        for channel in (self.connection,getattr(self.upstream_connection,'sock',None)):
            if channel:
                try:channel.shutdown(socket.SHUT_RDWR)
                except OSError:pass
    def finish(self):
        self.deadline.cancel()
        try:super().finish()
        finally:
            with self.server.handler_lock:self.server.handlers.discard(self)
    def fail(self, status, code):
        body = json.dumps({'code':code}).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/problem+json')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Connection','close')
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)
        self.close_connection = True
    def do_GET(self): self.serve()
    def do_HEAD(self): self.serve()
    def do_POST(self): self.serve()
    def do_PUT(self): self.serve()
    def do_DELETE(self): self.serve()
    def do_PATCH(self): self.serve()
    def do_OPTIONS(self): self.serve()
    def serve(self):
        target = self.server.active.get()  # One immutable target for this entire request.
        if self.requestline.split(' ')[1].startswith('//'):
            return self.fail(400,'INVALID_LOCAL_PATH')
        raw = urlsplit(self.path)
        path = unquote(raw.path)
        if raw.scheme or raw.netloc or raw.fragment or len(self.path)>2048 or '\\' in path or '%' in path or any(ord(c)<32 for c in self.path) or any(p in ('.','..') for p in path.split('/')):
            return self.fail(400,'INVALID_LOCAL_PATH')
        if self.headers.get('Host') not in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'):
            return self.fail(400,'INVALID_LOCAL_HOST')
        lengths=self.headers.get_all('Content-Length',[])
        if len(lengths)>1 or self.headers.get('Transfer-Encoding') or lengths and (not lengths[0].isdigit() or len(lengths[0])>9 or int(lengths[0])>MAX_BODY):
            return self.fail(413,'REQUEST_LIMIT')
        if path.startswith('/api/') or path=='/api':
            return self.proxy(target)
        if self.command not in ('GET','HEAD'):
            return self.fail(405,'STATIC_READ_ONLY')
        name = path.lstrip('/') or 'index.html'
        if name not in target.hashes:
            if '.' in name or name.startswith(('assets/','actuator','api')):
                return self.fail(404,'STATIC_NOT_FOUND')
            name='index.html' # Only extensionless SPA deep links.
        p=target.root/name
        if p.is_symlink() or not p.is_file() or any(parent.is_symlink() for parent in p.parents):
            return self.fail(503,'DIST_UNAVAILABLE')
        with p.open('rb') as stream: data=stream.read(MAX_BODY+1)
        if hashlib.sha256(data).hexdigest()!=target.hashes[name]:
            return self.fail(503,'DIST_HASH_CHANGED')
        self.send_response(200)
        self.send_header('Content-Type',mimetypes.guess_type(name)[0] or 'application/octet-stream')
        self.send_header('Content-Length',str(len(data)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('Connection','close');self.close_connection=True
        self.end_headers()
        if self.command!='HEAD': self.wfile.write(data)
    def proxy(self, target):
        # Fixed loopback destination; never accept forwarded host/URL or follow redirects.
        connection=http.client.HTTPConnection('127.0.0.1',target.port,timeout=15)
        self.upstream_connection=connection
        allowed={'content-type','cookie','x-csrf-token','idempotency-key','range','accept'}
        headers={k:v for k,v in self.headers.items() if k.lower() in allowed}
        headers['Host']=f'127.0.0.1:{target.port}'
        headers['Connection']='close'
        size=int(self.headers.get('Content-Length','0'))
        headers['Content-Length']=str(size)
        sent=False
        try:
            connection.putrequest(self.command,self.path,skip_host=True,skip_accept_encoding=True)
            for k,v in headers.items(): connection.putheader(k,v)
            connection.endheaders()
            left=size
            while left:
                chunk=self.rfile.read(min(left,65536))
                if not chunk: raise OSError('INCOMPLETE_REQUEST')
                connection.send(chunk);left-=len(chunk)
            upstream=connection.getresponse()
            # Buffer within explicit limit before forwarding: never disguise truncation as success.
            body=upstream.read(MAX_BODY+1)
            if len(body)>MAX_BODY: return self.fail(502,'UPSTREAM_LIMIT')
            if upstream.length not in (None,0): return self.fail(502,'UPSTREAM_TRUNCATED')
            self.send_response(upstream.status)
            for k,v in upstream.getheaders():
                if k.lower() in {'content-type','set-cookie','x-trace-id','retry-after','content-range','accept-ranges','etag','x-content-sha256','x-viewer-quota-units','x-viewer-qualification-checks'}:
                    self.send_header(k,v)
            self.send_header('Cache-Control','no-store')
            self.send_header('Content-Length',str(len(body)))
            self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('Connection','close');self.close_connection=True
            self.end_headers();sent=True
            if self.command!='HEAD': self.wfile.write(body)
        except (OSError,http.client.HTTPException):
            if not sent: self.fail(502,'UPSTREAM_UNAVAILABLE')
        finally:
            connection.close()

def main():
    parser=argparse.ArgumentParser(description='Loopback synthetic dist rehearsal; TLS/domain NOT_CONFIGURED')
    parser.add_argument('--dist',type=Path,required=True)
    parser.add_argument('--port',type=int,default=5175)
    parser.add_argument('--upstream-port',type=int,default=8080)
    args=parser.parse_args()
    if not 1024<=args.port<=65535: raise ValueError('INVALID_LISTEN_PORT')
    def stop(signum, _frame):
        raise SystemExit(128+signum) # finally closes our listener and in-flight connections.
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    server=Server(args.port,Active(Target(args.dist,args.upstream_port)))
    try: server.serve_forever(poll_interval=.1)
    finally: server.server_close()
if __name__=='__main__': main()
