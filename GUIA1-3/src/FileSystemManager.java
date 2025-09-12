import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
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

    public synchronized OperationResult writeFile(String fileName, String content, boolean append) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (append) {
                // Append to existing file
                Files.write(filePath, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                // Overwrite entire file (for replication consistency)
                Files.write(filePath, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            LOGGER.info("Successfully wrote to file: " + fileName + " (append: " + append + ")");
            return new OperationResult(true, "File written successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to write file: " + fileName, e);
            return new OperationResult(false, "Write operation failed: " + e.getMessage());
        }
    }

    public synchronized OperationResult writeFile(String fileName, String content) {
        return writeFile(fileName, content, true); // Default to append
    }


    public synchronized OperationResult readFile(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return new OperationResult(false, "File not found: " + fileName);
            }

            // Read all bytes and preserve line breaks
            byte[] allBytes = Files.readAllBytes(filePath);
            String content = new String(allBytes, StandardCharsets.UTF_8);

            LOGGER.info("Successfully read file: " + fileName + " (" + allBytes.length + " bytes)");
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

    public synchronized OperationResult listFiles() {
        try {
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                return new OperationResult(true, "Directory is empty", "");
            }

            StringBuilder fileList = new StringBuilder();
            Files.list(storagePath)
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .forEach(fileName -> fileList.append(fileName).append("\n"));

            String result = fileList.toString();
            LOGGER.info("Listed " + (result.split("\n").length - 1) + " files");
            return new OperationResult(true, "Files listed successfully", result.trim());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to list files", e);
            return new OperationResult(false, "List operation failed: " + e.getMessage());
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

    public synchronized FileMetadata getFileMetadata(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return null;
            }

            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);

            return new FileMetadata(fileName, content, attrs.lastModifiedTime().toMillis(), attrs.size());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get file metadata: " + fileName, e);
            return null;
        }
    }

    public synchronized Map<String, FileMetadata> getAllFilesMetadata() {
        Map<String, FileMetadata> filesMap = new HashMap<>();

        try {
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                return filesMap;
            }

            Files.list(storagePath)
                    .filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        String fileName = filePath.getFileName().toString();
                        FileMetadata metadata = getFileMetadata(fileName);
                        if (metadata != null) {
                            filesMap.put(fileName, metadata);
                        }
                    });

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get all files metadata", e);
        }

        return filesMap;
    }


}