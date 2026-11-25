package android.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stand in for the JVM test of ble/WatchChannel (issue #469): a service of the discovered layout; writes down each look up. */
public class BluetoothGattService {
    private final UUID uuid;
    private final List<String> calls;
    public final List<BluetoothGattCharacteristic> characteristics = new ArrayList<BluetoothGattCharacteristic>();

    public BluetoothGattService(List<String> calls, UUID uuid) {
        this.calls = calls;
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    /** The first characteristic with {@code u}, as Android answers it. */
    public BluetoothGattCharacteristic getCharacteristic(UUID u) {
        calls.add("getCharacteristic " + u + " in " + uuid);
        for (BluetoothGattCharacteristic c : characteristics) if (c.getUuid().equals(u)) return c;
        return null;
    }
}
