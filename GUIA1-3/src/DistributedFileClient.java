import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DistributedFileClient {
    private static final Logger LOGGER = Logger.getLogger(DistributedFileClient.class.getName());
    private final String primaryHost;
    private final int primaryPort;
    private final String clientId;

    public DistributedFileClient(String primaryHost, int primaryPort) {
        this.primaryHost = primaryHost;
        this.primaryPort = primaryPort;
        this.clientId = "CLIENT_" + System.currentTimeMillis();
    }

    public OperationResult write(String fileName, String content) {
        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.WRITE, fileName, content);
        message.setClientId(clientId);
        return sendMessage(message);
    }

    public OperationResult read(String fileName) {
        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.READ, fileName, null);
        message.setClientId(clientId);
        return sendMessage(message);
    }

    public OperationResult delete(String fileName) {
        ProtocolMessage message = new ProtocolMessage(ProtocolCommand.DELETE, fileName, null);
        message.setClientId(clientId);
        return sendMessage(message);
    }

    private OperationResult sendMessage(ProtocolMessage message) {
        try (Socket socket = new Socket(primaryHost, primaryPort);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            writer.println(message.toString());
            String responseStr = reader.readLine();

            if (responseStr != null) {
                ProtocolMessage response = ProtocolMessage.fromString(responseStr);

                if (response.getCommand() == ProtocolCommand.SUCCESS) {
                    return new OperationResult(true, "Operation completed successfully", response.getContent());
                } else {
                    return new OperationResult(false, response.getContent());
                }
            }

            return new OperationResult(false, "No response from server");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error communicating with server", e);
            return new OperationResult(false, "Communication error: " + e.getMessage());
        }
    }
}