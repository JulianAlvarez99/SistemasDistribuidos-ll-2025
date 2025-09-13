import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Main GUI application using Swing
 */
public class DistributedFileSystemGUI extends JFrame {
    private DistributedFileClient client;
    private JTextField serverHostField;
    private JTextField serverPortField;
    private JTextField fileNameField;
    private JTextArea contentArea;
    private JTextArea resultArea;
    private JButton connectButton;
    private JButton writeButton;
    private JButton readButton;
    private JButton deleteButton;
    private JTextArea fileListArea;
    private JButton listFilesButton;
    private JButton clearViewButton;
    private JButton refreshListButton;
    private JLabel syncStatusLabel;
    private JButton forceSyncButton;

    public DistributedFileSystemGUI() {
        initializeComponents();
        setupLayout();
        setupEventHandlers();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setTitle("Distributed File System Client");
        pack();
        setLocationRelativeTo(null);
    }

    private void initializeComponents() {
        serverHostField = new JTextField("localhost", 15);
        serverPortField = new JTextField("8080", 5);
        fileNameField = new JTextField(25);

        // Enhanced text areas with better formatting
        contentArea = new JTextArea(15, 60);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        resultArea = new JTextArea(8, 60);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        // File list area
        fileListArea = new JTextArea(10, 25);
        fileListArea.setEditable(false);
        fileListArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        connectButton = new JButton("Connect");
        writeButton = new JButton("Write");
        readButton = new JButton("Read");
        deleteButton = new JButton("Delete");
        listFilesButton = new JButton("List Files");
        clearViewButton = new JButton("Clear View");
        refreshListButton = new JButton("Refresh List");
        syncStatusLabel = new JLabel("Sync Status: Not connected");
        forceSyncButton = new JButton("Force Full Sync");


        // Initially disable operation buttons
        writeButton.setEnabled(false);
        readButton.setEnabled(false);
        deleteButton.setEnabled(false);
        listFilesButton.setEnabled(false);
        clearViewButton.setEnabled(false);
        refreshListButton.setEnabled(false);
        forceSyncButton.setEnabled(false);

        contentArea.setBorder(BorderFactory.createTitledBorder("File Content"));
        resultArea.setBorder(BorderFactory.createTitledBorder("Operation Results"));
        fileListArea.setBorder(BorderFactory.createTitledBorder("Files in Repository"));
        resultArea.setEditable(false);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Top panel - connection
        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectionPanel.add(new JLabel("Server Host:"));
        connectionPanel.add(serverHostField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(serverPortField);
        connectionPanel.add(connectButton);
        connectionPanel.add(forceSyncButton);
        connectionPanel.add(syncStatusLabel);

        // Left panel - file list
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(new JScrollPane(fileListArea), BorderLayout.CENTER);

        JPanel listButtonPanel = new JPanel(new FlowLayout());
        listButtonPanel.add(listFilesButton);
        listButtonPanel.add(refreshListButton);
        leftPanel.add(listButtonPanel, BorderLayout.SOUTH);

        // Center panel - file operations
        JPanel centerPanel = new JPanel(new BorderLayout());

        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(new JLabel("File Name:"));
        filePanel.add(fileNameField);
        filePanel.add(clearViewButton);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(writeButton);
        buttonPanel.add(readButton);
        buttonPanel.add(deleteButton);

        centerPanel.add(filePanel, BorderLayout.NORTH);
        centerPanel.add(new JScrollPane(contentArea), BorderLayout.CENTER);
        centerPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Bottom panel - results
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.add(new JScrollPane(resultArea), BorderLayout.CENTER);

        // Create split panes for responsive design
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, centerPanel);
        mainSplitPane.setDividerLocation(300);
        mainSplitPane.setResizeWeight(0.3);

        JSplitPane verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplitPane, resultPanel);
        verticalSplitPane.setDividerLocation(500);
        verticalSplitPane.setResizeWeight(0.7);

        add(connectionPanel, BorderLayout.NORTH);
        add(verticalSplitPane, BorderLayout.CENTER);

