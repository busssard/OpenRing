package app.openring.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Reads drip's export (issue #252, phase 1b, the owner's additions of 30 Oct): a CSV file with one row per day, read by
 * column NAME, never by position, so other drip versions still work. Only drip's documented column names are used; no
 * drip code (drip is GPL, its rules library AGPL).
 *
 * <ul>
 * <li>{@code date} (YYYY-MM-DD) and {@code bleeding.value} must be there, else the file is refused with a plain sentence.</li>
 * <li>{@code bleeding.value}: 0 spotting, 1 light, 2 medium, 3 heavy, empty none. {@code bleeding.exclude} true: drip
 * did not count it as a period, so it is kept as spotting (spotting never starts or extends a period).</li>
 * <li>{@code temperature.value} (basal temperature by mouth), {@code temperature.time}, {@code temperature.exclude}: its
 * own series, apart from the ring's skin temperature, in °C; a value above {@link #FAHRENHEIT_ABOVE} is °F and is
 * converted.</li>
 * <li>Every other column is counted per kind (the part of its name before the first dot) for the summary and not
 * stored (decided on the issue: drip notes are counted, not stored).</li>
 * </ul>
 * Robust against a byte order mark, Windows and old Mac line ends, quoted fields with commas, quotes and line breaks,
 * missing and extra columns, rows in any order, empty lines and duplicate dates (the last row of a date wins, counted).
 */
public final class DripCsv {
    private DripCsv() {}

    /** A temperature above this is in °F (no person's mouth is at 45 °C, no °F reading is below it). */
    public static final double FAHRENHEIT_ABOVE = 45;
    /** A mouth temperature outside this range (°C, after the conversion) is not a reading and is left out. */
    public static final double LOWEST_C = 34, HIGHEST_C = 43;

    /** The file cannot be read as a drip export; the message is a plain sentence for the screen (no data in it). */
    public static final class Refused extends Exception {
        private final String sentence;

        Refused(String m) {
            super(m);
            sentence = m;
        }

        /** The sentence for the screen: one of {@link #EMPTY}, {@link #NOT_TEXT}, {@link #NOT_DRIP}. */
        public String sentence() {
            return sentence;
        }
    }

    public static final String EMPTY = "This file is empty.";
    public static final String NOT_TEXT = "This file is not a text file, so it cannot be a drip export.";
    public static final String NOT_DRIP = "This is not a drip export: it has no date and bleeding.value columns. "
            + "In drip, export your data under Settings, Export data, and pick that file.";

    /** One mouth temperature: °C (two decimals), the time of day as HH:MM or null, and whether drip left it out. */
    public static final class Temp {
        public final double c;
        public final String time;
        public final boolean excluded;

        public Temp(double c, String time, boolean excluded) {
            this.c = c;
            this.time = time;
            this.excluded = excluded;
        }

        /** Same reading: the same °C to the hundredth, the same time and the same exclude mark. */
        public boolean same(Temp o) {
            return o != null && Math.round(c * 100) == Math.round(o.c * 100) && excluded == o.excluded
                    && (time == null ? o.time == null : time.equals(o.time));
        }

        @Override
        public String toString() {
            return c + (time == null ? "" : " " + time) + (excluded ? " excluded" : "");
        }
    }

    /** What a drip file holds. Nothing here is stored; the preview and the import work from it. */
    public static final class File {
        /** The bleeding days: 0 spotting (also bleeding drip did not count as a period), 1 light, 2 medium, 3 heavy. */
        public final TreeMap<LocalDate, Integer> flows = new TreeMap<LocalDate, Integer>();
        /** The mouth temperatures by day. */
        public final TreeMap<LocalDate, Temp> temps = new TreeMap<LocalDate, Temp>();
        /** Rows with a readable date (after duplicates were folded), rows whose date came again (the last one counts). */
        public int rows, duplicates;
        /** Rows without a readable date (left out), and rows dated after today (left out). */
        public int unreadable, future;
        /** Bleeding or temperature values that are not a number in range (each left out). */
        public int badValues;
        /** Bleeding days drip did not count as a period (kept as spotting), temperatures given in °F (converted). */
        public int excludedBleeding, fahrenheit;
        /** Per kind of the other columns (mood, note, mucus, ...), the rows with an entry: counted, not imported. */
        public final TreeMap<String, Integer> other = new TreeMap<String, Integer>();
    }

