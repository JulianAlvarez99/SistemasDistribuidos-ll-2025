import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔗 IMPROVED BACKUP CONNECTION
 * Conexión mejorada con reconexión automática y manejo robusto
 */
public class BackupConnection {
    private static final Logger LOGGER = Logger.getLogger(BackupConnection.class.getName());

    private final String host;
    private final int port;
    private final SystemConfig config;

    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean connected;
    private volatile long lastSuccessfulOperation;
    private int reconnectionAttempts = 0;
    private static final int MAX_RECONNECTION_ATTEMPTS = 3;

    public BackupConnection(String host, int port) {
        this.host = host;
        this.port = port;
        this.config = SystemConfig.getInstance();
        this.connected = false;
        this.lastSuccessfulOperation = 0;
    }

    public boolean connect() {
        return connectWithRetry();
    }

    private boolean connectWithRetry() {
        for (int attempt = 1; attempt <= MAX_RECONNECTION_ATTEMPTS; attempt++) {
            try {
                // Cerrar conexión existente si existe
                closeQuietly();

                LOGGER.info("🔗 Attempting connection to " + host + ":" + port + " (attempt " + attempt + ")");

                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), config.getConnectionTimeoutMs());
                socket.setSoTimeout(config.getReadTimeoutMs());

                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                lastSuccessfulOperation = System.currentTimeMillis();
                reconnectionAttempts = 0;

                LOGGER.info("✅ Successfully connected to " + host + ":" + port);
                return true;

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "❌ Connection attempt " + attempt + " failed to " + host + ":" + port, e);

                if (attempt < MAX_RECONNECTION_ATTEMPTS) {
                    try {
                        Thread.sleep(1000 * attempt); // Backoff progresivo
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        connected = false;
        return false;
    }

    public void sendMessage(ProtocolMessage message) {
        if (!ensureConnection()) {
            LOGGER.warning("❌ Cannot send message - connection unavailable");
            return;
        }

        try {
            String messageStr = message.toString();
            LOGGER.fine("📤 Sending: " + messageStr);
            writer.println(messageStr);
            writer.flush();
            lastSuccessfulOperation = System.currentTimeMillis();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to send message", e);
            connected = false;
        }
    }

    public ProtocolMessage sendMessageAndWaitResponse(ProtocolMessage message, int timeoutMs) {
        if (!ensureConnection()) {
            LOGGER.warning("❌ Cannot send message - connection unavailable");
            return null;
        }

        try {
            // Configurar timeout específico para esta operación
            socket.setSoTimeout(timeoutMs);

            String messageStr = message.toString();
            LOGGER.fine("📤 Sending with response expected: " + messageStr);

            writer.println(messageStr);
            writer.flush();

            String responseStr = reader.readLine();

            // Restaurar timeout por defecto
            socket.setSoTimeout(config.getReadTimeoutMs());

            if (responseStr != null && !responseStr.trim().isEmpty()) {
                lastSuccessfulOperation = System.currentTimeMillis();
                LOGGER.fine("📥 Received: " + responseStr);
                return ProtocolMessage.fromString(responseStr);
            } else {
                LOGGER.warning("❌ Received empty response");
                connected = false;
                return null;
            }

        } catch (SocketTimeoutException e) {
            LOGGER.warning("⏰ Timeout waiting for response from " + host + ":" + port);
            connected = false;
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to get response", e);
            connected = false;
            return null;
        }
    }

    private boolean ensureConnection() {
        if (connected && socket != null && !socket.isClosed()) {
            // Verificar si la conexión sigue siendo válida
            long timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulOperation;
            if (timeSinceLastSuccess < 300000) { // 5 minutos
                return true;
            }
        }

        // Intentar reconectar
        if (reconnectionAttempts < MAX_RECONNECTION_ATTEMPTS) {
            reconnectionAttempts++;
            LOGGER.info("🔄 Attempting to reconnect to " + host + ":" + port);
            return connectWithRetry();
        }

        return false;
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public void close() {
        connected = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (Exception e) {
            // Ignorar errores de cierre
        }

        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (Exception e) {
            // Ignorar errores de cierre
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            // Ignorar errores de cierre
        }
    }

    public String getEndpoint() {
        return host + ":" + port;
    }

    public boolean performHealthCheck() {
        try {
            ProtocolMessage ping = new ProtocolMessage(ProtocolCommand.HEARTBEAT, null, "health-check");
            ProtocolMessage response = sendMessageAndWaitResponse(ping, 5000);
            return response != null && response.getCommand() == ProtocolCommand.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }
}