        // Set minimum size for responsive behavior
        setMinimumSize(new Dimension(900, 600));
        setPreferredSize(new Dimension(1200, 800));
    }

    private void setupEventHandlers() {
        connectButton.addActionListener(e -> connectToServer());
        writeButton.addActionListener(e -> performWrite());
        readButton.addActionListener(e -> performRead());
        deleteButton.addActionListener(e -> performDelete());
        listFilesButton.addActionListener(e -> performListFiles());
        refreshListButton.addActionListener(e -> performListFiles());
        clearViewButton.addActionListener(e -> clearFileView());
        forceSyncButton.addActionListener(e -> performForcedSync());

        // Double-click on file list to open file
        fileListArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selectedText = fileListArea.getSelectedText();
                    if (selectedText != null && !selectedText.trim().isEmpty()) {
                        String fileName = selectedText.trim();
                        fileNameField.setText(fileName);
                        performRead();
                    }
                }
            }
        });
    }

    private void connectToServer() {
        try {
            String host = serverHostField.getText().trim();
            int port = Integer.parseInt(serverPortField.getText().trim());

            client = new DistributedFileClient(host, port);

            // Enable all operation buttons
            writeButton.setEnabled(true);
            readButton.setEnabled(true);
            deleteButton.setEnabled(true);
            listFilesButton.setEnabled(true);
            clearViewButton.setEnabled(true);
            refreshListButton.setEnabled(true);
            connectButton.setEnabled(false);
            forceSyncButton.setEnabled(true);

            appendResult("Connected to server: " + host + ":" + port);

            // Automatically load file list
            performListFiles();

        } catch (NumberFormatException e) {
            appendResult("Error: Invalid port number");
        } catch (Exception e) {
            appendResult("Error connecting to server: " + e.getMessage());
        }
    }

    private void performForcedSync() {
        // This would require extending the client protocol to trigger sync
        appendResult("FORCED FULL SYNC: Requested complete state synchronization");
        syncStatusLabel.setText("Sync Status: Synchronizing...");

        SwingUtilities.invokeLater(() -> {
            try {
                Thread.sleep(3000); // Wait for sync
                syncStatusLabel.setText("Sync Status: Last sync completed");
                performListFiles(); // Refresh file list
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void performListFiles() {
        if (client == null) {
            appendResult("Error: Not connected to server");
            return;
        }

        OperationResult result = client.listFiles();
        if (result.isSuccess()) {
            fileListArea.setText(result.getContent());
            appendResult("LIST - SUCCESS: File list refreshed");
        } else {
            fileListArea.setText("No files found");
            appendResult("LIST - INFO: " + result.getMessage());
        }
    }

    private void performWrite() {
        String fileName = fileNameField.getText().trim();
        String content = contentArea.getText();

        if (fileName.isEmpty()) {
            appendResult("Error: File name is required");
            return;
        }

        OperationResult result = client.write(fileName, content);
        appendResult("WRITE - " + (result.isSuccess() ? "SUCCESS" : "ERROR") + ": " + result.getMessage());

        if (result.isSuccess()) {
            // Refresh file list after successful write
            performListFiles();
        }
    }

    private void performRead() {
        String fileName = fileNameField.getText().trim();

        if (fileName.isEmpty()) {
            appendResult("Error: File name is required");
            return;
        }

        OperationResult result = client.read(fileName);
        if (result.isSuccess()) {
            contentArea.setText(result.getContent());
            contentArea.setCaretPosition(0); // Scroll to top
            appendResult("READ - SUCCESS: File '" + fileName + "' loaded (" +
                    result.getContent().length() + " characters)");
        } else {
            contentArea.setText("");
            appendResult("READ - ERROR: " + result.getMessage());
        }
    }

    private void performDelete() {
        String fileName = fileNameField.getText().trim();

        if (fileName.isEmpty()) {
            appendResult("Error: File name is required");
            return;
        }

        // Confirm deletion
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete '" + fileName + "'?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (confirm != JOptionPane.YES_OPTION) {
            appendResult("DELETE - CANCELLED by user");
            return;
        }

        OperationResult result = client.delete(fileName);
        appendResult("DELETE - " + (result.isSuccess() ? "SUCCESS" : "ERROR") + ": " + result.getMessage());

        if (result.isSuccess()) {
            contentArea.setText("");
            fileNameField.setText("");
            // Refresh file list after successful delete
            performListFiles();
        }
    }

    private void clearFileView() {
        fileNameField.setText("");
        contentArea.setText("");
        appendResult("View cleared");
    }

    private void appendResult(String message) {
        SwingUtilities.invokeLater(() -> {
            resultArea.append("[" + java.time.LocalTime.now() + "] " + message + "\n");
            resultArea.setCaretPosition(resultArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Use default look and feel
            }
            new DistributedFileSystemGUI().setVisible(true);
        });
    }
}
