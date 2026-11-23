package android.annotation;

/** Stand in for the JVM test of ble/Gatt (tests/ble/GattTest.java). */
public @interface SuppressLint {
    String[] value();
}
