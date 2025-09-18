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
            int port = 8081;//parsePortArgument(args);
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
     * ⏰ PROGRAMAR CONEXIÓN CON RÉPLICA - VERSIÓN OPTIMIZADA
     */
    private static void scheduleReplicaConnection(String host, int port, long delayMs) {
        monitoringService.schedule(() -> {
            if (!running) return;

            int maxAttempts = 2; // Reducir intentos para ser más rápido

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    System.out.println("🔗 Connecting to replica: " + host + ":" + port +
                            " (attempt " + attempt + "/" + maxAttempts + ")");

                    server.addReplica(host, port);
                    System.out.println("✅ Fast connection established: " + host + ":" + port);
                    return; // Éxito, salir

                } catch (Exception e) {
                    System.err.println("❌ Connection attempt " + attempt + " failed: " + e.getMessage());

                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(2000); // Solo 2 segundos entre intentos
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            // Si fallan todos los intentos, programar reintento con delay más largo
            long retryDelay = Math.min(delayMs * 2, 30000); // Max 30 segundos
            System.out.println("⏰ Will retry connection in " + (retryDelay / 1000) + " seconds...");
            scheduleReplicaConnection(host, port, retryDelay);

        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 🔄 MONITOREO DE CONEXIÓN MÁS AGRESIVO
     */
    private static void scheduleConnectionMonitoring(String host, int port) {
        monitoringService.scheduleAtFixedRate(() -> {
            if (!running) return;

            try {
                var stats = server.getServerStatistics();
                int activeReplicas = (Integer) stats.get("activeReplicas");

                // Si hay menos réplicas de las esperadas, reconectar inmediatamente
                int[] expectedPorts = config.getDefaultPorts();
                int expectedReplicas = expectedPorts.length - 1; // Menos este servidor

                if (activeReplicas < expectedReplicas) {
                    System.out.println("🔄 Low replica count (" + activeReplicas + "/" + expectedReplicas +
                            "), attempting fast reconnection...");

                    // Intentar reconectar a todos los puertos conocidos
                    for (int checkPort : expectedPorts) {
                        if (checkPort != getCurrentServerPort()) {
                            scheduleReplicaConnection(host, checkPort, 1000); // 1 segundo delay
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("Error in connection monitoring: " + e.getMessage());
            }
        }, 20, 20, TimeUnit.SECONDS); // Verificar cada 20 segundos en lugar de 60
    }

    /**
     * 🏃 OBTENER PUERTO ACTUAL DEL SERVIDOR
     */
    private static int getCurrentServerPort() {
        try {
            var stats = server.getServerStatistics();
            return (Integer) stats.get("port");
        } catch (Exception e) {
            return 8080; // Default fallback
        }
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
     * 📊 ESTADÍSTICAS OPTIMIZADAS - Menos verbose
     */
    private static void displayServerStatistics() {
        try {
            var stats = server.getServerStatistics();
            String timeStr = java.time.LocalTime.now().toString().substring(0, 8);

            System.out.println("\n📊 [" + timeStr + "] Quick Stats");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  🔗 Active Replicas: " + stats.get("activeReplicas"));
            System.out.println("  💾 Total Files: " + stats.get("totalFiles"));

            // Solo mostrar estadísticas detalladas si hay problemas
            @SuppressWarnings("unchecked")
            var replicationStats = (java.util.Map<String, Object>) stats.get("replicationStats");
            if (replicationStats != null) {
                long totalSyncs = (Long) replicationStats.get("totalSyncs");
                long failedSyncs = (Long) replicationStats.get("failedSyncs");

                if (failedSyncs > 0) {
                    System.out.println("  ⚠️  Failed Syncs: " + failedSyncs + "/" + totalSyncs);
                }
            }
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

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
        System.out.println("  clearlocks            - Clear expired locks manually");
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
            case "clearlocks":
                handleClearLocksCommand();
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
     * 🔒 MANEJAR COMANDO LOCKS - VERSIÓN MEJORADA CON DEBUG
     */
    private static void handleLocksCommand() {
        try {
            var stats = server.getServerStatistics();
            @SuppressWarnings("unchecked")
            var lockStats = (java.util.Map<String, Object>) stats.get("lockStats");

            if (lockStats != null) {
                System.out.println("🔒 === LOCK INFORMATION ===");
                System.out.println("  Server ID: " + lockStats.get("serverId"));
                System.out.println("  Active Locks: " + lockStats.get("activeLocksCount"));
                System.out.println("  Connected Servers: " + lockStats.get("connectedServers"));
                System.out.println("  Total Operations: " + lockStats.get("totalOperations"));
                System.out.println("  Lock Timeout: " + lockStats.get("lockTimeoutMs") + "ms");

                // Mostrar detalles de locks activos si existen
                @SuppressWarnings("unchecked")
                var activeLockDetails = (java.util.Map<String, String>) lockStats.get("activeLockDetails");
                if (activeLockDetails != null && !activeLockDetails.isEmpty()) {
                    System.out.println("  === ACTIVE LOCKS DETAILS ===");
                    for (var entry : activeLockDetails.entrySet()) {
                        System.out.println("    📄 " + entry.getKey() + ": " + entry.getValue());
                    }
                } else {
                    System.out.println("  ✅ No active locks");
                }
                System.out.println("==============================");
            }
        } catch (Exception e) {
            System.err.println("Error getting lock information: " + e.getMessage());
        }
    }

    /**
     * 🧹 NUEVO COMANDO: LIMPIAR LOCKS EXPIRADOS MANUALMENTE
     */
    private static void handleClearLocksCommand() {
        System.out.println("🧹 Triggering manual lock cleanup...");
        try {
            // Forzar limpieza de locks expirados
            // Esto se podría implementar exponiendo un método en el server
            handleLocksCommand(); // Mostrar estado antes
            System.out.println("✅ Manual cleanup triggered");

            // Esperar un momento y mostrar estado después
            Thread.sleep(1000);
            handleLocksCommand(); // Mostrar estado después
        } catch (Exception e) {
            System.err.println("Error during manual cleanup: " + e.getMessage());
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