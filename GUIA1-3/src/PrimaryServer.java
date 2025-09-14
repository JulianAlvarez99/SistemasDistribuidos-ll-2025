import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * PRIMARY SERVER - CON REPLICACIÓN HÍBRIDA
 * Combina socket + replicación directa de archivos
 */
public class PrimaryServer {
    private static final Logger LOGGER = Logger.getLogger(PrimaryServer.class.getName());
    private final int port;
    private final FileSystemManager fileManager;
    private final ExecutorService clientThreadPool;
    private final CopyOnWriteArrayList<BackupConnection> backupServers;
    private final AtomicInteger backupCounter = new AtomicInteger(0);
    private final HybridReplicationManager replicationManager;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private final ScheduledExecutorService syncScheduler;
    private final int SYNC_INTERVAL_SECONDS = 15;

    public PrimaryServer(int port, String storageDirectory) {
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.clientThreadPool = Executors.newCachedThreadPool();
        this.backupServers = new CopyOnWriteArrayList<>();
        this.syncScheduler = Executors.newScheduledThreadPool(2);
        this.running = false;

        // 🔧 Inicializar el manager de replicación híbrida
        this.replicationManager = new HybridReplicationManager(storageDirectory);
        setupBackupDirectories();
    }

    private void setupBackupDirectories() {
        // Configurar directorios de backup conocidos
        String baseDir = "C:/Users/julia/OneDrive/Desktop/RepoMaster";
        replicationManager.addBackupDirectory(baseDir + "/backup_8081_storage");
//        replicationManager.addBackupDirectory(baseDir + "/backup_8082_storage");

        LOGGER.info("Configured backup directories for direct file replication");
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;

            startPeriodicSync();
            startBackupHealthMonitoring();

            LOGGER.info("🚀 Primary server started on port " + port + " with HYBRID replication");

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

    private void startBackupHealthMonitoring() {
        syncScheduler.scheduleAtFixedRate(() -> {
            try {
                monitorBackupHealth();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during backup health monitoring", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void monitorBackupHealth() {
        backupServers.removeIf(backup -> {
            if (!backup.isConnected()) {
                LOGGER.warning("Removing disconnected backup server");
                backup.close();
                return true;
            }
            return false;
        });

        LOGGER.info("Active backup servers: " + backupServers.size());
    }

    public void startPeriodicSync() {
        syncScheduler.scheduleAtFixedRate(() -> {
            try {
                // Usar el hybrid manager para sincronización completa
                replicationManager.performFullSync();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during periodic sync", e);
            }
        }, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOGGER.info("Periodic hybrid synchronization started (every " + SYNC_INTERVAL_SECONDS + " seconds)");
    }

    /**
     *  Usa el HybridReplicationManager
     */
    private void replicateToBackups(ProtocolMessage originalMessage, String actualContent) {
        if (backupServers.isEmpty() && replicationManager == null) {
            LOGGER.info("No replication targets available");
            return;
        }

        LOGGER.info("🔄 Starting HYBRID replication for: " + originalMessage.getFileName() +
                " (operation: " + originalMessage.getCommand() + ")");

        try {
            // Usar el HybridReplicationManager para replicación confiable
            replicationManager.replicateFile(
                    originalMessage.getFileName(),
                    originalMessage.getCommand(),
                    actualContent
            );

            LOGGER.info("✅ Hybrid replication completed for: " + originalMessage.getFileName());

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Hybrid replication failed for: " + originalMessage.getFileName(), e);
        }
    }

    public void addBackupServer(String host, int port) {
        try {
            BackupConnection backup = new BackupConnection(host, port);
            if (backup.connect()) {
                backupServers.add(backup);

                // 🔧 Agregar al manager de replicación híbrida
                replicationManager.addBackupConnection(backup);

                int backupId = backupCounter.incrementAndGet();
                LOGGER.info("✅ Connected to backup server #" + backupId + ": " + host + ":" + port);

                // Trigger immediate sync
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        replicationManager.performFullSync();
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error during initial sync with new backup", e);
                    }
                });
            } else {
                LOGGER.warning("❌ Failed to connect to backup server: " + host + ":" + port);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to add backup server: " + host + ":" + port, e);
        }
    }

    public void stop() {
        running = false;

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
                    LOGGER.info("📨 Received: " + message.toDebugString());

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

        /**
         * PROCESAMIENTO CON REPLICACIÓN HÍBRIDA
         */
        private ProtocolMessage processMessage(ProtocolMessage message) {
            OperationResult result;

            LOGGER.info("🔄 Processing " + message.getCommand() + " for file: " + message.getFileName() +
                    " from client: " + message.getClientId());

            switch (message.getCommand()) {
                case WRITE:
                    // 1. Escribir en el primary
                    result = fileManager.writeFile(message.getFileName(), message.getContent());

                    if (result.isSuccess()) {
                        // 2. Leer el contenido completo para replicación
                        OperationResult readResult = fileManager.readFile(message.getFileName());
                        if (readResult.isSuccess()) {
                            String completeContent = readResult.getContent();
                            LOGGER.info("📖 Read complete file for replication: " +
                                    message.getFileName() + " (" + completeContent.length() + " chars)");

                            // 3.  NUEVA REPLICACIÓN HÍBRIDA
                            replicateToBackups(message, completeContent);

                            LOGGER.info("✅ Write + hybrid replication completed for: " + message.getFileName());
                        } else {
                            LOGGER.warning("❌ Could not read file for replication: " + readResult.getMessage());
                        }
                    } else {
                        LOGGER.warning("❌ Primary write failed: " + result.getMessage());
                    }
                    break;

                case READ:
                    result = fileManager.readFile(message.getFileName());
                    LOGGER.info("📖 Read completed: " + message.getFileName());
                    break;

                case DELETE:
                    result = fileManager.deleteFile(message.getFileName());
                    if (result.isSuccess()) {
                        // 🔧 REPLICACIÓN HÍBRIDA PARA DELETE
                        replicateToBackups(message, "");
                        LOGGER.info("🗑️ Delete + hybrid replication completed: " + message.getFileName());
                    }
                    break;

                case LIST:
                    result = fileManager.listFiles();
                    LOGGER.info("📋 List completed");
                    break;

                default:
                    result = new OperationResult(false, "Unknown command: " + message.getCommand());
                    LOGGER.warning("❓ Unknown command: " + message.getCommand());
            }

            ProtocolCommand responseCommand = result.isSuccess() ? ProtocolCommand.SUCCESS :
                    (result.getMessage().contains("not found") ? ProtocolCommand.NOT_FOUND : ProtocolCommand.ERROR);

            return new ProtocolMessage(responseCommand, message.getFileName(),
                    result.getContent() != null ? result.getContent() : result.getMessage());
        }
    }
}