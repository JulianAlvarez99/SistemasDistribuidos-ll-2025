import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 🔄 ACTIVE REPLICATION MANAGER
 * Maneja la sincronización activa entre todos los servidores réplica
 * Implementa total order broadcast para mantener consistencia
 */
public class ActiveReplicationManager {
    private static final Logger LOGGER = Logger.getLogger(ActiveReplicationManager.class.getName());

    private final String serverId;
    private final List<DistributedLockManager.ServerConnection> replicaServers;
    private final ExecutorService syncExecutor;
    private final AtomicInteger operationCounter;
    private final Map<String, OperationHistory> operationHistory;
    private final int syncTimeoutMs;
    private final int maxRetries;

    public ActiveReplicationManager(String serverId) {
        this.serverId = serverId;
        this.replicaServers = new CopyOnWriteArrayList<>();
        this.syncExecutor = Executors.newCachedThreadPool();
        this.operationCounter = new AtomicInteger(0);
        this.operationHistory = new ConcurrentHashMap<>();
        this.syncTimeoutMs = 15000; // 15 segundos
        this.maxRetries = 3;
    }

    /**
     * 🔄 SINCRONIZAR OPERACIÓN CON TODAS LAS RÉPLICAS
     * Este es el método principal para redundancia activa
     */
    public boolean synchronizeOperation(String fileName, ProtocolCommand operation, String content) {
        int operationId = operationCounter.incrementAndGet();
        String operationKey = serverId + "_" + operationId;

        LOGGER.info("🔄 Synchronizing operation: " + operation + " on " + fileName +
                " (OpID: " + operationKey + ") with " + replicaServers.size() + " replicas");

        // Crear registro de operación
        OperationHistory opHistory = new OperationHistory(
                operationKey, fileName, operation, content, serverId, System.currentTimeMillis()
        );
        operationHistory.put(operationKey, opHistory);

        if (replicaServers.isEmpty()) {
            LOGGER.info("✅ No replicas to sync - operation completed locally");
            return true;
        }

        try {
            return performTotalOrderBroadcast(opHistory);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Error during operation synchronization", e);
            return false;
        }
    }

