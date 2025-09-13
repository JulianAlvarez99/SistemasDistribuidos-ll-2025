import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BACKUP SERVER - FIXED VERSION
 * Consistent replication handling with proper content management
 */
public class BackupServer {
    private static final Logger LOGGER = Logger.getLogger(BackupServer.class.getName());
    private final int port;
    private final FileSystemManager fileManager;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile boolean primaryConnected;
    private final String serverId;

    public BackupServer(int port, String storageDirectory) {
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.running = false;
        this.primaryConnected = false;
        this.serverId = "BACKUP_" + port;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            LOGGER.info("Backup server (" + serverId + ") started on port " + port);

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
            LOGGER.info("Primary server connected to backup (" + serverId + ")");

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
                LOGGER.info("Primary connection closed for backup (" + serverId + ")");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing primary socket", e);
            }
        }
    }

    private ProtocolMessage processMessage(ProtocolMessage message) {
        switch (message.getCommand()) {
            case BACKUP_READY:
                LOGGER.info("Backup server (" + serverId + ") ready for replication");
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

    /**
     * FIXED: Consistent replication message processing
     */
    private void processReplicationMessage(ProtocolMessage message) {
        if (message.getCommand() != ProtocolCommand.REPLICATE) {
            LOGGER.warning("Received non-replication message: " + message.getCommand());
            return;
        }

        String fileName = message.getFileName();
        String content = message.getContent();

        if (fileName == null || fileName.isEmpty()) {
            LOGGER.warning("Empty filename in replication message");
            return;
        }

        if (content == null || content.isEmpty()) {
            LOGGER.warning("Empty content in replication message for file: " + fileName);
            return;
        }

        LOGGER.info("Processing replication for file: " + fileName);

        String[] contentParts = content.split("\\|", 2);
        if (contentParts.length < 1) {
            LOGGER.warning("Invalid replication message format for file: " + fileName);
            return;
        }

        try {
            ProtocolCommand originalCommand = ProtocolCommand.fromString(contentParts[0]);
            String fileContent = contentParts.length > 1 ? contentParts[1] : "";

            OperationResult result;
            switch (originalCommand) {
                case WRITE:
                    // FIXED: Use OVERWRITE mode for replication consistency
                    // This ensures backup has exactly the same content as primary
                    result = fileManager.writeFile(fileName, fileContent, FileSystemManager.WriteMode.OVERWRITE);
                    LOGGER.info("Replicated WRITE (complete file) for " + fileName +
                            " - " + (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()) +
                            " (" + fileContent.length() + " chars)");
                    break;

                case DELETE:
                    result = fileManager.deleteFile(fileName);
                    LOGGER.info("Replicated DELETE for " + fileName +
                            " - " + (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()));
                    break;

                default:
                    LOGGER.warning("Unsupported replication command: " + originalCommand + " for file: " + fileName);
                    return;
            }

            if (!result.isSuccess()) {
                LOGGER.severe("Replication FAILED for " + fileName + ": " + result.getMessage());
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing replication message for " + fileName, e);
        }
    }

    /**
     * FIXED: Enhanced sync file processing with better error handling
     */
    private void processSyncFile(ProtocolMessage message) {
        try {
            String fileName = message.getFileName();
            String content = message.getContent();

            if (content == null || fileName == null) {
                LOGGER.warning("Sync file message missing filename or content");
                return;
            }

            // Parse content and checksum
            String[] parts = content.split("\\|", 2);
            if (parts.length < 2) {
                LOGGER.warning("Invalid sync file message format for: " + fileName);
                return;
            }

            String fileContent = parts[0];
            String primaryChecksum = parts[1];

            LOGGER.info("Processing sync for file: " + fileName + " (checksum: " + primaryChecksum + ")");

            // Check if we need to sync this file
            FileMetadata backupMetadata = fileManager.getFileMetadata(fileName);
            boolean needsSync = backupMetadata == null || !backupMetadata.getChecksum().equals(primaryChecksum);

            if (needsSync) {
                // Use OVERWRITE mode to ensure consistency
                OperationResult result = fileManager.writeFile(fileName, fileContent, FileSystemManager.WriteMode.OVERWRITE);
                LOGGER.info("Synced file: " + fileName + " - " +
                        (result.isSuccess() ? "SUCCESS" : "FAILED: " + result.getMessage()) +
                        " (" + fileContent.length() + " chars)");
            } else {
                LOGGER.fine("File " + fileName + " already in sync (checksum match)");
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing sync file message", e);
        }
    }

    /**
     * Enhanced sync delete processing
     */
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

    /**
     * Enhanced state request handling
     */
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

            String stateResponse = stateContent.toString().trim();
            LOGGER.info("Sending state response with " + allFiles.size() + " files (" +
                    stateResponse.length() + " chars)");

            return new ProtocolMessage(ProtocolCommand.SYNC_STATE_RESPONSE, null, stateResponse);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error creating state response", e);
            return new ProtocolMessage(ProtocolCommand.ERROR, null, "Failed to get server state: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        primaryConnected = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                LOGGER.info("Backup server (" + serverId + ") stopped");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error stopping backup server", e);
        }
    }

    /**
     * Health check method for monitoring
     */
    public boolean isHealthy() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * Get backup server statistics
     */
    public String getStats() {
        try {
            Map<String, FileMetadata> files = fileManager.getAllFilesMetadata();
            long totalSize = files.values().stream().mapToLong(FileMetadata::getSize).sum();

            return String.format("Backup %s: %d files, %.2f KB total, %s",
                    serverId, files.size(), totalSize / 1024.0,
                    primaryConnected ? "CONNECTED" : "DISCONNECTED");
        } catch (Exception e) {
            return String.format("Backup %s: ERROR - %s", serverId, e.getMessage());
        }
    }
}