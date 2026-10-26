package app.openring.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Runtime permissions for Bluetooth (and notifications for the sync indicator). */
public final class Permissions {
    private Permissions() {}

    static String[] needed() {
        List<String> l = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return l.toArray(new String[0]);
    }

    public static boolean granted(Context c) {
        for (String p : needed()) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** For each of {@link #needed}, whether Android would explain it now (it was refused once, issue #140). */
    static boolean[] rationale(Activity a) {
        String[] n = needed();
        boolean[] out = new boolean[n.length];
        for (int i = 0; i < n.length; i++) out[i] = a.checkSelfPermission(n[i]) != PackageManager.PERMISSION_GRANTED && a.shouldShowRequestPermissionRationale(n[i]);
        return out;
    }

    /**
     * Remembers an explicit refusal (Android explains the permission now) and forgets it once granted, so a
     * later refusal without explanation can be told from a dialog closed without choosing (issue #140).
     */
    static void remember(Activity a) {
        store(a, app.openring.core.PermissionAsk.remember(granted(a), rationale(a), app.openring.data.Prefs.of(a).permissionDenied()));
    }

    /** Saves the remembered explicit refusal when it changed. */
    static void store(Activity a, boolean denied) {
        app.openring.data.Prefs p = app.openring.data.Prefs.of(a);
        if (p.permissionDenied() != denied) p.setPermissionDenied(denied);
    }

    public static void request(Activity a) {
        // a second request only comes after an answer to the first: a refusal then shows here
        remember(a);
        List<String> l = new ArrayList<String>();
        for (String p : needed()) l.add(p);
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
        a.requestPermissions(l.toArray(new String[0]), 1);
    }
}
