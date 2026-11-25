package app.openring.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.openring.core.rings.RingFamily;
import app.openring.core.withings.WatchUuid;

/**
 * The Device check (issue #251, phase 1a, the Watch check; issue #418, phase 1, any ring or watch): what a device shows of
 * its Bluetooth layout, without pairing and without storing anything. A Withings watch is listened to as before; for any
 * other device ({@link RingFamily} names its family) the report holds only names, services and characteristics, no
 * messages, so it can never hold a health value. {@code ble/WatchLink} fills a {@link Report} from one short session, {@code ui/WatchActivity}
 * shows it, and this class turns it into the text the person copies. Demo mode shows {@link #demoFound} and
 * {@link #demoReport} through the same code.
 *
 * <p>The copied report must not carry health values: of each message it keeps the count and the lengths, and only of the
 * first {@link #TYPE_MESSAGES} messages per characteristic that are longer than {@link #TYPE_BYTES} bytes also their first
 * {@link #TYPE_BYTES} bytes (usually the kind of message). A message of {@link #TYPE_BYTES} bytes or less would be
 * all value, and from the second piece of a long answer on the first bytes are values too (review of #270), so the
 * report keeps no more than {@link #TYPE_MESSAGES} times {@link #TYPE_BYTES} bytes per characteristic. The full bytes
 * only go into the second, clearly marked copy. Standard characteristics (16 bit UUIDs, such as the standard heart rate) are not listened to: they are
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
    /** Messages per characteristic whose first bytes the default report keeps (only those longer than {@link #TYPE_BYTES}). */
    public static final int TYPE_MESSAGES = 3;
    /** Messages kept per characteristic, so a chatty watch cannot fill the memory in 10 s. */
    public static final int KEPT_MESSAGES = 300;
    /** The longest report copied: far below what Android's clipboard takes in one go. */
    public static final int REPORT_MAX = 30000;
    /** The last line of a report that was cut at {@link #REPORT_MAX}. */
    public static final String CUT = "(cut here, the report was longer)";

    /** The service UUID the public notes give for the Steel HR (unconfirmed): "WITH" in ASCII in its middle. */
    public static final String WITHINGS_SERVICE = "00000020-5749-5448-0037-000000000000";

    /** The screen's title and the Settings row that opens it (issue #418). */
    public static final String TITLE = "Device check";
    public static final String ROW = "Check a ring or watch";
    public static final String INTRO = "Open Ring can look at a ring or watch to learn how it talks. This check only looks and listens: it does not pair, "
            + "changes nothing on the device and stores nothing.";
    public static final String SIGNAL = "Signal: closer to 0 means nearer. Your device is usually the strongest.";
    /** Said under the report when the phone was not paired with the watch before the check and is now (review of #270). */
    public static final String PAIRED_NOW = "The phone is now paired with this watch. To undo it, open Android's Bluetooth settings, tap the watch and choose Forget.";

    /** {@link #PAIRED_NOW} for a device that is not a Withings watch (review of #430). */
    public static final String PAIRED_NOW_DEVICE = "The phone is now paired with this device. To undo it, open Android's Bluetooth settings, tap the device and choose Forget.";

    /** The sentence for a bond the check left: the watch's or the device's. */
    public static String pairedNow(Report r) {
        return other(r) ? PAIRED_NOW_DEVICE : PAIRED_NOW;
    }

    /** True when the check left a bond that was not there before, so the screen tells how to remove it. */
    public static boolean pairedDuring(Report r) {
        return r != null && r.bondedAfter && !r.bondedBefore;
    }

    public static final String PAIR_NOTE = "If Android asks to pair, tap Cancel: the check works without pairing.";
    public static final String REPORT_NOTE = "The report holds the watch's model, firmware and Bluetooth layout. Of each message it keeps only "
            + "the length. Of the first 3 messages it also keeps the first 2 bytes (only for messages longer than that), which usually "
            + "name the kind of message, not its values.";
    public static final String PRIVATE = "Private: may hold health values. Send it only to someone you trust, never in a public issue.";
    public static final String SEND_BACK = "What to send back: tap Copy the report and paste it as a comment on issue 251 on GitHub, with the "
            + "model name on the back of the watch, the firmware shown in the Withings app and the battery % on 3 mornings.";
    public static final String NO_ANSWER = "The watch did not answer. It may be busy with the Withings app; close that app and try again.";
    /** {@link #NO_ANSWER} for a device that is not a Withings watch. */
    public static final String NO_ANSWER_DEVICE = "The device did not answer. It may be busy with its own app; close that app and try again.";
    public static final String BUSY = "The ring is busy right now. Try again in a minute.";
    public static final String DEMO = "Demo mode: this is a made up example. Turn demo mode off to check a real ring or watch.";
    /** The status line of the ring while the check holds it (review of #270), so a sync by hand meanwhile shows why it waits. */
    public static final String RUNNING = "Device check running, the ring is free again within 30 seconds";

    /** Why a check ended early, in plain words for the screen; the status code or error goes to {@link Report#detail}. */
    public static final String ENDED_STOPPED = "The check was stopped because the screen was left.";
    public static final String ENDED_REFUSED = "Bluetooth did not open a connection. Switch Bluetooth off and on and try again.";
    public static final String ENDED_LAYOUT = "The watch did not show its layout.";
    public static final String ENDED_BY_WATCH = "The watch ended the connection.";
    public static final String ENDED_BLUETOOTH = "Bluetooth was switched off or the permission was taken back.";

    /** The line above the report on the screen: plain words only, the details stay in the report. */
    public static String outcome(Report r) {
        boolean watch = !other(r);
        if (r.family == RingFamily.OURA && !r.connected && r.problem == null) return OURA_LEFT;
        if (!r.connected) return r.problem != null ? r.problem : watch ? NO_ANSWER : NO_ANSWER_DEVICE;
        if (r.problem != null) return "The check ended early. " + r.problem + " What it found so far is below.";
        return watch ? "Done. The watch was let go again." : "Done. The device was let go again.";
    }

    public static final String NOTHING_FOUND = "Nothing found nearby. Wake the watch (press its button) or close the ring's own app, keep the "
            + "device next to the phone and scan again.";

    /** The report note for a device that was not listened to (every one but a Withings watch, issue #418). */
    public static final String DEVICE_NOTE = "When the device answered, the report holds its name, maker, battery level and Bluetooth layout. "
            + "The check did not listen to its messages, so the report holds no health values.";

    /** True for a report of a device that is not a Withings watch (its family is known and not Withings). */
    static boolean other(Report r) {
        return r.family != null && r.family != RingFamily.WITHINGS;
    }

    /**
     * False for the person's own Oura ring (review of #430): the check never connects to it, so there is no report to show
     * or copy, only {@link #OURA_LEFT} and the family's line ({@link RingFamily#OURA_LINE}).
     */
    public static boolean showsReport(Report r) {
        return r != null && r.family != RingFamily.OURA;
    }

    /** The note under the report: what it holds. */
    public static String reportNote(Report r) {
        return r.listen ? REPORT_NOTE : DEVICE_NOTE;
    }

    /** What to do with the report: the watch's lines of issue #251, or the family's one line (issue #418). */
    public static String sendBack(Report r) {
        return other(r) ? r.family.line() : SEND_BACK;
    }

    /**
     * Whether the session switches notifications on and listens: only for a Withings watch, known from the scan or from
     * the Withings service in its layout. Any other device is only looked at (names, services, characteristics), so its
     * report can never hold a health value and nothing is written to it.
     */
    public static boolean listens(RingFamily scanFamily, Report r) {
        boolean withings = scanFamily == RingFamily.WITHINGS;
        for (Service s : r.services) {
            RingFamily f = RingFamily.byService(s.uuid);
            // a service of another family (a ring with a Withings word in its name, review of #430): never listened to
            if (f != null && f != RingFamily.WITHINGS) return false;
            if (f == RingFamily.WITHINGS) withings = true;
        }
        return withings;
    }

    /**
     * The family of a scanned device, from its name, advertised services and maker data: Oura's company id
     * ({@link Protocol#COMPANY_ID}) counts as the Oura service, as {@code ble/RingScanner.isOura} counts it.
     */
    public static RingFamily family(Found f) {
        List<String> s = f.services;
        if (f.ouraMaker) {
            s = new ArrayList<String>(s);
            s.add(RingFamily.OURA_SERVICE);
        }
        return RingFamily.of(f.name, s, null);
    }

    /** Said instead of an outcome for the Oura ring: the check does not connect to it (review of #430). */
    public static final String OURA_LEFT = "Not connected: Open Ring already reads this ring, so the check leaves it alone and a sync is never disturbed.";

    /** The report of the Oura ring, from the scan alone: the check never connects to it, so a sync is never disturbed. */
    public static Report ouraReport(Found f) {
        Report r = new Report();
        r.name = title(f);
        r.rssi = f.rssi;
        r.listen = false;
        r.family = RingFamily.OURA;
        return r;
    }

    /**
     * Names the family of a finished report from everything known: the scan's name and services, the services of the
     * layout and the maker read from Device Information. A report that was listened to is a Withings watch's.
     */
    public static void settle(Found f, Report r) {
        List<String> all = new ArrayList<String>(f.services);
        for (Service s : r.services) if (!all.contains(s.uuid)) all.add(s.uuid);
        r.family = r.listen ? RingFamily.WITHINGS : RingFamily.of(f.name, all, r.manufacturer);
    }

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
        return RingFamily.withingsService(uuid);
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
        /** True when the advertisement carries maker data under Oura's company id ({@link Protocol#COMPANY_ID}). */
        public boolean ouraMaker;

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

    /**
     * The order while a scan runs (review of #270): rows already shown keep their place, so a row does not move under the
     * finger as the signal changes; devices heard for the first time come after them, strongest first. Once the scan
     * ends the screen sorts the whole list again with {@link #strongestFirst}.
     */
    public static List<Found> stableOrder(List<String> shown, List<Found> in) {
        java.util.Map<String, Found> by = new java.util.LinkedHashMap<String, Found>();
        for (Found f : strongestFirst(in)) by.put(f.address, f);
        List<Found> out = new ArrayList<Found>();
        for (String a : shown) {
            Found f = by.remove(a);
            if (f != null) out.add(f);
        }
        out.addAll(by.values());
        return out;
    }

    /** The name shown for a device: its advertised name or "(no name)". */
    public static String title(Found f) {
        return f.name == null || f.name.trim().isEmpty() ? "(no name)" : f.name.trim();
    }

    /**
     * The devices worth listing: those with a name, and any of a known family (a Withings watch, a ring) even without
     * one. Unnamed devices (headphones, tags, other phones) only fill the list (owner, 3 Nov).
     */
    public static List<Found> shown(List<Found> all) {
        List<Found> out = new ArrayList<Found>();
        for (Found f : all) if (family(f) != RingFamily.UNKNOWN || f.name != null && !f.name.trim().isEmpty()) out.add(f);
        return out;
    }

    /**
     * Watch setup's name rule: the Withings family's name rule, {@link RingFamily#withingsName} (issue #418: one rule for
     * Setup and the check, withings, steel, activit, scanwatch or hwa0 anywhere in the name, in any case). Setup adds the
     * exact Withings service on its own.
     */
    public static boolean setupName(String name) {
        return RingFamily.withingsName(name);
    }

    /** True when the name or the advertised services suggest a Withings watch: the Withings family of {@link RingFamily}. */
    public static boolean likelyWatch(Found f) {
        return family(f) == RingFamily.WITHINGS;
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
        String line = family(f).rowLine(); // "Looks like a Withings watch", "Looks like a ring of the QRing family (...)"
        if (line != null) sb.append('\n').append(line);
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

        /** The messages and the count of dropped ones, read together (written on a Bluetooth thread). */
        synchronized Heard copy() {
            return new Heard(new ArrayList<byte[]>(messages), dropped);
        }
    }

    /** What one characteristic sent, as one consistent copy. */
    static final class Heard {
        final List<byte[]> messages;
        final int dropped;

        Heard(List<byte[]> messages, int dropped) {
            this.messages = messages;
            this.dropped = dropped;
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
        /** Read again when the session ends (review of #270): true when the phone holds a bond with the watch now. */
        public boolean bondedAfter;
        /** Standard Device Information and Battery, null when the watch has none or did not answer. */
        public String model, firmware, hardware, software, manufacturer;
        public int battery = -1;
        public long connectMs = -1, listenMs = -1;
        /** Why the session ended early, one of the plain ENDED_ sentences, or null. */
        public String problem;
        /** A status code or error name for the report only (never on the screen), or null. */
        public String detail;
        public final List<Service> services = new ArrayList<Service>();
        /** The family the screen names (issue #418), set by {@link #settle}; null in a report made before that. */
        public RingFamily family;
        /**
         * Whether the session listened ({@link #listens}): true for a Withings watch. False keeps every message out of the
         * report, so a ring's report holds names, services and characteristics only.
         */
        public boolean listen = true;

        public Service add(String uuid) {
            Service s = new Service(uuid);
            services.add(s);
            return s;
        }
    }

    /** The copied report: {@code bytes} false keeps only lengths and the first {@link #TYPE_BYTES} bytes of each message. */
    public static String text(Report r, boolean bytes) {
        StringBuilder sb = new StringBuilder();
        boolean bytesShown = bytes && r.listen;
        sb.append(other(r) ? "Open Ring device check" : "Open Ring watch check").append(bytesShown ? " (with message bytes, private)" : "").append('\n');
        sb.append("Name: ").append(r.name == null || r.name.isEmpty() ? "(no name)" : r.name).append('\n');
        if (other(r)) sb.append("Looks like: ").append(r.family.title).append('\n');
        if (r.rssi != NO_RSSI) sb.append("Signal: ").append(r.rssi).append(" dBm\n");
        sb.append("Model: ").append(or(r.model)).append('\n');
        sb.append("Firmware: ").append(or(r.firmware)).append('\n');
        if (r.hardware != null) sb.append("Hardware: ").append(r.hardware).append('\n');
        if (r.software != null) sb.append("Software: ").append(r.software).append('\n');
        if (r.manufacturer != null) sb.append("Maker: ").append(r.manufacturer).append('\n');
        sb.append("Battery: ").append(r.battery < 0 ? "not shown" : r.battery + "%").append('\n');
        sb.append("Paired with this phone before: ").append(r.bondedBefore ? "yes" : "no").append('\n');
        sb.append("Paired now: ").append(r.bondedAfter ? "yes" : "no").append('\n');
        if (!r.connected) sb.append("Connected: no\n");
        else if (!r.listen) sb.append("Connected in ").append(seconds(r.connectMs)).append(", messages not listened to (no health values)\n");
        else sb.append("Connected in ").append(seconds(r.connectMs)).append(", listened ").append(seconds(r.listenMs)).append('\n');
        if (r.connected && r.listen && !r.services.isEmpty()) sb.append(syncChannelLine(r)).append('\n');
        if (r.problem != null) sb.append("Ended early: ").append(r.problem).append('\n');
        if (r.detail != null) sb.append("Detail: ").append(r.detail).append('\n');
        for (Service s : r.services) {
            sb.append('\n').append("Service ").append(name(s.uuid)).append('\n');
            for (Characteristic c : s.characteristics) {
                // only the service is marked as Withings like; its characteristics keep their plain UUID
                sb.append("  ").append(standard(c.uuid) ? name(c.uuid) : c.uuid).append(": ").append(properties(c.props));
                if (!r.listen) {
                    // not a Withings watch: never a message in its report, whatever a characteristic holds
                    if (sends(c.props)) sb.append(" (not listened to)");
                    sb.append('\n');
                    continue;
                }
                if (c.listening != null) sb.append(c.listening ? " (listened)" : " (did not switch on)");
                else if (sends(c.props) && standard(c.uuid)) sb.append(" (standard, not listened to)");
                sb.append('\n');
                if (c.listening == null || !c.listening) continue;
                Heard h = c.copy();
                List<byte[]> m = h.messages;
                int n = m.size() + h.dropped;
                sb.append("    ").append(Texts.count(n, "message", "messages"));
                if (h.dropped > 0) sb.append(" (the first ").append(m.size()).append(" kept)");
                if (!m.isEmpty()) {
                    sb.append(", lengths ");
                    for (int i = 0; i < m.size(); i++) sb.append(i == 0 ? "" : " ").append(m.get(i).length);
                    if (bytes) {
                        sb.append("\n    bytes ");
                        for (int i = 0; i < m.size(); i++) sb.append(i == 0 ? "" : " ").append(Hex.encode(m.get(i), 0, m.get(i).length));
                    } else {
                        sb.append(firstBytes(m));
                    }
                }
                sb.append('\n');
            }
        }
        if (r.services.isEmpty() && r.connected) sb.append("\nNo services found\n");
        return cut(sb.toString());
    }

    /**
     * The part of the Withings service whose channel a sync would use, by the sync's own rule ({@code ble/WatchChannel},
     * issue #469): the Steel HR's part {@link WatchUuid#STEEL_HR} when its service holds its channel, else the first service
     * in the listed order whose part matches a channel in it; null when there is none.
     */
    public static String syncPart(Report r) {
        String steel = WatchUuid.service(WatchUuid.STEEL_HR).toString();
        for (Service s : r.services) if (s.uuid.equals(steel) && holds(s, WatchUuid.STEEL_HR)) return WatchUuid.STEEL_HR;
        for (Service s : r.services) {
            String part = WatchUuid.servicePart(s.uuid);
            if (part != null && holds(s, part)) return part;
        }
        return null;
    }

    private static boolean holds(Service s, String part) {
        String ch = WatchUuid.channel(part).toString();
        for (Characteristic c : s.characteristics) if (c.uuid.equals(ch)) return true;
        return false;
    }

    /** The report's line about the sync channel (issue #469), so one report from a new watch says whether a sync can start. */
    static String syncChannelLine(Report r) {
        String part = syncPart(r);
        return part == null ? "Withings sync channel: not found, a sync would not start" : "Withings sync channel: found in service part " + part;
    }

    /**
     * "\n    first bytes: message 1 0105, message 3 0203": the first {@link #TYPE_BYTES} bytes of those of the first
     * {@link #TYPE_MESSAGES} messages that are longer than that, numbered as they arrived; empty when there are none.
     */
    static String firstBytes(List<byte[]> m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(TYPE_MESSAGES, m.size()); i++) {
            byte[] v = m.get(i);
            if (v.length <= TYPE_BYTES) continue;
            sb.append(sb.length() == 0 ? "\n    first bytes: " : ", ").append("message ").append(i + 1).append(' ').append(Hex.encode(v, 0, TYPE_BYTES));
        }
        return sb.toString();
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

    /** The address of the made up QRing ring in {@link #demoFound} (issue #418). */
    public static final String DEMO_RING = "00:00:00:00:00:04";

    /** The made up scan of demo mode: a watch, headphones, a device without a name and a QRing ring (issue #418). */
    public static List<Found> demoFound() {
        List<Found> l = new ArrayList<Found>();
        l.add(new Found("00:00:00:00:00:01", "Headphones (example)", -71).service("0000110b" + BASE));
        l.add(new Found("00:00:00:00:00:02", "Steel HR (example)", -52).service(WITHINGS_SERVICE));
        l.add(new Found("00:00:00:00:00:03", null, -88));
        l.add(new Found(DEMO_RING, "R09_ABCD (example)", -63).service(RingFamily.QRING_SERVICE));
        return l;
    }

    /** The made up report of demo mode for the device {@code f}: the watch's, the ring's, or one of its advertised layout only. */
    public static Report demoReport(Found f) {
        Report r;
        if (likelyWatch(f)) r = demoReport();
        else if (DEMO_RING.equals(f.address)) r = demoRingReport();
        else {
            r = new Report();
            r.name = title(f);
            r.rssi = f.rssi;
            r.connected = true;
            r.listen = false;
            r.connectMs = 1100;
            for (String s : f.services) r.add(s);
        }
        settle(f, r);
        return r;
    }

    /** The made up report of the demo's QRing ring: looked at, never listened to (issue #418); every value is made up. */
    public static Report demoRingReport() {
        Report r = new Report();
        r.name = "R09_ABCD (example)";
        r.rssi = -63;
        r.connected = true;
        r.listen = false;
        r.battery = 70;
        r.connectMs = 900;
        r.add("00001800" + BASE).add("00002a00" + BASE, 0x02);
        r.add("0000180f" + BASE).add("00002a19" + BASE, 0x12);
        Service q = r.add(RingFamily.QRING_SERVICE);
        q.add("6e400002-b5a3-f393-e0a9-e50e24dcca9e", 0x0c);
        q.add("6e400003-b5a3-f393-e0a9-e50e24dcca9e", 0x10);
        Service big = r.add(RingFamily.QRING_BIG);
        big.add("de5bf72a-d711-4e47-af26-65e3012a5dc7", 0x0c);
        big.add("de5bf729-d711-4e47-af26-65e3012a5dc7", 0x10);
        return r;
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
