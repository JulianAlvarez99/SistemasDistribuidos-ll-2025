import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 *  HYBRID REPLICATION MANAGER
 * Combina replicación por socket + replicación directa de archivos
 * Inspirado en tu función replicateFile()
 */
public class HybridReplicationManager {
    private static final Logger LOGGER = Logger.getLogger(HybridReplicationManager.class.getName());

    private final String primaryStorageDir;
    private final List<String> backupStorageDirs;
    private final List<BackupConnection> backupConnections;

    public HybridReplicationManager(String primaryStorageDir) {
        this.primaryStorageDir = primaryStorageDir;
        this.backupStorageDirs = new ArrayList<>();
        this.backupConnections = new ArrayList<>();
    }

    public void addBackupDirectory(String backupDir) {
        backupStorageDirs.add(backupDir);
        LOGGER.info("Added backup directory: " + backupDir);
    }

    public void addBackupConnection(BackupConnection connection) {
        backupConnections.add(connection);
        LOGGER.info("Added backup connection");
    }

    /**
     *  FUNCIÓN PRINCIPAL: Replicación híbrida
     * 1. Intenta replicación por socket (rápida)
     * 2. Si falla, usa replicación directa de archivos (confiable)
     */
    public void replicateFile(String fileName, ProtocolCommand operation, String content) {
        LOGGER.info("🔄 Starting hybrid replication for: " + fileName + " (operation: " + operation + ")");

        // FUNCIÓN 1: Replicación por socket (rápida pero puede fallar)
        boolean socketReplicationSuccess = attemptSocketReplication(fileName, operation, content);

        if (socketReplicationSuccess) {
            LOGGER.info("✅ Socket replication successful for: " + fileName);
        } else {
            LOGGER.warning("❌ Socket replication failed, falling back to direct file replication");

            // FUNCIÓN 2: Replicación directa de archivos
            attemptDirectFileReplication(fileName, operation);
        }

        // FUNCIÓN 3: Verificación posterior (opcional)
        scheduleVerification(fileName, operation);
    }

    /**
     *  Replicación por socket
     */
    private boolean attemptSocketReplication(String fileName, ProtocolCommand operation, String content) {
        if (backupConnections.isEmpty()) {
            return false;
        }

        try {
            String replicationContent;
            switch (operation) {
                case WRITE:
                    replicationContent = operation.getCommand() + "|" + content;
                    break;
                case DELETE:
                    replicationContent = operation.getCommand() + "|";
                    break;
                default:
                    return false;
            }

            ProtocolMessage replicationMessage = new ProtocolMessage(
                    ProtocolCommand.REPLICATE, fileName, replicationContent);

            // Enviar a todos los backups
            boolean allSuccess = true;
            for (BackupConnection backup : backupConnections) {
                if (backup.isConnected()) {
                    try {
                        backup.sendMessage(replicationMessage);
                        LOGGER.fine("Socket replication sent to backup: " + fileName);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Socket replication failed to backup", e);
                        allSuccess = false;
                    }
                } else {
                    allSuccess = false;
                }
            }

            return allSuccess;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Socket replication error", e);
            return false;
        }
    }

    /**
     *  REPLICACIÓN DIRECTA DE ARCHIVOS
     */
    private void attemptDirectFileReplication(String fileName, ProtocolCommand operation) {
        File sourceFile = new File(primaryStorageDir, fileName);

        if (operation == ProtocolCommand.WRITE) {
            // Para escrituras: copiar el archivo
            replicateFileWrite(sourceFile);
        } else if (operation == ProtocolCommand.DELETE) {
            // Para eliminaciones: borrar de todos los backups
            replicateFileDelete(fileName);
        }
    }

