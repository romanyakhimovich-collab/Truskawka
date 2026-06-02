param(
    [int]$Port = 8899
)

$ErrorActionPreference = "Stop"

Add-Type -TypeDefinition @"
using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace TruskawkaRelay {
    public static class Server {
        private const int MaxFrameSize = 1024 * 1024;
        private static readonly ConcurrentDictionary<TcpClient, byte> Clients = new ConcurrentDictionary<TcpClient, byte>();

        public static void Run(int port) {
            var listener = new TcpListener(IPAddress.Any, port);
            listener.Start();
            Console.WriteLine("Truskawka emulator relay listening on 0.0.0.0:" + port);

            while (true) {
                var client = listener.AcceptTcpClient();
                client.NoDelay = true;
                client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                Clients.TryAdd(client, 0);
                Console.WriteLine("client connected: " + client.Client.RemoteEndPoint);
                Task.Run(() => HandleClient(client));
            }
        }

        private static void HandleClient(TcpClient client) {
            try {
                using (client) {
                    var stream = client.GetStream();
                    while (true) {
                        var header = ReadExact(stream, 4);
                        if (header == null) break;

                        var length = (header[0] << 24) | (header[1] << 16) | (header[2] << 8) | header[3];
                        if (length <= 0 || length > MaxFrameSize) break;

                        var payload = ReadExact(stream, length);
                        if (payload == null) break;

                        var frame = new byte[4 + payload.Length];
                        Buffer.BlockCopy(header, 0, frame, 0, 4);
                        Buffer.BlockCopy(payload, 0, frame, 4, payload.Length);
                        Broadcast(client, frame);
                    }
                }
            } catch (IOException) {
            } catch (SocketException) {
            } finally {
                byte ignored;
                Clients.TryRemove(client, out ignored);
                Console.WriteLine("client disconnected");
            }
        }

        private static byte[] ReadExact(Stream stream, int length) {
            var buffer = new byte[length];
            var offset = 0;
            while (offset < length) {
                var read = stream.Read(buffer, offset, length - offset);
                if (read <= 0) return null;
                offset += read;
            }
            return buffer;
        }

        private static void Broadcast(TcpClient sender, byte[] frame) {
            foreach (var entry in Clients) {
                var client = entry.Key;
                if (ReferenceEquals(client, sender)) continue;

                try {
                    var stream = client.GetStream();
                    stream.Write(frame, 0, frame.Length);
                    stream.Flush();
                } catch {
                    byte ignored;
                    Clients.TryRemove(client, out ignored);
                    client.Close();
                }
            }
        }
    }
}
"@

[TruskawkaRelay.Server]::Run($Port)
