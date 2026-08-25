#!/usr/bin/env python3
"""Minimal RCON client for the Phase C smoke gate.

Usage: smoke-rcon.py <port> <password> <command> [<command> ...]

Sends each command in order and prints "> cmd" followed by the server's reply
with Minecraft colour codes stripped. Deliberately dependency-free: the gate has
to run on a bare checkout, and `scripts/rcon.sh` needs `npm install` first.
"""
import re
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2
COLOUR = re.compile("§.")


def send(sock, request_id, packet_type, body):
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def read_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("connection closed by the server")
        buf += chunk
    return buf


def recv(sock):
    (length,) = struct.unpack("<i", read_exactly(sock, 4))
    payload = read_exactly(sock, length)
    request_id, packet_type = struct.unpack("<ii", payload[:8])
    body = payload[8:-2].decode("utf-8", "replace")
    return request_id, packet_type, body


def connect(port, password, attempts=30):
    last = None
    for _ in range(attempts):
        try:
            sock = socket.create_connection(("127.0.0.1", port), timeout=15)
            sock.settimeout(30)
            send(sock, 1, SERVERDATA_AUTH, password)
            request_id, _, _ = recv(sock)
            if request_id == -1:
                raise RuntimeError("RCON authentication failed")
            return sock
        except (OSError, EOFError) as e:
            last = e
            time.sleep(1)
    raise SystemExit("!! could not connect to RCON on port %s: %s" % (port, last))


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    port = int(sys.argv[1])
    password = sys.argv[2]
    commands = sys.argv[3:]

    sock = connect(port, password)
    try:
        for i, command in enumerate(commands):
            print("> %s" % command, flush=True)
            send(sock, 100 + i, SERVERDATA_EXECCOMMAND, command)
            _, _, body = recv(sock)
            text = COLOUR.sub("", body).rstrip()
            print(text if text else "(no reply)", flush=True)
            print(flush=True)
    finally:
        sock.close()


if __name__ == "__main__":
    main()
