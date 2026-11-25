package android.bluetooth;

import java.util.List;

/** Stand in for the JVM test of ble/Gatt: writes down what is set on it. */
public class BluetoothGattCharacteristic {
    public static final int WRITE_TYPE_DEFAULT = 2;
    public static final int WRITE_TYPE_NO_RESPONSE = 1;
    public final List<String> calls;

    private final java.util.UUID uuid;

    public BluetoothGattCharacteristic(List<String> calls) {
        this(calls, null);
    }

    /** One of a service's characteristics in the layout (the JVM test of ble/WatchChannel, issue #469). */
    public BluetoothGattCharacteristic(List<String> calls, java.util.UUID uuid) {
        this.calls = calls;
        this.uuid = uuid;
    }

    public java.util.UUID getUuid() {
        return uuid;
    }

    public void setWriteType(int type) {
        calls.add("characteristic.setWriteType " + type);
    }

    public boolean setValue(byte[] value) {
        calls.add("characteristic.setValue " + BluetoothGatt.hex(value));
        return true;
    }
}
