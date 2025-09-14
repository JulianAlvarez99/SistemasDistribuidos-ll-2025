import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔄 MULTI-REPLICA FILE CLIENT
 * Cliente que puede conectarse a cualquier réplica activa
 * Implementa load balancing y failover automático
 */
public class MultiReplicaFileClient {
    private static final Logger LOGGER = Logger.getLogger(MultiReplicaFileClient.class.getName());

    private final List<ReplicaEndpoint> availableReplicas;
    private final String clientId;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;
    private final int maxRetries;
    private final LoadBalancingStrategy loadBalancing;

    public enum LoadBalancingStrategy {
        ROUND_ROBIN("ROUND_ROBIN"),    // Rotar entre réplicas
        RANDOM("RANDOM"),         // Selección aleatoria
        PREFERRED("PREFERRED");       // Usar réplica preferida si está disponible

        private final String command;

        LoadBalancingStrategy(String command){
            this.command = command;
        }

        public String getCommand() {
            return command;
        }

        public static LoadBalancingStrategy fromString(String command) {
            for (LoadBalancingStrategy cmd : values()) {
                if (cmd.command.equals(command)) {
                    return cmd;
                }
            }
            throw new IllegalArgumentException("Unknown command: " + command);
        }

    }

    public MultiReplicaFileClient(String strategy) {
        this.availableReplicas = new ArrayList<>();
        this.clientId = "MULTI_CLIENT_" + System.currentTimeMillis();
        this.connectionTimeoutMs = 10000;
        this.readTimeoutMs = 30000;
        this.maxRetries = 3;
        this.loadBalancing = LoadBalancingStrategy.fromString(strategy);

        // Configurar réplicas por defecto
        addReplica("localhost", 8080, true);  // Réplica preferida
        addReplica("localhost", 8081, false);
        addReplica("localhost", 8082, false);
    }

    /**
     * 🔗 AGREGAR RÉPLICA DISPONIBLE
     */
    public void addReplica(String host, int port, boolean preferred) {
        ReplicaEndpoint replica = new ReplicaEndpoint(host, port, preferred);
        availableReplicas.add(replica);
        LOGGER.info("Added replica: " + host + ":" + port + (preferred ? " (preferred)" : ""));
    }

    /**
     * 🔗 REMOVER RÉPLICA
     */
    public void removeReplica(String host, int port) {
        availableReplicas.removeIf(replica ->
                replica.getHost().equals(host) && replica.getPort() == port);
        LOGGER.info("Removed replica: " + host + ":" + port);
    }

    /**
     * 📝 WRITE OPERATION CON REDUNDANCIA ACTIVA
     */
    public OperationResult write(String fileName, String content) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        if (content == null) {
            content = "";
        }

