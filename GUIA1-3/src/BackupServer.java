import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
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
                ProtocolMessage response = processMessage(message);

                // Send response if needed
                if (response != null) {
                    writer.println(response.toString());
                }
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

    private ProtocolMessage processMessage(ProtocolMessage message) {
        switch (message.getCommand()) {
            case BACKUP_READY:
                LOGGER.info("Backup server ready for replication");
                break;

            case SYNC_REQUEST:
                LOGGER.info("Received sync request from primary");
                break;

            case SYNC_STATE_REQUEST:
                return handleStateRequest();

            case SYNC_FILE:
                processSyncFile(message);
                break;

            case SYNC_DELETE:
                processSyncDelete(message);
                break;

            case REPLICATE:
                processReplicationMessage(message);
                break;

            default:
                LOGGER.warning("Received unknown message: " + message.getCommand());
        }

        return null; // No response needed for most messages
    }

    private void processReplicationMessage(ProtocolMessage message) {
        if (message.getCommand() == ProtocolCommand.BACKUP_READY) {
            LOGGER.info("Backup server ready for replication");
            return;
        }

        if (message.getCommand() == ProtocolCommand.SYNC_REQUEST) {
            LOGGER.info("Received sync request from primary");
            return; // Just acknowledge, primary will send individual files
        }

        if (message.getCommand() == ProtocolCommand.SYNC_FILE) {
            processSyncFile(message);
            return;
        }

        if (message.getCommand() != ProtocolCommand.REPLICATE) {
            LOGGER.warning("Received non-replication message: " + message.getCommand());
            return;
        }

        LOGGER.info("Processing replication message for file: " + message.getFileName());

        String content = message.getContent();
        if (content == null || content.isEmpty()) {
            LOGGER.warning("Empty replication content");
            return;
        }

        String[] contentParts = content.split("\\|", 2);
        if (contentParts.length < 1) {
            LOGGER.warning("Invalid replication message format");
            return;
        }

        try {
            ProtocolCommand originalCommand = ProtocolCommand.fromString(contentParts[0]);
            String actualContent = contentParts.length > 1 ? contentParts[1] : "";

            OperationResult result;
            switch (originalCommand) {
                case WRITE:
                    // For replication, always overwrite completely for consistency
                    result = fileManager.writeFile(message.getFileName(), actualContent, false);
                    LOGGER.info("Replicated WRITE (overwrite) for " + message.getFileName() +
                            " - " + (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()));
                    break;

                case DELETE:
                    result = fileManager.deleteFile(message.getFileName());
                    LOGGER.info("Replicated DELETE for " + message.getFileName() +
                            " - " + (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()));
                    break;

                default:
                    LOGGER.warning("Unsupported replication command: " + originalCommand);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing replication message for " + message.getFileName(), e);
        }
    }

    private void processSyncFile(ProtocolMessage message) {
        try {
            String fileName = message.getFileName();
            String content = message.getContent();

            if (content == null || fileName == null) {
                return;
            }

            // Parse content and checksum
            String[] parts = content.split("\\|", 2);
            if (parts.length < 2) {
                return;
            }

            String fileContent = parts[0];
            String primaryChecksum = parts[1];

            // Check if we need to sync this file
            FileMetadata backupMetadata = fileManager.getFileMetadata(fileName);
            boolean needsSync = backupMetadata == null || !backupMetadata.getChecksum().equals(primaryChecksum);

            if (needsSync) {
                OperationResult result = fileManager.writeFile(fileName, fileContent, false);
                LOGGER.info("Synced file: " + fileName + " - " +
                        (result.isSuccess() ? "SUCCESS" : "FAILED"));
            } else {
                LOGGER.fine("File " + fileName + " already in sync");
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing sync file message", e);
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

    private ProtocolMessage handleStateRequest() {
        LOGGER.info("Processing state request from primary");

        try {
            Map<String, FileMetadata> allFiles = fileManager.getAllFilesMetadata();
            StringBuilder stateContent = new StringBuilder();

            for (FileMetadata metadata : allFiles.values()) {
                stateContent.append(String.format("%s|%s|%d|%d\n",
                        metadata.getFileName(),
                        metadata.getChecksum(),
                        metadata.getLastModified(),
                        metadata.getSize()));
            }

            LOGGER.info("Sending state response with " + allFiles.size() + " files");

            return new ProtocolMessage(ProtocolCommand.SYNC_STATE_RESPONSE, null, stateContent.toString().trim());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error creating state response", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, null, "Failed to get server state");
        }
    }

    private void processSyncDelete(ProtocolMessage message) {
        try {
            String fileName = message.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                LOGGER.warning("Received sync delete with empty filename");
                return;
            }

            OperationResult result = fileManager.deleteFile(fileName);
            LOGGER.info("Sync DELETE for " + fileName + " - " +
                    (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing sync delete", e);
        }
    }

}