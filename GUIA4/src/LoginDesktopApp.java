import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Swing desktop application that uses an HTTP service for login.
 */
public class LoginDesktopApp extends JFrame {

    private final JTextField usernameField;
    private final JPasswordField passwordField;
    private final JButton loginButton;
    private final JLabel statusLabel;

    // HTTP Client (reusable, thread-safe)
    private final HttpClient httpClient;
    private static final String LOGIN_URL = "http://localhost:8080/login";

    // Pattern to extract the name from the server's success response:
    // <h1>Bienvenido, [FullName]</h1>
    private static final Pattern NAME_PATTERN = Pattern.compile("<h1>Bienvenido, (.*?)</h1>");

    public LoginDesktopApp() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // --- Frame Setup ---
        setTitle("Login Cliente HTTP");
        setSize(400, 220);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Center screen

        // --- Components ---
        usernameField = new JTextField(20);
        passwordField = new JPasswordField(20);
        loginButton = new JButton("Login");
        statusLabel = new JLabel("Ingrese sus credenciales.", SwingConstants.CENTER);

        // --- Layout (GridBagLayout for form alignment) ---
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8); // Padding
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Username
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Usuario:"), gbc);
        gbc.gridx = 1;
        panel.add(usernameField, gbc);

        // Row 1: Password
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Contrasena:"), gbc);
        gbc.gridx = 1;
        panel.add(passwordField, gbc);

        // Row 2: Button
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        panel.add(loginButton, gbc);

        // --- Add panels to frame ---
        getContentPane().setLayout(new BorderLayout(10, 10));
        getContentPane().add(panel, BorderLayout.CENTER);
        getContentPane().add(statusLabel, BorderLayout.SOUTH);

        // --- Attach Listener ---
        loginButton.addActionListener(this::handleLoginAttempt);
        // Also allow 'Enter' key in the password field to trigger login
        passwordField.addActionListener(this::handleLoginAttempt);
    }

    /**
     * Handles the login button click event.
     */
    private void handleLoginAttempt(ActionEvent e) {
        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            updateStatus("Usuario y contrasena son requeridos.", Color.RED);
            return;
        }

        // Disable UI while the network request is in progress
        setUiEnabled(false);

        // Use SwingWorker to perform network I/O off the Event Dispatch Thread (EDT)
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // This runs on a background thread
                return performHttpLogin(username, password);
            }

            @Override
            protected void done() {
                // This runs back on the EDT
                try {
                    String fullName = get(); // Get result from doInBackground

                    // Success
                    updateStatus("Login exitoso.", new Color(0, 128, 0));
                    JOptionPane.showMessageDialog(
                            LoginDesktopApp.this,
                            "Bienvenido, " + fullName,
                            "Login Exitoso",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    // Reset fields after successful login
                    usernameField.setText("");
                    passwordField.setText("");

                } catch (ExecutionException ex) {
                    // Handle exceptions thrown by doInBackground
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof LoginFailedException) {
                        updateStatus(cause.getMessage(), Color.RED);
                    } else if (cause instanceof IOException) {
                        updateStatus("Error de red: No se pudo conectar al servidor.", Color.RED);
                    } else {
                        updateStatus("Error inesperado: " + cause.getMessage(), Color.RED);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } finally {
                    // Re-enable UI regardless of outcome
                    setUiEnabled(true);
                }
            }
        };

        worker.execute();
    }

    /**
     * Performs the blocking HTTP POST request.
     * @return The full name of the user if successful.
     * @throws IOException, InterruptedException, LoginFailedException
     */
    private String performHttpLogin(String username, String password)
            throws IOException, InterruptedException, LoginFailedException {

        // 1. Encode form data (application/x-www-form-urlencoded)
        String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String encodedPass = URLEncoder.encode(password, StandardCharsets.UTF_8);
        String formData = "username=" + encodedUser + "&password=" + encodedPass;

        // 2. Build request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOGIN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "JavaLoginClient")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        // 3. Send request and get response
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 4. Process response
        if (response.statusCode() == 200) {
            // Parse the full name from the HTML body
            Matcher matcher = NAME_PATTERN.matcher(response.body());
            if (matcher.find()) {
                return matcher.group(1); // Return the captured name
            } else {
                throw new LoginFailedException("Respuesta del servidor invalida.");
            }
        } else if (response.statusCode() == 401) {
            throw new LoginFailedException("Usuario o contrasena incorrectos.");
        } else {
            throw new LoginFailedException("Error del servidor (HTTP " + response.statusCode() + ")");
        }
    }

    // --- UI Utility Methods (must be called on EDT) ---

    private void setUiEnabled(boolean enabled) {
        loginButton.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
        statusLabel.setText(enabled ? "Ingrese sus credenciales." : "Validando...");
    }

    private void updateStatus(String message, Color color) {
        statusLabel.setText(message);
        statusLabel.setForeground(color);
    }

    // Custom exception for failed login attempts
    private static class LoginFailedException extends Exception {
        public LoginFailedException(String message) {
            super(message);
        }
    }

    // --- Main Method ---
    public static void main(String[] args) {
        // Ensure GUI creation runs on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            LoginDesktopApp app = new LoginDesktopApp();
            app.setVisible(true);
        });
    }
}