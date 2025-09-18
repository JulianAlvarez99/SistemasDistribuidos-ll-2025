import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔗 IMPROVED BACKUP CONNECTION WITH CONNECTION POOLING
 * Conexión mejorada con pool de conexiones persistentes y reconexión automática
 */
public class BackupConnection {
    private static final Logger LOGGER = Logger.getLogger(BackupConnection.class.getName());

    private final String host;
    private final int port;
    private final SystemConfig config;

    // Pool de conexiones
    private final BlockingQueue<PooledSocket> availableConnections;
    private final AtomicInteger totalConnections;
    private final AtomicInteger busyConnections;
    private final int maxConnections = 3; // Pool pequeño pero eficiente

    private volatile boolean isShutdown = false;
    private volatile long lastSuccessfulOperation;

    public BackupConnection(String host, int port) {
        this.host = host;
        this.port = port;
        this.config = SystemConfig.getInstance();
        this.availableConnections = new LinkedBlockingQueue<>();
        this.totalConnections = new AtomicInteger(0);
        this.busyConnections = new AtomicInteger(0);
        this.lastSuccessfulOperation = System.currentTimeMillis();

        LOGGER.info("🔗 BackupConnection pool initialized for " + host + ":" + port);
    }

    public boolean connect() {
        // Precargar el pool con una conexión inicial
        try {
            PooledSocket initialConnection = createNewConnection();
            if (initialConnection != null) {
                availableConnections.offer(initialConnection);
                totalConnections.incrementAndGet();
                lastSuccessfulOperation = System.currentTimeMillis();
                LOGGER.info("✅ Initial connection established to " + host + ":" + port);
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to establish initial connection to " + host + ":" + port, e);
        }
        return false;
    }

    /**
     * 📤 ENVIAR MENSAJE CON POOL DE CONEXIONES
     */
    public void sendMessage(ProtocolMessage message) {
        PooledSocket connection = null;
        try {
            connection = getConnection();
            if (connection != null) {
                String messageStr = message.toString();
                LOGGER.fine("📤 Sending to " + host + ":" + port + ": " + message.toDebugString());

                connection.writer.println(messageStr);
                connection.writer.flush();
                lastSuccessfulOperation = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to send message to " + host + ":" + port, e);
            if (connection != null) {
                closeConnection(connection);
                connection = null;
            }
        } finally {
            if (connection != null) {
                returnConnection(connection);
            }
        }
    }

    /**
     * 📤📥 ENVIAR MENSAJE Y ESPERAR RESPUESTA CON POOL
     */
    public ProtocolMessage sendMessageAndWaitResponse(ProtocolMessage message, int timeoutMs) {
        PooledSocket connection = null;
        try {
            connection = getConnection();
            if (connection == null) {
                LOGGER.warning("❌ Cannot get connection to " + host + ":" + port);
                return null;
            }

            // Configurar timeout específico
            connection.socket.setSoTimeout(Math.max(timeoutMs, 10000)); // Mínimo 10 segundos

            String messageStr = message.toString();
            LOGGER.fine("📤 Sending with response to " + host + ":" + port + ": " + message.toDebugString());

            // Enviar mensaje
            connection.writer.println(messageStr);
            connection.writer.flush();

            // Leer respuesta
            String responseStr = connection.reader.readLine();

            if (responseStr != null && !responseStr.trim().isEmpty()) {
                lastSuccessfulOperation = System.currentTimeMillis();
                LOGGER.fine("📥 Received from " + host + ":" + port + ": " + responseStr);

                ProtocolMessage response = ProtocolMessage.fromString(responseStr);
                return response;
            } else {
                LOGGER.warning("❌ Empty response from " + host + ":" + port);
                // Conexión puede estar dañada, cerrarla
                closeConnection(connection);
                connection = null;
                return null;
            }

        } catch (SocketTimeoutException e) {
            LOGGER.warning("⏰ Timeout waiting for response from " + host + ":" + port +
                    " (timeout: " + timeoutMs + "ms)");
            if (connection != null) {
                closeConnection(connection);
                connection = null;
            }
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Error getting response from " + host + ":" + port, e);
            if (connection != null) {
                closeConnection(connection);
                connection = null;
            }
            return null;
        } finally {
            if (connection != null) {
                // Restaurar timeout por defecto
                try {
                    connection.socket.setSoTimeout(config.getReadTimeoutMs());
                } catch (Exception e) {
                    // Ignorar errores de timeout
                }
                returnConnection(connection);
            }
        }
    }

    /**
     * 🔗 OBTENER CONEXIÓN DEL POOL
     */
    private PooledSocket getConnection() {
        if (isShutdown) {
            return null;
        }

        try {
            // Intentar obtener conexión existente del pool
            PooledSocket connection = availableConnections.poll();

            if (connection != null && connection.isValid()) {
                busyConnections.incrementAndGet();
                return connection;
            }

            // Si no hay conexión válida, crear nueva si no hemos alcanzado el límite
            if (totalConnections.get() < maxConnections) {
                connection = createNewConnection();
                if (connection != null) {
                    totalConnections.incrementAndGet();
                    busyConnections.incrementAndGet();
                    return connection;
                }
            }

            // Como último recurso, esperar por una conexión disponible
            LOGGER.fine("🔄 Waiting for available connection to " + host + ":" + port);
            connection = availableConnections.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            if (connection != null && connection.isValid()) {
                busyConnections.incrementAndGet();
                return connection;
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Error getting connection from pool", e);
        }

        return null;
    }

    /**
     * 🔙 RETORNAR CONEXIÓN AL POOL
     */
    private void returnConnection(PooledSocket connection) {
        if (connection != null && connection.isValid() && !isShutdown) {
            busyConnections.decrementAndGet();
            availableConnections.offer(connection);
        } else {
            closeConnection(connection);
        }
    }

    /**
     * 🆕 CREAR NUEVA CONEXIÓN
     */
    private PooledSocket createNewConnection() {
        try {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), config.getConnectionTimeoutMs());
            socket.setSoTimeout(config.getReadTimeoutMs());
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true); // Importante para reducir latencia

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            LOGGER.fine("✅ Created new connection to " + host + ":" + port);
            return new PooledSocket(socket, writer, reader);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to create connection to " + host + ":" + port, e);
            return null;
        }
    }

