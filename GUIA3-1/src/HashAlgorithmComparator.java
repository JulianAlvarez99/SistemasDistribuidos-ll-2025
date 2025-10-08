import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HashAlgorithmComparator extends JFrame {
    private static final String[] ALGORITHMS = {"MD5", "SHA-1", "SHA-256"};
    private static final int[] TEST_SIZES = {100, 1000, 10000, 100000, 1000000}; // bytes
    private static final int ITERATIONS = 1000;

    private JTextArea inputArea;
    private JTextArea outputArea;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JButton hashButton, benchmarkButton, clearButton;
    private JProgressBar progressBar;

    public HashAlgorithmComparator() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Hash Algorithm Comparator - MD5 vs SHA-1 vs SHA-256");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Top Panel - Input
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.setBorder(BorderFactory.createTitledBorder("Input Message"));

        inputArea = new JTextArea(5, 50);
        inputArea.setLineWrap(true);
        inputArea.setText("Hello, Distributed Systems Security!");
        topPanel.add(new JScrollPane(inputArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hashButton = new JButton("Calculate Hashes");
        hashButton.addActionListener(e -> calculateHashes());

        benchmarkButton = new JButton("Run Performance Benchmark");
        benchmarkButton.addActionListener(e -> runBenchmark());

        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearAll());

        buttonPanel.add(hashButton);
        buttonPanel.add(benchmarkButton);
        buttonPanel.add(clearButton);
        topPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        // Center Panel - Results Table
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBorder(BorderFactory.createTitledBorder("Hash Results"));

        String[] columnNames = {"Algorithm", "Hash Length (bits)", "Hash Length (hex)", "Hash Value", "Time (µs)"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(400);

        centerPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // Bottom Panel - Output and Progress
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        outputArea = new JTextArea(10, 50);
        outputArea.setEditable(false);
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Benchmark Results & Analysis"));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        bottomPanel.add(outputScroll, BorderLayout.CENTER);
        bottomPanel.add(progressBar, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);

        // Initial info
        displayAlgorithmInfo();

        pack();
        setLocationRelativeTo(null);
    }

    private void displayAlgorithmInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== HASH ALGORITHM COMPARISON ===\n\n");
        info.append("MD5 (Message Digest 5):\n");
        info.append("  - Hash Length: 128 bits (16 bytes, 32 hex chars)\n");
        info.append("  - Status: CRYPTOGRAPHICALLY BROKEN (collisions found)\n");
        info.append("  - Speed: Fastest\n");
        info.append("  - Use: Checksums only, NOT for security\n\n");

        info.append("SHA-1 (Secure Hash Algorithm 1):\n");
        info.append("  - Hash Length: 160 bits (20 bytes, 40 hex chars)\n");
        info.append("  - Status: DEPRECATED (practical collisions demonstrated)\n");
        info.append("  - Speed: Medium\n");
        info.append("  - Use: Legacy systems only, migrate to SHA-256\n\n");

        info.append("SHA-256 (Secure Hash Algorithm 256):\n");
        info.append("  - Hash Length: 256 bits (32 bytes, 64 hex chars)\n");
        info.append("  - Status: SECURE (current standard)\n");
        info.append("  - Speed: Slower than MD5/SHA-1, but acceptable\n");
        info.append("  - Use: Cryptographic applications, digital signatures, blockchain\n\n");

        outputArea.setText(info.toString());
    }

    private void calculateHashes() {
        String message = inputArea.getText();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a message", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        tableModel.setRowCount(0);
        byte[] messageBytes = message.getBytes();

        for (String algorithm : ALGORITHMS) {
            try {
                long startTime = System.nanoTime();
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                byte[] hashBytes = digest.digest(messageBytes);
                long endTime = System.nanoTime();

                String hashHex = bytesToHex(hashBytes);
                int hashLengthBits = hashBytes.length * 8;
                int hashLengthHex = hashHex.length();
                double timeMicros = (endTime - startTime) / 1000.0;

                tableModel.addRow(new Object[]{
                        algorithm,
                        hashLengthBits,
                        hashLengthHex,
                        hashHex,
                        String.format("%.3f", timeMicros)
                });

            } catch (NoSuchAlgorithmException e) {
                appendOutput("Error: " + algorithm + " not available\n");
            }
        }

        appendOutput("\n--- Single Hash Calculation Complete ---\n");
        appendOutput("Message length: " + messageBytes.length + " bytes\n");
    }

    private void runBenchmark() {
        benchmarkButton.setEnabled(false);
        hashButton.setEnabled(false);
        progressBar.setValue(0);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish("\n=== PERFORMANCE BENCHMARK ===\n");
                publish("Testing with " + ITERATIONS + " iterations per size\n\n");

                int totalTests = ALGORITHMS.length * TEST_SIZES.length;
                int currentTest = 0;

                for (int size : TEST_SIZES) {
                    publish(String.format("\n--- Testing with %d bytes message ---\n", size));
                    byte[] testData = generateRandomBytes(size);

                    List<BenchmarkResult> results = new ArrayList<>();

                    for (String algorithm : ALGORITHMS) {
                        currentTest++;
                        int progress = (currentTest * 100) / totalTests;
                        setProgress(progress);

                        BenchmarkResult result = benchmarkAlgorithm(algorithm, testData);
                        results.add(result);

                        publish(String.format("%s: avg=%.3f µs, min=%.3f µs, max=%.3f µs, throughput=%.2f MB/s\n",
                                algorithm,
                                result.avgTimeMicros,
                                result.minTimeMicros,
                                result.maxTimeMicros,
                                result.throughputMBps));
                    }

                    // Calculate speed ratios
                    double md5Speed = results.get(0).avgTimeMicros;
                    publish(String.format("\nSpeed Ratios (relative to MD5):\n"));
                    for (BenchmarkResult result : results) {
                        double ratio = result.avgTimeMicros / md5Speed;
                        publish(String.format("  %s: %.2fx %s\n",
                                result.algorithm,
                                ratio,
                                ratio < 1 ? "(faster)" : ratio > 1 ? "(slower)" : "(same)"));
                    }
                }

                publish("\n=== SECURITY ANALYSIS ===\n");
                publish("Collision Resistance (theoretical):\n");
                publish("  MD5:     2^64 operations (BROKEN - practical attacks exist)\n");
                publish("  SHA-1:   2^80 operations (BROKEN - collision found in 2017)\n");
                publish("  SHA-256: 2^128 operations (SECURE - no known attacks)\n\n");

                publish("Preimage Resistance (theoretical):\n");
                publish("  MD5:     2^128 operations (weakened)\n");
                publish("  SHA-1:   2^160 operations (weakened)\n");
                publish("  SHA-256: 2^256 operations (secure)\n\n");

                publish("=== RECOMMENDATION ===\n");
                publish("✓ Use SHA-256 for: Digital signatures, certificates, blockchain\n");
                publish("✓ Use SHA-1 only for: Legacy compatibility (migrate ASAP)\n");
                publish("✓ Use MD5 only for: Non-security checksums (file integrity)\n");
                publish("✗ NEVER use MD5 or SHA-1 for: Passwords, authentication, crypto\n");

                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    appendOutput(message);
                }
            }

            @Override
            protected void done() {
                benchmarkButton.setEnabled(true);
                hashButton.setEnabled(true);
                progressBar.setValue(100);
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private BenchmarkResult benchmarkAlgorithm(String algorithm, byte[] data) throws NoSuchAlgorithmException {
        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        MessageDigest digest = MessageDigest.getInstance(algorithm);

        // Warmup
        for (int i = 0; i < 100; i++) {
            digest.reset();
            digest.digest(data);
        }

        // Actual benchmark
        for (int i = 0; i < ITERATIONS; i++) {
            digest.reset();
            long startTime = System.nanoTime();
            digest.digest(data);
            long endTime = System.nanoTime();

            long elapsed = endTime - startTime;
            totalTime += elapsed;
            minTime = Math.min(minTime, elapsed);
            maxTime = Math.max(maxTime, elapsed);
        }

        double avgTimeMicros = (totalTime / (double) ITERATIONS) / 1000.0;
        double minTimeMicros = minTime / 1000.0;
        double maxTimeMicros = maxTime / 1000.0;

        // Throughput in MB/s
        double avgTimeSeconds = avgTimeMicros / 1_000_000.0;
        double throughputMBps = (data.length / (1024.0 * 1024.0)) / avgTimeSeconds;

        return new BenchmarkResult(algorithm, avgTimeMicros, minTimeMicros, maxTimeMicros, throughputMBps);
    }

    private byte[] generateRandomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random().nextBytes(bytes);
        return bytes;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void clearAll() {
        inputArea.setText("");
        tableModel.setRowCount(0);
        outputArea.setText("");
        progressBar.setValue(0);
        displayAlgorithmInfo();
    }

    private void appendOutput(String text) {
        outputArea.append(text);
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    private static class BenchmarkResult {
        String algorithm;
        double avgTimeMicros;
        double minTimeMicros;
        double maxTimeMicros;
        double throughputMBps;

        BenchmarkResult(String algorithm, double avg, double min, double max, double throughput) {
            this.algorithm = algorithm;
            this.avgTimeMicros = avg;
            this.minTimeMicros = min;
            this.maxTimeMicros = max;
            this.throughputMBps = throughput;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new HashAlgorithmComparator().setVisible(true);
        });
    }
}