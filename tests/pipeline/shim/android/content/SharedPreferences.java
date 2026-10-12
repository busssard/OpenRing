package android.content;
import java.util.HashMap;
import java.util.Map;
public class SharedPreferences {
    final Map<String, Object> m = new HashMap<String, Object>();
    public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : (Integer) v; }
    public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : (Long) v; }
    public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
    public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
    public boolean contains(String k) { return m.containsKey(k); }
    public Editor edit() { return new Editor(this); }
    public static class Editor {
        final SharedPreferences p;
        Editor(SharedPreferences p) { this.p = p; }
        public Editor putInt(String k, int v) { p.m.put(k, v); return this; }
        public Editor putLong(String k, long v) { p.m.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { p.m.put(k, v); return this; }
        public Editor putString(String k, String v) { p.m.put(k, v); return this; }
        public Editor remove(String k) { p.m.remove(k); return this; }
        public void apply() {}
        public boolean commit() { return true; }
    }
}
