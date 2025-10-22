import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;

/** No trae el documento http porque el servidor me desvia

/**
 * Implements an HTTPS client (HTTP over SSL/TLS) using Sockets.
 * Descripción Técnica del Flujo
 * Factory: En lugar de new Socket(), obtenemos una instancia de SSLSocketFactory.getDefault().
 * Esta factory está preconfigurada para confiar en los certificados raíz (CAs) estándar incluidos en el almacén
 * de confianza (truststore) de la JVM (generalmente cacerts).
 *
 * Conexión y Handshake: Al llamar a factory.createSocket(host, port), se crea un SSLSocket. Cuando se
 * intenta escribir (out.flush()) o leer por primera vez, el SSLSocket inicia automáticamente el handshake SSL/TLS con
 * el servidor (www.dolarhoy.com:443). Durante este handshake, el cliente y el servidor negocian el protocolo criptográfico,
 * intercambian certificados (el cliente verifica el certificado del servidor contra su truststore) y establecen una clave
 * de sesión simétrica.
 *
 * Comunicación Transparente: Una vez establecido el túnel seguro, el PrintWriter (OutputStream) cifra automáticamente
 * los datos (la solicitud HTTP) antes de enviarlos por TCP, y el InputStreamReader (InputStream) descifra automáticamente
 * los datos recibidos (la respuesta HTTP) antes de entregarlos al código de la aplicación.
 *
 * Protocolo HTTP: A nivel de aplicación, el código envía exactamente la misma solicitud GET / HTTP/1.1... que en el
 * ejemplo de HTTP plano. La capa SSL/TLS maneja  el cifrado y la integridad de los datos de forma transparente.
 */
public class HttpsClientSocket {

    public static void main(String[] args) {
        // Target host supporting HTTPS
        String host = "www.dolarhoy.com";
        // Default HTTPS port
        int port = 443;

        // Get the default SSL socket factory
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        // Try-with-resources. Note: We use 'Socket' as the type,
        // but 'factory.createSocket' returns an SSLSocket.
        try (
                Socket socket = factory.createSocket(host, port); // Create SSL socket
                PrintWriter out = new PrintWriter(socket.getOutputStream(), false);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {

            // The SSL handshake is handled automatically by factory.createSocket()
            // and the first I/O operations.

            // 1. Send HTTP GET Request (The request itself is identical to HTTP)
            out.print("GET / HTTP/1.1\r\n");
            out.print("Host: " + host + "\r\n");
            out.print("User-Agent: JavaSecureClient\r\n");
            out.print("Connection: close\r\n");
            out.print("\r\n"); // End of headers
            out.flush();

            // 2. Read Response Headers (Data is transparently decrypted)
            System.out.println("--- [HTTPS Headers (via SSLSocket)] ---");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                System.out.println(line);
            }

            // 3. Read Response Body
            System.out.println("\n--- [HTTPS Document Body] ---");
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }

        } catch (Exception e) {
            System.err.println("SSLSocket Client Error: " + e.getMessage());
            // e.printStackTrace();
        }
    }
}