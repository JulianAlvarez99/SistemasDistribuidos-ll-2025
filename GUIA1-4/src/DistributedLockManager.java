import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 🔒 ENHANCED DISTRIBUTED LOCK MANAGER
 * Maneja locks distribuidos con algoritmo optimizado y configuración centralizada
 */
public class DistributedLockManager {
    private static final Logger LOGGER = Logger.getLogger(DistributedLockManager.class.getName());

    private final String serverId;
    private final SystemConfig config;
    private final List<ServerConnection> otherServers;
    private final Map<String, DistributedLock> activeLocks;
    private final Map<String, LockRequestQueue> pendingRequests;
    private final ReentrantLock localLock;
    private final ExecutorService lockExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final AtomicLong lockOperationCounter;

    public DistributedLockManager(String serverId) {
        this.serverId = serverId;
        this.config = SystemConfig.getInstance();
        this.otherServers = new CopyOnWriteArrayList<>();
        this.activeLocks = new ConcurrentHashMap<>();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.localLock = new ReentrantLock(true); // Fair lock
        this.lockExecutor = Executors.newCachedThreadPool();
        this.maintenanceExecutor = Executors.newScheduledThreadPool(1);
        this.lockOperationCounter = new AtomicLong(0);

        startMaintenanceServices();
    }

