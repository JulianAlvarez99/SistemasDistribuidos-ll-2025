import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔄 ENHANCED ACTIVE REPLICATION MANAGER - VERSIÓN LIMPIA
 * Solo métodos que realmente se usan en el flujo simplificado
 */
public class ActiveReplicationManager {
    private static final Logger LOGGER = Logger.getLogger(ActiveReplicationManager.class.getName());

    private final String serverId;
    private final SystemConfig config;
    private final List<DistributedLockManager.ServerConnection> replicaServers;
    private final ExecutorService syncExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final AtomicLong operationCounter;
    private final ReplicationMetrics metrics;

    public ActiveReplicationManager(String serverId) {
        this.serverId = serverId;
        this.config = SystemConfig.getInstance();
        this.replicaServers = new CopyOnWriteArrayList<>();
        this.syncExecutor = Executors.newCachedThreadPool();
        this.maintenanceExecutor = Executors.newScheduledThreadPool(1);
        this.operationCounter = new AtomicLong(0);
        this.metrics = new ReplicationMetrics();

        LOGGER.info("🔄 ActiveReplicationManager initialized for: " + serverId);
    }

    /**
     * 🔄 SINCRONIZAR OPERACIÓN - MÉTODO PRINCIPAL
     */
    public boolean synchronizeOperation(String fileName, ProtocolCommand operation, String content) {
        long operationId = operationCounter.incrementAndGet();

        LOGGER.info("🔄 [SYNC:" + operationId + "] Starting synchronization: " + operation +
                " on '" + fileName + "' with " + replicaServers.size() + " replicas");

        if (replicaServers.isEmpty()) {
            LOGGER.info("✅ [SYNC:" + operationId + "] No replicas - local operation only");
            metrics.recordSuccessfulSync();
            return true;
        }

        try {
            return performDirectSynchronization(fileName, operation, content, operationId);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ [SYNC:" + operationId + "] Synchronization error", e);
            metrics.recordFailedSync();
            return false;
        }
    }

