package app.openring.core;

/**
 * Whether Android still shows its permission dialog after a request came back without the permission
 * (issue #140). Android offers an explanation ({@code shouldShowRequestPermissionRationale}) only after
 * the person explicitly said no once; after a second no (or "Don't ask again") it stops asking and says
 * nothing. But it also offers no explanation for a permission that was never refused, so a dialog closed
 * with Back, or a request cut off (empty results), looks the same right after it. Only a refusal that
 * follows an earlier one means Android no longer asks.
 */
public final class PermissionAsk {
    private PermissionAsk() {}

    /** Android's {@code PackageManager.PERMISSION_GRANTED} and {@code PERMISSION_DENIED}. */
    public static final int GRANTED = 0, DENIED = -1;

    /**
     * Whether only the app's settings can switch the permission on now.
     *
     * @param perms        the permissions of the request, as Android returns them (may be empty)
     * @param results      Android's answer for each of them (may be empty or shorter: no answer)
     * @param needed       the permissions the screen needs
     * @param rationale    for each needed one, whether Android would explain it now
     * @param deniedBefore whether the person explicitly refused before this request
     */
    public static boolean blocked(String[] perms, int[] results, String[] needed, boolean[] rationale, boolean deniedBefore) {
        if (!deniedBefore || perms == null || results == null) return false;
        for (int i = 0; i < needed.length; i++) {
            if (rationale[i]) continue;
            for (int j = 0; j < perms.length && j < results.length; j++) {
                if (needed[i].equals(perms[j]) && results[j] == DENIED) return true;
            }
        }
        return false;
    }

    /** Whether the person explicitly refused at least once: Android explains a needed permission only then. */
    public static boolean deniedOnce(boolean[] rationale) {
        for (boolean b : rationale) if (b) return true;
        return false;
    }

    /** What the screen does after a permission request came back (see {@link #answer}). */
    public static final class Answer {
        /** Only the app's settings can switch it on: show "Open app settings" instead of "Allow". */
        public boolean blocked;
        /** This answer was a refusal without explanation; the next one like it blocks (see {@link #quietDenial}). */
        public boolean quiet;
        /** The new value of the remembered explicit refusal ({@link #remember}). */
        public boolean denied;
    }

    /**
     * The screen's whole decision after a request (issue #140), so the test drives the code the app runs.
     *
     * @param granted      whether all needed permissions are granted now
     * @param deniedBefore the remembered explicit refusal from before this request
     * @param quietBefore  the last answer on this screen was a refusal without explanation
     */
    public static Answer answer(boolean granted, String[] perms, int[] results, String[] needed, boolean[] rationale,
                                boolean deniedBefore, boolean quietBefore) {
        Answer a = new Answer();
        a.denied = remember(granted, rationale, deniedBefore);
        if (granted) return a;
        a.blocked = blocked(perms, results, needed, rationale, deniedBefore || quietBefore);
        a.quiet = quietDenial(perms, results, needed, rationale);
        return a;
    }

    /**
     * The remembered explicit refusal, updated: set once Android explains a needed permission, cleared once
     * they are all granted (Android may take an unused app's permissions back later and then asks afresh,
     * so a refusal from before the grant must not count).
     */
    public static boolean remember(boolean granted, boolean[] rationale, boolean stored) {
        if (granted) return false;
        return stored || deniedOnce(rationale);
    }

    /**
     * Whether Android answered a needed permission with DENIED without explaining it: a dialog closed without
     * choosing, or a refusal Android no longer asks about. Two of these in a row on one screen mean Android
     * does not ask (a phone where the refusals happened before the app remembered them).
     */
    public static boolean quietDenial(String[] perms, int[] results, String[] needed, boolean[] rationale) {
        return blocked(perms, results, needed, rationale, true);
    }
}
