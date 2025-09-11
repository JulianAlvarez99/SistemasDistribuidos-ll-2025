import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

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
        fileNameField = new JTextField(20);
        contentArea = new JTextArea(10, 40);
        resultArea = new JTextArea(8, 40);

        connectButton = new JButton("Connect");
        writeButton = new JButton("Write");
        readButton = new JButton("Read");
        deleteButton = new JButton("Delete");

        // Initially disable operation buttons
        writeButton.setEnabled(false);
        readButton.setEnabled(false);
        deleteButton.setEnabled(false);

        contentArea.setBorder(BorderFactory.createTitledBorder("File Content"));
        resultArea.setBorder(BorderFactory.createTitledBorder("Operation Results"));
        resultArea.setEditable(false);

        // Add scroll panes
        contentArea = new JTextArea(10, 40);
        resultArea = new JTextArea(8, 40);
        resultArea.setEditable(false);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Top panel - connection
        JPanel connectionPanel = new JPanel(new FlowLayout());
        connectionPanel.add(new JLabel("Server Host:"));
        connectionPanel.add(serverHostField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(serverPortField);
        connectionPanel.add(connectButton);

        // Center panel - file operations
        JPanel operationsPanel = new JPanel(new BorderLayout());

        JPanel filePanel = new JPanel(new FlowLayout());
        filePanel.add(new JLabel("File Name:"));
        filePanel.add(fileNameField);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(writeButton);
        buttonPanel.add(readButton);
        buttonPanel.add(deleteButton);

        operationsPanel.add(filePanel, BorderLayout.NORTH);
        operationsPanel.add(new JScrollPane(contentArea), BorderLayout.CENTER);
        operationsPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Bottom panel - results
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.add(new JScrollPane(resultArea), BorderLayout.CENTER);

        add(connectionPanel, BorderLayout.NORTH);
        add(operationsPanel, BorderLayout.CENTER);
        add(resultPanel, BorderLayout.SOUTH);
    }

    private void setupEventHandlers() {
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                connectToServer();
            }
        });

        writeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performWrite();
            }
        });

        readButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performRead();
            }
        });

        deleteButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performDelete();
            }
        });
    }

    private void connectToServer() {
        try {
            String host = serverHostField.getText().trim();
            int port = Integer.parseInt(serverPortField.getText().trim());

            client = new DistributedFileClient(host, port);

            writeButton.setEnabled(true);
            readButton.setEnabled(true);
            deleteButton.setEnabled(true);
            connectButton.setEnabled(false);

            appendResult("Connected to server: " + host + ":" + port);

        } catch (NumberFormatException e) {
            appendResult("Error: Invalid port number");
        } catch (Exception e) {
            appendResult("Error connecting to server: " + e.getMessage());
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
            appendResult("READ - SUCCESS: File content loaded");
        } else {
            appendResult("READ - ERROR: " + result.getMessage());
        }
    }

    private void performDelete() {
        String fileName = fileNameField.getText().trim();

        if (fileName.isEmpty()) {
            appendResult("Error: File name is required");
            return;
        }

        OperationResult result = client.delete(fileName);
        appendResult("DELETE - " + (result.isSuccess() ? "SUCCESS" : "ERROR") + ": " + result.getMessage());

        if (result.isSuccess()) {
            contentArea.setText("");
        }
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
