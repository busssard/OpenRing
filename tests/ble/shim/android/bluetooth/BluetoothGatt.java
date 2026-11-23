package android.bluetooth;

import java.util.List;

/**
 * Stand in for the JVM test of ble/Gatt: writes every call down in {@link #calls} and answers with {@link #code} (the
 * API 33 calls) or {@link #started} (the older ones); {@link #fail} names a call that throws as Android does when
 * Bluetooth was switched off.
 */
public class BluetoothGatt {
    public final List<String> calls;
    public int code;
    public boolean started = true;
    public String fail = "";

    public BluetoothGatt(List<String> calls) {
        this.calls = calls;
    }

    private void call(String what) {
        calls.add(what);
        if (fail.equals(what.split(" ")[0])) throw new IllegalStateException("Bluetooth off");
    }

    public int writeDescriptor(BluetoothGattDescriptor d, byte[] value) {
        call("writeDescriptor33 " + hex(value));
        return code;
    }

    public boolean writeDescriptor(BluetoothGattDescriptor d) {
        call("writeDescriptor");
        return started;
    }

    public int writeCharacteristic(BluetoothGattCharacteristic c, byte[] value, int writeType) {
        call("writeCharacteristic33 " + hex(value) + " type " + writeType);
        return code;
    }

    public boolean writeCharacteristic(BluetoothGattCharacteristic c) {
        call("writeCharacteristic");
        return started;
    }

    public void disconnect() {
        call("disconnect");
    }

    public void close() {
        call("close");
    }

    static String hex(byte[] v) {
        StringBuilder b = new StringBuilder();
        for (byte x : v) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }
}
