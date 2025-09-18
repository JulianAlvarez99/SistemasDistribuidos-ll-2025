// ============ SERVIDOR ============

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaultTolerantServer extends JFrame {
    private static final int PORT = 8080;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;
    private Random random = new Random();

    // UI Components
    private JTextArea logArea;
    private JButton startButton, stopButton;
    private JSpinner connectionFailureRate, responseDelayRate, incorrectResponseRate;

    public FaultTolerantServer() {
        initializeUI();
        threadPool = Executors.newCachedThreadPool();
    }

    private void initializeUI() {
        setTitle("Fault Tolerant Server");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(5,5,5,5);
        controlPanel.add(new JLabel("Connection Failure Rate (%):"), gbc);
        gbc.gridx = 1;
        connectionFailureRate = new JSpinner(new SpinnerNumberModel(20, 0, 100, 5));
        controlPanel.add(connectionFailureRate, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        controlPanel.add(new JLabel("Response Delay Rate (%):"), gbc);
        gbc.gridx = 1;
        responseDelayRate = new JSpinner(new SpinnerNumberModel(15, 0, 100, 5));
        controlPanel.add(responseDelayRate, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        controlPanel.add(new JLabel("Incorrect Response Rate (%):"), gbc);
        gbc.gridx = 1;
        incorrectResponseRate = new JSpinner(new SpinnerNumberModel(10, 0, 100, 5));
        controlPanel.add(incorrectResponseRate, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        startButton = new JButton("Start Server");
        startButton.addActionListener(e -> startServer());
        controlPanel.add(startButton, gbc);

        gbc.gridx = 1;
        stopButton = new JButton("Stop Server");
        stopButton.addActionListener(e -> stopServer());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton, gbc);

        add(controlPanel, BorderLayout.NORTH);

        // Log Area
        logArea = new JTextArea(20, 50);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            running = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            appendLog("Server started on port " + PORT);

            threadPool.submit(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.submit(new ClientHandler(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            appendLog("Error accepting connection: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (IOException e) {
            appendLog("Failed to start server: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            appendLog("Error stopping server: " + e.getMessage());
        }
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        appendLog("Server stopped");
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                String request = in.readLine();
                if (request != null) {
                    processRequest(request, out);
                }
            } catch (IOException e) {
                appendLog("Error handling client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    appendLog("Error closing client socket: " + e.getMessage());
                }
            }
        }

        private void processRequest(String request, PrintWriter out) {
            int connFailRate = (Integer) connectionFailureRate.getValue();
            int delayRate = (Integer) responseDelayRate.getValue();
            int incorrectRate = (Integer) incorrectResponseRate.getValue();

            appendLog("Received request: " + request);

            // Connection failure simulation
            if (random.nextInt(100) < connFailRate) {
                appendLog("Simulating connection failure for: " + request);
                try {
                    clientSocket.close();
                } catch (IOException e) {}
                return;
            }

            // Response delay simulation
            if (random.nextInt(100) < delayRate) {
                int delayMs = 3000 + random.nextInt(5000);
                appendLog("Simulating delay of " + delayMs + "ms for: " + request);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Incorrect response simulation
            if (random.nextInt(100) < incorrectRate) {
                String incorrectResponse = "ERROR_" + random.nextInt(1000);
                appendLog("Sending incorrect response: " + incorrectResponse);
                out.println(incorrectResponse);
                return;
            }

            // Normal response
            String response = "ACK_" + request;
            appendLog("Sending response: " + response);
            out.println(response);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new FaultTolerantServer().setVisible(true);
        });
    }
}


