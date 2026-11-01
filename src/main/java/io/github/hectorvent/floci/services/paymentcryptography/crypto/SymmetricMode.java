package io.github.hectorvent.floci.services.paymentcryptography.crypto;

/** The symmetric block-cipher modes the data plane supports. */
public enum SymmetricMode {
    CBC,
    ECB;

    public static SymmetricMode from(String name) {
        if (name == null) {
            return null;
        }
        for (SymmetricMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }
}
