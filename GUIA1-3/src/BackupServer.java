import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BackupServer {
    private static final Logger LOGGER = Logger.getLogger(BackupServer.class.getName());
    private final int port;
    private final FileSystemManager fileManager;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile boolean primaryConnected;

    public BackupServer(int port, String storageDirectory) {
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.running = false;
        this.primaryConnected = false;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            LOGGER.info("Backup server started on port " + port);

            while (running) {
                try {
                    Socket primarySocket = serverSocket.accept();
                    handlePrimaryConnection(primarySocket);
                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Error accepting primary connection", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start backup server", e);
        }
    }

    private void handlePrimaryConnection(Socket primarySocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(primarySocket.getInputStream()));
             PrintWriter writer = new PrintWriter(primarySocket.getOutputStream(), true)) {

            primaryConnected = true;
            LOGGER.info("Primary server connected");

            String messageStr;
            while ((messageStr = reader.readLine()) != null && primaryConnected) {
                ProtocolMessage message = ProtocolMessage.fromString(messageStr);
                processReplicationMessage(message);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error handling primary connection", e);
        } finally {
            primaryConnected = false;
            try {
                primarySocket.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing primary socket", e);
            }
        }
    }

    private void processReplicationMessage(ProtocolMessage message) {
        if (message.getCommand() == ProtocolCommand.BACKUP_READY) {
            LOGGER.info("Backup server ready for replication");
            return;
        }

        if (message.getCommand() != ProtocolCommand.REPLICATE) {
            return;
        }

        // Extract original command from replicated message content
        String[] contentParts = message.getContent().split("\\|", 2);
        if (contentParts.length < 1) return;

        try {
            ProtocolCommand originalCommand = ProtocolCommand.fromString(contentParts[0]);
            String actualContent = contentParts.length > 1 ? contentParts[1] : "";

            switch (originalCommand) {
                case WRITE:
                    fileManager.writeFile(message.getFileName(), actualContent);
                    break;
                case DELETE:
                    fileManager.deleteFile(message.getFileName());
                    break;
            }

            LOGGER.info("Replicated operation: " + originalCommand + " for file: " + message.getFileName());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing replication message", e);
        }
    }

    public void stop() {
        running = false;
        primaryConnected = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error stopping backup server", e);
        }
    }
}