    /**
     * 📢 TOTAL ORDER BROADCAST
     * Garantiza que todas las réplicas ejecuten operaciones en el mismo orden
     */
    private boolean performTotalOrderBroadcast(OperationHistory operation) {
        LOGGER.info("📢 Broadcasting operation: " + operation.getOperationKey());

        // Fase 1: Proponer la operación a todas las réplicas
        Map<String, CompletableFuture<Boolean>> proposalFutures = new HashMap<>();

        for (DistributedLockManager.ServerConnection replica : replicaServers) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() ->
                    proposeOperationToReplica(replica, operation), syncExecutor);
            proposalFutures.put(replica.getServerId(), future);
        }

        // Fase 2: Esperar acknowledgment de todas las réplicas
        int successfulProposals = 0;
        for (Map.Entry<String, CompletableFuture<Boolean>> entry : proposalFutures.entrySet()) {
            try {
                boolean success = entry.getValue().get(syncTimeoutMs, TimeUnit.MILLISECONDS);
                if (success) {
                    successfulProposals++;
                } else {
                    LOGGER.warning("❌ Proposal failed for replica: " + entry.getKey());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "❌ Error getting proposal result from: " + entry.getKey(), e);
            }
        }

        // Fase 3: Decidir si proceder basado en mayoría o unanimidad
        boolean proceedWithOperation = decideProceedBasedOnConsensus(successfulProposals, replicaServers.size());

        if (proceedWithOperation) {
            // Fase 4: Confirmar operación a todas las réplicas
            return commitOperationToAllReplicas(operation);
        } else {
            // Fase 5: Abortar operación
            abortOperationOnAllReplicas(operation);
            return false;
        }
    }

    /**
     * 💭 PROPONER OPERACIÓN A UNA RÉPLICA
     */
    private boolean proposeOperationToReplica(DistributedLockManager.ServerConnection replica, OperationHistory operation) {
        try {
            ProtocolMessage proposal = new ProtocolMessage(
                    ProtocolCommand.OPERATION_PROPOSAL,
                    operation.getFileName(),
                    serializeOperation(operation)
            );

            ProtocolMessage response = replica.sendMessageAndWaitResponse(proposal, syncTimeoutMs);

            return response != null && response.getCommand() == ProtocolCommand.OPERATION_ACCEPTED;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error proposing operation to replica: " + replica.getServerId(), e);
            return false;
        }
    }

    /**
     * ✅ CONFIRMAR OPERACIÓN EN TODAS LAS RÉPLICAS
     */
    private boolean commitOperationToAllReplicas(OperationHistory operation) {
        LOGGER.info("✅ Committing operation to all replicas: " + operation.getOperationKey());

        CountDownLatch commitLatch = new CountDownLatch(replicaServers.size());
        AtomicInteger successfulCommits = new AtomicInteger(0);

        for (DistributedLockManager.ServerConnection replica : replicaServers) {
            syncExecutor.submit(() -> {
                try {
                    if (commitOperationToReplica(replica, operation)) {
                        successfulCommits.incrementAndGet();
                    }
                } finally {
                    commitLatch.countDown();
                }
            });
        }

        try {
            commitLatch.await(syncTimeoutMs, TimeUnit.MILLISECONDS);
            int commits = successfulCommits.get();

            LOGGER.info("Commit results: " + commits + "/" + replicaServers.size() + " successful");

            // Para redundancia activa, necesitamos que todas las réplicas confirmen
            return commits == replicaServers.size();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * ✅ CONFIRMAR OPERACIÓN EN UNA RÉPLICA
     */
    private boolean commitOperationToReplica(DistributedLockManager.ServerConnection replica, OperationHistory operation) {
        try {
            ProtocolMessage commit = new ProtocolMessage(
                    ProtocolCommand.OPERATION_COMMIT,
                    operation.getFileName(),
                    serializeOperation(operation)
            );

            ProtocolMessage response = replica.sendMessageAndWaitResponse(commit, syncTimeoutMs);
            return response != null && response.getCommand() == ProtocolCommand.OPERATION_COMMITTED;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error committing operation to replica: " + replica.getServerId(), e);
            return false;
        }
    }

    /**
     * ❌ ABORTAR OPERACIÓN EN TODAS LAS RÉPLICAS
     */
    private void abortOperationOnAllReplicas(OperationHistory operation) {
        LOGGER.warning("❌ Aborting operation on all replicas: " + operation.getOperationKey());

        ProtocolMessage abort = new ProtocolMessage(
                ProtocolCommand.OPERATION_ABORT,
                operation.getFileName(),
                operation.getOperationKey()
        );

        for (DistributedLockManager.ServerConnection replica : replicaServers) {
            syncExecutor.submit(() -> {
                try {
                    replica.sendMessage(abort);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error sending abort to replica: " + replica.getServerId(), e);
                }
            });
        }
    }

    /**
     * 🗳️ DECIDIR SI PROCEDER BASADO EN CONSENSO
     */
    private boolean decideProceedBasedOnConsensus(int approvals, int totalReplicas) {
        // Para redundancia activa fuerte, requerimos unanimidad
        // Para redundancia activa débil, podríamos usar mayoría

        // Configuración: Unanimidad estricta
        boolean unanimous = approvals == totalReplicas;

        // Configuración alternativa: Mayoría simple
        // boolean majority = approvals > totalReplicas / 2;

        LOGGER.info("🗳️ Consensus decision: " + approvals + "/" + totalReplicas +
                " approvals - " + (unanimous ? "PROCEED" : "ABORT"));

        return unanimous;
    }

    /**
     * 📥 PROCESAR PROPUESTA DE OPERACIÓN DE OTRA RÉPLICA
     */
    public ProtocolMessage processOperationProposal(ProtocolMessage proposal) {
        try {
            OperationHistory operation = deserializeOperation(proposal.getContent());
            LOGGER.info("📥 Processing operation proposal: " + operation.getOperationKey());

            // Validar la propuesta
            if (validateOperationProposal(operation)) {
                // Registrar la operación como pendiente
                operationHistory.put(operation.getOperationKey(), operation);

                LOGGER.info("✅ Operation proposal accepted: " + operation.getOperationKey());
                return new ProtocolMessage(ProtocolCommand.OPERATION_ACCEPTED,
                        proposal.getFileName(), serverId);
            } else {
                LOGGER.warning("❌ Operation proposal rejected: " + operation.getOperationKey());
                return new ProtocolMessage(ProtocolCommand.OPERATION_REJECTED,
                        proposal.getFileName(), "Validation failed");
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing operation proposal", e);
            return new ProtocolMessage(ProtocolCommand.OPERATION_REJECTED,
                    proposal.getFileName(), "Processing error");
        }
    }

    /**
     * 📥 PROCESAR COMMIT DE OPERACIÓN
     */
    public ProtocolMessage processOperationCommit(ProtocolMessage commit, FileSystemManager fileManager) {
        try {
            OperationHistory operation = deserializeOperation(commit.getContent());
            LOGGER.info("📥 Processing operation commit: " + operation.getOperationKey());

            // Ejecutar la operación localmente
            OperationResult result = executeOperationLocally(operation, fileManager);

            if (result.isSuccess()) {
                LOGGER.info("✅ Operation committed successfully: " + operation.getOperationKey());
                return new ProtocolMessage(ProtocolCommand.OPERATION_COMMITTED,
                        commit.getFileName(), serverId);
            } else {
                LOGGER.warning("❌ Operation commit failed: " + operation.getOperationKey() + " - " + result.getMessage());
                return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED,
                        commit.getFileName(), result.getMessage());
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing operation commit", e);
            return new ProtocolMessage(ProtocolCommand.OPERATION_FAILED,
                    commit.getFileName(), "Commit processing error");
        }
    }

    /**
     * 🏃 EJECUTAR OPERACIÓN LOCALMENTE
     */
    private OperationResult executeOperationLocally(OperationHistory operation, FileSystemManager fileManager) {
        switch (operation.getOperation()) {
            case WRITE:
                return fileManager.writeFile(operation.getFileName(), operation.getContent());
            case DELETE:
                return fileManager.deleteFile(operation.getFileName());
            default:
                return new OperationResult(false, "Unsupported operation for sync: " + operation.getOperation());
        }
    }

    /**
     * ✅ VALIDAR PROPUESTA DE OPERACIÓN
     */
    private boolean validateOperationProposal(OperationHistory operation) {
        // Validaciones básicas
        if (operation.getFileName() == null || operation.getFileName().isEmpty()) {
            return false;
        }

        if (operation.getOperation() == null) {
            return false;
        }

        // Verificar que no sea una operación duplicada
        return !operationHistory.containsKey(operation.getOperationKey());
    }

    /**
     * 🔗 AGREGAR SERVIDOR RÉPLICA
     */
    public void addReplicaServer(DistributedLockManager.ServerConnection server) {
        replicaServers.add(server);
        LOGGER.info("Added replica server: " + server.getServerId() + " (Total replicas: " + replicaServers.size() + ")");
    }

    /**
     * 🔗 REMOVER SERVIDOR RÉPLICA
     */
    public void removeReplicaServer(String serverId) {
        replicaServers.removeIf(server -> server.getServerId().equals(serverId));
        LOGGER.info("Removed replica server: " + serverId + " (Total replicas: " + replicaServers.size() + ")");
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
        return stats;
    }

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
        return new OperationHistory(
                parts[0], // operationKey
                parts[1], // fileName
                ProtocolCommand.fromString(parts[2]), // operation
                parts[3].isEmpty() ? null : parts[3].replace("\\|", "|"), // content
                parts[4], // originServer
                Long.parseLong(parts[5]) // timestamp
        );
    }

    public void shutdown() {
        LOGGER.info("Shutting down ActiveReplicationManager");
        if (syncExecutor != null) {
            syncExecutor.shutdown();
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

        // Getters
        public String getOperationKey() { return operationKey; }
        public String getFileName() { return fileName; }
        public ProtocolCommand getOperation() { return operation; }
        public String getContent() { return content; }
        public String getOriginServer() { return originServer; }
        public long getTimestamp() { return timestamp; }
    }
}