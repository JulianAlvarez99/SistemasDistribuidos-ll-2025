// FolderSynchronizer.java
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FolderSynchronizer {
    private final Path masterPath;
    private final CopyOnWriteArrayList<Path> replicas = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> invalidationMap = new ConcurrentHashMap<>();
    private Timer continuousTimer;
    private Timer invalidationTimer;

    public FolderSynchronizer(String masterDir, List<String> replicaDirs) {
        this.masterPath = Paths.get(masterDir);
        for (String r : replicaDirs) {
            try {
                addReplica(r);
            } catch (Exception ex) {
                System.err.println("[INIT] No se pudo añadir réplica " + r + ": " + ex.getMessage());
            }
        }
        initializeState();
        // sincronización inicial
        syncAllFiles();
    }

    private void initializeState() {
        File[] masterFiles = masterPath.toFile().listFiles(File::isFile);
        if (masterFiles != null) {
            for (File mf : masterFiles) {
                invalidationMap.put(mf.getName(), false);
            }
        }
    }

    // ------------------------------
    // Consistencia estricta (WatchService)
    // ------------------------------
    public void startStrictConsistency() {
        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                masterPath.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                while (true) {
                    WatchKey key = watchService.take(); // bloquea hasta que haya evento
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changedFile = masterPath.resolve((Path) event.context());
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
                                event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                            replicateFile(changedFile.toFile());
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            removeFileFromReplicas(changedFile.getFileName().toString());
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    // ------------------------------
    // Operaciones de replicación
    // ------------------------------
    private void replicateFile(File sourceFile) {
        for (Path replica : replicas) {
            Path target = replica.resolve(sourceFile.getName());
            try {
                Files.copy(sourceFile.toPath(), target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                System.out.println("[SYNC] Replicado " + sourceFile.getName() + " en " + replica);
            } catch (IOException e) {
                System.err.println("[SYNC][ERROR] No se pudo copiar " + sourceFile.getName() + " a " + replica + ": " + e.getMessage());
            }
        }
    }

    public void startContinuousConsistency(long intervalMs) {
        if (continuousTimer != null) {
            continuousTimer.cancel();
        }
        continuousTimer = new Timer(true);
        continuousTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                syncAllFiles();
            }
        }, 0, intervalMs);
        System.out.println("[SYNC] Continuous consistency started (interval ms = " + intervalMs + ")");
    }


    public void startInvalidation(long periodMs) {
        if (invalidationTimer != null) {
            invalidationTimer.cancel();
        }
        invalidationTimer = new Timer(true);
        invalidationTimer.scheduleAtFixedRate(new TimerTask() {
            private final Map<String, Long> lastModifiedMap = new HashMap<>();

            @Override
            public void run() {
                File[] masterFiles = masterPath.toFile().listFiles(File::isFile);
                if (masterFiles == null) return;

                Set<String> currentFiles = new HashSet<>();
                for (File mf : masterFiles) {
                    String fname = mf.getName();
                    currentFiles.add(fname);

                    long lastModified = mf.lastModified();
                    Long prev = lastModifiedMap.get(fname);

                    if (prev == null) {
                        // nuevo archivo -> sincronizar y marcar válido
                        invalidationMap.put(fname, false);
                        lastModifiedMap.put(fname, lastModified);
                        syncAllFiles();
                    } else if (lastModified > prev) {
                        // archivo modificado -> invalidar
                        invalidateFile(fname);
                        lastModifiedMap.put(fname, lastModified);
                    }
                }

                // detectar archivos eliminados en master
                for (String fname : new HashSet<>(lastModifiedMap.keySet())) {
                    if (!currentFiles.contains(fname)) {
                        invalidateFile(fname);
                        removeFileFromReplicas(fname);
                        lastModifiedMap.remove(fname);
                    }
                }
            }
        }, 500, periodMs);
    }

    public Path getMasterPath() {
        return masterPath;
    }

    public synchronized boolean addReplica(String replicaDir) {
        if (replicaDir == null || replicaDir.isBlank()) return false;
        Path replicaPath = Paths.get(replicaDir).toAbsolutePath().normalize();

        if (replicaPath.equals(masterPath.toAbsolutePath().normalize())) {
            System.out.println("[ADD_REPLICA] Se pasó el path del master, no se agrega como réplica.");
            return false;
        }

        try {
            Files.createDirectories(replicaPath);
        } catch (IOException e) {
            System.err.println("[ADD_REPLICA] No se pudo crear réplica: " + e.getMessage());
            return false;
        }

        if (replicas.contains(replicaPath)) {
            System.out.println("[ADD_REPLICA] Réplica ya existe en la lista: " + replicaPath);
            return true;
        }

        replicas.add(replicaPath);
        System.out.println("[ADD_REPLICA] Réplica añadida: " + replicaPath);
        syncFilesToReplica(replicaPath); // 🚀 sincroniza apenas se agrega
        return true;
    }

    public synchronized boolean removeReplica(String replicaDir) {
        if (replicaDir == null || replicaDir.isBlank()) return false;
        Path replicaPath = Paths.get(replicaDir).toAbsolutePath().normalize();
        boolean removed = replicas.remove(replicaPath);
        if (removed) {
            System.out.println("[REMOVE_REPLICA] Réplica removida: " + replicaPath);
        } else {
            System.out.println("[REMOVE_REPLICA] Réplica no encontrada: " + replicaPath);
        }
        return removed;
    }

    private void syncFilesToReplica(Path replicaPath) {
        try {
            Files.createDirectories(replicaPath);
        } catch (IOException e) {
            System.err.println("[SYNC_REPLICA] No se pudo asegurar réplica: " + e.getMessage());
            return;
        }

        File[] masterFilesArr = masterPath.toFile().listFiles(File::isFile);
        Set<String> masterNames = new HashSet<>();
        if (masterFilesArr != null) {
            for (File mf : masterFilesArr) {
                String name = mf.getName();
                masterNames.add(name);
                Path target = replicaPath.resolve(name);
                Boolean invalid = invalidationMap.getOrDefault(name, false);
                try {
                    if (!invalid && Files.exists(mf.toPath())) {
                        Files.copy(mf.toPath(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        Files.deleteIfExists(replicaPath.resolve(name + ".invalid"));
                        System.out.println("[SYNC_REPLICA] Copiado " + name + " -> " + replicaPath);
                    } else if (invalid) {
                        Path marker = replicaPath.resolve(name + ".invalid");
                        Files.writeString(marker, "Archivo inválido hasta próxima actualización desde master.",
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.deleteIfExists(target);
                        System.out.println("[SYNC_REPLICA] Marcador .invalid creado para " + name + " en " + replicaPath);
                    }
                } catch (IOException e) {
                    System.err.println("[SYNC_REPLICA][ERROR] No se pudo sincronizar " + name + " a " + replicaPath + ": " + e.getMessage());
                }
            }
        }

        // podar archivos que ya no están en master
        File[] replicaFiles = replicaPath.toFile().listFiles(File::isFile);
        if (replicaFiles != null) {
            for (File rf : replicaFiles) {
                String rn = rf.getName();
                if (rn.endsWith(".invalid")) continue;
                if (!masterNames.contains(rn)) {
                    try {
                        if (Files.deleteIfExists(rf.toPath())) {
                            System.out.println("[SYNC_REPLICA] Podado en nueva réplica: " + rn + " en " + replicaPath);
                        }
                    } catch (IOException e) {
                        System.err.println("[SYNC_REPLICA][ERROR] No se pudo podar " + rn + " en " + replicaPath + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void syncAllFiles() {
        for (Path replica : replicas) {
            syncFilesToReplica(replica);
        }
    }

    // -------------------
    // Invalidación (renombra/copia a *_invalid y borra original en réplica)
    // -------------------
    public void invalidateFile(String filename) {
        invalidationMap.put(filename, true);
        for (Path replica : replicas) {
            Path original = replica.resolve(filename);
            Path invalidCopy = replica.resolve(appendInvalidSuffix(filename));
            try {
                if (Files.exists(original)) {
                    // Renombrar/mover original -> original_invalid.ext (preserva contenido)
                    Files.move(original, invalidCopy, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[INVALIDATE] Renombrado a inválido: " + invalidCopy);
                } else {
                    // Si no existía la copia original en la réplica (p. ej. réplica vacía),
                    // creamos un marcador vacío para que el usuario vea algo con sufijo invalid.
                    if (!Files.exists(invalidCopy)) {
                        Files.writeString(invalidCopy, "Copia inválida (no existía la versión local).",
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        System.out.println("[INVALIDATE] Marcador inválido creado: " + invalidCopy);
                    }
                }
            } catch (IOException e) {
                System.err.println("[INVALIDATE][ERROR] No se pudo invalidar " + filename + " en " + replica + ": " + e.getMessage());
            }
        }
    }

    // -------------------
    // Acceso / Revalidación
    // - Acepta tanto "name.txt" como "name_invalid.txt" en requestedFilename
    // - Si hay marca invalid o existe el invalid copy, trae desde master, crea name.txt y borra name_invalid.txt
    // -------------------
    public void accessFile(Path replicaPath, String requestedFilename) {
        // Normalizar: calcular nombre original (sin sufijo)
        String originalName = stripInvalidSuffix(requestedFilename);
        Path source = masterPath.resolve(originalName);
        Path target = replicaPath.resolve(originalName);
        Path invalidCopy = replicaPath.resolve(appendInvalidSuffix(originalName));

        boolean invalidFlag = invalidationMap.getOrDefault(originalName, false);
        boolean invalidCopyExists = Files.exists(invalidCopy);

        // Si estamos marcados inválidos o existe la copia inválida -> intentar revalidar desde master
        if (invalidFlag || invalidCopyExists) {
            try {
                if (Files.exists(source)) {
                    // copiar la versión actual desde master a la réplica (nombre original)
                    Files.createDirectories(replicaPath);
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    // eliminar la copia inválida si existía
                    Files.deleteIfExists(invalidCopy);
                    // actualizar el estado
                    invalidationMap.put(originalName, false);
                    System.out.println("[ACCESS] Revalidado: " + originalName + " en " + replicaPath);
                } else {
                    // El master ya no tiene el archivo: no podemos revalidar, dejamos el invalidCopy si existe
                    System.out.println("[ACCESS] No existe en master: " + originalName + " — no se revalidó.");
                }
            } catch (IOException e) {
                System.err.println("[ACCESS][ERROR] No se pudo revalidar " + originalName + " en " + replicaPath + ": " + e.getMessage());
            }
        } else {
            // No está invalidado — nada que hacer
            System.out.println("[ACCESS] Archivo ya válido: " + originalName + " en " + replicaPath);
        }
    }

    // -------------------
    // Eliminación: si se borra en master, borrarlo en las réplicas
    // -------------------
    public void removeFileFromReplicas(String filename) {
        invalidationMap.remove(filename); // ya no tiene sentido llevar registro
        for (Path replica : replicas) {
            Path original = replica.resolve(filename);
            Path invalidCopy = replica.resolve(appendInvalidSuffix(filename));
            try {
                Files.deleteIfExists(original);
                Files.deleteIfExists(invalidCopy);
                System.out.println("[DELETE] Borrado " + filename + " en " + replica);
            } catch (IOException e) {
                System.err.println("[DELETE][ERROR] No se pudo borrar " + filename + " en " + replica + ": " + e.getMessage());
            }
        }
    }

    // -------------------
    // Helpers para sufijo "_invalid"
    // -------------------
    public String appendInvalidSuffix(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) {
            return filename + "_invalid";
        } else {
            String name = filename.substring(0, dotIndex);
            String ext = filename.substring(dotIndex); // incluye punto
            return name + "_invalid" + ext;
        }
    }

    public String stripInvalidSuffix(String filename) {
        // si viene "foo_invalid.txt" -> devuelve "foo.txt"
        // si viene "foo.txt" -> devuelve "foo.txt"
        int dotIndex = filename.lastIndexOf('.');
        String base = (dotIndex == -1) ? filename : filename.substring(0, dotIndex);
        String ext = (dotIndex == -1) ? "" : filename.substring(dotIndex); // incluye punto o vacío

        String suffix = "_invalid";
        if (base.endsWith(suffix)) {
            String originalBase = base.substring(0, base.length() - suffix.length());
            return originalBase + ext;
        } else {
            return filename;
        }
    }
}
