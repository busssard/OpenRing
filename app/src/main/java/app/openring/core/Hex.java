package app.openring.core;

/** Small hex helpers. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] data) {
        if (data == null) return "";
        return encode(data, 0, data.length);
    }

    public static String encode(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xff;
            sb.append(DIGITS[b >>> 4]).append(DIGITS[b & 0x0f]);
        }
        return sb.toString();
    }

    /**
     * Decode a hex string (whitespace, colons and spaces are ignored, also a byte order mark and a no
     * break space, which editors like Windows Notepad write into a saved key, issue #33). Returns null
     * when invalid.
     */
    public static byte[] decode(String s) {
        if (s == null) return null;
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF' || c == ':') continue;
            clean.append(c);
        }
        String h = clean.toString();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.length() % 2 != 0) return null;
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16);
            int lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Parse a 16 byte key given as 32 hex characters, or null. */
    public static byte[] parseKey(String s) {
        byte[] k = decode(s);
        return (k != null && k.length == 16) ? k : null;
    }
}
