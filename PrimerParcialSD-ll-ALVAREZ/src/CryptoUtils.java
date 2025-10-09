import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

public class CryptoUtils {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    // A shared secret key for all components. In a real system, this should be managed securely.
    private static final String SECRET_KEY = "MySuperSecretKeyForExam";

    private static SecretKeySpec secretKeySpec;
    private static IvParameterSpec ivParameterSpec;

    static {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            key = Arrays.copyOf(key, 16); // Use first 128 bit (16 bytes)
            secretKeySpec = new SecretKeySpec(key, ALGORITHM);
            ivParameterSpec = new IvParameterSpec(key);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            System.err.println("Error encrypting: " + e.getMessage());
            return null;
        }
    }

    public static String decrypt(String cipherText) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] original = cipher.doFinal(Base64.getDecoder().decode(cipherText));
            return new String(original, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Error decrypting: " + e.getMessage());
            return null;
        }
    }
}