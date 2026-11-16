"""A raw TCP service that needs PROXY protocol v2. It says the address of the client in the
header, then it echoes each byte. At the end of the input it answers `bye`."""
import socket
import socketserver
import struct

SIGNATURE = b"\r\n\r\n\x00\r\nQUIT\n"


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        head = self.rfile.read(16)
        if head[:12] != SIGNATURE:
            self.wfile.write(b"no PROXY header\n")
            return
        body = self.rfile.read(struct.unpack(">H", head[14:16])[0])
        client = socket.inet_ntoa(body[0:4])
        self.wfile.write(f"client {client} via {self.client_address[0]}\n".encode())
        while chunk := self.rfile.read1(65536):
            self.wfile.write(chunk)
        self.wfile.write(b"bye\n")


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer.daemon_threads = True
socketserver.ThreadingTCPServer.request_queue_size = 1024
socketserver.ThreadingTCPServer(("0.0.0.0", 9100), Handler).serve_forever()
