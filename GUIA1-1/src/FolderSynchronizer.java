import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class FolderSynchronizer {
    private final Path masterPath;
    private final List<Path> replicas;
    private final Map<String, Boolean> invalidationMap = new HashMap<>();

    public FolderSynchronizer(String masterDir, List<String> replicaDirs) {
        this.masterPath = Paths.get(masterDir);
        this.replicas = replicaDirs.stream().map(Paths::get).toList();
        initializeState();
    }

    // Al inicio, se asume que todo lo que hay en Master está válido
    private void initializeState() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(masterPath)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    invalidationMap.put(file.getFileName().toString(), false);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Arranca el proceso de invalidación basado en eventos
    public void startInvalidation() {
        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                masterPath.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );

                while (true) {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = masterPath.resolve((Path) event.context());
                        String fileName = changed.getFileName().toString();

                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            // Eliminación: propagar y sacar del mapa
                            deleteFileFromReplicas(fileName);
                            invalidationMap.remove(fileName);
                        } else {
                            // Creación o modificación: marcar como inválido
                            invalidationMap.put(fileName, true);
                            System.out.println("[INVALIDACIÓN] Archivo " + fileName + " marcado como inválido.");
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Cuando una réplica accede a un archivo, primero pregunta acá.
     * Si está inválido, se actualiza bajo demanda desde Master.
     */
    public void fetchIfInvalid(String fileName, Path replicaPath) {
        Boolean invalid = invalidationMap.get(fileName);

        if (invalid != null && invalid) {
            Path source = masterPath.resolve(fileName);
            Path target = replicaPath.resolve(fileName);
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                invalidationMap.put(fileName, false);
                System.out.println("[SYNC] Archivo " + fileName + " transferido bajo demanda a " + replicaPath);
            } catch (IOException e) {
                System.err.println("[SYNC][ERROR] No se pudo copiar " + fileName + ": " + e.getMessage());
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
                            deleteFileFromReplicas(changedFile.getFileName().toString());
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
    // Consistencia continua (Timer)
    // ------------------------------
    public void startContinuousConsistency(long intervalMs) {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                syncAllFiles();
            }
        }, 0, intervalMs);
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

    private void deleteFileFromReplicas(String fileName) {
        for (Path replica : replicas) {
            Path target = replica.resolve(fileName);
            try {
                if (Files.deleteIfExists(target)) {
                    System.out.println("[SYNC] Eliminado " + fileName + " en " + replica);
                }
            } catch (IOException e) {
                System.err.println("[SYNC][ERROR] No se pudo eliminar " + fileName + " en " + replica + ": " + e.getMessage());
            }
        }
    }

    /**
     * Sincroniza el estado completo:
     * 1) Copia/actualiza todos los archivos que están en Master.
     * 2) Elimina de cada réplica los archivos que NO estén en Master.
     * (No recursivo; solo archivos en el nivel de la carpeta Master/Replica)
     */
    private void syncAllFiles() {
        // 1) Replicar/actualizar desde Master
        File[] masterFilesArr = masterPath.toFile().listFiles(File::isFile);
        Set<String> masterNames = new HashSet<>();
        if (masterFilesArr != null) {
            for (File f : masterFilesArr) {
                masterNames.add(f.getName());
                replicateFile(f);
            }
        }

        // 2) Eliminar sobrantes en cada réplica
        for (Path replica : replicas) {
            File[] replicaFiles = replica.toFile().listFiles(File::isFile);
            if (replicaFiles == null) continue;

            for (File rf : replicaFiles) {
                if (!masterNames.contains(rf.getName())) {
                    try {
                        if (Files.deleteIfExists(rf.toPath())) {
                            System.out.println("[SYNC] Podado (no está en Master): " + rf.getName() + " en " + replica);
                        }
                    } catch (IOException e) {
                        System.err.println("[SYNC][ERROR] No se pudo podar " + rf.getName() + " en " + replica + ": " + e.getMessage());
                    }
                }
            }
        }
    }
}
