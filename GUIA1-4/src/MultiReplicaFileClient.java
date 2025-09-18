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
 * 🔄 CORRECTED MULTI-REPLICA FILE CLIENT
 * Cliente corregido que implementa correctamente la redundancia activa
 */
public class MultiReplicaFileClient {
    private static final Logger LOGGER = Logger.getLogger(MultiReplicaFileClient.class.getName());

    private final List<ReplicaEndpoint> availableReplicas;
    private final String clientId;
    private final SystemConfig config;
    private final int maxRetries;
    private final LoadBalancingStrategy loadBalancing;
    private int currentReplicaIndex = 0; // Para round robin

    public enum LoadBalancingStrategy {
        ROUND_ROBIN("ROUND_ROBIN"),
        RANDOM("RANDOM"),
        PREFERRED("PREFERRED");

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
        this.config = SystemConfig.getInstance();
        this.availableReplicas = new ArrayList<>();
        this.clientId = "MULTI_CLIENT_" + System.currentTimeMillis();
        this.maxRetries = config.getMaxRetries();
        this.loadBalancing = LoadBalancingStrategy.fromString(strategy);

        // Configurar réplicas automáticamente desde configuración
        configurarReplicasDesdeConfig();

        LOGGER.info("🔄 MultiReplicaFileClient initialized with strategy: " + strategy);
        LOGGER.info("📊 Available replicas: " + availableReplicas.size());
    }

    /**
     * ⚙️ CONFIGURAR RÉPLICAS DESDE CONFIGURACIÓN
     */
    private void configurarReplicasDesdeConfig() {
        String host = config.getDefaultHost();
        int[] ports = config.getDefaultPorts();

        for (int i = 0; i < ports.length; i++) {
            boolean isPreferred = (i == 0); // Primer puerto como preferido
            addReplica(host, ports[i], isPreferred);
        }
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
     * 📝 WRITE OPERATION - Redundancia activa real
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

        // En redundancia activa, enviar a UNA réplica que coordinará con las demás
        return executeOperationWithActiveReplication(message, "WRITE " + fileName);
    }

    /**
     * 📖 READ OPERATION - Puede leer desde cualquier réplica
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
     * 🗑️ DELETE OPERATION - Redundancia activa real
     */
    public OperationResult delete(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return new OperationResult(false, "File name cannot be null or empty");
        }

