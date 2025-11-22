import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Multi-threaded HTTP server with thread pool.
 * Routes: GET /login, POST /login, GET /
 * Validates credentials against users.txt
 */
public class MultiThreadedHttpServer {

    private static final int PORT = 8080;
    private static final int THREAD_POOL_SIZE = 10;
    private static final String USER_FILE = "C:/Users/julia/IdeaProjects/SistemasDistribuidos-ll-2025/GUIA4/src/users.txt";

    private static volatile boolean running = true;
    private static ExecutorService threadPool;

    public static void main(String[] args) {
        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            running = false;
            shutdownThreadPool();
        }));

        File userFile = new File(USER_FILE);
        if (!userFile.exists()) {
            System.err.println("ERROR: users.txt not found at: " + userFile.getAbsolutePath());
            System.exit(1);
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverSocket.setSoTimeout(1000);
            System.out.println("Server listening on port " + PORT);
            System.out.println("User file: " + userFile.getAbsolutePath());
            System.out.println("Access: http://localhost:" + PORT + "/login");

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(new ClientHandler(clientSocket));
                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout, check if still running
                }
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            shutdownThreadPool();
        }
    }

    private static void shutdownThreadPool() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            String clientAddr = clientSocket.getInetAddress().getHostAddress();

            try (
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
            ) {
                String requestLine = in.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }

                System.out.println("[" + clientAddr + "] " + requestLine);

                Map<String, String> headers = new HashMap<>();
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    int colonPos = headerLine.indexOf(":");
                    if (colonPos > 0) {
                        String name = headerLine.substring(0, colonPos).trim();
                        String value = headerLine.substring(colonPos + 1).trim();
                        headers.put(name.toLowerCase(), value);
                    }
                }

                String body = "";
                if (requestLine.startsWith("POST")) {
                    int contentLength = 0;
                    if (headers.containsKey("content-length")) {
                        try {
                            contentLength = Integer.parseInt(headers.get("content-length"));
                        } catch (NumberFormatException e) {
                            sendBadRequest(out);
                            return;
                        }
                    }

                    if (contentLength > 0 && contentLength < 10000) {
                        char[] bodyChars = new char[contentLength];
                        int read = in.read(bodyChars, 0, contentLength);
                        if (read > 0) {
                            body = new String(bodyChars, 0, read);
                        }
                    }
                }

                route(out, requestLine, body, clientAddr);

            } catch (Exception e) {
                System.err.println("[" + clientAddr + "] Error: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (Exception e) { /* ignore */ }
            }
        }

        private void sendBadRequest(PrintWriter out) {
            String html = "<!DOCTYPE html><html><head>" +
                    "<meta charset='UTF-8'><title>400 Bad Request</title>" +
                    "</head><body><h1>400 Bad Request</h1></body></html>";
            sendHttpResponse(out, "400 Bad Request", "text/html", html);
        }

        private void route(PrintWriter out, String requestLine, String body, String clientAddr) {
            if (requestLine.startsWith("GET /login.html")) {
                serveFile(out, "login.html", "text/html");
            } else if (requestLine.startsWith("GET /ajax.html")) {
                serveFile(out, "ajax.html", "text/html");
            } else if (requestLine.startsWith("GET /index.html")) {
                serveFile(out, "index.html", "text/html");
            } else if (requestLine.startsWith("POST /login")) {
                handleLogin(out, body, clientAddr);
            } else if (requestLine.startsWith("GET /documento.txt")) {
                serveFile(out, "documento.txt", "text/plain");
            } else if (requestLine.startsWith("GET /paises.txt")) {
                serveFile(out, "paises.txt", "text/html");
            } else if (requestLine.startsWith("GET /paises.json")) {
                serveFile(out, "paises.json", "application/json");
            } else if (requestLine.startsWith("GET / ") || requestLine.startsWith("GET / HTTP")) {
                serveFile(out, "index.html", "text/html");
            } else {
                serveFile(out, "404.html", "text/html");
            }
        }

        private void handleLogin(PrintWriter out, String body, String clientAddr) {
            Map<String, String> formData = parseFormData(body);
            String username = formData.getOrDefault("username", "");
            String password = formData.getOrDefault("password", "");

            System.out.println("[" + clientAddr + "] Login attempt: " + username);

            String fullName = validateUser(username, password);

            if (fullName != null) {
                System.out.println("[" + clientAddr + "] Login successful: " + fullName);
                String html = "<!DOCTYPE html><html><head>" +
                        "<meta charset='UTF-8'><title>Bienvenido</title>" +
                        "<style>" +
                        "body{font-family:Arial,sans-serif;background:#f4f4f4;padding:50px;text-align:center;}" +
                        ".container{background:white;padding:40px;border-radius:8px;max-width:500px;margin:auto;box-shadow:0 2px 10px rgba(0,0,0,0.1);}" +
                        "h1{color:#4CAF50;}" +
                        "a{display:inline-block;margin-top:20px;padding:10px 20px;background:#4CAF50;color:white;text-decoration:none;border-radius:4px;}" +
                        "a:hover{background:#45a049;}" +
                        "</style>" +
                        "</head><body><div class='container'>" +
                        "<h1>Bienvenido, " + escapeHtml(fullName) + "</h1>" +
                        "<p>Has iniciado sesión correctamente.</p>" +
                        "<a href='/login.html'>Cerrar Sesión</a>" +
                        "</div></body></html>";
                sendHttpResponse(out, "200 OK", "text/html", html);
            } else {
                System.out.println("[" + clientAddr + "] Login failed: " + username);
                String html = "<!DOCTYPE html><html><head>" +
                        "<meta charset='UTF-8'><title>Error</title>" +
                        "<style>" +
                        "body{font-family:Arial,sans-serif;background:#f4f4f4;padding:50px;text-align:center;}" +
                        ".container{background:white;padding:40px;border-radius:8px;max-width:500px;margin:auto;box-shadow:0 2px 10px rgba(0,0,0,0.1);}" +
                        "h1{color:#f44336;}" +
                        "a{display:inline-block;margin-top:20px;padding:10px 20px;background:#4CAF50;color:white;text-decoration:none;border-radius:4px;}" +
                        "a:hover{background:#45a049;}" +
                        "</style>" +
                        "</head><body><div class='container'>" +
                        "<h1>Error de Autenticación</h1>" +
                        "<p>Usuario o contraseña incorrectos.</p>" +
                        "<a href='/login.html'>Intentar Nuevamente</a>" +
                        "</div></body></html>";
                sendHttpResponse(out, "401 Unauthorized", "text/html", html);
            }
        }

        private String escapeHtml(String text) {
            if (text == null) return "";
            return text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;")
                       .replace("\"", "&quot;")
                       .replace("'", "&#x27;");
        }

        private String validateUser(String username, String password) {
            try (BufferedReader fileReader = new BufferedReader(new FileReader(USER_FILE))) {
                String line;
                while ((line = fileReader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        String fileUser = parts[0].trim();
                        String filePass = parts[1].trim();
                        String fileFullName = parts[2].trim();

                        if (fileUser.equals(username) && filePass.equals(password)) {
                            return fileFullName;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error reading user file: " + e.getMessage());
            }
            return null;
        }

        private Map<String, String> parseFormData(String body) {
            Map<String, String> params = new HashMap<>();
            if (body == null || body.isEmpty()) {
                return params;
            }

            try {
                String[] pairs = body.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                        String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                        params.put(key, value);
                    }
                }
            } catch (Exception e) {
                System.err.println("Form data parsing error: " + e.getMessage());
            }
            return params;
        }

        private void serveFile(PrintWriter out, String filename, String contentType) {
            String filePath = USER_FILE.substring(0, USER_FILE.lastIndexOf('/') + 1) + filename;
            StringBuilder content = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                String responseContent = content.toString();
                if (responseContent.endsWith("\n")) {
                    responseContent = responseContent.substring(0, responseContent.length() - 1);
                }
                sendHttpResponse(out, "200 OK", contentType, responseContent);
            } catch (Exception e) {
                System.err.println("Error reading file " + filename + ": " + e.getMessage());
                String errorHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>404 Not Found</title></head>" +
                        "<body><h1>File not found: " + filename + "</h1></body></html>";
                sendHttpResponse(out, "404 Not Found", "text/html", errorHtml);
            }
        }

        private void sendHttpResponse(PrintWriter out, String status, String contentType, String body) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            out.println("HTTP/1.1 " + status);
            out.println("Content-Type: " + contentType + "; charset=utf-8");
            out.println("Content-Length: " + bodyBytes.length);
            out.println("Access-Control-Allow-Origin: *");
            out.println("Connection: close");
            out.println();
            out.print(body);
            out.flush();
        }
    }
}

