import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class CryptoBenchmark {

    // --- Configuration ---
    private static final int DATA_SIZE_MB = 10;
    private static final int DATA_SIZE_BYTES = DATA_SIZE_MB * 1024 * 1024;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 20;

    /**
     * Main method to run the encryption/decryption benchmarks.
     */
    public static void main(String[] args) {
        System.out.println("Crypto Benchmark Configuration:");
        System.out.println("Data Size: " + DATA_SIZE_MB + " MB");
        System.out.println("Warm-up Iterations: " + WARMUP_ITERATIONS);
        System.out.println("Benchmark Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println("----------------------------------------------------------");

        // Generate a consistent block of random data for all tests
        byte[] dataToProcess = new byte[DATA_SIZE_BYTES];
        new SecureRandom().nextBytes(dataToProcess);

        // Run benchmarks for each algorithm
        runBenchmarkFor("DES", 56, "DES/ECB/PKCS5Padding", dataToProcess);
        runBenchmarkFor("TripleDES", 168, "DESede/ECB/PKCS5Padding", dataToProcess);
        runBenchmarkFor("AES", 128, "AES/ECB/PKCS5Padding", dataToProcess);

        System.out.println("----------------------------------------------------------");
        System.out.println("Benchmark complete.");
    }

    /**
     * Performs a full benchmark (warm-up and timed runs) for a given algorithm.
     *
     * @param algorithmName   The display name for the algorithm (e.g., "AES").
     * @param keySize         The key size in bits (e.g., 128).
     * @param transformation  The full JCE transformation string (e.g., "AES/ECB/PKCS5Padding").
     * @param data            The byte array to encrypt and decrypt.
     */
    private static void runBenchmarkFor(String algorithmName, int keySize, String transformation, byte[] data) {
        try {
            System.out.println("Benchmarking: " + algorithmName + " (" + keySize + " bits)");

            // 1. Generate Key
            KeyGenerator keyGenerator = KeyGenerator.getInstance(algorithmName.equals("TripleDES") ? "DESede" : algorithmName);
            keyGenerator.init(keySize);
            SecretKey secretKey = keyGenerator.generateKey();

            // 2. Create Ciphers
            Cipher encryptCipher = Cipher.getInstance(transformation);
            Cipher decryptCipher = Cipher.getInstance(transformation);

            // 3. Warm-up Phase
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                performOperation(encryptCipher, secretKey, data, Cipher.ENCRYPT_MODE);
            }

            // 4. Benchmark Phase
            long totalEncryptTime = 0;
            long totalDecryptTime = 0;

            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                // Encrypt
                long startEncrypt = System.nanoTime();
                byte[] encryptedData = performOperation(encryptCipher, secretKey, data, Cipher.ENCRYPT_MODE);
                totalEncryptTime += System.nanoTime() - startEncrypt;

                // Decrypt
                long startDecrypt = System.nanoTime();
                performOperation(decryptCipher, secretKey, encryptedData, Cipher.DECRYPT_MODE);
                totalDecryptTime += System.nanoTime() - startDecrypt;
            }

            // 5. Print Results
            double avgEncryptTimeMs = (totalEncryptTime / (double) BENCHMARK_ITERATIONS) / 1_000_000.0;
            double avgDecryptTimeMs = (totalDecryptTime / (double) BENCHMARK_ITERATIONS) / 1_000_000.0;
            double throughputEncrypt = (DATA_SIZE_BYTES / (1024.0 * 1024.0)) / (avgEncryptTimeMs / 1000.0);

            System.out.printf("  ├─ Average Encrypt Time: %.2f ms (%.2f MB/s)\n", avgEncryptTimeMs, throughputEncrypt);
            System.out.printf("  └─ Average Decrypt Time: %.2f ms\n\n", avgDecryptTimeMs);

        } catch (Exception e) {
            System.err.println("An error occurred during benchmark for " + algorithmName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Executes a single cryptographic operation (encrypt or decrypt).
     *
     * @param cipher    The Cipher instance to use.
     * @param key       The secret key.
     * @param inputData The data to process.
     * @param mode      The operation mode (Cipher.ENCRYPT_MODE or Cipher.DECRYPT_MODE).
     * @return The resulting byte array from the operation.
     * @throws Exception if the operation fails.
     */
    private static byte[] performOperation(Cipher cipher, SecretKey key, byte[] inputData, int mode) throws Exception {
        cipher.init(mode, key);
        return cipher.doFinal(inputData);
    }
}