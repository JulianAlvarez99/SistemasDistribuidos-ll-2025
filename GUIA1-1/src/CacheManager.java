import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

//* Cada réplica tiene su carpeta de caché única (hash de la ruta).
//* por ejemplo dir: ReplicaNro_cache
//* TTL configurable: si el archivo en caché vence, se refresca desde réplica.
//
//* Revalidación automática: synchronizer.accessFile garantiza que si estaba invalidado, se trae del master.
//
//* Thread-safe con SwingWorker: evita bloquear la UI al abrir archivos grandes.
//
//* El nombre real del archivo se restaura si estaba _invalid.


public class CacheManager {
    private final Path cacheBaseDir;
    private final long cacheTtlMs;
    private final FolderSynchronizer synchronizer;
    private final JPanel mainPanel; // para diálogos Swing

    public CacheManager(String baseDir, long cacheTtlSeconds, FolderSynchronizer synchronizer, JPanel mainPanel) {
        this.cacheBaseDir = Paths.get(baseDir);
        this.cacheTtlMs = TimeUnit.SECONDS.toMillis(cacheTtlSeconds);
        this.synchronizer = synchronizer;
        this.mainPanel = mainPanel;

        try {
            Files.createDirectories(cacheBaseDir);
        } catch (IOException e) {
            System.err.println("[CACHE] No se pudo crear directorio base de cache: " + e.getMessage());
        }
    }

    // ---------- CACHE HELPERS ----------

    // Carpeta de cache específica para una réplica (evita colisiones)
    private Path getCacheDirForReplica(Path replicaPath) {
        String id = Integer.toHexString(replicaPath.toAbsolutePath().toString().hashCode());
        return cacheBaseDir.resolve(id);
    }

    private Path getCacheFile(Path replicaPath, String originalName) {
        Path replicaCacheDir = getCacheDirForReplica(replicaPath);
        return replicaCacheDir.resolve(originalName);
    }

    // si el nombre puede venir con sufijo _invalid, obtenemos el original
    private String stripInvalidSuffixLocal(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        String base = (dotIndex == -1) ? filename : filename.substring(0, dotIndex);
        String ext = (dotIndex == -1) ? "" : filename.substring(dotIndex);
        String suffix = "_invalid";
        if (base.endsWith(suffix)) {
            String originalBase = base.substring(0, base.length() - suffix.length());
            return originalBase + ext;
        } else {
            return filename;
        }
    }

    // Asegura que la réplica tiene la última versión (llamando a synchronizer.accessFile si está invalidada)
    private void ensureReplicaHasLatest(Path replicaPath, String originalName) {
        synchronizer.accessFile(replicaPath, originalName);
    }

    // copia desde replica -> cache (reemplaza)
    private void copyReplicaToCache(Path replicaPath, String originalName) throws IOException {
        Path replicaFile = replicaPath.resolve(originalName);
        Path cacheFile = getCacheFile(replicaPath, originalName);

        Files.createDirectories(cacheFile.getParent());
        Files.copy(replicaFile, cacheFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    // decide si la caché es "potencialmente stale" por TTL
    private boolean isCacheExpired(Path cacheFile) {
        try {
            if (!Files.exists(cacheFile)) return true;
            long lastMod = Files.getLastModifiedTime(cacheFile).toMillis();
            return (System.currentTimeMillis() - lastMod) > cacheTtlMs;
        } catch (IOException e) {
            return true;
        }
    }

    // compara timestamps entre réplica y cache; si réplica más nueva devuelve true
    private boolean isReplicaNewerThanCache(Path replicaPath, Path cacheFile, String originalName) {
        try {
            Path replicaFile = replicaPath.resolve(originalName);
            if (!Files.exists(replicaFile)) return false;
            if (!Files.exists(cacheFile)) return true;
            long replicaLm = Files.getLastModifiedTime(replicaFile).toMillis();
            long cacheLm = Files.getLastModifiedTime(cacheFile).toMillis();
            return replicaLm > cacheLm;
        } catch (IOException e) {
            return true;
        }
    }

    // ---------- OPEN FILE w/ CACHE (background) ----------

    public void openFileWithCache(Path replicaPath, String requestedFilename) {
        new SwingWorker<String, Void>() {
            Exception error = null;
            String displayName = requestedFilename;

            @Override
            protected String doInBackground() {
                String originalName = stripInvalidSuffixLocal(requestedFilename);
                Path cacheFile = getCacheFile(replicaPath, originalName);
                Path replicaFile = replicaPath.resolve(originalName);
                try {
                    if (Files.exists(cacheFile)) {
                        // Revalidar réplica
                        ensureReplicaHasLatest(replicaPath, originalName);

                        if (!Files.exists(replicaFile)) {
                            return Files.readString(cacheFile, StandardCharsets.UTF_8);
                        }

                        if (isCacheExpired(cacheFile) ||
                                isReplicaNewerThanCache(replicaPath, cacheFile, originalName)) {
                            copyReplicaToCache(replicaPath, originalName);
                        }

                        displayName = originalName;
                        return Files.readString(cacheFile, StandardCharsets.UTF_8);
                    } else {
                        ensureReplicaHasLatest(replicaPath, originalName);
                        if (!Files.exists(replicaFile)) {
                            throw new IOException("El archivo no existe en la réplica ni en master: " + originalName);
                        }
                        copyReplicaToCache(replicaPath, originalName);
                        displayName = originalName;
                        return Files.readString(getCacheFile(replicaPath, originalName), StandardCharsets.UTF_8);
                    }
                } catch (IOException ex) {
                    this.error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                if (error != null) {
                    JOptionPane.showMessageDialog(mainPanel,
                            "Error al abrir/actualizar archivo: " + error.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                try {
                    String content = get();
                    if (content != null) showTextFileDialog(displayName, content);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(mainPanel,
                            "Error al obtener contenido: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showTextFileDialog(String fileName, String content) {
        JDialog dialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(mainPanel),
                "Contenido de " + fileName, true);
        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        dialog.add(new JScrollPane(textArea));
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }
}
