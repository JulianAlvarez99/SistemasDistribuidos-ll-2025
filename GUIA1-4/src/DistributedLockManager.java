import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 🔒 DISTRIBUTED LOCK MANAGER
 * Maneja locks distribuidos para redundancia activa
 * Implementa algoritmo de Ricart-Agrawala modificado
 */
public class DistributedLockManager {
    private static final Logger LOGGER = Logger.getLogger(DistributedLockManager.class.getName());

    private final String serverId;
    private final List<ServerConnection> otherServers;
    private final Map<String, DistributedLock> activeLocks;
    private final Map<String, Set<String>> pendingRequests; // resource -> set of requesting servers
    private final ReentrantLock localLock;
    private final int lockTimeoutMs;
    private final ExecutorService lockExecutor;

    public DistributedLockManager(String serverId) {
        this.serverId = serverId;
        this.otherServers = new CopyOnWriteArrayList<>();
        this.activeLocks = new ConcurrentHashMap<>();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.localLock = new ReentrantLock();
        this.lockTimeoutMs = 30000; // 30 seconds timeout
        this.lockExecutor = Executors.newCachedThreadPool();
    }

    /**
     * 🔒 ADQUIRIR LOCK DISTRIBUIDO
     * Implementa algoritmo de consenso para obtener exclusive lock
     */
    public boolean acquireDistributedLock(String resourceId, String operationType) {
        LOGGER.info("🔒 Attempting to acquire distributed lock for: " + resourceId + " (op: " + operationType + ")");

        try {
            // 1. Crear lock local
            DistributedLock lock = new DistributedLock(resourceId, serverId, operationType);

            // 2. Solicitar consenso a todos los otros servidores
            if (requestConsensusFromAllServers(lock)) {
                // 3. Si todos están de acuerdo, adquirir lock
                activeLocks.put(resourceId, lock);
                LOGGER.info("✅ Distributed lock acquired for: " + resourceId);
                return true;
            } else {
                LOGGER.warning("❌ Failed to acquire distributed lock for: " + resourceId);
                return false;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error acquiring distributed lock for: " + resourceId, e);
            return false;
        }
    }

    /**
     * 🔓 LIBERAR LOCK DISTRIBUIDO
     */
    public void releaseDistributedLock(String resourceId) {
        LOGGER.info("🔓 Releasing distributed lock for: " + resourceId);

        try {
            DistributedLock lock = activeLocks.remove(resourceId);
            if (lock != null) {
                // Notificar a todos los servidores que el lock fue liberado
                notifyLockReleased(resourceId);
                LOGGER.info("✅ Distributed lock released for: " + resourceId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error releasing distributed lock for: " + resourceId, e);
        }
    }

    /**
     * 🗳️ SOLICITAR CONSENSO A TODOS LOS SERVIDORES
     */
    private boolean requestConsensusFromAllServers(DistributedLock lock) {
        if (otherServers.isEmpty()) {
            // Si no hay otros servidores, el consenso es automático
            return true;
        }

        CountDownLatch consensusLatch = new CountDownLatch(otherServers.size());
        AtomicInteger approvals = new AtomicInteger(0);

        // Enviar solicitud de lock a todos los servidores
        for (ServerConnection server : otherServers) {
            lockExecutor.submit(() -> {
                try {
                    if (requestLockFromServer(server, lock)) {
                        approvals.incrementAndGet();
                    }
                } finally {
                    consensusLatch.countDown();
                }
            });
        }

        try {
            // Esperar respuestas de todos los servidores con timeout
            if (consensusLatch.await(lockTimeoutMs, TimeUnit.MILLISECONDS)) {
                // Verificar si TODOS los servidores aprobaron
                boolean consensus = approvals.get() == otherServers.size();
                LOGGER.info("Consensus result: " + approvals.get() + "/" + otherServers.size() +
                        " approvals - " + (consensus ? "ACQUIRED" : "DENIED"));
                return consensus;
            } else {
                LOGGER.warning("Consensus timeout for lock: " + lock.getResourceId());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 📨 SOLICITAR LOCK A UN SERVIDOR ESPECÍFICO
     */
    private boolean requestLockFromServer(ServerConnection server, DistributedLock lock) {
        try {
            ProtocolMessage lockRequest = new ProtocolMessage(
                    ProtocolCommand.LOCK_REQUEST,
                    lock.getResourceId(),
                    lock.getOperationType() + "|" + serverId
            );

            ProtocolMessage response = server.sendMessageAndWaitResponse(lockRequest, 5000);

            return response != null && response.getCommand() == ProtocolCommand.LOCK_GRANTED;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error requesting lock from server: " + server.getServerId(), e);
            return false;
        }
    }

    /**
     * 📢 NOTIFICAR LIBERACIÓN DE LOCK
     */
    private void notifyLockReleased(String resourceId) {
        ProtocolMessage releaseMessage = new ProtocolMessage(
                ProtocolCommand.LOCK_RELEASED,
                resourceId,
                serverId
        );

        for (ServerConnection server : otherServers) {
            lockExecutor.submit(() -> {
                try {
                    server.sendMessage(releaseMessage);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying lock release to: " + server.getServerId(), e);
                }
            });
        }
    }

    /**
     * 📥 PROCESAR SOLICITUD DE LOCK DE OTRO SERVIDOR
     */
    public ProtocolMessage processLockRequest(ProtocolMessage request) {
        String resourceId = request.getFileName();
        String requestingServer = extractServerIdFromContent(request.getContent());

        LOGGER.info("📥 Lock request from " + requestingServer + " for: " + resourceId);

        localLock.lock();
        try {
            // Verificar si tenemos el recurso lockeado
            if (activeLocks.containsKey(resourceId)) {
                LOGGER.info("❌ Lock denied - resource already locked: " + resourceId);
                return new ProtocolMessage(ProtocolCommand.LOCK_DENIED, resourceId, "Resource locked by " + serverId);
            }

            // Si no está lockeado, otorgar el lock
            LOGGER.info("✅ Lock granted to " + requestingServer + " for: " + resourceId);
            return new ProtocolMessage(ProtocolCommand.LOCK_GRANTED, resourceId, serverId);

        } finally {
            localLock.unlock();
        }
    }

    /**
     * 📥 PROCESAR LIBERACIÓN DE LOCK DE OTRO SERVIDOR
     */
    public void processLockRelease(ProtocolMessage releaseMessage) {
        String resourceId = releaseMessage.getFileName();
        String releasingServer = releaseMessage.getContent();

        LOGGER.info("📥 Lock released by " + releasingServer + " for: " + resourceId);

        // Remover de pending requests si existe
        pendingRequests.remove(resourceId);
    }

    /**
     * 🔗 AGREGAR CONEXIÓN A OTRO SERVIDOR
     */
    public void addServerConnection(ServerConnection connection) {
        otherServers.add(connection);
        LOGGER.info("Added server connection: " + connection.getServerId() +
                " (Total servers: " + otherServers.size() + ")");
    }

    /**
     * 🔗 REMOVER CONEXIÓN A SERVIDOR
     */
    public void removeServerConnection(String serverId) {
        otherServers.removeIf(conn -> conn.getServerId().equals(serverId));
        LOGGER.info("Removed server connection: " + serverId);
    }

    /**
     * 🔍 VERIFICAR SI TENEMOS LOCK PARA RECURSO
     */
    public boolean hasLock(String resourceId) {
        return activeLocks.containsKey(resourceId);
    }

    /**
     * 📊 OBTENER ESTADÍSTICAS DE LOCKS
     */
    public Map<String, Object> getLockStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeLocksCount", activeLocks.size());
        stats.put("connectedServers", otherServers.size());
        stats.put("pendingRequests", pendingRequests.size());
        stats.put("serverId", serverId);
        return stats;
    }

    private String extractServerIdFromContent(String content) {
        if (content == null) return "unknown";
        String[] parts = content.split("\\|");
        return parts.length > 1 ? parts[1] : "unknown";
    }

    public void shutdown() {
        LOGGER.info("Shutting down DistributedLockManager");

        // Liberar todos los locks activos
        for (String resourceId : activeLocks.keySet()) {
            releaseDistributedLock(resourceId);
        }

        if (lockExecutor != null) {
            lockExecutor.shutdown();
        }
    }

    /**
     * 🔒 CLASE INTERNA: DistributedLock
     */
    public static class DistributedLock {
        private final String resourceId;
        private final String ownerId;
        private final String operationType;
        private final long acquisitionTime;

        public DistributedLock(String resourceId, String ownerId, String operationType) {
            this.resourceId = resourceId;
            this.ownerId = ownerId;
            this.operationType = operationType;
            this.acquisitionTime = System.currentTimeMillis();
        }

        public String getResourceId() { return resourceId; }
        public String getOwnerId() { return ownerId; }
        public String getOperationType() { return operationType; }
        public long getAcquisitionTime() { return acquisitionTime; }
    }

    /**
     * 🔗 CLASE INTERNA: ServerConnection
     */
    public static class ServerConnection {
        private final String serverId;
        private final String host;
        private final int port;
        private final BackupConnection connection;

        public ServerConnection(String serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
            this.connection = new BackupConnection(host, port);
        }

        public boolean connect() {
            return connection.connect();
        }

        public void sendMessage(ProtocolMessage message) {
            connection.sendMessage(message);
        }

        public ProtocolMessage sendMessageAndWaitResponse(ProtocolMessage message, int timeoutMs) {
            return connection.sendMessageAndWaitResponse(message, timeoutMs);
        }

        public String getServerId() { return serverId; }
        public String getHost() { return host; }
        public int getPort() { return port; }

        public void close() {
            connection.close();
        }
    }
}