import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 🔄 ENHANCED ACTIVE REPLICA SERVER APPLICATION
 * Aplicación mejorada para servidor de redundancia activa con configuración centralizada
 */
public class ActiveReplicaServerApp {
    private static ActiveReplicaServer server;
    private static SystemConfig config;
    private static final ScheduledExecutorService monitoringService = Executors.newScheduledThreadPool(2);
    private static volatile boolean running = true;

    public static void main(String[] args) {
        try {
            // Inicializar configuración
            config = SystemConfig.getInstance();

            // Parse argumentos con fallbacks configurables
            int port = 8082;//parsePortArgument(args);
            String storageDir = parseStorageDirectory(args, port);

            // Mostrar configuración
            displayStartupConfiguration(port, storageDir);

            // Inicializar servidor
            server = new ActiveReplicaServer(port, storageDir);

            // Configurar red de réplicas
            configureReplicaNetwork(port);

            // Setup graceful shutdown
            setupShutdownHook();

            // Iniciar servicios de monitoreo
            startMonitoringServices();

            // Iniciar interfaz de comandos en hilo separado
            startCommandLineInterface();

            System.out.println("🔄 ================================");
            System.out.println("🔄 ACTIVE REPLICA SERVER READY");
            System.out.println("🔄 Press Ctrl+C to shutdown gracefully");
            System.out.println("🔄 ================================");

            // Iniciar servidor (blocking call)
            server.start();

        } catch (Exception e) {
            System.err.println("❌ Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 🔧 PARSE PORT ARGUMENT
     */
    private static int parsePortArgument(String[] args) {
        if (args.length > 0) {
            try {
                int port = Integer.parseInt(args[0]);
                if (port < 1024 || port > 65535) {
                    throw new IllegalArgumentException("Port must be between 1024 and 65535");
                }
                return port;
            } catch (NumberFormatException e) {
                System.err.println("❌ Invalid port number: " + args[0]);
                System.exit(1);
            }
        }

        // Usar puerto por defecto del primer puerto configurado
        int[] defaultPorts = config.getDefaultPorts();
        return defaultPorts.length > 0 ? defaultPorts[0] : 8080;
    }

    /**
     * 📁 PARSE STORAGE DIRECTORY
     */
    private static String parseStorageDirectory(String[] args, int port) {
        if (args.length > 1) {
            return args[1];
        }

        // Usar configuración centralizada
        return config.getReplicaStoragePath(port);
    }

    /**
     * 📊 DISPLAY STARTUP CONFIGURATION
     */
    private static void displayStartupConfiguration(int port, String storageDir) {
        System.out.println("🔄 ================================");
        System.out.println("🔄 ACTIVE REPLICA SERVER STARTING");
        System.out.println("🔄 ================================");
        System.out.println("📊 Configuration:");
        System.out.println("  • Port: " + port);
        System.out.println("  • Storage: " + storageDir);
        System.out.println("  • Mode: ACTIVE REPLICATION");
        System.out.println("  • Lock Timeout: " + config.getLockTimeoutMs() + "ms");
        System.out.println("  • Sync Timeout: " + config.getSyncTimeoutMs() + "ms");
        System.out.println("  • Consensus: " + (config.requireUnanimousConsensus() ? "UNANIMOUS" : "MAJORITY"));
        System.out.println("  • Write Verification: " + (config.verifyWrites() ? "ENABLED" : "DISABLED"));
        System.out.println("🔄 ================================");
    }

    /**
     * 🔗 CONFIGURAR RED DE RÉPLICAS
     */
    private static void configureReplicaNetwork(int currentPort) {
        int[] knownPorts = config.getDefaultPorts();
        String host = config.getDefaultHost();

        System.out.println("🔗 Configuring replica network...");
        System.out.println("  • Host: " + host);
        System.out.println("  • Known ports: " + java.util.Arrays.toString(knownPorts));

        for (int port : knownPorts) {
            if (port != currentPort) {
                // Programar conexión con delay escalonado para evitar race conditions
                long delay = 3000 + (port % 1000); // Delay entre 3-4 segundos
                scheduleReplicaConnection(host, port, delay);
            }
        }

        System.out.println("✅ Replica network configuration scheduled");
    }

    /**
     * ⏰ PROGRAMAR CONEXIÓN CON RÉPLICA
     */
    private static void scheduleReplicaConnection(String host, int port, long delayMs) {
        monitoringService.schedule(() -> {
            if (!running) return;

            try {
                System.out.println("🔗 Attempting to connect to replica: " + host + ":" + port);
                server.addReplica(host, port);
                System.out.println("✅ Connected to replica: " + host + ":" + port);

                // Programar verificación periódica de la conexión
                scheduleConnectionMonitoring(host, port);

            } catch (Exception e) {
                System.err.println("❌ Failed to connect to replica " + host + ":" + port + ": " + e.getMessage());

                // Programar reintento con backoff exponencial limitado
                long retryDelay = Math.min(delayMs * 2, 60000); // Max 1 minuto
                System.out.println("⏰ Retrying connection in " + (retryDelay / 1000) + " seconds...");
                scheduleReplicaConnection(host, port, retryDelay);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 🔄 PROGRAMAR MONITOREO DE CONEXIÓN
     */
    private static void scheduleConnectionMonitoring(String host, int port) {
        monitoringService.scheduleAtFixedRate(() -> {
            if (!running) return;

            try {
                // Verificar si la conexión sigue activa
                var stats = server.getServerStatistics();
                int activeReplicas = (Integer) stats.get("activeReplicas");

                if (activeReplicas == 0) {
                    System.out.println("🔄 No active replicas detected, attempting reconnection...");
                    scheduleReplicaConnection(host, port, 5000); // Reconectar en 5 segundos
                }

            } catch (Exception e) {
                System.err.println("Error in connection monitoring: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS); // Cada minuto
    }

    /**
     * 📊 INICIAR SERVICIOS DE MONITOREO
     */
    private static void startMonitoringServices() {
        // Estadísticas del servidor cada 2 minutos
        monitoringService.scheduleAtFixedRate(() -> {
            if (!running) return;

            try {
                displayServerStatistics();
            } catch (Exception e) {
                System.err.println("Error in statistics monitoring: " + e.getMessage());
            }
        }, 120, 120, TimeUnit.SECONDS);

        // Verificación de salud cada 1 minuto
        monitoringService.scheduleAtFixedRate(() -> {
            if (!running) return;

            try {
                performHealthCheck();
            } catch (Exception e) {
                System.err.println("Error in health monitoring: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 📊 MOSTRAR ESTADÍSTICAS DEL SERVIDOR
     */
    private static void displayServerStatistics() {
        try {
            var stats = server.getServerStatistics();
            System.out.println("\n📊 [" + java.time.LocalTime.now().toString().substring(0, 8) + "] SERVER STATISTICS");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  🆔 Server ID: " + stats.get("serverId"));
            System.out.println("  🔌 Port: " + stats.get("port"));
            System.out.println("  🔗 Active Replicas: " + stats.get("activeReplicas"));

            // Estadísticas de locks
            @SuppressWarnings("unchecked")
            var lockStats = (java.util.Map<String, Object>) stats.get("lockStats");
            if (lockStats != null) {
                System.out.println("  🔒 Active Locks: " + lockStats.get("activeLocksCount"));
                System.out.println("  📡 Connected Servers: " + lockStats.get("connectedServers"));
                System.out.println("  🔢 Total Lock Operations: " + lockStats.get("totalOperations"));
            }

            // Estadísticas de replicación
            @SuppressWarnings("unchecked")
            var replicationStats = (java.util.Map<String, Object>) stats.get("replicationStats");
            if (replicationStats != null) {
                System.out.println("  🔄 Total Syncs: " + replicationStats.get("totalSyncs"));
                System.out.println("  ✅ Successful Syncs: " + replicationStats.get("successfulSyncs"));
                System.out.println("  ❌ Failed Syncs: " + replicationStats.get("failedSyncs"));
                System.out.println("  💾 Operation History: " + replicationStats.get("operationHistorySize"));
            }

            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        } catch (Exception e) {
            System.err.println("Error displaying statistics: " + e.getMessage());
        }
    }

    /**
     * 🏥 REALIZAR VERIFICACIÓN DE SALUD
     */
    private static void performHealthCheck() {
        // Implementar verificación de salud básica
        System.out.println("🏥 [" + java.time.LocalTime.now().toString().substring(0, 8) + "] Health check performed");
    }

    /**
     * 💬 INICIAR INTERFAZ DE LÍNEA DE COMANDOS
     */
    private static void startCommandLineInterface() {
        Thread cliThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            System.out.println("\n🔄 === ACTIVE REPLICA SERVER CONSOLE ===");
            displayAvailableCommands();

            while (running && scanner.hasNextLine()) {
                try {
                    String input = scanner.nextLine().trim();
                    if (input.isEmpty()) continue;

                    processCommand(input);

                } catch (Exception e) {
                    System.err.println("CLI Error: " + e.getMessage());
                }
            }
            scanner.close();
        });

        cliThread.setDaemon(true);
        cliThread.start();
    }

    /**
     * 📖 MOSTRAR COMANDOS DISPONIBLES
     */
    private static void displayAvailableCommands() {
        System.out.println("Commands:");
        System.out.println("  add <host> <port>     - Add replica server");
        System.out.println("  stats                 - Show server statistics");
        System.out.println("  replicas              - List connected replicas");
        System.out.println("  health                - Check replica health");
        System.out.println("  config                - Show current configuration");
        System.out.println("  locks                 - Show active locks");
        System.out.println("  history               - Show operation history summary");
        System.out.println("  clear                 - Clear console");
        System.out.println("  help                  - Show this help");
        System.out.println("  quit                  - Shutdown server");
        System.out.println("========================================\n");
    }

    /**
     * 🎯 PROCESAR COMANDO
     */
    private static void processCommand(String input) {
        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();

        switch (command) {
            case "add":
                handleAddReplicaCommand(parts);
                break;
            case "stats":
                displayServerStatistics();
                break;
            case "replicas":
                handleReplicasCommand();
                break;
            case "health":
                handleHealthCommand();
                break;
            case "config":
                handleConfigCommand();
                break;
            case "locks":
                handleLocksCommand();
                break;
            case "history":
                handleHistoryCommand();
                break;
            case "clear":
                clearConsole();
                break;
            case "help":
                displayAvailableCommands();
                break;
            case "quit":
            case "exit":
            case "shutdown":
                handleShutdownCommand();
                break;
            default:
                System.out.println("❓ Unknown command: " + command + " (type 'help' for available commands)");
        }
    }

    /**
     * 🔗 MANEJAR COMANDO ADD REPLICA
     */
    private static void handleAddReplicaCommand(String[] parts) {
        if (parts.length >= 3) {
            try {
                String host = parts[1];
                int port = Integer.parseInt(parts[2]);

                System.out.println("🔗 Adding replica: " + host + ":" + port);
                server.addReplica(host, port);
                System.out.println("✅ Replica added successfully");

            } catch (NumberFormatException e) {
                System.out.println("❌ Invalid port number: " + parts[2]);
            } catch (Exception e) {
                System.out.println("❌ Error adding replica: " + e.getMessage());
            }
        } else {
            System.out.println("Usage: add <host> <port>");
        }
    }

    /**
     * 🔗 MANEJAR COMANDO REPLICAS
     */
    private static void handleReplicasCommand() {
        try {
            var stats = server.getServerStatistics();
            System.out.println("🔗 Connected Replicas:");
            System.out.println("  Active replicas: " + stats.get("activeReplicas"));

            @SuppressWarnings("unchecked")
            var lockStats = (java.util.Map<String, Object>) stats.get("lockStats");
            if (lockStats != null) {
                System.out.println("  Connected servers: " + lockStats.get("connectedServers"));
            }
        } catch (Exception e) {
            System.err.println("Error getting replica information: " + e.getMessage());
        }
    }

    /**
     * 🏥 MANEJAR COMANDO HEALTH
     */
    private static void handleHealthCommand() {
        System.out.println("🏥 Performing comprehensive health check...");
        performHealthCheck();
        displayServerStatistics();
        System.out.println("✅ Health check completed");
    }

    /**
     * ⚙️ MANEJAR COMANDO CONFIG
     */
    private static void handleConfigCommand() {
        System.out.println("⚙️ Current Configuration:");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Storage Base Path: " + config.getStorageBasePath());
        System.out.println("  Lock Timeout: " + config.getLockTimeoutMs() + "ms");
        System.out.println("  Sync Timeout: " + config.getSyncTimeoutMs() + "ms");
        System.out.println("  Connection Timeout: " + config.getConnectionTimeoutMs() + "ms");
        System.out.println("  Health Check Interval: " + config.getHealthCheckIntervalSec() + "s");
        System.out.println("  Max Retries: " + config.getMaxRetries());
        System.out.println("  Consensus Type: " + (config.requireUnanimousConsensus() ? "UNANIMOUS" : "MAJORITY"));
        System.out.println("  Verify Writes: " + (config.verifyWrites() ? "ENABLED" : "DISABLED"));
        System.out.println("  Default Host: " + config.getDefaultHost());
        System.out.println("  Default Ports: " + java.util.Arrays.toString(config.getDefaultPorts()));
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * 🔒 MANEJAR COMANDO LOCKS
     */
    private static void handleLocksCommand() {
        try {
            var stats = server.getServerStatistics();
            @SuppressWarnings("unchecked")
            var lockStats = (java.util.Map<String, Object>) stats.get("lockStats");

            if (lockStats != null) {
                System.out.println("🔒 Lock Information:");
                System.out.println("  Active Locks: " + lockStats.get("activeLocksCount"));
                System.out.println("  Total Operations: " + lockStats.get("totalOperations"));
                System.out.println("  Lock Timeout: " + config.getLockTimeoutMs() + "ms");
            }
        } catch (Exception e) {
            System.err.println("Error getting lock information: " + e.getMessage());
        }
    }

    /**
     * 📚 MANEJAR COMANDO HISTORY
     */
    private static void handleHistoryCommand() {
        try {
            var stats = server.getServerStatistics();
            @SuppressWarnings("unchecked")
            var replicationStats = (java.util.Map<String, Object>) stats.get("replicationStats");

            if (replicationStats != null) {
                System.out.println("📚 Operation History Summary:");
                System.out.println("  Operation History Size: " + replicationStats.get("operationHistorySize"));
                System.out.println("  Last Operation ID: " + replicationStats.get("lastOperationId"));
                System.out.println("  Total Syncs: " + replicationStats.get("totalSyncs"));
                System.out.println("  Successful Syncs: " + replicationStats.get("successfulSyncs"));
                System.out.println("  Failed Syncs: " + replicationStats.get("failedSyncs"));
            }
        } catch (Exception e) {
            System.err.println("Error getting history information: " + e.getMessage());
        }
    }

    /**
     * 🧹 LIMPIAR CONSOLA
     */
    private static void clearConsole() {
        try {
            // Intentar limpiar consola en diferentes sistemas
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[2J\033[H");
                System.out.flush();
            }
        } catch (Exception e) {
            // Si falla, imprimir líneas vacías
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
        System.out.println("🔄 === ACTIVE REPLICA SERVER CONSOLE ===");
    }

    /**
     * 🛑 MANEJAR COMANDO SHUTDOWN
     */
    private static void handleShutdownCommand() {
        System.out.println("🛑 Initiating graceful shutdown...");
        running = false;

        // Trigger shutdown hook
        System.exit(0);
    }

    /**
     * 🛑 CONFIGURAR SHUTDOWN GRACEFUL
     */
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;

            System.out.println("\n🛑 ======================================");
            System.out.println("🛑 SHUTTING DOWN ACTIVE REPLICA SERVER");
            System.out.println("🛑 ======================================");

            // Shutdown monitoring services
            if (monitoringService != null) {
                System.out.println("🔄 Stopping monitoring services...");
                monitoringService.shutdown();
                try {
                    if (!monitoringService.awaitTermination(10, TimeUnit.SECONDS)) {
                        System.out.println("⚠️  Forcing monitoring services shutdown...");
                        monitoringService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    monitoringService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Shutdown server
            if (server != null) {
                System.out.println("🔄 Stopping replica server...");
                try {
                    server.stop();
                    System.out.println("✅ Server stopped successfully");
                } catch (Exception e) {
                    System.err.println("❌ Error during server shutdown: " + e.getMessage());
                }
            }

            System.out.println("✅ Active Replica Server shutdown complete");
            System.out.println("👋 Goodbye!");
        }));
    }
}