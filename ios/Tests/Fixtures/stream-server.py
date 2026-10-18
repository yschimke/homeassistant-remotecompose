"""Dependency-free loopback fixture for StreamRoute's text-only JSON contract."""
import base64
import hashlib
import json
import socket
import struct


def read_exact(connection, size):
    data = b""
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError()
        data += chunk
    return data


def send_json(connection, value):
    data = json.dumps(value).encode()
    header = bytes([0x81, len(data)]) if len(data) < 126 else b"\x81\x7e" + struct.pack("!H", len(data))
    connection.sendall(header + data)


with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    print(listener.getsockname()[1], flush=True)
    connection, _ = listener.accept()
    with connection:
        request = b""
        while not request.endswith(b"\r\n\r\n"):
            request += read_exact(connection, 1)
        headers = dict(line.split(":", 1) for line in request.decode().split("\r\n")[1:] if ":" in line)
        key = next(value.strip() for name, value in headers.items() if name.lower() == "sec-websocket-key")
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        connection.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").encode())
        try:
            while True:
                first, second = read_exact(connection, 2)
                opcode = first & 15
                length = second & 127
                if length == 126:
                    length = struct.unpack("!H", read_exact(connection, 2))[0]
                elif length == 127:
                    length = struct.unpack("!Q", read_exact(connection, 8))[0]
                mask = read_exact(connection, 4) if second & 128 else None
                payload = read_exact(connection, length)
                if mask:
                    payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
                if opcode == 8:
                    break
                # Like StreamRoute: binary commands are ignored, not decoded as JSON.
                if opcode != 1:
                    continue
                command = json.loads(payload)
                if command["type"] == "subscribe":
                    send_json(connection, {"type": "state", "bindings": {"light.test.is_on": True}})
                elif command["type"] == "call_service":
                    send_json(connection, {"type": "state", "bindings": {"echo": json.dumps(command["service_data"])}})
        except (EOFError, ConnectionResetError):
            pass
