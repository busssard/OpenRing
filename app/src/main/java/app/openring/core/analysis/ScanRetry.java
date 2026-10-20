package app.openring.core.analysis;

/**
 * When the ring finder's continuous Bluetooth scan starts again after Android refused or stopped it
 * (issue #96). Android lets a scan start again after 30 s; while it waits, the screen says so. When
 * Bluetooth goes off or the permission is missing meanwhile, that is no longer a failure to wait
 * out: the screen shows the real reason and starts the scan once it is solved.
 */
public final class ScanRetry {
    /** Wait after a failure before the next start ("scanning too frequently" lasts 30 s). */
    public static final long RETRY_MS = 30000;
    /** The "starts again within 30 s" message never stays longer than this, whatever happened meanwhile. */
    public static final long STALE_MS = RETRY_MS + 5000;

    private long failedAt = -1;
    private int error;

    /** The scan failed at {@code nowMs} (monotonic clock) with Android's error code; returns the wait before the retry. */
    public long failed(long nowMs, int code) {
        failedAt = Math.max(0, nowMs);
        error = code;
        return RETRY_MS;
    }

    /** A scan was started. */
    public void started() {
        failedAt = -1;
    }

    /** Bluetooth is off or the permission is missing: the screen says that instead, and starts the scan once it is solved. */
    public void blocked() {
        failedAt = -1;
    }

    /** Whether the scan is waiting out a failure (the screen says it starts again by itself). */
    public boolean waiting(long nowMs) {
        return failedAt >= 0 && nowMs - failedAt < STALE_MS;
    }

    /** Android's error code of the last failure (6: scanning too frequently, -1: an exception). */
    public int error() {
        return error;
    }

    /** Whether the screen's regular check should start a scan now. */
    public boolean shouldStart(long nowMs, boolean bluetoothOn, boolean scanning) {
        return bluetoothOn && !scanning && !waiting(nowMs);
    }
}
