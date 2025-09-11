/**
 * Protocol commands for primary-backup system communication
 */
public enum ProtocolCommand {
    WRITE("WRITE"),
    READ("READ"),
    DELETE("DELETE"),
    REPLICATE("REPLICATE"),
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