public enum ProtocolCommand {
    // Comandos originales del cliente
    WRITE("WRITE"),
    READ("READ"),
    DELETE("DELETE"),
    LIST("LIST"),

    // Comandos de respuesta
    SUCCESS("SUCCESS"),
    ERROR("ERROR"),
    NOT_FOUND("NOT_FOUND"),

    // 🆕 COMANDOS PARA LOCKS DISTRIBUIDOS
    LOCK_REQUEST("LOCK_REQUEST"),           // Solicitar lock distribuido
    LOCK_GRANTED("LOCK_GRANTED"),           // Lock otorgado
    LOCK_DENIED("LOCK_DENIED"),             // Lock denegado
    LOCK_RELEASED("LOCK_RELEASED"),         // Notificar liberación de lock

    // 🆕 COMANDOS PARA REPLICACIÓN ACTIVA
    OPERATION_PROPOSAL("OPERATION_PROPOSAL"),   // Proponer operación a réplicas
    OPERATION_ACCEPTED("OPERATION_ACCEPTED"),   // Operación aceptada por réplica
    OPERATION_REJECTED("OPERATION_REJECTED"),   // Operación rechazada por réplica
    OPERATION_COMMIT("OPERATION_COMMIT"),       // Confirmar operación
    OPERATION_COMMITTED("OPERATION_COMMITTED"), // Operación confirmada
    OPERATION_ABORT("OPERATION_ABORT"),         // Abortar operación
    OPERATION_FAILED("OPERATION_FAILED"),       // Operación falló

    // 🆕 COMANDOS PARA GESTIÓN DE RÉPLICAS
    REPLICA_JOIN("REPLICA_JOIN"),               // Nueva réplica se une
    REPLICA_LEAVE("REPLICA_LEAVE"),             // Réplica se desconecta
    REPLICA_STATUS("REPLICA_STATUS"),           // Estado de réplica
    REPLICA_SYNC_REQUEST("REPLICA_SYNC_REQUEST"), // Solicitar sincronización
    REPLICA_SYNC_RESPONSE("REPLICA_SYNC_RESPONSE"), // Respuesta de sincronización

    // Comandos heredados (mantener compatibilidad)
    REPLICATE("REPLICATE"),
    SYNC_REQUEST("SYNC_REQUEST"),
    SYNC_FILE("SYNC_FILE"),
    SYNC_DELETE("SYNC_DELETE"),
    SYNC_STATE_REQUEST("SYNC_STATE_REQUEST"),
    SYNC_STATE_RESPONSE("SYNC_STATE_RESPONSE"),
    HEARTBEAT("HEARTBEAT"),
    BACKUP_READY("BACKUP_READY");

    private final String command;

    ProtocolCommand(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    public static ProtocolCommand fromString(String command) {
        for (ProtocolCommand cmd : values()) {
            if (cmd.command.equals(command)) {
                return cmd;
            }
        }
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    /**
     * 🔍 VERIFICAR SI ES COMANDO DE CLIENTE
     */
    public boolean isClientCommand() {
        return this == WRITE || this == READ || this == DELETE || this == LIST;
    }

    /**
     * 🔍 VERIFICAR SI ES COMANDO DE LOCK
     */
    public boolean isLockCommand() {
        return this == LOCK_REQUEST || this == LOCK_GRANTED ||
                this == LOCK_DENIED || this == LOCK_RELEASED;
    }

    /**
     * 🔍 VERIFICAR SI ES COMANDO DE REPLICACIÓN
     */
    public boolean isReplicationCommand() {
        return this == OPERATION_PROPOSAL || this == OPERATION_ACCEPTED ||
                this == OPERATION_REJECTED || this == OPERATION_COMMIT ||
                this == OPERATION_COMMITTED || this == OPERATION_ABORT ||
                this == OPERATION_FAILED;
    }

    /**
     * 🔍 VERIFICAR SI ES COMANDO DE GESTIÓN DE RÉPLICAS
     */
    public boolean isReplicaManagementCommand() {
        return this == REPLICA_JOIN || this == REPLICA_LEAVE ||
                this == REPLICA_STATUS || this == REPLICA_SYNC_REQUEST ||
                this == REPLICA_SYNC_RESPONSE;
    }
}