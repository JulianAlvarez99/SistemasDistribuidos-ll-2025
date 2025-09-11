public class ProtocolMessage {
    private ProtocolCommand command;
    private String fileName;
    private String content;
    private String timestamp;
    private String clientId;

    public ProtocolMessage() {
        this.timestamp = java.time.Instant.now().toString();
    }

    public ProtocolMessage(ProtocolCommand command, String fileName, String content) {
        this();
        this.command = command;
        this.fileName = fileName;
        this.content = content;
    }

    // Getters and Setters
    public ProtocolCommand getCommand() { return command; }
    public void setCommand(ProtocolCommand command) { this.command = command; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    @Override
    public String toString() {
        return String.format("%s|%s|%s|%s|%s",
                command != null ? command.getCommand() : "",
                fileName != null ? fileName : "",
                content != null ? content.replace("\n", "\\n") : "",
                timestamp != null ? timestamp : "",
                clientId != null ? clientId : "");
    }

    public static ProtocolMessage fromString(String messageStr) {
        String[] parts = messageStr.split("\\|", 5);
        ProtocolMessage message = new ProtocolMessage();

        if (parts.length >= 1) message.setCommand(ProtocolCommand.fromString(parts[0]));
        if (parts.length >= 2) message.setFileName(parts[1].isEmpty() ? null : parts[1]);
        if (parts.length >= 3) message.setContent(parts[2].isEmpty() ? null : parts[2].replace("\\n", "\n"));
        if (parts.length >= 4) message.setTimestamp(parts[3].isEmpty() ? null : parts[3]);
        if (parts.length >= 5) message.setClientId(parts[4].isEmpty() ? null : parts[4]);

        return message;
    }
}


