package android.content;
import java.util.LinkedHashMap;
public class ContentValues {
    public final LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
    public void put(String k, String v) { map.put(k, v); }
    public void put(String k, Long v) { map.put(k, v); }
    public void put(String k, Integer v) { map.put(k, v); }
    public void put(String k, Double v) { map.put(k, v); }
    public void put(String k, byte[] v) { map.put(k, v); }
}
