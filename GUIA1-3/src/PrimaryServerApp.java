import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ENHANCED Primary server startup application
 * Supports multiple backup servers and runtime management
 */
public class PrimaryServerApp {
    private static PrimaryServer server;
    private static final ScheduledExecutorService monitoringService = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) {
        // Parse command line arguments
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String storageDir = args.length > 1 ? args[1] : "C:/Users/julia/Desktop/SistDistribuidos/primary_storage";

        // Initialize primary server
        server = new PrimaryServer(port, storageDir);

        // Add default backup servers
        addDefaultBackupServers();

        // Add runtime backup servers from command line
        if (args.length > 2) {
            addBackupServersFromArgs(args, 1);
        }

        // Start monitoring service
        startMonitoringService();

        // Setup graceful shutdown
        setupShutdownHook();

        // Start command line interface in separate thread
        startCommandLineInterface(args);

        System.out.println("=================================");
        System.out.println("PRIMARY SERVER STARTING");
        System.out.println("Port: " + port);
        System.out.println("Storage: " + storageDir);
        System.out.println("=================================");

        // Start the server (blocking call)
        server.start();
    }

    private static void addDefaultBackupServers() {
        // Add standard backup servers
        server.addBackupServer("localhost", 8081);
//        server.addBackupServer("localhost", 8082); // Second backup

        System.out.println("Added default backup servers: localhost:8081, localhost:8082");
    }

    private static void addBackupServersFromArgs(String[] args, int startIndex) {
        // Parse backup servers from command line: host1:port1 host2:port2 ...
        for (int i = startIndex; i < args.length; i++) {
            String[] hostPort = args[i].split(":");
            if (hostPort.length == 2) {
                try {
                    String host = hostPort[0];
                    int port = Integer.parseInt(hostPort[1]);
                    server.addBackupServer(host, port);
                    System.out.println("Added backup server from args: " + host + ":" + port);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid backup server format: " + args[i] + " (expected host:port)");
                }
            }
        }
    }

    private static void startMonitoringService() {
        monitoringService.scheduleAtFixedRate(() -> {
            try {
                // This could be extended to show server statistics
                System.out.println("[MONITOR] " + java.time.LocalTime.now() + " - Primary server running");
            } catch (Exception e) {
                System.err.println("Monitoring error: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS); // Every minute
    }

    private static void startCommandLineInterface(String[] args) {
        Thread cliThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            System.out.println("\n=== PRIMARY SERVER CONSOLE ===");
            System.out.println("Commands:");
            System.out.println("  add <host> <port>  - Add backup server");
            System.out.println("  sync               - Force full sync");
            System.out.println("  status             - Show server status");
            System.out.println("  help               - Show this help");
            System.out.println("  quit               - Shutdown server");
            System.out.println("===============================\n");

            while (server != null && scanner.hasNextLine()) {
                try {
                    String input = scanner.nextLine().trim();
                    if (input.isEmpty()) continue;

                    String[] parts = input.split("\\s+");
                    String command = parts[0].toLowerCase();

                    switch (command) {
                        case "add":
                            if (parts.length >= 3) {
                                try {
                                    String host = parts[1];
                                    int port = Integer.parseInt(parts[2]);
                                    server.addBackupServer(host, port);
                                    System.out.println("Added backup server: " + host + ":" + port);
                                } catch (NumberFormatException e) {
                                    System.out.println("Error: Invalid port number");
                                }
                            } else {
                                System.out.println("Usage: add <host> <port>");
                            }
                            break;

                        case "sync":
                            System.out.println("Triggering full synchronization...");
                            new Thread(() -> server.startPeriodicSync()).start();
                            break;

                        case "status":
                            System.out.println("Primary server is running");
                            String msg = args.length > 1 ? args[1] : "default";
                            System.out.println("Storage directory: " + msg);
                            break;

                        case "help":
                            System.out.println("Available commands:");
                            System.out.println("  add <host> <port>  - Add backup server");
                            System.out.println("  sync               - Force full sync");
                            System.out.println("  status             - Show server status");
                            System.out.println("  quit               - Shutdown server");
                            break;

                        case "quit":
                        case "exit":
                            System.out.println("Shutting down primary server...");
                            System.exit(0);
                            break;

                        default:
                            System.out.println("Unknown command: " + command + " (type 'help' for available commands)");
                    }
                } catch (Exception e) {
                    System.err.println("CLI Error: " + e.getMessage());
                }
            }
        });

        cliThread.setDaemon(true);
        cliThread.start();
    }

    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== SHUTTING DOWN PRIMARY SERVER ===");

            if (monitoringService != null) {
                monitoringService.shutdown();
                try {
                    if (!monitoringService.awaitTermination(5, TimeUnit.SECONDS)) {
                        monitoringService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    monitoringService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            if (server != null) {
                server.stop();
            }

            System.out.println("Primary server shutdown complete");
        }));
    }
}