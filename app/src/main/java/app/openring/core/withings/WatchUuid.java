package app.openring.core.withings;

import java.util.Locale;
import java.util.UUID;

/**
 * The Withings service and its sync channel by their part (issue #469, phase 1): the service
 * {@code 00000020-5749-5448-XXXX-000000000000} and in it the channel {@code 00000024-5749-5448-XXXX-000000000000} with the
 * same four hex digits {@code XXXX}. The Steel HR shows part {@link #STEEL_HR}; the ScanWatch family may show another
 * (Gadgetbridge's ScanWatch pull request, Codeberg 7266, says its watches are tried on every set; facts only, our words).
 * Pure, so {@code ble/WatchChannel} (the sync) and {@code core/WatchCheck} (the Device check) use one rule.
 */
public final class WatchUuid {
    private WatchUuid() {}

    /** The Steel HR's part, the one the sync has always used and still tries first. */
    public static final String STEEL_HR = "0037";

    private static final String MIDDLE = "-5749-5448-", TAIL = "-000000000000";
    private static final String SERVICE_HEAD = "00000020", CHANNEL_HEAD = "00000024";

    /** The part of a Withings service UUID ("0037"), lower case, or null for any other UUID. */
    public static String servicePart(String uuid) {
        return part(uuid, SERVICE_HEAD);
    }

    /** The part of a Withings sync channel UUID ("0037"), lower case, or null for any other UUID. */
    public static String channelPart(String uuid) {
        return part(uuid, CHANNEL_HEAD);
    }

    /** The service with {@code part}. */
    public static UUID service(String part) {
        return UUID.fromString(SERVICE_HEAD + MIDDLE + part + TAIL);
    }

    /** The sync channel with {@code part}, the one in the service with the same part. */
    public static UUID channel(String part) {
        return UUID.fromString(CHANNEL_HEAD + MIDDLE + part + TAIL);
    }

    private static String part(String uuid, String head) {
        if (uuid == null) return null;
        String u = uuid.toLowerCase(Locale.ROOT);
        if (u.length() != 36 || !u.startsWith(head + MIDDLE) || !u.endsWith(TAIL)) return null;
        String p = u.substring(19, 23);
        for (int i = 0; i < 4; i++) if (Character.digit(p.charAt(i), 16) < 0) return null;
        return p;
    }
}