    /**
     * 🔧 SERVICIOS DE MANTENIMIENTO
     */
    private void startMaintenanceServices() {
        // Limpieza de locks expirados cada minuto
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredLocks();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during lock cleanup", e);
            }
        }, 60, 60, TimeUnit.SECONDS);

        // Verificación de salud de conexiones cada 2 minutos
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                verifyServerConnections();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during connection verification", e);
            }
        }, 120, 120, TimeUnit.SECONDS);
    }

    /**
     * 🔒 ADQUIRIR LOCK DISTRIBUIDO - Algoritmo mejorado
     */
    public boolean acquireDistributedLock(String resourceId, String operationType) {
        long operationId = lockOperationCounter.incrementAndGet();

        LOGGER.info("🔒 [Op:" + operationId + "] Acquiring distributed lock for: " + resourceId +
                " (operation: " + operationType + ")");

        try {
            // 1. Verificar disponibilidad local
            if (isResourceLocallyLocked(resourceId)) {
                LOGGER.warning("❌ [Op:" + operationId + "] Resource already locked locally: " + resourceId);
                return false;
            }

            // 2. Crear lock temporal
            DistributedLock lockRequest = new DistributedLock(resourceId, serverId, operationType, operationId);

            // 3. Solicitar consenso con timeout configurado
            boolean consensusAchieved = requestConsensusWithTimeout(lockRequest);

            if (consensusAchieved) {
                // 4. Adquirir lock localmente
                activeLocks.put(resourceId, lockRequest);
                LOGGER.info("✅ [Op:" + operationId + "] Distributed lock acquired for: " + resourceId);
                return true;
            } else {
                LOGGER.warning("❌ [Op:" + operationId + "] Failed to achieve consensus for: " + resourceId);
                return false;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ [Op:" + operationId + "] Error acquiring lock for: " + resourceId, e);
            return false;
        }
    }

    /**
     * 🔓 LIBERAR LOCK DISTRIBUIDO
     */
    public void releaseDistributedLock(String resourceId) {
        try {
            DistributedLock lock = activeLocks.remove(resourceId);
            if (lock != null) {
                long operationId = lock.getOperationId();
                LOGGER.info("🔓 [Op:" + operationId + "] Releasing distributed lock for: " + resourceId);

                // Notificar liberación a todos los servidores
                notifyLockReleased(resourceId, operationId);
                LOGGER.info("✅ [Op:" + operationId + "] Distributed lock released for: " + resourceId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Error releasing distributed lock for: " + resourceId, e);
        }
    }

    /**
     * 🗳️ SOLICITAR CONSENSO CON TIMEOUT
     */
    private boolean requestConsensusWithTimeout(DistributedLock lock) {
        if (otherServers.isEmpty()) {
            LOGGER.info("✅ No other servers, consensus automatic");
            return true;
        }

        int totalServers = otherServers.size();
        CountDownLatch consensusLatch = new CountDownLatch(totalServers);
        AtomicInteger approvals = new AtomicInteger(0);
        AtomicInteger denials = new AtomicInteger(0);

        // Enviar solicitudes en paralelo
        for (ServerConnection server : otherServers) {
            lockExecutor.submit(() -> {
                try {
                    LockRequestResult result = requestLockFromServer(server, lock);
                    if (result == LockRequestResult.GRANTED) {
                        approvals.incrementAndGet();
                    } else {
                        denials.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error requesting lock from: " + server.getServerId(), e);
                    denials.incrementAndGet();
                } finally {
                    consensusLatch.countDown();
                }
            });
        }

        try {
            // Esperar respuestas con timeout configurado
            boolean allResponsesReceived = consensusLatch.await(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);

            if (!allResponsesReceived) {
                LOGGER.warning("⏰ Lock consensus timeout for: " + lock.getResourceId());
                return false;
            }

            // Evaluar consenso
            boolean consensusAchieved = evaluateConsensus(approvals.get(), denials.get(), totalServers);

            LOGGER.info("🗳️ Consensus result: " + approvals.get() + " approvals, " +
                    denials.get() + " denials (" + totalServers + " total) - " +
                    (consensusAchieved ? "GRANTED" : "DENIED"));

            return consensusAchieved;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Consensus interrupted for: " + lock.getResourceId());
            return false;
        }
    }

    /**
     * 📊 EVALUAR CONSENSO
     */
    private boolean evaluateConsensus(int approvals, int denials, int totalServers) {
        if (config.requireUnanimousConsensus()) {
            // Unanimidad requerida
            return approvals == totalServers && denials == 0;
        } else {
            // Mayoría simple
            return approvals > totalServers / 2;
        }
    }

    /**
     * 📨 SOLICITAR LOCK A SERVIDOR ESPECÍFICO
     */
    private LockRequestResult requestLockFromServer(ServerConnection server, DistributedLock lock) {
        try {
            ProtocolMessage lockRequest = new ProtocolMessage(
                    ProtocolCommand.LOCK_REQUEST,
                    lock.getResourceId(),
                    serializeLockRequest(lock)
            );

            ProtocolMessage response = server.sendMessageAndWaitResponse(
                    lockRequest, config.getConnectionTimeoutMs());

            if (response == null) {
                return LockRequestResult.TIMEOUT;
            }

            switch (response.getCommand()) {
                case LOCK_GRANTED:
                    return LockRequestResult.GRANTED;
                case LOCK_DENIED:
                    return LockRequestResult.DENIED;
                default:
                    return LockRequestResult.ERROR;
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error requesting lock from server: " + server.getServerId(), e);
            return LockRequestResult.ERROR;
        }
    }

    /**
     * 📢 NOTIFICAR LIBERACIÓN DE LOCK
     */
    private void notifyLockReleased(String resourceId, long operationId) {
        ProtocolMessage releaseMessage = new ProtocolMessage(
                ProtocolCommand.LOCK_RELEASED,
                resourceId,
                serverId + "|" + operationId
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
        DistributedLock lockRequest = deserializeLockRequest(request.getContent());

        LOGGER.info("📥 Lock request from " + lockRequest.getOwnerId() + " for: " + resourceId +
                " [Op:" + lockRequest.getOperationId() + "]");

        localLock.lock();
        try {
            // Verificar si el recurso está lockeado
            if (activeLocks.containsKey(resourceId)) {
                DistributedLock existingLock = activeLocks.get(resourceId);
                LOGGER.info("❌ Lock denied - resource locked by: " + existingLock.getOwnerId() +
                        " [Op:" + existingLock.getOperationId() + "]");
                return new ProtocolMessage(ProtocolCommand.LOCK_DENIED, resourceId,
                        "Resource locked by " + existingLock.getOwnerId());
            }

            // Verificar si hay solicitudes pendientes con mayor prioridad
            if (hasPriorityConflict(resourceId, lockRequest)) {
                LOGGER.info("❌ Lock denied - priority conflict for: " + resourceId);
                return new ProtocolMessage(ProtocolCommand.LOCK_DENIED, resourceId,
                        "Priority conflict");
            }

            // Otorgar el lock
            LOGGER.info("✅ Lock granted to " + lockRequest.getOwnerId() + " for: " + resourceId +
                    " [Op:" + lockRequest.getOperationId() + "]");
            return new ProtocolMessage(ProtocolCommand.LOCK_GRANTED, resourceId, serverId);

        } finally {
            localLock.unlock();
        }
    }

    /**
     * 📥 PROCESAR LIBERACIÓN DE LOCK
     */
    public void processLockRelease(ProtocolMessage releaseMessage) {
        String resourceId = releaseMessage.getFileName();
        String[] parts = releaseMessage.getContent().split("\\|");
        String releasingServer = parts[0];
        long operationId = parts.length > 1 ? Long.parseLong(parts[1]) : 0;

        LOGGER.info("📥 Lock released by " + releasingServer + " for: " + resourceId +
                " [Op:" + operationId + "]");

        // Remover de solicitudes pendientes si existe
        pendingRequests.remove(resourceId);

        // Procesar cola de solicitudes pendientes
        processQueuedRequests(resourceId);
    }

    /**
     * 🔍 VERIFICAR SI RECURSO ESTÁ LOCKEADO LOCALMENTE
     */
    private boolean isResourceLocallyLocked(String resourceId) {
        return activeLocks.containsKey(resourceId);
    }

    /**
     * ⚖️ VERIFICAR CONFLICTOS DE PRIORIDAD
     */
    private boolean hasPriorityConflict(String resourceId, DistributedLock incomingRequest) {
        LockRequestQueue queue = pendingRequests.get(resourceId);
        if (queue == null) {
            return false;
        }

        // Verificar si hay solicitudes con timestamp anterior (mayor prioridad)
        return queue.hasHigherPriorityRequest(incomingRequest.getTimestamp());
    }

    /**
     * 📋 PROCESAR SOLICITUDES EN COLA
     */
    private void processQueuedRequests(String resourceId) {
        LockRequestQueue queue = pendingRequests.get(resourceId);
        if (queue != null && !queue.isEmpty()) {
            LOGGER.info("📋 Processing queued requests for: " + resourceId);
            // Implementar lógica de procesamiento de cola si es necesario
        }
    }

    /**
     * 🧹 LIMPIAR LOCKS EXPIRADOS
     */
    private void cleanupExpiredLocks() {
        long currentTime = System.currentTimeMillis();
        long lockTimeoutMs = config.getLockTimeoutMs();

        Iterator<Map.Entry<String, DistributedLock>> iterator = activeLocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DistributedLock> entry = iterator.next();
            DistributedLock lock = entry.getValue();

            if ((currentTime - lock.getAcquisitionTime()) > lockTimeoutMs) {
                LOGGER.warning("🧹 Cleaning up expired lock: " + entry.getKey() +
                        " [Op:" + lock.getOperationId() + "]");
                iterator.remove();
                notifyLockReleased(entry.getKey(), lock.getOperationId());
            }
        }
    }

    /**
     * 🔗 VERIFICAR CONEXIONES DE SERVIDORES
     */
    private void verifyServerConnections() {
        LOGGER.fine("🔗 Verifying " + otherServers.size() + " server connections");

        otherServers.removeIf(server -> {
            if (!server.isHealthy()) {
                LOGGER.warning("🔗 Removing unhealthy server: " + server.getServerId());
                return true;
            }
            return false;
        });
    }

    /**
     * 🔗 GESTIÓN DE CONEXIONES DE SERVIDORES
     */
    public void addServerConnection(ServerConnection connection) {
        otherServers.add(connection);
        LOGGER.info("🔗 Added server connection: " + connection.getServerId() +
                " (Total servers: " + otherServers.size() + ")");
    }

    public void removeServerConnection(String serverId) {
        boolean removed = otherServers.removeIf(conn -> conn.getServerId().equals(serverId));
        if (removed) {
            LOGGER.info("🔗 Removed server connection: " + serverId);
        }
    }

    /**
     * 🔍 UTILIDADES
     */
    public boolean hasLock(String resourceId) {
        return activeLocks.containsKey(resourceId);
    }

    public List<String> getActiveLockedResources() {
        return new ArrayList<>(activeLocks.keySet());
    }

    /**
     * 📊 ESTADÍSTICAS
     */
    public Map<String, Object> getLockStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("activeLocksCount", activeLocks.size());
        stats.put("connectedServers", otherServers.size());
        stats.put("pendingRequestsCount", pendingRequests.size());
        stats.put("totalOperations", lockOperationCounter.get());
        stats.put("lockTimeoutMs", config.getLockTimeoutMs());
        return stats;
    }

    /**
     * 📝 SERIALIZACIÓN DE SOLICITUDES
     */
    private String serializeLockRequest(DistributedLock lock) {
        return String.format("%s|%s|%s|%d|%d",
                lock.getOwnerId(),
                lock.getOperationType(),
                lock.getResourceId(),
                lock.getOperationId(),
                lock.getTimestamp());
    }

    private DistributedLock deserializeLockRequest(String serialized) {
        String[] parts = serialized.split("\\|");
        return new DistributedLock(
                parts[2], // resourceId
                parts[0], // ownerId
                parts[1], // operationType
                Long.parseLong(parts[3]), // operationId
                Long.parseLong(parts[4])  // timestamp
        );
    }

    /**
     * 🛑 SHUTDOWN
     */
    public void shutdown() {
        LOGGER.info("🛑 Shutting down DistributedLockManager");

        // Liberar todos los locks activos
        for (String resourceId : new ArrayList<>(activeLocks.keySet())) {
            releaseDistributedLock(resourceId);
        }

        // Shutdown executors
        if (lockExecutor != null) {
            lockExecutor.shutdown();
        }
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdown();
        }
    }

    /**
     * 🔒 CLASE INTERNA: DistributedLock
     */
    public static class DistributedLock {
        private final String resourceId;
        private final String ownerId;
        private final String operationType;
        private final long operationId;
        private final long acquisitionTime;
        private final long timestamp;

        public DistributedLock(String resourceId, String ownerId, String operationType, long operationId) {
            this(resourceId, ownerId, operationType, operationId, System.currentTimeMillis());
        }

        public DistributedLock(String resourceId, String ownerId, String operationType,
                               long operationId, long timestamp) {
            this.resourceId = resourceId;
            this.ownerId = ownerId;
            this.operationType = operationType;
            this.operationId = operationId;
            this.timestamp = timestamp;
            this.acquisitionTime = System.currentTimeMillis();
        }

        public String getResourceId() { return resourceId; }
        public String getOwnerId() { return ownerId; }
        public String getOperationType() { return operationType; }
        public long getOperationId() { return operationId; }
        public long getAcquisitionTime() { return acquisitionTime; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 📋 CLASE INTERNA: LockRequestQueue
     */
    private static class LockRequestQueue {
        private final PriorityQueue<DistributedLock> requests;

        public LockRequestQueue() {
            this.requests = new PriorityQueue<>(Comparator.comparing(DistributedLock::getTimestamp));
        }

        public void addRequest(DistributedLock lock) {
            requests.offer(lock);
        }

        public boolean hasHigherPriorityRequest(long timestamp) {
            DistributedLock topRequest = requests.peek();
            return topRequest != null && topRequest.getTimestamp() < timestamp;
        }

        public boolean isEmpty() {
            return requests.isEmpty();
        }
    }

    /**
     * 🎯 ENUM: LockRequestResult
     */
    private enum LockRequestResult {
        GRANTED, DENIED, TIMEOUT, ERROR
    }

    /**
     * 🔗 CLASE INTERNA: ServerConnection (Mejorada)
     */
    public static class ServerConnection {
        private final String serverId;
        private final String host;
        private final int port;
        private final BackupConnection connection;
        private volatile long lastHealthCheck;
        private volatile boolean healthy;

        public ServerConnection(String serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
            this.connection = new BackupConnection(host, port);
            this.lastHealthCheck = 0;
            this.healthy = false;
        }

        public boolean connect() {
            boolean connected = connection.connect();
            if (connected) {
                healthy = true;
                lastHealthCheck = System.currentTimeMillis();
            }
            return connected;
        }

        public void sendMessage(ProtocolMessage message) {
            connection.sendMessage(message);
        }

        public ProtocolMessage sendMessageAndWaitResponse(ProtocolMessage message, int timeoutMs) {
            return connection.sendMessageAndWaitResponse(message, timeoutMs);
        }

        public boolean isHealthy() {
            // Verificar salud basada en conexión y tiempo transcurrido
            if (!connection.isConnected()) {
                healthy = false;
                return false;
            }

            long timeSinceLastCheck = System.currentTimeMillis() - lastHealthCheck;
            if (timeSinceLastCheck > 300000) { // 5 minutos
                // Realizar health check
                try {
                    ProtocolMessage ping = new ProtocolMessage(ProtocolCommand.HEARTBEAT, null, "ping");
                    ProtocolMessage response = sendMessageAndWaitResponse(ping, 5000);
                    healthy = response != null;
                    lastHealthCheck = System.currentTimeMillis();
                } catch (Exception e) {
                    healthy = false;
                }
            }

            return healthy;
        }

        public String getServerId() { return serverId; }
        public String getHost() { return host; }
        public int getPort() { return port; }

        public void close() {
            connection.close();
            healthy = false;
        }
    }
}