    /**
     * Replicación de escritura por copia directa
     */
    private void replicateFileWrite(File sourceFile) {
        if (!sourceFile.exists()) {
            LOGGER.warning("Source file does not exist for replication: " + sourceFile.getName());
            return;
        }

        for (String backupDir : backupStorageDirs) {
            Path backupPath = Paths.get(backupDir);
            Path target = backupPath.resolve(sourceFile.getName());

            try {
                // Crear directorio si no existe
                Files.createDirectories(backupPath);

                // Copia con reemplazo y atributos
                Files.copy(sourceFile.toPath(), target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);

                LOGGER.info("[SYNC] Replicado " + sourceFile.getName() + " en " + backupDir);

                // Verificar que la copia fue exitosa
                if (Files.exists(target)) {
                    long sourceSize = Files.size(sourceFile.toPath());
                    long targetSize = Files.size(target);

                    if (sourceSize == targetSize) {
                        LOGGER.info("✅ File replication verified: " + sourceFile.getName() +
                                " (" + sourceSize + " bytes)");
                    } else {
                        LOGGER.warning("⚠️ File size mismatch: " + sourceFile.getName() +
                                " (source: " + sourceSize + ", target: " + targetSize + ")");
                    }
                }

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "[SYNC][ERROR] No se pudo copiar " + sourceFile.getName() +
                        " a " + backupDir + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     *  Replicación de eliminación por borrado directo
     */
    private void replicateFileDelete(String fileName) {
        for (String backupDir : backupStorageDirs) {
            Path target = Paths.get(backupDir, fileName);

            try {
                if (Files.exists(target)) {
                    Files.delete(target);
                    LOGGER.info("[SYNC] Eliminado " + fileName + " de " + backupDir);
                } else {
                    LOGGER.fine("File already deleted or doesn't exist: " + fileName + " in " + backupDir);
                }

            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[SYNC][ERROR] No se pudo eliminar " + fileName +
                        " de " + backupDir + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     *  Verificación posterior (ejecutar después de un delay)
     */
    private void scheduleVerification(String fileName, ProtocolCommand operation) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000); // Esperar 5 segundos
                verifyReplication(fileName, operation);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     *  Verificación de que la replicación fue exitosa
     */
    private void verifyReplication(String fileName, ProtocolCommand operation) {
        File primaryFile = new File(primaryStorageDir, fileName);

        for (String backupDir : backupStorageDirs) {
            File backupFile = new File(backupDir, fileName);

            if (operation == ProtocolCommand.WRITE) {
                // Verificar que ambos archivos existen y tienen el mismo contenido
                if (primaryFile.exists() && backupFile.exists()) {
                    try {
                        if (Files.size(primaryFile.toPath()) == Files.size(backupFile.toPath())) {
                            LOGGER.info("✅ Replication verified: " + fileName + " in " + backupDir);
                        } else {
                            LOGGER.warning("❌ Size mismatch in replication: " + fileName + " in " + backupDir);
                            // Re-replicate if there's a mismatch
                            replicateFileWrite(primaryFile);
                        }
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Error verifying replication", e);
                    }
                } else if (primaryFile.exists() && !backupFile.exists()) {
                    LOGGER.warning("❌ Missing backup file: " + fileName + " in " + backupDir);
                    // Re-replicate missing file
                    replicateFileWrite(primaryFile);
                }
            } else if (operation == ProtocolCommand.DELETE) {
                // Verificar que el archivo fue eliminado
                if (backupFile.exists()) {
                    LOGGER.warning("❌ File not deleted in backup: " + fileName + " in " + backupDir);
                    // Re-attempt deletion
                    replicateFileDelete(fileName);
                } else {
                    LOGGER.info("✅ Deletion verified: " + fileName + " in " + backupDir);
                }
            }
        }
    }

    /**
     *  Sincronización completa (útil para recuperación)
     */
    public void performFullSync() {
        LOGGER.info("🔄 Starting full synchronization using direct file replication");

        File primaryDir = new File(primaryStorageDir);
        if (!primaryDir.exists() || !primaryDir.isDirectory()) {
            LOGGER.warning("Primary directory does not exist: " + primaryStorageDir);
            return;
        }

        File[] files = primaryDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile()) {
                replicateFileWrite(file);
            }
        }

        LOGGER.info("✅ Full synchronization completed");
    }
}