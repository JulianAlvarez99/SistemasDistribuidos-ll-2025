import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public void start(String path) {
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
}

public class Repository {
    private JPanel mainPanel;
    private JLabel repoLabel;
    private JTextField pathField;
    private JButton startBtn;
    private JTable dirTable;
    private JLabel updateLabel;
    private JPanel configPanel;
    private JScrollPane scrollPane;

    private FileRepositoryService repositoryService;
    private FileTableModel tableModel;
    private RepositoryUIUpdater updater;
    private FolderSynchronizer synchronizer;

    public Repository(FolderSynchronizer synchronizer) {
        repositoryService = new FileRepositoryService();
        tableModel = new FileTableModel();
        dirTable.setModel(tableModel);
        updater = new RepositoryUIUpdater(repositoryService, tableModel, updateLabel);

        // Acción del botón "Iniciar"
        startBtn.addActionListener(e -> {
            String path = pathField.getText().trim();
            if (!path.isEmpty()) {
                updater.start(path);
            } else {
                JOptionPane.showMessageDialog(mainPanel, "Ingrese un directorio válido.", "Error", JOptionPane.ERROR_MESSAGE);
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
                    Path filePath = Path.of(folder, fileName);

                    if (fileName.toLowerCase().endsWith(".txt")) {
                        try {
                            // ---- NUEVO: sincronización bajo demanda ----
                            // Determinar la réplica correspondiente
                            Path replicaPath = Path.of(folder);
                            synchronizer.fetchIfInvalid(fileName, replicaPath);
                            // -------------------------------------------

                            // Leer el archivo ya actualizado
                            String content = Files.readString(filePath);
                            showTextFileDialog(fileName, content);
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(mainPanel, "Error al leer el archivo: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    } else {
                        JOptionPane.showMessageDialog(mainPanel, "Solo se pueden abrir archivos .txt",
                                "Información", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        });
    }

    private void showTextFileDialog(String fileName, String content) {
        JDialog dialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(mainPanel), "Contenido de " + fileName, true);
        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        dialog.add(new JScrollPane(textArea));
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setVisible(true);
    }

    public static void main(String[] args) {
        // Rutas
        String master = "C:/Users/julia/Desktop/SistDistribuidos/RepoMaster";
        List<String> replicas = List.of(
                "C:/Users/julia/Desktop/SistDistribuidos/Replica1",
                "C:/Users/julia/Desktop/SistDistribuidos/Replica2",
                "C:/Users/julia/Desktop/SistDistribuidos/Replica3"
        );

        FolderSynchronizer synchronizer = new FolderSynchronizer(master, replicas);

        // a) Consistencia estricta (cambios en tiempo real)
//        synchronizer.startStrictConsistency();

        // b) Consistencia continua (cada 7.5 segundos)
//         synchronizer.startContinuousConsistency(7500);

         //c) Modelo de invalidacion
        synchronizer.startInvalidation();

        // Lanzar UI
        JFrame frame = new JFrame("File Repository");
        frame.setContentPane(new Repository(synchronizer).mainPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

}
