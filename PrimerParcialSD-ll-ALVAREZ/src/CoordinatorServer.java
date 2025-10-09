import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class CoordinatorServer {

    private static final int PORT = 9090;
    private final List<ClientHandler> clientHandlers = new CopyOnWriteArrayList<>();
    private final List<String> itemList = new ArrayList<>();
    private final Map<Integer, String> messageHistory = new ConcurrentHashMap<>();
    private final AtomicInteger messageCounter = new AtomicInteger(0);

    public static void main(String[] args) {
        new CoordinatorServer().startServer();
    }

    public void startServer() {
        System.out.println("Coordinator Server started on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                clientHandlers.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }

    private void broadcastMessage(String message) {
        String encryptedMessage = CryptoUtils.encrypt(message);
        for (ClientHandler client : clientHandlers) {
            client.sendMessage(encryptedMessage);
        }
    }

    public synchronized void addNewItem(String item) {
        itemList.add(item);
        int sequenceNumber = messageCounter.incrementAndGet();
        String message = "UPDATE_LIST|" + sequenceNumber + "|" + item;
        messageHistory.put(sequenceNumber, message);
        broadcastMessage(message);
        System.out.println("Broadcasting: " + message);
    }

    public synchronized String getInitialListState(int lastKnownSequence) {
        int currentSequence = messageCounter.get();
        if (currentSequence == 0) {
            return "INITIAL_LIST|" + currentSequence + "|";
        }
        if (lastKnownSequence >= currentSequence) {
            return null; // Client is up to date
        }

        // For a new client, lastKnownSequence is 0, send the full list as a single message.
        if (lastKnownSequence == 0) {
            return "INITIAL_LIST|" + currentSequence + "|" + String.join(",", itemList);
        }

        // For sync request, send missed messages
        for (int i = lastKnownSequence + 1; i <= currentSequence; i++) {
            if(messageHistory.containsKey(i)){
                String message = messageHistory.get(i);
                // The client handler will send this message
                // This logic is handled inside ClientHandler to send to a specific client
            }
        }
        return null;
    }

    public void removeClient(ClientHandler clientHandler) {
        clientHandlers.remove(clientHandler);
        System.out.println("Client disconnected: " + clientHandler.getClientInfo() + ". Remaining clients: " + clientHandlers.size());

        // **MODIFICATION START**
        // If the last client has disconnected, reset the server state.
        if (clientHandlers.isEmpty()) {
            resetServerState();
        }
        // **MODIFICATION END**
    }

    // **NEW METHOD**
    private synchronized void resetServerState() {
        System.out.println("Last client disconnected. Resetting server state.");
        itemList.clear();
        messageHistory.clear();
        messageCounter.set(0);
        System.out.println("Server state has been wiped. Waiting for new connections to start a new list.");
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final CoordinatorServer server;
        private PrintWriter out;
        private BufferedReader in;
        private String clientInfo = "New Client";

        public ClientHandler(Socket socket, CoordinatorServer server) {
            this.clientSocket = socket;
            this.server = server;
        }

        public String getClientInfo() {
            return clientInfo;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                String encryptedRequest;
                while ((encryptedRequest = in.readLine()) != null) {
                    String request = CryptoUtils.decrypt(encryptedRequest);
                    if (request == null) continue;

                    System.out.println("Received from " + clientInfo + ": " + request);
                    String[] parts = request.split("\\|", 2);
                    String command = parts[0];

                    switch (command) {
                        case "CONNECT":
                            this.clientInfo = parts[1];
                            System.out.println(clientInfo + " connected. Total clients: " + server.clientHandlers.size());
                            String initialState = server.getInitialListState(0);
                            if (initialState != null) {
                                sendMessage(CryptoUtils.encrypt(initialState));
                            }
                            break;
                        case "ADD_ITEM":
                            // Format: ADD_ITEM|CONSISTENCY_MODE|DELAY|ITEM_TEXT
                            server.addNewItem(request.split("\\|",4)[3]);
                            break;
                        case "SYNC_REQUEST":
                            int lastKnownSeq = Integer.parseInt(parts[1]);
                            resendMissedMessages(lastKnownSeq);
                            break;
                    }
                }
            } catch (IOException e) {

            } finally {
                server.removeClient(this);
                try {
                    clientSocket.close();
                } catch (IOException e) {

                }
            }
        }

        private void resendMissedMessages(int lastKnownSeq) {
            System.out.println("Client " + clientInfo + " requested sync from sequence " + lastKnownSeq);
            for (int i = lastKnownSeq + 1; i <= server.messageCounter.get(); i++) {
                if(server.messageHistory.containsKey(i)){
                    String message = server.messageHistory.get(i);
                    sendMessage(CryptoUtils.encrypt(message));
                    System.out.println("Resending to " + clientInfo + ": " + message);
                }
            }
        }

        public void sendMessage(String message) {
            if (out != null && !out.checkError()) {
                out.println(message);
            }
        }
    }
}