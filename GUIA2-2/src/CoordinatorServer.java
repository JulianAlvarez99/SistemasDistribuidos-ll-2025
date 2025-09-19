import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.util.List;

public class CoordinatorServer extends JFrame implements WorkerProcess.WorkerProcessListener {
    private static final int COORDINATOR_PORT = 8080;
    private static final int WORKER_BASE_PORT = 8100;
    private static final int COORDINATOR_TIMEOUT = 30000;

    private ServerSocket coordinatorSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;
    private final AtomicInteger nextWorkerId = new AtomicInteger(1);
    private final Map<Integer, WorkerProcess> workers = new ConcurrentHashMap<>();

    // UI Components
    private JTextArea logArea;
    private JButton startButton, stopButton, addWorkerButton, removeWorkerButton;
    private JSpinner workerCountSpinner;
    private JList<String> workerList;
    private DefaultListModel<String> workerListModel;

    public CoordinatorServer() {
        initializeUI();
        threadPool = Executors.newCachedThreadPool();
    }

    private JSpinner connectionFailureRate, responseDelayRate, incorrectResponseRate;

    private void initializeUI() {
        setTitle("Coordinator Server with Dynamic Worker Pool");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Control Panel
        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(5,5,5,5);
        startButton = new JButton("Start Coordinator");
        startButton.addActionListener(e -> startCoordinator());
        controlPanel.add(startButton, gbc);

        gbc.gridx = 1;
        stopButton = new JButton("Stop Coordinator");
        stopButton.addActionListener(e -> stopCoordinator());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.insets = new Insets(5,5,5,5);
        controlPanel.add(new JLabel("Connection Failure Rate (%):"), gbc);
        gbc.gridx = 3;
        connectionFailureRate = new JSpinner(new SpinnerNumberModel(20, 0, 100, 5));
        controlPanel.add(connectionFailureRate, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        controlPanel.add(new JLabel("Initial Workers:"), gbc);
        gbc.gridx = 1;
        workerCountSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        controlPanel.add(workerCountSpinner, gbc);

        gbc.gridx = 2; gbc.gridy = 1;
        controlPanel.add(new JLabel("Response Delay Rate (%):"), gbc);
        gbc.gridx = 3;
        responseDelayRate = new JSpinner(new SpinnerNumberModel(15, 0, 100, 5));
        controlPanel.add(responseDelayRate, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        addWorkerButton = new JButton("Add Worker");
        addWorkerButton.addActionListener(e -> addWorker());
        addWorkerButton.setEnabled(false);
        controlPanel.add(addWorkerButton, gbc);

        gbc.gridx = 1;
        removeWorkerButton = new JButton("Remove Selected Worker");
        removeWorkerButton.addActionListener(e -> removeSelectedWorker());
        removeWorkerButton.setEnabled(false);
        controlPanel.add(removeWorkerButton, gbc);

        gbc.gridx = 2; gbc.gridy = 2;
        controlPanel.add(new JLabel("Incorrect Response Rate (%):"), gbc);
        gbc.gridx = 3;
        incorrectResponseRate = new JSpinner(new SpinnerNumberModel(10, 0, 100, 5));
        controlPanel.add(incorrectResponseRate, gbc);

        add(controlPanel, BorderLayout.NORTH);

        // Workers Panel
        JPanel workersPanel = new JPanel(new BorderLayout());
        workersPanel.setBorder(BorderFactory.createTitledBorder("Active Workers"));
        workerListModel = new DefaultListModel<>();
        workerList = new JList<>(workerListModel);
        workerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        workersPanel.add(new JScrollPane(workerList), BorderLayout.CENTER);
        workersPanel.setPreferredSize(new Dimension(300, 200));
        add(workersPanel, BorderLayout.EAST);

        // Log Area
        logArea = new JTextArea(25, 50);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void startCoordinator() {
        try {
            coordinatorSocket = new ServerSocket(COORDINATOR_PORT);
            running = true;

            // Create initial workers
            int initialWorkers = (Integer) workerCountSpinner.getValue();
            for (int i = 0; i < initialWorkers; i++) {
                addWorker();
            }

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            addWorkerButton.setEnabled(true);
            removeWorkerButton.setEnabled(true);

            appendLog("Coordinator started on port " + COORDINATOR_PORT);

            threadPool.submit(() -> {
                while (running) {
                    try {
                        Socket clientSocket = coordinatorSocket.accept();
                        threadPool.submit(new ClientRequestHandler(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            appendLog("Error accepting client connection: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (IOException e) {
            appendLog("Failed to start coordinator: " + e.getMessage());
        }
    }

    private void stopCoordinator() {
        running = false;

        // Stop all workers
        new ArrayList<>(workers.values()).forEach(WorkerProcess::stop);
        workers.clear();
        updateWorkerList();

        try {
            if (coordinatorSocket != null) coordinatorSocket.close();
        } catch (IOException e) {
            appendLog("Error stopping coordinator: " + e.getMessage());
        }

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        addWorkerButton.setEnabled(false);
        removeWorkerButton.setEnabled(false);

        appendLog("Coordinator stopped");
    }

    private void addWorker() {
        int workerId = nextWorkerId.getAndIncrement();
        int port = WORKER_BASE_PORT + workerId;

        WorkerProcess worker = getWorkerProcess(workerId, port);

        if (worker.start()) {
            workers.put(workerId, worker);
            updateWorkerList();
            appendLog("Added worker " + workerId + " on port " + port);
        } else {
            appendLog("Failed to add worker " + workerId);
        }
    }

    private WorkerProcess getWorkerProcess(int workerId, int port) {
        int connFailRate = (Integer) connectionFailureRate.getValue();
        int delayRate = (Integer) responseDelayRate.getValue();
        int incorrectRate = (Integer) incorrectResponseRate.getValue();

        WorkerProcess worker = new WorkerProcess(workerId, port, this);
        Random rand = new Random();
        worker.setFaultRates(connFailRate, delayRate, incorrectRate);
        worker.setDelayParams(500 + rand.nextInt(1000), 5000 + rand.nextInt(10000));
        return worker;
    }

    private void removeSelectedWorker() {
        String selected = workerList.getSelectedValue();
        if (selected != null) {
            try {
                int workerId = Integer.parseInt(selected.split(" ")[1]);
                WorkerProcess worker = workers.remove(workerId);
                if (worker != null) {
                    worker.stop();
                    appendLog("Removed worker " + workerId);
                }
            } catch (NumberFormatException e) {
                appendLog("Invalid worker selection");
            }
        }
    }

    private void updateWorkerList() {
        SwingUtilities.invokeLater(() -> {
            workerListModel.clear();
            workers.keySet().stream().sorted().forEach(id ->
                    workerListModel.addElement("Worker " + id + " (port " + workers.get(id).getPort() + ")")
            );
        });
    }

    private class ClientRequestHandler implements Runnable {
        private final Socket clientSocket;

        public ClientRequestHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                String request = in.readLine();
                if (request != null) {
                    appendLog("Coordinator received request: " + request);
                    String response = coordinateRequest(request);
                    if (response != null) {
                        out.println(response);
                        appendLog("Coordinator forwarded response: " + response);
                    } else {
                        // Send explicit timeout response to client
                        String timeoutResponse = "TIMEOUT_COORDINATOR";
                        out.println(timeoutResponse);
                        appendLog("Coordinator timeout, sent: " + timeoutResponse);
                    }
                }
            } catch (IOException e) {
                appendLog("Error handling client request: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {}
            }
        }
    }

    private String coordinateRequest(String request) {
        if (workers.isEmpty()) {
            appendLog("No workers available to process request: " + request);
            return null;
        }

        // Create futures for all worker requests with worker references
        Map<Future<String>, WorkerProcess> futureToWorkerMap = new HashMap<>();
        CompletionService<String> completionService = new ExecutorCompletionService<>(threadPool);

        for (WorkerProcess worker : workers.values()) {
            Future<String> future = completionService.submit(() -> sendRequestToWorker(worker, request));
            futureToWorkerMap.put(future, worker);
        }

        appendLog("Sent request to " + workers.size() + " workers");

        // Wait for first response (success or error)
        try {
            Future<String> firstCompleted = completionService.poll(COORDINATOR_TIMEOUT, TimeUnit.MILLISECONDS);
            if (firstCompleted != null) {
                String response = firstCompleted.get();
                if (response != null) {
                    // Notify all other workers to abort regardless of response type
                    notifyWorkersToAbort(futureToWorkerMap, firstCompleted);
                    if (response.startsWith("ACK_")) {
                        appendLog("First successful response received, notified other workers to abort");
                    } else if (response.startsWith("ERROR_")) {
                        appendLog("First error response received, notified other workers to abort");
                    } else {
                        appendLog("First response received (unknown type), notified other workers to abort");
                    }
                    return response;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            appendLog("Error waiting for worker responses: " + e.getMessage());
        }

        // Cancel all remaining requests and notify workers to abort
        futureToWorkerMap.forEach((future, worker) -> {
            future.cancel(true);
            worker.abortCurrentRequest();
        });
        return null;
    }

    private void notifyWorkersToAbort(Map<Future<String>, WorkerProcess> futureToWorkerMap, Future<String> completedFuture) {
        futureToWorkerMap.forEach((future, worker) -> {
            if (future != completedFuture) {
                future.cancel(true);
                worker.abortCurrentRequest();
            }
        });
    }

    private String sendRequestToWorker(WorkerProcess worker, String request) {
        try (Socket socket = new Socket("localhost", worker.getPort())) {
            socket.setSoTimeout(COORDINATOR_TIMEOUT);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println(request);
                return in.readLine();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onWorkerLog(int workerId, String message) {
        appendLog("Worker " + workerId + ": " + message);
    }

    @Override
    public void onWorkerStopped(int workerId) {
        workers.remove(workerId);
        updateWorkerList();
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + System.currentTimeMillis() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new CoordinatorServer().setVisible(true);
        });
    }
}