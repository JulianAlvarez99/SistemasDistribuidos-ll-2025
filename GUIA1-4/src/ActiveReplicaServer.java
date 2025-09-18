import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.*;

/**
 * 🔄 CORRECTED ACTIVE REPLICA SERVER
 * Servidor con redundancia activa completamente funcional
 */
public class ActiveReplicaServer {
    private static final Logger LOGGER = Logger.getLogger(ActiveReplicaServer.class.getName());

    private final String serverId;
    private final int port;
    private final SystemConfig config;
    private final FileSystemManager fileManager;
    private final DistributedLockManager lockManager;
    private final ActiveReplicationManager replicationManager;

    private final ExecutorService clientThreadPool;
    private final ExecutorService replicaThreadPool;
    private ServerSocket serverSocket;
    private volatile boolean running;

    // Configuración de la red de réplicas
    private final Map<String, ReplicaInfo> knownReplicas;
    private final ScheduledExecutorService maintenanceScheduler;

    public ActiveReplicaServer(int port, String storageDirectory) {
        this.config = SystemConfig.getInstance();
        this.serverId = "REPLICA_" + port;
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.lockManager = new DistributedLockManager(serverId);
        this.replicationManager = new ActiveReplicationManager(serverId);

        this.clientThreadPool = Executors.newCachedThreadPool();
        this.replicaThreadPool = Executors.newCachedThreadPool();
        this.knownReplicas = new ConcurrentHashMap<>();
        this.maintenanceScheduler = Executors.newScheduledThreadPool(2);
        this.running = false;

        LOGGER.info("🔄 ActiveReplicaServer initialized: " + serverId);
    }

