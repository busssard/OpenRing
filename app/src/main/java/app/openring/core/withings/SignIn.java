package app.openring.core.withings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The watch's sign in (issue #251): right after the hello, the Steel HR sends a challenge ({@link Proto#CHALLENGE} with
 * a {@link Proto#CHALLENGE_PART}: its own address as text and 16 random bytes). The app proves it knows a fixed secret
 * by sending back the SHA-1 of the challenge bytes, the address text and the secret, one after the other, and adds a
 * challenge of its own, which the watch answers the same way inside its hello answer.
 *
 * <p>The secret and the layout are protocol facts taken from Gadgetbridge's public Withings support (AGPL), written
 * down in docs/ARCHITECTURE.md; this class is our own code (the owner's decision of 3 November). Nothing here is ever
 * logged: the log names message types and lengths only.
 */
public final class SignIn {
    private SignIn() {}

    /** The fixed secret every Withings app uses for the sign in (a protocol fact, see the class comment). */
    static final String SECRET = "2EM5zNP37QzM00hmP6BFTD92nG15XwNd";
    /** Bytes of our own challenge (the watch's is the same length). */
    public static final int OURS = 16;

    /** What the watch's challenge holds; null fields never happen, {@link #read} returns null instead. */
    public static final class Challenge {
        public final String address;
        public final byte[] bytes;

        Challenge(String address, byte[] bytes) {
            this.address = address;
            this.bytes = bytes;
        }
    }

    /**
     * Reads the challenge part of {@code msg}: a length byte and the address text, then a length byte and the challenge
     * bytes. Null when the part is missing, cut short or holds no challenge bytes. An empty address text is replaced by
     * {@code fallbackAddress} (the address Android connected to), in capitals as the watch writes it.
     */
    public static Challenge read(byte[] msg, String fallbackAddress) {
        Frame.Part p = Frame.first(msg, Proto.CHALLENGE_PART);
        if (p == null) return null;
        byte[] v = p.value;
        if (v.length < 1) return null;
        int n = v[0] & 0xff;
        if (1 + n + 1 > v.length) return null;
        String address = new String(v, 1, n, StandardCharsets.UTF_8);
        int m = v[1 + n] & 0xff;
        if (m == 0 || 2 + n + m > v.length) return null;
        byte[] bytes = Arrays.copyOfRange(v, 2 + n, 2 + n + m);
        if (address.isEmpty()) {
            if (fallbackAddress == null || fallbackAddress.isEmpty()) return null;
            address = fallbackAddress.toUpperCase(java.util.Locale.ROOT);
        }
        return new Challenge(address, bytes);
    }

    /** SHA-1 of the challenge bytes, then the address text, then the secret. */
    public static byte[] proof(byte[] challenge, String address) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(challenge);
            sha.update(address.getBytes(StandardCharsets.UTF_8));
            sha.update(SECRET.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            // every Java and Android runtime has SHA-1
            throw new IllegalStateException(e);
        }
    }

    /**
     * The whole answer to the watch's challenge message {@code msg}, or null when its layout is not the known one. It is
     * a {@link Proto#CHALLENGE} message with two parts: {@link Proto#CHALLENGE_ANSWER} (a length byte, then the proof)
     * and {@link Proto#CHALLENGE_PART} (the watch's address text and {@code ours}, each with a length byte first).
     */
    public static byte[] answer(byte[] msg, String fallbackAddress, byte[] ours) {
        return answer(read(msg, fallbackAddress), ours);
    }

    /** The whole answer to an already read challenge {@code c} (see {@link #answer(byte[], String, byte[])}), or null. */
    public static byte[] answer(Challenge c, byte[] ours) {
        if (c == null || ours == null || ours.length == 0 || ours.length > 255) return null;
        byte[] proof = proof(c.bytes, c.address);
        byte[] address = c.address.getBytes(StandardCharsets.UTF_8);
        if (address.length > 255) return null;
        byte[] answer = new byte[1 + proof.length];
        answer[0] = (byte) proof.length;
        System.arraycopy(proof, 0, answer, 1, proof.length);
        byte[] back = new byte[1 + address.length + 1 + ours.length];
        back[0] = (byte) address.length;
        System.arraycopy(address, 0, back, 1, address.length);
        back[1 + address.length] = (byte) ours.length;
        System.arraycopy(ours, 0, back, 2 + address.length, ours.length);
        return Frame.message(Proto.CHALLENGE, Frame.part(Proto.CHALLENGE_ANSWER, answer), Frame.part(Proto.CHALLENGE_PART, back));
    }

    /**
     * Checks the watch's own proof in its hello answer {@code reply} against {@code ours} and the address text: null
     * when the answer holds no proof part (the watch need not send one), else whether it is right.
     */
    public static Boolean watchProof(byte[] reply, byte[] ours, String address) {
        Frame.Part p = Frame.first(reply, Proto.CHALLENGE_ANSWER);
        if (p == null) return null;
        byte[] v = p.value;
        if (v.length < 1 || 1 + (v[0] & 0xff) > v.length) return Boolean.FALSE;
        byte[] got = Arrays.copyOfRange(v, 1, 1 + (v[0] & 0xff));
        return MessageDigest.isEqual(got, proof(ours, address));
    }
}
