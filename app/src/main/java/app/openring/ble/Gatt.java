package app.openring.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Build;
import android.os.SystemClock;

/**
 * The Bluetooth calls every link makes the same way: the ring's {@link GattLink}, the Watch check's {@link WatchLink} and
 * the watch sync's link in {@link WatchSync}. Android 13 (API 33) changed the write calls, so each write has two ways;
 * keeping them here means a fix to one reaches every device. Each caller keeps its own waits, timeouts and log texts.
 */
@SuppressLint("MissingPermission")
final class Gatt {
    /** The pause between asking the device to disconnect and freeing the link, so the disconnect goes out first. */
    static final int CLOSE_PAUSE_MS = 200;

    private Gatt() {}

    /**
     * Starts writing {@code value} to the descriptor {@code d} (the notification switch). True when Android started the
     * write; its answer then arrives in the callback's {@code onDescriptorWrite}.
     */
    @SuppressWarnings("deprecation")
    static boolean startDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(d, value) == 0;
        d.setValue(value);
        return gatt.writeDescriptor(d);
    }

    /**
     * Starts writing {@code data} to {@code c} with a response (the default write type). True when Android started the
     * write; its answer then arrives in the callback's {@code onCharacteristicWrite}.
     */
    @SuppressWarnings("deprecation")
    static boolean startWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] data) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(data);
        return gatt.writeCharacteristic(c);
    }

    /**
     * Disconnects and frees {@code gatt} (nothing for null). False when Android threw on the way: Bluetooth was switched
     * off meanwhile, so the link is gone already.
     */
    static boolean close(BluetoothGatt gatt) {
        if (gatt == null) return true;
        try {
            gatt.disconnect();
            SystemClock.sleep(CLOSE_PAUSE_MS);
            gatt.close();
            return true;
        } catch (RuntimeException ignored) {
            // Bluetooth was switched off meanwhile: the link is gone already
            return false;
        }
    }
}
