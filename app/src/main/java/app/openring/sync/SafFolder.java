package app.openring.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.openring.core.Backups;
import app.openring.data.AutoBackup;

/**
 * The folder the person picked for the automatic backups (issue #242), through Android's storage access framework: a
 * tree URI whose permission the app keeps ({@code takePersistableUriPermission}). No storage permission, no network.
 * Files are found by their display name; a folder that cannot be listed (card removed, permission taken back) is
 * {@link AutoBackup.Gone}, a write that hits a full disk is {@link AutoBackup.Full}.
 */
public final class SafFolder implements AutoBackup.Folder {
    private final ContentResolver cr;
    private final Uri tree;
    /** Display name to document id, from the last {@link #list}. */
    private final Map<String, String> ids = new HashMap<String, String>();

    public SafFolder(Context c, Uri tree) {
        this.cr = c.getContentResolver();
        this.tree = tree;
    }

    /** The folder saved in the settings, or null without one. */
    public static SafFolder saved(Context c) {
        String u = app.openring.data.Prefs.of(c).backupFolder();
        return u == null ? null : new SafFolder(c.getApplicationContext(), Uri.parse(u));
    }

    /**
     * Keeps the permission for a folder just picked, with the flags of the picker's result ({@code resultFlags}), and
     * returns its name; null when Android does not allow it or the picker gave no write access.
     */
    public static String keep(Context c, Uri tree, int resultFlags) {
        int flags = Backups.keepFlags(resultFlags);
        if (flags < 0) return null;
        try {
            c.getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (RuntimeException e) {
            return null;
        }
        String name = null;
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Cursor cur = c.getContentResolver().query(doc, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) name = cur.getString(0);
                } finally {
                    cur.close();
                }
            }
        } catch (RuntimeException e) {
            // the name is only shown; the folder works without it
        }
        return name == null || name.isEmpty() ? "chosen folder" : name;
    }

    /** Gives a folder's permission back (another folder chosen, or the backups stopped). */
    public static void release(Context c, String uri) {
        if (uri == null) return;
        try {
            c.getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException e) {
            // already gone
        }
    }

    private Uri folderDoc() throws IOException {
        try {
            return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        } catch (RuntimeException e) {
            throw new AutoBackup.Gone("not a folder: " + e, e);
        }
    }

    @Override
    public List<String> list() throws IOException {
        List<String> out = new ArrayList<String>();
        Cursor cur;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            cur = cr.query(children, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
        } catch (RuntimeException e) {
            // SecurityException (permission taken back), IllegalArgumentException (card removed)
            throw new AutoBackup.Gone(String.valueOf(e), e);
        }
        if (cur == null) throw new AutoBackup.Gone("the folder could not be listed", null);
        synchronized (ids) {
            ids.clear();
            try {
                while (cur.moveToNext()) {
                    String id = cur.getString(0), name = cur.getString(1);
                    if (name == null) continue;
                    ids.put(name, id);
                    out.add(name);
                }
            } finally {
                cur.close();
            }
        }
        return out;
    }

    private Uri uriOf(String name) throws IOException {
        String id;
        synchronized (ids) {
            id = ids.get(name);
        }
        if (id == null) {
            list();
            synchronized (ids) {
                id = ids.get(name);
            }
        }
        if (id == null) throw new FileNotFoundException(name + " is not in the folder");
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    @Override
    public OutputStream create(String name) throws IOException {
        Uri doc;
        try {
            // octet stream: a provider would add an extension to a known type, and .partial must stay
            doc = DocumentsContract.createDocument(cr, folderDoc(), "application/octet-stream", name);
        } catch (FileNotFoundException | RuntimeException e) {
            throw new AutoBackup.Gone(String.valueOf(e), e);
        }
        if (doc == null) throw new AutoBackup.Gone("the file could not be made", null);
        String made = displayName(doc);
        if (made != null && !made.equals(name)) {
            // the provider changed the name (a file with it appeared meanwhile): ours must be found by its name
            try {
                DocumentsContract.deleteDocument(cr, doc);
            } catch (Exception ignored) {
                // left with another name, which the app never counts nor deletes
            }
            throw new IOException("the folder gave the file another name: " + made);
        }
        OutputStream os;
        try {
            os = cr.openOutputStream(doc, "w");
        } catch (RuntimeException e) {
            throw new AutoBackup.Gone(String.valueOf(e), e);
        }
        if (os == null) throw new AutoBackup.Gone("the file could not be opened", null);
        synchronized (ids) {
            ids.put(name, DocumentsContract.getDocumentId(doc));
        }
        final OutputStream out = os;
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    out.write(b, off, len);
                } catch (IOException e) {
                    throw Backups.noSpace(String.valueOf(e)) ? new AutoBackup.Full(String.valueOf(e), e) : e;
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    out.flush();
                } catch (IOException e) {
                    throw Backups.noSpace(String.valueOf(e)) ? new AutoBackup.Full(String.valueOf(e), e) : e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    out.close();
                } catch (IOException e) {
                    throw Backups.noSpace(String.valueOf(e)) ? new AutoBackup.Full(String.valueOf(e), e) : e;
                }
            }
        };
    }

    private String displayName(Uri doc) {
        try {
            Cursor cur = cr.query(doc, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur == null) return null;
            try {
                return cur.moveToFirst() ? cur.getString(0) : null;
            } finally {
                cur.close();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public InputStream open(String name) throws IOException {
        try {
            InputStream in = cr.openInputStream(uriOf(name));
            if (in == null) throw new FileNotFoundException(name);
            return in;
        } catch (RuntimeException e) {
            throw new AutoBackup.Gone(String.valueOf(e), e);
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Uri renamed;
        try {
            renamed = DocumentsContract.renameDocument(cr, uriOf(from), to);
        } catch (RuntimeException e) {
            throw new IOException("rename failed: " + e, e);
        }
        if (renamed == null) throw new IOException("rename failed");
        String made = displayName(renamed);
        if (made != null && !made.equals(to)) throw new IOException("the folder gave the file another name: " + made);
        synchronized (ids) {
            ids.remove(from);
            ids.put(to, DocumentsContract.getDocumentId(renamed));
        }
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(cr, uriOf(name))) throw new IOException("delete failed");
        } catch (RuntimeException e) {
            throw new IOException("delete failed: " + e, e);
        }
        synchronized (ids) {
            ids.remove(name);
        }
    }
}
