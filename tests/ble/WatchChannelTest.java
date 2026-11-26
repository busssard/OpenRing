package app.openring.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import app.openring.core.WatchCheck;
import app.openring.core.withings.WatchUuid;

/**
 * ble/WatchChannel on the JVM (issue #469, phase 1), with the stand ins of tests/ble/shim: which calls it makes on a
 * discovered layout and which channel it picks. The Steel HR's layout (the owner's Watch check report of 3 November on
 * issue #251) must give exactly the two look ups the sync always made, and nothing else. Then, from the source: the
 * watch sync's open keeps its steps, order, constants and log line. Run by tests/run_tests.sh from the repository root.
 */
public final class WatchChannelTest {
    private static int passed, failed;

    static final UUID SERVICE = UUID.fromString("00000020-5749-5448-0037-000000000000");
    static final UUID CHANNEL = UUID.fromString("00000024-5749-5448-0037-000000000000");

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
        check(name, want == null ? got == null : want.equals(got), "got " + got + ", want " + want);
    }

    public static void main(String[] args) throws Exception {
        layouts();
        bothRules();
        sources();
        System.out.println("ble WatchChannel: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static UUID std(String id) {
        return UUID.fromString("0000" + id + "-0000-1000-8000-00805f9b34fb");
    }

    static UUID w(String head, String part) {
        return UUID.fromString(head + "-5749-5448-" + part + "-000000000000");
    }

    /** A service with characteristics, added to the layout of {@code g}. */
    static BluetoothGattService add(BluetoothGatt g, UUID service, UUID... chars) {
        BluetoothGattService s = new BluetoothGattService(g.calls, service);
        for (UUID c : chars) s.characteristics.add(new BluetoothGattCharacteristic(g.calls, c));
        g.services.add(s);
        return s;
    }

    /** The owner's Steel HR: Generic Access, Generic Attribute and the Withings service 0037 with 0x21 to 0x24. */
    static BluetoothGatt steelHr(List<String> calls) {
        BluetoothGatt g = new BluetoothGatt(calls);
        add(g, std("1800"), std("2a00"), std("2a01"), std("2a04"));
        add(g, std("1801"), std("2a05"));
        add(g, SERVICE, w("00000021", "0037"), w("00000022", "0037"), w("00000023", "0037"), w("00000024", "0037"));
        return g;
    }

    static void layouts() {
        // the Steel HR: exactly the two look ups of before, the same channel object, nothing else called
        List<String> c = new ArrayList<String>();
        BluetoothGatt g = steelHr(c);
        BluetoothGattCharacteristic ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("Steel HR: the channel 0x24 of part 0037", ch == null ? null : ch.getUuid(), CHANNEL);
        check("Steel HR: the very characteristic object of its service", ch == g.services.get(2).characteristics.get(3), ch);
        eq("Steel HR: only the two look ups the sync always made, no list of services",
                c, Arrays.asList("getService " + SERVICE, "getCharacteristic " + CHANNEL + " in " + SERVICE));
        eq("Steel HR: its log line gets no part (it stays as it was)", WatchChannel.otherPart(ch), null);

        // 0037 wins even when another Withings part is listed before it
        c = new ArrayList<String>();
        g = new BluetoothGatt(c);
        add(g, w("00000020", "005f"), w("00000024", "005f"));
        add(g, SERVICE, CHANNEL);
        ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("0037 first: picked although 005f is listed before it", ch == null ? null : ch.getUuid(), CHANNEL);
        eq("0037 first: still only the two look ups", c.size(), 2);

        // a ScanWatch like layout with another part only
        c = new ArrayList<String>();
        g = new BluetoothGatt(c);
        add(g, std("1800"), std("2a00"));
        add(g, w("00000020", "005f"), w("00000021", "005f"), w("00000024", "005f"));
        ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("other part: the channel of part 005f", ch == null ? null : ch.getUuid(), w("00000024", "005f"));
        eq("other part: the log names it", WatchChannel.otherPart(ch), "005f");
        eq("other part: 0037 asked first, then the list, then the channel of the matching part",
                c, Arrays.asList("getService " + SERVICE, "getServices", "getCharacteristic " + w("00000024", "005f") + " in " + w("00000020", "005f")));

        // 0037 without its channel, another part with it
        g = new BluetoothGatt(new ArrayList<String>());
        add(g, SERVICE, w("00000021", "0037"));
        add(g, w("00000020", "00a1"), w("00000024", "00a1"));
        ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("0037 without 0x24: the next part that has it", ch == null ? null : ch.getUuid(), w("00000024", "00a1"));

        // the first of two other parts, in the listed order
        g = new BluetoothGatt(new ArrayList<String>());
        add(g, w("00000020", "0062"), w("00000024", "0062"));
        add(g, w("00000020", "005f"), w("00000024", "005f"));
        ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("two other parts: the first listed", ch == null ? null : ch.getUuid(), w("00000024", "0062"));

        // a channel of another part is never taken
        g = new BluetoothGatt(new ArrayList<String>());
        add(g, w("00000020", "005f"), w("00000024", "0062"));
        eq("mixed parts: no channel", WatchChannel.find(g, SERVICE, CHANNEL), null);

        // no Withings service at all, or nothing discovered
        g = new BluetoothGatt(new ArrayList<String>());
        add(g, std("1800"), std("2a00"));
        add(g, std("180f"), std("2a19"));
        add(g, UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"), UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"));
        eq("no Withings service: null (the sync says it did not show the Withings service)", WatchChannel.find(g, SERVICE, CHANNEL), null);
        eq("empty layout: null", WatchChannel.find(new BluetoothGatt(new ArrayList<String>()), SERVICE, CHANNEL), null);

        // Android gives UUIDs in lower case; the rule takes upper case too
        g = new BluetoothGatt(new ArrayList<String>());
        add(g, UUID.fromString("00000020-5749-5448-00AB-000000000000"), UUID.fromString("00000024-5749-5448-00ab-000000000000"));
        ch = WatchChannel.find(g, SERVICE, CHANNEL);
        eq("hex letters in the part", WatchChannel.otherPart(ch), "00ab");
        eq("no channel, no part", WatchChannel.otherPart(null), null);
    }

    /**
     * Review of 1e2e83a (Could improve): the sync's rule (this class) and the Device check's copy of it
     * ({@code core/WatchCheck.syncPart}) get the same layouts and must pick the same part. Each layout is a list of services,
     * each a service UUID followed by its characteristic UUIDs, in the order Android lists them.
     */
    static void bothRules() {
        String s37 = SERVICE.toString(), c37 = CHANNEL.toString();
        String s5f = w("00000020", "005f").toString(), c5f = w("00000024", "005f").toString();
        String s62 = w("00000020", "0062").toString(), c62 = w("00000024", "0062").toString();
        String[][][] cases = {
            {{std("1800").toString(), std("2a00").toString()}, {s37, w("00000021", "0037").toString(), c37}},
            {{s5f, c5f}, {s37, c37}},
            {{std("1800").toString()}, {s5f, w("00000021", "005f").toString(), c5f}},
            {{s37, w("00000021", "0037").toString()}, {s62, c62}, {s5f, c5f}},
            {{s5f, c62}},
            {{std("180f").toString(), std("2a19").toString()}},
            {},
            {{s62, w("00000021", "0062").toString()}, {s5f, c5f}, {s37, c37}},
            // upper case: Android gives lower case, the Device check takes either
            {{s5f.toUpperCase(Locale.ROOT), c5f.toUpperCase(Locale.ROOT)}},
            {{s37.toUpperCase(Locale.ROOT), c37.toUpperCase(Locale.ROOT)}, {s5f, c5f}},
        };
        String[] want = {"0037", "0037", "005f", "0062", null, null, null, "0037", "005f", "0037"};
        for (int i = 0; i < cases.length; i++) {
            BluetoothGatt g = new BluetoothGatt(new ArrayList<String>());
            WatchCheck.Report r = new WatchCheck.Report();
            r.connected = true;
            for (String[] sv : cases[i]) {
                BluetoothGattService gs = add(g, UUID.fromString(sv[0]));
                WatchCheck.Service rs = r.add(sv[0]);
                for (int k = 1; k < sv.length; k++) {
                    gs.characteristics.add(new BluetoothGattCharacteristic(g.calls, UUID.fromString(sv[k])));
                    rs.add(sv[k], 0x10);
                }
            }
            BluetoothGattCharacteristic ch = WatchChannel.find(g, SERVICE, CHANNEL);
            String sync = ch == null ? null : WatchUuid.channelPart(ch.getUuid().toString());
            eq("both rules, layout " + (i + 1) + ": the sync picks part " + want[i], sync, want[i]);
            eq("both rules, layout " + (i + 1) + ": the Device check names the same part", WatchCheck.syncPart(r), sync);
        }
        WatchCheck.Report up = new WatchCheck.Report();
        up.add("00000020-5749-5448-00AB-000000000000").add("00000024-5749-5448-00AB-000000000000", 0x1c);
        eq("Device check: hex letters in upper case give the lower case part", WatchCheck.syncPart(up), "00ab");
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    /** True when every piece is in {@code s}, each after the one before. */
    static boolean inOrder(String s, String... pieces) {
        int at = 0;
        for (String p : pieces) {
            int i = s.indexOf(p, at);
            if (i < 0) {
                System.out.println("       missing or out of order: " + p);
                return false;
            }
            at = i + p.length();
        }
        return true;
    }

    /** The watch sync's link, unchanged apart from the look up (pins it before and after issue #469). */
    static void sources() throws Exception {
        String ws = read("app/src/main/java/app/openring/ble/WatchSync.java");
        check("WatchSync: the Steel HR's service and channel stay the constants",
                ws.contains("SERVICE = UUID.fromString(\"00000020-5749-5448-0037-000000000000\")")
                        && ws.contains("CHANNEL = UUID.fromString(\"00000024-5749-5448-0037-000000000000\")"), "");
        check("WatchSync: MTU 119, connect 15 s, step 5 s", ws.contains("static final int MTU = 119;") && ws.contains("static final long CONNECT_MS = 15000, STEP_MS = 5000;"), "");
        int open = ws.indexOf("String open(Context ctx, BluetoothDevice device)");
        int send = ws.indexOf("public void send(byte[] msg)");
        check("WatchSync: open and send found", open > 0 && send > open, open + " " + send);
        String o = ws.substring(open, send);
        check("WatchSync open: the same steps in the same order",
                inOrder(o, "device.connectGatt(ctx.getApplicationContext(), false, cb, BluetoothDevice.TRANSPORT_LE)",
                        "conn.poll(CONNECT_MS, TimeUnit.MILLISECONDS)",
                        "gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)",
                        "SystemClock.sleep(300)",
                        "if (!gatt.discoverServices()) return NO_SERVICE;",
                        "found.poll(STEP_MS * 2, TimeUnit.MILLISECONDS)",
                        "d != null && d == BluetoothGatt.GATT_SUCCESS ? WatchChannel.find(gatt, SERVICE, CHANNEL) : null",
                        "ring.log(\"watch: \" + gatt.getServices().size() + \" services, Withings channel \" + (channel == null ? \"missing\" : \"found\")",
                        "(part == null ? \"\" : \" in service part \" + part)",
                        "if (channel == null) return NO_SERVICE;",
                        "listenOn = channel.getUuid();",
                        "if (gatt.requestMtu(MTU))",
                        "mtus.poll(STEP_MS, TimeUnit.MILLISECONDS)",
                        "gatt.setCharacteristicNotification(channel, true)",
                        "channel.getDescriptor(Protocol.CCCD)",
                        "Gatt.startDescriptorWrite(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)",
                        "acks.poll(STEP_MS, TimeUnit.MILLISECONDS)"), "");
        check("WatchSync open: no other look up of a service or characteristic", !o.contains("getService(") && !o.contains("getCharacteristic("), "");
        check("WatchSync: answers come from the channel found, the Steel HR's until then",
                ws.contains("private volatile UUID listenOn = CHANNEL;") && ws.contains("if (!listenOn.equals(c.getUuid()) || v == null) return;")
                        && !ws.contains("CHANNEL.equals(c.getUuid())"), "");
        check("WatchSync send: every piece to the channel found, as before", ws.contains("Gatt.startWrite(gatt, channel, piece)"), "");
    }
}