    /**
     * 🔒 CERRAR CONEXIÓN ESPECÍFICA
     */
    private void closeConnection(PooledSocket connection) {
        if (connection != null) {
            try {
                connection.close();
                totalConnections.decrementAndGet();
                busyConnections.decrementAndGet();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error closing connection", e);
            }
        }
    }

    /**
     * 🔍 VERIFICAR SI ESTÁ CONECTADO
     */
    public boolean isConnected() {
        if (isShutdown) {
            return false;
        }

        // Verificar si tenemos al menos una conexión activa
        boolean hasActiveConnection = totalConnections.get() > 0;

        // Verificar si las conexiones han sido usadas recientemente
        long timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulOperation;
        boolean recentActivity = timeSinceLastSuccess < 300000; // 5 minutos

        return hasActiveConnection && recentActivity;
    }

    /**
     * 🏥 HEALTH CHECK MEJORADO
     */
    public boolean performHealthCheck() {
        try {
            ProtocolMessage ping = new ProtocolMessage(ProtocolCommand.HEARTBEAT, null, "health-check");
            ProtocolMessage response = sendMessageAndWaitResponse(ping, 8000); // 8 segundos timeout
            return response != null && response.getCommand() == ProtocolCommand.SUCCESS;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Health check failed for " + host + ":" + port, e);
            return false;
        }
    }

    /**
     * 🛑 CERRAR TODAS LAS CONEXIONES
     */
    public void close() {
        isShutdown = true;
        LOGGER.info("🛑 Closing connection pool to " + host + ":" + port);

        // Cerrar todas las conexiones disponibles
        PooledSocket connection;
        while ((connection = availableConnections.poll()) != null) {
            closeConnection(connection);
        }

        LOGGER.info("✅ Connection pool closed for " + host + ":" + port);
    }

    public String getEndpoint() {
        return host + ":" + port;
    }

    /**
     * 📊 ESTADÍSTICAS DEL POOL
     */
    public String getPoolStats() {
        return String.format("Pool[total=%d, busy=%d, available=%d, shutdown=%s]",
                totalConnections.get(),
                busyConnections.get(),
                availableConnections.size(),
                isShutdown);
    }

    /**
     * 🔗 CLASE INTERNA: PooledSocket
     */
    private static class PooledSocket {
        final Socket socket;
        final PrintWriter writer;
        final BufferedReader reader;
        private final long creationTime;

        PooledSocket(Socket socket, PrintWriter writer, BufferedReader reader) {
            this.socket = socket;
            this.writer = writer;
            this.reader = reader;
            this.creationTime = System.currentTimeMillis();
        }

        boolean isValid() {
            try {
                return socket != null && !socket.isClosed() && socket.isConnected() &&
                        !writer.checkError() && reader.ready();
            } catch (Exception e) {
                return false;
            }
        }

        void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception e) {
                // Ignorar errores de cierre
            }
        }
    }
}