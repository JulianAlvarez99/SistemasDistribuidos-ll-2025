import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

class RepositoryUIUpdater {
    private final FileRepositoryService service;
    private final FileTableModel tableModel;
    private final JLabel updateLabel;
    private Timer timer;

    public RepositoryUIUpdater(FileRepositoryService service, FileTableModel tableModel, JLabel updateLabel) {
        this.service = service;
        this.tableModel = tableModel;
        this.updateLabel = updateLabel;
    }

    public synchronized void start(String path) {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                List<FileMetadata> files = service.listFiles(path);
                SwingUtilities.invokeLater(() -> {
                    tableModel.setData(files);
                    updateLabel.setText("Última actualización: " +
                            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
                });
            }
        }, 500, 3000);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
            SwingUtilities.invokeLater(() -> updateLabel.setText("Monitor detenido"));
        }
    }
}

public class Repository {
    public JPanel mainPanel;
    private JLabel repoLabel;
    private JTextField pathField;
    private JButton startBtn;
    private JTable dirTable;
    private JLabel updateLabel;
    private JPanel configPanel;
    private JScrollPane scrollPane;

    private final FileRepositoryService repositoryService;
    private final FileTableModel tableModel;
    private final RepositoryUIUpdater updater;
    private final FolderSynchronizer synchronizer;
    private CacheManager cacheManager; // 🚀 Integración

    public Repository(FolderSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
        this.repositoryService = new FileRepositoryService();
        this.tableModel = new FileTableModel();
        this.updater = new RepositoryUIUpdater(repositoryService, tableModel, updateLabel);

        dirTable.setModel(tableModel);

        // Acción del botón "Iniciar"
        startBtn.addActionListener(e -> {
            String newPath = pathField.getText().trim();
            if (newPath.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Ingrese la ruta en el campo path.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path masterPath = synchronizer.getMasterPath().toAbsolutePath().normalize();
            Path requestedPath = Paths.get(newPath).toAbsolutePath().normalize();

            if (requestedPath.equals(masterPath)) {
                JOptionPane.showMessageDialog(mainPanel, "Mostrando contenido del master.", "Info", JOptionPane.INFORMATION_MESSAGE);
                updater.start(newPath);
                return;
            }

            boolean ok = synchronizer.addReplica(newPath);
            if (ok) {
                JOptionPane.showMessageDialog(mainPanel, "Réplica añadida y sincronizada con master.", "Info", JOptionPane.INFORMATION_MESSAGE);
                updater.start(newPath);

                // 🚀 inicializar cache con carpeta dedicada
                String cacheDir = newPath + "_cache";
                cacheManager = new CacheManager(cacheDir, 120 ,synchronizer, mainPanel);
                System.out.println("[CACHE] CacheManager inicializado en: " + cacheDir);
            } else {
                JOptionPane.showMessageDialog(mainPanel, "No se pudo añadir la réplica (ver consola).", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Listener de doble click sobre la tabla
        dirTable.addMouseListener(new MouseInputAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && dirTable.getSelectedRow() != -1) {
                    int row = dirTable.getSelectedRow();
                    String fileName = (String) tableModel.getValueAt(row, 0);
                    String folder = pathField.getText().trim();
                    if (folder.isEmpty()) {
                        JOptionPane.showMessageDialog(mainPanel, "Defina el directorio en el campo 'path'.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    Path folderPath = Path.of(folder);

                    if (cacheManager != null && fileName.toLowerCase().endsWith(".txt")) {
                        // 🚀 toda la lógica de revalidación + apertura via cache
                        cacheManager.openFileWithCache(folderPath, fileName);
                    } else {
                        JOptionPane.showMessageDialog(mainPanel, "Solo se pueden abrir archivos .txt",
                                "Información", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        });
    }

    public void shutdown() {
        updater.stop();
    }

    public static void main(String[] args) {
        String master = "C:/Users/julia/Desktop/SistDistribuidos/RepoMaster";
        List<String> replicas = List.of();

        FolderSynchronizer synchronizer = new FolderSynchronizer(master, replicas);
        synchronizer.startInvalidation(8000);

        JFrame frame = new JFrame("File Repository");
        Repository repoUI = new Repository(synchronizer);
        frame.setContentPane(repoUI.mainPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(700, 500);
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                repoUI.shutdown();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                repoUI.shutdown();
            }
        });

        frame.setVisible(true);
    }
}
