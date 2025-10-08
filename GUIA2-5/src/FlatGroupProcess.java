import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlatGroupProcess extends JFrame {
    private final int processId;
    private final int clientPort;
    private final int internalPort;
    private final Set<ProcessInfo> groupMembers = ConcurrentHashMap.newKeySet();
    private final List<ProcessInfo> bootstrapList = new ArrayList<>();

    private ServerSocket clientSocket;
    private ServerSocket internalSocket;
    private ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random random = new Random();

    // Fault injection rates
    private int connectionFailureRate = 20;
    private int responseDelayRate = 15;
    private int incorrectResponseRate = 10;
    private int baseDelayMs = 500;
    private int maxDelayMs = 3000;

    // Consensus data
    private final Map<String, ConsensusData> activeConsensus = new ConcurrentHashMap<>();

    // UI Components
    private JTextArea logArea;
    JButton startButton;
    private JButton stopButton;
    private JList<String> membersList;
    private DefaultListModel<String> membersListModel;
    private JLabel statusLabel;
    private JSpinner connFailureSpinner, delayRateSpinner, incorrectRateSpinner;

    public FlatGroupProcess(int processId, int clientPort, int internalPort, List<ProcessInfo> bootstrapList) {
        this.processId = processId;
        this.clientPort = clientPort;
        this.internalPort = internalPort;
        this.bootstrapList.addAll(bootstrapList);
        this.threadPool = Executors.newCachedThreadPool();
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Flat Group Process " + processId);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(5,5,5,5);
        startButton = new JButton("Start Process");
        startButton.addActionListener(e -> startProcess());
        controlPanel.add(startButton, gbc);

        gbc.gridx = 1;
        stopButton = new JButton("Stop Process");
        stopButton.addActionListener(e -> stopProcess());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        statusLabel = new JLabel("Status: Stopped");
        controlPanel.add(statusLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        controlPanel.add(new JLabel("Conn Fail %:"), gbc);
        gbc.gridx = 1;
        connFailureSpinner = new JSpinner(new SpinnerNumberModel(20, 0, 100, 5));
        connFailureSpinner.addChangeListener(e -> updateFaultRates());
        controlPanel.add(connFailureSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        controlPanel.add(new JLabel("Delay %:"), gbc);
        gbc.gridx = 1;
        delayRateSpinner = new JSpinner(new SpinnerNumberModel(15, 0, 100, 5));
        delayRateSpinner.addChangeListener(e -> updateFaultRates());
        controlPanel.add(delayRateSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        controlPanel.add(new JLabel("Incorrect %:"), gbc);
        gbc.gridx = 1;
        incorrectRateSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 100, 5));
        incorrectRateSpinner.addChangeListener(e -> updateFaultRates());
        controlPanel.add(incorrectRateSpinner, gbc);

        add(controlPanel, BorderLayout.NORTH);

        JPanel membersPanel = new JPanel(new BorderLayout());
        membersPanel.setBorder(BorderFactory.createTitledBorder("Group Members"));
        membersListModel = new DefaultListModel<>();
        membersList = new JList<>(membersListModel);
        membersPanel.add(new JScrollPane(membersList), BorderLayout.CENTER);
        membersPanel.setPreferredSize(new Dimension(200, 300));
        add(membersPanel, BorderLayout.EAST);

        logArea = new JTextArea(20, 50);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        pack();
    }

    private void updateFaultRates() {
        connectionFailureRate = (Integer) connFailureSpinner.getValue();
        responseDelayRate = (Integer) delayRateSpinner.getValue();
        incorrectResponseRate = (Integer) incorrectRateSpinner.getValue();
    }

    private void startProcess() {
        try {
            clientSocket = new ServerSocket(clientPort);
            internalSocket = new ServerSocket(internalPort);
            running.set(true);

            // Add self to group first
            groupMembers.add(new ProcessInfo(processId, "localhost", internalPort));

            // Perform initial discovery with bootstrap list
            appendLog("Starting discovery with " + bootstrapList.size() + " bootstrap members");
            performInitialDiscovery();

            // Start listeners
            threadPool.submit(this::listenForClients);
            threadPool.submit(this::listenForInternalMessages);
            threadPool.submit(this::announceMembership);

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            statusLabel.setText("Status: Running (Client: " + clientPort + ", Internal: " + internalPort + ")");

            appendLog("Process started - ID: " + processId + ", Group size: " + groupMembers.size());
        } catch (IOException e) {
            appendLog("Failed to start process: " + e.getMessage());
        }
    }

    private void performInitialDiscovery() {
        CountDownLatch discoveryLatch = new CountDownLatch(bootstrapList.size());

        for (ProcessInfo peer : bootstrapList) {
            if (peer.processId == processId) {
                discoveryLatch.countDown();
                continue;
            }

            threadPool.submit(() -> {
                try {
                    // Try to connect and announce ourselves
                    for (int attempt = 0; attempt < 3 && running.get(); attempt++) {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(peer.host, peer.internalPort), 1000);
                            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                            out.println("JOIN:" + processId + ":" + internalPort);

                            groupMembers.add(peer);
                            appendLog("Connected to P" + peer.processId);
                            break;
                        } catch (IOException e) {
                            if (attempt < 2) {
                                Thread.sleep(200);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    discoveryLatch.countDown();
                }
            });
        }

        try {
            discoveryLatch.await(5, TimeUnit.SECONDS);
            updateMembersList();
            appendLog("Discovery complete. Known members: " + groupMembers.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopProcess() {
        running.set(false);
        broadcastMessage("LEAVE:" + processId);

        try {
            if (clientSocket != null) clientSocket.close();
            if (internalSocket != null) internalSocket.close();
            threadPool.shutdownNow();
        } catch (IOException e) {
            appendLog("Error stopping process: " + e.getMessage());
        }

        groupMembers.clear();
        updateMembersList();

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusLabel.setText("Status: Stopped");

        appendLog("Process stopped");
    }

    private void listenForClients() {
        while (running.get()) {
            try {
                Socket client = clientSocket.accept();
                threadPool.submit(() -> handleClientRequest(client));
            } catch (IOException e) {
                if (running.get()) {
                    appendLog("Error accepting client: " + e.getMessage());
                }
            }
        }
    }

    private void handleClientRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request = in.readLine();
            if (request != null) {
                appendLog("Received client request: " + request);

                int leaderId = selectLeaderForRequest(request);

                if (leaderId == processId) {
                    appendLog("I am leader for: " + request);
                    String response = coordinateConsensus(request);
                    out.println(response);
                } else {
                    appendLog("Forwarding to leader P" + leaderId + " for: " + request);
                    String response = forwardToLeader(leaderId, request);
                    out.println(response);
                }
            }
        } catch (IOException e) {
            appendLog("Error handling client request: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {}
        }
    }

    private int selectLeaderForRequest(String request) {
        if (groupMembers.isEmpty()) return processId;

        int hash = Math.abs(request.hashCode());
        List<ProcessInfo> sortedMembers = new ArrayList<>(groupMembers);
        sortedMembers.sort(Comparator.comparingInt(p -> p.processId));

        return sortedMembers.get(hash % sortedMembers.size()).processId;
    }

    private String coordinateConsensus(String request) {
        int totalMembers = groupMembers.size();
        if (totalMembers < 3) {
            appendLog("Insufficient members for consensus: " + totalMembers);
            return "ERROR_INSUFFICIENT_MEMBERS";
        }

        int requiredConsensus = (totalMembers / 2) + 1;
        appendLog("Starting consensus for " + request + " - need " + requiredConsensus + " of " + totalMembers);

        ConsensusData consensus = new ConsensusData(request, requiredConsensus);
        activeConsensus.put(request, consensus);

        broadcastMessage("VOTE_REQUEST:" + processId + ":" + request);

        String myVote = processRequest(request);
        consensus.addVote(processId, myVote);

        try {
            String result = consensus.waitForConsensus(5000);
            if (result != null) {
                appendLog("Consensus reached for " + request + ": " + result);
                return result;
            } else {
                appendLog("No consensus for " + request + " (votes: " + consensus.getVotesReceived() + ")");
                return "NO_CONSENSUS_" + consensus.getVotesReceived() + "_OF_" + totalMembers;
            }
        } finally {
            activeConsensus.remove(request);
        }
    }

    private String forwardToLeader(int leaderId, String request) {
        ProcessInfo leader = groupMembers.stream()
                .filter(p -> p.processId == leaderId)
                .findFirst()
                .orElse(null);

        if (leader == null) {
            return "ERROR_LEADER_NOT_FOUND";
        }

        try (Socket socket = new Socket(leader.host, leader.internalPort)) {
            socket.setSoTimeout(6000);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println("FORWARD_REQUEST:" + request);
                return in.readLine();
            }
        } catch (IOException e) {
            appendLog("Error forwarding to leader: " + e.getMessage());
            return "ERROR_FORWARD_FAILED";
        }
    }

    private void listenForInternalMessages() {
        while (running.get()) {
            try {
                Socket peer = internalSocket.accept();
                threadPool.submit(() -> handleInternalMessage(peer));
            } catch (IOException e) {
                if (running.get()) {
                    appendLog("Error accepting internal connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleInternalMessage(Socket peerSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()));
             PrintWriter out = new PrintWriter(peerSocket.getOutputStream(), true)) {

            String message = in.readLine();
            if (message == null) return;

            if (message.startsWith("VOTE_REQUEST:")) {
                String[] parts = message.split(":", 3);
                int senderId = Integer.parseInt(parts[1]);
                String request = parts[2];

                appendLog("Received vote request from P" + senderId + " for: " + request);

                String vote = processRequest(request);
                sendVoteToLeader(senderId, request, vote);

            } else if (message.startsWith("VOTE:")) {
                String[] parts = message.split(":", 3);
                int voterId = Integer.parseInt(parts[1]);
                String voteData = parts[2];

                String[] voteParts = voteData.split("\\|", 2);
                String request = voteParts[0];
                String vote = voteParts[1];

                appendLog("Received vote from P" + voterId + " for " + request + ": " + vote);

                ConsensusData consensus = activeConsensus.get(request);
                if (consensus != null) {
                    consensus.addVote(voterId, vote);
                }

            } else if (message.startsWith("JOIN:")) {
                String[] parts = message.split(":");
                int newId = Integer.parseInt(parts[1]);
                int newPort = Integer.parseInt(parts[2]);

                ProcessInfo newMember = new ProcessInfo(newId, "localhost", newPort);
                if (groupMembers.add(newMember)) {
                    updateMembersList();
                    appendLog("Process " + newId + " joined the group");
                }

            } else if (message.startsWith("LEAVE:")) {
                String[] parts = message.split(":");
                int leavingId = Integer.parseInt(parts[1]);
                groupMembers.removeIf(p -> p.processId == leavingId);
                updateMembersList();
                appendLog("Process " + leavingId + " left the group");

            } else if (message.startsWith("FORWARD_REQUEST:")) {
                String request = message.substring("FORWARD_REQUEST:".length());
                appendLog("Processing forwarded request: " + request);
                String response = coordinateConsensus(request);
                out.println(response);
            }

        } catch (IOException e) {
            appendLog("Error handling internal message: " + e.getMessage());
        } finally {
            try {
                peerSocket.close();
            } catch (IOException e) {}
        }
    }

    private void sendVoteToLeader(int leaderId, String request, String vote) {
        ProcessInfo leader = groupMembers.stream()
                .filter(p -> p.processId == leaderId)
                .findFirst()
                .orElse(null);

        if (leader == null) return;

        threadPool.submit(() -> {
            try (Socket socket = new Socket(leader.host, leader.internalPort)) {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println("VOTE:" + processId + ":" + request + "|" + vote);
            } catch (IOException e) {
                appendLog("Error sending vote to leader: " + e.getMessage());
            }
        });
    }

    private String processRequest(String request) {
        try {
            int delay = baseDelayMs + random.nextInt(maxDelayMs - baseDelayMs);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (random.nextInt(100) < connectionFailureRate) {
            appendLog("Simulating connection failure for: " + request);
            return null;
        }

        if (random.nextInt(100) < incorrectResponseRate) {
            String errorResponse = "ERROR_P" + processId + "_" + random.nextInt(1000);
            appendLog("Generating error response: " + errorResponse);
            return errorResponse;
        }

        String response = "ACK_P" + processId + "_" + request;
        appendLog("Generated response: " + response);
        return response;
    }

    private void announceMembership() {
        while (running.get()) {
            try {
                Thread.sleep(3000);
                if (running.get()) {
                    broadcastMessage("JOIN:" + processId + ":" + internalPort);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void broadcastMessage(String message) {
        for (ProcessInfo member : groupMembers) {
            if (member.processId == processId) continue;

            threadPool.submit(() -> {
                try (Socket socket = new Socket(member.host, member.internalPort)) {
                    socket.setSoTimeout(1000);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    out.println(message);
                } catch (IOException e) {
                    // Member might be down, ignore
                }
            });
        }
    }

    private void updateMembersList() {
        SwingUtilities.invokeLater(() -> {
            membersListModel.clear();
            groupMembers.stream()
                    .sorted(Comparator.comparingInt(p -> p.processId))
                    .forEach(p -> membersListModel.addElement("Process " + p.processId + " (:" + p.internalPort + ")"));
        });
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[P" + processId + " - " + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    static class ProcessInfo {
        final int processId;
        final String host;
        final int internalPort;

        ProcessInfo(int id, String host, int port) {
            this.processId = id;
            this.host = host;
            this.internalPort = port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProcessInfo that = (ProcessInfo) o;
            return processId == that.processId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(processId);
        }
    }

    private static class ConsensusData {
        private final String request;
        private final int requiredVotes;
        private final Map<String, Integer> voteCounts = new ConcurrentHashMap<>();
        private final Map<String, List<String>> voteDetails = new ConcurrentHashMap<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String consensusResult = null;
        private int votesReceived = 0;

        ConsensusData(String request, int requiredVotes) {
            this.request = request;
            this.requiredVotes = requiredVotes;
        }

        synchronized void addVote(int voterId, String vote) {
            if (vote == null || consensusResult != null) return;

            votesReceived++;
            String normalizedVote = normalizeVote(vote);
            voteCounts.put(normalizedVote, voteCounts.getOrDefault(normalizedVote, 0) + 1);
            voteDetails.computeIfAbsent(normalizedVote, k -> new ArrayList<>()).add(vote);

            if (voteCounts.get(normalizedVote) >= requiredVotes) {
                consensusResult = voteDetails.get(normalizedVote).get(0);
                latch.countDown();
            }
        }

        private String normalizeVote(String vote) {
            if (vote.startsWith("ACK_")) return "ACK_SUCCESS";
            if (vote.startsWith("ERROR_")) return "ERROR_RESPONSE";
            return vote;
        }

        String waitForConsensus(long timeoutMs) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return consensusResult;
        }

        int getVotesReceived() {
            return votesReceived;
        }
    }
}