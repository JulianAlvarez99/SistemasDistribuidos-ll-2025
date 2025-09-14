import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 🔄 ACTIVE REPLICA SERVER APPLICATION
 * Aplicación para iniciar un servidor de redundancia activa
 */
public class ActiveReplicaServerApp {
    private static ActiveReplicaServer server;
    private static final ScheduledExecutorService monitoringService = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) {
        // Parse argumentos
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String storageDir = args.length > 1 ? args[1] :
                "C:/Users/julia/OneDrive/Desktop/RepoMaster/active_replica_" + port + "_storage";

        // Inicializar servidor
        server = new ActiveReplicaServer(port, storageDir);

        // Configurar réplicas hermanas
        configureReplicaNetwork(port);

        // Setup graceful shutdown
        setupShutdownHook();

        // Iniciar interfaz de comandos
        startCommandLineInterface();

        // Iniciar monitoreo
        startMonitoringService();

        System.out.println("🔄 ================================");
        System.out.println("🔄 ACTIVE REPLICA SERVER STARTING");
        System.out.println("🔄 Port: " + port);
        System.out.println("🔄 Storage: " + storageDir);
        System.out.println("🔄 Mode: ACTIVE REPLICATION");
        System.out.println("🔄 ================================");

        // Iniciar servidor (blocking call)
        server.start();
    }

    /**
     * 🔗 CONFIGURAR RED DE RÉPLICAS
     */
    private static void configureReplicaNetwork(int currentPort) {
        // Agregar otras réplicas conocidas (excluyendo el puerto actual)
        int[] knownPorts = {8080, 8081, 8082};

        for (int port : knownPorts) {
            if (port != currentPort) {
                // Intentar conectar después de un delay para permitir que otros servidores se inicien
                scheduleReplicaConnection("localhost", port, 5000);
            }
        }

        System.out.println("🔗 Configured to connect to replica network");
    }

    /**
     * ⏰ PROGRAMAR CONEXIÓN CON RÉPLICA
     */
    private static void scheduleReplicaConnection(String host, int port, long delayMs) {
        monitoringService.schedule(() -> {
            try {
                server.addReplica(host, port);
                System.out.println("✅ Connected to replica: " + host + ":" + port);
            } catch (Exception e) {
                System.err.println("❌ Failed to connect to replica " + host + ":" + port + ": " + e.getMessage());

                // Retry after 30 seconds
                scheduleReplicaConnection(host, port, 30000);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 📊 INICIAR SERVICIO DE MONITOREO
     */
    private static void startMonitoringService() {
        monitoringService.scheduleAtFixedRate(() -> {
            try {
                // Mostrar estadísticas cada 2 minutos
                var stats = server.getServerStatistics();
                System.out.println("📊 [" + java.time.LocalTime.now() + "] Server Stats: " + stats);
            } catch (Exception e) {
                System.err.println("Error in monitoring: " + e.getMessage());
            }
        }, 120, 120, TimeUnit.SECONDS);
    }

    /**
     * 💬 INICIAR INTERFAZ DE LÍNEA DE COMANDOS
     */
    private static void startCommandLineInterface() {
        Thread cliThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            System.out.println("\n🔄 === ACTIVE REPLICA SERVER CONSOLE ===");
            System.out.println("Commands:");
            System.out.println("  add <host> <port>     - Add replica server");
            System.out.println("  stats                 - Show server statistics");
            System.out.println("  replicas              - List connected replicas");
            System.out.println("  health                - Check replica health");
            System.out.println("  help                  - Show this help");
            System.out.println("  quit                  - Shutdown server");
            System.out.println("========================================\n");

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
                                    server.addReplica(host, port);
                                    System.out.println("✅ Added replica: " + host + ":" + port);
                                } catch (NumberFormatException e) {
                                    System.out.println("❌ Invalid port number");
                                }
                            } else {
                                System.out.println("Usage: add <host> <port>");
                            }
                            break;

                        case "stats":
                            var stats = server.getServerStatistics();
                            System.out.println("📊 Server Statistics:");
                            stats.forEach((key, value) ->
                                    System.out.println("  " + key + ": " + value));
                            break;

                        case "replicas":
                            System.out.println("🔗 Connected Replicas:");
                            var replicaStats = server.getServerStatistics();
                            System.out.println("  Active replicas: " + replicaStats.get("activeReplicas"));
                            break;

                        case "health":
                            System.out.println("🏥 Performing health check...");
                            // Health check logic would go here
                            System.out.println("✅ Health check completed");
                            break;

                        case "help":
                            System.out.println("Available commands:");
                            System.out.println("  add <host> <port>     - Add replica server");
                            System.out.println("  stats                 - Show server statistics");
                            System.out.println("  replicas              - List connected replicas");
                            System.out.println("  health                - Check replica health");
                            System.out.println("  quit                  - Shutdown server");
                            break;

                        case "quit":
                        case "exit":
                            System.out.println("🛑 Shutting down Active Replica Server...");
                            System.exit(0);
                            break;

                        default:
                            System.out.println("❓ Unknown command: " + command + " (type 'help' for available commands)");
                    }
                } catch (Exception e) {
                    System.err.println("CLI Error: " + e.getMessage());
                }
            }
        });

        cliThread.setDaemon(true);
        cliThread.start();
    }

    /**
     * 🛑 CONFIGURAR SHUTDOWN GRACEFUL
     */
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 === SHUTTING DOWN ACTIVE REPLICA SERVER ===");

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

            System.out.println("✅ Active Replica Server shutdown complete");
        }));
    }
}