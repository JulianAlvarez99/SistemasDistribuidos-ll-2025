import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MessageSender extends JFrame {

    // --- UI Components ---
    private JTextArea logArea;
    private JTextArea messageTemplateArea;
    private JButton startButton, stopButton;
    private JSpinner portSpinner, intervalSpinner, adulterationRateSpinner;
    private JPasswordField passwordField;
    private JLabel statusLabel;

    // --- Networking & Scheduling ---
    private ServerSocket serverSocket;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile boolean running = false;
    private final Random random = new Random();
    private final List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();

    public MessageSender() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Message Sender - Session Key Encryption");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Top Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setBorder(BorderFactory.createTitledBorder("Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; controlPanel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1; portSpinner = new JSpinner(new SpinnerNumberModel(5555, 5000, 9999, 1)); controlPanel.add(portSpinner, gbc);
        gbc.gridx = 2; controlPanel.add(new JLabel("Shared Password:"), gbc);
        gbc.gridx = 3; passwordField = new JPasswordField("default-password", 15); controlPanel.add(passwordField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; controlPanel.add(new JLabel("Send Interval (s):"), gbc);
        gbc.gridx = 1; intervalSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1)); controlPanel.add(intervalSpinner, gbc);
        gbc.gridx = 2; controlPanel.add(new JLabel("Adulteration Rate (%):"), gbc);
        gbc.gridx = 3; adulterationRateSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 100, 5)); controlPanel.add(adulterationRateSpinner, gbc);
        gbc.gridx = 0; gbc.gridy = 2; startButton = new JButton("Start Server"); startButton.addActionListener(e -> startServer()); controlPanel.add(startButton, gbc);
        gbc.gridx = 1; stopButton = new JButton("Stop Server"); stopButton.addActionListener(e -> stopServer()); stopButton.setEnabled(false); controlPanel.add(stopButton, gbc);
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4; statusLabel = new JLabel("Status: Stopped"); statusLabel.setFont(new Font("Arial", Font.BOLD, 12)); controlPanel.add(statusLabel, gbc);
        add(controlPanel, BorderLayout.NORTH);

        // Message Template Panel
        JPanel messagePanel = new JPanel(new BorderLayout(5, 5));
        messagePanel.setBorder(BorderFactory.createTitledBorder("Message Template"));
        messageTemplateArea = new JTextArea(4, 50);
        messageTemplateArea.setLineWrap(true);
        messageTemplateArea.setText("Transaction ID: {RANDOM}\nTimestamp: {TIMESTAMP}\nDetails: Payment for services.");
        messagePanel.add(new JScrollPane(messageTemplateArea), BorderLayout.CENTER);
        add(messagePanel, BorderLayout.CENTER);

        // Log Panel
        logArea = new JTextArea(15, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(new JScrollPane(logArea), BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private void startServer() {
        if (new String(passwordField.getPassword()).isEmpty()) {
            JOptionPane.showMessageDialog(this, "Password cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        running = true;
        int port = (Integer) portSpinner.getValue();
        try {
            serverSocket = new ServerSocket(port);
            executorService.submit(this::acceptConnections);
            int interval = (Integer) intervalSpinner.getValue();
            scheduler.scheduleAtFixedRate(this::sendDataToAllClients, interval, interval, TimeUnit.SECONDS);

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            statusLabel.setText("Status: Running on port " + port);
            appendLog("=== SERVER STARTED on port " + port + " ===");
        } catch (IOException e) {
            appendLog("ERROR starting server: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;
        connectedClients.forEach(ClientHandler::close);
        connectedClients.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) { /* ignore */ }
        scheduler.shutdownNow();
        scheduler = Executors.newScheduledThreadPool(1);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusLabel.setText("Status: Stopped");
        appendLog("=== SERVER STOPPED ===");
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                appendLog("New client connecting: " + clientSocket.getInetAddress().getHostAddress());
                ClientHandler handler = new ClientHandler(clientSocket, new String(passwordField.getPassword()));
                connectedClients.add(handler);
                appendLog("Client session key sent. Total clients: " + connectedClients.size());
            } catch (Exception e) {
                if (running) appendLog("Error handling new client: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void sendDataToAllClients() {
        if (connectedClients.isEmpty()) return;

        String originalMessage = generateMessage();
        appendLog("\n--- Broadcasting data message to " + connectedClients.size() + " client(s) ---");
        appendLog("Plaintext: " + originalMessage.replace("\n", " "));

        for (ClientHandler client : connectedClients) {
            try {
                SecretKey sessionKey = client.getSessionKey();
                if (sessionKey == null) {
                    appendLog("WARNING: No session key for client " + client.socket.getInetAddress());
                    continue;
                }

                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, sessionKey, new IvParameterSpec(iv));
                byte[] ciphertext = cipher.doFinal(originalMessage.getBytes(StandardCharsets.UTF_8));

                String encodedIv = Base64.getEncoder().encodeToString(iv);
                String encodedCiphertext = Base64.getEncoder().encodeToString(ciphertext);

                boolean shouldAdulterate = random.nextInt(100) < (Integer) adulterationRateSpinner.getValue();
                if (shouldAdulterate) {
                    encodedCiphertext = adulteratePayload(encodedCiphertext);
                    appendLog("-> Adulterated message for " + client.socket.getInetAddress());
                }

                String protocolMessage = "DATA|" + encodedIv + "|" + encodedCiphertext;
                client.sendMessage(protocolMessage);

            } catch (Exception e) {
                appendLog("ERROR encrypting data for client: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private String adulteratePayload(String base64Payload) {
        if (base64Payload.length() < 2) return base64Payload;
        char[] chars = base64Payload.toCharArray();
        int pos = random.nextInt(chars.length);
        chars[pos] = (chars[pos] == 'A') ? 'B' : 'A';
        return new String(chars);
    }

    private String generateMessage() {
        return messageTemplateArea.getText()
                .replace("{RANDOM}", String.valueOf(random.nextInt(100000)))
                .replace("{TIMESTAMP}", String.valueOf(System.currentTimeMillis()));
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private class ClientHandler {
        private final Socket socket;
        private final PrintWriter writer;
        private SecretKey sessionKey;

        ClientHandler(Socket socket, String sharedPassword) throws Exception {
            this.socket = socket;
            this.writer = new PrintWriter(socket.getOutputStream(), true);
            initiateKeyExchange(sharedPassword);
        }

        private void initiateKeyExchange(String password) throws Exception {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128);
            this.sessionKey = keyGen.generateKey();
            appendLog("Generated new session key for " + socket.getInetAddress());

            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
            SecretKey pbeKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

            Cipher pbeCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            pbeCipher.init(Cipher.ENCRYPT_MODE, pbeKey, new IvParameterSpec(iv));
            byte[] encryptedSessionKey = pbeCipher.doFinal(this.sessionKey.getEncoded());

            String protocolMessage = "KEY|"
                    + Base64.getEncoder().encodeToString(salt) + "|"
                    + Base64.getEncoder().encodeToString(iv) + "|"
                    + Base64.getEncoder().encodeToString(encryptedSessionKey);
            sendMessage(protocolMessage);
        }

        public SecretKey getSessionKey() {
            return sessionKey;
        }

        void sendMessage(String message) {
            if (writer != null && !socket.isClosed()) writer.println(message);
        }
        void close() { try { if (socket != null) socket.close(); } catch (IOException e) { /* ignore */ } }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MessageSender().setVisible(true));
    }
}