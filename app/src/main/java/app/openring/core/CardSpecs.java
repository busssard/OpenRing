package app.openring.core;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a page says about its cards (the tile key, title, folded summary, Further analysis' listener) from the moment it builds
 * them until {@code ui/Tiles} wraps them into tiles. Keyed weakly by the card, so a card that is never wrapped does not stay in
 * memory.
 * <p>
 * The tiles {@link #take} the value: a weak key only lets go of a card when nothing strong reaches it, and a value can reach its
 * own card (Further analysis' listener holds the card's body, the body its parent, the card). Left in the map such an entry
 * would never be cleared, and with it the whole page, its day data and the Activity, once per Sleep page built. Once taken the
 * tile holds the value for as long as the page lives. Plain Java, so the JVM tests run it.
 */
public final class CardSpecs<K, V> {
    private final Map<K, V> map = new WeakHashMap<K, V>();

    /** The value of {@code card}, null when the page said nothing about it (or it was taken). */
    public V get(K card) {
        return map.get(card);
    }

    public void put(K card, V value) {
        map.put(card, value);
    }

    /** The value of {@code card}, removed from here (null when there is none): the tile keeps it from now on. */
    public V take(K card) {
        return map.remove(card);
    }

    /** Cards still waiting for their tile (the tests use it). */
    public int size() {
        return map.size();
    }
}
