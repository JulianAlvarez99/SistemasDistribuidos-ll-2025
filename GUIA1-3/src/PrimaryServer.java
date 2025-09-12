import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Primary server implementation
 */
public class PrimaryServer {
    private static final Logger LOGGER = Logger.getLogger(PrimaryServer.class.getName());
    private final int port;
    private final FileSystemManager fileManager;
    private final ExecutorService clientThreadPool;
    private final CopyOnWriteArrayList<BackupConnection> backupServers;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private final ScheduledExecutorService syncScheduler;
    private final int SYNC_INTERVAL_SECONDS = 10; // Sync every 10 seconds

    public PrimaryServer(int port, String storageDirectory) {
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.clientThreadPool = Executors.newCachedThreadPool();
        this.backupServers = new CopyOnWriteArrayList<>();
        this.syncScheduler = Executors.newScheduledThreadPool(1);
        this.running = false;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;

            // Start periodic synchronization
            startPeriodicSync();

            LOGGER.info("Primary server started on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientThreadPool.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Error accepting client connection", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start primary server", e);
        }
    }

    private void startPeriodicSync() {
        syncScheduler.scheduleAtFixedRate(() -> {
            try {
                performFullSync();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during periodic sync", e);
            }
        }, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOGGER.info("Periodic synchronization started (every " + SYNC_INTERVAL_SECONDS + " seconds)");
    }

    public void performFullSync() {
        if (backupServers.isEmpty()) {
            return;
        }

        LOGGER.info("Starting COMPLETE global state synchronization with backup servers");

        // Get current state of primary server
        Map<String, FileMetadata> primaryFiles = fileManager.getAllFilesMetadata();

        for (BackupConnection backup : backupServers) {
            if (backup.isConnected()) {
                CompletableFuture.runAsync(() -> performCompleteSync(backup, primaryFiles));
            }
        }
    }

    private void performCompleteSync(BackupConnection backup, Map<String, FileMetadata> primaryFiles) {
        try {
            LOGGER.info("Starting complete sync with backup server");

            // STEP 1: Get backup server state
            Map<String, FileMetadata> backupFiles = getBackupServerState(backup);
            if (backupFiles == null) {
                LOGGER.warning("Failed to get backup server state, skipping sync");
                return;
            }

            // STEP 2: Identify files to sync FROM primary TO backup
            Set<String> filesToSync = new HashSet<>();
            Set<String> filesToCreate = new HashSet<>();

            for (Map.Entry<String, FileMetadata> entry : primaryFiles.entrySet()) {
                String fileName = entry.getKey();
                FileMetadata primaryMetadata = entry.getValue();
                FileMetadata backupMetadata = backupFiles.get(fileName);

                if (backupMetadata == null) {
                    // File exists in primary but not in backup - CREATE
                    filesToCreate.add(fileName);
                    LOGGER.info("File to CREATE in backup: " + fileName);
                } else if (primaryMetadata.needsSync(backupMetadata)) {
                    // File exists in both but differs - UPDATE
                    filesToSync.add(fileName);
                    LOGGER.info("File to UPDATE in backup: " + fileName +
                            " (primary checksum: " + primaryMetadata.getChecksum() +
                            ", backup checksum: " + backupMetadata.getChecksum() + ")");
                }
            }

            // STEP 3: Identify files to DELETE FROM backup (exist in backup but not in primary)
            Set<String> filesToDelete = new HashSet<>();
            for (String backupFileName : backupFiles.keySet()) {
                if (!primaryFiles.containsKey(backupFileName)) {
                    filesToDelete.add(backupFileName);
                    LOGGER.info("File to DELETE from backup: " + backupFileName);
                }
            }

            // STEP 4: Execute synchronization operations
            int syncedFiles = 0;
            int createdFiles = 0;
            int deletedFiles = 0;

            // Sync existing files
            for (String fileName : filesToSync) {
                FileMetadata primaryFile = primaryFiles.get(fileName);
                if (syncFileToBackup(backup, primaryFile)) {
                    syncedFiles++;
                }
            }

            // Create new files
            for (String fileName : filesToCreate) {
                FileMetadata primaryFile = primaryFiles.get(fileName);
                if (syncFileToBackup(backup, primaryFile)) {
                    createdFiles++;
                }
            }

            // Delete orphaned files
            for (String fileName : filesToDelete) {
                if (deleteFileFromBackup(backup, fileName)) {
                    deletedFiles++;
                }
            }

            LOGGER.info(String.format(
                    "Completed FULL sync with backup server - Updated: %d, Created: %d, Deleted: %d files",
                    syncedFiles, createdFiles, deletedFiles));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during complete sync with backup", e);
        }
    }

    private Map<String, FileMetadata> getBackupServerState(BackupConnection backup) {
        try {
            // Request backup server state
            ProtocolMessage stateRequest = new ProtocolMessage(ProtocolCommand.SYNC_STATE_REQUEST, null, null);
            ProtocolMessage response = backup.sendMessageAndWaitResponse(stateRequest, 10000); // 10s timeout

            if (response == null || response.getCommand() != ProtocolCommand.SYNC_STATE_RESPONSE) {
                LOGGER.warning("No valid response from backup server for state request");
                return null;
            }

            // Parse backup server state
            Map<String, FileMetadata> backupFiles = parseBackupState(response.getContent());
            LOGGER.info("Received backup server state: " + backupFiles.size() + " files");

            return backupFiles;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting backup server state", e);
            return null;
        }
    }

    private Map<String, FileMetadata> parseBackupState(String stateContent) {
        Map<String, FileMetadata> backupFiles = new HashMap<>();

        if (stateContent == null || stateContent.trim().isEmpty()) {
            return backupFiles; // Empty backup
        }

        String[] fileEntries = stateContent.split("\n");
        for (String entry : fileEntries) {
            if (entry.trim().isEmpty()) continue;

            try {
                // Format: filename|checksum|lastModified|size
                String[] parts = entry.split("\\|", 4);
                if (parts.length >= 4) {
                    String fileName = parts[0];
                    String checksum = parts[1];
                    long lastModified = Long.parseLong(parts[2]);
                    long size = Long.parseLong(parts[3]);

                    // Create metadata without content (we don't need it for comparison)
                    FileMetadata metadata = new FileMetadata(fileName, "", lastModified, size);
                    // Set checksum manually since we don't have content
                    metadata.setChecksum(checksum);

                    backupFiles.put(fileName, metadata);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error parsing backup file entry: " + entry, e);
            }
        }

        return backupFiles;
    }

    /**
     * Sync specific file to backup
     */
    private boolean syncFileToBackup(BackupConnection backup, FileMetadata fileMetadata) {
        try {
            ProtocolMessage syncMessage = new ProtocolMessage(
                    ProtocolCommand.SYNC_FILE,
                    fileMetadata.getFileName(),
                    fileMetadata.getContent() + "|" + fileMetadata.getChecksum()
            );

            backup.sendMessage(syncMessage);
            // Note: In production, you'd wait for confirmation
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error syncing file to backup: " + fileMetadata.getFileName(), e);
            return false;
        }
    }

    /**
     * Delete file from backup
     */
    private boolean deleteFileFromBackup(BackupConnection backup, String fileName) {
        try {
            ProtocolMessage deleteMessage = new ProtocolMessage(ProtocolCommand.SYNC_DELETE, fileName, null);
            backup.sendMessage(deleteMessage);
            LOGGER.info("Sent delete command to backup for file: " + fileName);
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error deleting file from backup: " + fileName, e);
            return false;
        }
    }



    private void syncWithBackup(BackupConnection backup, Map<String, FileMetadata> primaryFiles) {
        try {
            // Send sync request to get backup files metadata
            ProtocolMessage syncRequest = new ProtocolMessage(ProtocolCommand.SYNC_REQUEST, null, null);
            backup.sendMessage(syncRequest);

            // For each file in primary, sync if needed
            for (FileMetadata primaryFile : primaryFiles.values()) {
                ProtocolMessage syncMessage = new ProtocolMessage(
                        ProtocolCommand.SYNC_FILE,
                        primaryFile.getFileName(),
                        primaryFile.getContent() + "|" + primaryFile.getChecksum()
                );
                backup.sendMessage(syncMessage);
            }

            LOGGER.info("Completed sync with backup server - " + primaryFiles.size() + " files processed");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error syncing with backup", e);
        }
    }

    public void stop() {
        running = false;

        // Stop sync scheduler
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            try {
                if (!syncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    syncScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                syncScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            clientThreadPool.shutdown();

            for (BackupConnection backup : backupServers) {
                backup.close();
            }
            backupServers.clear();

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error stopping primary server", e);
        }
    }

    public void addBackupServer(String host, int port) {
        try {
            BackupConnection backup = new BackupConnection(host, port);
            if (backup.connect()) {
                backupServers.add(backup);
                LOGGER.info("Connected to backup server: " + host + ":" + port);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to connect to backup server: " + host + ":" + port, e);
        }
    }

    private void replicateToBackups(ProtocolMessage originalMessage) {
        if (backupServers.isEmpty()) {
            return;
        }

        // For WRITE operations, get the current complete file content
        String completeContent = null;
        if (originalMessage.getCommand() == ProtocolCommand.WRITE) {
            OperationResult readResult = fileManager.readFile(originalMessage.getFileName());
            if (readResult.isSuccess()) {
                completeContent = readResult.getContent();
            }
        }

        // Create replication message with complete file content for consistency
        String replicationContent;
        if (completeContent != null) {
            replicationContent = originalMessage.getCommand().getCommand() + "|" + completeContent;
        } else {
            replicationContent = originalMessage.getCommand().getCommand() + "|" +
                    (originalMessage.getContent() != null ? originalMessage.getContent() : "");
        }

        ProtocolMessage replicationMessage = new ProtocolMessage(ProtocolCommand.REPLICATE,
                originalMessage.getFileName(),
                replicationContent);

        LOGGER.info("Replicating to " + backupServers.size() + " backup servers: " +
                originalMessage.getCommand() + " on " + originalMessage.getFileName());

        for (BackupConnection backup : backupServers) {
            if (backup.isConnected()) {
                CompletableFuture.runAsync(() -> {
                    backup.sendMessage(replicationMessage);
                    LOGGER.fine("Sent replication message to backup server");
                });
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

                String messageStr = reader.readLine();
                if (messageStr != null) {
                    ProtocolMessage message = ProtocolMessage.fromString(messageStr);
                    ProtocolMessage response = processMessage(message);
                    writer.println(response.toString());
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error handling client", e);
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing client socket", e);
                }
            }
        }

        private ProtocolMessage processMessage(ProtocolMessage message) {
            OperationResult result;

            LOGGER.info("Processing command: " + message.getCommand() + " for file: " + message.getFileName());

            switch (message.getCommand()) {
                case WRITE:
                    result = fileManager.writeFile(message.getFileName(), message.getContent());
                    if (result.isSuccess()) {
                        replicateToBackups(message);
                        LOGGER.info("Write operation completed and replicated");
                    }
                    break;

                case READ:
                    result = fileManager.readFile(message.getFileName());
                    break;

                case DELETE:
                    result = fileManager.deleteFile(message.getFileName());
                    if (result.isSuccess()) {
                        replicateToBackups(message);
                        LOGGER.info("Delete operation completed and replicated");
                    }
                    break;

                case LIST:
                    result = fileManager.listFiles();
                    break;

                default:
                    result = new OperationResult(false, "Unknown command: " + message.getCommand());
            }

            ProtocolCommand responseCommand = result.isSuccess() ? ProtocolCommand.SUCCESS :
                    (result.getMessage().contains("not found") ? ProtocolCommand.NOT_FOUND : ProtocolCommand.ERROR);
            return new ProtocolMessage(responseCommand, message.getFileName(),
                    result.getContent() != null ? result.getContent() : result.getMessage());
        }
    }
}