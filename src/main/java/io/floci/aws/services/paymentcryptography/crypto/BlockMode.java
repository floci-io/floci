package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.OFBBlockCipher;

/**
 * The block cipher modes the reference names for the symmetric encryption.
 *
 * <p>The reference's model names eight of them: the ECB, the CBC, the CFB, the CFB1, the
 * CFB8, the CFB64, the CFB128 and the OFB. The CFB with no number is the one whose
 * feedback is the cipher's own block, so it is the CFB128 over AES and the CFB64 over
 * Triple DES, and the numbered ones are the ones whose feedback is that many bits.
 *
 * <p>BouncyCastle's modes are the ones that run: the ECB, the CBC, the CFB and the OFB. Its
 * CFB takes a feedback of whole bytes, so the CFB8 and the CFB64 are its own, and the CFB1,
 * whose feedback is a single bit, is {@link Cfb1}.
 */
public enum BlockMode {

    /** The cipher's own mode: every block is enciphered on its own. */
    ECB,

    /** The chaining mode: every block is enciphered under the previous one. */
    CBC,

    /** The feedback mode over the cipher's own block. */
    CFB,

    /** The feedback mode over a single bit. */
    CFB1,

    /** The feedback mode over eight bits. */
    CFB8,

    /** The feedback mode over sixty-four bits. */
    CFB64,

    /** The feedback mode over a hundred and twenty-eight bits. */
    CFB128,

    /** The output feedback mode: every block is enciphered under the vector and the vector
     * becomes the enciphered block. */
    OFB;

    /**
     * The mode a name in the request names.
     *
     * @param wireName the name the request carries
     * @return the mode, or null when the name is one this service does not make
     */
    public static BlockMode from(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (BlockMode mode : values()) {
            if (mode.name().equals(wireName)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * The names of the modes, for a message that names the ones this service makes.
     *
     * @return the names, separated by commas
     */
    public static String supported() {
        StringBuilder sb = new StringBuilder();
        for (BlockMode mode : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(mode.name());
        }
        return sb.toString();
    }

    /**
     * Whether the mode takes an initialization vector.
     *
     * <p>The ECB mode enciphers every block on its own, so it has nowhere to put one.
     *
     * @return whether the mode takes a vector
     */
    public boolean takesVector() {
        return this != ECB;
    }

    /**
     * The width of the feedback the mode names, in bits.
     *
     * @param blockBits the width of the cipher's own block, in bits
     * @return the feedback width, or zero for a mode that has no feedback
     */
    public int feedbackBits(int blockBits) {
        return switch (this) {
            case CFB -> blockBits;
            case CFB1 -> 1;
            case CFB8 -> 8;
            case CFB64 -> 64;
            case CFB128 -> 128;
            default -> 0;
        };
    }

    /**
     * The cipher of the mode, over the given engine.
     *
     * @param engine the cipher the mode runs over
     * @return the mode's cipher
     */
    BlockCipher cipher(BlockCipher engine) {
        return switch (this) {
            // The cipher's own mode is the cipher itself: there is nothing to wrap it in.
            case ECB -> engine;
            case CBC -> new CBCBlockCipher(engine);
            case CFB -> new CFBBlockCipher(engine, engine.getBlockSize() * 8);
            case CFB8 -> new CFBBlockCipher(engine, 8);
            case CFB64 -> new CFBBlockCipher(engine, 64);
            case CFB128 -> new CFBBlockCipher(engine, 128);
            case OFB -> new OFBBlockCipher(engine, engine.getBlockSize() * 8);
            case CFB1 -> new Cfb1(engine);
        };
    }
}
