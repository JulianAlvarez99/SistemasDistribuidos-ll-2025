import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;

public class MessageReceiver extends JFrame {
    private static final int DEFAULT_PORT = 5555;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private ExecutorService executorService;
    private volatile boolean connected = false;

    // Statistics
    private int messagesReceived = 0;
    private int messagesIntact = 0;
    private int messagesAdulterated = 0;

    // UI Components
    private JTextArea logArea;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JButton connectButton, disconnectButton, clearButton;
    private JTextField hostField;
    private JSpinner portSpinner;
    private JLabel statsLabel, statusLabel;

    public MessageReceiver() {
        initializeUI();
        executorService = Executors.newCachedThreadPool();
    }

    private void initializeUI() {
        setTitle("Message Receiver - Hash Integrity Verification");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Top Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        controlPanel.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1;
        hostField = new JTextField("localhost", 15);
        controlPanel.add(hostField, gbc);

        gbc.gridx = 2;
        controlPanel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 3;
        portSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_PORT, 5000, 9999, 1));
        controlPanel.add(portSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        connectButton = new JButton("Connect to Sender");
        connectButton.addActionListener(e -> connectToSender());
        controlPanel.add(connectButton, gbc);

        gbc.gridx = 1;
        disconnectButton = new JButton("Disconnect");
        disconnectButton.addActionListener(e -> disconnect());
        disconnectButton.setEnabled(false);
        controlPanel.add(disconnectButton, gbc);

        gbc.gridx = 2;
        clearButton = new JButton("Clear Results");
        clearButton.addActionListener(e -> clearResults());
        controlPanel.add(clearButton, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 4;
        statusLabel = new JLabel("Status: Disconnected");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));
        controlPanel.add(statusLabel, gbc);

        add(controlPanel, BorderLayout.NORTH);

        // Center - Results Table
        JPanel tablePanel = new JPanel(new BorderLayout(5, 5));
        tablePanel.setBorder(BorderFactory.createTitledBorder("Verification Results"));

        String[] columnNames = {"#", "Algorithm", "Status", "Received Hash", "Calculated Hash", "Message Length"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setFont(new Font("Monospaced", Font.PLAIN, 11));
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(200);
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(200);

        tablePanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        add(tablePanel, BorderLayout.CENTER);

        // Bottom - Log and Stats
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        logArea = new JTextArea(12, 50);
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

    private void connectToSender() {
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();

        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a host", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            appendLog("=== CONNECTING TO SENDER ===");
            appendLog("Attempting connection to " + host + ":" + port);

            socket = new Socket(host, port);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            connected = true;

            appendLog("✓ Connected successfully!");
            appendLog("Waiting for messages...");

            executorService.submit(this::listenForMessages);

            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            statusLabel.setText("Status: Connected to " + host + ":" + port);
            statusLabel.setForeground(new Color(0, 128, 0));

        } catch (IOException e) {
            appendLog("✗ Connection failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disconnect() {
        connected = false;

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            appendLog("Error closing connection: " + e.getMessage());
        }

        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        statusLabel.setText("Status: Disconnected");
        statusLabel.setForeground(Color.BLACK);

        appendLog("=== DISCONNECTED ===");
    }

    private void listenForMessages() {
        try {
            while (connected && !socket.isClosed()) {
                String line = reader.readLine();
                if (line == null) {
                    appendLog("Connection closed by sender");
                    break;
                }

                processMessage(line);
            }
        } catch (IOException e) {
            if (connected) {
                appendLog("Error reading message: " + e.getMessage());
            }
        } finally {
            if (connected) {
                disconnect();
            }
        }
    }

    private void processMessage(String protocolMessage) {
        appendLog("\n--- Message Received ---");
        appendLog("Raw protocol data length: " + protocolMessage.length() + " bytes");

        try {
            // Parse protocol: ALGORITHM|HASH|MESSAGE_LENGTH|MESSAGE
            String[] parts = protocolMessage.split("\\|", 4);
            if (parts.length < 4) {
                appendLog("ERROR: Invalid protocol format");
                return;
            }

            String algorithm = parts[0];
            String receivedHash = parts[1];
            int messageLength = Integer.parseInt(parts[2]);
            String message = parts[3];

            appendLog("Algorithm: " + algorithm);
            appendLog("Received hash: " + receivedHash);
            appendLog("Message length: " + messageLength + " bytes");
            appendLog("Message content:");
            appendLog(message);

            // Verify message length
            if (message.length() != messageLength) {
                appendLog("⚠ WARNING: Message length mismatch! Expected: " + messageLength + ", Got: " + message.length());
            }

            // Calculate hash of received message
            String calculatedHash = calculateHash(message, algorithm);
            if (calculatedHash == null) {
                appendLog("ERROR: Failed to calculate hash");
                return;
            }

            appendLog("Calculated hash: " + calculatedHash);

            // Compare hashes
            boolean intact = receivedHash.equalsIgnoreCase(calculatedHash);
            messagesReceived++;

            if (intact) {
                messagesIntact++;
                appendLog("✓ INTEGRITY VERIFIED - Message is intact");
                addResultToTable(algorithm, "✓ INTACT", receivedHash, calculatedHash, messageLength, true);
            } else {
                messagesAdulterated++;
                appendLog("✗ INTEGRITY VIOLATION - Message was adulterated!");
                appendLog("Hash mismatch detected:");
                appendLog("  Expected: " + receivedHash);
                appendLog("  Got:      " + calculatedHash);
                addResultToTable(algorithm, "✗ ADULTERATED", receivedHash, calculatedHash, messageLength, false);
            }

            updateStatistics();

        } catch (Exception e) {
            appendLog("ERROR processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String calculateHash(String message, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = digest.digest(message.getBytes());
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

    private void addResultToTable(String algorithm, String status, String receivedHash,
                                  String calculatedHash, int messageLength, boolean intact) {
        SwingUtilities.invokeLater(() -> {
            Color statusColor = intact ? new Color(0, 150, 0) : new Color(200, 0, 0);

            tableModel.addRow(new Object[]{
                    messagesReceived,
                    algorithm,
                    status,
                    receivedHash,
                    calculatedHash,
                    messageLength
            });

            // Highlight the row
            int row = tableModel.getRowCount() - 1;
            resultsTable.setRowSelectionInterval(row, row);
            resultsTable.scrollRectToVisible(resultsTable.getCellRect(row, 0, true));
        });
    }

    private void clearResults() {
        tableModel.setRowCount(0);
        logArea.setText("");
        messagesReceived = 0;
        messagesIntact = 0;
        messagesAdulterated = 0;
        updateStatistics();
        appendLog("Results cleared");
    }

    private void updateStatistics() {
        SwingUtilities.invokeLater(() -> {
            double intactPercentage = messagesReceived > 0 ? (messagesIntact * 100.0 / messagesReceived) : 0;
            double adulteratedPercentage = messagesReceived > 0 ? (messagesAdulterated * 100.0 / messagesReceived) : 0;

            String stats = String.format(
                    "<html><b>Statistics:</b> Total: %d | " +
                            "<span style='color:green;'>✓ Intact: %d (%.1f%%)</span> | " +
                            "<span style='color:red;'>✗ Adulterated: %d (%.1f%%)</span></html>",
                    messagesReceived,
                    messagesIntact, intactPercentage,
                    messagesAdulterated, adulteratedPercentage
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
            new MessageReceiver().setVisible(true);
        });
    }
}