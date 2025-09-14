import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.*;

/**
 * 🔄 ACTIVE REPLICA SERVER
 * Servidor que implementa redundancia activa - puede recibir operaciones de clientes
 * y coordinar con otras réplicas para mantener consistencia
 */
public class ActiveReplicaServer {
    private static final Logger LOGGER = Logger.getLogger(ActiveReplicaServer.class.getName());

    private final String serverId;
    private final int port;
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
        this.serverId = "REPLICA_" + port;
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.lockManager = new DistributedLockManager(serverId);
        this.replicationManager = new ActiveReplicationManager(serverId);

        this.clientThreadPool = Executors.newCachedThreadPool();
        this.replicaThreadPool = Executors.newCachedThreadPool();
        this.knownReplicas = new ConcurrentHashMap<>();
        this.maintenanceScheduler = Executors.newScheduledThreadPool(1);
        this.running = false;
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
            LOGGER.info("📊 Configuration: Storage=" + fileManager.toString());

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();

                    // Determinar si es cliente o réplica basado en el primer mensaje
                    clientThreadPool.submit(new ConnectionHandler(clientSocket));

                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Error accepting connection", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start Active Replica Server", e);
        }
    }

    /**
     * 🔧 SERVICIOS DE MANTENIMIENTO
     */
    private void startMaintenanceServices() {
        // Monitoreo de salud de réplicas cada 30 segundos
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                monitorReplicaHealth();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during replica health monitoring", e);
            }
        }, 30, 30, TimeUnit.SECONDS);

        // Limpieza de operaciones antiguas cada 5 minutos
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupOldOperations();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during operation cleanup", e);
            }
        }, 300, 300, TimeUnit.SECONDS);
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
        // Implementar limpieza de historial de operaciones antiguas
        LOGGER.fine("🧹 Cleaning up old operations");
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
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error adding replica: " + host + ":" + port, e);
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
            // Enviar heartbeat
            ProtocolMessage heartbeat = new ProtocolMessage(ProtocolCommand.HEARTBEAT, null, serverId);
            ProtocolMessage response = replica.getConnection().sendMessageAndWaitResponse(heartbeat, 5000);

            return response != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 🛑 DETENER SERVIDOR
     */
    public void stop() {
        running = false;

        LOGGER.info("🛑 Stopping Active Replica Server: " + serverId);

        // Cerrar recursos
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }

            clientThreadPool.shutdown();
            replicaThreadPool.shutdown();
            maintenanceScheduler.shutdown();

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

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error stopping server", e);
        }

        LOGGER.info("✅ Active Replica Server stopped");
    }

    /**
     * 📊 ESTADÍSTICAS DEL SERVIDOR
     */
    public Map<String, Object> getServerStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("port", port);
        stats.put("activeReplicas", knownReplicas.size());
        stats.put("lockStats", lockManager.getLockStatistics());
        stats.put("replicationStats", replicationManager.getReplicationStatistics());
        return stats;
    }

    /**
     * 🔌 MANEJADOR DE CONEXIONES
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
                if (messageStr != null) {
                    ProtocolMessage message = ProtocolMessage.fromString(messageStr);

                    // Determinar el tipo de conexión y procesar
                    if (message.getCommand().isClientCommand()) {
                        // Es un cliente
                        handleClientRequest(message, writer);
                    } else {
                        // Es otra réplica
                        handleReplicaMessage(message, writer);
                    }
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error handling connection", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing socket", e);
                }
            }
        }

        /**
         * 👤 MANEJAR SOLICITUD DE CLIENTE
         */
        private void handleClientRequest(ProtocolMessage message, PrintWriter writer) {
            LOGGER.info("👤 Client request: " + message.toDebugString());

            ProtocolMessage response = processClientOperation(message);
            writer.println(response.toString());
        }

        /**
         * 🔄 MANEJAR MENSAJE DE RÉPLICA
         */
        private void handleReplicaMessage(ProtocolMessage message, PrintWriter writer) {
            LOGGER.info("🔄 Replica message: " + message.toDebugString());

            ProtocolMessage response = processReplicaMessage(message);
            if (response != null) {
                writer.println(response.toString());
            }
        }
    }

    /**
     * 👤 PROCESAR OPERACIÓN DE CLIENTE
     * Redundancia activa
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
                    return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Unsupported operation");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing client operation", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Server error: " + e.getMessage());
        }
    }

    /**
     * ✍️ PROCESAR OPERACIÓN DE ESCRITURA (WRITE/DELETE)
     * Implementa el protocolo de redundancia activa completo
     */
    private ProtocolMessage processWriteOperation(String fileName, ProtocolCommand operation, String content) {
        LOGGER.info("✍️ Processing write operation with active replication: " + operation + " on " + fileName);

        try {
            // FASE 1: Adquirir lock distribuido
            if (!lockManager.acquireDistributedLock(fileName, operation.getCommand())) {
                return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Could not acquire distributed lock");
            }

            try {
                // FASE 2: Ejecutar operación localmente
                OperationResult localResult = executeLocalOperation(fileName, operation, content);

                if (!localResult.isSuccess()) {
                    return new ProtocolMessage(ProtocolCommand.ERROR, fileName, localResult.getMessage());
                }

                // FASE 3: Sincronizar con todas las réplicas
                boolean syncSuccess = replicationManager.synchronizeOperation(fileName, operation, content);

                if (syncSuccess) {
                    LOGGER.info("✅ Write operation completed successfully: " + operation + " on " + fileName);
                    return new ProtocolMessage(ProtocolCommand.SUCCESS, fileName,
                            localResult.getContent() != null ? localResult.getContent() : "Operation completed");
                } else {
                    LOGGER.warning("❌ Synchronization failed for: " + operation + " on " + fileName);
                    // En caso de fallo de sync, podríamos revertir la operación local
                    return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Synchronization failed");
                }

            } finally {
                // FASE 4: Liberar lock distribuido
                lockManager.releaseDistributedLock(fileName);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during write operation with active replication", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, fileName, "Replication error: " + e.getMessage());
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
                return fileManager.writeFile(fileName, content);
            case DELETE:
                return fileManager.deleteFile(fileName);
            default:
                return new OperationResult(false, "Unsupported local operation: " + operation);
        }
    }

    /**
     * 🔄 PROCESAR MENSAJE DE RÉPLICA
     */
    private ProtocolMessage processReplicaMessage(ProtocolMessage message) {
        if (message.getCommand().isLockCommand()) {
            return lockManager.processLockRequest(message);
        } else if (message.getCommand().isReplicationCommand()) {
            switch (message.getCommand()) {
                case OPERATION_PROPOSAL:
                    return replicationManager.processOperationProposal(message);
                case OPERATION_COMMIT:
                    return replicationManager.processOperationCommit(message, fileManager);
                default:
                    return null; // No response needed
            }
        } else if (message.getCommand() == ProtocolCommand.HEARTBEAT) {
            return new ProtocolMessage(ProtocolCommand.SUCCESS, null, serverId);
        }

        return null;
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

        public ReplicaInfo(String serverId, String host, int port, DistributedLockManager.ServerConnection connection) {
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