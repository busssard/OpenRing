package app.openring.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ble/Gatt on the JVM, with small stand ins for the Android Bluetooth classes (tests/ble/shim): the exact calls each
 * helper makes on Android 13 and newer (API 33) and before, what it answers, and that closing never throws. Then, from
 * the source: no link copies these calls any more, and every link keeps its own waits, timeouts and order.
 * Run by tests/run_tests.sh from the repository root.
 */
public final class GattTest {
    private static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "  (" + detail + ")");
        }
    }

    static void eq(String name, Object got, Object want) {
        check(name, want.equals(got), "got " + got + ", want " + want);
    }

    static List<String> calls() {
        List<String> c = new ArrayList<String>();
        SystemClock.calls = c;
        return c;
    }

    public static void main(String[] args) throws Exception {
        descriptorWrites();
        characteristicWrites();
        closing();
        sources();
        System.out.println("ble Gatt: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The descriptor write as GattLink, WatchLink and WatchSync wrote it inline before (#418 Experiment view, point 3). */
    static void descriptorWrites() {
        byte[] note = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, ind = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        for (int sdk : new int[] {33, 34, 35}) {
            Build.VERSION.SDK_INT = sdk;
            List<String> c = calls();
            BluetoothGatt g = new BluetoothGatt(c);
            BluetoothGattDescriptor d = new BluetoothGattDescriptor(c);
            g.code = 0;
            eq("API " + sdk + ": the descriptor write started when Android answers 0", Gatt.startDescriptorWrite(g, d, note), true);
            eq("API " + sdk + ": one call with the value, nothing set on the descriptor", c, Arrays.asList("writeDescriptor33 0100"));
            c.clear();
            g.code = 201;
            eq("API " + sdk + ": any other answer is not started", Gatt.startDescriptorWrite(g, d, ind), false);
            eq("API " + sdk + ": the indication value goes out as given", c, Arrays.asList("writeDescriptor33 0200"));
        }
        for (int sdk : new int[] {26, 31, 32}) {
            Build.VERSION.SDK_INT = sdk;
            List<String> c = calls();
            BluetoothGatt g = new BluetoothGatt(c);
            BluetoothGattDescriptor d = new BluetoothGattDescriptor(c);
            g.code = 201; // the API 33 answer must not matter here
            g.started = true;
            eq("API " + sdk + ": the descriptor write started as Android says", Gatt.startDescriptorWrite(g, d, note), true);
            eq("API " + sdk + ": the value is set first, then the descriptor written", c, Arrays.asList("descriptor.setValue 0100", "writeDescriptor"));
            c.clear();
            g.started = false;
            eq("API " + sdk + ": refused by Android is not started", Gatt.startDescriptorWrite(g, d, ind), false);
            eq("API " + sdk + ": the indication value is set as given", c, Arrays.asList("descriptor.setValue 0200", "writeDescriptor"));
        }
    }

    /** The characteristic write as GattLink.write and WatchSync's send wrote it inline before. */
    static void characteristicWrites() {
        byte[] data = {0x2f, 0x01, 0x7e};
        for (int sdk : new int[] {33, 34}) {
            Build.VERSION.SDK_INT = sdk;
            List<String> c = calls();
            BluetoothGatt g = new BluetoothGatt(c);
            BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(c);
            g.code = 0;
            eq("API " + sdk + ": the write started when Android answers 0", Gatt.startWrite(g, ch, data), true);
            eq("API " + sdk + ": one call with the data and the default write type (with a response)", c,
                    Arrays.asList("writeCharacteristic33 2f017e type " + BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
            c.clear();
            g.code = 201;
            eq("API " + sdk + ": a busy stack is not started", Gatt.startWrite(g, ch, data), false);
        }
        for (int sdk : new int[] {26, 32}) {
            Build.VERSION.SDK_INT = sdk;
            List<String> c = calls();
            BluetoothGatt g = new BluetoothGatt(c);
            BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(c);
            g.code = 201;
            g.started = true;
            eq("API " + sdk + ": the write started as Android says", Gatt.startWrite(g, ch, data), true);
            eq("API " + sdk + ": write type, then value, then the write", c, Arrays.asList(
                    "characteristic.setWriteType " + BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, "characteristic.setValue 2f017e", "writeCharacteristic"));
            c.clear();
            g.started = false;
            eq("API " + sdk + ": refused by Android is not started", Gatt.startWrite(g, ch, data), false);
        }
    }

    /** close() of the three links: disconnect, 200 ms, close, and a throw from Android swallowed (Bluetooth went off). */
    static void closing() {
        eq("the pause before freeing the link is 200 ms, as in the three copies before", Gatt.CLOSE_PAUSE_MS, 200);
        List<String> c = calls();
        eq("no link: nothing to close, nothing thrown", Gatt.close(null), true);
        eq("no link: no call", c, new ArrayList<String>());

        c = calls();
        BluetoothGatt g = new BluetoothGatt(c);
        eq("a link closes cleanly", Gatt.close(g), true);
        eq("disconnect, the pause, then close", c, Arrays.asList("disconnect", "sleep 200", "close"));

        c = calls();
        g = new BluetoothGatt(c);
        g.fail = "disconnect";
        eq("Bluetooth off at disconnect: no throw, says so", Gatt.close(g), false);
        eq("Bluetooth off at disconnect: nothing after it, as before", c, Arrays.asList("disconnect"));

        c = calls();
        g = new BluetoothGatt(c);
        g.fail = "close";
        eq("Bluetooth off at close: no throw, says so", Gatt.close(g), false);
        eq("Bluetooth off at close: disconnect and the pause came first", c, Arrays.asList("disconnect", "sleep 200", "close"));
    }

    static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), "UTF-8");
    }

    static boolean has(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }

    static void sources() throws Exception {
        String root = "app/src/main/java/app/openring/";
        // one copy: no other file writes descriptors or characteristics, or disconnects, by itself
        List<File> all = new ArrayList<File>();
        collect(new File(root), all);
        List<String> desc = new ArrayList<String>(), write = new ArrayList<String>(), disc = new ArrayList<String>();
        for (File f : all) {
            if (f.getPath().endsWith("ble/Gatt.java")) continue;
            String s = read(f.getPath());
            String name = f.getPath().substring(root.length());
            if (s.contains(".writeDescriptor(")) desc.add(name);
            if (s.contains(".writeCharacteristic(")) write.add(name);
            if (s.contains(".disconnect()")) disc.add(name);
        }
        check("the app's sources were found", all.size() > 50 && read(root + "ble/Gatt.java").contains(".writeDescriptor("), all.size());
        eq("no file but ble/Gatt writes a descriptor itself (Gatt.startDescriptorWrite)", desc, new ArrayList<String>());
        eq("no file but ble/Gatt writes a characteristic itself (Gatt.startWrite)", write, new ArrayList<String>());
        eq("no file but ble/Gatt disconnects a link itself (Gatt.close)", disc, new ArrayList<String>());

        // what stays with each link, unchanged: its queue cleared before the write starts, its wait, its timeout
        String gl = read(root + "ble/GattLink.java"), wl = read(root + "ble/WatchLink.java"), ws = read(root + "ble/WatchSync.java");
        check("GattLink other channels: acks cleared, then the write, then 5 s only for a started write",
                has(gl, "descAcks\\.clear\\(\\);\\s*boolean started[^;]*;[\\s\\S]{0,700}?Integer st = started \\? descAcks\\.poll\\(5, TimeUnit\\.SECONDS\\) : null;"), "");
        check("GattLink subscribe: acks cleared, a refused start returns false, then up to 45 s for the pairing consent",
                has(gl, "descAcks\\.clear\\(\\);\\s*boolean started[\\s\\S]{0,700}?if \\(!started\\) return false;\\s*//[^\\n]*\\n\\s*Integer st = descAcks\\.poll\\(45, TimeUnit\\.SECONDS\\);"), "");
        check("GattLink write: up to 5 tries 60 ms apart, then 6 s for the answer",
                has(gl, "for \\(int attempt = 0; attempt < 5 && !started; attempt\\+\\+\\) \\{[\\s\\S]{0,500}?if \\(!started\\) SystemClock\\.sleep\\(60\\);\\s*\\}")
                        && gl.contains("writeAcks.poll(6, TimeUnit.SECONDS)"), "");
        check("WatchLink: acks cleared, then the write, then at most 3 s and never past the check's end",
                has(wl, "descAcks\\.clear\\(\\);\\s*boolean started[\\s\\S]{0,700}?Integer st = started \\? descAcks\\.poll\\(Math\\.min\\(3000, left\\(end\\)\\), TimeUnit\\.MILLISECONDS\\) : null;"), "");
        check("WatchSync open: acks cleared, then the write, then one step's wait only for a started write",
                has(ws, "acks\\.clear\\(\\);\\s*boolean started[\\s\\S]{0,700}?Integer ack = started \\? acks\\.poll\\(STEP_MS, TimeUnit\\.MILLISECONDS\\) : null;"), "");
        check("WatchSync send: writes cleared, a refused start throws, then one step's wait",
                has(ws, "writes\\.clear\\(\\);\\s*boolean started[\\s\\S]{0,700}?if \\(!started\\) throw new IOException\\(\"write refused\"\\);\\s*Integer st = writes\\.poll\\(STEP_MS, TimeUnit\\.MILLISECONDS\\);"), "");
        for (String[] f : new String[][] {{"GattLink", gl}, {"WatchLink", wl}, {"WatchSync", ws}}) {
            check(f[0] + " close: the link is marked gone and dropped after closing", has(f[1], "connected = false;\\s*gatt = null;\\s*\\}"), "");
        }
        check("WatchSync close: logs closed only after a clean close", ws.contains("if (gatt != null && Gatt.close(gatt)) ring.log(\"watch: closed\""), "");
    }

    static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }
}
