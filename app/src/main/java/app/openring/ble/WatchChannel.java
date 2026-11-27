package app.openring.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import java.util.UUID;

import app.openring.core.withings.WatchUuid;

/**
 * Finds the Withings sync channel in the layout Android already discovered (issue #469, phase 1). First exactly the calls
 * the sync always made, {@code getService(service)} and {@code getCharacteristic(channel)} (the Steel HR's part
 * {@link WatchUuid#STEEL_HR}): when they find it, nothing else is called, so a Steel HR takes the same path as before.
 * Only then every service in the order Android lists them: the first Withings service of any part that holds the channel
 * of the same part ({@link WatchUuid}). These only read the discovered layout: no Bluetooth traffic, no write, no wait.
 */
final class WatchChannel {
    private WatchChannel() {}

    /** The channel, or null when the device shows none (the sync then ends with "did not show the Withings service"). */
    static BluetoothGattCharacteristic find(BluetoothGatt gatt, UUID service, UUID channel) {
        BluetoothGattService s = gatt.getService(service);
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(channel);
        if (c != null) return c;
        for (BluetoothGattService o : gatt.getServices()) {
            String part = WatchUuid.servicePart(o.getUuid().toString());
            if (part == null) continue;
            c = o.getCharacteristic(WatchUuid.channel(part));
            if (c != null) return c;
        }
        return null;
    }

    /** The part of a found channel, for the log: null for the Steel HR's part (its log line stays as it was). */
    static String otherPart(BluetoothGattCharacteristic c) {
        String part = c == null ? null : WatchUuid.channelPart(c.getUuid().toString());
        return WatchUuid.STEEL_HR.equals(part) ? null : part;
    }
}
