class FileMetadata {
    private String name;
    private String lastModified;
    private String size;

    public FileMetadata(String name, String lastModified, String size) {
        this.name = name;
        this.lastModified = lastModified;
        this.size = size;
    }

    public String getName() { return name; }
    public String getLastModified() { return lastModified; }
    public String getSize() { return size; }
}
