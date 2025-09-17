import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 🔄 ENHANCED ACTIVE REPLICATION MANAGER
 * Maneja sincronización activa con algoritmo optimizado y configuración centralizada
 */
public class ActiveReplicationManager {
    private static final Logger LOGGER = Logger.getLogger(ActiveReplicationManager.class.getName());

    private final String serverId;
    private final SystemConfig config;
    private final List<DistributedLockManager.ServerConnection> replicaServers;
    private final ExecutorService syncExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final AtomicLong operationCounter;
    private final Map<String, OperationHistory> operationHistory;
    private final Map<String, CompletableFuture<Boolean>> pendingSyncs;
    private final ReplicationMetrics metrics;

    public ActiveReplicationManager(String serverId) {
        this.serverId = serverId;
        this.config = SystemConfig.getInstance();
        this.replicaServers = new CopyOnWriteArrayList<>();
        this.syncExecutor = Executors.newCachedThreadPool();
        this.maintenanceExecutor = Executors.newScheduledThreadPool(1);
        this.operationCounter = new AtomicLong(0);
        this.operationHistory = new ConcurrentHashMap<>();
        this.pendingSyncs = new ConcurrentHashMap<>();
        this.metrics = new ReplicationMetrics();

        startMaintenanceServices();
    }

    /**
     * 🔧 SERVICIOS DE MANTENIMIENTO
     */
    private void startMaintenanceServices() {
        // Limpieza de historial cada 5 minutos
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupOldOperations();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during operation cleanup", e);
            }
        }, config.getCleanupIntervalSec(), config.getCleanupIntervalSec(), TimeUnit.SECONDS);

        // Verificación de sincronizaciones pendientes cada 2 minutos
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                checkPendingSynchronizations();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error checking pending syncs", e);
            }
        }, 120, 120, TimeUnit.SECONDS);
    }

    /**
     * 🔄 SINCRONIZAR OPERACIÓN - Funcion principal para redundancia activa
     */
    public boolean synchronizeOperation(String fileName, ProtocolCommand operation, String content) {
        long operationId = operationCounter.incrementAndGet();
        String operationKey = serverId + "_" + operationId;

        LOGGER.info("🔄 [Op:" + operationId + "] Starting synchronization: " + operation +
                " on '" + fileName + "' with " + replicaServers.size() + " replicas");

        // Crear registro de operación
        OperationHistory opHistory = new OperationHistory(
                operationKey, fileName, operation, content, serverId, System.currentTimeMillis()
        );
        operationHistory.put(operationKey, opHistory);

        if (replicaServers.isEmpty()) {
            LOGGER.info("✅ [Op:" + operationId + "] No replicas to sync - operation completed locally");
            metrics.recordSuccessfulSync();
            return true;
        }

        try {
            // Ejecutar algoritmo de replicación activa
            boolean success = performActiveReplication(opHistory);

            if (success) {
                metrics.recordSuccessfulSync();
            } else {
                metrics.recordFailedSync();
            }

            return success;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ [Op:" + operationId + "] Error during synchronization", e);
            metrics.recordFailedSync();
            return false;
        }
    }

    /**
     * 🔄 EJECUTAR REPLICACIÓN ACTIVA - Algoritmo en 3 fases
     */
    private boolean performActiveReplication(OperationHistory operation) {
        long operationId = extractOperationId(operation.getOperationKey());

        try {
            LOGGER.info("📢 [Op:" + operationId + "] Phase 1: Broadcasting operation proposal");

            // FASE 1: Propuesta y validación
            Map<String, CompletableFuture<ProposalResult>> proposalFutures = sendProposalsToAllReplicas(operation);

            // FASE 2: Evaluación de consenso
            ConsensusResult consensus = evaluateProposalConsensus(proposalFutures, operationId);

            if (consensus.isSuccessful()) {
                // FASE 3: Confirmación y aplicación
                return commitOperationToAllReplicas(operation, consensus.getSuccessfulReplicas());
            } else {
                // FASE 3: Abortar operación
                abortOperationOnAllReplicas(operation, consensus.getFailedReplicas());
                return false;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ [Op:" + operationId + "] Active replication failed", e);
            return false;
        }
    }

    /**
     * 💭 ENVIAR PROPUESTAS A TODAS LAS RÉPLICAS
     */
    private Map<String, CompletableFuture<ProposalResult>> sendProposalsToAllReplicas(OperationHistory operation) {
        Map<String, CompletableFuture<ProposalResult>> futures = new ConcurrentHashMap<>();

        for (DistributedLockManager.ServerConnection replica : replicaServers) {
            CompletableFuture<ProposalResult> future = CompletableFuture.supplyAsync(() ->
                    sendProposalToReplica(replica, operation), syncExecutor);
            futures.put(replica.getServerId(), future);
        }

        return futures;
    }

    /**
     * 💭 ENVIAR PROPUESTA A UNA RÉPLICA
     */
    private ProposalResult sendProposalToReplica(DistributedLockManager.ServerConnection replica,
                                                 OperationHistory operation) {
        try {
            ProtocolMessage proposal = new ProtocolMessage(
                    ProtocolCommand.OPERATION_PROPOSAL,
                    operation.getFileName(),
                    serializeOperation(operation)
            );

            ProtocolMessage response = replica.sendMessageAndWaitResponse(
                    proposal, config.getSyncTimeoutMs());

            if (response == null) {
                return new ProposalResult(replica.getServerId(), false, "Timeout");
            }

            boolean accepted = response.getCommand() == ProtocolCommand.OPERATION_ACCEPTED;
            String message = accepted ? "Accepted" : response.getContent();

            return new ProposalResult(replica.getServerId(), accepted, message);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error sending proposal to: " + replica.getServerId(), e);
            return new ProposalResult(replica.getServerId(), false, "Communication error: " + e.getMessage());
        }
    }

    /**
     * 🗳️ EVALUAR CONSENSO DE PROPUESTAS
     */
    private ConsensusResult evaluateProposalConsensus(
            Map<String, CompletableFuture<ProposalResult>> proposalFutures, long operationId) {

        List<String> successfulReplicas = new ArrayList<>();
        List<String> failedReplicas = new ArrayList<>();
        int totalReplicas = proposalFutures.size();

        // Esperar todas las respuestas con timeout
        for (Map.Entry<String, CompletableFuture<ProposalResult>> entry : proposalFutures.entrySet()) {
            try {
                ProposalResult result = entry.getValue().get(config.getSyncTimeoutMs(), TimeUnit.MILLISECONDS);

                if (result.isAccepted()) {
                    successfulReplicas.add(result.getReplicaId());
                } else {
                    failedReplicas.add(result.getReplicaId());
                    LOGGER.warning("❌ [Op:" + operationId + "] Proposal rejected by " +
                            result.getReplicaId() + ": " + result.getMessage());
                }

            } catch (Exception e) {
                String replicaId = entry.getKey();
                failedReplicas.add(replicaId);
                LOGGER.log(Level.WARNING, "❌ [Op:" + operationId + "] Proposal error from: " + replicaId, e);
            }
        }

        // Evaluar si se puede proceder
        boolean canProceed = evaluateConsensusDecision(successfulReplicas.size(), totalReplicas);

        LOGGER.info("🗳️ [Op:" + operationId + "] Consensus evaluation: " +
                successfulReplicas.size() + "/" + totalReplicas + " accepted - " +
                (canProceed ? "PROCEED" : "ABORT"));

        return new ConsensusResult(canProceed, successfulReplicas, failedReplicas);
    }

    /**
     * 🎯 EVALUAR DECISIÓN DE CONSENSO
     */
    private boolean evaluateConsensusDecision(int acceptances, int totalReplicas) {
        if (config.requireUnanimousConsensus()) {
            return acceptances == totalReplicas;
        } else {
            return acceptances > totalReplicas / 2;
        }
    }

    /**
     * ✅ CONFIRMAR OPERACIÓN EN TODAS LAS RÉPLICAS
     */
    private boolean commitOperationToAllReplicas(OperationHistory operation, List<String> targetReplicas) {
        long operationId = extractOperationId(operation.getOperationKey());
        LOGGER.info("✅ [Op:" + operationId + "] Committing operation to " + targetReplicas.size() + " replicas");

        CountDownLatch commitLatch = new CountDownLatch(targetReplicas.size());
        AtomicInteger successfulCommits = new AtomicInteger(0);

        for (String replicaId : targetReplicas) {
            DistributedLockManager.ServerConnection replica = findReplicaById(replicaId);
            if (replica != null) {
                syncExecutor.submit(() -> {
                    try {
                        if (commitOperationToReplica(replica, operation)) {
                            successfulCommits.incrementAndGet();
                        }
                    } finally {
                        commitLatch.countDown();
                    }
                });
            } else {
                commitLatch.countDown();
            }
        }

        try {
            boolean allCompleted = commitLatch.await(config.getSyncTimeoutMs(), TimeUnit.MILLISECONDS);
            int commits = successfulCommits.get();

            if (!allCompleted) {
                LOGGER.warning("⏰ [Op:" + operationId + "] Commit phase timeout");
            }

            LOGGER.info("✅ [Op:" + operationId + "] Commit results: " + commits + "/" +
                    targetReplicas.size() + " successful");

            // Para redundancia activa, requerimos todos los commits exitosos
            return commits == targetReplicas.size();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("❌ [Op:" + operationId + "] Commit phase interrupted");
            return false;
        }
    }

    /**
     * ✅ CONFIRMAR OPERACIÓN EN UNA RÉPLICA
     */
    private boolean commitOperationToReplica(DistributedLockManager.ServerConnection replica,
                                             OperationHistory operation) {
        try {
            ProtocolMessage commit = new ProtocolMessage(
                    ProtocolCommand.OPERATION_COMMIT,
                    operation.getFileName(),
                    serializeOperation(operation)
            );

            ProtocolMessage response = replica.sendMessageAndWaitResponse(
                    commit, config.getSyncTimeoutMs());

            boolean success = response != null &&
                    response.getCommand() == ProtocolCommand.OPERATION_COMMITTED;

            if (!success && response != null) {
                LOGGER.warning("❌ Commit failed on " + replica.getServerId() + ": " + response.getContent());
            }

            return success;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Error committing to replica: " + replica.getServerId(), e);
            return false;
        }
    }

    /**
     * ❌ ABORTAR OPERACIÓN EN TODAS LAS RÉPLICAS
     */
    private void abortOperationOnAllReplicas(OperationHistory operation, List<String> targetReplicas) {
        long operationId = extractOperationId(operation.getOperationKey());
        LOGGER.warning("❌ [Op:" + operationId + "] Aborting operation on " + targetReplicas.size() + " replicas");

        ProtocolMessage abort = new ProtocolMessage(
                ProtocolCommand.OPERATION_ABORT,
                operation.getFileName(),
                operation.getOperationKey()
        );

        for (String replicaId : targetReplicas) {
            DistributedLockManager.ServerConnection replica = findReplicaById(replicaId);
            if (replica != null) {
                syncExecutor.submit(() -> {
                    try {
                        replica.sendMessage(abort);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error sending abort to: " + replicaId, e);
                    }
                });
            }
        }
    }

    /**
     * 📥 PROCESAR PROPUESTA DE OPERACIÓN DE OTRA RÉPLICA
     */
    public ProtocolMessage processOperationProposal(ProtocolMessage proposal) {
        try {
            OperationHistory operation = deserializeOperation(proposal.getContent());
            long operationId = extractOperationId(operation.getOperationKey());

            LOGGER.info("📥 [Op:" + operationId + "] Processing operation proposal from " +
                    operation.getOriginServer() + " for: " + operation.getFileName());

            // Validar la propuesta
            ValidationResult validation = validateOperationProposal(operation);

            if (validation.isValid()) {
                // Registrar operación como pendiente
                operationHistory.put(operation.getOperationKey(), operation);

                LOGGER.info("✅ [Op:" + operationId + "] Operation proposal accepted");
                return new ProtocolMessage(ProtocolCommand.OPERATION_ACCEPTED,
                        proposal.getFileName(), serverId);
            } else {
                LOGGER.warning("❌ [Op:" + operationId + "] Operation proposal rejected: " +
                        validation.getReason());
                return new ProtocolMessage(ProtocolCommand.OPERATION_REJECTED,
                        proposal.getFileName(), validation.getReason());
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing operation proposal", e);
            return new ProtocolMessage(ProtocolCommand.OPERATION_REJECTED,
                    proposal.getFileName(), "Processing error: " + e.getMessage());
        }
    }

    /**
     * 📥 PROCESAR COMMIT DE OPERACIÓN
     */
    public ProtocolMessage processOperationCommit(ProtocolMessage commit, FileSystemManager fileManager) {
        try {
            OperationHistory operation = deserializeOperation(commit.getContent());
            long operationId = extractOperationId(operation.getOperationKey());

            LOGGER.info("📥 [Op:" + operationId + "] Processing operation commit from " +
                    operation.getOriginServer() + " for: " + operation.getFileName());

            // Ejecutar operación localmente
            OperationResult result = executeOperationLocally(operation, fileManager);

            if (result.isSuccess()) {
                LOGGER.info("✅ [Op:" + operationId + "] Operation committed successfully");
                metrics.recordSuccessfulCommit();
                return new ProtocolMessage(ProtocolCommand.OPERATION_COMMITTED,
                        commit.getFileName(), serverId);
            } else {
                LOGGER.warning("❌ [Op:" + operationId + "] Operation commit failed: " + result.getMessage());
                metrics.recordFailedCommit();
                return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED,
                        commit.getFileName(), result.getMessage());
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing operation commit", e);
            return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED,
                    commit.getFileName(), "Commit processing error: " + e.getMessage());
        }
    }

    /**
     * 🏃 EJECUTAR OPERACIÓN LOCALMENTE
     */
    private OperationResult executeOperationLocally(OperationHistory operation, FileSystemManager fileManager) {
        switch (operation.getOperation()) {
            case WRITE:
                return fileManager.replaceFileContent(operation.getFileName(), operation.getContent());
            case DELETE:
                return fileManager.deleteFile(operation.getFileName());
            default:
                return new OperationResult(false, "Unsupported operation for sync: " + operation.getOperation());
        }
    }

    /**
     * ✅ VALIDAR PROPUESTA DE OPERACIÓN
     */
    private ValidationResult validateOperationProposal(OperationHistory operation) {
        // Validación básica de datos
        if (operation.getFileName() == null || operation.getFileName().trim().isEmpty()) {
            return new ValidationResult(false, "Invalid file name");
        }

        if (operation.getOperation() == null) {
            return new ValidationResult(false, "Invalid operation");
        }

        // Verificar duplicados
        if (operationHistory.containsKey(operation.getOperationKey())) {
            return new ValidationResult(false, "Duplicate operation");
        }

        // Validaciones específicas por tipo de operación
        switch (operation.getOperation()) {
            case WRITE:
                if (operation.getContent() == null) {
                    return new ValidationResult(false, "Write operation requires content");
                }
                break;
            case DELETE:
                // Para DELETE no necesitamos contenido
                break;
            default:
                return new ValidationResult(false, "Unsupported operation type");
        }

        return new ValidationResult(true, "Valid operation");
    }

    /**
     * 🧹 LIMPIAR OPERACIONES ANTIGUAS
     */
    private void cleanupOldOperations() {
        long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 horas

        Iterator<Map.Entry<String, OperationHistory>> iterator = operationHistory.entrySet().iterator();
        int removed = 0;

        while (iterator.hasNext()) {
            Map.Entry<String, OperationHistory> entry = iterator.next();
            if (entry.getValue().getTimestamp() < cutoffTime) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.info("🧹 Cleaned up " + removed + " old operations from history");
        }
    }

    /**
     * ⏰ VERIFICAR SINCRONIZACIONES PENDIENTES
     */
    private void checkPendingSynchronizations() {
        if (pendingSyncs.isEmpty()) {
            return;
        }

        LOGGER.fine("⏰ Checking " + pendingSyncs.size() + " pending synchronizations");

        Iterator<Map.Entry<String, CompletableFuture<Boolean>>> iterator = pendingSyncs.entrySet().iterator();
        int completed = 0;

        while (iterator.hasNext()) {
            Map.Entry<String, CompletableFuture<Boolean>> entry = iterator.next();
            CompletableFuture<Boolean> future = entry.getValue();

            if (future.isDone()) {
                iterator.remove();
                completed++;
                try {
                    boolean success = future.get();
                    LOGGER.fine("⏰ Pending sync completed: " + entry.getKey() + " - " +
                            (success ? "SUCCESS" : "FAILED"));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error getting pending sync result", e);
                }
            }
        }

        if (completed > 0) {
            LOGGER.info("⏰ Completed " + completed + " pending synchronizations");
        }
    }

    /**
     * 🔗 GESTIÓN DE SERVIDORES RÉPLICA
     */
    public void addReplicaServer(DistributedLockManager.ServerConnection server) {
        replicaServers.add(server);
        LOGGER.info("🔗 Added replica server: " + server.getServerId() +
                " (Total replicas: " + replicaServers.size() + ")");
    }

    public void removeReplicaServer(String serverId) {
        boolean removed = replicaServers.removeIf(server -> server.getServerId().equals(serverId));
        if (removed) {
            LOGGER.info("🔗 Removed replica server: " + serverId +
                    " (Total replicas: " + replicaServers.size() + ")");
        }
    }

    /**
     * 🔍 UTILIDADES
     */
    private DistributedLockManager.ServerConnection findReplicaById(String replicaId) {
        return replicaServers.stream()
                .filter(replica -> replica.getServerId().equals(replicaId))
                .findFirst()
                .orElse(null);
    }

    private long extractOperationId(String operationKey) {
        try {
            String[] parts = operationKey.split("_");
            return Long.parseLong(parts[parts.length - 1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 📊 ESTADÍSTICAS DE REPLICACIÓN
     */
    public Map<String, Object> getReplicationStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("replicaCount", replicaServers.size());
        stats.put("operationHistorySize", operationHistory.size());
        stats.put("lastOperationId", operationCounter.get());
        stats.put("pendingSyncs", pendingSyncs.size());
        stats.putAll(metrics.getMetricsMap());
        return stats;
    }

    /**
     * 📝 SERIALIZACIÓN DE OPERACIONES
     */
    private String serializeOperation(OperationHistory operation) {
        return String.format("%s|%s|%s|%s|%s|%d",
                operation.getOperationKey(),
                operation.getFileName(),
                operation.getOperation().getCommand(),
                operation.getContent() != null ? operation.getContent().replace("|", "\\|") : "",
                operation.getOriginServer(),
                operation.getTimestamp());
    }

    private OperationHistory deserializeOperation(String serialized) {
        String[] parts = serialized.split("\\|", 6);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid serialized operation: " + serialized);
        }

        return new OperationHistory(
                parts[0], // operationKey
                parts[1], // fileName
                ProtocolCommand.fromString(parts[2]), // operation
                parts[3].isEmpty() ? null : parts[3].replace("\\|", "|"), // content
                parts[4], // originServer
                Long.parseLong(parts[5]) // timestamp
        );
    }

    /**
     * 🛑 SHUTDOWN
     */
    public void shutdown() {
        LOGGER.info("🛑 Shutting down ActiveReplicationManager");

        // Cancelar sincronizaciones pendientes
        for (CompletableFuture<Boolean> future : pendingSyncs.values()) {
            future.cancel(true);
        }
        pendingSyncs.clear();

        // Shutdown executors
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
        private final AtomicLong totalCommits = new AtomicLong(0);
        private final AtomicLong successfulCommits = new AtomicLong(0);
        private final AtomicLong failedCommits = new AtomicLong(0);

        public void recordSuccessfulSync() {
            totalSyncs.incrementAndGet();
            successfulSyncs.incrementAndGet();
        }

        public void recordFailedSync() {
            totalSyncs.incrementAndGet();
            failedSyncs.incrementAndGet();
        }

        public void recordSuccessfulCommit() {
            totalCommits.incrementAndGet();
            successfulCommits.incrementAndGet();
        }

        public void recordFailedCommit() {
            totalCommits.incrementAndGet();
            failedCommits.incrementAndGet();
        }

        public Map<String, Object> getMetricsMap() {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("totalSyncs", totalSyncs.get());
            metrics.put("successfulSyncs", successfulSyncs.get());
            metrics.put("failedSyncs", failedSyncs.get());
            metrics.put("totalCommits", totalCommits.get());
            metrics.put("successfulCommits", successfulCommits.get());
            metrics.put("failedCommits", failedCommits.get());
            return metrics;
        }
    }

    /**
     * 📝 CLASE INTERNA: OperationHistory
     */
    public static class OperationHistory {
        private final String operationKey;
        private final String fileName;
        private final ProtocolCommand operation;
        private final String content;
        private final String originServer;
        private final long timestamp;

        public OperationHistory(String operationKey, String fileName, ProtocolCommand operation,
                                String content, String originServer, long timestamp) {
            this.operationKey = operationKey;
            this.fileName = fileName;
            this.operation = operation;
            this.content = content;
            this.originServer = originServer;
            this.timestamp = timestamp;
        }

        public String getOperationKey() { return operationKey; }
        public String getFileName() { return fileName; }
        public ProtocolCommand getOperation() { return operation; }
        public String getContent() { return content; }
        public String getOriginServer() { return originServer; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 💭 CLASE INTERNA: ProposalResult
     */
    private static class ProposalResult {
        private final String replicaId;
        private final boolean accepted;
        private final String message;

        public ProposalResult(String replicaId, boolean accepted, String message) {
            this.replicaId = replicaId;
            this.accepted = accepted;
            this.message = message;
        }

        public String getReplicaId() { return replicaId; }
        public boolean isAccepted() { return accepted; }
        public String getMessage() { return message; }
    }

    /**
     * 🗳️ CLASE INTERNA: ConsensusResult
     */
    private static class ConsensusResult {
        private final boolean successful;
        private final List<String> successfulReplicas;
        private final List<String> failedReplicas;

        public ConsensusResult(boolean successful, List<String> successfulReplicas, List<String> failedReplicas) {
            this.successful = successful;
            this.successfulReplicas = new ArrayList<>(successfulReplicas);
            this.failedReplicas = new ArrayList<>(failedReplicas);
        }

        public boolean isSuccessful() { return successful; }
        public List<String> getSuccessfulReplicas() { return successfulReplicas; }
        public List<String> getFailedReplicas() { return failedReplicas; }
    }

    /**
     * ✅ CLASE INTERNA: ValidationResult
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String reason;

        public ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
    }
}