        LOGGER.info("📝 WRITE request: " + fileName + " (" + content.length() + " chars) - Active Replication");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.WRITE, fileName, content);
        message.setClientId(clientId);

        return executeOperationWithFailover(message, "WRITE " + fileName);
    }

    /**
     * 📖 READ OPERATION
     */
    public OperationResult read(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        LOGGER.info("📖 READ request: " + fileName);

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.READ, fileName, null);
        message.setClientId(clientId);

        return executeOperationWithFailover(message, "READ " + fileName);
    }

    /**
     * 🗑️ DELETE OPERATION CON REDUNDANCIA ACTIVA
     */
    public OperationResult delete(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        LOGGER.info("🗑️ DELETE request: " + fileName + " - Active Replication");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.DELETE, fileName, null);
        message.setClientId(clientId);

        return executeOperationWithFailover(message, "DELETE " + fileName);
    }

    /**
     * 📋 LIST OPERATION
     */
    public OperationResult listFiles() {
        LOGGER.info("📋 LIST request");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.LIST, null, null);
        message.setClientId(clientId);

        return executeOperationWithFailover(message, "LIST files");
    }

    /**
     * 🔄 EJECUTAR OPERACIÓN CON FAILOVER AUTOMÁTICO
     */
    private OperationResult executeOperationWithFailover(ProtocolMessage message, String operationDescription) {
        List<ReplicaEndpoint> replicaOrder = selectReplicaOrder();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            for (ReplicaEndpoint replica : replicaOrder) {
                if (!replica.isHealthy()) {
                    continue; // Saltar réplicas no saludables
                }

                try {
                    LOGGER.fine("🔗 Attempting " + operationDescription + " on replica: " + replica.getEndpoint() +
                            " (attempt " + attempt + "/" + maxRetries + ")");

                    OperationResult result = sendMessageToReplica(replica, message, operationDescription);

                    if (result.isSuccess()) {
                        replica.recordSuccess();
                        LOGGER.info("✅ " + operationDescription + " completed successfully via: " + replica.getEndpoint());
                        return result;
                    } else {
                        replica.recordFailure();
                        LOGGER.warning("❌ " + operationDescription + " failed on " + replica.getEndpoint() + ": " + result.getMessage());
                    }

                } catch (Exception e) {
                    lastException = e;
                    replica.recordFailure();
                    LOGGER.log(Level.WARNING, "❌ Connection error to " + replica.getEndpoint(), e);
                }
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000 * attempt); // Backoff exponencial
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String errorMsg = "All replicas failed for " + operationDescription +
                " after " + maxRetries + " attempts";
        if (lastException != null) {
            errorMsg += ": " + lastException.getMessage();
        }

        LOGGER.severe("❌ " + errorMsg);
        return new OperationResult(false, errorMsg);
    }

    /**
     * 📨 ENVIAR MENSAJE A UNA RÉPLICA ESPECÍFICA
     */
    private OperationResult sendMessageToReplica(ReplicaEndpoint replica, ProtocolMessage message, String operationDescription) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(replica.getHost(), replica.getPort()), connectionTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                // Enviar mensaje
                String serializedMessage = message.toString();
                LOGGER.fine("📤 Sending to " + replica.getEndpoint() + ": " + message.toDebugString());

                writer.println(serializedMessage);
                writer.flush();

                // Leer respuesta
                String responseStr = reader.readLine();

                if (responseStr == null || responseStr.trim().isEmpty()) {
                    return new OperationResult(false, "No response from replica: " + replica.getEndpoint());
                }

                LOGGER.fine("📥 Received from " + replica.getEndpoint() + ": " + responseStr);

                // Parsear respuesta
                ProtocolMessage response = ProtocolMessage.fromString(responseStr);
                return processServerResponse(response, operationDescription, replica.getEndpoint());
            }

        } catch (SocketTimeoutException e) {
            throw new RuntimeException("Timeout communicating with " + replica.getEndpoint(), e);
        } catch (java.net.ConnectException e) {
            throw new RuntimeException("Cannot connect to " + replica.getEndpoint(), e);
        } catch (Exception e) {
            throw new RuntimeException("Communication error with " + replica.getEndpoint() + ": " + e.getMessage(), e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error closing socket to " + replica.getEndpoint(), e);
                }
            }
        }
    }

    /**
     * 🔄 SELECCIONAR ORDEN DE RÉPLICAS SEGÚN ESTRATEGIA
     */
    private List<ReplicaEndpoint> selectReplicaOrder() {
        List<ReplicaEndpoint> healthyReplicas = availableReplicas.stream()
                .filter(ReplicaEndpoint::isHealthy)
                .collect(ArrayList::new, (list, replica) -> list.add(replica), ArrayList::addAll);

        if (healthyReplicas.isEmpty()) {
            // Si no hay réplicas saludables, intentar con todas
            healthyReplicas = new ArrayList<>(availableReplicas);
        }

        switch (loadBalancing) {
            case PREFERRED:
                // Ordenar por preferencia, luego por salud
                healthyReplicas.sort((a, b) -> {
                    if (a.isPreferred() && !b.isPreferred()) return -1;
                    if (!a.isPreferred() && b.isPreferred()) return 1;
                    return Double.compare(b.getHealthScore(), a.getHealthScore());
                });
                break;

            case RANDOM:
                Collections.shuffle(healthyReplicas);
                break;

            case ROUND_ROBIN:
            default:
                // Para round robin, rotar la lista
                if (!healthyReplicas.isEmpty()) {
                    int offset = (int) (System.currentTimeMillis() % healthyReplicas.size());
                    Collections.rotate(healthyReplicas, -offset);
                }
                break;
        }

        return healthyReplicas;
    }

    /**
     * 🔍 PROCESAR RESPUESTA DEL SERVIDOR
     */
    private OperationResult processServerResponse(ProtocolMessage response, String operationDescription, String endpoint) {
        if (response.getCommand() == null) {
            return new OperationResult(false, "Invalid response from " + endpoint + " - no command");
        }

        switch (response.getCommand()) {
            case SUCCESS:
                String content = response.getContent();
                LOGGER.info("✅ " + operationDescription + " - SUCCESS from " + endpoint +
                        (content != null ? " (" + content.length() + " chars)" : ""));
                return new OperationResult(true, "Operation completed successfully", content);

            case ERROR:
                String errorMsg = response.getContent() != null ? response.getContent() : "Unknown server error";
                LOGGER.warning("❌ " + operationDescription + " - ERROR from " + endpoint + ": " + errorMsg);
                return new OperationResult(false, errorMsg);

            case NOT_FOUND:
                String notFoundMsg = response.getContent() != null ? response.getContent() : "Resource not found";
                LOGGER.info("📂 " + operationDescription + " - NOT FOUND from " + endpoint + ": " + notFoundMsg);
                return new OperationResult(false, notFoundMsg);

            default:
                String unexpectedMsg = "Unexpected response command from " + endpoint + ": " + response.getCommand();
                LOGGER.warning("⚠️ " + operationDescription + " - " + unexpectedMsg);
                return new OperationResult(false, unexpectedMsg);
        }
    }

    /**
     * 🏥 VERIFICAR SALUD DE TODAS LAS RÉPLICAS
     */
    public void checkReplicaHealth() {
        LOGGER.info("🏥 Checking health of " + availableReplicas.size() + " replicas");

        for (ReplicaEndpoint replica : availableReplicas) {
            checkSingleReplicaHealth(replica);
        }
    }

    /**
     * 🏥 VERIFICAR SALUD DE UNA RÉPLICA
     */
    private void checkSingleReplicaHealth(ReplicaEndpoint replica) {
        try (Socket testSocket = new Socket()) {
            testSocket.connect(new java.net.InetSocketAddress(replica.getHost(), replica.getPort()), 3000);
            replica.recordSuccess();
            LOGGER.fine("✅ Health check passed: " + replica.getEndpoint());
        } catch (Exception e) {
            replica.recordFailure();
            LOGGER.fine("❌ Health check failed: " + replica.getEndpoint() + " - " + e.getMessage());
        }
    }

    // Getters
    public List<ReplicaEndpoint> getAvailableReplicas() { return new ArrayList<>(availableReplicas); }
    public String getClientId() { return clientId; }
    public LoadBalancingStrategy getLoadBalancingStrategy() { return loadBalancing; }

    /**
     * 🔗 CLASE INTERNA: ReplicaEndpoint
     */
    public static class ReplicaEndpoint {
        private final String host;
        private final int port;
        private final boolean preferred;
        private volatile long totalRequests;
        private volatile long successfulRequests;
        private volatile long lastSuccessTime;
        private volatile long lastFailureTime;
        private final Object statsLock = new Object();

        public ReplicaEndpoint(String host, int port, boolean preferred) {
            this.host = host;
            this.port = port;
            this.preferred = preferred;
            this.totalRequests = 0;
            this.successfulRequests = 0;
            this.lastSuccessTime = System.currentTimeMillis();
            this.lastFailureTime = 0;
        }

        public void recordSuccess() {
            synchronized (statsLock) {
                totalRequests++;
                successfulRequests++;
                lastSuccessTime = System.currentTimeMillis();
            }
        }

        public void recordFailure() {
            synchronized (statsLock) {
                totalRequests++;
                lastFailureTime = System.currentTimeMillis();
            }
        }

        public boolean isHealthy() {
            synchronized (statsLock) {
                // Considerar saludable si:
                // 1. Nunca ha fallado, O
                // 2. El último éxito es más reciente que el último fallo, O  
                // 3. Ha habido un éxito en los últimos 60 segundos
                if (lastFailureTime == 0) return true;
                if (lastSuccessTime > lastFailureTime) return true;
                return (System.currentTimeMillis() - lastSuccessTime) < 60000;
            }
        }

        public double getHealthScore() {
            synchronized (statsLock) {
                if (totalRequests == 0) return 1.0;
                double successRate = (double) successfulRequests / totalRequests;

                // Penalizar fallos recientes
                long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime;
                double timePenalty = lastFailureTime > 0 ? Math.min(1.0, timeSinceLastFailure / 60000.0) : 1.0;

                return successRate * timePenalty;
            }
        }

        public String getEndpoint() { return host + ":" + port; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public boolean isPreferred() { return preferred; }

        public long getTotalRequests() {
            synchronized (statsLock) { return totalRequests; }
        }

        public long getSuccessfulRequests() {
            synchronized (statsLock) { return successfulRequests; }
        }
    }
}