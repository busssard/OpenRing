import app.openring.core.WatchCheck;
import app.openring.core.rings.Forbidden;
import app.openring.core.rings.RingDriver;
import app.openring.core.rings.RingFamily;

import java.util.*;

/**
 * Other rings (issue #418, phase 1, {@code core/rings}): each family recognised from made up advertisements, unknown
 * devices stay unknown, a Withings watch is found as before (the old rules of the check and of Setup as reference), the
 * Device check's lines and report for a ring (never a message, so never a health value), the demo's made up ring,
 * {@link Forbidden} and the {@link RingDriver} contract on a tiny fake driver. Made up names and addresses only. Run with
 * tests/run_tests.sh.
 */
public class RingsTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) System.out.println("  " + detail);
        check(name, ok);
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    static final String BASE = "-0000-1000-8000-00805f9b34fb";

    public static void main(String[] args) {
        families();
        withingsAsBefore();
        lines();
        checkScreen();
        demo();
        forbidden();
        driver();
        System.out.println("rings: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static List<String> s(String... u) {
        return Arrays.asList(u);
    }

    static void families() {
        Object[][] t = {
            // name, services, maker, family
            {"R02_341C", null, null, RingFamily.QRING},
            {"R09_ABCD", s(), null, RingFamily.QRING},
            {null, s("6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E"), null, RingFamily.QRING},
            {"Smart ring", s("de5bf728-d711-4e47-af26-65e3012a5dc7"), null, RingFamily.QRING},
            {null, s("8327ad99-2d87-4a22-a8ce-6dd7971c0437"), null, RingFamily.RINGCONN},
            {"Ring", s("0000180a" + BASE), "JZ_Tech", RingFamily.RINGCONN},
            {"RingConn Gen 2", null, null, RingFamily.RINGCONN},
            {"UH_A1B2C3D4E5F6", null, null, RingFamily.ULTRAHUMAN},
            {null, s("86f65000-1111-2222-3333-444455556666"), null, RingFamily.ULTRAHUMAN},
            {null, s("86F61000-1111-2222-3333-444455556666"), null, RingFamily.ULTRAHUMAN},
            {null, s("00001b1b" + BASE), null, RingFamily.SAMSUNG},
            {"Galaxy Ring (A1B2)", null, null, RingFamily.SAMSUNG},
            {"Amazfit Helio Ring", null, null, RingFamily.ZEPP},
            {"Lefun ring", s("000018D0" + BASE), null, RingFamily.LEFUN},
            {null, s("14839ac4-7d7e-415c-9a42-167340cf2339"), null, RingFamily.VIATOM},
            {"O2Ring 1234", null, null, RingFamily.VIATOM},
            {"Steel HR", null, null, RingFamily.WITHINGS},
            {null, s("00000020-5749-5448-0037-000000000000"), null, RingFamily.WITHINGS},
            // unknown stays unknown
            {"Headphones", s("0000110b" + BASE), null, RingFamily.UNKNOWN},
            {null, null, null, RingFamily.UNKNOWN},
            {"", s(), "", RingFamily.UNKNOWN},
            {"R2_1234", null, null, RingFamily.UNKNOWN},
            {"r02_341c", null, null, RingFamily.UNKNOWN},
            {"XR02_341C", null, null, RingFamily.UNKNOWN},
            {"uh_123", null, null, RingFamily.UNKNOWN},
            {null, s("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), null, RingFamily.UNKNOWN},
            {null, s("86f65001-1111-2222-3333-444455556666"), null, RingFamily.UNKNOWN},
            {null, s("0000180d" + BASE), "Some maker", RingFamily.UNKNOWN},
            {"Mi Band", s((String) null), null, RingFamily.UNKNOWN},
        };
        for (Object[] r : t) {
            @SuppressWarnings("unchecked")
            List<String> services = (List<String>) r[1];
            eq("family of " + r[0] + " " + services + " " + r[2], RingFamily.of((String) r[0], services, (String) r[2]), r[3]);
        }
        // review of #430: another family's service beats a Withings word in the name
        eq("a Steel R02 with the QRing service is a QRing ring", RingFamily.of("Steel R02", s(RingFamily.QRING_SERVICE), null), RingFamily.QRING);
        WatchCheck.Found steelRing = new WatchCheck.Found("s", "Steel R02", -60).service(RingFamily.QRING_SERVICE);
        check("a Steel R02 with the QRing service is not a watch in the check", !WatchCheck.likelyWatch(steelRing) && WatchCheck.family(steelRing) == RingFamily.QRING);
        WatchCheck.Report steelLayout = new WatchCheck.Report();
        steelLayout.add(RingFamily.QRING_SERVICE);
        check("a Steel R02 is never listened to, whatever the scan said", !WatchCheck.listens(WatchCheck.family(steelRing), steelLayout)
                && !WatchCheck.listens(RingFamily.WITHINGS, steelLayout));
        steelLayout.add(WatchCheck.WITHINGS_SERVICE);
        check("a layout with the Withings service and a ring's service is not listened to", !WatchCheck.listens(RingFamily.WITHINGS, steelLayout));
        eq("the Withings service still beats the other services", RingFamily.of("R02_0000", s(RingFamily.QRING_SERVICE, WatchCheck.WITHINGS_SERVICE), null), RingFamily.WITHINGS);
        eq("the maker beats a Withings word", RingFamily.of("Steel", null, "JZ_Tech"), RingFamily.RINGCONN);
        eq("a Withings word without other signs is still a watch", RingFamily.of("Steel HR", s("0000180a" + BASE), null), RingFamily.WITHINGS);
        // the person's own Oura ring (review of #430)
        String oura = app.openring.core.Protocol.SERVICE.toString();
        eq("Oura: its service", RingFamily.of(null, s(oura.toUpperCase(Locale.ROOT)), null), RingFamily.OURA);
        eq("Oura: its name, any case", RingFamily.of("Oura Ring 4", null, null), RingFamily.OURA);
        eq("Oura: the name rule", RingFamily.ouraName("my OURA") + " " + RingFamily.ouraName(null) + " " + RingFamily.ouraName("Ring"), "true false false");
        eq("Oura: a QRing name with the Oura service is Oura", RingFamily.of("R02_0000", s(oura), null), RingFamily.OURA);
        WatchCheck.Found own = new WatchCheck.Found("o", null, -50);
        own.ouraMaker = true;
        eq("Oura: its maker data alone (company id, as RingScanner.isOura)", WatchCheck.family(own), RingFamily.OURA);
        check("Oura: the maker data does not change the scan's services", own.services.isEmpty());
        check("Oura: listed, a known family, read already", WatchCheck.shown(Arrays.asList(own)).size() == 1 && !RingFamily.OURA.otherRing()
                && !WatchCheck.likelyWatch(own));
        eq("Oura: its own line", RingFamily.OURA.line(), "This is an Oura ring. Open Ring already reads it, there is nothing to send.");
        WatchCheck.Report or = WatchCheck.ouraReport(new WatchCheck.Found("o", "Oura Ring 4", -50));
        check("Oura: its report comes from the scan, never connected, never listened", !or.connected && !or.listen && or.family == RingFamily.OURA
                && or.services.isEmpty() && or.connectMs < 0);
        eq("Oura: the screen says it was left alone", WatchCheck.outcome(or), WatchCheck.OURA_LEFT);
        eq("Oura: the line under the report", WatchCheck.sendBack(or), RingFamily.OURA_LINE);
        check("Oura: no report, note or Copy on the screen, only the outcome and its line (review of #430)", !WatchCheck.showsReport(or)
                && !WatchCheck.showsReport(null));
        check("Oura: the report text names it", WatchCheck.text(or, false).startsWith("Open Ring device check\nName: Oura Ring 4\nLooks like: Oura\n")
                && WatchCheck.text(or, false).contains("Connected: no"));
        WatchCheck.Report ol = new WatchCheck.Report();
        ol.add(oura);
        check("Oura: never listened to, even if the scan said Withings", !WatchCheck.listens(RingFamily.WITHINGS, ol) && !WatchCheck.listens(RingFamily.OURA, ol));
        check("Oura: no driver writes to it through Forbidden", !Forbidden.allowed(RingFamily.OURA, new byte[] {0x01}));
        eq("services before the name: a QRing name with the RingConn service is RingConn",
                RingFamily.of("R02_0000", s(RingFamily.RINGCONN_SERVICE), null), RingFamily.RINGCONN);
        check("other rings are every family but Withings and unknown", RingFamily.QRING.otherRing() && RingFamily.VIATOM.otherRing()
                && !RingFamily.WITHINGS.otherRing() && !RingFamily.UNKNOWN.otherRing());
        // the Device check's scan rows use the same rule
        WatchCheck.Found ring = new WatchCheck.Found("a", null, -60).service(RingFamily.QRING_SERVICE.toUpperCase(Locale.ROOT));
        eq("the check: a scanned ring's family", WatchCheck.family(ring), RingFamily.QRING);
        eq("the check: an unnamed ring is listed (a known family)", WatchCheck.shown(Arrays.asList(ring, new WatchCheck.Found("b", null, -50))).size(), 1);
        eq("the check: a ring's scan row names its family", WatchCheck.detail(ring),
                "Signal -60 dBm, services: " + RingFamily.QRING_SERVICE + "\nLooks like a ring of the QRing family (Colmi, Yawell, KSIX)");
        eq("the check: an unknown row has no family line", WatchCheck.detail(new WatchCheck.Found("z", "Headphones", -61)), "Signal -61 dBm, no services advertised");
    }

    /** The check's rule before issue #418: one of four words anywhere, or the Withings service. */
    static boolean oldCheck(String name, List<String> services) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("withings") || n.contains("steel") || n.contains("hwa0") || n.contains("activit")) return true;
        for (String x : services) if (x.toLowerCase(Locale.ROOT).contains("-5749-5448-")) return true;
        return false;
    }

    /** Setup's name rule before issue #418: a prefix of four words. */
    static boolean oldSetup(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return low.startsWith("steel") || low.startsWith("withings") || low.startsWith("activit") || low.startsWith("scanwatch");
    }

    static void withingsAsBefore() {
        String[] names = {"Steel HR", "steel hr sport", "Withings Move", "WITHINGS", "Activite Pop", "ScanWatch 2", "scanwatch", "My Steel HR",
            "HWA09", "Headphones", "", null, "Steel HR (example)", "Withings ScanWatch Light", "R09_ABCD", "UH_00", "Pulse HR"};
        List<String> none = new ArrayList<String>();
        for (String n : names) {
            boolean now = RingFamily.of(n, none, null) == RingFamily.WITHINGS;
            check("Withings: every watch the check found before is found: " + n, !oldCheck(n, none) || now);
            check("Withings: every watch Setup found before is found: " + n, !oldSetup(n) || now);
            eq("Withings: the one rule is the two old ones together: " + n, now, oldCheck(n, none) || oldSetup(n));
            eq("Withings: the check and Setup agree: " + n, WatchCheck.likelyWatch(new WatchCheck.Found("a", n, -60)), WatchCheck.setupName(n));
        }
        List<String> svc = s(WatchCheck.WITHINGS_SERVICE);
        check("Withings: the service alone, as before", RingFamily.of(null, svc, null) == RingFamily.WITHINGS && oldCheck(null, svc)
                && WatchCheck.likelyWatch(new WatchCheck.Found("a", null, -60).service(WatchCheck.WITHINGS_SERVICE)));
        check("Withings: the changed case of #418, a ScanWatch 2 without services counts in the check now",
                !oldCheck("ScanWatch 2", none) && WatchCheck.likelyWatch(new WatchCheck.Found("a", "ScanWatch 2", -60)));
        eq("Withings: the scan row line as before", RingFamily.WITHINGS.rowLine(), "Looks like a Withings watch");
        check("Withings: WatchCheck.withings is the family's service rule", WatchCheck.withings(WatchCheck.WITHINGS_SERVICE)
                && RingFamily.withingsService("00000024-5749-5448-0037-000000000000") && !RingFamily.withingsService(null) && !WatchCheck.withings(RingFamily.QRING_SERVICE));
    }

    static boolean noDash(String x) {
        return !x.contains(" - ") && !x.contains("–") && !x.contains("—");
    }

    static void lines() {
        eq("QRing line, the decisions' text", RingFamily.QRING.line(), "This looks like a ring of the QRing family (Colmi, Yawell, KSIX). "
                + "Open Ring cannot read it yet. Copy the report and add it to issue 418 on GitHub, so we can build it.");
        check("Withings has no family line (its own lines of #251)", RingFamily.WITHINGS.line() == null);
        check("unknown: a line, no row line", RingFamily.UNKNOWN.line().startsWith("Open Ring does not know this device.") && RingFamily.UNKNOWN.rowLine() == null);
        Set<String> seen = new HashSet<String>();
        for (RingFamily f : RingFamily.values()) {
            if (f == RingFamily.WITHINGS) continue;
            String l = f.line();
            check("one line for " + f, l != null && seen.add(l));
            if (f != RingFamily.OURA) check("the line names the issue: " + f, l.contains("issue 418 on GitHub"));
            check("no dash in the line of " + f, noDash(l) && noDash(f.title));
            if (f.otherRing()) check("the line says what it looks like and that it is not read: " + f, l.startsWith("This looks like " + f.what + ". Open Ring cannot read it"));
        }
        check("Zepp: says why (the key from Zepp's servers, no internet)", RingFamily.ZEPP.line().contains("Zepp's servers") && RingFamily.ZEPP.line().contains("never goes online"));
        for (String x : new String[] {WatchCheck.TITLE, WatchCheck.ROW, WatchCheck.INTRO, WatchCheck.SIGNAL, WatchCheck.DEVICE_NOTE, WatchCheck.NO_ANSWER_DEVICE,
            WatchCheck.NOTHING_FOUND, WatchCheck.RUNNING, WatchCheck.DEMO, WatchCheck.OURA_LEFT, WatchCheck.PAIRED_NOW_DEVICE, RingFamily.OURA_LINE}) {
            check("no dash: " + x.substring(0, Math.min(30, x.length())), noDash(x));
        }
        eq("the screen's title", WatchCheck.TITLE, "Device check");
        eq("the Settings row", WatchCheck.ROW, "Check a ring or watch");
        check("the intro names rings and watches", WatchCheck.INTRO.startsWith("Open Ring can look at a ring or watch"));
        check("the intro says it looks (rings) and listens (watches)", WatchCheck.INTRO.contains("This check only looks and listens: it does not pair"));
        check("the ring's status line names the Device check", WatchCheck.RUNNING.startsWith("Device check running"));
    }

    static void checkScreen() {
        // only a Withings watch is listened to
        WatchCheck.Report ringLayout = new WatchCheck.Report();
        ringLayout.add(RingFamily.QRING_SERVICE);
        check("listen: a ring is never listened to", !WatchCheck.listens(RingFamily.QRING, ringLayout) && !WatchCheck.listens(RingFamily.UNKNOWN, ringLayout));
        WatchCheck.Report bare = new WatchCheck.Report();
        bare.add("0000180a" + BASE);
        bare.add("12345678-1234-1234-1234-123456789abc");
        check("listen: an unknown device is not listened to", !WatchCheck.listens(RingFamily.UNKNOWN, bare) && !WatchCheck.listens(null, bare));
        check("listen: a Withings watch from the scan is", WatchCheck.listens(RingFamily.WITHINGS, bare) && WatchCheck.listens(RingFamily.WITHINGS, new WatchCheck.Report()));
        bare.add(WatchCheck.WITHINGS_SERVICE);
        check("listen: the Withings service in the layout is enough", WatchCheck.listens(RingFamily.UNKNOWN, bare));

        // a ring's report: names, services and characteristics only, even if a message got into it somehow
        WatchCheck.Report r = new WatchCheck.Report();
        r.name = "R02_0000";
        r.connected = true;
        r.listen = false;
        r.connectMs = 1500;
        r.manufacturer = "Example maker";
        WatchCheck.Service q = r.add(RingFamily.QRING_SERVICE);
        WatchCheck.Characteristic notify = q.add("6e400003-b5a3-f393-e0a9-e50e24dcca9e", 0x10);
        notify.listening = true;
        notify.message(new byte[] {0x15, 0x48, 0x3c, 0x40, 0x41});
        q.add("6e400002-b5a3-f393-e0a9-e50e24dcca9e", 0x0c);
        WatchCheck.settle(new WatchCheck.Found("a", "R02_0000", -60), r);
        eq("report: the ring's family", r.family, RingFamily.QRING);
        String t = WatchCheck.text(r, false);
        check("report: a device check with its family", t.startsWith("Open Ring device check\nName: R02_0000\nLooks like: QRing family (Colmi, Yawell, KSIX)\n"), t);
        check("report: says nothing was listened to", t.contains("Connected in 1.5 s, messages not listened to (no health values)\n")
                && t.contains("6e400003-b5a3-f393-e0a9-e50e24dcca9e: notify (not listened to)\n") && t.contains("6e400002-b5a3-f393-e0a9-e50e24dcca9e: write without answer, write\n"));
        check("report privacy: no message, no count, no byte of it", !t.contains("lengths") && !t.contains("1 message") && !t.contains("1548") && !t.contains("483c") && !t.contains("first bytes"), t);
        eq("report privacy: the copy with bytes is the same for a ring", WatchCheck.text(r, true), t);
        eq("report: the note says no health values", WatchCheck.reportNote(r), WatchCheck.DEVICE_NOTE);
        check("report: a ring's report is shown and can be copied", WatchCheck.showsReport(r));
        check("report: the note only speaks for a device that answered", WatchCheck.DEVICE_NOTE.startsWith("When the device answered, the report holds"));
        eq("report: the family's line under it", WatchCheck.sendBack(r), RingFamily.QRING.line());
        eq("report: the outcome names the device", WatchCheck.outcome(r), "Done. The device was let go again.");
        eq("report: a bond left on a device is named as a device", WatchCheck.pairedNow(r), WatchCheck.PAIRED_NOW_DEVICE);
        check("report: the device's bond sentence says how to undo it", WatchCheck.PAIRED_NOW_DEVICE.contains("tap the device and choose Forget"));
        r.connected = false;
        eq("report: no answer from a device", WatchCheck.outcome(r), WatchCheck.NO_ANSWER_DEVICE);
        // the maker read from Device Information names RingConn
        WatchCheck.Report rc = new WatchCheck.Report();
        rc.listen = false;
        rc.manufacturer = "JZ_Tech";
        WatchCheck.settle(new WatchCheck.Found("b", "Ring", -60), rc);
        eq("report: the maker names RingConn", rc.family, RingFamily.RINGCONN);
        // a watch's report stays as it was: the old header, the old notes
        WatchCheck.Report w = WatchCheck.demoReport();
        WatchCheck.settle(new WatchCheck.Found("c", null, -52), w);
        eq("watch report: family Withings", w.family, RingFamily.WITHINGS);
        check("watch report: as before", WatchCheck.text(w, false).startsWith("Open Ring watch check\nName: Steel HR (example)\n")
                && !WatchCheck.text(w, false).contains("Looks like:") && WatchCheck.text(w, true).startsWith("Open Ring watch check (with message bytes, private)"));
        check("watch report: shown and copied as before", WatchCheck.showsReport(w));
        check("watch report: its notes as before", WatchCheck.reportNote(w).equals(WatchCheck.REPORT_NOTE) && WatchCheck.sendBack(w).equals(WatchCheck.SEND_BACK)
                && WatchCheck.outcome(w).equals("Done. The watch was let go again.") && WatchCheck.pairedNow(w).equals(WatchCheck.PAIRED_NOW));
    }

    static void demo() {
        List<WatchCheck.Found> found = WatchCheck.demoFound();
        eq("demo: five made up devices", found.size(), 5);
        WatchCheck.Found ring = found.get(3);
        check("demo: the fourth is the made up QRing ring", ring.address.equals(WatchCheck.DEMO_RING) && ring.name.startsWith("R09_ABCD")
                && WatchCheck.family(ring) == RingFamily.QRING && ring.name.contains("example"));
        check("demo: the watch is still the strongest", WatchCheck.likelyWatch(WatchCheck.strongestFirst(found).get(0)));
        WatchCheck.Report rr = WatchCheck.demoReport(ring);
        eq("demo: the ring's report names QRing", rr.family, RingFamily.QRING);
        check("demo: the ring was not listened to", !rr.listen);
        String t = WatchCheck.text(rr, false);
        check("demo: the ring's report", t.startsWith("Open Ring device check\nName: R09_ABCD (example)\nLooks like: QRing family") && t.contains("Battery: 70%")
                && t.contains(RingFamily.QRING_SERVICE) && t.contains(RingFamily.QRING_BIG) && !t.contains("lengths") && !t.contains("first bytes"), t);
        check("demo: no dash in the ring's report", noDash(t));
        eq("demo: the ring's line", WatchCheck.sendBack(rr), RingFamily.QRING.line());
        WatchCheck.Report hw = WatchCheck.demoReport(found.get(0));
        check("demo: the headphones are an unknown device, only looked at", hw.family == RingFamily.UNKNOWN && !hw.listen
                && WatchCheck.sendBack(hw).equals(RingFamily.UNKNOWN.line()) && WatchCheck.text(hw, false).contains("Looks like: not known"));
        // issue #458: the fifth is a made up Oura ring, the one Open Ring reads, which the check leaves alone (the screen's device_oura)
        WatchCheck.Found oura = found.get(4);
        check("demo: the fifth is the made up Oura ring", oura.address.equals(WatchCheck.DEMO_OURA) && oura.name.contains("example")
                && WatchCheck.family(oura) == RingFamily.OURA && WatchCheck.shown(found).contains(oura));
        WatchCheck.Report ora = WatchCheck.ouraReport(oura);
        check("demo: the Oura ring is left alone, two lines, no report", !ora.connected && !ora.listen && !WatchCheck.showsReport(ora)
                && WatchCheck.outcome(ora).equals(WatchCheck.OURA_LEFT) && WatchCheck.sendBack(ora).equals(RingFamily.OURA_LINE));
        check("demo: the made up devices have different addresses", new java.util.HashSet<String>(java.util.Arrays.asList(found.get(0).address,
                found.get(1).address, found.get(2).address, found.get(3).address, oura.address)).size() == 5);
        WatchCheck.Report wr = WatchCheck.demoReport(found.get(1));
        check("demo: the watch's report as before", wr.family == RingFamily.WITHINGS && wr.listen
                && WatchCheck.text(wr, false).equals(WatchCheck.text(WatchCheck.demoReport(), false)));
    }

    static byte[] qring(int command) {
        byte[] p = new byte[16];
        p[0] = (byte) command;
        int sum = 0;
        for (int i = 0; i < 15; i++) sum += p[i] & 0xff;
        p[15] = (byte) sum;
        return p;
    }

    static void forbidden() {
        check("QRing: the factory reset 0xff is refused", !Forbidden.allowed(RingFamily.QRING, qring(0xff)));
        check("QRing: other commands pass (time 0x01, battery 0x03, heart rate history 0x15)", Forbidden.allowed(RingFamily.QRING, qring(0x01))
                && Forbidden.allowed(RingFamily.QRING, qring(0x03)) && Forbidden.allowed(RingFamily.QRING, qring(0x15)));
        check("Ultrahuman: the wipe 0x98 is refused", !Forbidden.allowed(RingFamily.ULTRAHUMAN, new byte[] {(byte) 0x98}));
        check("Ultrahuman: reading records 0x04 passes", Forbidden.allowed(RingFamily.ULTRAHUMAN, new byte[] {0x04, 0, 0, 0, 0}));
        check("the commands are per family: 0x98 is not a QRing reset, 0xff not Ultrahuman's wipe",
                Forbidden.allowed(RingFamily.QRING, qring(0x98)) && Forbidden.allowed(RingFamily.ULTRAHUMAN, new byte[] {(byte) 0xff}));
        for (RingFamily f : RingFamily.values()) {
            if (f == RingFamily.QRING || f == RingFamily.ULTRAHUMAN) continue;
            check("a family without written rules gets nothing through: " + f, !Forbidden.allowed(f, qring(0x03)));
        }
        check("nothing for no family, nothing empty", !Forbidden.allowed(null, qring(0x03)) && !Forbidden.allowed(RingFamily.QRING, new byte[0])
                && !Forbidden.allowed(RingFamily.QRING, null));
        eq("the reason names the command", Forbidden.reason(RingFamily.QRING, qring(0xff)), "QRing factory reset (0xff)");
        byte[] ok = qring(0x03);
        check("check returns the same bytes", Forbidden.check(RingFamily.QRING, ok) == ok);
        try {
            Forbidden.check(RingFamily.ULTRAHUMAN, new byte[] {(byte) 0x98, 1});
            check("check throws for the wipe", false);
        } catch (Forbidden.Refused e) {
            eq("check throws for the wipe", e.getMessage(), "Ultrahuman wipe (0x98)");
        }
    }

    /** A tiny made up QRing like driver: one heart rate request per day from the cursor, acknowledgement 0x15 back. */
    static final class Fake implements RingDriver {
        final int extra;
        long next;

        Fake(int extra) {
            this.extra = extra;
        }

        @Override
        public RingFamily family() {
            return RingFamily.QRING;
        }

        @Override
        public List<byte[]> requests(long cursor) {
            List<byte[]> l = new ArrayList<byte[]>();
            l.add(qring(0x03));
            for (long d = cursor; d < cursor + 3; d++) {
                byte[] p = qring(0x15);
                p[1] = (byte) d;
                l.add(p);
            }
            if (extra >= 0) l.add(qring(extra));
            return l;
        }

        @Override
        public List<Row> decode(byte[] message) {
            next = Math.max(next, (message[1] & 0xff) + 1);
            return Collections.singletonList(new Row("hr", message));
        }

        @Override
        public byte[] ack(byte[] message) {
            return message[0] == 0x15 ? qring(0x15) : null;
        }

        @Override
        public long nextCursor() {
            return next;
        }
    }

    static void driver() {
        // every request a driver can build passes Forbidden, for every cursor
        Fake good = new Fake(-1);
        int walked = 0;
        for (long c = 0; c < 400; c++) for (byte[] b : Forbidden.requests(good, c)) {
            walked++;
            if (!Forbidden.allowed(good.family(), b)) check("driver: a request was not allowed at cursor " + c, false);
        }
        eq("driver: every request walked through Forbidden", walked, 400 * 4);
        Fake bad = new Fake(0xff);
        try {
            Forbidden.requests(bad, 0);
            check("driver: a driver that builds the factory reset is stopped before it sends anything", false);
        } catch (Forbidden.Refused e) {
            check("driver: a driver that builds the factory reset is stopped before it sends anything", e.getMessage().contains("0xff"));
        }
        // store before acknowledge, the order of RingDriver's contract, on a made up session
        List<String> order = new ArrayList<String>();
        List<RingDriver.Row> stored = new ArrayList<RingDriver.Row>();
        byte[] msg = qring(0x15);
        msg[1] = 7;
        for (RingDriver.Row row : good.decode(msg)) {
            stored.add(row);
            order.add("stored");
        }
        byte[] a = Forbidden.ack(good, msg);
        if (a != null) order.add("ack");
        eq("driver: the raw row is stored before the acknowledgement", order, Arrays.asList("stored", "ack"));
        eq("driver: the cursor moves past the stored day", good.nextCursor(), 8L);
        msg[2] = 99;
        check("driver: a raw row keeps the bytes as they came", stored.get(0).bytes[2] == 0 && stored.get(0).kind.equals("hr"));
        check("driver: no acknowledgement where the family needs none", Forbidden.ack(good, qring(0x03)) == null);
    }
}
