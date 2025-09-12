import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class FileMetadata {
    private String fileName;
    private String content;
    private long lastModified;
    private long size;
    private String checksum;

    public FileMetadata(String fileName, String content, long lastModified, long size) {
        this.fileName = fileName;
        this.content = content;
        this.lastModified = lastModified;
        this.size = size;
        this.checksum = calculateChecksum(content);
    }

    private String calculateChecksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    public boolean needsSync(FileMetadata other) {
        if (other == null) return true;
        return !this.checksum.equals(other.checksum) ||
                this.lastModified > other.lastModified;
    }

    // Getters and Setters
    public String getFileName() { return fileName; }
    public String getContent() { return content; }
    public long getLastModified() { return lastModified; }
    public long getSize() { return size; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    @Override
    public String toString() {
        return String.format("FileMetadata{name='%s', size=%d, checksum='%s', lastModified=%d}",
                fileName, size, checksum, lastModified);
    }

    public static FileMetadata fromStateString(String fileName, String checksum, long lastModified, long size) {
        FileMetadata metadata = new FileMetadata(fileName, "", lastModified, size);
        metadata.setChecksum(checksum);
        return metadata;
    }


}