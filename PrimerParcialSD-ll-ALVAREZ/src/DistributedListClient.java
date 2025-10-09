import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class DistributedListClient {
    private JPanel rootPanel;
    private JPanel configPanel;
    private JLabel nicknameLabel;
    private JTextField nicknameField;
    private JLabel delayLabel;
    private JTextField delayField;
    private JPanel listP;
    private JScrollPane listScroll;
    private JButton connectButton;
    private JRadioButton strictConsistencyRadioButton;
    private JRadioButton continuousConsistencyRadioButton;
    private JTextField itemTextField;
    private JButton addItemButton;
    private JTextArea logArea;
    private JLabel itemLabel;
    private JList<String> itemList;
    private JPanel listPanel;
    private JCheckBox airplaneModeToggleButton;
    private JButton disconnectButton;

    private DefaultListModel<String> listModel;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean isConnected = false;
    private volatile boolean isAirplaneMode = false;
    private int lastProcessedSequenceNumber = 0;

    private final List<String> pendingItems = new ArrayList<>();
    private Timer continuousConsistencyTimer;
    private final Random random = new Random();

    public DistributedListClient() {
        listModel = new DefaultListModel<>();
        itemList.setModel(listModel);

        connectButton.addActionListener(e -> connectToServer());
        disconnectButton.addActionListener(e -> disconnectFromServer());
        addItemButton.addActionListener(e -> addItem());
        airplaneModeToggleButton.addActionListener(e -> toggleAirplaneMode());

        ButtonGroup consistencyGroup = new ButtonGroup();
        consistencyGroup.add(strictConsistencyRadioButton);
        consistencyGroup.add(continuousConsistencyRadioButton);
        strictConsistencyRadioButton.setSelected(true);

        setUIEnabledState(false);
    }

    private void connectToServer() {
        String nickname = nicknameField.getText().trim();
        if (nickname.isEmpty()) {
            log("Nickname cannot be empty.");
            return;
        }

        try {
            socket = new Socket("localhost", 9090);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            isConnected = true;

            sendMessage("CONNECT|" + nickname);

            new Thread(this::listenToServer).start();

            log("Connected to the server as " + nickname);
            setUIEnabledState(true);

        } catch (IOException e) {
            log("Connection failed: " + e.getMessage());
        }
    }

    private void disconnectFromServer() {
        try {
            if (socket != null && !socket.isClosed()) {
                isConnected = false;
                socket.close(); // This will interrupt the listening thread
                log("Disconnected from server.");
            }
        } catch (IOException e) {
            log("Error disconnecting: " + e.getMessage());
        } finally {
            setUIEnabledState(false);
            listModel.clear();
            lastProcessedSequenceNumber = 0;
        }
    }

    private void listenToServer() {
        try {
            String encryptedResponse;
            while (isConnected && (encryptedResponse = in.readLine()) != null) {
                String response = CryptoUtils.decrypt(encryptedResponse);
                if (response == null) continue;

                SwingUtilities.invokeLater(() -> processServerMessage(response));
            }
        } catch (IOException e) {
            if (isConnected) { // Avoid logging error on intentional disconnect
                log("Connection lost: " + e.getMessage());
                SwingUtilities.invokeLater(() -> setUIEnabledState(false));
            }
        } finally {
            SwingUtilities.invokeLater(this::disconnectFromServer);
        }
    }

    private void processServerMessage(String message) {
        log("Received: " + message);
        String[] parts = message.split("\\|", 3);
        String command = parts[0];

        if (isAirplaneMode && command.equals("UPDATE_LIST")) {
            log("Airplane mode ON. Ignoring message: " + message);
            return;
        }

        int sequenceNumber;
        switch (command) {
            case "INITIAL_LIST":
                sequenceNumber = Integer.parseInt(parts[1]);
                lastProcessedSequenceNumber = sequenceNumber;
                listModel.clear();
                if (parts.length > 2 && !parts[2].isEmpty()) {
                    String[] items = parts[2].split(",");
                    for (String item : items) {
                        listModel.addElement(item);
                    }
                }
                log("List synchronized. Current sequence: " + sequenceNumber);
                break;
            case "UPDATE_LIST":
                sequenceNumber = Integer.parseInt(parts[1]);
                if (sequenceNumber > lastProcessedSequenceNumber) {
                    lastProcessedSequenceNumber = sequenceNumber;
                    listModel.addElement(parts[2]);
                    log("List updated with item: " + parts[2]);
                } else {
                    log("Ignoring old or duplicate message with sequence: " + sequenceNumber);
                }
                break;
        }
    }

    private void addItem() {
        String item = itemTextField.getText().trim();
        if (item.isEmpty()) return;

        int delayMs = 1000; // Default min delay

        try {
            int maxDelay = Integer.parseInt(delayField.getText().trim());
            if (maxDelay > 1) {
                delayMs = 1000 + random.nextInt(maxDelay * 1000 - 1000);
            }
        } catch (NumberFormatException e) {
            log("Invalid delay. Using 1 second.");
        }

        final int finalDelay = delayMs;
        new Thread(() -> {
            try {
                log("Waiting for " + finalDelay + "ms to add item '" + item + "'");
                Thread.sleep(finalDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (strictConsistencyRadioButton.isSelected()) {
                String message = "ADD_ITEM|STRICT|" + delayField.getText() + "|" + item;
                sendMessage(message);
            } else { // Continuous Consistency
                synchronized (pendingItems) {
                    pendingItems.add(item);
                }
                schedulePendingItemsFlush();
            }
            SwingUtilities.invokeLater(() -> itemTextField.setText(""));
        }).start();
    }

    private void schedulePendingItemsFlush() {
        if (continuousConsistencyTimer != null) {
            continuousConsistencyTimer.cancel();
        }
        continuousConsistencyTimer = new Timer();
        long delay;
        try {
            delay = Long.parseLong(delayField.getText().trim()) * 1000;
        } catch (NumberFormatException e) {
            delay = 5000; // Default to 5 seconds
        }

        continuousConsistencyTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                flushPendingItems();
            }
        }, delay);
    }

    private void flushPendingItems() {
        synchronized (pendingItems) {
            if (!pendingItems.isEmpty()) {
                log("Flushing " + pendingItems.size() + " pending items.");
                for (String pendingItem : pendingItems) {
                    String message = "ADD_ITEM|CONTINUOUS|" + delayField.getText() + "|" + pendingItem;
                    sendMessage(message);
                }
                pendingItems.clear();
            }
        }
    }


    private void toggleAirplaneMode() {
        isAirplaneMode = airplaneModeToggleButton.isSelected();
        if (isAirplaneMode) {
            log("Airplane Mode ON. Incoming updates will be ignored.");
            airplaneModeToggleButton.setText("Airplane Mode (ON)");
        } else {
            log("Airplane Mode OFF. Requesting sync from server...");
            airplaneModeToggleButton.setText("Airplane Mode (OFF)");
            sendMessage("SYNC_REQUEST|" + lastProcessedSequenceNumber);
        }
    }

    private void sendMessage(String message) {
        if (isConnected && out != null) {
            String encryptedMessage = CryptoUtils.encrypt(message);
            out.println(encryptedMessage);
            log("Sent: " + message);
        } else {
            log("Cannot send message. Not connected.");
        }
    }

    private void setUIEnabledState(boolean connected) {
        // Connection panel
        connectButton.setEnabled(!connected);
        nicknameField.setEnabled(!connected);

        // Main controls
        disconnectButton.setEnabled(connected);
        delayField.setEnabled(connected);
        strictConsistencyRadioButton.setEnabled(connected);
        continuousConsistencyRadioButton.setEnabled(connected);
        itemTextField.setEnabled(connected);
        addItemButton.setEnabled(connected);
        airplaneModeToggleButton.setEnabled(connected);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("Distributed List Client");
        frame.setContentPane(new DistributedListClient().rootPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);
    }
}