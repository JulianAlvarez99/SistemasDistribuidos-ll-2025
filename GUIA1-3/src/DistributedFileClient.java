import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔧 DISTRIBUTED FILE CLIENT - VERSIÓN CORREGIDA FINAL
 * Cliente robusto con manejo de errores mejorado y protocolo corregido
 */
public class DistributedFileClient {
    private static final Logger LOGGER = Logger.getLogger(DistributedFileClient.class.getName());
    private final String primaryHost;
    private final int primaryPort;
    private final String clientId;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;

    public DistributedFileClient(String primaryHost, int primaryPort) {
        this.primaryHost = primaryHost;
        this.primaryPort = primaryPort;
        this.clientId = "CLIENT_" + System.currentTimeMillis();
        this.connectionTimeoutMs = 10000; // 10 segundos para conectar
        this.readTimeoutMs = 30000;       // 30 segundos para leer respuesta
    }

    /**
     * 🔧 WRITE OPERATION - Escribir contenido a un archivo
     */
    public OperationResult write(String fileName, String content) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        if (content == null) {
            content = ""; // Permitir contenido vacío pero no null
        }

        LOGGER.info("📝 WRITE request: " + fileName + " (" + content.length() + " chars)");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.WRITE, fileName, content);
        message.setClientId(clientId);

        return sendMessage(message, "WRITE " + fileName);
    }

    /**
     * 🔧 READ OPERATION - Leer contenido de un archivo
     */
    public OperationResult read(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        LOGGER.info("📖 READ request: " + fileName);

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.READ, fileName, null);
        message.setClientId(clientId);

        return sendMessage(message, "READ " + fileName);
    }

    /**
     * 🔧 DELETE OPERATION - Eliminar un archivo
     */
    public OperationResult delete(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        LOGGER.info("🗑️ DELETE request: " + fileName);

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.DELETE, fileName, null);
        message.setClientId(clientId);

        return sendMessage(message, "DELETE " + fileName);
    }

    /**
     * 🔧 LIST OPERATION - Listar todos los archivos
     */
    public OperationResult listFiles() {
        LOGGER.info("📋 LIST request");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.LIST, null, null);
        message.setClientId(clientId);

        return sendMessage(message, "LIST files");
    }

    /**
     * 🔧 MÉTODO PRINCIPAL DE COMUNICACIÓN - Envío y recepción de mensajes
     */
    private OperationResult sendMessage(ProtocolMessage message, String operationDescription) {
        // Validar mensaje antes de enviar
        if (!message.isValid()) {
            String error = "Invalid message for operation: " + operationDescription;
            LOGGER.severe("❌ " + error + " - " + message.toDebugString());
            return new OperationResult(false, error);
        }

        Socket socket = null;
        try {
            // 1. Establecer conexión con timeout
            LOGGER.fine("🔌 Connecting to " + primaryHost + ":" + primaryPort);
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(primaryHost, primaryPort), connectionTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            // 2. Crear streams de comunicación
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                // 3. Serializar y enviar mensaje
                String serializedMessage = message.toString();
                LOGGER.fine("📤 Sending: " + message.toDebugString());
                LOGGER.fine("📤 Serialized: " + serializedMessage);

                writer.println(serializedMessage);
                writer.flush(); // Asegurar que se envía inmediatamente

                // 4. Leer respuesta del servidor
                String responseStr = reader.readLine();

                if (responseStr == null || responseStr.trim().isEmpty()) {
                    String error = "No response from server for: " + operationDescription;
                    LOGGER.warning("❌ " + error);
                    return new OperationResult(false, error);
                }

                LOGGER.fine("📥 Received response: " + responseStr);

                // 5. Parsear respuesta
                try {
                    ProtocolMessage response = ProtocolMessage.fromString(responseStr);
                    LOGGER.fine("📥 Parsed response: " + response.toDebugString());

                    return processServerResponse(response, operationDescription);

                } catch (Exception parseException) {
                    String error = "Error parsing server response: " + parseException.getMessage();
                    LOGGER.log(Level.WARNING, "❌ " + error + " - Raw response: " + responseStr, parseException);
                    return new OperationResult(false, error);
                }
            }

        } catch (SocketTimeoutException e) {
            String error = "Timeout communicating with server for: " + operationDescription;
            LOGGER.log(Level.WARNING, "⏱️ " + error, e);
            return new OperationResult(false, error);

        } catch (java.net.ConnectException e) {
            String error = "Cannot connect to server " + primaryHost + ":" + primaryPort + " - Is the server running?";
            LOGGER.log(Level.WARNING, "🔌 " + error, e);
            return new OperationResult(false, error);

        } catch (Exception e) {
            String error = "Communication error for " + operationDescription + ": " + e.getMessage();
            LOGGER.log(Level.WARNING, "❌ " + error, e);
            return new OperationResult(false, error);

        } finally {
            // Asegurar que el socket se cierre
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error closing socket", e);
                }
            }
        }
    }

    /**
     * 🔧 PROCESAMIENTO DE RESPUESTA DEL SERVIDOR
     */
    private OperationResult processServerResponse(ProtocolMessage response, String operationDescription) {
        if (response.getCommand() == null) {
            return new OperationResult(false, "Invalid response from server - no command");
        }

        switch (response.getCommand()) {
            case SUCCESS:
                String content = response.getContent();
                String successMsg = "Operation completed successfully";

                LOGGER.info("✅ " + operationDescription + " - SUCCESS" +
                        (content != null ? " (" + content.length() + " chars)" : ""));

                return new OperationResult(true, successMsg, content);

            case ERROR:
                String errorMsg = response.getContent() != null ? response.getContent() : "Unknown server error";
                LOGGER.warning("❌ " + operationDescription + " - ERROR: " + errorMsg);
                return new OperationResult(false, errorMsg);

            case NOT_FOUND:
                String notFoundMsg = response.getContent() != null ? response.getContent() : "Resource not found";
                LOGGER.info("📂 " + operationDescription + " - NOT FOUND: " + notFoundMsg);
                return new OperationResult(false, notFoundMsg);

            default:
                String unexpectedMsg = "Unexpected response command: " + response.getCommand();
                LOGGER.warning("⚠️ " + operationDescription + " - " + unexpectedMsg);
                return new OperationResult(false, unexpectedMsg);
        }
    }

    /**
     * 🔧 TEST DE CONECTIVIDAD
     */
    public boolean testConnection() {
        LOGGER.info("🔍 Testing connection to " + primaryHost + ":" + primaryPort);

        try (Socket testSocket = new Socket()) {
            testSocket.connect(new java.net.InetSocketAddress(primaryHost, primaryPort), 3000);
            LOGGER.info("✅ Connection test successful");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Connection test failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 🔧 INFORMACIÓN DEL CLIENTE
     */
    public String getClientInfo() {
        return String.format("DistributedFileClient{server=%s:%d, clientId=%s, connectTimeout=%dms, readTimeout=%dms}",
                primaryHost, primaryPort, clientId, connectionTimeoutMs, readTimeoutMs);
    }

    // Getters
    public String getPrimaryHost() { return primaryHost; }
    public int getPrimaryPort() { return primaryPort; }
    public String getClientId() { return clientId; }

    /**
     * 🧪 MÉTODO DE TESTING ESTÁTICO
     */
    public static void runConnectionTest(String host, int port) {
        System.out.println("🧪 Testing DistributedFileClient...");

        DistributedFileClient client = new DistributedFileClient(host, port);
        System.out.println("Client info: " + client.getClientInfo());

        // Test connectivity
        if (client.testConnection()) {
            System.out.println("✅ Connection test passed");

            // Test LIST operation
            OperationResult listResult = client.listFiles();
            System.out.println("LIST result: " + (listResult.isSuccess() ? "✅ SUCCESS" : "❌ FAILED") +
                    " - " + listResult.getMessage());

        } else {
            System.out.println("❌ Connection test failed - server may not be running");
        }
    }
}