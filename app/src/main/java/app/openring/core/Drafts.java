package app.openring.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed but not yet saved texts of a screen's inputs, by name. A screen built in code copies them in
 * before it replaces its inputs (after a ring action, or when the phone turns) and fills the new
 * inputs from them, so nothing typed disappears (issue #133).
 */
public final class Drafts {
    private final Map<String, String> texts = new LinkedHashMap<String, String>();

    /** Keeps {@code text} for the input {@code name}; null keeps nothing. An empty text is kept as typed. */
    public void keep(String name, CharSequence text) {
        if (name == null || text == null) return;
        texts.put(name, text.toString());
    }

    /** The kept text of {@code name}, or the saved number when nothing was kept. */
    public String text(String name, int saved) {
        String t = texts.get(name);
        return t != null ? t : String.valueOf(saved);
    }

    /** True when a text is kept for {@code name}. */
    public boolean has(String name) {
        return texts.containsKey(name);
    }

    /** Names and texts in turn, for Android's saved state. */
    public String[] toArray() {
        String[] out = new String[texts.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : texts.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    /** Back from {@link #toArray}; a missing or broken list gives no drafts. */
    public static Drafts fromArray(String[] a) {
        Drafts d = new Drafts();
        if (a == null || a.length % 2 != 0) return d;
        for (int i = 0; i < a.length; i += 2) d.keep(a[i], a[i + 1]);
        return d;
    }
}
