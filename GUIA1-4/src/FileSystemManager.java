import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 📁 ENHANCED FILESYSTEM MANAGER
 * Gestión de archivos con configuración parametrizada y verificación mejorada
 */
public class FileSystemManager {
    private static final Logger LOGGER = Logger.getLogger(FileSystemManager.class.getName());

    private final String storageDirectory;
    private final SystemConfig config;
    private final boolean verifyWrites;

    public FileSystemManager(String storageDirectory) {
        this.config = SystemConfig.getInstance();
        this.storageDirectory = storageDirectory != null ? storageDirectory :
                config.getReplicaStoragePath(8080); // fallback
        this.verifyWrites = config.verifyWrites();
        initializeStorageDirectory();
    }

    public FileSystemManager(int replicaPort) {
        this.config = SystemConfig.getInstance();
        this.storageDirectory = config.getReplicaStoragePath(replicaPort);
        this.verifyWrites = config.verifyWrites();
        initializeStorageDirectory();
    }

    private void initializeStorageDirectory() {
        try {
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                LOGGER.info("📁 Created storage directory: " + storageDirectory);
            } else {
                LOGGER.info("📁 Using existing storage directory: " + storageDirectory);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "❌ Failed to initialize storage directory: " + storageDirectory, e);
            throw new RuntimeException("Cannot initialize storage directory", e);
        }
    }

    /**
     * 📝 WRITE FILE - Implementación robusta con verificación
     */
    public synchronized OperationResult writeFile(String fileName, String content, WriteMode writeMode) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            LOGGER.info("📝 Writing file: " + fileName + " (mode: " + writeMode +
                    ", content length: " + (content != null ? content.length() : 0) + ")");

            // Crear directorio padre si no existe
            Files.createDirectories(filePath.getParent());

            // Ejecutar escritura según el modo
            switch (writeMode) {
                case APPEND:
                    writeWithImmediateFlush(filePath, content, true);
                    break;
                case OVERWRITE:
                    writeWithImmediateFlush(filePath, content, false);
                    break;
                case CREATE_NEW:
                    if (Files.exists(filePath)) {
                        return new OperationResult(false, "File already exists: " + fileName);
                    }
                    writeWithImmediateFlush(filePath, content, false);
                    break;
            }

            // Verificación de escritura si está habilitada
            if (verifyWrites) {
                boolean writeSuccessful = verifyWriteOperation(filePath, content, writeMode);
                if (!writeSuccessful) {
                    LOGGER.severe("❌ Write verification FAILED for file: " + fileName);
                    return new OperationResult(false, "Write verification failed");
                }
            }

            long finalSize = Files.size(filePath);
            LOGGER.info("✅ Successfully wrote file: " + fileName + " (final size: " + finalSize + " bytes)");
            return new OperationResult(true, "File written successfully");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Failed to write file: " + fileName, e);
            return new OperationResult(false, "Write operation failed: " + e.getMessage());
        }
    }

    /**
     * 💾 ESCRITURA CON FLUSH INMEDIATO
     */
    private void writeWithImmediateFlush(Path filePath, String content, boolean append) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile(), append);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            writer.write(content != null ? content : "");
            writer.flush(); // Flush del buffer
            fos.getFD().sync(); // Sincronización a disco (fsync)

        } // AutoCloseable garantiza el cierre

        LOGGER.fine("💾 File written with immediate flush: " + filePath.getFileName());
    }

    /**
     * ✅ VERIFICAR OPERACIÓN DE ESCRITURA
     */
    private boolean verifyWriteOperation(Path filePath, String content, WriteMode writeMode) throws IOException {
        String verificationContent = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);

        switch (writeMode) {
            case OVERWRITE:
            case CREATE_NEW:
                return content.equals(verificationContent);
            case APPEND:
                return verificationContent.endsWith(content);
            default:
                return false;
        }
    }

    // Métodos de compatibilidad
    public synchronized OperationResult writeFile(String fileName, String content, boolean append) {
        return writeFile(fileName, content, append ? WriteMode.APPEND : WriteMode.OVERWRITE);
    }

    public synchronized OperationResult writeFile(String fileName, String content) {
        return writeFile(fileName, content, WriteMode.APPEND);
    }

    /**
     * 🔄 REEMPLAZAR CONTENIDO COMPLETO (Para replicación)
     */
    public synchronized OperationResult replaceFileContent(String fileName, String content) {
        OperationResult result = writeFile(fileName, content, WriteMode.OVERWRITE);
        if (result.isSuccess()) {
            LOGGER.info("🔄 File content replaced for replication: " + fileName +
                    " (" + (content != null ? content.length() : 0) + " chars)");
        }
        return result;
    }

    /**
     * 📖 READ FILE
     */
    public synchronized OperationResult readFile(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return new OperationResult(false, "File not found: " + fileName);
            }

            byte[] allBytes = Files.readAllBytes(filePath);
            String content = new String(allBytes, StandardCharsets.UTF_8);

            LOGGER.info("📖 Successfully read file: " + fileName + " (" + allBytes.length + " bytes)");
            return new OperationResult(true, "File read successfully", content);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to read file: " + fileName, e);
            return new OperationResult(false, "Read operation failed: " + e.getMessage());
        }
    }

    /**
     * 🗑️ DELETE FILE
     */
    public synchronized OperationResult deleteFile(String fileName) {
        try {
            validateFileName(fileName);
            Path filePath = Paths.get(storageDirectory, fileName);

            if (!Files.exists(filePath)) {
                return new OperationResult(false, "File not found: " + fileName);
            }

            Files.delete(filePath);
            LOGGER.info("🗑️ Successfully deleted file: " + fileName);
            return new OperationResult(true, "File deleted successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to delete file: " + fileName, e);
            return new OperationResult(false, "Delete operation failed: " + e.getMessage());
        }
    }

    /**
     * 📋 LIST FILES
     */
    public synchronized OperationResult listFiles() {
        try {
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                return new OperationResult(true, "Directory is empty", "");
            }

            StringBuilder fileList = new StringBuilder();
            long fileCount = Files.list(storagePath)
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .peek(fileName -> {
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
                    })
                    .count();

            String result = fileList.toString();
            LOGGER.info("📋 Listed " + fileCount + " files");
            return new OperationResult(true, "Files listed successfully", result.trim());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Failed to list files", e);
            return new OperationResult(false, "List operation failed: " + e.getMessage());
        }
    }

    /**
     * 🔍 VALIDATE FILE NAME
     */
    private void validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }

        // Security check to prevent directory traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }

        // Additional checks
        if (fileName.length() > 255) {
            throw new IllegalArgumentException("File name too long: " + fileName);
        }
    }

    /**
     * 📊 GET FILE METADATA
     */
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
            LOGGER.log(Level.WARNING, "❌ Failed to get file metadata: " + fileName, e);
            return null;
        }
    }

    /**
     * 📊 GET ALL FILES METADATA
     */
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
            LOGGER.log(Level.WARNING, "❌ Failed to get all files metadata", e);
        }

        return filesMap;
    }

    // Getters
    public String getStorageDirectory() { return storageDirectory; }

    @Override
    public String toString() {
        return "FileSystemManager{directory='" + storageDirectory + "', verifyWrites=" + verifyWrites + "}";
    }

    /**
     * 📝 WRITE MODE ENUMERATION
     */
    public enum WriteMode {
        APPEND,      // Add content to end of file
        OVERWRITE,   // Replace entire file content
        CREATE_NEW   // Create new file only if it doesn't exist
    }
}