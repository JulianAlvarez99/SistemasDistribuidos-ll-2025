import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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

    public Repository() {
        repositoryService = new FileRepositoryService();
        tableModel = new FileTableModel();
        dirTable.setModel(tableModel);
        updater = new RepositoryUIUpdater(repositoryService, tableModel, updateLabel);

        startBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = pathField.getText().trim();
                if (!path.isEmpty()) {
                    updater.start(path);
                } else {
                    JOptionPane.showMessageDialog(mainPanel, "Ingrese un directorio válido.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
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
         synchronizer.startContinuousConsistency(7500);

        // Lanzar UI
        JFrame frame = new JFrame("File Repository");
        frame.setContentPane(new Repository().mainPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

}
