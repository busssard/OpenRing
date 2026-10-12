package android.database;
import java.util.ArrayList;
import java.util.List;
/** Fully buffered cursor over a JDBC result. */
public class Cursor {
    final List<Object[]> rows = new ArrayList<Object[]>();
    int pos = -1;
    public Cursor(java.sql.ResultSet rs) throws java.sql.SQLException {
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) { Object[] r = new Object[n]; for (int i = 0; i < n; i++) r[i] = rs.getObject(i + 1); rows.add(r); }
        rs.close();
    }
    public int getCount() { return rows.size(); }
    public boolean moveToFirst() { pos = 0; return !rows.isEmpty(); }
    public boolean moveToNext() { pos++; return pos < rows.size(); }
    Object v(int i) { return rows.get(pos)[i]; }
    public boolean isNull(int i) { return v(i) == null; }
    public long getLong(int i) { Object o = v(i); return o == null ? 0 : o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString()); }
    public int getInt(int i) { return (int) getLong(i); }
    public double getDouble(int i) { Object o = v(i); return o == null ? 0 : o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(o.toString()); }
    public String getString(int i) { Object o = v(i); return o == null ? null : o.toString(); }
    public byte[] getBlob(int i) { return (byte[]) v(i); }
    public void close() {}
}
