// ============ LAUNCHER (Para iniciar múltiples procesos) ============

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
    private JSpinner processCountSpinner;
    private JButton launchButton, stopAllButton;
    private JTextArea logArea;

    public FlatGroupLauncher() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Flat Group Process Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Control Panel
        JPanel controlPanel = new JPanel(new FlowLayout());
        controlPanel.add(new JLabel("Number of Processes (2K for consensus):"));
        processCountSpinner = new JSpinner(new SpinnerNumberModel(4, 3, 10, 1));
        controlPanel.add(processCountSpinner);

        launchButton = new JButton("Launch Group");
        launchButton.addActionListener(e -> launchProcesses());
        controlPanel.add(launchButton);

        stopAllButton = new JButton("Stop All");
        stopAllButton.addActionListener(e -> stopAllProcesses());
        stopAllButton.setEnabled(false);
        controlPanel.add(stopAllButton);

        add(controlPanel, BorderLayout.NORTH);

        // Info Panel
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("Information"));
        JTextArea infoArea = new JTextArea(3, 60);
        infoArea.setEditable(false);
        infoArea.setText("Minimized Messages Architecture:\n" +
                "- Leader selection by consistent hashing (no election messages)\n" +
                "- Direct voting to leader (2N messages per consensus)\n" +
                "- Periodic membership announcements (every 3s)");
        infoPanel.add(new JScrollPane(infoArea), BorderLayout.CENTER);
        add(infoPanel, BorderLayout.SOUTH);

        // Log Area
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
        appendLog("Launching " + count + " processes...");
        appendLog("Consensus requires " + ((count/2)+1) + " votes out of " + count);

        for (int i = 0; i < count; i++) {
            int processId = i + 1;
            int clientPort = BASE_CLIENT_PORT + i;
            int internalPort = BASE_INTERNAL_PORT + i;

            FlatGroupProcess process = new FlatGroupProcess(processId, clientPort, internalPort);
            process.setLocation(50 + (i % 3) * 450, 50 + (i / 3) * 450);
            process.setVisible(true);

            // Auto-start the process after a short delay
            final int delay = i * 200;
            Timer timer = new Timer(delay, e -> {
                process.startButton.doClick();
            });
            timer.setRepeats(false);
            timer.start();

            processes.add(process);
            appendLog("Launched Process " + processId + " - Client: " + clientPort + ", Internal: " + internalPort);
        }

        launchButton.setEnabled(false);
        stopAllButton.setEnabled(true);
        appendLog("All processes launched. Connect client to any process (ports " + BASE_CLIENT_PORT + "-" + (BASE_CLIENT_PORT + count - 1) + ")");
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