    /**
     * 📤 SINCRONIZACIÓN DIRECTA OPTIMIZADA - Versión rápida y robusta
     */
    private boolean performDirectSynchronization(String fileName, ProtocolCommand operation,
                                                 String content, long operationId) {

        LOGGER.info("📤 [SYNC:" + operationId + "] Fast sync to " + replicaServers.size() + " replicas");

        // Timeout más corto pero realista
        int syncTimeoutMs = Math.min(config.getSyncTimeoutMs(), 15000); // Max 15 segundos

        CountDownLatch syncLatch = new CountDownLatch(replicaServers.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Preparar mensaje una sola vez
        String commitContent = prepareCommitContent(operation, content);
        ProtocolMessage commitMessage = new ProtocolMessage(
                ProtocolCommand.OPERATION_COMMIT,
                fileName,
                commitContent
        );

        long startTime = System.currentTimeMillis();

        // Enviar a todas las réplicas INMEDIATAMENTE en paralelo
        for (DistributedLockManager.ServerConnection replica : replicaServers) {
            syncExecutor.submit(() -> {
                long threadStartTime = System.currentTimeMillis();
                boolean success = false;

                try {
                    LOGGER.fine("📤 [SYNC:" + operationId + "] → " + replica.getServerId());

                    // Usar timeout individual más corto
                    ProtocolMessage response = replica.sendMessageAndWaitResponse(
                            commitMessage, syncTimeoutMs / 2); // La mitad del tiempo total

                    if (response != null) {
                        if (response.getCommand() == ProtocolCommand.OPERATION_COMMITTED) {
                            success = true;
                            successCount.incrementAndGet();
                            long elapsed = System.currentTimeMillis() - threadStartTime;
                            LOGGER.info("✅ [SYNC:" + operationId + "] ← " + replica.getServerId() +
                                    " SUCCESS (" + elapsed + "ms)");
                        } else {
                            failureCount.incrementAndGet();
                            LOGGER.warning("❌ [SYNC:" + operationId + "] ← " + replica.getServerId() +
                                    " REJECTED: " + response.getContent());
                        }
                    } else {
                        failureCount.incrementAndGet();
                        LOGGER.warning("❌ [SYNC:" + operationId + "] ← " + replica.getServerId() + " TIMEOUT");
                    }

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    long elapsed = System.currentTimeMillis() - threadStartTime;
                    LOGGER.warning("❌ [SYNC:" + operationId + "] ← " + replica.getServerId() +
                            " ERROR (" + elapsed + "ms): " + e.getMessage());
                } finally {
                    syncLatch.countDown();
                }
            });
        }

        try {
            // Esperar con timeout global más generoso
            boolean allCompleted = syncLatch.await(syncTimeoutMs + 3000, TimeUnit.MILLISECONDS);

            int successful = successCount.get();
            int failed = failureCount.get();
            int total = replicaServers.size();
            long totalTime = System.currentTimeMillis() - startTime;

            LOGGER.info("📊 [SYNC:" + operationId + "] Results in " + totalTime + "ms: " +
                    successful + "/" + total + " successful" +
                    (failed > 0 ? " (" + failed + " failed)" : "") +
                    (allCompleted ? "" : " [SOME TIMED OUT]"));

            // Criterio de éxito más flexible para desarrollo
            boolean success;
            if (total == 0) {
                success = true; // No hay réplicas
            } else if (total == 1) {
                success = successful >= 1; // Con una réplica, debe tener éxito
            } else {
                success = successful > 0; // Con múltiples réplicas, al menos una debe tener éxito
            }

            if (success) {
                metrics.recordSuccessfulSync();
                LOGGER.info("✅ [SYNC:" + operationId + "] Synchronization completed successfully");
            } else {
                metrics.recordFailedSync();
                LOGGER.warning("❌ [SYNC:" + operationId + "] Synchronization failed completely");
            }

            return success;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("❌ [SYNC:" + operationId + "] Synchronization interrupted");
            metrics.recordFailedSync();
            return false;
        }
    }

    /**
     * 📝 PREPARAR CONTENIDO DEL COMMIT
     */
    private String prepareCommitContent(ProtocolCommand operation, String content) {
        switch (operation) {
            case WRITE:
                return content != null ? content : "";
            case DELETE:
                return "DELETE:";
            default:
                return content != null ? content : "";
        }
    }

    /**
     * 🔗 GESTIÓN DE SERVIDORES RÉPLICA
     */
    public void addReplicaServer(DistributedLockManager.ServerConnection server) {
        replicaServers.add(server);
        LOGGER.info("🔗 Added replica server: " + server.getServerId() + " (Total: " + replicaServers.size() + ")");
    }

    public void removeReplicaServer(String serverId) {
        boolean removed = replicaServers.removeIf(server -> server.getServerId().equals(serverId));
        if (removed) {
            LOGGER.info("🔗 Removed replica server: " + serverId + " (Total: " + replicaServers.size() + ")");
        }
    }

    /**
     * 📊 ESTADÍSTICAS DE REPLICACIÓN
     */
    public Map<String, Object> getReplicationStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("replicaCount", replicaServers.size());
        stats.put("lastOperationId", operationCounter.get());
        stats.putAll(metrics.getMetricsMap());
        return stats;
    }

    /**
     * 🛑 SHUTDOWN
     */
    public void shutdown() {
        LOGGER.info("🛑 Shutting down ActiveReplicationManager");

        if (syncExecutor != null) {
            syncExecutor.shutdown();
            try {
                if (!syncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    syncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                syncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdown();
        }
    }

    /**
     * 📊 CLASE INTERNA: ReplicationMetrics
     */
    private static class ReplicationMetrics {
        private final AtomicLong totalSyncs = new AtomicLong(0);
        private final AtomicLong successfulSyncs = new AtomicLong(0);
        private final AtomicLong failedSyncs = new AtomicLong(0);

        public void recordSuccessfulSync() {
            totalSyncs.incrementAndGet();
            successfulSyncs.incrementAndGet();
        }

        public void recordFailedSync() {
            totalSyncs.incrementAndGet();
            failedSyncs.incrementAndGet();
        }

        public Map<String, Object> getMetricsMap() {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("totalSyncs", totalSyncs.get());
            metrics.put("successfulSyncs", successfulSyncs.get());
            metrics.put("failedSyncs", failedSyncs.get());
            return metrics;
        }
    }
}