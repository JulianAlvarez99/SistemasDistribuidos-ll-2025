import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 🖥️ IMPROVED DISTRIBUTED FILE SYSTEM GUI
 * GUI mejorada para trabajar correctamente con redundancia activa
 */
public class DistributedFileSystemGUI extends JFrame {
    private MultiReplicaFileClient client;
    private SystemConfig config;

    // Components
    private JTextField fileNameField;
    private JTextArea contentArea;
    private JTextArea resultArea;
    private JTextArea fileListArea;
    private JButton connectButton;
    private JButton writeButton;
    private JButton readButton;
    private JButton deleteButton;
    private JButton listFilesButton;
    private JButton clearViewButton;
    private JButton refreshListButton;
    private JLabel statusLabel;
    private JLabel replicationModeLabel;
    private JComboBox<String> loadBalancingCombo;

    // Auto-refresh
    private ScheduledExecutorService refreshScheduler;
    private volatile boolean autoRefreshEnabled = true;

    public DistributedFileSystemGUI() {
        this.config = SystemConfig.getInstance();
        initializeComponents();
        setupLayout();
        setupEventHandlers();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setTitle("Distributed File System - Active Replication Client");
        pack();
        setLocationRelativeTo(null);

        // Auto-conectar al iniciar
        autoConnect();
    }

    private void initializeComponents() {
        fileNameField = new JTextField(25);

        // Enhanced text areas
        contentArea = new JTextArea(15, 60);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        resultArea = new JTextArea(8, 60);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        resultArea.setEditable(false);

        fileListArea = new JTextArea(12, 25);
        fileListArea.setEditable(false);
        fileListArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        // Buttons
        connectButton = new JButton("Reconnect");
        writeButton = new JButton("Write");
        readButton = new JButton("Read");
        deleteButton = new JButton("Delete");
        listFilesButton = new JButton("List Files");
        clearViewButton = new JButton("Clear View");
        refreshListButton = new JButton("Refresh");

        // Labels
        statusLabel = new JLabel("Status: Initializing...");
        replicationModeLabel = new JLabel("Mode: ACTIVE REPLICATION");
        replicationModeLabel.setFont(replicationModeLabel.getFont().deriveFont(Font.BOLD));
        replicationModeLabel.setForeground(new Color(0, 120, 0));

        // Load balancing combo
        loadBalancingCombo = new JComboBox<>(new String[]{"ROUND_ROBIN", "RANDOM", "PREFERRED"});
        loadBalancingCombo.setSelectedItem("ROUND_ROBIN");

        // Borders and styling
        contentArea.setBorder(BorderFactory.createTitledBorder("File Content"));
        resultArea.setBorder(BorderFactory.createTitledBorder("Operation Results"));
        fileListArea.setBorder(BorderFactory.createTitledBorder("Files in System (All Replicas)"));

        // Initially enable all buttons (will auto-connect)
        enableOperationButtons(false);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Top panel - status and controls
        JPanel topPanel = new JPanel(new BorderLayout());

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(replicationModeLabel);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(new JLabel("Load Balancing:"));
        statusPanel.add(loadBalancingCombo);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(connectButton);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(statusLabel);

        topPanel.add(statusPanel, BorderLayout.NORTH);

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

        // Create split panes
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, centerPanel);
        mainSplitPane.setDividerLocation(300);
        mainSplitPane.setResizeWeight(0.3);

