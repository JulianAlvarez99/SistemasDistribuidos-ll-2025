import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

public class FlatGroupLauncher extends JFrame {
    private static final int BASE_CLIENT_PORT = 8080;
    private static final int BASE_INTERNAL_PORT = 9080;

    private final List<FlatGroupProcess> processes = new ArrayList<>();
    private JSpinner processCountSpinner, baseClientPortSpinner, baseInternalPortSpinner;
    private JButton launchButton, stopAllButton;
    private JTextArea logArea;

    public FlatGroupLauncher() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Flat Group Process Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        controlPanel.add(new JLabel("Number of Processes (2K for consensus):"), gbc);
        gbc.gridx = 1;
        processCountSpinner = new JSpinner(new SpinnerNumberModel(4, 3, 10, 1));
        controlPanel.add(processCountSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        controlPanel.add(new JLabel("Base Client Port:"), gbc);
        gbc.gridx = 1;
        baseClientPortSpinner = new JSpinner(new SpinnerNumberModel(BASE_CLIENT_PORT, 8000, 9000, 1));
        controlPanel.add(baseClientPortSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        controlPanel.add(new JLabel("Base Internal Port:"), gbc);
        gbc.gridx = 1;
        baseInternalPortSpinner = new JSpinner(new SpinnerNumberModel(BASE_INTERNAL_PORT, 9000, 10000, 1));
        controlPanel.add(baseInternalPortSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        launchButton = new JButton("Launch Group");
        launchButton.addActionListener(e -> launchProcesses());
        controlPanel.add(launchButton, gbc);

        gbc.gridx = 1;
        stopAllButton = new JButton("Stop All");
        stopAllButton.addActionListener(e -> stopAllProcesses());
        stopAllButton.setEnabled(false);
        controlPanel.add(stopAllButton, gbc);

        add(controlPanel, BorderLayout.NORTH);

        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("Architecture Information"));
        JTextArea infoArea = new JTextArea(4, 60);
        infoArea.setEditable(false);
        infoArea.setText("Minimized Messages Architecture:\n" +
                "- Leader selection by consistent hashing (no election messages)\n" +
                "- Direct voting to leader (2N messages per consensus: N vote_requests + N votes)\n" +
                "- Periodic membership announcements (every 3s)\n" +
                "- Bootstrap discovery at startup for immediate group formation");
        infoPanel.add(new JScrollPane(infoArea), BorderLayout.CENTER);
        add(infoPanel, BorderLayout.SOUTH);

        logArea = new JTextArea(15, 60);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopAllProcesses();
            }
        });
    }

    private void launchProcesses() {
        int count = (Integer) processCountSpinner.getValue();
        int baseClientPort = (Integer) baseClientPortSpinner.getValue();
        int baseInternalPort = (Integer) baseInternalPortSpinner.getValue();

        appendLog("Launching " + count + " processes...");
        appendLog("Consensus requires " + ((count/2)+1) + " votes out of " + count);
        appendLog("Client ports: " + baseClientPort + "-" + (baseClientPort + count - 1));
        appendLog("Internal ports: " + baseInternalPort + "-" + (baseInternalPort + count - 1));

        // Build bootstrap list with ALL processes
        List<FlatGroupProcess.ProcessInfo> bootstrapList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int processId = i + 1;
            int internalPort = baseInternalPort + i;
            bootstrapList.add(new FlatGroupProcess.ProcessInfo(processId, "localhost", internalPort));
        }

        appendLog("Bootstrap list created with " + bootstrapList.size() + " members");

        // Create all processes with the complete bootstrap list
        for (int i = 0; i < count; i++) {
            int processId = i + 1;
            int clientPort = baseClientPort + i;
            int internalPort = baseInternalPort + i;

            FlatGroupProcess process = new FlatGroupProcess(processId, clientPort, internalPort, bootstrapList);
            process.setLocation(50 + (i % 3) * 450, 50 + (i / 3) * 450);
            process.setVisible(true);

            processes.add(process);
            appendLog("Created Process " + processId + " - Client: " + clientPort + ", Internal: " + internalPort);
        }

        // Auto-start processes with staggered delay
        appendLog("Starting processes with staggered delay...");
        for (int i = 0; i < processes.size(); i++) {
            final int index = i;
            final int delay = i * 300;
            Timer timer = new Timer(delay, e -> {
                processes.get(index).startButton.doClick();
            });
            timer.setRepeats(false);
            timer.start();
        }

        launchButton.setEnabled(false);
        stopAllButton.setEnabled(true);
        appendLog("All processes scheduled for startup");
        appendLog("Client can connect to ANY process (ports " + baseClientPort + "-" + (baseClientPort + count - 1) + ")");
    }

    private void stopAllProcesses() {
        appendLog("Stopping all processes...");
        for (FlatGroupProcess process : processes) {
            process.dispose();
        }
        processes.clear();
        launchButton.setEnabled(true);
        stopAllButton.setEnabled(false);
        appendLog("All processes stopped.");
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new FlatGroupLauncher().setVisible(true);
        });
    }
}