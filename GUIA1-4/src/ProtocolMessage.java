/**
 * 🔧 PROTOCOL MESSAGE - VERSIÓN FINAL CORREGIDA
 * Protocolo simplificado y robusto para comunicación cliente-servidor
 */
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

    /**
     * 🔧 SERIALIZACIÓN CORREGIDA: Formato simple y confiable
     */
    @Override
    public String toString() {
        try {
            // Usar formato simple: COMMAND|FILENAME|CONTENT|TIMESTAMP|CLIENTID
            // Escapar caracteres problemáticos en el contenido
            String escapedContent = "";
            if (content != null) {
                escapedContent = content
                        .replace("\\", "\\\\")  // Escapar backslashes primero
                        .replace("|", "\\|")    // Escapar separadores
                        .replace("\n", "\\n")   // Escapar newlines
                        .replace("\r", "\\r");  // Escapar carriage returns
            }

            return String.format("%s|%s|%s|%s|%s",
                    command != null ? command.getCommand() : "",
                    fileName != null ? fileName : "",
                    escapedContent,
                    timestamp != null ? timestamp : "",
                    clientId != null ? clientId : "");

        } catch (Exception e) {
            System.err.println("❌ Error serializing message: " + e.getMessage());
            throw new RuntimeException("Serialization failed", e);
        }
    }

    /**
     * 🔧 DESERIALIZACIÓN CORREGIDA: Parsing robusto y con validación
     */
    public static ProtocolMessage fromString(String messageStr) {
        if (messageStr == null || messageStr.trim().isEmpty()) {
            throw new RuntimeException("Empty or null message string");
        }

        try {
            // Split usando regex para limitar a exactamente 5 partes
            String[] parts = messageStr.split("\\|", 5);
            ProtocolMessage message = new ProtocolMessage();

            // Debug: Log del parsing
            System.out.println("🔍 Parsing message with " + parts.length + " parts");
            for (int i = 0; i < parts.length; i++) {
                System.out.println("  Part[" + i + "]: '" + parts[i] + "'");
            }

            // 1. Command (obligatorio)
            if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                try {
                    message.setCommand(ProtocolCommand.fromString(parts[0].trim()));
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Invalid command: '" + parts[0] + "'", e);
                }
            } else {
                throw new RuntimeException("Missing command in message");
            }

            // 2. FileName (opcional según el comando)
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                message.setFileName(parts[1].trim());
            }

            // 3. Content (opcional, des-escapar caracteres)
            if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                String unescapedContent = parts[2]
                        .replace("\\n", "\n")   // Des-escapar newlines
                        .replace("\\r", "\r")   // Des-escapar carriage returns
                        .replace("\\|", "|")    // Des-escapar separadores
                        .replace("\\\\", "\\"); // Des-escapar backslashes (último)
                message.setContent(unescapedContent);
            }

            // 4. Timestamp
            if (parts.length > 3 && !parts[3].trim().isEmpty()) {
                message.setTimestamp(parts[3].trim());
            }

            // 5. ClientId
            if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                message.setClientId(parts[4].trim());
            }

            // Validar que el mensaje es correcto
            if (!message.isValid()) {
                throw new RuntimeException("Invalid message structure: " + message.toDebugString());
            }

            System.out.println("✅ Successfully parsed: " + message.toDebugString());
            return message;

        } catch (Exception e) {
            System.err.println("❌ Error parsing message: '" + messageStr + "'");
            System.err.println("❌ Error details: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to parse message: " + messageStr, e);
        }
    }

    /**
     * 🔧 VALIDACIÓN DE MENSAJE MEJORADA
     */
    public boolean isValid() {
        if (command == null) {
            System.err.println("Invalid: command is null");
            return false;
        }

        switch (command) {
            case LIST:
                // LIST no necesita fileName ni content
                return true;

            case READ:
            case DELETE:
                // READ y DELETE necesitan fileName pero no content
                if (fileName == null || fileName.trim().isEmpty()) {
                    System.err.println("Invalid: " + command + " requires fileName");
                    return false;
                }
                return true;

            case WRITE:
                // WRITE necesita fileName, content puede ser vacío pero no null
                if (fileName == null || fileName.trim().isEmpty()) {
                    System.err.println("Invalid: WRITE requires fileName");
                    return false;
                }
                if (content == null) {
                    System.err.println("Invalid: WRITE requires content (can be empty)");
                    return false;
                }
                return true;

            default:
                return true; // Otros comandos son válidos por defecto
        }
    }

    /**
     * 🔧 DEBUG STRING MEJORADO
     */
    public String toDebugString() {
        return String.format("ProtocolMessage{cmd=%s, file='%s', contentLen=%d, timestamp='%s', clientId='%s'}",
                command,
                fileName != null ? fileName : "null",
                content != null ? content.length() : 0,
                timestamp != null ? timestamp.substring(Math.max(0, timestamp.length() - 10)) : "null", // Solo últimos 10 chars
                clientId != null ? clientId : "null");
    }

    /**
     * 🧪 MÉTODO DE TESTING ESTÁTICO
     */
    public static void runTests() {
        System.out.println("🧪 Running ProtocolMessage tests...");

        // Test 1: LIST command
        testCommand(ProtocolCommand.LIST, null, null, "LIST test");

        // Test 2: WRITE command
        testCommand(ProtocolCommand.WRITE, "test.txt", "Hello\nWorld!", "WRITE test");

        // Test 3: READ command  
        testCommand(ProtocolCommand.READ, "test.txt", null, "READ test");

        // Test 4: DELETE command
        testCommand(ProtocolCommand.DELETE, "test.txt", null, "DELETE test");

        // Test 5: Content with special characters
        testCommand(ProtocolCommand.WRITE, "special.txt", "Content with | pipes and \n newlines", "Special chars test");

        System.out.println("✅ All tests completed");
    }

    private static void testCommand(ProtocolCommand cmd, String fileName, String content, String testName) {
        try {
            // Create message
            ProtocolMessage original = new ProtocolMessage(cmd, fileName, content);
            original.setClientId("TEST_CLIENT");

            // Serialize
            String serialized = original.toString();
            System.out.println(testName + " serialized: " + serialized);

            // Deserialize
            ProtocolMessage deserialized = fromString(serialized);

            // Compare
            boolean success = original.getCommand() == deserialized.getCommand() &&
                    equals(original.getFileName(), deserialized.getFileName()) &&
                    equals(original.getContent(), deserialized.getContent());

            System.out.println(testName + ": " + (success ? "✅ PASS" : "❌ FAIL"));

        } catch (Exception e) {
            System.out.println(testName + ": ❌ EXCEPTION - " + e.getMessage());
        }
    }

    private static boolean equals(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }
}