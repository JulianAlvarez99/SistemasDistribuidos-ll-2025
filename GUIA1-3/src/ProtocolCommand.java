public enum ProtocolCommand {
    WRITE("WRITE"),
    READ("READ"),
    DELETE("DELETE"),
    LIST("LIST"),
    REPLICATE("REPLICATE"),
    SYNC_REQUEST("SYNC_REQUEST"),         // Request full sync
    SYNC_FILE("SYNC_FILE"),              // Sync specific file
    SYNC_DELETE("SYNC_DELETE"),          // Delete file during sync
    SYNC_STATE_REQUEST("SYNC_STATE_REQUEST"), // Request backup state
    SYNC_STATE_RESPONSE("SYNC_STATE_RESPONSE"), // Backup state response
    HEARTBEAT("HEARTBEAT"),
    BACKUP_READY("BACKUP_READY"),
    SUCCESS("SUCCESS"),
    ERROR("ERROR"),
    NOT_FOUND("NOT_FOUND");

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
}