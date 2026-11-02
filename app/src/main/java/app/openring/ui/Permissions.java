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

    /**
     * The decision after a request came back (the rule of issue #140), with the remembered refusal stored.
     * {@code quietBefore}: the screen's last answer was a refusal without explanation.
     */
    static app.openring.core.PermissionAsk.Answer answer(Activity a, String[] perms, int[] results, boolean quietBefore) {
        app.openring.core.PermissionAsk.Answer r = app.openring.core.PermissionAsk.answer(granted(a), perms, results, needed(),
                rationale(a), app.openring.data.Prefs.of(a).permissionDenied(), quietBefore);
        store(a, r.denied);
        return r;
    }

    /** Opens this app's page in Android's settings, where a permission Android no longer asks for is switched on (issue #186). */
    static void openSettings(Activity a) {
        try {
            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", a.getPackageName(), null)));
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(a, "The app settings did not open. Find them in Settings, Apps.", android.widget.Toast.LENGTH_LONG).show();
        }
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
