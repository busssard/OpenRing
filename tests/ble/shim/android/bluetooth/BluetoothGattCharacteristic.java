package android.bluetooth;

import java.util.List;

/** Stand in for the JVM test of ble/Gatt: writes down what is set on it. */
public class BluetoothGattCharacteristic {
    public static final int WRITE_TYPE_DEFAULT = 2;
    public static final int WRITE_TYPE_NO_RESPONSE = 1;
    public final List<String> calls;

    public BluetoothGattCharacteristic(List<String> calls) {
        this.calls = calls;
    }

    public void setWriteType(int type) {
        calls.add("characteristic.setWriteType " + type);
    }

    public boolean setValue(byte[] value) {
        calls.add("characteristic.setValue " + BluetoothGatt.hex(value));
        return true;
    }
}
