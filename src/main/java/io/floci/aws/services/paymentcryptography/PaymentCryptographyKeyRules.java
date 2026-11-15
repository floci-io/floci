package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;

import java.util.List;
import java.util.Map;

/**
 * The rules a key has to satisfy before an operation runs under it.
 *
 * <p>The data plane has more than one kind of operation, and they all ask the same questions
 * of the key they run under: is it enabled, does it allow the mode of use the operation
 * needs, is it of the usage the operation is for, is it of the cipher family the algorithm
 * is defined over, and does it hold its material. The answers are the same whatever the
 * operation is, so they are asked in one place.
 *
 * <p>A key that does not satisfy a rule is refused with the error that names it, so a caller
 * is told which of the key's attributes is wrong rather than that the request failed. The
 * refusal is a {@code ValidationException}, which is the error the data plane's model
 * states for a request that is well made but that the service will not serve.
 */
final class PaymentCryptographyKeyRules {

    private PaymentCryptographyKeyRules() {
    }

    /**
     * A key that is disabled does no work.
     *
     * <p>The service reads a disabled key and tells the caller about it, but it does not
     * encrypt with it, decrypt with it, or make a MAC under it.
     *
     * @param key the key the request names
     */
    static void requireEnabled(PaymentCryptographyKey key) {
        if (!key.isEnabled()) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is disabled", 400);
        }
    }

    /**
     * The mode of use a key has to have for an operation.
     *
     * <p>The mode is what the key allows: a key that may only decrypt may not encrypt, and a
     * key with no restrictions may do both.
     *
     * @param key the key the operation runs under
     * @param mode the mode of use the operation needs
     * @param what the operation, for the message
     */
    static void requireMode(PaymentCryptographyKey key, String mode, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        if (modes == null) {
            return;
        }
        // A NoRestrictions that is false is not a permission: a request that names the
        // member says nothing about what it allows, so the mode itself decides.
        if (Boolean.TRUE.equals(modes.get("NoRestrictions"))) {
            return;
        }
        if (!Boolean.TRUE.equals(modes.get(mode))) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " does not have the mode of use " + mode
                            + ", which it needs to " + what, 400);
        }
    }

    /**
     * The usage a key has to have for an operation.
     *
     * <p>The usage is what binds a key to its purpose, so an operation that belongs to one
     * purpose is not served with a key of another: a PIN encryption key does not encrypt
     * arbitrary data, and a MAC key does not either. The check is on the usage itself, not
     * on the mode, because a key may carry the mode and still be of another purpose.
     *
     * @param key the key the operation runs under
     * @param usages the usages the operation serves
     * @param what the operation, for the message
     */
    static void requireUsage(PaymentCryptographyKey key, List<String> usages, String what) {
        String usage = key.getKeyUsage();
        if (usage == null || usages.contains(usage)) {
            return;
        }
        throw new AwsException("ValidationException",
                "the key " + key.getArn() + " is of the usage " + usage + ", and it may not be used to "
                        + what + ", which needs a key of the usage " + String.join(" or ", usages), 400);
    }

    /**
     * The family a key's algorithm has to be for an operation.
     *
     * <p>The algorithm runs under the key: the ISO 9797 MACs are defined over Triple DES and
     * the CMAC over AES, so a key of the other family would be read as bytes it is not.
     *
     * @param key the key the operation runs under
     * @param aes whether the operation needs an AES key
     * @param what the operation, for the message
     */
    static void requireFamily(PaymentCryptographyKey key, boolean aes, String what) {
        if (PaymentCryptographyService.isAes(key) != aes) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is a " + key.getKeyAlgorithm() + " key, and "
                            + what + " needs a " + (aes ? "AES" : "Triple DES") + " one", 400);
        }
    }

    /**
     * The algorithm a key has to have for an operation.
     *
     * <p>The algorithm is what the cipher runs, so the operation names the one it is
     * defined over: a Triple DES key and an AES key are different ciphers, and so are
     * the two Triple DES forms.
     *
     * @param key the key the operation runs under
     * @param algorithm the algorithm the operation needs
     * @param what the operation, for the message
     */
    static void requireAlgorithm(PaymentCryptographyKey key, String algorithm, String what) {
        if (!algorithm.equals(key.getKeyAlgorithm())) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is a " + key.getKeyAlgorithm() + " key, and "
                            + what + " needs a " + algorithm + " one", 400);
        }
    }

    /**
     * The mode of use a key has to have to derive from it.
     *
     * <p>A derivation reads the key, so the key has to allow it. A key with no modes
     * of use allows nothing, and is refused here rather than read.
     *
     * @param key the key the operation runs under
     * @param what the operation, for the message
     */
    static void requireDerive(PaymentCryptographyKey key, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        boolean derives = modes != null
                && (Boolean.TRUE.equals(modes.get("DeriveKey"))
                        || Boolean.TRUE.equals(modes.get("NoRestrictions")));
        if (!derives) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " does not have the mode of use DeriveKey, "
                            + "which it needs to " + what, 400);
        }
    }

    /**
     * The material of a key.
     *
     * <p>The material is held by the key while the emulator runs and is not written to the
     * store, so a key read back after a restart has none. A request that needs the material
     * is then refused with an error that says so, rather than failing on the missing value.
     *
     * @param key the key the operation runs under
     * @return the key's material
     */
    static byte[] material(PaymentCryptographyKey key) {
        return PaymentCryptographyCrypto.fromHex(materialHex(key));
    }

    /**
     * The material of a key, as it is held.
     *
     * <p>The material is held by the key while the emulator runs and is not written to the
     * store, so a key read back after a restart has none. A request that needs the material
     * is then refused with an error that says so, rather than failing on the missing value.
     *
     * @param key the key the operation runs under
     * @return the key's material, as hexadecimal
     */
    static String materialHex(PaymentCryptographyKey key) {
        String hex = key.getKeyMaterialHex();
        if (hex == null || hex.isEmpty()) {
            throw new AwsException("ValidationException",
                    "the material of the key " + key.getArn() + " is not held: it is kept while the "
                            + "emulator runs and is not written to the store, so a key read back after "
                            + "a restart has none", 400);
        }
        return hex;
    }
}
