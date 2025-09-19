import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class FaultTolerantClient extends JFrame {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080; // Puerto del coordinador
    private static final int REQUEST_TIMEOUT = 16000; // Aumentado para considerar coordinación
    private static final int MAX_RETRIES = 3;

    private ScheduledExecutorService scheduler;
    private int requestCounter = 1;
    private volatile boolean running = false;

    // Statistics
    private int successfulRequests = 0;
    private int timeoutRequests = 0;
    private int incorrectResponses = 0;
    private int errorResponses = 0;
    private int noConsensusResponses = 0;
    private int connectionFailures = 0;
    private int totalRequests = 0;

    // UI Components
    private JTextArea logArea;
    private JButton startButton, stopButton, resetStatsButton;
    private JSpinner intervalSpinner;
    private JLabel statsLabel;

    public FaultTolerantClient() {
        initializeUI();
        scheduler = Executors.newScheduledThreadPool(1);
    }

    private void initializeUI() {
        setTitle("Fault Tolerant Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(5,5,5,5);
        controlPanel.add(new JLabel("Request Interval (seconds):"), gbc);
        gbc.gridx = 1;
        intervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
        controlPanel.add(intervalSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        startButton = new JButton("Start Client");
        startButton.addActionListener(e -> startClient());
        controlPanel.add(startButton, gbc);

        gbc.gridx = 1;
        stopButton = new JButton("Stop Client");
        stopButton.addActionListener(e -> stopClient());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton, gbc);

        gbc.gridx = 2;
        resetStatsButton = new JButton("Reset Stats");
        resetStatsButton.addActionListener(e -> resetStatistics());
        controlPanel.add(resetStatsButton, gbc);

        add(controlPanel, BorderLayout.NORTH);

        // Statistics Panel
        statsLabel = new JLabel();
        updateStatistics();
        add(statsLabel, BorderLayout.SOUTH);

        // Log Area
        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void startClient() {
        running = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        int intervalSeconds = (Integer) intervalSpinner.getValue();
        appendLog("Client started with " + intervalSeconds + " second intervals");

        scheduler.scheduleAtFixedRate(this::sendRequest, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    private void stopClient() {
        running = false;
        scheduler.shutdown();
        scheduler = Executors.newScheduledThreadPool(1);

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        appendLog("Client stopped");
    }

    private void sendRequest() {
        if (!running) return;

        String request = "REQ_" + requestCounter++;
        totalRequests++;

        appendLog("Sending request to coordinator: " + request);

        boolean success = false;
        int retryCount = 0;

        while (!success && retryCount < MAX_RETRIES && running) {
            if (retryCount > 0) {
                appendLog("Retry attempt " + retryCount + " for: " + request);
            }

            RequestResult result = sendSingleRequest(request);

            switch (result) {
                case SUCCESS:
                    successfulRequests++;
                    appendLog("Request successful: " + request);
                    success = true;
                    break;
                case TIMEOUT:
                    timeoutRequests++;
                    appendLog("Request timeout: " + request);
                    retryCount++;
                    break;
                case CONNECTION_FAILURE:
                    connectionFailures++;
                    appendLog("Connection failure: " + request);
                    retryCount++;
                    break;
                case ERROR_RESPONSE:
                    errorResponses++;
                    appendLog("Error response received for: " + request);
                    retryCount++;
                    break;
                case NO_CONSENSUS:
                    noConsensusResponses++;
                    appendLog("No consensus reached for: " + request);
                    retryCount++;
                    break;
                case INCORRECT_RESPONSE:
                    incorrectResponses++;
                    appendLog("Incorrect response for: " + request);
                    retryCount++;
                    break;
            }
        }

        if (!success) {
            appendLog("Request failed after " + MAX_RETRIES + " retries: " + request);
        }

        updateStatistics();
    }

    private RequestResult sendSingleRequest(String request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), REQUEST_TIMEOUT);
            socket.setSoTimeout(REQUEST_TIMEOUT);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println(request);
                String response = in.readLine();

                if (response == null) {
                    return RequestResult.TIMEOUT;
                }

                // Accept any ACK response from any worker
                if (response.startsWith("ACK_W") && response.contains("_" + request)) {
                    appendLog("Received successful consensus response: " + response);
                    return RequestResult.SUCCESS;
                } else if (response.startsWith("ERROR_")) {
                    appendLog("Received error consensus response: " + response + " for request: " + request);
                    return RequestResult.ERROR_RESPONSE;
                } else if (response.startsWith("NO_CONSENSUS_")) {
                    appendLog("No consensus reached by coordinator for request: " + request + " (" + response + ")");
                    return RequestResult.NO_CONSENSUS;
                } else if (response.equals("TIMEOUT_COORDINATOR")) {
                    appendLog("Coordinator timeout for request: " + request);
                    return RequestResult.TIMEOUT;
                } else if (response.equals("ERROR_INSUFFICIENT_WORKERS")) {
                    appendLog("Insufficient workers for consensus: " + response + " for request: " + request);
                    return RequestResult.NO_CONSENSUS;
                } else {
                    appendLog("Unexpected response from coordinator: " + response + " for request: " + request);
                    return RequestResult.INCORRECT_RESPONSE;
                }
            }
        } catch (SocketTimeoutException e) {
            return RequestResult.TIMEOUT;
        } catch (ConnectException e) {
            return RequestResult.CONNECTION_FAILURE;
        } catch (IOException e) {
            appendLog("IO Error: " + e.getMessage());
            return RequestResult.CONNECTION_FAILURE;
        }
    }

    private void resetStatistics() {
        successfulRequests = 0;
        timeoutRequests = 0;
        incorrectResponses = 0;
        errorResponses = 0;
        noConsensusResponses = 0;
        connectionFailures = 0;
        totalRequests = 0;
        requestCounter = 1;
        updateStatistics();
        appendLog("Statistics reset");
    }

    private void updateStatistics() {
        SwingUtilities.invokeLater(() -> {
            String stats = String.format(
                    "<html>Statistics - Total: %d | Successful: %d | Timeouts: %d | Connection Failures: %d | Error Responses: %d | No Consensus: %d | Incorrect: %d</html>",
                    totalRequests, successfulRequests, timeoutRequests, connectionFailures, errorResponses, noConsensusResponses, incorrectResponses
            );
            statsLabel.setText(stats);
        });
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private enum RequestResult {
        SUCCESS, TIMEOUT, CONNECTION_FAILURE, ERROR_RESPONSE, NO_CONSENSUS, INCORRECT_RESPONSE
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new FaultTolerantClient().setVisible(true);
        });
    }
}