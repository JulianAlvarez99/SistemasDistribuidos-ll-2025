import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

class FileTableModel extends AbstractTableModel {
    private final String[] columns = {"Nombre", "Fecha de Modificación", "Tamaño"};
    private List<FileMetadata> data = new ArrayList<>();

    public void setData(List<FileMetadata> newData) {
        this.data = newData;
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() { return data.size(); }

    @Override
    public int getColumnCount() { return columns.length; }

    @Override
    public String getColumnName(int column) { return columns[column]; }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        FileMetadata file = data.get(rowIndex);
        switch (columnIndex) {
            case 0: return file.getName();
            case 1: return file.getLastModified();
            case 2: return file.getSize();
            default: return null;
        }
    }
}