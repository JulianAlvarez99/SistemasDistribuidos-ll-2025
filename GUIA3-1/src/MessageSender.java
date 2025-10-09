import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MessageSender extends JFrame {
    private static final int DEFAULT_PORT = 5555;
    private static final String[] HASH_ALGORITHMS = {"MD5", "SHA-1", "SHA-256"};

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private final Random random = new Random();
    private final List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();

    // Statistics
    private int messagesSent = 0;
    private int messagesAdulterated = 0;
    private int connectionCount = 0;

    // UI Components
    private JTextArea logArea;
    private JTextArea messageTemplateArea;
    private JButton startButton, stopButton, sendNowButton;
    private JSpinner portSpinner, intervalSpinner, adulterationRateSpinner;
    private JComboBox<String> hashAlgorithmCombo;
    private JLabel statsLabel, statusLabel;
    private JCheckBox enableScheduledCheckBox;

    public MessageSender() {
        initializeUI();
        executorService = Executors.newCachedThreadPool();
        scheduler = Executors.newScheduledThreadPool(1);
    }

    private void initializeUI() {
        setTitle("Message Sender - Hash Integrity System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Top Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setBorder(BorderFactory.createTitledBorder("Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        controlPanel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        portSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_PORT, 5000, 9999, 1));
        controlPanel.add(portSpinner, gbc);

        gbc.gridx = 2;
        controlPanel.add(new JLabel("Hash Algorithm:"), gbc);
        gbc.gridx = 3;
        hashAlgorithmCombo = new JComboBox<>(HASH_ALGORITHMS);
        hashAlgorithmCombo.setSelectedIndex(2); // SHA-256 by default
        controlPanel.add(hashAlgorithmCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        controlPanel.add(new JLabel("Send Interval (seconds):"), gbc);
        gbc.gridx = 1;
        intervalSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
        controlPanel.add(intervalSpinner, gbc);

        gbc.gridx = 2;
        controlPanel.add(new JLabel("Adulteration Rate (%):"), gbc);
        gbc.gridx = 3;
        adulterationRateSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 100, 5));
        adulterationRateSpinner.setToolTipText("Probability of adulterating a message after hash calculation");
        controlPanel.add(adulterationRateSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        enableScheduledCheckBox = new JCheckBox("Enable Scheduled Sending", true);
        controlPanel.add(enableScheduledCheckBox, gbc);

        gbc.gridx = 2; gbc.gridwidth = 1;
        startButton = new JButton("Start Server");
        startButton.addActionListener(e -> startServer());
        controlPanel.add(startButton, gbc);

        gbc.gridx = 3;
        stopButton = new JButton("Stop Server");
        stopButton.addActionListener(e -> stopServer());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4;
        statusLabel = new JLabel("Status: Stopped");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));
        controlPanel.add(statusLabel, gbc);

        add(controlPanel, BorderLayout.NORTH);

        // Message Template Panel
        JPanel messagePanel = new JPanel(new BorderLayout(5, 5));
        messagePanel.setBorder(BorderFactory.createTitledBorder("Message Template"));

        messageTemplateArea = new JTextArea(4, 50);
        messageTemplateArea.setLineWrap(true);
        messageTemplateArea.setText("Transaction ID: {RANDOM}\nTimestamp: {TIMESTAMP}\nAmount: {AMOUNT}\nAccount: {ACCOUNT}\nDescription: Payment processing for distributed systems course");
        messagePanel.add(new JScrollPane(messageTemplateArea), BorderLayout.CENTER);

        JPanel messageButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        sendNowButton = new JButton("Send Message Now");
        sendNowButton.addActionListener(e -> sendMessageManually());
        sendNowButton.setEnabled(false);
        messageButtonPanel.add(sendNowButton);
        messagePanel.add(messageButtonPanel, BorderLayout.SOUTH);

        add(messagePanel, BorderLayout.CENTER);

        // Bottom Panel - Log and Stats
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        logArea = new JTextArea(15, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Activity Log"));
        bottomPanel.add(logScroll, BorderLayout.CENTER);

        statsLabel = new JLabel();
        updateStatistics();
        bottomPanel.add(statsLabel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private void startServer() {
        int port = (Integer) portSpinner.getValue();

        try {
            serverSocket = new ServerSocket(port);
            running = true;

            executorService.submit(this::acceptConnections);

            if (enableScheduledCheckBox.isSelected()) {
                int interval = (Integer) intervalSpinner.getValue();
                scheduler.scheduleAtFixedRate(this::sendScheduledMessage, interval, interval, TimeUnit.SECONDS);
                appendLog("Scheduled sending enabled (every " + interval + " seconds)");
            }

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            sendNowButton.setEnabled(true);
            statusLabel.setText("Status: Running on port " + port);
            statusLabel.setForeground(new Color(0, 128, 0));

            appendLog("=== SERVER STARTED ===");
            appendLog("Listening on port: " + port);
            appendLog("Hash algorithm: " + hashAlgorithmCombo.getSelectedItem());
            appendLog("Adulteration rate: " + adulterationRateSpinner.getValue() + "%");

        } catch (IOException e) {
            appendLog("ERROR: Failed to start server - " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Failed to start server: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopServer() {
        running = false;

        // Close all client connections
        for (ClientHandler client : connectedClients) {
            client.close();
        }
        connectedClients.clear();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            appendLog("Error closing server socket: " + e.getMessage());
        }

        scheduler.shutdownNow();
        scheduler = Executors.newScheduledThreadPool(1);

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        sendNowButton.setEnabled(false);
        statusLabel.setText("Status: Stopped");
        statusLabel.setForeground(Color.BLACK);

        appendLog("=== SERVER STOPPED ===");
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionCount++;
                appendLog("New connection from: " + clientSocket.getInetAddress().getHostAddress());
                executorService.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    appendLog("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        ClientHandler handler = new ClientHandler(clientSocket);
        connectedClients.add(handler);
        appendLog("Client handler registered. Total clients: " + connectedClients.size());
    }

    private class ClientHandler {
        private final Socket socket;
        private PrintWriter writer;

        ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                this.writer = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e) {
                appendLog("Error creating writer for client: " + e.getMessage());
            }
        }

        void sendMessage(String message) {
            if (writer != null && !socket.isClosed()) {
                writer.println(message);
            }
        }

        boolean isConnected() {
            return socket != null && !socket.isClosed();
        }

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private void sendScheduledMessage() {
        if (running) {
            sendMessage();
        }
    }

    private void sendMessageManually() {
        sendMessage();
    }

    private void sendMessage() {
        // Generate message from template
        String message = generateMessage();
        String algorithm = (String) hashAlgorithmCombo.getSelectedItem();

        appendLog("\n=== PREPARING MESSAGE ===");
        appendLog("Original message (" + message.length() + " bytes):");
        appendLog(message);

        // Calculate hash of original message
        String originalHash = calculateHash(message, algorithm);
        if (originalHash == null) {
            appendLog("ERROR: Failed to calculate hash");
            return;
        }

        appendLog("Original hash (" + algorithm + "): " + originalHash);

        // Decide if we should adulterate the message
        int adulterationRate = (Integer) adulterationRateSpinner.getValue();
        int randomValue = random.nextInt(100);
        boolean shouldAdulterate = randomValue < adulterationRate;

        appendLog("DEBUG: Adulteration check - Random: " + randomValue + " < Rate: " + adulterationRate + " = " + shouldAdulterate);

        String transmittedMessage = message;
        if (shouldAdulterate) {
            transmittedMessage = adulterateMessage(message);
            appendLog("⚠ ADULTERATION APPLIED!");
            appendLog("Adulterated message (" + transmittedMessage.length() + " bytes):");
            appendLog(transmittedMessage);

            // Verify the messages are actually different
            if (message.equals(transmittedMessage)) {
                appendLog("⚠ WARNING: Adulteration failed - messages are identical!");
            } else {
                appendLog("✓ Confirmed: Message was modified");
            }

            messagesAdulterated++;
        } else {
            appendLog("✓ Message transmitted WITHOUT adulteration");
        }

        // Broadcast to all connected clients
        broadcastMessage(transmittedMessage, originalHash, algorithm);

        messagesSent++;
        updateStatistics();
    }

    private String generateMessage() {
        String template = messageTemplateArea.getText();

        // Replace placeholders
        template = template.replace("{RANDOM}", String.valueOf(random.nextInt(1000000)));
        template = template.replace("{TIMESTAMP}", String.valueOf(System.currentTimeMillis()));
        template = template.replace("{AMOUNT}", String.format("%.2f", 100 + random.nextDouble() * 9900));
        template = template.replace("{ACCOUNT}", String.format("ACC-%06d", random.nextInt(1000000)));

        return template;
    }

    private String adulterateMessage(String message) {
        char[] chars = message.toCharArray();
        int adulterationType = random.nextInt(4);

        switch (adulterationType) {
            case 0: // Change random character
                if (chars.length > 0) {
                    int pos = random.nextInt(chars.length);
                    char original = chars[pos];
                    chars[pos] = (char) (chars[pos] + random.nextInt(10) + 1);
                    appendLog("Type: Character modification at position " + pos +
                            " ('" + original + "' → '" + chars[pos] + "')");
                }
                break;
            case 1: // Remove characters
                if (chars.length > 5) {
                    int removeCount = random.nextInt(3) + 1;
                    int startPos = random.nextInt(chars.length - removeCount);
                    String result = new String(chars, 0, startPos) +
                            new String(chars, startPos + removeCount, chars.length - startPos - removeCount);
                    appendLog("Type: Character removal (" + removeCount + " chars at position " + startPos + ")");
                    return result;
                }
                break;
            case 2: // Insert characters
                int insertPos = random.nextInt(chars.length);
                String toInsert = "XXX";
                String result = new String(chars, 0, insertPos) + toInsert +
                        new String(chars, insertPos, chars.length - insertPos);
                appendLog("Type: Character insertion ('" + toInsert + "' at position " + insertPos + ")");
                return result;
            case 3: // Swap characters
                if (chars.length > 10) {
                    int pos1 = random.nextInt(chars.length);
                    int pos2 = random.nextInt(chars.length);
                    char temp = chars[pos1];
                    chars[pos1] = chars[pos2];
                    chars[pos2] = temp;
                    appendLog("Type: Character swap (positions " + pos1 + " ↔ " + pos2 + ")");
                }
                break;
        }

        return new String(chars);
    }

    private String calculateHash(String message, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            // CAMBIO: Especificar explícitamente la codificación a UTF-8 para consistencia
            byte[] hashBytes = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            appendLog("ERROR: Algorithm not available - " + algorithm);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void broadcastMessage(String message, String hash, String algorithm) {
        // AÑADIDO: Codificar el mensaje en Base64 para una transmisión segura
        String encodedMessage = Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8));

        // CAMBIO: Usar el mensaje codificado en el protocolo
        // La longitud que enviamos sigue siendo la del mensaje original para verificación en el receptor
        String protocolMessage = algorithm + "|" + hash + "|" + message.length() + "|" + encodedMessage;

        appendLog("\n>>> BROADCASTING TO RECEIVERS <<<");
        appendLog("Protocol: " + algorithm + " | Hash: " + hash.length() + " | Original Length: " + message.length());
        appendLog("DEBUG: Base64 Payload Length: " + encodedMessage.length());

        connectedClients.removeIf(client -> !client.isConnected());

        if (connectedClients.isEmpty()) {
            appendLog("⚠ No clients connected. Message not transmitted.");
            return;
        }

        int successCount = 0;
        for (ClientHandler client : connectedClients) {
            try {
                client.sendMessage(protocolMessage);
                successCount++;
            } catch (Exception e) {
                appendLog("Error sending to client: " + e.getMessage());
            }
        }
        appendLog("✓ Transmission complete - Sent to " + successCount + " client(s)");
    }


    private void updateStatistics() {
        SwingUtilities.invokeLater(() -> {
            String stats = String.format(
                    "<html><b>Statistics:</b> Messages Sent: %d | Adulterated: %d (%.1f%%) | Connections: %d</html>",
                    messagesSent,
                    messagesAdulterated,
                    messagesSent > 0 ? (messagesAdulterated * 100.0 / messagesSent) : 0,
                    connectionCount
            );
            statsLabel.setText(stats);
        });
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() % 100000 + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new MessageSender().setVisible(true);
        });
    }
}