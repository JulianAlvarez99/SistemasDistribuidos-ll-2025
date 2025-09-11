import java.net.*;
import java.io.*;
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

    public PrimaryServer(int port, String storageDirectory) {
        this.port = port;
        this.fileManager = new FileSystemManager(storageDirectory);
        this.clientThreadPool = Executors.newCachedThreadPool();
        this.backupServers = new CopyOnWriteArrayList<>();
        this.running = false;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
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

    public void stop() {
        running = false;
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

    private void replicateToBackups(ProtocolMessage message) {
        ProtocolMessage replicationMessage = new ProtocolMessage(ProtocolCommand.REPLICATE,
                message.getFileName(),
                message.getContent());

        for (BackupConnection backup : backupServers) {
            CompletableFuture.runAsync(() -> backup.sendMessage(replicationMessage));
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

            switch (message.getCommand()) {
                case WRITE:
                    result = fileManager.writeFile(message.getFileName(), message.getContent());
                    if (result.isSuccess()) {
                        replicateToBackups(message);
                    }
                    break;

                case READ:
                    result = fileManager.readFile(message.getFileName());
                    break;

                case DELETE:
                    result = fileManager.deleteFile(message.getFileName());
                    if (result.isSuccess()) {
                        replicateToBackups(message);
                    }
                    break;

                default:
                    result = new OperationResult(false, "Unknown command");
            }

            ProtocolCommand responseCommand = result.isSuccess() ? ProtocolCommand.SUCCESS : ProtocolCommand.ERROR;
            return new ProtocolMessage(responseCommand, message.getFileName(),
                    result.getContent() != null ? result.getContent() : result.getMessage());
        }
    }
}