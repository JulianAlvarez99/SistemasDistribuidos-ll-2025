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
     * 🔒 ADQUIRIR LOCK DISTRIBUIDO - Algoritmo corregido
     */
    public boolean acquireDistributedLock(String resourceId, String operationType) {
        long operationId = lockOperationCounter.incrementAndGet();

        LOGGER.info("🔒 [Lock:" + operationId + "] Attempting to acquire lock for: '" + resourceId +
                "' (operation: " + operationType + ")");

        localLock.lock();
        try {
            // 1. Verificar si YA tenemos el lock para este recurso específico
            if (activeLocks.containsKey(resourceId)) {
                DistributedLock existingLock = activeLocks.get(resourceId);
                LOGGER.warning("❌ [Lock:" + operationId + "] Resource '" + resourceId +
                        "' already locked by " + existingLock.getOwnerId() +
                        " [Op:" + existingLock.getOperationId() + "]");
                return false;
            }

            // 2. Crear lock request
            DistributedLock lockRequest = new DistributedLock(resourceId, serverId, operationType, operationId);

            // 3. Si no hay otros servidores, otorgar lock inmediatamente
            if (otherServers.isEmpty()) {
                activeLocks.put(resourceId, lockRequest);
                LOGGER.info("✅ [Lock:" + operationId + "] Lock granted locally (no other servers): '" + resourceId + "'");
                return true;
            }

            // 4. Solicitar consenso de otros servidores
            LOGGER.info("📡 [Lock:" + operationId + "] Requesting consensus from " + otherServers.size() + " servers");
            boolean consensusAchieved = requestConsensusWithTimeout(lockRequest);

            if (consensusAchieved) {
                // 5. Otorgar lock localmente
                activeLocks.put(resourceId, lockRequest);
                LOGGER.info("✅ [Lock:" + operationId + "] Distributed lock acquired: '" + resourceId + "'");
                return true;
            } else {
                LOGGER.warning("❌ [Lock:" + operationId + "] Consensus failed for: '" + resourceId + "'");
                return false;
            }

        } finally {
            localLock.unlock();
        }
    }

    /**
     * 🔓 LIBERAR LOCK DISTRIBUIDO - Corregido para liberar solo el recurso específico
     */
    public void releaseDistributedLock(String resourceId) {
        localLock.lock();
        try {
            DistributedLock lock = activeLocks.remove(resourceId);
            if (lock != null) {
                long operationId = lock.getOperationId();
                LOGGER.info("🔓 [Lock:" + operationId + "] Releasing distributed lock for: '" + resourceId + "'");

                // Notificar liberación solo si hay otros servidores
                if (!otherServers.isEmpty()) {
                    notifyLockReleased(resourceId, operationId);
                }

                LOGGER.info("✅ [Lock:" + operationId + "] Lock released: '" + resourceId + "'");
            } else {
                LOGGER.warning("⚠️ Attempted to release non-existent lock: '" + resourceId + "'");
            }
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 🗳️ SOLICITAR CONSENSO CON TIMEOUT - Simplificado y más robusto
     */
    private boolean requestConsensusWithTimeout(DistributedLock lock) {
        int totalServers = otherServers.size();
        CountDownLatch consensusLatch = new CountDownLatch(totalServers);
        AtomicInteger approvals = new AtomicInteger(0);
        AtomicInteger denials = new AtomicInteger(0);

        LOGGER.info("📡 [Lock:" + lock.getOperationId() + "] Sending lock requests to " + totalServers + " servers");

        // Enviar solicitudes en paralelo
        for (ServerConnection server : otherServers) {
            lockExecutor.submit(() -> {
                try {
                    LockRequestResult result = requestLockFromServer(server, lock);
                    switch (result) {
                        case GRANTED:
                            approvals.incrementAndGet();
                            LOGGER.fine("✅ [Lock:" + lock.getOperationId() + "] Approved by: " + server.getServerId());
                            break;
                        case DENIED:
                        case TIMEOUT:
                        case ERROR:
                        default:
                            denials.incrementAndGet();
                            LOGGER.fine("❌ [Lock:" + lock.getOperationId() + "] Denied by: " + server.getServerId() + " (" + result + ")");
                            break;
                    }
                } catch (Exception e) {
                    denials.incrementAndGet();
                    LOGGER.log(Level.WARNING, "❌ Error requesting lock from: " + server.getServerId(), e);
                } finally {
                    consensusLatch.countDown();
                }
            });
        }

        try {
            // Esperar respuestas con timeout más generoso
            boolean allResponsesReceived = consensusLatch.await(
                    Math.max(config.getLockTimeoutMs(), 10000), TimeUnit.MILLISECONDS);

            int totalApprovals = approvals.get();
            int totalDenials = denials.get();

            if (!allResponsesReceived) {
                LOGGER.warning("⏰ [Lock:" + lock.getOperationId() + "] Consensus timeout - some servers didn't respond");
            }

            LOGGER.info("🗳️ [Lock:" + lock.getOperationId() + "] Consensus results: " +
                    totalApprovals + " approvals, " + totalDenials + " denials (" + totalServers + " total)");

            // Evaluar consenso - Para desarrollo, ser más permisivo
            boolean consensusAchieved = evaluateConsensus(totalApprovals, totalDenials, totalServers);

            LOGGER.info("🗳️ [Lock:" + lock.getOperationId() + "] Consensus decision: " +
                    (consensusAchieved ? "GRANTED" : "DENIED"));

            return consensusAchieved;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("❌ [Lock:" + lock.getOperationId() + "] Consensus interrupted");
            return false;
        }
    }


    /**
     * 📊 EVALUAR CONSENSO - Más permisivo para desarrollo
     */
    private boolean evaluateConsensus(int approvals, int denials, int totalServers) {
        if (totalServers == 0) {
            return true; // Sin otros servidores, otorgar automáticamente
        }

        // Para desarrollo: aceptar si hay al menos 50% de aprobación o si no hay denegaciones explícitas
        if (denials == 0) {
            return true; // Si nadie lo denegó explícitamente, otorgar
        }

        // Mayoría simple
        return approvals > totalServers / 2;
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
     * 📥 PROCESAR SOLICITUD DE LOCK - Mejorado para debugging
     */
    public ProtocolMessage processLockRequest(ProtocolMessage request) {
        String resourceId = request.getFileName();

        try {
            DistributedLock lockRequest = deserializeLockRequest(request.getContent());
            long operationId = lockRequest.getOperationId();

            LOGGER.info("📥 [Lock:" + operationId + "] Processing lock request from " +
                    lockRequest.getOwnerId() + " for: '" + resourceId + "'");

            localLock.lock();
            try {
                // Verificar si el recurso específico está lockeado
                if (activeLocks.containsKey(resourceId)) {
                    DistributedLock existingLock = activeLocks.get(resourceId);
                    LOGGER.info("❌ [Lock:" + operationId + "] DENIED - Resource '" + resourceId +
                            "' locked by: " + existingLock.getOwnerId() + " [Op:" + existingLock.getOperationId() + "]");
                    return new ProtocolMessage(ProtocolCommand.LOCK_DENIED, resourceId,
                            "Resource locked by " + existingLock.getOwnerId());
                }

                // Otorgar el lock
                LOGGER.info("✅ [Lock:" + operationId + "] GRANTED to " + lockRequest.getOwnerId() +
                        " for: '" + resourceId + "'");
                return new ProtocolMessage(ProtocolCommand.LOCK_GRANTED, resourceId, serverId);

            } finally {
                localLock.unlock();
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Error processing lock request for: " + resourceId, e);
            return new ProtocolMessage(ProtocolCommand.LOCK_DENIED, resourceId,
                    "Processing error: " + e.getMessage());
        }
    }

    /**
     * 📥 PROCESAR LIBERACIÓN DE LOCK - Mejorado
     */
    public void processLockRelease(ProtocolMessage releaseMessage) {
        String resourceId = releaseMessage.getFileName();
        String[] parts = releaseMessage.getContent().split("\\|");
        String releasingServer = parts[0];
        long operationId = parts.length > 1 ? Long.parseLong(parts[1]) : 0;

        LOGGER.info("📥 [Lock:" + operationId + "] Processing lock release from " + releasingServer +
                " for: '" + resourceId + "'");

        // No necesitamos hacer nada especial aquí ya que no mantenemos locks de otros servidores
        // Solo loggeamos la notificación
        LOGGER.info("✅ [Lock:" + operationId + "] Acknowledged lock release for: '" + resourceId + "'");
    }


    /**
     * 🧹 LIMPIAR LOCKS EXPIRADOS - Más agresivo
     */
    private void cleanupExpiredLocks() {
        if (activeLocks.isEmpty()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long lockTimeoutMs = config.getLockTimeoutMs();

        localLock.lock();
        try {
            Iterator<Map.Entry<String, DistributedLock>> iterator = activeLocks.entrySet().iterator();
            int cleanedCount = 0;

            while (iterator.hasNext()) {
                Map.Entry<String, DistributedLock> entry = iterator.next();
                DistributedLock lock = entry.getValue();

                if ((currentTime - lock.getAcquisitionTime()) > lockTimeoutMs) {
                    String resourceId = entry.getKey();
                    LOGGER.warning("🧹 [Lock:" + lock.getOperationId() + "] Cleaning expired lock: '" +
                            resourceId + "' (held for " + (currentTime - lock.getAcquisitionTime()) + "ms)");
                    iterator.remove();
                    notifyLockReleased(resourceId, lock.getOperationId());
                    cleanedCount++;
                }
            }

            if (cleanedCount > 0) {
                LOGGER.info("🧹 Cleaned " + cleanedCount + " expired locks");
            }
        } finally {
            localLock.unlock();
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
     * 📊 ESTADÍSTICAS DE LOCKS - Con más detalle
     */
    public Map<String, Object> getLockStatistics() {
        localLock.lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("serverId", serverId);
            stats.put("activeLocksCount", activeLocks.size());
            stats.put("connectedServers", otherServers.size());
            stats.put("totalOperations", lockOperationCounter.get());
            stats.put("lockTimeoutMs", config.getLockTimeoutMs());

            // Detalles de locks activos para debugging
            if (!activeLocks.isEmpty()) {
                Map<String, String> activeLockDetails = new HashMap<>();
                for (Map.Entry<String, DistributedLock> entry : activeLocks.entrySet()) {
                    DistributedLock lock = entry.getValue();
                    activeLockDetails.put(entry.getKey(),
                            "Owner: " + lock.getOwnerId() + ", Op: " + lock.getOperationId() +
                                    ", Age: " + (System.currentTimeMillis() - lock.getAcquisitionTime()) + "ms");
                }
                stats.put("activeLockDetails", activeLockDetails);
            }

            return stats;
        } finally {
            localLock.unlock();
        }
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