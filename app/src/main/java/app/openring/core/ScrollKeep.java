package app.openring.core;

/**
 * Where a scrolled page stays when one card on it is redrawn and changes height (issue #204). All
 * values are pixels in the page's own coordinates. Plain Java so the JVM tests cover it.
 */
public final class ScrollKeep {
    private ScrollKeep() {}

    /**
     * The scroll position after a card at {@code cardTop} changed from {@code oldHeight} to
     * {@code newHeight}, the page being scrolled to {@code scrollY} before. When the whole card was
     * above the top of the screen, the page moves with it, so what the person looks at (below the
     * card) stays still. When any part of the card is on screen, the position stays: the card's top
     * stays still and it grows or shrinks at its bottom. Never below 0.
     */
    public static int after(int scrollY, int cardTop, int oldHeight, int newHeight) {
        if (cardTop + oldHeight > scrollY) return Math.max(0, scrollY);
        return Math.max(0, scrollY + newHeight - oldHeight);
    }
}
