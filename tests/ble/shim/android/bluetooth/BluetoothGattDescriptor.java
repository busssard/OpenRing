package android.bluetooth;

import java.util.List;

/** Stand in for the JVM test of ble/Gatt: writes down what is set on it. */
public class BluetoothGattDescriptor {
    public static final byte[] ENABLE_NOTIFICATION_VALUE = {0x01, 0x00};
    public static final byte[] ENABLE_INDICATION_VALUE = {0x02, 0x00};
    public final List<String> calls;

    public BluetoothGattDescriptor(List<String> calls) {
        this.calls = calls;
    }

    public boolean setValue(byte[] value) {
        calls.add("descriptor.setValue " + BluetoothGatt.hex(value));
        return true;
    }
}