    /**
     * 🚀 INICIAR SERVIDOR DE REDUNDANCIA ACTIVA
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;

            // Iniciar servicios de mantenimiento
            startMaintenanceServices();

            LOGGER.info("🔄 Active Replica Server (" + serverId + ") started on port " + port);
            LOGGER.info("📊 Configuration: " + fileManager.toString());

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(config.getReadTimeoutMs());

                    // Procesar conexión en hilo separado
                    clientThreadPool.submit(new ConnectionHandler(clientSocket));

                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Error accepting connection", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start Active Replica Server", e);
            throw new RuntimeException("Server startup failed", e);
        }
    }

    /**
     * 🔧 SERVICIOS DE MANTENIMIENTO
     */
    private void startMaintenanceServices() {
        // Monitoreo de salud de réplicas
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                monitorReplicaHealth();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during replica health monitoring", e);
            }
        }, config.getHealthCheckIntervalSec(), config.getHealthCheckIntervalSec(), TimeUnit.SECONDS);

        // Limpieza de operaciones antiguas
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupOldOperations();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during operation cleanup", e);
            }
        }, config.getCleanupIntervalSec(), config.getCleanupIntervalSec(), TimeUnit.SECONDS);
    }

    /**
     * 🏥 MONITOREAR SALUD DE RÉPLICAS
     */
    private void monitorReplicaHealth() {
        LOGGER.fine("🏥 Monitoring health of " + knownReplicas.size() + " replicas");

        Iterator<Map.Entry<String, ReplicaInfo>> iterator = knownReplicas.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ReplicaInfo> entry = iterator.next();
            ReplicaInfo replica = entry.getValue();

            if (!isReplicaHealthy(replica)) {
                LOGGER.warning("❌ Replica unhealthy, removing: " + replica.getServerId());
                iterator.remove();
                lockManager.removeServerConnection(replica.getServerId());
                replicationManager.removeReplicaServer(replica.getServerId());
            }
        }

        LOGGER.info("📊 Active replicas: " + knownReplicas.size());
    }

    /**
     * 🧹 LIMPIAR OPERACIONES ANTIGUAS
     */
    private void cleanupOldOperations() {
        LOGGER.fine("🧹 Cleaning up old operations");
        // La limpieza específica se maneja en los managers individuales
    }

    /**
     * 🔗 AGREGAR RÉPLICA A LA RED
     */
    public void addReplica(String host, int port) {
        String replicaId = "REPLICA_" + port;

        try {
            // Crear conexión con la réplica
            DistributedLockManager.ServerConnection connection =
                    new DistributedLockManager.ServerConnection(replicaId, host, port);

            if (connection.connect()) {
                // Registrar réplica
                ReplicaInfo replica = new ReplicaInfo(replicaId, host, port, connection);
                knownReplicas.put(replicaId, replica);

                // Agregar a los managers
                lockManager.addServerConnection(connection);
                replicationManager.addReplicaServer(connection);

                LOGGER.info("✅ Added replica: " + replicaId + " (" + host + ":" + port + ")");

                // Trigger initial synchronization
                performInitialSync(replica);

            } else {
                LOGGER.warning("❌ Failed to connect to replica: " + host + ":" + port);
                throw new RuntimeException("Connection failed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error adding replica: " + host + ":" + port, e);
            throw e;
        }
    }

    /**
     * 🔄 SINCRONIZACIÓN INICIAL CON NUEVA RÉPLICA
     */
    private void performInitialSync(ReplicaInfo replica) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // Esperar que la réplica esté lista

                // Enviar estado actual
                Map<String, FileMetadata> currentFiles = fileManager.getAllFilesMetadata();
                for (FileMetadata file : currentFiles.values()) {
                    replicationManager.synchronizeOperation(
                            file.getFileName(), ProtocolCommand.WRITE, file.getContent());
                }

                LOGGER.info("✅ Initial sync completed with: " + replica.getServerId());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during initial sync with: " + replica.getServerId(), e);
            }
        }, replicaThreadPool);
    }

    /**
     * 🏥 VERIFICAR SALUD DE RÉPLICA
     */
    private boolean isReplicaHealthy(ReplicaInfo replica) {
        try {
            ProtocolMessage heartbeat = new ProtocolMessage(ProtocolCommand.HEARTBEAT, null, serverId);
            ProtocolMessage response = replica.getConnection().sendMessageAndWaitResponse(
                    heartbeat, config.getConnectionTimeoutMs());

            boolean healthy = response != null && response.getCommand() == ProtocolCommand.SUCCESS;
            if (healthy) {
                replica.updateHeartbeat();
            }
            return healthy;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Health check failed for: " + replica.getServerId(), e);
            return false;
        }
    }

    /**
     * 🛑 DETENER SERVIDOR
     */
    public void stop() {
        running = false;

        LOGGER.info("🛑 Stopping Active Replica Server: " + serverId);

        try {
            // Cerrar socket del servidor
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            // Shutdown thread pools
            shutdownExecutorService(clientThreadPool, "Client Thread Pool");
            shutdownExecutorService(replicaThreadPool, "Replica Thread Pool");
            shutdownExecutorService(maintenanceScheduler, "Maintenance Scheduler");

            // Shutdown managers
            lockManager.shutdown();
            replicationManager.shutdown();

            // Cerrar conexiones de réplicas
            for (ReplicaInfo replica : knownReplicas.values()) {
                try {
                    replica.getConnection().close();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing replica connection: " + replica.getServerId(), e);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error stopping server", e);
        }

        LOGGER.info("✅ Active Replica Server stopped");
    }

    private void shutdownExecutorService(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warning("⚠️ Forcing shutdown of " + name);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 📊 ESTADÍSTICAS DEL SERVIDOR
     */
    public Map<String, Object> getServerStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("port", port);
        stats.put("running", running);
        stats.put("activeReplicas", knownReplicas.size());
        stats.put("lockStats", lockManager.getLockStatistics());
        stats.put("replicationStats", replicationManager.getReplicationStatistics());

        // Estadísticas del file manager
        Map<String, FileMetadata> files = fileManager.getAllFilesMetadata();
        stats.put("totalFiles", files.size());

        return stats;
    }

    /**
     * 🔌 MANEJADOR DE CONEXIONES MEJORADO
     */
    private class ConnectionHandler implements Runnable {
        private final Socket socket;

        public ConnectionHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

                String messageStr = reader.readLine();
                if (messageStr != null && !messageStr.trim().isEmpty()) {

                    LOGGER.info("📨 Received message: " + messageStr);

                    ProtocolMessage message = ProtocolMessage.fromString(messageStr);
                    ProtocolMessage response;

                    // Determinar el tipo de conexión y procesar
                    if (message.getCommand().isClientCommand()) {
                        response = handleClientRequest(message);
                    } else if (message.getCommand().isLockCommand()) {
                        response = handleLockMessage(message);
                    } else if (message.getCommand().isReplicationCommand()) {
                        response = handleReplicationMessage(message);
                    } else if (message.getCommand() == ProtocolCommand.HEARTBEAT) {
                        response = new ProtocolMessage(ProtocolCommand.SUCCESS, null, serverId);
                    } else {
                        response = new ProtocolMessage(ProtocolCommand.ERROR, null,
                                "Unsupported command: " + message.getCommand());
                    }

                    if (response != null) {
                        String responseStr = response.toString();
                        LOGGER.info("📤 Sending response: " + responseStr);
                        writer.println(responseStr);
                    }
                } else {
                    LOGGER.warning("❌ Received empty or null message");
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error handling connection from: " + socket.getRemoteSocketAddress(), e);
            } finally {
                try {
                    if (!socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing socket", e);
                }
            }
        }

        /**
         * 👤 MANEJAR SOLICITUD DE CLIENTE
         */
        private ProtocolMessage handleClientRequest(ProtocolMessage message) {
            LOGGER.info("👤 Processing client request: " + message.toDebugString());
            return processClientOperation(message);
        }

        /**
         * 🔒 MANEJAR MENSAJE DE LOCK
         */
        private ProtocolMessage handleLockMessage(ProtocolMessage message) {
            LOGGER.info("🔒 Processing lock message: " + message.toDebugString());

            switch (message.getCommand()) {
                case LOCK_REQUEST:
                    return lockManager.processLockRequest(message);
                case LOCK_RELEASED:
                    lockManager.processLockRelease(message);
                    return null; // No response needed
                default:
                    return new ProtocolMessage(ProtocolCommand.ERROR, message.getFileName(),
                            "Unsupported lock command");
            }
        }

        /**
         * 🔄 MANEJAR MENSAJE DE REPLICACIÓN - IMPLEMENTACIÓN SIMPLIFICADA
         */
        private ProtocolMessage handleReplicationMessage(ProtocolMessage message) {
            LOGGER.info("🔄 [REPLICA] Processing replication message: " + message.getCommand() + " for " + message.getFileName());

            try {
                switch (message.getCommand()) {
                    case OPERATION_COMMIT:
                        return processReplicationCommit(message);

                    case HEARTBEAT:
                        return new ProtocolMessage(ProtocolCommand.SUCCESS, null, serverId);

                    default:
                        LOGGER.warning("⚠️ Unsupported replication command: " + message.getCommand());
                        return new ProtocolMessage(ProtocolCommand.ERROR, message.getFileName(),
                                "Unsupported replication command: " + message.getCommand());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error handling replication message", e);
                return new ProtocolMessage(ProtocolCommand.ERROR, message.getFileName(),
                        "Replication error: " + e.getMessage());
            }
        }

        /**
         * ✅ PROCESAR COMMIT DE REPLICACIÓN
         */
        private ProtocolMessage processReplicationCommit(ProtocolMessage commit) {
            try {
                String fileName = commit.getFileName();
                String content = commit.getContent();

                LOGGER.info("✅ [REPLICA] Processing commit for: " + fileName);

                // Determinar operación basada en el contenido
                OperationResult result;
                if (content != null && content.startsWith("DELETE:")) {
                    LOGGER.info("🗑️ [REPLICA] Executing DELETE operation for: " + fileName);
                    result = fileManager.deleteFile(fileName);
                } else {
                    LOGGER.info("📝 [REPLICA] Executing WRITE operation for: " + fileName +
                            " (content length: " + (content != null ? content.length() : 0) + ")");
                    result = fileManager.replaceFileContent(fileName, content != null ? content : "");
                }

                if (result.isSuccess()) {
                    LOGGER.info("✅ [REPLICA] Operation committed successfully: " + fileName);
                    return new ProtocolMessage(ProtocolCommand.OPERATION_COMMITTED, fileName, serverId);
                } else {
                    LOGGER.warning("❌ [REPLICA] Operation failed: " + result.getMessage());
                    return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED, fileName, result.getMessage());
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in replication commit", e);
                return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED, commit.getFileName(),
                        "Commit error: " + e.getMessage());
            }
        }
    }

    /**
     * 👤 PROCESAR OPERACIÓN DE CLIENTE - Redundancia activa completa
     */
    private ProtocolMessage processClientOperation(ProtocolMessage message) {
        String fileName = message.getFileName();
        ProtocolCommand operation = message.getCommand();
        String content = message.getContent();

        LOGGER.info("🔄 Processing client operation: " + operation + " on " + fileName);

        try {
            switch (operation) {
                case WRITE:
                case DELETE:
                    return processWriteOperation(fileName, operation, content);

                case READ:
                    return processReadOperation(fileName);

                case LIST:
                    return processListOperation();

                default:
                    return new ProtocolMessage(ProtocolCommand.ERROR, fileName,
                            "Unsupported operation: " + operation);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing client operation", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, fileName,
                    "Server error: " + e.getMessage());
        }
    }

    /**
     * ✍️ PROCESAR OPERACIÓN DE ESCRITURA - SECUENCIA COMPLETAMENTE CORREGIDA
     */
    private ProtocolMessage processWriteOperation(String fileName, ProtocolCommand operation, String content) {
        LOGGER.info("✍️ [COORDINATOR] Processing write operation: " + operation + " on " + fileName);
        LOGGER.info("📊 Available replicas for synchronization: " + knownReplicas.size());

        try {
            // FASE 1: Adquirir lock distribuido
            LOGGER.info("🔒 [PHASE 1] Acquiring distributed lock for: " + fileName);
            if (!lockManager.acquireDistributedLock(fileName, operation.getCommand())) {
                LOGGER.warning("❌ Could not acquire distributed lock for: " + fileName);
                return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Could not acquire distributed lock");
            }

            try {
                // FASE 2: Ejecutar operación localmente PRIMERO (como coordinador)
                LOGGER.info("💾 [PHASE 2] Executing operation locally as coordinator: " + operation + " on " + fileName);
                OperationResult localResult = executeLocalOperation(fileName, operation, content);

                if (!localResult.isSuccess()) {
                    LOGGER.warning("❌ Local execution failed: " + localResult.getMessage());
                    return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Local operation failed: " + localResult.getMessage());
                }

                LOGGER.info("✅ Local operation successful, now synchronizing with replicas...");

                // FASE 3: Sincronizar con réplicas DESPUÉS de éxito local
                if (!knownReplicas.isEmpty()) {
                    LOGGER.info("🔄 [PHASE 3] Synchronizing with " + knownReplicas.size() + " replicas");
                    boolean syncSuccess = replicationManager.synchronizeOperation(fileName, operation, content);

                    if (!syncSuccess) {
                        LOGGER.warning("⚠️ Synchronization with some replicas failed");
                        // En redundancia activa, continuamos ya que el coordinador tiene la operación
                        // pero registramos el problema
                    }
                } else {
                    LOGGER.info("ℹ️ No replicas to synchronize with - single node operation");
                }

                LOGGER.info("✅ [COMPLETE] Write operation completed successfully: " + operation + " on " + fileName);
                return new ProtocolMessage(ProtocolCommand.SUCCESS, fileName, "Operation completed successfully");

            } finally {
                // FASE 4: Liberar lock distribuido SIEMPRE
                LOGGER.info("🔓 [PHASE 4] Releasing distributed lock for: " + fileName);
                lockManager.releaseDistributedLock(fileName);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Error during write operation", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Operation error: " + e.getMessage());
        }
    }

    /**
     * 📖 PROCESAR OPERACIÓN DE LECTURA
     */
    private ProtocolMessage processReadOperation(String fileName) {
        // Las lecturas pueden ser locales en redundancia activa
        OperationResult result = fileManager.readFile(fileName);

        ProtocolCommand responseCommand = result.isSuccess() ? ProtocolCommand.SUCCESS :
                (result.getMessage().contains("not found") ? ProtocolCommand.NOT_FOUND : ProtocolCommand.ERROR);

        return new ProtocolMessage(responseCommand, fileName,
                result.getContent() != null ? result.getContent() : result.getMessage());
    }

    /**
     * 📋 PROCESAR OPERACIÓN DE LISTADO
     */
    private ProtocolMessage processListOperation() {
        OperationResult result = fileManager.listFiles();

        ProtocolCommand responseCommand = result.isSuccess() ? ProtocolCommand.SUCCESS : ProtocolCommand.ERROR;
        return new ProtocolMessage(responseCommand, null,
                result.getContent() != null ? result.getContent() : result.getMessage());
    }

    /**
     * 🔧 EJECUTAR OPERACIÓN LOCALMENTE
     */
    private OperationResult executeLocalOperation(String fileName, ProtocolCommand operation, String content) {
        switch (operation) {
            case WRITE:
                return fileManager.replaceFileContent(fileName, content);
            case DELETE:
                return fileManager.deleteFile(fileName);
            default:
                return new OperationResult(false, "Unsupported local operation: " + operation);
        }
    }

    /**
     * 📊 CLASE INTERNA: ReplicaInfo
     */
    private static class ReplicaInfo {
        private final String serverId;
        private final String host;
        private final int port;
        private final DistributedLockManager.ServerConnection connection;
        private volatile long lastHeartbeat;

        public ReplicaInfo(String serverId, String host, int port,
                           DistributedLockManager.ServerConnection connection) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
            this.connection = connection;
            this.lastHeartbeat = System.currentTimeMillis();
        }

        public String getServerId() { return serverId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public DistributedLockManager.ServerConnection getConnection() { return connection; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void updateHeartbeat() { this.lastHeartbeat = System.currentTimeMillis(); }
    }
}