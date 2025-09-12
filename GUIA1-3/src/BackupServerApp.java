/**
 * Backup server startup application
 */
public class BackupServerApp {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        String storageDir = args.length > 1 ? args[1] : "C:/Users/julia/Desktop/SistDistribuidos/backup_storage";

        BackupServer server = new BackupServer(port, storageDir);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        System.out.println("Starting Backup Server on port " + port);
        server.start();
    }
}