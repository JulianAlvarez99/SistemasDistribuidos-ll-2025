import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to launch multiple backup servers for testing
 * This simulates a distributed environment with multiple backup nodes
 */
public class MultipleBackupLauncher {
    private final List<BackupServerInstance> backupInstances;
    private final ExecutorService executorService;

    public MultipleBackupLauncher() {
        this.backupInstances = new ArrayList<>();
        this.executorService = Executors.newCachedThreadPool();
    }

    public static void main(String[] args) {
        MultipleBackupLauncher launcher = new MultipleBackupLauncher();

        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(launcher::shutdown));

        // Default configuration: 1 backup servers
        int numBackups = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int startPort = args.length > 1 ? Integer.parseInt(args[1]) : 8081;

        System.out.println("=== MULTIPLE BACKUP SERVER LAUNCHER ===");
        System.out.println("Starting " + numBackups + " backup servers from port " + startPort);
        System.out.println("=======================================");

        launcher.startBackupServers(numBackups, startPort);

        // Keep main thread alive
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            System.out.println("Launcher interrupted, shutting down...");
            launcher.shutdown();
        }
    }

    public void startBackupServers(int count, int startPort) {
        for (int i = 0; i < count; i++) {
            int port = startPort + i;
            String storageDir = String.format("C:/Users/julia/Desktop/SistDistribuidos/backup_%d_storage", port);

            BackupServerInstance instance = new BackupServerInstance(port, storageDir);
            backupInstances.add(instance);
            int backupNum = i;
            // Start each backup server in its own thread
            executorService.submit(() -> {
                System.out.println("Starting Backup Server #" + (backupNum + 1) + " on port " + port);
                instance.start();
            });

            // Small delay to avoid port conflicts
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("All backup servers started successfully!");
    }

    public void shutdown() {
        System.out.println("\n=== SHUTTING DOWN ALL BACKUP SERVERS ===");

        // Stop all backup instances
        for (BackupServerInstance instance : backupInstances) {
            try {
                instance.stop();
            } catch (Exception e) {
                System.err.println("Error stopping backup instance: " + e.getMessage());
            }
        }

        // Shutdown executor service
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("All backup servers shut down");
    }

    /**
     * Wrapper class for individual backup server instances
     */
    private static class BackupServerInstance {
        private final BackupServer server;
        private final int port;
        private final String storageDir;

        public BackupServerInstance(int port, String storageDir) {
            this.port = port;
            this.storageDir = storageDir;
            this.server = new BackupServer(port, storageDir);
        }

        public void start() {
            try {
                server.start();
            } catch (Exception e) {
                System.err.println("Error starting backup server on port " + port + ": " + e.getMessage());
            }
        }

        public void stop() {
            try {
                server.stop();
                System.out.println("Backup server on port " + port + " stopped");
            } catch (Exception e) {
                System.err.println("Error stopping backup server on port " + port + ": " + e.getMessage());
            }
        }

        public int getPort() {
            return port;
        }

        public String getStorageDir() {
            return storageDir;
        }
    }
}