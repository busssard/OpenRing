package app.openring.core;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * App level authentication: the ring sends a 15 byte nonce, the client returns it encrypted with
 * AES-128/ECB/PKCS5Padding under the shared 16 byte key (one block). Port of open_oura
 * {@code auth.rs}.
 */
public final class Auth {
    private Auth() {}

    public static final int OK = 0x00;
    public static final int WRONG_KEY = 0x01;
    public static final int IN_FACTORY_RESET = 0x02;
    public static final int NOT_ORIGINAL_DEVICE = 0x03;

    public static byte[] encryptNonce(byte[] key16, byte[] nonce) {
        int n = Math.min(nonce.length, 16);
        byte[] block = new byte[16];
        System.arraycopy(nonce, 0, block, 0, n);
        byte pad = (byte) (16 - n);
        for (int i = n; i < 16; i++) block[i] = pad;
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
            return c.doFinal(block);
        } catch (Exception e) {
            throw new IllegalStateException("AES unavailable: " + e, e);
        }
    }

    public static byte[] newKey() {
        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        return k;
    }

    public static String describe(int state) {
        switch (state) {
            case OK: return "accepted";
            case WRONG_KEY: return "the ring rejected this key (it belongs to a different pairing)";
            case IN_FACTORY_RESET: return "the ring is factory reset and has no key yet, pair it first";
            case NOT_ORIGINAL_DEVICE: return "the ring is bonded to a different onboarding";
            default: return String.format("unknown auth state 0x%02x", state);
        }
    }
}