        LOGGER.info("🗑️ DELETE request: " + fileName + " - Active Replication");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.DELETE, fileName, null);
        message.setClientId(clientId);

        // En redundancia activa, enviar a UNA réplica que coordinará con las demás
        return executeOperationWithActiveReplication(message, "DELETE " + fileName);
    }

    /**
     * 📋 LIST OPERATION - Puede listar desde cualquier réplica
     */
    public OperationResult listFiles() {
        LOGGER.info("📋 LIST request");

        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.LIST, null, null);
        message.setClientId(clientId);

        return executeOperationWithFailover(message, "LIST files");
    }

    /**
     * 🔄 EJECUTAR OPERACIÓN CON REDUNDANCIA ACTIVA
     * Para operaciones de escritura/eliminación - enviar solo a UNA réplica coordinadora
     */
    private OperationResult executeOperationWithActiveReplication(ProtocolMessage message, String operationDescription) {
        List<ReplicaEndpoint> healthyReplicas = getHealthyReplicas();

        if (healthyReplicas.isEmpty()) {
            return new OperationResult(false, "No healthy replicas available for " + operationDescription);
        }

        // Seleccionar UNA réplica coordinadora usando la estrategia configurada
        ReplicaEndpoint coordinatorReplica = selectCoordinatorReplica(healthyReplicas);

        LOGGER.info("🎯 Selected coordinator replica: " + coordinatorReplica.getEndpoint() + " for " + operationDescription);

        // Enviar operación solo al coordinador
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                LOGGER.info("🔗 Attempting " + operationDescription + " on coordinator: " + coordinatorReplica.getEndpoint() +
                        " (attempt " + attempt + "/" + maxRetries + ")");

                OperationResult result = sendMessageToReplica(coordinatorReplica, message, operationDescription);

                if (result.isSuccess()) {
                    coordinatorReplica.recordSuccess();
                    LOGGER.info("✅ " + operationDescription + " completed successfully via coordinator: " + coordinatorReplica.getEndpoint());
                    return result;
                } else {
                    coordinatorReplica.recordFailure();
                    LOGGER.warning("❌ " + operationDescription + " failed on coordinator " + coordinatorReplica.getEndpoint() + ": " + result.getMessage());

                    // Si falla el coordinador, intentar con otro
                    if (attempt < maxRetries) {
                        healthyReplicas.remove(coordinatorReplica);
                        if (!healthyReplicas.isEmpty()) {
                            coordinatorReplica = selectCoordinatorReplica(healthyReplicas);
                            LOGGER.info("🔄 Switching to new coordinator: " + coordinatorReplica.getEndpoint());
                        }
                    }
                }

            } catch (Exception e) {
                coordinatorReplica.recordFailure();
                LOGGER.log(Level.WARNING, "❌ Connection error to coordinator " + coordinatorReplica.getEndpoint(), e);

                // Cambiar coordinador para el siguiente intento
                if (attempt < maxRetries) {
                    healthyReplicas.remove(coordinatorReplica);
                    if (!healthyReplicas.isEmpty()) {
                        coordinatorReplica = selectCoordinatorReplica(healthyReplicas);
                    } else {
                        break; // No hay más réplicas disponibles
                    }
                }
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000 * attempt); // Backoff
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new OperationResult(false, "Failed to execute " + operationDescription + " on any coordinator after " + maxRetries + " attempts");
    }

    /**
     * 🔄 EJECUTAR OPERACIÓN CON FAILOVER (para lecturas y listados)
     */
    private OperationResult executeOperationWithFailover(ProtocolMessage message, String operationDescription) {
        List<ReplicaEndpoint> replicaOrder = selectReplicaOrder();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            for (ReplicaEndpoint replica : replicaOrder) {
                if (!replica.isHealthy()) {
                    continue;
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
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String errorMsg = "All replicas failed for " + operationDescription + " after " + maxRetries + " attempts";
        if (lastException != null) {
            errorMsg += ": " + lastException.getMessage();
        }

        LOGGER.severe("❌ " + errorMsg);
        return new OperationResult(false, errorMsg);
    }

    /**
     * 🎯 SELECCIONAR RÉPLICA COORDINADORA
     */
    private ReplicaEndpoint selectCoordinatorReplica(List<ReplicaEndpoint> healthyReplicas) {
        switch (loadBalancing) {
            case PREFERRED:
                return healthyReplicas.stream()
                        .filter(ReplicaEndpoint::isPreferred)
                        .findFirst()
                        .orElse(healthyReplicas.get(0));

            case RANDOM:
                return healthyReplicas.get(ThreadLocalRandom.current().nextInt(healthyReplicas.size()));

            case ROUND_ROBIN:
            default:
                ReplicaEndpoint selected = healthyReplicas.get(currentReplicaIndex % healthyReplicas.size());
                currentReplicaIndex = (currentReplicaIndex + 1) % healthyReplicas.size();
                return selected;
        }
    }

    /**
     * 🔍 OBTENER RÉPLICAS SALUDABLES
     */
    private List<ReplicaEndpoint> getHealthyReplicas() {
        List<ReplicaEndpoint> healthy = new ArrayList<>();
        for (ReplicaEndpoint replica : availableReplicas) {
            if (replica.isHealthy()) {
                healthy.add(replica);
            }
        }

        // Si no hay réplicas saludables, intentar con todas
        if (healthy.isEmpty()) {
            LOGGER.warning("⚠️ No healthy replicas found, trying all replicas");
            return new ArrayList<>(availableReplicas);
        }

        return healthy;
    }

    /**
     * 🔄 SELECCIONAR ORDEN DE RÉPLICAS
     */
    private List<ReplicaEndpoint> selectReplicaOrder() {
        List<ReplicaEndpoint> healthyReplicas = getHealthyReplicas();

        switch (loadBalancing) {
            case PREFERRED:
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
                if (!healthyReplicas.isEmpty()) {
                    int offset = currentReplicaIndex % healthyReplicas.size();
                    Collections.rotate(healthyReplicas, -offset);
                    currentReplicaIndex = (currentReplicaIndex + 1) % healthyReplicas.size();
                }
                break;
        }

        return healthyReplicas;
    }

    /**
     * 📨 ENVIAR MENSAJE A UNA RÉPLICA ESPECÍFICA
     */
    private OperationResult sendMessageToReplica(ReplicaEndpoint replica, ProtocolMessage message, String operationDescription) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(replica.getHost(), replica.getPort()), config.getConnectionTimeoutMs());
            socket.setSoTimeout(config.getReadTimeoutMs());

            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                String serializedMessage = message.toString();
                LOGGER.fine("📤 Sending to " + replica.getEndpoint() + ": " + message.toDebugString());

                writer.println(serializedMessage);
                writer.flush();

                String responseStr = reader.readLine();

                if (responseStr == null || responseStr.trim().isEmpty()) {
                    return new OperationResult(false, "No response from replica: " + replica.getEndpoint());
                }

                LOGGER.fine("📥 Received from " + replica.getEndpoint() + ": " + responseStr);

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

    // Getters y demás métodos permanecen igual...
    public List<ReplicaEndpoint> getAvailableReplicas() { return new ArrayList<>(availableReplicas); }
    public String getClientId() { return clientId; }
    public LoadBalancingStrategy getLoadBalancingStrategy() { return loadBalancing; }

    /**
     * 🔗 CLASE INTERNA: ReplicaEndpoint (sin cambios)
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
                return (System.currentTimeMillis() - lastSuccessTime) < 120000;
            }
        }

        public double getHealthScore() {
            synchronized (statsLock) {
                if (totalRequests == 0) return 1.0;
                double successRate = (double) successfulRequests / totalRequests;

                // Penalizar fallos recientes
                long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime;
                double timePenalty = lastFailureTime > 0 ? Math.min(1.0, timeSinceLastFailure / 120000.0) : 1.0;

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