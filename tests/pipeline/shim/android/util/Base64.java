package android.util;
public class Base64 {
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] b, int f) { return java.util.Base64.getEncoder().encodeToString(b); }
    public static byte[] decode(String s, int f) { return java.util.Base64.getDecoder().decode(s); }
}
