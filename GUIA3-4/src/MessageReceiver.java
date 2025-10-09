import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageReceiver extends JFrame {

    // --- UI Components ---
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JButton connectButton, disconnectButton;
    private JTextField hostField;
    private JSpinner portSpinner;
    private JPasswordField passwordField;
    private JLabel statusLabel;

    // --- Networking ---
    private Socket socket;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile boolean connected = false;

    // --- Crypto ---
    private SecretKey sessionKey;

    // --- Statistics ---
    private int messagesReceived = 0;

    public MessageReceiver() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Message Receiver - Session Key Encryption");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Top Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; controlPanel.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1; hostField = new JTextField("localhost", 15); controlPanel.add(hostField, gbc);
        gbc.gridx = 2; controlPanel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 3; portSpinner = new JSpinner(new SpinnerNumberModel(5555, 5000, 9999, 1)); controlPanel.add(portSpinner, gbc);
        gbc.gridx = 0; gbc.gridy = 1; controlPanel.add(new JLabel("Shared Password:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3; passwordField = new JPasswordField("default-password", 20); controlPanel.add(passwordField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; connectButton = new JButton("Connect"); connectButton.addActionListener(e -> connectToServer()); controlPanel.add(connectButton, gbc);
        gbc.gridx = 1; disconnectButton = new JButton("Disconnect"); disconnectButton.addActionListener(e -> disconnect()); disconnectButton.setEnabled(false); controlPanel.add(disconnectButton, gbc);
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4; statusLabel = new JLabel("Status: Disconnected"); statusLabel.setFont(new Font("Arial", Font.BOLD, 12)); controlPanel.add(statusLabel, gbc);
        add(controlPanel, BorderLayout.NORTH);

        // Results Table
        String[] columnNames = {"#", "Status", "Message Content / Error"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            public boolean isCellEditable(int row, int column) { return false; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(400);
        add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void connectToServer() {
        if (new String(passwordField.getPassword()).isEmpty()) {
            JOptionPane.showMessageDialog(this, "Password cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();

        try {
            socket = new Socket(host, port);
            connected = true;
            executorService.submit(this::listenForMessages);

            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            statusLabel.setText("Status: Connected to " + host + ":" + port);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disconnect() {
        connected = false;
        this.sessionKey = null; // Clear session key on disconnect
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) { /* ignore */ }
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        statusLabel.setText("Status: Disconnected");
        addResultToTable("---", "Disconnected from server.");
    }

    private void listenForMessages() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                if (line.startsWith("KEY|")) {
                    handleKeyExchange(line.substring(4));
                } else if (line.startsWith("DATA|")) {
                    handleDataMessage(line.substring(5));
                }
            }
        } catch (IOException e) {
            if (connected) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Connection lost.", "Error", JOptionPane.ERROR_MESSAGE));
            }
        } finally {
            if(connected) disconnect();
        }
    }

    private void handleKeyExchange(String keyPayload) {
        try {
            String[] parts = keyPayload.split("\\|", 3);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid key exchange format.");

            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encryptedSessionKey = Base64.getDecoder().decode(parts[2]);

            String password = new String(passwordField.getPassword());
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
            SecretKey pbeKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

            Cipher pbeCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            pbeCipher.init(Cipher.DECRYPT_MODE, pbeKey, new IvParameterSpec(iv));
            byte[] decryptedSessionKeyBytes = pbeCipher.doFinal(encryptedSessionKey);

            this.sessionKey = new SecretKeySpec(decryptedSessionKeyBytes, 0, decryptedSessionKeyBytes.length, "AES");
            addResultToTable("✓ INFO", "Session key established successfully.");

        } catch (Exception e) {
            addResultToTable("✗ ERROR", "Failed to establish session key (check password?): " + e.getMessage());
            e.printStackTrace();
            disconnect();
        }
    }

    private void handleDataMessage(String dataPayload) {
        if (this.sessionKey == null) {
            addResultToTable("✗ ERROR", "Data received before session key. Disconnecting.");
            disconnect();
            return;
        }

        String status;
        String content;
        try {
            String[] parts = dataPayload.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid data message format.");

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher dataCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            dataCipher.init(Cipher.DECRYPT_MODE, this.sessionKey, new IvParameterSpec(iv));
            byte[] decryptedBytes = dataCipher.doFinal(ciphertext);

            content = new String(decryptedBytes, StandardCharsets.UTF_8);
            status = "✓ INTACT";

        } catch (Exception e) {
            status = "✗ ADULTERATED";
            content = "Decryption failed: " + e.getMessage();
        }
        addResultToTable(status, content);
    }

    private void addResultToTable(String status, String content) {
        messagesReceived++;
        SwingUtilities.invokeLater(() -> {
            tableModel.addRow(new Object[]{messagesReceived, status, content.replace("\n", " ")});
            resultsTable.scrollRectToVisible(resultsTable.getCellRect(tableModel.getRowCount() - 1, 0, true));
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MessageReceiver().setVisible(true));
    }
}