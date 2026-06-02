#!/usr/bin/env python3
import socket
import struct
import threading

HOST = "0.0.0.0"
PORT = 8899
MAX_FRAME_SIZE = 1024 * 1024

clients = set()
clients_lock = threading.Lock()


def read_exact(conn, size):
    data = bytearray()
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            return None
        data.extend(chunk)
    return bytes(data)


def broadcast(sender, frame):
    stale = []
    with clients_lock:
        targets = [client for client in clients if client is not sender]
    for client in targets:
        try:
            client.sendall(frame)
        except OSError:
            stale.append(client)
    if stale:
        with clients_lock:
            for client in stale:
                clients.discard(client)


def handle_client(conn, address):
    print(f"client connected: {address[0]}:{address[1]}", flush=True)
    with clients_lock:
        clients.add(conn)
    try:
        while True:
            header = read_exact(conn, 4)
            if header is None:
                break
            length = struct.unpack(">I", header)[0]
            if length <= 0 or length > MAX_FRAME_SIZE:
                break
            payload = read_exact(conn, length)
            if payload is None:
                break
            broadcast(conn, header + payload)
    finally:
        with clients_lock:
            clients.discard(conn)
        conn.close()
        print(f"client disconnected: {address[0]}:{address[1]}", flush=True)


def main():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((HOST, PORT))
        server.listen()
        print(f"Truskawka emulator relay listening on {HOST}:{PORT}", flush=True)
        while True:
            conn, address = server.accept()
            thread = threading.Thread(target=handle_client, args=(conn, address), daemon=True)
            thread.start()


if __name__ == "__main__":
    main()
