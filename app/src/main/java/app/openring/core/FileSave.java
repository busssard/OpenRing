package app.openring.core;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writing a file the person picked in Settings (a backup or an export, issue #222). Android makes the file before the
 * app writes anything, so a save that fails would leave it behind: empty, or cut off on the way (disk full, the file's
 * app gone) and looking like a backup. After any failure the file that was started is removed; when it cannot be
 * removed and holds something, {@link Outcome#leftOver} says so, so the screen can tell the person it is incomplete.
 * A save the app does not live to finish (Android ended it) cannot be cleaned up here: restoring such a file says it is
 * damaged and changes nothing. Plain Java so the JVM tests cover it.
 */
public final class FileSave {
    private FileSave() {}

    /** The file the person picked. */
    public interface Target {
        /** Opens it for writing; null when Android gave none. */
        OutputStream open() throws Exception;

        /** Removes it; false (or an exception) when that did not work. */
        boolean delete() throws Exception;
    }

    /** What goes into the file. */
    public interface Body {
        void write(OutputStream os) throws Exception;
    }

    /** How a save went. */
    public static final class Outcome {
        /** Why it failed, null when the file is complete. */
        public Exception error;
        /** Bytes that reached the file. */
        public long bytes;
        /** The file that was started was removed after the failure. */
        public boolean removed;
        /** The failed file is still there and holds part of the content: it is incomplete. */
        public boolean leftOver;
        /** Why the started file could not be removed (for the log), null when it was or nothing was started. */
        public String removeError;

        public boolean ok() {
            return error == null;
        }
    }

    /** Writes the file; an {@link Error} from {@code b} is thrown on after the started file was removed. */
    public static Outcome save(Target t, Body b) {
        final Outcome o = new Outcome();
        OutputStream raw;
        try {
            raw = t.open();
        } catch (Exception e) {
            o.error = e;
            return o;
        }
        if (raw == null) {
            o.error = new IOException("Android gave no file to write to");
            return o;
        }
        OutputStream os = new FilterOutputStream(raw) {
            @Override
            public void write(int x) throws IOException {
                out.write(x);
                o.bytes++;
            }

            @Override
            public void write(byte[] x, int off, int len) throws IOException {
                out.write(x, off, len);
                o.bytes += len;
            }
        };
        // also an Error (out of memory) removes the started file first, then goes on to the caller
        Error fatal = null;
        try {
            b.write(os);
            os.flush();
        } catch (Exception e) {
            o.error = e;
        } catch (Error e) {
            fatal = e;
            o.error = new IOException(e.toString());
        }
        try {
            os.close();
        } catch (Exception e) {
            // a file whose last bytes did not reach the disk is not complete either
            if (o.error == null) o.error = e;
        }
        if (o.error == null) return o;
        try {
            o.removed = t.delete();
            if (!o.removed) o.removeError = "not removed";
        } catch (Exception e) {
            o.removeError = String.valueOf(e);
        }
        o.leftOver = !o.removed && o.bytes > 0;
        if (fatal != null) throw fatal;
        return o;
    }
}
