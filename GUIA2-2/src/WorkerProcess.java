import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkerProcess {
    private final int workerId;
    private final int port;
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random random = new Random();
    private WorkerProcessListener listener;

    // Fault injection rates (configurable per worker)
    private int connectionFailureRate = 20;
    private int responseDelayRate = 15;
    private int incorrectResponseRate = 10;
    private int baseDelayMs = 100;
    private int maxDelayMs = 2000;

    public interface WorkerProcessListener {
        void onWorkerLog(int workerId, String message);
        void onWorkerStopped(int workerId);
    }

    public WorkerProcess(int workerId, int port, WorkerProcessListener listener) {
        this.workerId = workerId;
        this.port = port;
        this.listener = listener;
    }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(port);
            running.set(true);

            Thread workerThread = new Thread(this::run);
            workerThread.setName("Worker-" + workerId);
            workerThread.setDaemon(true);
            workerThread.start();

            logMessage("Started on port " + port);
            return true;
        } catch (IOException e) {
            logMessage("Failed to start: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            logMessage("Error stopping: " + e.getMessage());
        }
        logMessage("Stopped");
        if (listener != null) {
            listener.onWorkerStopped(workerId);
        }
    }

    private void run() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                processRequest(clientSocket);
            } catch (IOException e) {
                if (running.get()) {
                    logMessage("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void processRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request = in.readLine();
            if (request != null) {
                handleRequest(request, out, clientSocket);
            }
        } catch (IOException e) {
            logMessage("Error processing request: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {}
        }
    }

    private void handleRequest(String request, PrintWriter out, Socket clientSocket) {
        logMessage("Processing request: " + request);

        // Random base delay for this worker
        try {
            Thread.sleep(baseDelayMs + random.nextInt(maxDelayMs - baseDelayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Connection failure simulation
        if (random.nextInt(100) < connectionFailureRate) {
            logMessage("Simulating connection failure for: " + request);
            try {
                clientSocket.close();
            } catch (IOException e) {}
            return;
        }

        // Response delay simulation
        if (random.nextInt(100) < responseDelayRate) {
            int delayMs = 1000 + random.nextInt(3000);
            logMessage("Adding extra delay of " + delayMs + "ms for: " + request);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Incorrect response simulation
        if (random.nextInt(100) < incorrectResponseRate) {
            String incorrectResponse = "ERROR_W" + workerId + "_" + random.nextInt(1000);
            logMessage("Sending incorrect response: " + incorrectResponse);
            out.println(incorrectResponse);
            return;
        }

        // Normal response
        String response = "ACK_W" + workerId + "_" + request;
        logMessage("Sending response: " + response);
        out.println(response);
    }

    private void logMessage(String message) {
        if (listener != null) {
            listener.onWorkerLog(workerId, message);
        }
    }

    public int getWorkerId() { return workerId; }
    public int getPort() { return port; }
    public boolean isRunning() { return running.get(); }

    public void setFaultRates(int connFailure, int delay, int incorrect) {
        this.connectionFailureRate = connFailure;
        this.responseDelayRate = delay;
        this.incorrectResponseRate = incorrect;
    }

    public void setDelayParams(int base, int max) {
        this.baseDelayMs = base;
        this.maxDelayMs = max;
    }
}