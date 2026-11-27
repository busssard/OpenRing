package app.openring.core;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Life factors (issue #292, phase 1): the things a person logs about a day (meditation, alcohol, a holiday, their own tags),
 * so they can later be seen next to the nights. This class holds what needs no Android: the fixed keys and their names, the
 * chips Today shows by default, own tags, a stretch of days turned into single days, and every sentence the screens show.
 * Phase 1 stores and shows tags only: nothing here works out an effect or changes a score.
 * <p>
 * A tag is stored per calendar day (yyyy-mm-dd), so travel and clock changes never move it. A tag of day D belongs to the night
 * that starts on the evening of D (shown on Sleep and Readiness the morning after). Since issue #435 a tag of a moment can also
 * carry a clock time on its day and a length ({@code LifeTimes}); the day stays what counts.
 */
public final class LifeTags {
    private LifeTags() {}

    public static final String MEDITATION = "meditation", ALCOHOL = "alcohol", CAFFEINE = "caffeine_late", STRESS = "stress",
            WORKOUT = "hard_workout", INTIMACY = "intimacy", HOLIDAY = "holiday", ILLNESS = "illness", TRAVEL = "travel";
    /** Built in since issue #435 (owner's wish of 21 November), listed after Hard workout. */
    public static final String SAUNA = "sauna", FRIENDS = "friends";

    /** The fixed tags in the order All factors lists them. */
    public static final List<String> BUILT_IN = Collections.unmodifiableList(java.util.Arrays.asList(MEDITATION, ALCOHOL, CAFFEINE, STRESS, WORKOUT,
            SAUNA, FRIENDS, INTIMACY, HOLIDAY, ILLNESS, TRAVEL));

    /**
     * The chips on Today's row, in this order, for a person who never chose the chips (issue #435; before it Meditation,
     * Alcohol, Caffeine late, Stress, Hard workout, decisions of #292). Intimacy is not one (decisions of #292), Holiday is days,
     * not a moment. A choice the person made always wins ({@link Known}): a tag the choice did not know stays off the card.
     */
    public static final List<String> DEFAULT_CHIPS = Collections.unmodifiableList(java.util.Arrays.asList(MEDITATION, SAUNA, FRIENDS, ALCOHOL, STRESS, WORKOUT));

    private static final String[] NAMES = {"Meditation", "Alcohol", "Caffeine late", "Stress", "Hard workout", "Sauna", "Friends", "Intimacy", "Holiday",
        "Illness", "Travel"};

    /** Own tags are stored as this prefix and a number (the moment they were made, in milliseconds). */
    public static final String OWN = "own:";

    /** The longest name of an own tag, so a chip stays readable. */
    public static final int NAME_MAX = 30;

    // ------------------------------------------------------------------------------------------------ texts

    public static final String TITLE = "What was today like?", TITLE_PAST = "What was this day like?";
    public static final String NOTE = "Tap what fits. Stored only on this phone.";
    public static final String MORE = "More", LESS = "Less";
    public static final String ALL = "All factors", SEVERAL = "Several days", ADD_OWN = "Add your own", CHOOSE = "Choose the chips on the card",
            REMOVE_OWN = "Remove one of your own";
    public static final String OWN_HINT = "For example: late dinner";
    /** Short sentences after Several days and Add your own (toasts, each fits two lines). */
    public static final String SPAN_FUTURE = "Several days can end today at the latest.", SPAN_BACKWARDS = "The last day is before the first. Please try again.",
            OWN_REFUSED = "This name is empty, too long or already there.";
    /** Body's card. */
    public static final String BODY_TITLE = "Life factors";
    /** The hint of the cards beside readiness and the night without an effect line (they open Body's month), as before phase 3. */
    public static final String LOGGED_HINT = "As you logged them on Today. Tap for the month on Body.";
    public static final String MONTH_HINT = "Touch and slide across the days to read what you logged. The arrows page through the months.";
    public static final String NONE_YET = "Nothing logged yet. Tap the chips on Today to log what a day was like.";
    /** Where the effect is (issue #292, phase 2): said once on the Body card's month view. */
    public static final String NO_EFFECT_YET = "How your nights went after each factor is in the card below.";
    public static final String NOT_SAVED = "Could not save. Please try again.", NOT_UNDONE = "Could not undo. Please try again.";
    public static final String UNREADABLE = "Your life factors could not be read just now. Your logged days are kept; try again later.";
    /** The CSV export's choices (Settings, Export). */
    public static final String CSV_CHOICE = "Include life factors", CSV_INTIMACY = "Include Intimacy too";
    public static final String CSV_NOTE = "Adds one column per factor to the daily summaries: 1 on each day you logged it, empty on the others. Off by default, as these are private.";

    // ------------------------------------------------------------------------------------------------ keys

    /** The name of a fixed tag, or null for a key that is not one. */
    public static String builtInName(String key) {
        int i = BUILT_IN.indexOf(key);
        return i < 0 ? null : NAMES[i];
    }

    public static boolean isBuiltIn(String key) {
        return BUILT_IN.contains(key);
    }

    public static boolean isOwn(String key) {
        return key != null && key.startsWith(OWN) && key.length() > OWN.length() && key.substring(OWN.length()).matches("[0-9]{1,19}");
    }

    /** A tag key that can be stored: a fixed one or an own one. */
    public static boolean valid(String key) {
        return isBuiltIn(key) || isOwn(key);
    }

    /** The key of a new own tag made at {@code millis}. */
    public static String ownKey(long millis) {
        return OWN + Math.max(0, millis);
    }

    /**
     * The name typed for an own tag, cleaned (spaces at the ends and runs of spaces made one, line breaks and other control
     * characters taken out), or null when it cannot be a tag: empty, longer than {@link #NAME_MAX}, or the name of a fixed tag
     * (any case), which is already there.
     */
    public static String cleanName(String typed) {
        String s = tidy(typed);
        if (s == null || s.isEmpty() || s.length() > NAME_MAX) return null;
        for (String n : NAMES) if (n.equalsIgnoreCase(s)) return null;
        return s;
    }

    /** {@code typed} with spaces at the ends and runs of spaces made one, line breaks and control characters as spaces. */
    static String tidy(String typed) {
        if (typed == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < typed.length(); i++) {
            char ch = typed.charAt(i);
            b.append(Character.isISOControl(ch) || Character.isWhitespace(ch) ? ' ' : ch);
        }
        return b.toString().trim().replaceAll(" +", " ");
    }

    /**
     * What makes two own tag names the same name (issue #422): cleaned as {@link #cleanName} cleans, then any case, as
     * {@code equalsIgnoreCase} compares them ("Sauna", " sauna " and "SAUNA" give the same).
     */
    public static String sameName(String name) {
        String s = tidy(name);
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) b.append(Character.toLowerCase(Character.toUpperCase(s.charAt(i))));
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ the known tags

    /** One tag as the screens know it: its key, its name, whether it is on Today's row, and whether it was removed (own only). */
    public static final class Tag {
        public final String key, name;
        public final boolean chip, removed;

        public Tag(String key, String name, boolean chip, boolean removed) {
            this.key = key;
            this.name = name;
            this.chip = chip;
            this.removed = removed;
        }

        @Override
        public String toString() {
            return key + "=" + name + (chip ? " chip" : "") + (removed ? " removed" : "");
        }
    }

    /** What the person changed about one tag (a row of {@code life_tags}): null fields mean "as by default". */
    public static final class Choice {
        public final String key, name;
        /** On Today's row (1), not (0), or as by default (null). */
        public final Integer shown;
        /** The place among the chips, null for the default order. */
        public final Integer ord;
        public final boolean gone;

        public Choice(String key, String name, Integer shown, Integer ord, boolean gone) {
            this.key = key;
            this.name = name;
            this.shown = shown;
            this.ord = ord;
            this.gone = gone;
        }
    }

    /**
     * Every tag the person can log or has logged, in the order All factors lists them, from the stored choices.
     * <p>
     * Own tags with the same name ({@link #sameName}) that are all in force, or all removed, are one factor (issue #422: after
     * Delete all, "Sauna" made again and an older backup with its own "Sauna" restored give two keys with one name). The
     * factor's key is the older one (made first); every newer key with that name is an alias of it: not listed in {@link #all},
     * its days read as the older key's ({@link #fold}), and the writes of a tap reach it too ({@link #aliases}). A removed own
     * tag never joins one in force. Only reading joins them: no stored row changes.
     */
    public static final class Known {
        /** By key: the fixed tags first in their order, then own tags in the order they were made (removed ones too). */
        public final Map<String, Tag> all = new LinkedHashMap<String, Tag>();
        private final Map<String, Integer> ord = new java.util.HashMap<String, Integer>();
        /** An alias key and the older key it is read as. */
        private final Map<String, String> sameAs = new java.util.HashMap<String, String>();

        public Known(List<Choice> choices) {
            Map<String, Choice> by = new java.util.HashMap<String, Choice>();
            List<Choice> own = new ArrayList<Choice>();
            if (choices != null) {
                for (Choice c : choices) {
                    if (c == null || !valid(c.key)) continue;
                    by.put(c.key, c);
                    if (isOwn(c.key) && c.name != null && !c.name.trim().isEmpty()) own.add(c);
                }
            }
            // the person chose the chips once (Choose the chips on the card stores every fixed tag): a fixed tag that choice did
            // not know (Sauna and Friends, new in issue #435) stays off the card; only a person who never chose gets the defaults
            boolean chose = false;
            for (String k : BUILT_IN) chose |= by.get(k) != null && by.get(k).shown != null;
            // an own tag in force with a fixed tag's name (an own "Sauna" made before issue #435): that fixed tag is no default
            // chip, so the card never shows two chips with one name (review of #444); All factors lists both
            java.util.Set<String> ownNames = new java.util.HashSet<String>();
            for (Choice c : own) if (!c.gone) ownNames.add(sameName(c.name));
            for (String k : BUILT_IN) {
                Choice c = by.get(k);
                boolean byDefault = !chose && DEFAULT_CHIPS.contains(k) && !ownNames.contains(sameName(builtInName(k)));
                boolean chip = c == null || c.shown == null ? byDefault : c.shown != 0;
                all.put(k, new Tag(k, builtInName(k), chip, false));
                if (c != null && c.ord != null) ord.put(k, c.ord);
            }
            Collections.sort(own, new java.util.Comparator<Choice>() {
                @Override
                public int compare(Choice a, Choice b) {
                    return Long.compare(ownMillis(a.key), ownMillis(b.key));
                }
            });
            // the oldest key of each name (in force and removed apart) is the factor; the newer ones are its aliases
            Map<String, String> first = new java.util.HashMap<String, String>();
            Map<String, Boolean> chip = new java.util.HashMap<String, Boolean>();
            for (Choice c : own) {
                String g = (c.gone ? "removed " : "in force ") + sameName(c.name);
                String main = first.get(g);
                if (main == null) first.put(g, main = c.key);
                else sameAs.put(c.key, main);
                // a new own tag is on the card until the person takes it off; a removed one never is; a factor of several keys
                // is on the card when any of them is (the key made last may be the one the person just put there)
                boolean on = !c.gone && (c.shown == null || c.shown != 0);
                chip.put(main, on || Boolean.TRUE.equals(chip.get(main)));
                if (c.ord != null && !ord.containsKey(main)) ord.put(main, c.ord);
            }
            for (Choice c : own) {
                if (!sameAs.containsKey(c.key)) all.put(c.key, new Tag(c.key, c.name, chip.get(c.key), c.gone));
            }
        }

        /** The key {@code k} is read as: the older key of its name for an alias, else {@code k} itself. */
        public String key(String k) {
            String m = sameAs.get(k);
            return m != null ? m : k;
        }

        /** The newer keys read as {@code key}, oldest first (empty for a fixed tag or an own tag alone with its name). */
        public List<String> aliases(String key) {
            List<String> out = new ArrayList<String>();
            for (Map.Entry<String, String> e : sameAs.entrySet()) if (e.getValue().equals(key)) out.add(e.getKey());
            Collections.sort(out, new java.util.Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Long.compare(ownMillis(a), ownMillis(b));
                }
            });
            return out;
        }

        /**
         * The tags of each day as one factor each: an alias read as its older key, a day holding both listed once, each day's
         * tags in key order. A new map; {@code days} is not changed.
         */
        public TreeMap<LocalDate, List<String>> fold(Map<LocalDate, List<String>> days) {
            TreeMap<LocalDate, List<String>> out = new TreeMap<LocalDate, List<String>>();
            if (days == null) return out;
            for (Map.Entry<LocalDate, List<String>> e : days.entrySet()) {
                java.util.TreeSet<String> keys = new java.util.TreeSet<String>();
                for (String k : e.getValue()) keys.add(key(k));
                out.put(e.getKey(), new ArrayList<String>(keys));
            }
            return out;
        }

        /** The name of {@code key}; an own tag nobody knows any more (a row of another phone) reads as "Own factor". */
        public String name(String key) {
            Tag t = all.get(key(key));
            if (t != null) return t.name;
            String b = builtInName(key);
            return b != null ? b : "Own factor";
        }

        /**
         * The chips on Today's row, in their order: the stored order first, then the default chips in their order
         * ({@link #DEFAULT_CHIPS}), then the list order.
         */
        public List<Tag> chips() {
            List<Tag> out = new ArrayList<Tag>();
            for (Tag t : all.values()) if (t.chip && !t.removed) out.add(t);
            final List<String> listed = new ArrayList<String>(all.keySet());
            Collections.sort(out, new java.util.Comparator<Tag>() {
                @Override
                public int compare(Tag a, Tag b) {
                    return Integer.compare(place(a.key), place(b.key));
                }

                private int place(String k) {
                    Integer o = ord.get(k);
                    if (o != null) return o;
                    int d = DEFAULT_CHIPS.indexOf(k);
                    return 1000 + (d >= 0 ? d : DEFAULT_CHIPS.size() + listed.indexOf(k));
                }
            });
            return out;
        }

        /** The tags that can be logged now (All factors): every fixed one and the own ones not removed. */
        public List<Tag> loggable() {
            List<Tag> out = new ArrayList<Tag>();
            for (Tag t : all.values()) if (!t.removed) out.add(t);
            return out;
        }

        /** The own tags not removed (for Remove one of your own). */
        public List<Tag> own() {
            List<Tag> out = new ArrayList<Tag>();
            for (Tag t : all.values()) if (isOwn(t.key) && !t.removed) out.add(t);
            return out;
        }

        /** The names of {@code keys} in the list order (fixed tags first), for the lines beside a night. */
        public List<String> names(java.util.Collection<String> keys) {
            List<String> out = new ArrayList<String>();
            for (String k : keys(keys)) out.add(all.containsKey(k) ? all.get(k).name : name(k));
            return out;
        }

        /**
         * {@code keys} as their factors' keys (an alias read as its older key, each once) in the order of {@link #names}, for the
         * effect lines beside a night (issue #292, phase 3).
         */
        public List<String> keys(java.util.Collection<String> keys) {
            List<String> out = new ArrayList<String>();
            if (keys == null) return out;
            java.util.Set<String> in = new java.util.LinkedHashSet<String>();
            for (String k : keys) in.add(key(k));
            for (String k : all.keySet()) if (in.contains(k)) out.add(k);
            for (String k : in) if (!all.containsKey(k)) out.add(k);
            return out;
        }
    }

    static long ownMillis(String key) {
        try {
            return Long.parseLong(key.substring(OWN.length()));
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------------------------------------ days

    /**
     * Every calendar day from {@code from} to {@code to}, both included, one entry each (a clock change or a month end changes
     * nothing: these are dates, not times). Empty when {@code to} is before {@code from} or either is null.
     */
    public static List<LocalDate> span(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<LocalDate>();
        if (from == null || to == null || to.isBefore(from)) return out;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(d);
        return out;
    }

    /** "3 Nov", or "3 Nov 2025" outside {@code thisYear}. */
    public static String date(LocalDate d, int thisYear, Locale loc) {
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == thisYear ? "d MMM" : "d MMM yyyy", loc));
    }

    /** "Holiday from 3 Nov to 10 Nov" (one day: "Holiday on 3 Nov"). */
    public static String spanLine(String name, LocalDate from, LocalDate to, int thisYear, Locale loc) {
        if (from.equals(to)) return name + " on " + date(from, thisYear, loc);
        return name + " from " + date(from, thisYear, loc) + " to " + date(to, thisYear, loc);
    }

    /** After a stretch was logged: "Holiday logged for 8 days, 3 Nov to 10 Nov." */
    public static String spanDone(String name, LocalDate from, LocalDate to, int thisYear, Locale loc) {
        int n = span(from, to).size();
        return name + " logged for " + n + (n == 1 ? " day, " : " days, ") + date(from, thisYear, loc) + " to " + date(to, thisYear, loc) + ".";
    }

    /**
     * The question before several days are logged, under "Holiday from 3 Nov to 10 Nov": "One entry on each of the 8 days. To
     * take a day away, open that day and tap its chip." ("One entry on 1 day." for one day).
     */
    public static String spanDone(int n) {
        return (n == 1 ? "One entry on 1 day." : "One entry on each of the " + n + " days.") + " To take a day away, open that day and tap its chip.";
    }

    /** After a tap: "Meditation added to 8 Nov." or "Meditation taken away from 8 Nov." */
    public static String tapLine(String name, boolean on, LocalDate day, int thisYear, Locale loc) {
        return name + (on ? " added to " : " taken away from ") + date(day, thisYear, loc) + ".";
    }

    /** "Log for 27 Oct" over the chips of a past day. */
    public static String logFor(LocalDate day, int thisYear, Locale loc) {
        return "Log for " + date(day, thisYear, loc);
    }

    /** Sleep, beside the night after a day with tags: "Before this night: Alcohol, Meditation." Null when there are none. */
    public static String beforeNight(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        return "Before this night: " + join(names) + ".";
    }

    /**
     * Readiness, the morning after a day with tags: "Logged yesterday: Alcohol." when the shown day is today, else "Logged on
     * 26 Oct: Alcohol." Null when there are none.
     */
    public static String morning(List<String> names, LocalDate shown, LocalDate today, Locale loc) {
        if (names == null || names.isEmpty()) return null;
        LocalDate before = shown.minusDays(1);
        String when = shown.equals(today) ? "yesterday" : "on " + date(before, today.getYear(), loc);
        return "Logged " + when + ": " + join(names) + ".";
    }

    /** The month view's readout of one day: "Mon 3 Nov: Holiday, Meditation." or "Mon 3 Nov: nothing logged." */
    public static String dayLine(LocalDate day, List<String> names, LocalDate today, Locale loc) {
        String d = day.format(DateTimeFormatter.ofPattern(day.getYear() == today.getYear() ? "EEE d MMM" : "EEE d MMM yyyy", loc));
        if (day.isAfter(today)) return d + ": still to come.";
        return d + ": " + (names == null || names.isEmpty() ? "nothing logged." : join(names) + ".");
    }

    /**
     * The month's counts under the month view: "This month: Meditation on 12 days, Alcohol on 4 days." (most days first, then
     * the list order), or "Nothing logged this month." The days are each calendar day of the month with the tag.
     */
    public static String monthLine(Map<LocalDate, List<String>> days, java.time.YearMonth month, Known known) {
        final Map<String, Integer> n = new LinkedHashMap<String, Integer>();
        for (String k : known.all.keySet()) n.put(k, 0);
        for (Map.Entry<LocalDate, List<String>> e : days.entrySet()) {
            if (!java.time.YearMonth.from(e.getKey()).equals(month)) continue;
            for (String k : e.getValue()) n.put(k, (n.containsKey(k) ? n.get(k) : 0) + 1);
        }
        List<String> keys = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() > 0) keys.add(e.getKey());
        if (keys.isEmpty()) return "Nothing logged this month.";
        final List<String> order = new ArrayList<String>(n.keySet());
        Collections.sort(keys, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Integer.compare(n.get(b), n.get(a));
                return c != 0 ? c : Integer.compare(order.indexOf(a), order.indexOf(b));
            }
        });
        StringBuilder b = new StringBuilder("This month: ");
        for (int i = 0; i < keys.size(); i++) {
            int c = n.get(keys.get(i));
            b.append(i == 0 ? "" : ", ").append(known.name(keys.get(i))).append(" on ").append(c).append(c == 1 ? " day" : " days");
        }
        return b.append('.').toString();
    }

    /** "A, B, C" (as the plan's texts: a list to scan, not a sentence). */
    static String join(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) b.append(i == 0 ? "" : ", ").append(names.get(i));
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ CSV

    /**
     * The CSV columns for the tags (Settings, Export, "Include life factors"): every fixed tag but Intimacy, Intimacy only with
     * {@code intimacy}, then every own tag (removed ones too, their days are kept), then Sauna and Friends; none without
     * {@code life}. Sauna and Friends came with issue #435 and go last, so a sheet that reads the earlier columns by their
     * place keeps working (code review of #444); on screen they stay after Hard workout. Header names are {@code life_} and
     * the key, own tags {@code life_own_} and their name in small letters with anything but letters and digits as {@code _},
     * a number added when two would be the same.
     */
    public static Map<String, String> csvColumns(Known known, boolean life, boolean intimacy) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!life) return out;
        List<Tag> order = new ArrayList<Tag>(), last = new ArrayList<Tag>();
        for (Tag t : known.all.values()) (SAUNA.equals(t.key) || FRIENDS.equals(t.key) ? last : order).add(t);
        order.addAll(last);
        java.util.Set<String> used = new java.util.HashSet<String>();
        for (Tag t : order) {
            if (INTIMACY.equals(t.key) && !intimacy) continue;
            String head;
            if (isBuiltIn(t.key)) {
                head = "life_" + t.key;
            } else {
                StringBuilder b = new StringBuilder("life_own_");
                for (char ch : t.name.toLowerCase(Locale.ROOT).toCharArray()) b.append(Character.isLetterOrDigit(ch) ? ch : '_');
                head = b.toString();
            }
            String h = head;
            for (int i = 2; used.contains(h); i++) h = head + "_" + i;
            used.add(h);
            out.put(t.key, h);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ demo

    /**
     * The demo's made up tags (only ever written into the demo file): meditation most mornings of the easy last week and some
     * before, alcohol on the weekend evenings, a hard workout twice a week, caffeine late and stress in the busy weeks, the
     * short cold of the demo's story (2 days, {@code coldBack} days before today) as Illness, a long weekend away as Holiday
     * and Travel; a sauna now and then and friends once a week (issue #435, their times in {@code LifeTimes.demo}). Never
     * Intimacy. Today Meditation, yesterday Alcohol, Meditation and Sauna, so every screen has something to show.
     */
    public static TreeMap<LocalDate, List<String>> demo(LocalDate today, int days, int coldBack) {
        TreeMap<LocalDate, List<String>> out = new TreeMap<LocalDate, List<String>>();
        for (int back = days - 1; back >= 0; back--) {
            LocalDate d = today.minusDays(back);
            List<String> t = new ArrayList<String>();
            int dow = d.getDayOfWeek().getValue();
            boolean easy = back < 7;
            if (back == 0 || back == 1 || (easy && back != 3) || back % 3 == 0) t.add(MEDITATION);
            if (back == 1 || (!easy && (dow == 5 || dow == 6) && back % 2 == 0)) t.add(ALCOHOL);
            if (!easy && back % 4 == 2) t.add(CAFFEINE);
            if (!easy && back >= 9 && back % 5 == 4) t.add(STRESS);
            if (back > 1 && (dow == 2 || dow == 6) && back != coldBack && back != coldBack + 1) t.add(WORKOUT);
            // issue #435: a sauna now and then (yesterday's and 8 days ago with a time, LifeTimes.demo), friends once a week
            if (back == 1 || back == 8 || back == 29) t.add(SAUNA);
            if (back == 3 || back == 10 || back == 17 || back == 24) t.add(FRIENDS);
            if (back == coldBack + 1 || back == coldBack) t.add(ILLNESS);
            if (back >= 23 && back <= 26) t.add(HOLIDAY);
            if (back == 26 || back == 23) t.add(TRAVEL);
            if (!t.isEmpty()) out.put(d, t);
        }
        return out;
    }

    /**
     * Made up tags over the demo's made up year (issue #292, phase 2; only in {@code Demo.enter}, only into the demo file), so the
     * card "Life factors and your nights" has enough days to show: Meditation on every third day whatever the night (made up as
     * no effect, "not clear yet"), Alcohol on Friday and Saturday evenings before a night whose HRV was more than 4 % under its
     * usual and on some others (made up as a clear effect), a hard workout on Tuesdays and Saturdays, Stress now and then, and
     * Illness on the year's week of illness ({@code ill}). {@code hrvVsUsual}: the HRV of each day's night (the one starting that evening) in % of its usual
     * ({@code FactorEffect.points}), missing where there is none. Never Intimacy.
     */
    public static TreeMap<LocalDate, List<String>> demoYear(LocalDate from, LocalDate to, Map<LocalDate, Double> hrvVsUsual, java.util.Set<LocalDate> ill) {
        TreeMap<LocalDate, List<String>> out = new TreeMap<LocalDate, List<String>>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<String> t = new ArrayList<String>();
            long e = d.toEpochDay();
            int dow = d.getDayOfWeek().getValue();
            boolean sick = ill != null && ill.contains(d);
            Double h = hrvVsUsual == null ? null : hrvVsUsual.get(d);
            if (e % 3 == 0) t.add(MEDITATION);
            if (!sick && (dow == 5 || dow == 6) && (h != null && h < -4 || e % 10 == 0)) t.add(ALCOHOL);
            if (!sick && e % 9 == 4) t.add(STRESS);
            if (!sick && (dow == 2 || dow == 6)) t.add(WORKOUT);
            if (sick) t.add(ILLNESS);
            if (!t.isEmpty()) out.put(d, t);
        }
        return out;
    }

    /** The demo's several day entries (Illness and Holiday above), each from and to, so they share a span as typed ones do. */
    public static List<LocalDate[]> demoSpans(LocalDate today, int coldBack) {
        List<LocalDate[]> out = new ArrayList<LocalDate[]>();
        out.add(new LocalDate[] {today.minusDays(coldBack + 1), today.minusDays(coldBack)});
        out.add(new LocalDate[] {today.minusDays(26), today.minusDays(23)});
        return out;
    }
}
