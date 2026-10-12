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

    public static void request(Activity a) {
        List<String> l = new ArrayList<String>();
        for (String p : needed()) l.add(p);
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
        a.requestPermissions(l.toArray(new String[0]), 1);
    }
}
