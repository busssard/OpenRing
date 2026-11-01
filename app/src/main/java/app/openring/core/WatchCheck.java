package app.openring.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Watch check (issue #251, phase 1a): what a Withings watch shows of its Bluetooth layout, without pairing and
 * without storing anything. {@code ble/WatchLink} fills a {@link Report} from one short session, {@code ui/WatchActivity}
 * shows it, and this class turns it into the text the person copies. Demo mode shows {@link #demoFound} and
 * {@link #demoReport} through the same code.
 *
 * <p>The copied report must not carry health values: of each message it keeps only the count, the lengths and the
 * first {@link #TYPE_BYTES} bytes (usually the kind of message). The full bytes only go into the second, clearly marked
 * copy. Standard characteristics (16 bit UUIDs, such as the standard heart rate) are not listened to: they are
 * documented, and the heart rate one would switch on the sensor and send values.
 */
public final class WatchCheck {
    private WatchCheck() {}

    /** The whole session at most: connect, layout, reads, listening (Design round decisions). */
    public static final long SESSION_MS = 30000;
    /** Longest wait for the watch to accept the connection. */
    public static final long CONNECT_MS = 10000;
    /** How long the check listens for messages once notifications are on. */
    public static final long LISTEN_MS = 10000;
    /** How long a scan for watches runs (one scan start of Android's five per 30 s). */
    public static final long SCAN_MS = 10000;

    /** Bytes of each message the default report keeps. */
    public static final int TYPE_BYTES = 2;
    /** Messages kept per characteristic, so a chatty watch cannot fill the memory in 10 s. */
    public static final int KEPT_MESSAGES = 300;
    /** The longest report copied: far below what Android's clipboard takes in one go. */
    public static final int REPORT_MAX = 30000;
    /** The last line of a report that was cut at {@link #REPORT_MAX}. */
    public static final String CUT = "(cut here, the report was longer)";

    /** The service UUID the public notes give for the Steel HR (unconfirmed): "WITH" in ASCII in its middle. */
    public static final String WITHINGS_SERVICE = "00000020-5749-5448-0037-000000000000";

    public static final String INTRO = "Open Ring can look at a Withings watch to learn how it talks. This check only listens: it does not pair, "
            + "changes nothing on the watch and stores nothing.";
    public static final String SIGNAL = "Signal: closer to 0 means nearer. Your watch is usually the strongest.";
    public static final String PAIR_NOTE = "If Android asks to pair, tap Cancel: the check works without pairing.";
    public static final String REPORT_NOTE = "The report holds the watch's model, firmware and Bluetooth layout. Of each message it keeps only "
            + "the length and the first 2 bytes, which usually name the kind of message, not its values.";
    public static final String PRIVATE = "Private: may hold health values. Send it only to someone you trust, never in a public issue.";
    public static final String SEND_BACK = "What to send back: tap Copy the report and send it to the developer, with the model name on the "
            + "back of the watch and the firmware shown in the Withings app.";
    public static final String NO_ANSWER = "The watch did not answer. It may be busy with the Withings app; close that app and try again.";
    public static final String BUSY = "The ring is busy right now. Try again in a minute.";
    public static final String DEMO = "Demo mode: this is a made up example. Turn demo mode off to check a real watch.";
    public static final String NOTHING_FOUND = "Nothing found nearby. Wake the watch (press its button), keep it next to the phone and scan again.";

    // ---------------------------------------------------------------- names

    /** Android's characteristic property bits, in the order the report names them. */
    static final int[] PROP_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    static final String[] PROP_NAMES = {"broadcast", "read", "write without answer", "write", "notify", "indicate", "signed write", "extended"};

    /** "read, notify": the names of the property bits, "none" when there are none. */
    public static String properties(int props) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROP_BITS.length; i++) {
            if ((props & PROP_BITS[i]) == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(PROP_NAMES[i]);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** True when a characteristic sends messages by itself (notify or indicate). */
    public static boolean sends(int props) {
        return (props & 0x30) != 0;
    }

    private static final String BASE = "-0000-1000-8000-00805f9b34fb";
    private static final Map<String, String> NAMES = new HashMap<String, String>();

    static {
        String[] n = {
            "1800", "Generic Access", "1801", "Generic Attribute", "180a", "Device Information", "180f", "Battery", "180d", "Heart Rate",
            "1805", "Current Time", "1802", "Immediate Alert", "1803", "Link Loss", "1804", "Tx Power", "1812", "Human Interface Device",
            "2a00", "Device Name", "2a01", "Appearance", "2a04", "Connection Parameters", "2a05", "Service Changed", "2a19", "Battery Level",
            "2a23", "System ID", "2a24", "Model Number", "2a25", "Serial Number", "2a26", "Firmware Revision", "2a27", "Hardware Revision",
            "2a28", "Software Revision", "2a29", "Manufacturer Name", "2a50", "PnP ID", "2a37", "Heart Rate Measurement",
            "2a38", "Body Sensor Location", "2a2b", "Current Time", "2aa6", "Central Address Resolution", "2b29", "Client Supported Features",
            "2b2a", "Database Hash", "2b3a", "Server Supported Features",
        };
        for (int i = 0; i < n.length; i += 2) NAMES.put(n[i], n[i + 1]);
    }

    /** The 4 hex digits of a standard (Bluetooth base) UUID, or null for any other UUID. */
    public static String shortId(String uuid) {
        if (uuid == null) return null;
        String u = uuid.toLowerCase(Locale.ROOT);
        if (u.length() != 36 || !u.startsWith("0000") || !u.endsWith(BASE)) return null;
        return u.substring(4, 8);
    }

    /** A standard UUID that the Bluetooth group defined (16 bit): documented, so never listened to. */
    public static boolean standard(String uuid) {
        return shortId(uuid) != null;
    }

    /** True for a UUID with "WITH" in ASCII where the public notes put it (5749-5448). */
    public static boolean withings(String uuid) {
        return uuid != null && uuid.toLowerCase(Locale.ROOT).contains("-5749-5448-");
    }

    /**
     * How the report names a UUID: "0x180a Device Information" for a known standard one, "0x1234" for another
     * standard one, the full UUID otherwise (unknown ones are kept exactly as they are), marked when it looks like
     * Withings.
     */
    public static String name(String uuid) {
        if (uuid == null) return "?";
        String s = shortId(uuid);
        if (s != null) {
            String n = NAMES.get(s);
            return "0x" + s + (n == null ? "" : " " + n);
        }
        String u = uuid.toLowerCase(Locale.ROOT);
        return withings(u) ? u + " (looks like Withings)" : u;
    }

    // ---------------------------------------------------------------- scan

    /** One device the scan heard. */
    public static final class Found {
        public final String address;
        public String name;
        public int rssi;
        public final List<String> services = new ArrayList<String>();

        public Found(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }

        public Found service(String uuid) {
            if (uuid != null && !services.contains(uuid.toLowerCase(Locale.ROOT))) services.add(uuid.toLowerCase(Locale.ROOT));
            return this;
        }
    }

    /** Android's value for "no signal strength known". */
    public static final int NO_RSSI = 127;

    /** The list in the order the screen shows it: strongest signal first, unknown signal last, then by name. */
    public static List<Found> strongestFirst(List<Found> in) {
        List<Found> out = new ArrayList<Found>(in);
        Collections.sort(out, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                int ra = a.rssi == NO_RSSI ? Integer.MIN_VALUE : a.rssi, rb = b.rssi == NO_RSSI ? Integer.MIN_VALUE : b.rssi;
                if (ra != rb) return ra > rb ? -1 : 1;
                return title(a).compareTo(title(b));
            }
        });
        return out;
    }

    /** The name shown for a device: its advertised name or "(no name)". */
    public static String title(Found f) {
        return f.name == null || f.name.trim().isEmpty() ? "(no name)" : f.name.trim();
    }

    /** True when the name or the advertised services suggest a Withings watch. */
    public static boolean likelyWatch(Found f) {
        String n = f.name == null ? "" : f.name.toLowerCase(Locale.ROOT);
        if (n.contains("withings") || n.contains("steel") || n.contains("hwa0") || n.contains("activit")) return true;
        for (String s : f.services) if (withings(s)) return true;
        return false;
    }

    /** "Signal -58 dBm" and the advertised services, the second line of a row in the scan list. */
    public static String detail(Found f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.rssi == NO_RSSI ? "Signal unknown" : "Signal " + f.rssi + " dBm");
        if (f.services.isEmpty()) sb.append(", no services advertised");
        else {
            sb.append(", services: ");
            for (int i = 0; i < f.services.size(); i++) sb.append(i == 0 ? "" : ", ").append(name(f.services.get(i)));
        }
        if (likelyWatch(f)) sb.append("\nLooks like a Withings watch");
        return sb.toString();
    }

    // ---------------------------------------------------------------- layout and report

    /** One characteristic: its properties, what was read from it and the messages it sent while the check listened. */
    public static final class Characteristic {
        public final String uuid;
        public final int props;
        /** Whether notifications were switched on: null when not tried (standard, or it does not send). */
        public Boolean listening;
        /** Messages in arrival order, at most {@link #KEPT_MESSAGES}; {@link #dropped} counts the rest. */
        public final List<byte[]> messages = new ArrayList<byte[]>();
        public int dropped;

        public Characteristic(String uuid, int props) {
            this.uuid = uuid.toLowerCase(Locale.ROOT);
            this.props = props;
        }

        /** Keeps a copy of one message (called on a Bluetooth thread). */
        public synchronized void message(byte[] v) {
            if (v == null) return;
            if (messages.size() < KEPT_MESSAGES) messages.add(v.clone());
            else dropped++;
        }

        synchronized List<byte[]> copy() {
            return new ArrayList<byte[]>(messages);
        }
    }

    public static final class Service {
        public final String uuid;
        public final List<Characteristic> characteristics = new ArrayList<Characteristic>();

        public Service(String uuid) {
            this.uuid = uuid.toLowerCase(Locale.ROOT);
        }

        public Characteristic add(String uuid, int props) {
            Characteristic c = new Characteristic(uuid, props);
            characteristics.add(c);
            return c;
        }
    }

    /** What one check found; it lives in memory only and is gone when the screen closes. */
    public static final class Report {
        public String name;
        public int rssi = NO_RSSI;
        public boolean connected;
        public boolean bondedBefore;
        /** Standard Device Information and Battery, null when the watch has none or did not answer. */
        public String model, firmware, hardware, software, manufacturer;
        public int battery = -1;
        public long connectMs = -1, listenMs = -1;
        /** Why the session ended early, in plain words, or null. */
        public String problem;
        public final List<Service> services = new ArrayList<Service>();

        public Service add(String uuid) {
            Service s = new Service(uuid);
            services.add(s);
            return s;
        }
    }

    /** The copied report: {@code bytes} false keeps only lengths and the first {@link #TYPE_BYTES} bytes of each message. */
    public static String text(Report r, boolean bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Open Ring watch check").append(bytes ? " (with message bytes, private)" : "").append('\n');
        sb.append("Name: ").append(r.name == null || r.name.isEmpty() ? "(no name)" : r.name).append('\n');
        if (r.rssi != NO_RSSI) sb.append("Signal: ").append(r.rssi).append(" dBm\n");
        sb.append("Model: ").append(or(r.model)).append('\n');
        sb.append("Firmware: ").append(or(r.firmware)).append('\n');
        if (r.hardware != null) sb.append("Hardware: ").append(r.hardware).append('\n');
        if (r.software != null) sb.append("Software: ").append(r.software).append('\n');
        if (r.manufacturer != null) sb.append("Maker: ").append(r.manufacturer).append('\n');
        sb.append("Battery: ").append(r.battery < 0 ? "not shown" : r.battery + "%").append('\n');
        sb.append("Paired with this phone before: ").append(r.bondedBefore ? "yes" : "no").append('\n');
        if (!r.connected) sb.append("Connected: no\n");
        else sb.append("Connected in ").append(seconds(r.connectMs)).append(", listened ").append(seconds(r.listenMs)).append('\n');
        if (r.problem != null) sb.append("Ended early: ").append(r.problem).append('\n');
        for (Service s : r.services) {
            sb.append('\n').append("Service ").append(name(s.uuid)).append('\n');
            for (Characteristic c : s.characteristics) {
                // only the service is marked as Withings like; its characteristics keep their plain UUID
                sb.append("  ").append(standard(c.uuid) ? name(c.uuid) : c.uuid).append(": ").append(properties(c.props));
                if (c.listening != null) sb.append(c.listening ? " (listened)" : " (did not switch on)");
                else if (sends(c.props) && standard(c.uuid)) sb.append(" (standard, not listened to)");
                sb.append('\n');
                if (c.listening == null || !c.listening) continue;
                List<byte[]> m = c.copy();
                int n = m.size() + c.dropped;
                sb.append("    ").append(Texts.count(n, "message", "messages"));
                if (c.dropped > 0) sb.append(" (the first ").append(m.size()).append(" kept)");
                if (!m.isEmpty()) {
                    sb.append(", lengths ");
                    for (int i = 0; i < m.size(); i++) sb.append(i == 0 ? "" : " ").append(m.get(i).length);
                    sb.append(bytes ? "\n    bytes " : "\n    first bytes ");
                    for (int i = 0; i < m.size(); i++) {
                        byte[] v = m.get(i);
                        sb.append(i == 0 ? "" : " ").append(Hex.encode(v, 0, bytes ? v.length : Math.min(TYPE_BYTES, v.length)));
                    }
                }
                sb.append('\n');
            }
        }
        if (r.services.isEmpty() && r.connected) sb.append("\nNo services found\n");
        return cut(sb.toString());
    }

    /** {@code s} itself, or its whole lines up to {@link #REPORT_MAX} characters with {@link #CUT} as the last line. */
    public static String cut(String s) {
        if (s.length() <= REPORT_MAX) return s;
        int room = REPORT_MAX - CUT.length() - 1;
        int end = s.lastIndexOf('\n', room - 1);
        if (end < 0) end = room;
        return s.substring(0, end) + "\n" + CUT;
    }

    private static String or(String s) {
        return s == null || s.isEmpty() ? "not shown" : s;
    }

    private static String seconds(long ms) {
        return ms < 0 ? "?" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    // ---------------------------------------------------------------- demo

    /** The made up scan of demo mode: a watch, headphones and a device without a name. */
    public static List<Found> demoFound() {
        List<Found> l = new ArrayList<Found>();
        l.add(new Found("00:00:00:00:00:01", "Headphones (example)", -71).service("0000110b" + BASE));
        l.add(new Found("00:00:00:00:00:02", "Steel HR (example)", -52).service(WITHINGS_SERVICE));
        l.add(new Found("00:00:00:00:00:03", null, -88));
        return l;
    }

    /** The made up report of demo mode, built like a real one (the first message bytes are made up too). */
    public static Report demoReport() {
        Report r = new Report();
        r.name = "Steel HR (example)";
        r.rssi = -52;
        r.connected = true;
        r.model = "Example model";
        r.firmware = "0000";
        r.battery = 80;
        r.connectMs = 1400;
        r.listenMs = LISTEN_MS;
        r.add("00001800" + BASE).add("00002a00" + BASE, 0x02);
        Service info = r.add("0000180a" + BASE);
        info.add("00002a24" + BASE, 0x02);
        info.add("00002a26" + BASE, 0x02);
        r.add("0000180f" + BASE).add("00002a19" + BASE, 0x12);
        Service w = r.add(WITHINGS_SERVICE);
        w.add("00000021-5749-5448-0037-000000000000", 0x0c);
        Characteristic n = w.add("00000024-5749-5448-0037-000000000000", 0x10);
        n.listening = true;
        n.message(new byte[] {0x01, 0x01, 0x00, 0x00, 0x00});
        n.message(new byte[] {0x01, 0x05, 0x00, 0x02, 0x00, 0x00});
        return r;
    }
}
