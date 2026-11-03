package app.openring.core.analysis;

import app.openring.core.Texts;

import java.util.Locale;

/**
 * The ring finder's texts for the turn on the spot and the search walk (issue #74): how much of the
 * room was covered, where the ring probably is, and what to do next. Moved out of FinderActivity so
 * every branch has a JVM test (FinderTest); the screen only sets the strings.
 */
public final class WalkText {
    private WalkText() {}

    /** How to hold the phone; the finder's help box shows it too. */
    public static final String HOLD = "Hold the phone upright, flat against your chest, screen towards you.";

    /**
     * Whether the ring is connected to this phone right now. Asked only in the one branch that needs it,
     * because the screen's answer makes Bluetooth calls.
     */
    public interface Linked {
        boolean now();
    }

    /** One number with {@code dp} decimals in the phone's language, the same as {@code Ui.fmt}. */
    static String fmt(double v, int dp) {
        if (Double.isNaN(v)) return "--";
        return String.format(Locale.getDefault(), "%." + dp + "f", v);
    }

    /**
     * The text under the dial while turning on the spot (the walk has not reached {@link SearchWalk#SPOT_M} yet).
     *
     * @param noAccel   the phone has no accelerometer (never in demo mode)
     * @param recording the walk is running
     * @param upright   the phone is held upright right now
     * @param notHeard  the ring is not heard right now
     */
    public static String turn(SearchWalk walk, boolean noAccel, boolean recording, boolean upright, boolean notHeard) {
        StringBuilder b = new StringBuilder();
        if (noAccel) b.append("This phone has no accelerometer, so steps are not counted; turning on the spot still works.\n");
        SearchWalk.Direction d = walk.direction();
        if (d == null) {
            b.append("Covered ").append(walk.coveredDegrees()).append(" of 360 degrees (the directions you faced with the phone upright).");
            if (!recording) b.append(" Too little for a direction: tap Start again and turn once all the way around.");
        } else if (d.clear) {
            b.append("Strongest towards the arrow, ").append(Math.round(d.spread)).append(" dB more than the weakest side (more dB is a clearer direction).");
        } else {
            b.append("No clear direction (").append(Math.round(d.spread)).append(" dB between strongest and weakest side, under ")
                    .append(Math.round(SearchWalk.CLEAR_DB)).append("). The ring may be very close, above or below you, or the signal bounced off walls.");
        }
        appendWaiting(b, walk.waitingToShow());
        if (recording && !upright) b.append("\n").append(HOLD).append(" Readings while it is tilted are not counted.");
        if (recording && notHeard) b.append("\nNo signal right now; the turn needs the ring to be heard.");
        return b.toString();
    }

    /**
     * The walk's text: how far, how much of the room, and where the ring probably is.
     *
     * @param spot   where the ring probably is, or null while it is not worked out (yet)
     * @param linked asked only when the room is covered but the ring was hardly heard
     */
    public static String walk(SearchWalk walk, SearchWalk.Spot spot, boolean recording, boolean upright, boolean notHeard, Linked linked) {
        StringBuilder b = new StringBuilder();
        long moved = Math.round(walk.distance() / walk.stepLength());
        b.append("Walked ").append(fmt(walk.distance(), 1)).append(" m (").append(moved).append(moved == 1 ? " step" : " steps")
                .append(" of ").append(fmt(walk.stepLength(), 2)).append(" m).");
        if (spot == null && walk.mapReady()) {
            b.append("\nWorking out where the ring probably is.");
        } else if (spot == null && walk.covered()) {
            // enough of the room, but the ring was hardly heard
            b.append("\nThe ring was heard for only ").append(Texts.count((long) Math.floor(walk.heardSeconds()), "second", "seconds")).append(" of the walk; the circle needs ")
                    .append(SearchWalk.MIN_BINS / 2).append(". While a sync, Live or another app is connected to the ring, it sends no signal.");
            if (linked.now()) b.append(" It is connected to this phone right now.");
            else if (notHeard) b.append(" This phone does not hear it right now.");
        } else if (spot == null) {
            double[] c = walk.cover();
            double across = c[1] >= SearchWalk.WIDTH_M ? c[0] : c[1];
            b.append(" Across ").append(fmt(Math.min(across, SearchWalk.COVER_M), 1)).append(" of ").append(Math.round(SearchWalk.COVER_M))
                    .append(" m in two directions (the circle needs ").append(Math.round(SearchWalk.COVER_M)).append(" m both ways).");
            b.append("\nWalk a bit more, across the room.");
        } else if (spot.clear) {
            b.append("\nProbably within the circle: ").append(SearchWalk.where(spot.x, spot.y)).append(" (radius ").append(fmt(spot.radius, 1))
                    .append(" m). Start looking there.");
            if (spot.beyond) b.append(" The circle lies outside where you walked, so the ring may also be farther that way (the signal cannot tell how far).");
        } else if (spot.edge) {
            b.append("\nThe signal gets stronger towards the edge of where you walked (the dashed circle, ").append(SearchWalk.where(spot.x, spot.y))
                    .append("), so the ring is probably farther that way. "
                    + "Walk on in that direction and look again.");
        } else {
            b.append("\nThe signal gave no clear spot yet (");
            if (spot.explained < spot.needed) {
                // issue #136: on a short walk plain noise fits some spot, so more is needed there
                long[] pc = SearchWalk.percents(spot.explained, spot.needed);
                b.append("its ups and downs could be plain noise: the model explains ").append(pc[0])
                        .append(" % of them, a walk this short needs ").append(pc[1]).append(" %");
            } else {
                b.append("the circle would be wider than ").append(Math.round(SearchWalk.CLEAR_RADIUS_M)).append(" m");
            }
            b.append("). Walk more of the room; if that does not help, the ring may be in another room, "
                    + "upstairs or downstairs, or under something thick.");
        }
        appendWaiting(b, walk.waitingToShow());
        if (recording && !upright) b.append("\n").append(HOLD).append(" Readings while it is tilted are not counted, steps keep your last direction.");
        if (recording && notHeard) b.append("\nNo signal right now; the walk needs the ring to be heard.");
        return b.toString();
    }

    /**
     * Steps counted while turning wait until the turn ends; say so, so the step count and the map agree (issue #108).
     * Not for the one step that waits while the body sways on a straight walk ({@link SearchWalk#waitingToShow}).
     */
    public static void appendWaiting(StringBuilder b, int n) {
        if (n == 0) return;
        b.append("\n").append(Texts.count(n, "step", "steps")).append(" counted while you turned ").append(n == 1 ? "waits" : "wait")
                .append(": they move once you walk on, or stay if it was a turn on the spot.");
    }

    /**
     * The line under the map on the step length.
     *
     * @param hasHeight a height was entered in the profile (unused in demo mode)
     * @param heightCm  that height (used only with {@code hasHeight})
     */
    public static String stepLine(double stepLength, boolean demo, boolean hasHeight, int heightCm) {
        String len = "Steps of " + fmt(stepLength, 2) + " m: 0.41 × ";
        if (demo) return len + "175 cm, a usual height (demo).";
        if (hasHeight) return len + "your height of " + heightCm + " cm from the profile.";
        return len + "175 cm, a usual height. Enter yours in Settings, Profile and goals for a truer map.";
    }
}
