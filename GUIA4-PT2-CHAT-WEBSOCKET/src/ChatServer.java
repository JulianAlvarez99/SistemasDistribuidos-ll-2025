import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {

    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8081;
    private static final Set<WebSocketClient> clients = ConcurrentHashMap.newKeySet();
    private static final Map<WebSocketClient, String> clientNicknames = new ConcurrentHashMap<>();
    private static final String BASE_PATH = "C:/Users/julia/IdeaProjects/SistemasDistribuidos-ll-2025/GUIA4-PT2-CHAT-WEBSOCKET/src/";

    public static void main(String[] args) {
        new Thread(ChatServer::startHttpServer).start();
        new Thread(ChatServer::startWebSocketServer).start();

        System.out.println("Chat Server started");
        System.out.println("HTTP Server: http://localhost:" + HTTP_PORT);
        System.out.println("WebSocket Server: ws://localhost:" + WS_PORT);
    }

    private static void startHttpServer() {
        try (ServerSocket serverSocket = new ServerSocket(HTTP_PORT)) {
            System.out.println("HTTP Server listening on port " + HTTP_PORT);

            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleHttpRequest(client)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleHttpRequest(Socket client) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = client.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            System.out.println("HTTP Request: " + requestLine);

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";

            // Skip headers
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // Read and discard headers
            }

            serveFile(out, path);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void serveFile(OutputStream out, String path) throws IOException {
        String filename;
        String contentType;

        if (path.equals("/") || path.equals("/index.html")) {
            filename = "index.html";
            contentType = "text/html";
        } else if (path.equals("/styles.css")) {
            filename = "styles.css";
            contentType = "text/css";
        } else if (path.equals("/client.js")) {
            filename = "client.js";
            contentType = "application/javascript";
        } else {
            sendNotFound(out);
            return;
        }

        try {
            String filePath = BASE_PATH + filename;
            byte[] content = Files.readAllBytes(Paths.get(filePath));

            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: " + contentType + "; charset=UTF-8\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "Connection: close\r\n\r\n";

            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.flush();

        } catch (IOException e) {
            sendNotFound(out);
        }
    }

    private static void sendNotFound(OutputStream out) throws IOException {
        String response = "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Connection: close\r\n\r\n" +
                        "404 Not Found";
        out.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private static void startWebSocketServer() {
        try (ServerSocket serverSocket = new ServerSocket(WS_PORT)) {
            System.out.println("WebSocket Server listening on port " + WS_PORT);

            while (true) {
                Socket client = serverSocket.accept();
                WebSocketClient wsClient = new WebSocketClient(client);
                new Thread(wsClient).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class WebSocketClient implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private String nickname;

        public WebSocketClient(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                performHandshake();
                clients.add(this);

                String message;
                while ((message = readWebSocketFrame()) != null) {
                    handleMessage(message);
                }

            } catch (Exception e) {
                System.out.println("Client disconnected: " + nickname);
            } finally {
                disconnect();
            }
        }

        private void performHandshake() throws IOException {
            Map<String, String> headers = new HashMap<>();
            String line;

            while (!(line = in.readLine()).isEmpty()) {
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    headers.put(key, value);
                }
            }

            String key = headers.get("Sec-WebSocket-Key");
            String acceptKey = generateAcceptKey(key);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.print("HTTP/1.1 101 Switching Protocols\r\n");
            out.print("Upgrade: websocket\r\n");
            out.print("Connection: Upgrade\r\n");
            out.print("Sec-WebSocket-Accept: " + acceptKey + "\r\n");
            out.print("\r\n");
            out.flush();
        }

        private String generateAcceptKey(String key) {
            try {
                String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] hash = md.digest((key + magic).getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private String readWebSocketFrame() throws IOException {
            int b0 = socket.getInputStream().read();
            if (b0 == -1) return null;

            int b1 = socket.getInputStream().read();
            boolean masked = (b1 & 0x80) != 0;
            int length = b1 & 0x7F;

            if (length == 126) {
                length = (socket.getInputStream().read() << 8) | socket.getInputStream().read();
            } else if (length == 127) {
                throw new IOException("Payload too large");
            }

            byte[] maskingKey = new byte[4];
            if (masked) {
                socket.getInputStream().read(maskingKey);
            }

            byte[] payload = new byte[length];
            socket.getInputStream().read(payload);

            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
                }
            }

            return new String(payload, StandardCharsets.UTF_8);
        }

        public void sendMessage(String message) {
            try {
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();

                frame.write(0x81);

                if (payload.length < 126) {
                    frame.write(payload.length);
                } else if (payload.length < 65536) {
                    frame.write(126);
                    frame.write((payload.length >> 8) & 0xFF);
                    frame.write(payload.length & 0xFF);
                }

                frame.write(payload);
                socket.getOutputStream().write(frame.toByteArray());
                socket.getOutputStream().flush();
            } catch (IOException e) {
                disconnect();
            }
        }

        private void handleMessage(String message) {
            try {
                if (message.startsWith("{") && message.contains("\"type\"")) {
                    if (message.contains("\"type\":\"join\"")) {
                        String nick = extractValue(message, "nickname");
                        if (nick != null) {
                            nickname = nick;
                            clientNicknames.put(this, nickname);
                            System.out.println("User joined: " + nickname);
                            broadcastUserList();
                            broadcastMessage("system", nickname + " se unió al chat");
                        }
                    } else if (message.contains("\"type\":\"message\"")) {
                        String msg = extractValue(message, "message");
                        if (msg != null && nickname != null) {
                            broadcastMessage(nickname, msg);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private String extractValue(String json, String key) {
            int keyIndex = json.indexOf("\"" + key + "\"");
            if (keyIndex == -1) return null;

            int colonIndex = json.indexOf(":", keyIndex);
            int quoteStart = json.indexOf("\"", colonIndex);
            int quoteEnd = json.indexOf("\"", quoteStart + 1);

            if (quoteStart != -1 && quoteEnd != -1) {
                return json.substring(quoteStart + 1, quoteEnd);
            }
            return null;
        }

        private void disconnect() {
            clients.remove(this);
            if (nickname != null) {
                clientNicknames.remove(this);
                System.out.println("User left: " + nickname);
                broadcastUserList();
                broadcastMessage("system", nickname + " salió del chat");
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastMessage(String sender, String message) {
        String json = String.format("{\"type\":\"message\",\"sender\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
            escapeJson(sender), escapeJson(message), new Date());

        for (WebSocketClient client : clients) {
            client.sendMessage(json);
        }
    }

    private static void broadcastUserList() {
        StringBuilder users = new StringBuilder("[");
        int i = 0;
        for (String nickname : clientNicknames.values()) {
            if (i > 0) users.append(",");
            users.append("\"").append(escapeJson(nickname)).append("\"");
            i++;
        }
        users.append("]");

        String json = String.format("{\"type\":\"userlist\",\"users\":%s}", users);

        for (WebSocketClient client : clients) {
            client.sendMessage(json);
        }
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }
}

