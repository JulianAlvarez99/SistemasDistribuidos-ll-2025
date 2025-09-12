/**
 * Primary server startup application
 */
public class PrimaryServerApp {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String storageDir = args.length > 1 ? args[1] : "C:/Users/julia/Desktop/SistDistribuidos/primary_storage";

        PrimaryServer server = new PrimaryServer(port, storageDir);

//        // Add backup servers if specified
//        if (args.length > 3) {
//            String backupHost = args[2];
//            int backupPort = Integer.parseInt(args[3]);
//            server.addBackupServer(backupHost, backupPort);
//        }
        server.addBackupServer("localhost", 8081);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        System.out.println("Starting Primary Server on port " + port);
        server.start();
    }
}