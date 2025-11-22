import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

public class AgendaServer {

    private static final int PORT = 8080;
    private static final int THREAD_POOL_SIZE = 10;
    private static final String XML_FILE = "C:/Users/julia/IdeaProjects/SistemasDistribuidos-ll-2025/GUIA4-PT2-AGENDA/src/agenda.xml";

    private static volatile boolean running = true;
    private static ExecutorService threadPool;
    private static final Object xmlLock = new Object();

    public static void main(String[] args) {
        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            running = false;
            shutdownThreadPool();
        }));

        initializeXML();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverSocket.setSoTimeout(1000);
            System.out.println("Agenda Server listening on port " + PORT);
            System.out.println("XML file: " + XML_FILE);
            System.out.println("Access: http://localhost:" + PORT + "/");

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(new ClientHandler(clientSocket));
                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout
                }
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdownThreadPool();
        }
    }

    private static void initializeXML() {
        File xmlFile = new File(XML_FILE);
        if (!xmlFile.exists()) {
            try {
                String initialXML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<!DOCTYPE agenda [\n" +
                        "<!ELEMENT agenda (contacto)*>\n" +
                        "<!ELEMENT contacto (nombre,direccion,telefono,email)>\n" +
                        "<!ATTLIST contacto id CDATA #REQUIRED>\n" +
                        "<!ELEMENT nombre (#PCDATA)>\n" +
                        "<!ELEMENT direccion (#PCDATA)>\n" +
                        "<!ELEMENT telefono (#PCDATA)>\n" +
                        "<!ELEMENT email (#PCDATA)>\n" +
                        "]>\n" +
                        "<agenda>\n" +
                        "</agenda>";

                Files.write(Paths.get(XML_FILE), initialXML.getBytes(StandardCharsets.UTF_8));
                System.out.println("Created initial agenda.xml");
            } catch (IOException e) {
                System.err.println("Error creating XML file: " + e.getMessage());
            }
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
                if (requestLine.startsWith("POST") || requestLine.startsWith("PUT") || requestLine.startsWith("DELETE")) {
                    int contentLength = 0;
                    if (headers.containsKey("content-length")) {
                        try {
                            contentLength = Integer.parseInt(headers.get("content-length"));
                        } catch (NumberFormatException e) {
                            sendResponse(out, "400 Bad Request", "text/plain", "Invalid Content-Length");
                            return;
                        }
                    }

                    if (contentLength > 0 && contentLength < 100000) {
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
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                } catch (Exception e) { /* ignore */ }
            }
        }

        private void route(PrintWriter out, String requestLine, String body, String clientAddr) {
            if (requestLine.startsWith("GET /index.html") || requestLine.startsWith("GET / ") || requestLine.startsWith("GET / HTTP")) {
                serveFile(out, "index.html", "text/html");
            } else if (requestLine.startsWith("GET /agenda.xml")) {
                serveXML(out);
            } else if (requestLine.startsWith("POST /api/contactos")) {
                addContacto(out, body, clientAddr);
            } else if (requestLine.startsWith("PUT /api/contactos/")) {
                updateContacto(out, requestLine, body, clientAddr);
            } else if (requestLine.startsWith("DELETE /api/contactos/")) {
                deleteContacto(out, requestLine, clientAddr);
            } else if (requestLine.startsWith("GET /api/contactos")) {
                listContactos(out);
            } else {
                sendResponse(out, "404 Not Found", "text/plain", "Not Found");
            }
        }

        private void serveFile(PrintWriter out, String filename, String contentType) {
            String filePath = XML_FILE.substring(0, XML_FILE.lastIndexOf('/') + 1) + filename;
            try {
                String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
                sendResponse(out, "200 OK", contentType, content);
            } catch (IOException e) {
                sendResponse(out, "404 Not Found", "text/plain", "File not found");
            }
        }

        private void serveXML(PrintWriter out) {
            synchronized (xmlLock) {
                try {
                    String content = new String(Files.readAllBytes(Paths.get(XML_FILE)), StandardCharsets.UTF_8);
                    sendResponse(out, "200 OK", "application/xml", content);
                } catch (IOException e) {
                    sendResponse(out, "500 Internal Server Error", "text/plain", "Error reading XML");
                }
            }
        }

        private void addContacto(PrintWriter out, String body, String clientAddr) {
            try {
                Map<String, String> data = parseJSON(body);

                synchronized (xmlLock) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new File(XML_FILE));

                    Element root = doc.getDocumentElement();

                    // Generate unique ID
                    String newId = String.valueOf(System.currentTimeMillis());

                    Element contacto = doc.createElement("contacto");
                    contacto.setAttribute("id", newId);

                    Element nombre = doc.createElement("nombre");
                    nombre.setTextContent(data.getOrDefault("nombre", ""));
                    contacto.appendChild(nombre);

                    Element direccion = doc.createElement("direccion");
                    direccion.setTextContent(data.getOrDefault("direccion", ""));
                    contacto.appendChild(direccion);

                    Element telefono = doc.createElement("telefono");
                    telefono.setTextContent(data.getOrDefault("telefono", ""));
                    contacto.appendChild(telefono);

                    Element email = doc.createElement("email");
                    email.setTextContent(data.getOrDefault("email", ""));
                    contacto.appendChild(email);

                    root.appendChild(contacto);

                    saveXML(doc);

                    System.out.println("[" + clientAddr + "] Added contacto with ID: " + newId);
                    sendResponse(out, "201 Created", "application/json",
                        "{\"success\":true,\"id\":\"" + newId + "\",\"message\":\"Contacto agregado\"}");
                }
            } catch (Exception e) {
                System.err.println("Error adding contacto: " + e.getMessage());
                e.printStackTrace();
                sendResponse(out, "500 Internal Server Error", "application/json",
                    "{\"success\":false,\"message\":\"" + escapeJSON(e.getMessage()) + "\"}");
            }
        }

        private void updateContacto(PrintWriter out, String requestLine, String body, String clientAddr) {
            try {
                String[] parts = requestLine.split(" ");
                String path = parts[1];
                String id = path.substring(path.lastIndexOf('/') + 1);

                Map<String, String> data = parseJSON(body);

                synchronized (xmlLock) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new File(XML_FILE));

                    NodeList contactos = doc.getElementsByTagName("contacto");
                    boolean found = false;

                    for (int i = 0; i < contactos.getLength(); i++) {
                        Element contacto = (Element) contactos.item(i);
                        if (contacto.getAttribute("id").equals(id)) {
                            contacto.getElementsByTagName("nombre").item(0).setTextContent(data.getOrDefault("nombre", ""));
                            contacto.getElementsByTagName("direccion").item(0).setTextContent(data.getOrDefault("direccion", ""));
                            contacto.getElementsByTagName("telefono").item(0).setTextContent(data.getOrDefault("telefono", ""));
                            contacto.getElementsByTagName("email").item(0).setTextContent(data.getOrDefault("email", ""));
                            found = true;
                            break;
                        }
                    }

                    if (found) {
                        saveXML(doc);
                        System.out.println("[" + clientAddr + "] Updated contacto ID: " + id);
                        sendResponse(out, "200 OK", "application/json",
                            "{\"success\":true,\"message\":\"Contacto actualizado\"}");
                    } else {
                        sendResponse(out, "404 Not Found", "application/json",
                            "{\"success\":false,\"message\":\"Contacto no encontrado\"}");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error updating contacto: " + e.getMessage());
                sendResponse(out, "500 Internal Server Error", "application/json",
                    "{\"success\":false,\"message\":\"" + escapeJSON(e.getMessage()) + "\"}");
            }
        }

        private void deleteContacto(PrintWriter out, String requestLine, String clientAddr) {
            try {
                String[] parts = requestLine.split(" ");
                String path = parts[1];
                String id = path.substring(path.lastIndexOf('/') + 1);

                synchronized (xmlLock) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new File(XML_FILE));

                    NodeList contactos = doc.getElementsByTagName("contacto");
                    boolean found = false;

                    for (int i = 0; i < contactos.getLength(); i++) {
                        Element contacto = (Element) contactos.item(i);
                        if (contacto.getAttribute("id").equals(id)) {
                            contacto.getParentNode().removeChild(contacto);
                            found = true;
                            break;
                        }
                    }

                    if (found) {
                        saveXML(doc);
                        System.out.println("[" + clientAddr + "] Deleted contacto ID: " + id);
                        sendResponse(out, "200 OK", "application/json",
                            "{\"success\":true,\"message\":\"Contacto eliminado\"}");
                    } else {
                        sendResponse(out, "404 Not Found", "application/json",
                            "{\"success\":false,\"message\":\"Contacto no encontrado\"}");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error deleting contacto: " + e.getMessage());
                sendResponse(out, "500 Internal Server Error", "application/json",
                    "{\"success\":false,\"message\":\"" + escapeJSON(e.getMessage()) + "\"}");
            }
        }

        private void listContactos(PrintWriter out) {
            try {
                synchronized (xmlLock) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new File(XML_FILE));

                    NodeList contactos = doc.getElementsByTagName("contacto");
                    StringBuilder json = new StringBuilder("[");

                    for (int i = 0; i < contactos.getLength(); i++) {
                        Element contacto = (Element) contactos.item(i);
                        if (i > 0) json.append(",");

                        json.append("{");
                        json.append("\"id\":\"").append(escapeJSON(contacto.getAttribute("id"))).append("\",");
                        json.append("\"nombre\":\"").append(escapeJSON(contacto.getElementsByTagName("nombre").item(0).getTextContent())).append("\",");
                        json.append("\"direccion\":\"").append(escapeJSON(contacto.getElementsByTagName("direccion").item(0).getTextContent())).append("\",");
                        json.append("\"telefono\":\"").append(escapeJSON(contacto.getElementsByTagName("telefono").item(0).getTextContent())).append("\",");
                        json.append("\"email\":\"").append(escapeJSON(contacto.getElementsByTagName("email").item(0).getTextContent())).append("\"");
                        json.append("}");
                    }

                    json.append("]");
                    sendResponse(out, "200 OK", "application/json", json.toString());
                }
            } catch (Exception e) {
                System.err.println("Error listing contactos: " + e.getMessage());
                sendResponse(out, "500 Internal Server Error", "application/json", "[]");
            }
        }

        private void saveXML(Document doc) throws Exception {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(XML_FILE));
            transformer.transform(source, result);
        }

        private Map<String, String> parseJSON(String json) {
            Map<String, String> map = new HashMap<>();
            if (json == null || json.isEmpty()) return map;

            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

            String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    map.put(key, value);
                }
            }
            return map;
        }

        private String escapeJSON(String text) {
            if (text == null) return "";
            return text.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t");
        }

        private void sendResponse(PrintWriter out, String status, String contentType, String body) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            out.println("HTTP/1.1 " + status);
            out.println("Content-Type: " + contentType + "; charset=utf-8");
            out.println("Content-Length: " + bodyBytes.length);
            out.println("Access-Control-Allow-Origin: *");
            out.println("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS");
            out.println("Access-Control-Allow-Headers: Content-Type");
            out.println("Connection: close");
            out.println();
            out.print(body);
            out.flush();
        }
    }
}