    /** Reads {@code text} (the whole file) as a drip export; rows dated after {@code today} are left out and counted. */
    public static File parse(String text, LocalDate today) throws Refused {
        if (text == null) throw new Refused(EMPTY);
        if (text.indexOf('\0') >= 0) throw new Refused(NOT_TEXT);
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> recs = records(text);
        int h = 0;
        while (h < recs.size() && blank(recs.get(h))) h++;
        if (h == recs.size()) throw new Refused(EMPTY);
        List<String> head = recs.get(h);
        Map<String, Integer> col = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < head.size(); i++) {
            String n = head.get(i).trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && !col.containsKey(n)) col.put(n, i);
        }
        if (!col.containsKey("date") || !col.containsKey("bleeding.value")) throw new Refused(NOT_DRIP);
        File f = new File();
        // the last row of a date wins as a whole
        TreeMap<LocalDate, List<String>> byDay = new TreeMap<LocalDate, List<String>>();
        for (int r = h + 1; r < recs.size(); r++) {
            List<String> row = recs.get(r);
            if (blank(row)) continue;
            LocalDate d = date(cell(row, col, "date"));
            if (d == null) {
                f.unreadable++;
                continue;
            }
            if (d.isAfter(today)) {
                f.future++;
                continue;
            }
            if (byDay.put(d, row) != null) f.duplicates++;
        }
        f.rows = byDay.size();
        for (Map.Entry<LocalDate, List<String>> e : byDay.entrySet()) {
            LocalDate d = e.getKey();
            List<String> row = e.getValue();
            String b = cell(row, col, "bleeding.value");
            if (!b.isEmpty()) {
                Integer v = whole(b);
                if (v == null || v < 0 || v > 3) f.badValues++;
                else if (truth(cell(row, col, "bleeding.exclude"))) {
                    f.flows.put(d, 0);
                    if (v > 0) f.excludedBleeding++;
                } else {
                    f.flows.put(d, v);
                }
            }
            String t = cell(row, col, "temperature.value");
            if (!t.isEmpty()) {
                Double c = number(t);
                boolean fahr = c != null && c > FAHRENHEIT_ABOVE;
                if (fahr) c = (c - 32) * 5 / 9;
                if (c == null || c < LOWEST_C || c > HIGHEST_C) {
                    f.badValues++;
                } else {
                    if (fahr) f.fahrenheit++;
                    f.temps.put(d, new Temp(Math.round(c * 100) / 100.0, time(cell(row, col, "temperature.time")), truth(cell(row, col, "temperature.exclude"))));
                }
            }
            // every other column, counted once per row and kind
            java.util.TreeSet<String> kinds = new java.util.TreeSet<String>();
            for (Map.Entry<String, Integer> c : col.entrySet()) {
                String name = c.getKey();
                if (KNOWN.contains(name) || name.endsWith(".exclude")) continue;
                String v = c.getValue() < row.size() ? row.get(c.getValue()).trim() : "";
                if (v.isEmpty() || v.equalsIgnoreCase("false")) continue;
                kinds.add(kind(name));
            }
            for (String k : kinds) {
                Integer n = f.other.get(k);
                f.other.put(k, n == null ? 1 : n + 1);
            }
        }
        return f;
    }

    static final java.util.Set<String> KNOWN = new java.util.HashSet<String>(java.util.Arrays.asList("date", "bleeding.value", "bleeding.exclude",
            "temperature.value", "temperature.time", "temperature.exclude"));

    /** The kind a column belongs to: the part before the first dot ("mood.happy" is mood); other bleeding and temperature columns by their whole name. */
    static String kind(String name) {
        int dot = name.indexOf('.');
        String k = dot < 0 ? name : name.substring(0, dot);
        if (k.equals("bleeding") || k.equals("temperature")) return name.replace('.', ' ');
        return k;
    }

    private static boolean blank(List<String> row) {
        for (String s : row) if (!s.trim().isEmpty()) return false;
        return true;
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    /** A real calendar date written YYYY-MM-DD in the years a person logs (1900 to 2999), else null. */
    static LocalDate date(String s) {
        if (s.length() != 10) return null;
        try {
            LocalDate d = LocalDate.parse(s);
            return d.toString().equals(s) && d.getYear() >= 1900 && d.getYear() <= 2999 ? d : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    static boolean truth(String s) {
        return s.equalsIgnoreCase("true");
    }

    /** "2", "2.0": a whole number; else null. */
    static Integer whole(String s) {
        Double d = number(s);
        return d == null || d != Math.rint(d) ? null : (int) Math.round(d);
    }

    /** A decimal number with a point or a comma ("36.55", "36,55"), else null. */
    static Double number(String s) {
        s = s.replace(',', '.');
        if (!s.matches("[+-]?[0-9]+(\\.[0-9]+)?|[+-]?\\.[0-9]+")) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "7:05", "07:05", "07:05:30" as "07:05"; anything else null (the reading is kept without a time). */
    static String time(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{1,2}):([0-9]{2})(:[0-9]{2})?").matcher(s);
        if (!m.matches()) return null;
        int hh = Integer.parseInt(m.group(1)), mm = Integer.parseInt(m.group(2));
        return hh < 24 && mm < 60 ? String.format(Locale.ROOT, "%02d:%02d", hh, mm) : null;
    }

    /**
     * The records of a CSV text (RFC 4180): fields split by commas, a field in double quotes may hold commas, line breaks
     * and doubled quotes; a record ends at CRLF, LF or a lone CR outside quotes. An unclosed quote runs to the end.
     */
    public static List<List<String>> records(String s) {
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> rec = new ArrayList<String>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, any = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
                any = true;
            } else if (ch == ',') {
                rec.add(f.toString());
                f.setLength(0);
                any = true;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') i++;
                rec.add(f.toString());
                out.add(rec);
                rec = new ArrayList<String>();
                f.setLength(0);
                any = false;
            } else {
                f.append(ch);
                any = true;
            }
        }
        if (any || f.length() > 0) {
            rec.add(f.toString());
            out.add(rec);
        }
        return out;
    }

    // ------------------------------------------------------------------------------- texts

    /** "Not imported: 40 mood entries, 3 note entries." (counted, not stored); null without other columns. */
    public static String notImported(File f) {
        if (f.other.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Not imported: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : f.other.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey()).append(e.getValue() == 1 ? " entry" : " entries");
            first = false;
        }
        return sb.append('.').toString();
    }

    /** The lines about rows that were left out or changed on the way in (each with its number); empty when none. */
    public static List<String> notes(File f) {
        List<String> out = new ArrayList<String>();
        if (f.duplicates > 0) out.add(Texts.count(f.duplicates, "date came", "dates came") + " twice, the last row of each counts.");
        if (f.unreadable > 0) out.add(Texts.count(f.unreadable, "row has", "rows have") + " no readable date and " + (f.unreadable == 1 ? "is" : "are") + " left out.");
        if (f.future > 0) out.add(Texts.count(f.future, "row is", "rows are") + " dated after today and left out.");
        if (f.badValues > 0) out.add(Texts.count(f.badValues, "value is", "values are") + " not a bleeding amount or temperature this app can read, left out.");
        if (f.fahrenheit > 0) out.add(Texts.count(f.fahrenheit, "temperature was", "temperatures were") + " in °F and " + (f.fahrenheit == 1 ? "is" : "are") + " shown in °C.");
        return out;
    }

    // -------------------------------------------------------------------------------- demo

    /**
     * A made up drip export for demo mode and the screen test (never a real one): six cycles before the demo's own made up
     * log ({@code Cycles.demo}, whose first period starts 250 days before today), one of its periods with 2 days and a
     * single bleeding day between two periods (both flagged in the preview), the first period of the demo log (5 days) with
     * one day that differs, a bleeding day drip did not count, a temperature every morning (one in °F, one left out), mood
     * and note columns, a quoted note with a comma; newest row first, Windows line ends and a byte order mark.
     */
    public static String demo(LocalDate today) {
        // the first period of the demo's own log (Cycles.demo), so the file overlaps it there
        LocalDate demoStart = app.openring.core.analysis.Cycles.demo(today).firstKey();
        int[] cycle = {28, 30, 27, 29, 31, 26};
        int[] days = {5, 2, 5, 4, 6, 5};
        TreeMap<LocalDate, String[]> rows = new TreeMap<LocalDate, String[]>();
        LocalDate start = demoStart;
        for (int c : cycle) start = start.minusDays(c);
        LocalDate first = start;
        for (int i = 0; i < cycle.length; i++) {
            for (int k = 0; k < days[i]; k++) {
                String v = days[i] == 2 ? "1" : k == 0 ? "2" : k == 1 ? "3" : k < days[i] - 1 ? "2" : "1";
                rows.put(start.plusDays(k), new String[] {v, "", "", ""});
            }
            if (i == 2) rows.put(start.plusDays(13), new String[] {"2", "", "", ""}); // a single day mid cycle
            if (i == 4) rows.put(start.plusDays(15), new String[] {"1", "true", "", ""}); // drip did not count it
            start = start.plusDays(cycle[i]);
        }
        // the first period of the demo's log, one day logged differently
        String[] first5 = {"2", "2", "2", "1", "1"}; // the demo log has heavy on its second day
        for (int k = 0; k < first5.length; k++) rows.put(demoStart.plusDays(k), new String[] {first5[k], "", "", ""});
        Random r = new Random(11);
        StringBuilder sb = new StringBuilder("﻿date,temperature.value,temperature.exclude,temperature.time,temperature.note,bleeding.value,bleeding.exclude,"
                + "mucus.feeling,mucus.texture,mucus.value,mucus.exclude,cervix.opening,cervix.exclude,note.value,desire.value,pain.cramps,mood.happy,mood.sad\r\n");
        LocalDate last = demoStart.plusDays(4);
        List<String> lines = new ArrayList<String>();
        int n = 0;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1), n++) {
            String[] b = rows.get(d);
            // a little warmer in the second half of each cycle, plus noise
            double c = 36.35 + (r.nextInt(5) - 2) * 0.05 + (n % 29 > 14 ? 0.3 : 0);
            String temp = n == 40 ? String.format(Locale.ROOT, "%.1f", c * 9 / 5 + 32) : String.format(Locale.ROOT, "%.2f", c);
            String ex = n == 77 ? "true" : "false";
            String time = String.format(Locale.ROOT, "%02d:%02d", 6 + (n % 3 == 0 ? 1 : 0), (n * 7) % 60);
            String note = n % 23 == 5 ? "\"Long walk, felt fine\"" : "";
            String mood = n % 6 == 0 ? "true" : "";
            String mucus = n % 9 == 2 ? "1" : "";
            lines.add(d + "," + temp + "," + ex + "," + time + ",," + (b == null ? "" : b[0]) + "," + (b == null ? "" : b[1]) + ","
                    + mucus + "," + mucus + "," + mucus + ",false,,," + note + ",,," + mood + ",");
        }
        for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append("\r\n");
        return sb.toString();
    }

    /**
     * The demo's made up mouth temperatures (demo mode only, issue #252 phase 1b): one each morning for the last 100 days,
     * 0.3 °C warmer on the mornings after each made up ovulation ({@code warm}), with noise; one reading left out as drip
     * would ("excluded").
     */
    public static TreeMap<LocalDate, Temp> demoMouth(LocalDate today, java.util.Set<LocalDate> warm) {
        TreeMap<LocalDate, Temp> out = new TreeMap<LocalDate, Temp>();
        Random r = new Random(13);
        for (int i = 99; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            if (i % 17 == 9) continue; // a forgotten morning now and then
            double c = 36.4 + (r.nextInt(7) - 3) * 0.04 + (warm.contains(d) ? 0.3 : 0);
            String time = String.format(Locale.ROOT, "%02d:%02d", 6 + (i % 4 == 0 ? 1 : 0), (i * 11) % 60);
            out.put(d, new Temp(Math.round(c * 100) / 100.0, time, i == 31));
        }
        return out;
    }
}
