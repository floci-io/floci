package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamBlockCipher;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.util.Arrays;

/**
 * The cipher feedback mode over a single bit.
 *
 * <p>The standard's feedback mode takes a segment of so many bits, and the register that
 * feeds the cipher back is shifted by the segment and takes the cipher text of the segment
 * in. A segment of one bit is a bit at a time, so the register is shifted once per bit and
 * the cipher is run once per bit, which is why the mode is not one anybody uses often.
 *
 * <p>BouncyCastle's feedback mode takes a segment of whole bytes, so the one bit segment is
 * this class. The construction is the standard's: the cipher text bit of a bit is the
 * plain text bit exclusive-ored with the first bit of the enciphered register, and the
 * register is shifted left by one bit and takes that cipher text bit in.
 */
final class Cfb1 extends StreamBlockCipher {

    /** The cipher the feedback runs over. */
    private final BlockCipher cipher;

    /** The shift register the cipher text is fed back into. */
    private final byte[] register;

    /** The enciphered register, whose first bit is the key bit of the bit. */
    private final byte[] keystream;

    /** Whether the mode enciphers or deciphers. */
    private boolean encrypting;

    Cfb1(BlockCipher cipher) {
        super(cipher);
        this.cipher = cipher;
        this.register = new byte[cipher.getBlockSize()];
        this.keystream = new byte[cipher.getBlockSize()];
    }

    @Override
    public int getBlockSize() {
        return cipher.getBlockSize();
    }

    @Override
    public String getAlgorithmName() {
        return "CFB1";
    }

    @Override
    public void init(boolean forEncryption, CipherParameters params) {
        this.encrypting = forEncryption;
        CipherParameters key = params;
        byte[] iv = null;
        if (params instanceof ParametersWithIV withIv) {
            key = withIv.getParameters();
            iv = withIv.getIV();
        }
        // The register is always enciphered, in both directions: the key stream of the mode
        // is the enciphered register, and the data's own direction is this class's business.
        cipher.init(true, key);
        Arrays.fill(register, (byte) 0);        if (iv != null) {
            System.arraycopy(iv, 0, register, 0, iv.length);
        }
    }

    @Override
    public int processBlock(byte[] in, int inOff, byte[] out, int outOff) {
        int count = getBlockSize();
        for (int i = 0; i < count; i++) {
            out[outOff + i] = calculateByte(in[inOff + i]);
        }
        return count;
    }

    @Override
    public void reset() {
        Arrays.fill(register, (byte) 0);
    }

    /**
     * The byte the mode enciphers or deciphers.
     *
     * <p>The feedback is a bit, so the byte is taken a bit at a time: the cipher runs over
     * the register once per bit, and the register is shifted and fed with the cipher text
     * bit once per bit.
     *
     * @param in the byte to encipher or decipher
     * @return the byte the mode makes of it
     */
    @Override
    protected byte calculateByte(byte in) {
        int input = in & 0xff;
        int out = 0;
        for (int bit = 7; bit >= 0; bit--) {
            // The key bit of the bit is the first bit of the enciphered register.
            cipher.processBlock(register, 0, keystream, 0);
            int keyBit = (keystream[0] & 0x80) != 0 ? 1 : 0;
            int inputBit = (input & (1 << bit)) != 0 ? 1 : 0;
            // The cipher text bit is the plain text bit under the key bit, and the register
            // is fed with the cipher text bit, which the cipher text carries in both ways.
            int cipherBit = encrypting ? inputBit ^ keyBit : inputBit;
            int plainBit = encrypting ? inputBit : cipherBit ^ keyBit;
            out |= (encrypting ? cipherBit : plainBit) << bit;
            // The register is shifted left by one bit and takes the cipher text bit in.
            shift(register);
            register[register.length - 1] |= (byte) cipherBit;
        }
        return (byte) out;
    }

    /**
     * A register shifted left by one bit, whose top bit falls off.
     *
     * @param register the register to shift
     */
    private static void shift(byte[] register) {
        for (int i = 0; i < register.length - 1; i++) {
            register[i] = (byte) ((register[i] << 1) | ((register[i + 1] & 0x80) >> 7));
        }
        register[register.length - 1] = (byte) (register[register.length - 1] << 1);
    }
}