        JSplitPane verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplitPane, resultPanel);
        verticalSplitPane.setDividerLocation(500);
        verticalSplitPane.setResizeWeight(0.7);

        add(topPanel, BorderLayout.NORTH);
        add(verticalSplitPane, BorderLayout.CENTER);

        setMinimumSize(new Dimension(1000, 700));
        setPreferredSize(new Dimension(1300, 900));
    }

    private void setupEventHandlers() {
        connectButton.addActionListener(e -> reconnectToSystem());
        writeButton.addActionListener(e -> performWrite());
        readButton.addActionListener(e -> performRead());
        deleteButton.addActionListener(e -> performDelete());
        listFilesButton.addActionListener(e -> performListFiles());
        refreshListButton.addActionListener(e -> performListFiles());
        clearViewButton.addActionListener(e -> clearFileView());

        // Load balancing change
        loadBalancingCombo.addActionListener(e -> {
            String strategy = (String) loadBalancingCombo.getSelectedItem();
            if (client != null) {
                reconnectWithNewStrategy(strategy);
            }
        });

        // Double-click on file list to open file
        fileListArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selectedText = getSelectedFileName();
                    if (selectedText != null && !selectedText.trim().isEmpty()) {
                        fileNameField.setText(selectedText.trim());
                        performRead();
                    }
                }
            }
        });
    }

    /**
     * 🔗 AUTO-CONECTAR AL SISTEMA
     */
    private void autoConnect() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: Connecting to replica network...");
            statusLabel.setForeground(Color.BLUE);
        });

        // Conectar en hilo separado
        new Thread(() -> {
            try {
                String strategy = (String) loadBalancingCombo.getSelectedItem();
                client = new MultiReplicaFileClient(strategy);

                SwingUtilities.invokeLater(() -> {
                    enableOperationButtons(true);
                    statusLabel.setText("Status: Connected to " + client.getAvailableReplicas().size() + " replicas");
                    statusLabel.setForeground(new Color(0, 120, 0));
                    appendResult("✅ Successfully connected to replica network");
                    appendResult("📊 Available replicas: " + client.getAvailableReplicas().size());
                    appendResult("🔄 Replication mode: ACTIVE (operations coordinated across all replicas)");

                    // Auto-load file list
                    performListFiles();

                    // Start auto-refresh
                    startAutoRefresh();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Connection failed - " + e.getMessage());
                    statusLabel.setForeground(Color.RED);
                    appendResult("❌ Failed to connect: " + e.getMessage());
                    enableOperationButtons(false);
                });
            }
        }).start();
    }

    /**
     * 🔄 RECONECTAR CON NUEVA ESTRATEGIA
     */
    private void reconnectWithNewStrategy(String strategy) {
        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Switching load balancing strategy...");
                    statusLabel.setForeground(Color.BLUE);
                });

                client = new MultiReplicaFileClient(strategy);

                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Connected with " + strategy + " strategy");
                    statusLabel.setForeground(new Color(0, 120, 0));
                    appendResult("🔄 Switched to " + strategy + " load balancing");
                    performListFiles();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Reconnection failed");
                    statusLabel.setForeground(Color.RED);
                    appendResult("❌ Reconnection failed: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * 🔗 RECONECTAR AL SISTEMA
     */
    private void reconnectToSystem() {
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
        }
        autoConnect();
    }

    /**
     * 📝 REALIZAR ESCRITURA
     */
    private void performWrite() {
        String fileName = fileNameField.getText().trim();
        String content = contentArea.getText();

        if (fileName.isEmpty()) {
            appendResult("❌ Error: File name is required");
            return;
        }

        if (client == null) {
            appendResult("❌ Error: Not connected to system");
            return;
        }

        // Ejecutar en hilo separado para no bloquear la GUI
        new Thread(() -> {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Status: Writing file (coordinating across replicas)...");
                statusLabel.setForeground(Color.BLUE);
            });

            OperationResult result = client.write(fileName, content);

            SwingUtilities.invokeLater(() -> {
                if (result.isSuccess()) {
                    statusLabel.setText("Status: File written successfully");
                    statusLabel.setForeground(new Color(0, 120, 0));
                    appendResult("📝 WRITE - SUCCESS: '" + fileName + "' written to all replicas (" + content.length() + " chars)");
                    performListFiles(); // Refresh file list
                } else {
                    statusLabel.setText("Status: Write failed");
                    statusLabel.setForeground(Color.RED);
                    appendResult("📝 WRITE - ERROR: " + result.getMessage());
                }
            });
        }).start();
    }

    /**
     * 📖 REALIZAR LECTURA
     */
    private void performRead() {
        String fileName = fileNameField.getText().trim();

        if (fileName.isEmpty()) {
            appendResult("❌ Error: File name is required");
            return;
        }

        if (client == null) {
            appendResult("❌ Error: Not connected to system");
            return;
        }

        new Thread(() -> {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Status: Reading file...");
                statusLabel.setForeground(Color.BLUE);
            });

            OperationResult result = client.read(fileName);

            SwingUtilities.invokeLater(() -> {
                if (result.isSuccess()) {
                    contentArea.setText(result.getContent());
                    contentArea.setCaretPosition(0);
                    statusLabel.setText("Status: File read successfully");
                    statusLabel.setForeground(new Color(0, 120, 0));
                    appendResult("📖 READ - SUCCESS: '" + fileName + "' loaded (" +
                            (result.getContent() != null ? result.getContent().length() : 0) + " chars)");
                } else {
                    contentArea.setText("");
                    statusLabel.setText("Status: Read failed");
                    statusLabel.setForeground(Color.RED);
                    appendResult("📖 READ - ERROR: " + result.getMessage());
                }
            });
        }).start();
    }

    /**
     * 🗑️ REALIZAR ELIMINACIÓN
     */
    private void performDelete() {
        String fileName = fileNameField.getText().trim();

        if (fileName.isEmpty()) {
            appendResult("❌ Error: File name is required");
            return;
        }

        // Confirmación de eliminación
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete '" + fileName + "' from all replicas?",
                "Confirm Delete - Active Replication",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (confirm != JOptionPane.YES_OPTION) {
            appendResult("🗑️ DELETE - CANCELLED by user");
            return;
        }

        if (client == null) {
            appendResult("❌ Error: Not connected to system");
            return;
        }

        new Thread(() -> {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Status: Deleting file from all replicas...");
                statusLabel.setForeground(Color.BLUE);
            });

            OperationResult result = client.delete(fileName);

            SwingUtilities.invokeLater(() -> {
                if (result.isSuccess()) {
                    contentArea.setText("");
                    fileNameField.setText("");
                    statusLabel.setText("Status: File deleted successfully");
                    statusLabel.setForeground(new Color(0, 120, 0));
                    appendResult("🗑️ DELETE - SUCCESS: '" + fileName + "' deleted from all replicas");
                    performListFiles(); // Refresh file list
                } else {
                    statusLabel.setText("Status: Delete failed");
                    statusLabel.setForeground(Color.RED);
                    appendResult("🗑️ DELETE - ERROR: " + result.getMessage());
                }
            });
        }).start();
    }

    /**
     * 📋 LISTAR ARCHIVOS
     */
    private void performListFiles() {
        if (client == null) {
            appendResult("❌ Error: Not connected to system");
            return;
        }

        new Thread(() -> {
            OperationResult result = client.listFiles();

            SwingUtilities.invokeLater(() -> {
                if (result.isSuccess()) {
                    String content = result.getContent();
                    if (content != null && !content.trim().isEmpty()) {
                        fileListArea.setText(content);
                        int fileCount = content.split("\n").length;
                        appendResult("📋 LIST - SUCCESS: " + fileCount + " files found");
                    } else {
                        fileListArea.setText("No files found");
                        appendResult("📋 LIST - INFO: No files in system");
                    }
                    statusLabel.setText("Status: File list updated");
                    statusLabel.setForeground(new Color(0, 120, 0));
                } else {
                    fileListArea.setText("Error loading file list");
                    appendResult("📋 LIST - ERROR: " + result.getMessage());
                    statusLabel.setText("Status: List failed");
                    statusLabel.setForeground(Color.RED);
                }
            });
        }).start();
    }

    /**
     * 🔄 INICIAR AUTO-REFRESH
     */
    private void startAutoRefresh() {
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
        }

        refreshScheduler = Executors.newSingleThreadScheduledExecutor();
        refreshScheduler.scheduleAtFixedRate(() -> {
            if (autoRefreshEnabled && client != null) {
                SwingUtilities.invokeLater(() -> performListFiles());
            }
        }, 30, 30, TimeUnit.SECONDS); // Refresh cada 30 segundos
    }

    /**
     * 🧹 LIMPIAR VISTA
     */
    private void clearFileView() {
        fileNameField.setText("");
        contentArea.setText("");
        appendResult("🧹 View cleared");
    }

    /**
     * 🔧 UTILIDADES
     */
    private void enableOperationButtons(boolean enabled) {
        writeButton.setEnabled(enabled);
        readButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
        listFilesButton.setEnabled(enabled);
        clearViewButton.setEnabled(enabled);
        refreshListButton.setEnabled(enabled);
        loadBalancingCombo.setEnabled(enabled);
    }

    private String getSelectedFileName() {
        String selectedText = fileListArea.getSelectedText();
        if (selectedText != null && !selectedText.trim().isEmpty()) {
            // Extraer solo el nombre del archivo (antes del paréntesis del tamaño)
            String[] parts = selectedText.trim().split("\\s+\\(");
            return parts[0];
        }
        return null;
    }

    private void appendResult(String message) {
        SwingUtilities.invokeLater(() -> {
            resultArea.append("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + message + "\n");
            resultArea.setCaretPosition(resultArea.getDocument().getLength());
        });
    }

    /**
     * 🛑 CLEANUP AL CERRAR
     */
    @Override
    public void dispose() {
        autoRefreshEnabled = false;
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
        }
        super.dispose();
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