//Descripción Técnica del Flujo
//Inicio: El MultiThreadedHttpServer se inicia y espera conexiones en el puerto 8080.
//
//Solicitud (Navegador): El usuario accede a http://localhost:8080/login en un navegador web.
//
//Manejo (GET /login): El servidor acepta la conexión (Socket) y la delega a un ClientHandler. El handler lee la línea de solicitud GET /login .... El router interno del handler identifica esta ruta y llama a sendLoginForm(). Esta función envía una respuesta 200 OK con el HTML que contiene el formulario (<form method='POST' action='/login'>).
//
//Interacción (Usuario): El usuario llena el formulario (p.ej., admin, admin123) y presiona "Enviar".
//
//Solicitud (POST): El navegador serializa el formulario (como username=admin&password=admin123) y lo envía en el cuerpo de una solicitud POST /login.
//
//Manejo (POST /login): El servidor acepta esta nueva conexión en un nuevo ClientHandler.
//
//Lee la línea de solicitud (POST /login ...).
//
//Lee las cabeceras (headers) para encontrar el Content-Length.
//
//Lee el número exacto de bytes/caracteres del cuerpo (body).
//
//El router llama a handleLogin(out, body).
//
//Validación:
//
//handleLogin llama a parseFormData(body) para decodificar el cuerpo (manejando &, = y caracteres especiales como %20) y obtener un Map (p.ej., {"username": "admin", "password": "admin123"}).
//
//handleLogin llama a validateUser(username, password).
//
//validateUser abre users.txt, lo lee línea por línea, divide cada línea por | y compara las credenciales.
//
//Respuesta (Éxito): Si validateUser encuentra una coincidencia, devuelve el nombre completo (p.ej., "Administrator"). handleLogin genera un HTML de bienvenida (<h1>Bienvenido, Administrator</h1>) y lo envía con estado 200 OK.
//
//Respuesta (Fallo): Si no hay coincidencia, validateUser devuelve null. handleLogin genera un HTML de error y lo envía con estado 401 Unauthorized.