import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ChatWebSocketServer {

    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8081;
    private static final Set<WebSocketClient> clients = ConcurrentHashMap.newKeySet();
    private static final Map<WebSocketClient, String> clientNicknames = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // Start HTTP server for static files
        new Thread(() -> startHttpServer()).start();

        // Start WebSocket server
        new Thread(() -> startWebSocketServer()).start();

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

            // Skip headers
            while (in.readLine() != null && !in.readLine().isEmpty());

            String response = getIndexHtml();
            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=UTF-8\r\n" +
                                "Content-Length: " + response.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                                "Connection: close\r\n\r\n" +
                                response;

            out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        private PrintWriter out;
        private BufferedReader in;
        private String nickname;

        public WebSocketClient(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                // WebSocket handshake
                performHandshake();

                clients.add(this);

                // Read messages
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

                frame.write(0x81); // Text frame

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
                // Parse JSON-like message
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
            escapeJson(sender), escapeJson(message), new Date().toString());

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

        String json = String.format("{\"type\":\"userlist\",\"users\":%s}", users.toString());

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

    private static String getIndexHtml() {
        return "<!DOCTYPE html>\n" +
"<html>\n" +
"<head>\n" +
"    <meta charset='UTF-8'>\n" +
"    <title>Chat WebSocket</title>\n" +
"    <style>\n" +
"        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
"        body {\n" +
"            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n" +
"            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
"            height: 100vh;\n" +
"            display: flex;\n" +
"            justify-content: center;\n" +
"            align-items: center;\n" +
"        }\n" +
"        #loginScreen, #chatScreen { display: none; }\n" +
"        #loginScreen.active, #chatScreen.active { display: flex; }\n" +
"        #loginScreen {\n" +
"            flex-direction: column;\n" +
"            background: white;\n" +
"            padding: 40px;\n" +
"            border-radius: 12px;\n" +
"            box-shadow: 0 10px 40px rgba(0,0,0,0.3);\n" +
"            width: 400px;\n" +
"        }\n" +
"        #loginScreen h1 { color: #667eea; margin-bottom: 30px; text-align: center; }\n" +
"        #loginScreen input {\n" +
"            width: 100%;\n" +
"            padding: 15px;\n" +
"            border: 2px solid #ddd;\n" +
"            border-radius: 8px;\n" +
"            font-size: 16px;\n" +
"            margin-bottom: 20px;\n" +
"        }\n" +
"        #loginScreen input:focus { outline: none; border-color: #667eea; }\n" +
"        #loginScreen button {\n" +
"            width: 100%;\n" +
"            padding: 15px;\n" +
"            background: #667eea;\n" +
"            color: white;\n" +
"            border: none;\n" +
"            border-radius: 8px;\n" +
"            font-size: 16px;\n" +
"            cursor: pointer;\n" +
"            transition: background 0.3s;\n" +
"        }\n" +
"        #loginScreen button:hover { background: #5568d3; }\n" +
"        #chatScreen {\n" +
"            width: 90%;\n" +
"            max-width: 1200px;\n" +
"            height: 80vh;\n" +
"            background: white;\n" +
"            border-radius: 12px;\n" +
"            box-shadow: 0 10px 40px rgba(0,0,0,0.3);\n" +
"            overflow: hidden;\n" +
"        }\n" +
"        #chatContainer {\n" +
"            display: grid;\n" +
"            grid-template-columns: 250px 1fr;\n" +
"            height: 100%;\n" +
"        }\n" +
"        #userList {\n" +
"            background: #f8f9fa;\n" +
"            padding: 20px;\n" +
"            border-right: 1px solid #ddd;\n" +
"        }\n" +
"        #userList h3 {\n" +
"            color: #667eea;\n" +
"            margin-bottom: 20px;\n" +
"            padding-bottom: 10px;\n" +
"            border-bottom: 2px solid #667eea;\n" +
"        }\n" +
"        .user-item {\n" +
"            padding: 10px;\n" +
"            margin-bottom: 5px;\n" +
"            background: white;\n" +
"            border-radius: 6px;\n" +
"            display: flex;\n" +
"            align-items: center;\n" +
"        }\n" +
"        .user-item::before {\n" +
"            content: '🟢';\n" +
"            margin-right: 10px;\n" +
"        }\n" +
"        #chatArea {\n" +
"            display: flex;\n" +
"            flex-direction: column;\n" +
"            height: 100%;\n" +
"        }\n" +
"        #chatHeader {\n" +
"            background: #667eea;\n" +
"            color: white;\n" +
"            padding: 20px;\n" +
"            display: flex;\n" +
"            justify-content: space-between;\n" +
"            align-items: center;\n" +
"        }\n" +
"        #messages {\n" +
"            flex: 1;\n" +
"            padding: 20px;\n" +
"            overflow-y: auto;\n" +
"            background: #fff;\n" +
"            min-height: 0;\n" +
"        }\n" +
"        .message {\n" +
"            margin-bottom: 15px;\n" +
"            padding: 12px 15px;\n" +
"            border-radius: 8px;\n" +
"            max-width: 70%;\n" +
"            word-wrap: break-word;\n" +
"        }\n" +
"        .message.own {\n" +
"            background: #667eea;\n" +
"            color: white;\n" +
"            margin-left: auto;\n" +
"            text-align: right;\n" +
"        }\n" +
"        .message.other {\n" +
"            background: #f1f3f5;\n" +
"            color: #333;\n" +
"        }\n" +
"        .message.system {\n" +
"            background: #fff3cd;\n" +
"            color: #856404;\n" +
"            text-align: center;\n" +
"            margin: 10px auto;\n" +
"            max-width: 90%;\n" +
"        }\n" +
"        .message-sender {\n" +
"            font-weight: bold;\n" +
"            margin-bottom: 5px;\n" +
"            font-size: 14px;\n" +
"        }\n" +
"        .message-time {\n" +
"            font-size: 11px;\n" +
"            opacity: 0.7;\n" +
"            margin-top: 5px;\n" +
"        }\n" +
"        #inputArea {\n" +
"            display: flex;\n" +
"            padding: 20px;\n" +
"            background: #f8f9fa;\n" +
"            border-top: 1px solid #ddd;\n" +
"        }\n" +
"        #messageInput {\n" +
"            flex: 1;\n" +
"            padding: 12px 15px;\n" +
"            border: 2px solid #ddd;\n" +
"            border-radius: 25px;\n" +
"            font-size: 14px;\n" +
"            margin-right: 10px;\n" +
"        }\n" +
"        #messageInput:focus { outline: none; border-color: #667eea; }\n" +
"        #sendButton {\n" +
"            padding: 12px 30px;\n" +
"            background: #667eea;\n" +
"            color: white;\n" +
"            border: none;\n" +
"            border-radius: 25px;\n" +
"            cursor: pointer;\n" +
"            font-size: 14px;\n" +
"            font-weight: 600;\n" +
"        }\n" +
"        #sendButton:hover { background: #5568d3; }\n" +
"    </style>\n" +
"</head>\n" +
"<body>\n" +
"    <div id='loginScreen' class='active'>\n" +
"        <h1>💬 Chat WebSocket</h1>\n" +
"        <input type='text' id='nicknameInput' placeholder='Ingrese su nickname' maxlength='20' />\n" +
"        <button onclick='join()'>Unirse al Chat</button>\n" +
"    </div>\n" +
"    <div id='chatScreen'>\n" +
"        <div id='chatContainer'>\n" +
"            <div id='userList'>\n" +
"                <h3>👥 Usuarios Conectados</h3>\n" +
"                <div id='users'></div>\n" +
"            </div>\n" +
"            <div id='chatArea'>\n" +
"                <div id='chatHeader'>\n" +
"                    <h2>💬 Chat en Tiempo Real</h2>\n" +
"                    <span id='userNickname'></span>\n" +
"                </div>\n" +
"                <div id='messages'></div>\n" +
"                <div id='inputArea'>\n" +
"                    <input type='text' id='messageInput' placeholder='Escribe un mensaje...' />\n" +
"                    <button id='sendButton' onclick='sendMessage()'>Enviar</button>\n" +
"                </div>\n" +
"            </div>\n" +
"        </div>\n" +
"    </div>\n" +
"    <script>\n" +
"        let ws;\n" +
"        let nickname;\n" +
"        function join() {\n" +
"            nickname = document.getElementById('nicknameInput').value.trim();\n" +
"            if (!nickname) { alert('Por favor ingrese un nickname'); return; }\n" +
"            ws = new WebSocket('ws://localhost:8081');\n" +
"            ws.onopen = function() {\n" +
"                ws.send(JSON.stringify({type: 'join', nickname: nickname}));\n" +
"                document.getElementById('loginScreen').classList.remove('active');\n" +
"                document.getElementById('chatScreen').classList.add('active');\n" +
"                document.getElementById('userNickname').textContent = nickname;\n" +
"            };\n" +
"            ws.onmessage = function(event) {\n" +
"                const data = JSON.parse(event.data);\n" +
"                if (data.type === 'message') {\n" +
"                    addMessage(data.sender, data.message, data.timestamp);\n" +
"                } else if (data.type === 'userlist') {\n" +
"                    updateUserList(data.users);\n" +
"                }\n" +
"            };\n" +
"            ws.onerror = function() { alert('Error de conexión'); };\n" +
"            ws.onclose = function() { alert('Desconectado del servidor'); };\n" +
"        }\n" +
"        function sendMessage() {\n" +
"            const input = document.getElementById('messageInput');\n" +
"            const message = input.value.trim();\n" +
"            if (message && ws.readyState === WebSocket.OPEN) {\n" +
"                ws.send(JSON.stringify({type: 'message', message: message}));\n" +
"                input.value = '';\n" +
"            }\n" +
"        }\n" +
"        document.getElementById('messageInput').addEventListener('keypress', function(e) {\n" +
"            if (e.key === 'Enter') sendMessage();\n" +
"        });\n" +
"        function addMessage(sender, message, timestamp) {\n" +
"            const messagesDiv = document.getElementById('messages');\n" +
"            const messageDiv = document.createElement('div');\n" +
"            if (sender === 'system') {\n" +
"                messageDiv.className = 'message system';\n" +
"                messageDiv.textContent = message;\n" +
"            } else {\n" +
"                messageDiv.className = sender === nickname ? 'message own' : 'message other';\n" +
"                if (sender !== nickname) {\n" +
"                    const senderDiv = document.createElement('div');\n" +
"                    senderDiv.className = 'message-sender';\n" +
"                    senderDiv.textContent = sender;\n" +
"                    messageDiv.appendChild(senderDiv);\n" +
"                }\n" +
"                const textDiv = document.createElement('div');\n" +
"                textDiv.textContent = message;\n" +
"                messageDiv.appendChild(textDiv);\n" +
"                const timeDiv = document.createElement('div');\n" +
"                timeDiv.className = 'message-time';\n" +
"                timeDiv.textContent = new Date(timestamp).toLocaleTimeString();\n" +
"                messageDiv.appendChild(timeDiv);\n" +
"            }\n" +
"            messagesDiv.appendChild(messageDiv);\n" +
"            messagesDiv.scrollTop = messagesDiv.scrollHeight;\n" +
"        }\n" +
"        function updateUserList(users) {\n" +
"            const usersDiv = document.getElementById('users');\n" +
"            usersDiv.innerHTML = '';\n" +
"            users.forEach(user => {\n" +
"                const userDiv = document.createElement('div');\n" +
"                userDiv.className = 'user-item';\n" +
"                userDiv.textContent = user;\n" +
"                usersDiv.appendChild(userDiv);\n" +
"            });\n" +
"        }\n" +
"    </script>\n" +
"</body>\n" +
"</html>";
    }
}

