import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    public Repository(FolderSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
        this.repositoryService = new FileRepositoryService();
        this.tableModel = new FileTableModel();
        this.updater = new RepositoryUIUpdater(repositoryService, tableModel, updateLabel);

        dirTable.setModel(tableModel);

        // Acción del botón "Iniciar" (usa lo que haya en pathField)
        startBtn.addActionListener(e -> {
            String newPath = pathField.getText().trim();
            if (newPath.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Ingrese la ruta en el campo path.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path masterPath = synchronizer.getMasterPath().toAbsolutePath().normalize();
            Path requestedPath = Paths.get(newPath).toAbsolutePath().normalize();

            if (requestedPath.equals(masterPath)) {
                // Mostrar directamente el master en la UI
                JOptionPane.showMessageDialog(mainPanel, "Mostrando contenido del master.", "Info", JOptionPane.INFORMATION_MESSAGE);
                updater.start(newPath);
                return;
            }

            boolean ok = synchronizer.addReplica(newPath);
            if (ok) {
                JOptionPane.showMessageDialog(mainPanel, "Réplica añadida y sincronizada con master.", "Info", JOptionPane.INFORMATION_MESSAGE);
                updater.start(newPath);
            } else {
                JOptionPane.showMessageDialog(mainPanel, "No se pudo añadir la réplica (ver consola).", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Listener de doble click sobre la tabla - abre el archivo desde la ruta actual en pathField
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

                    // 1) Intentar revalidar si hace falta (accessFile maneja tanto name.txt como name_invalid.txt)
                    synchronizer.accessFile(folderPath, fileName);

                    // 2) Determinar qué archivo abrir: preferir el nombre original ya revalidado
                    String originalName = synchronizer.stripInvalidSuffix(fileName);
                    Path originalPath = folderPath.resolve(originalName);
                    if(fileName.toLowerCase().endsWith(".txt")){
                        try {
                            if (Files.exists(originalPath)) {
                                String content = Files.readString(originalPath, StandardCharsets.UTF_8);
                                showTextFileDialog(originalName, content);
                            } else {
                                // si no existe la versión original, intentar abrir la copia inválida (si existe)
                                Path invalidCopy = folderPath.resolve(synchronizer.appendInvalidSuffix(originalName));
                                if (Files.exists(invalidCopy)) {
                                    String content = Files.readString(invalidCopy, StandardCharsets.UTF_8);
                                    showTextFileDialog(invalidCopy.getFileName().toString(), content);
                                } else {
                                    JOptionPane.showMessageDialog(mainPanel, "El archivo no existe (ni versión válida ni inválida).", "Información", JOptionPane.INFORMATION_MESSAGE);
                                }
                            }
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

    /**
     * Llamar desde el main al cerrar la ventana para detener timers/hilos del updater.
     */
    public void shutdown() {
        updater.stop();
        // Si añadiste stop en FolderSynchronizer, aquí lo llamas:
//         synchronizer.stopContinuousConsistency();
//         synchronizer.stopStrictConsistency();
    }

    public static void main(String[] args) {
        // Rutas por defecto (puedes mantenerlas como ejemplo)
        String master = "C:/Users/julia/Desktop/SistDistribuidos/RepoMaster";
        List<String> replicas = List.of();

        FolderSynchronizer synchronizer = new FolderSynchronizer(master, replicas);
//        synchronizer.startContinuousConsistency(8000);
        synchronizer.startInvalidation(8000);

        // Lanzar UI
        JFrame frame = new JFrame("File Repository");
        Repository repoUI = new Repository(synchronizer);
        frame.setContentPane(repoUI.mainPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(700, 500);
        frame.setLocationRelativeTo(null);

        // Shutdown hook: cuando la ventana se cierra, detener timers/monitores limpias
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
