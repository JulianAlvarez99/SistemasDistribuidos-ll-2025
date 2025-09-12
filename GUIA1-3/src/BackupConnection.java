import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BackupConnection {
    private static final Logger LOGGER = Logger.getLogger(BackupConnection.class.getName());
    private final String host;
    private final int port;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean connected;

    public BackupConnection(String host, int port) {
        this.host = host;
        this.port = port;
        this.connected = false;
    }

    public boolean connect() {
        try {
            socket = new Socket(host, port);
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connected = true;

            // Send ready signal
            ProtocolMessage readyMessage = new ProtocolMessage(ProtocolCommand.BACKUP_READY, null, null);
            writer.println(readyMessage.toString());

            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to backup server: " + host + ":" + port, e);
            return false;
        }
    }

    public void sendMessage(ProtocolMessage message) {
        if (connected && writer != null) {
            try {
                writer.println(message.toString());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to send message to backup", e);
                connected = false;
            }
        }
    }

    public ProtocolMessage sendMessageAndWaitResponse(ProtocolMessage message, int timeoutMs) {
        if (!connected || writer == null || reader == null) {
            return null;
        }

        try {
            writer.println(message.toString());

            // Wait for response with timeout
            socket.setSoTimeout(timeoutMs);
            String responseStr = reader.readLine();
            socket.setSoTimeout(0); // Reset timeout

            if (responseStr != null) {
                return ProtocolMessage.fromString(responseStr);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get response from backup", e);
            connected = false;
        }

        return null;
    }

    public void close() {
        connected = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing backup connection", e);
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
