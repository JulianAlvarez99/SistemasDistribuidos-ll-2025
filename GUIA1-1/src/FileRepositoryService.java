import javax.swing.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class FileRepositoryService {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    public List<FileMetadata> listFiles(String directoryPath) {
        List<FileMetadata> filesList = new ArrayList<>();
        File directory = new File(directoryPath);

        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        String lastModified = DATE_FORMAT.format(new Date(f.lastModified()));
                        String size = f.length()/1024 + " KB";
                        filesList.add(new FileMetadata(f.getName(), lastModified, size));
                    }
                }
            }
        }
        return filesList;
    }
}