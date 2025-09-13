import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * FILESYSTEM MANAGER - FINAL FIXED VERSION
 *  SOLUCIÓN: Flush inmediato y verificación de escritura
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

    /**
     *  MÉTODO CORREGIDO: Escritura con flush inmediato y verificación
     */
    public synchronized OperationResult writeFile(String fileName, String content, WriteMode writeMode) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            LOGGER.info(" Writing file: " + fileName + " (mode: " + writeMode + ", content length: " +
                    (content != null ? content.length() : 0) + ")");

            // Crear el directorio padre si no existe
            Files.createDirectories(filePath.getParent());

            switch (writeMode) {
                case APPEND:
                    // Método con flush inmediato para APPEND
                    writeWithImmediateFlush(filePath, content, true);
                    break;

                case OVERWRITE:
                    // Método con flush inmediato para OVERWRITE
                    writeWithImmediateFlush(filePath, content, false);
                    break;

                case CREATE_NEW:
                    if (Files.exists(filePath)) {
                        return new OperationResult(false, "File already exists: " + fileName);
                    }
                    writeWithImmediateFlush(filePath, content, false);
                    break;
            }

            // 🔧 VERIFICACIÓN INMEDIATA: Leer el archivo para confirmar escritura
            String verificationContent = readFileImmediately(filePath);
            boolean writeSuccessful = true;

            if (writeMode == WriteMode.OVERWRITE) {
                writeSuccessful = content.equals(verificationContent);
            } else if (writeMode == WriteMode.APPEND) {
                writeSuccessful = verificationContent.endsWith(content);
            }

            if (writeSuccessful) {
                LOGGER.info(" Successfully wrote and verified file: " + fileName +
                        " (final size: " + verificationContent.length() + " chars)");
                return new OperationResult(true, "File written successfully");
            } else {
                LOGGER.severe(" Write verification FAILED for file: " + fileName);
                return new OperationResult(false, "Write verification failed");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to write file: " + fileName, e);
            return new OperationResult(false, "Write operation failed: " + e.getMessage());
        }
    }

    /**
     *  MÉTODO NUEVO: Escritura con flush inmediato y sincronización
     */
    private void writeWithImmediateFlush(Path filePath, String content, boolean append) throws IOException {
        // Usar FileOutputStream con flush y sync inmediatos
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile(), append);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            writer.write(content);
            writer.flush(); // Flush del buffer
            fos.getFD().sync(); // Sincronización a disco (fsync)

        } // AutoCloseable garantiza el cierre

        LOGGER.fine(" File written with immediate flush: " + filePath.getFileName());
    }

    /**
     *  MÉTODO NUEVO: Lectura inmediata para verificación
     */
    private String readFileImmediately(Path filePath) throws IOException {
        // Leer inmediatamente después de escribir
        return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    }

    // Backward compatibility methods
    public synchronized OperationResult writeFile(String fileName, String content, boolean append) {
        return writeFile(fileName, content, append ? WriteMode.APPEND : WriteMode.OVERWRITE);
    }

    public synchronized OperationResult writeFile(String fileName, String content) {
        return writeFile(fileName, content, WriteMode.APPEND); // Default to append for client operations
    }

    /**
     * Para replicación (siempre overwrite completo)
     */
    public synchronized OperationResult replaceFileContent(String fileName, String content) {
        OperationResult result = writeFile(fileName, content, WriteMode.OVERWRITE);
        if (result.isSuccess()) {
            LOGGER.info(" File content replaced for replication: " + fileName + " (" + content.length() + " chars)");
        }
        return result;
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

            LOGGER.info(" Successfully read file: " + fileName + " (" + allBytes.length + " bytes)");
            return new OperationResult(true, "File read successfully", content);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, " Failed to read file: " + fileName, e);
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
            LOGGER.info("🗑 Successfully deleted file: " + fileName);
            return new OperationResult(true, "File deleted successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, " Failed to delete file: " + fileName, e);
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
                    .forEach(fileName -> {
                        try {
                            FileMetadata metadata = getFileMetadata(fileName);
                            if (metadata != null) {
                                fileList.append(String.format("%s (%.2f KB)\n",
                                        fileName, metadata.getSize() / 1024.0));
                            } else {
                                fileList.append(fileName).append("\n");
                            }
                        } catch (Exception e) {
                            fileList.append(fileName).append("\n");
                        }
                    });

            String result = fileList.toString();
            long fileCount = result.isEmpty() ? 0 : result.split("\n").length;
            LOGGER.info(" Listed " + fileCount + " files");
            return new OperationResult(true, "Files listed successfully", result.trim());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, " Failed to list files", e);
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
            LOGGER.log(Level.WARNING, " Failed to get file metadata: " + fileName, e);
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
            LOGGER.log(Level.WARNING, " Failed to get all files metadata", e);
        }

        return filesMap;
    }

    /**
     * Write mode enumeration for clarity
     */
    public enum WriteMode {
        APPEND,      // Add content to end of file
        OVERWRITE,   // Replace entire file content
        CREATE_NEW   // Create new file only if it doesn't exist
    }
}