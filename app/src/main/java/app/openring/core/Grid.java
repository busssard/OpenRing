package app.openring.core;

/**
 * The sizes of a grid of equal cells (owner's wish of 17 November: "Badges should have consistent size"): every cell has the
 * same width, and every cell the height of the tallest one, so no badge is bigger than another however long its name. Plain
 * Java so the JVM tests cover it; {@code ui/EqualGrid} lays the views out with it.
 */
public final class Grid {
    private Grid() {}

    /** The width of each cell: what is left after the gaps, shared equally; never below 0. */
    public static int cellWidth(int total, int columns, int gap) {
        int cols = Math.max(1, columns);
        return Math.max(0, (total - gap * (cols - 1)) / cols);
    }

    /** How many rows {@code count} cells take. */
    public static int rows(int count, int columns) {
        int cols = Math.max(1, columns);
        return count <= 0 ? 0 : (count + cols - 1) / cols;
    }

    /** The height of every cell: the tallest one's (0 without cells). */
    public static int cellHeight(int[] measured) {
        int h = 0;
        for (int m : measured) h = Math.max(h, m);
        return h;
    }

    /** The height of the whole grid: its rows of {@code cellHeight} with a gap between them. */
    public static int height(int count, int columns, int cellHeight, int gap) {
        int r = rows(count, columns);
        return r == 0 ? 0 : r * cellHeight + (r - 1) * gap;
    }

    /** The left edge of cell {@code i}: filled row by row, left to right; an odd last cell stays in its column. */
    public static int left(int i, int columns, int cellWidth, int gap) {
        int cols = Math.max(1, columns);
        return (i % cols) * (cellWidth + gap);
    }

    /** The top edge of cell {@code i}. */
    public static int top(int i, int columns, int cellHeight, int gap) {
        int cols = Math.max(1, columns);
        return (i / cols) * (cellHeight + gap);
    }
}
