import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class FolderSynchronizer {
    private final Path masterPath;
    private final List<Path> replicas;

    public FolderSynchronizer(String masterDir, List<String> replicaDirs) {
        this.masterPath = Paths.get(masterDir);
        this.replicas = replicaDirs.stream().map(Paths::get).toList();
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
