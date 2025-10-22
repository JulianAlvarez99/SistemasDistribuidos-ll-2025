import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Implements an HTTP client using the high-level URL class.
 * Descripción Técnica (URL)
 * Abstracción: Se instancia java.net.URL con la cadena de la URL.
 * Conexión (Implícita): Al llamar a url.openStream(), la JVM (usando HttpURLConnection internamente) maneja el protocolo HTTP:
 * Resuelve el DNS.
 * Abre la conexión.
 * Envía la solicitud GET con las cabeceras requeridas (ej. Host).
 * Maneja automáticamente las redirecciones (ej., 301/302). Si http://... redirige a https://..., URL gestiona la negociación SSL/TLS y obtiene el documento final.
 * Lectura: openStream() devuelve un InputStream que apunta directamente al cuerpo del documento final (las cabeceras ya fueron procesadas). BufferedReader lo lee hasta el final.
 */

public class HttpClientUrl {

    public static void main(String[] args) {
        String urlString = "https://www.dolarhoy.com/";

        // Assumption: The URL is valid and reachable.
        try {
            URL url = new URL(urlString);

            // Try-with-resources ensures the stream is closed.
            // url.openStream() handles the connection, request, and headers.
            try (
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
            ) {

                System.out.println("--- [Document from " + urlString + "] ---");
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println(line);
                }
            }
        } catch (Exception e) {
            System.err.println("URL Client Error: " + e.getMessage());
            // e.printStackTrace();
        }
    }
}