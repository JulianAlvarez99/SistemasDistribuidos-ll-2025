import java.io.*;
import java.nio.file.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * File system operations manager
 */
public class FileSystemManager {
    private static final Logger LOGGER = Logger.getLogger(FileSystemManager.class.getName());
    private final String storageDirectory;

    public FileSystemManager(String storageDirectory) {
        this.storageDirectory = storageDirectory;
        initializeStorageDirectory();
    }

    private void initializeStorageDirectory() {
        try {
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                LOGGER.info("Created storage directory: " + storageDirectory);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize storage directory", e);
            throw new RuntimeException("Cannot initialize storage directory", e);
        }
    }

    public synchronized OperationResult writeFile(String fileName, String content) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            // Append content to existing file or create new file
            Files.write(filePath, content.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            LOGGER.info("Successfully wrote to file: " + fileName);
            return new OperationResult(true, "File written successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to write file: " + fileName, e);
            return new OperationResult(false, "Write operation failed: " + e.getMessage());
        }
    }

    public synchronized OperationResult readFile(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return new OperationResult(false, "File not found: " + fileName);
            }

            String content = new String(Files.readAllBytes(filePath));
            LOGGER.info("Successfully read file: " + fileName);
            return new OperationResult(true, "File read successfully", content);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read file: " + fileName, e);
            return new OperationResult(false, "Read operation failed: " + e.getMessage());
        }
    }

    public synchronized OperationResult deleteFile(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return new OperationResult(false, "File not found: " + fileName);
            }

            Files.delete(filePath);
            LOGGER.info("Successfully deleted file: " + fileName);
            return new OperationResult(true, "File deleted successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete file: " + fileName, e);
            return new OperationResult(false, "Delete operation failed: " + e.getMessage());
        }
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }

        // Basic security check to prevent directory traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }
    }
}