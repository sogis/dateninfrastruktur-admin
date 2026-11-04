#!/usr/bin/env python3
"""Test-only transparent wire observer. Records header NAMES only, never values.
No header sanitization, authorization, path rewrite or file buffering here.
The product file origin remains unmodified rclone WebDAV.
"""
import http.client,http.server,json
SENSITIVE={'authorization','cookie','x-access-token','x-id-token','x-userinfo','x-di-gateway-credential'}
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def do_HEAD(self):self.forward()
    def do_GET(self):self.forward()
    def forward(self):
        seen=sorted(k.lower() for k in self.headers if k.lower() in SENSITIVE)
        print(json.dumps({'method':self.command,'credentialHeaderNames':seen}),flush=True)
        c=http.client.HTTPConnection('origin',8080,timeout=8)
        try:
            headers={k:v for k,v in self.headers.items() if k.lower() not in {'host','connection'}}
            c.request(self.command,self.path,headers=headers);r=c.getresponse();self.send_response(r.status)
            for k,v in r.getheaders():
                if k.lower() not in {'connection','transfer-encoding','server','date'}:self.send_header(k,v)
            self.send_header('Connection','close');self.end_headers()
            if self.command!='HEAD':
                while chunk:=r.read(65536):self.wfile.write(chunk)
        except Exception:
            self.close_connection=True
        finally:c.close();self.close_connection=True
http.server.ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
