import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A client to test the MultiThreadedHttpServer.
 * It sends one POST request (expecting 501) and one GET request (expecting 200).
 */
public class HttpClientTester {

    public static void main(String[] args) {
        String host = "localhost";
        int port = 8080;

        // Assumption: The MultiThreadedHttpServer is running on localhost:8080.

        System.out.println("--- [Test 1] Attempting POST request (expecting 501 Not Implemented) ---");
        sendRequest(host, port, "POST /testpath HTTP/1.1");

        System.out.println("\n--- [Test 2] Attempting GET request (expecting 200 OK) ---");
        sendRequest(host, port, "GET / HTTP/1.1");
    }

    /**
     * Connects to the server, sends a request, and prints the response.
     * @param host The server host.
     * @param port The server port.
     * @param requestLine The first line of the HTTP request (e.g., "METHOD /path HTTP/1.1")
     */
    private static void sendRequest(String host, int port, String requestLine) {
        // Use try-with-resources for automatic socket and stream closure
        try (
                Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), false); // Disable auto-flush for manual control
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {

            // 1. Send Request Headers
            out.print(requestLine + "\r\n"); // Use standard CRLF
            out.print("Host: " + host + ":" + port + "\r\n");
            out.print("User-Agent: JavaTestClient\r\n");
            out.print("Connection: close\r\n"); // Tell server to close socket after response

            if (requestLine.startsWith("POST")) {
                String body = "This is a test POST body";
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

                out.print("Content-Length: " + bodyBytes.length + "\r\n");
                out.print("\r\n"); // Blank line (header/body separator)
                out.print(body);   // Send body
            } else {
                out.print("\r\n"); // Blank line for GET (no body)
            }

            out.flush(); // Manually flush the entire request

            // 2. Read Response
            String serverLine;
            while ((serverLine = in.readLine()) != null) {
                System.out.println(serverLine);
            }

        } catch (Exception e) {
            System.err.println("Client exception: " + e.getMessage());
        }
    }
}