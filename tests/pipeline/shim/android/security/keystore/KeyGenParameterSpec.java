package android.security.keystore;
public class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
    public static class Builder {
        public Builder(String a, int p) {}
        public Builder setBlockModes(String... m) { return this; }
        public Builder setEncryptionPaddings(String... m) { return this; }
        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(); }
    }
}
