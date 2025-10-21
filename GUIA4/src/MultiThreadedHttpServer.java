import java.io.BufferedReader;
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

/**
 * A multi-threaded HTTP server that handles:
 * GET /login (serves login form)
 * POST /login (validates credentials against users.txt)
 * GET / (default time page)
 */
public class MultiThreadedHttpServer {

    // Define the user data file
    private static final String USER_FILE = "C:/Users/julia/IdeaProjects/SistemasDistribuidos-ll-2025/GUIA4/src/users.txt";

    public static void main(String[] args) {
        int port = 8080;

        // Assumption: Port 8080 is available.
        // Assumption: users.txt exists in the server's working directory.
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            System.out.println("Access the login form at: http://localhost:8080/login");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread clientThread = new Thread(new ClientHandler(clientSocket));
                clientThread.start();
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
            ) {
                String requestLine = in.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }

                // Read headers to find Content-Length for POST
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
                        contentLength = Integer.parseInt(headers.get("content-length"));
                    }

                    if (contentLength > 0) {
                        char[] bodyChars = new char[contentLength];
                        in.read(bodyChars, 0, contentLength);
                        body = new String(bodyChars);
                    }
                }

                // --- Routing ---
                if (requestLine.startsWith("GET /login")) {
                    sendLoginForm(out);
                } else if (requestLine.startsWith("POST /login")) {
                    handleLogin(out, body);
                } else if (requestLine.startsWith("GET /")) {
                    sendDefaultPage(out);
                } else {
                    sendNotFoundResponse(out);
                }

            } catch (Exception e) {
                // System.err.println("Handler exception: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (Exception e) { /* ignore */ }
            }
        }

        /**
         * Sends the HTML login form.
         */
        private void sendLoginForm(PrintWriter out) {
            String html = "<html><body>" +
                    "<h2>Login</h2>" +
                    "<form action='/login' method='POST'>" +
                    "  <label for='username'>Usuario:</label><br>" +
                    "  <input type='text' id='username' name='username'><br>" +
                    "  <label for='password'>Contrasena:</label><br>" +
                    "  <input type='password' id='password' name='password'><br><br>" +
                    "  <input type='submit' value='Enviar'>" +
                    "</form>" +
                    "</body></html>";
            sendHttpResponse(out, "200 OK", "text/html", html);
        }

        /**
         * Handles the POST request from the login form.
         */
        private void handleLogin(PrintWriter out, String body) {
            Map<String, String> formData = parseFormData(body);
            String username = formData.getOrDefault("username", "");
            String password = formData.getOrDefault("password", "");

            String fullName = validateUser(username, password);

            if (fullName != null) {
                // Success
                String html = "<html><body>" +
                        "<h1>Bienvenido, " + fullName + "</h1>" +
                        "<a href='/login'>Volver al login</a>" +
                        "</body></html>";
                sendHttpResponse(out, "200 OK", "text/html", html);
            } else {
                // Failure
                String html = "<html><body>" +
                        "<h1>Error: Usuario o contrasena incorrectos.</h1>" +
                        "<a href='/login'>Intentar nuevamente</a>" +
                        "</body></html>";
                // 401 Unauthorized is semantically correct
                sendHttpResponse(out, "401 Unauthorized", "text/html", html);
            }
        }

        /**
         * Validates credentials against the users.txt file.
         * Returns full name on success, null on failure.
         */
        private String validateUser(String username, String password) {
            // This is not efficient for large files, but meets the requirement.
            try (BufferedReader fileReader = new BufferedReader(new FileReader(USER_FILE))) {
                String line;
                while ((line = fileReader.readLine()) != null) {
                    // Split by "|"
                    String[] parts = line.split("\\|");

                    if (parts.length == 3) {
                        String fileUser = parts[0];
                        String filePass = parts[1];
                        String fileFullName = parts[2];

                        if (fileUser.equals(username) && filePass.equals(password)) {
                            return fileFullName; // Match found
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Could not read user file: " + e.getMessage());
                return null;
            }
            return null; // No match
        }

        /**
         * Utility to parse x-www-form-urlencoded data.
         */
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
                        String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
                        String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
                        params.put(key, value);
                    }
                }
            } catch (Exception e) {
                // Handle decoding exception
            }
            return params;
        }

        // --- Standard Responses (from previous exercise or new) ---

        private void sendDefaultPage(PrintWriter out) {
            String html = "<html><body><h1>Server Time</h1><p>" + new Date() + "</p>" +
                    "<a href='/login'>Ir al Login</a></body></html>";
            sendHttpResponse(out, "200 OK", "text/html", html);
        }

        private void sendNotFoundResponse(PrintWriter out) {
            String html = "<html><body><h1>404 Not Found</h1></body></html>";
            sendHttpResponse(out, "404 Not Found", "text/html", html);
        }

        /**
         * Helper utility to send a complete HTTP response.
         */
        private void sendHttpResponse(PrintWriter out, String status, String contentType, String body) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            out.println("HTTP/1.1 " + status);
            out.println("Content-Type: " + contentType + "; charset=utf-8");
            out.println("Content-Length: " + bodyBytes.length);
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