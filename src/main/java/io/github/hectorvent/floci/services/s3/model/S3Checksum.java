package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.Checksum;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

@RegisterForReflection
public class S3Checksum {

    private static final Pattern PART_COUNT_SUFFIX = Pattern.compile("-\\d+$");

    private static final long CRC64_NVME_POLY = 0x9a6c9329ac4bc9b5L;
    // Slicing-by-8: row n holds the CRC of each byte value followed by n zero bytes.
    private static final long[][] CRC64_TABLES = buildCrc64Tables();

    private static final CrcCombiner CRC32_COMBINER = new CrcCombiner(0xEDB88320L, Integer.SIZE);
    private static final CrcCombiner CRC32C_COMBINER = new CrcCombiner(0x82F63B78L, Integer.SIZE);
    private static final CrcCombiner CRC64_NVME_COMBINER = new CrcCombiner(CRC64_NVME_POLY, Long.SIZE);

    private String checksumCRC32;
    private String checksumCRC32C;
    private String checksumCRC64NVME;
    private String checksumSHA1;
    private String checksumSHA256;
    private ChecksumType checksumType;

    public String getChecksumCRC32() { return checksumCRC32; }
    public void setChecksumCRC32(String checksumCRC32) { this.checksumCRC32 = checksumCRC32; }

    public String getChecksumCRC32C() { return checksumCRC32C; }
    public void setChecksumCRC32C(String checksumCRC32C) { this.checksumCRC32C = checksumCRC32C; }

    public String getChecksumCRC64NVME() { return checksumCRC64NVME; }
    public void setChecksumCRC64NVME(String checksumCRC64NVME) { this.checksumCRC64NVME = checksumCRC64NVME; }

    public String getChecksumSHA1() { return checksumSHA1; }
    public void setChecksumSHA1(String checksumSHA1) { this.checksumSHA1 = checksumSHA1; }

    public String getChecksumSHA256() { return checksumSHA256; }
    public void setChecksumSHA256(String checksumSHA256) { this.checksumSHA256 = checksumSHA256; }

    public ChecksumType getChecksumType() { return checksumType; }
    public void setChecksumType(ChecksumType checksumType) { this.checksumType = checksumType; }

    public boolean hasAnyValue() {
        return checksumCRC32 != null || checksumCRC32C != null || checksumCRC64NVME != null
                || checksumSHA1 != null || checksumSHA256 != null;
    }

    public String valueFor(ChecksumAlgorithm algorithm) {
        return switch (algorithm) {
            case CRC32 -> checksumCRC32;
            case CRC32C -> checksumCRC32C;
            case CRC64NVME -> checksumCRC64NVME;
            case SHA1 -> checksumSHA1;
            case SHA256 -> checksumSHA256;
        };
    }

    public void setValueFor(ChecksumAlgorithm algorithm, String value) {
        switch (algorithm) {
            case CRC32 -> checksumCRC32 = value;
            case CRC32C -> checksumCRC32C = value;
            case CRC64NVME -> checksumCRC64NVME = value;
            case SHA1 -> checksumSHA1 = value;
            case SHA256 -> checksumSHA256 = value;
        }
    }

    /** The algorithm whose value is set, or {@code null} when the checksum is empty. */
    public ChecksumAlgorithm algorithm() {
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            if (valueFor(algorithm) != null) {
                return algorithm;
            }
        }
        return null;
    }

    /** Checksum of {@code data} with no type, as stored for a part. S3 uses CRC64NVME when no algorithm was declared. */
    public static S3Checksum of(ChecksumAlgorithm algorithm, byte[] data) {
        ChecksumAlgorithm effective = algorithm != null ? algorithm : ChecksumAlgorithm.CRC64NVME;
        S3Checksum checksum = new S3Checksum();
        checksum.setValueFor(effective, effective.compute(data));
        return checksum;
    }

    public static S3Checksum fullObject(ChecksumAlgorithm algorithm, byte[] data) {
        S3Checksum checksum = of(algorithm, data);
        checksum.setChecksumType(ChecksumType.FULL_OBJECT);
        return checksum;
    }

    /**
     * Full-object checksum of a multipart object, combined from each part's CRC and size so the
     * object's bytes are never read. S3 linearizes it the same way, which is why it offers full-object
     * checksums on multipart uploads only for the CRC algorithms.
     */
    public static S3Checksum fullObject(ChecksumAlgorithm algorithm, List<Part> parts) {
        ChecksumAlgorithm effective = algorithm != null ? algorithm : ChecksumAlgorithm.CRC64NVME;
        CrcCombiner combiner = switch (effective) {
            case CRC32 -> CRC32_COMBINER;
            case CRC32C -> CRC32C_COMBINER;
            case CRC64NVME -> CRC64_NVME_COMBINER;
            case SHA1, SHA256 -> throw new IllegalArgumentException(
                    effective.wireValue() + " has no full-object multipart checksum");
        };
        // The CRC of no bytes is zero for all three algorithms, so zero seeds the fold.
        long crc = 0;
        for (Part part : parts) {
            crc = combiner.combine(crc, combiner.decode(part.getChecksum().valueFor(effective)), part.getSize());
        }
        S3Checksum checksum = new S3Checksum();
        checksum.setValueFor(effective, combiner.encode(crc));
        checksum.setChecksumType(ChecksumType.FULL_OBJECT);
        return checksum;
    }

    public static S3Checksum composite(ChecksumAlgorithm algorithm, List<String> partChecksums) {
        S3Checksum checksum = new S3Checksum();
        checksum.setValueFor(algorithm, algorithm.composite(partChecksums));
        checksum.setChecksumType(ChecksumType.COMPOSITE);
        return checksum;
    }

    public S3Checksum copy() {
        S3Checksum copy = new S3Checksum();
        copy.checksumCRC32 = checksumCRC32;
        copy.checksumCRC32C = checksumCRC32C;
        copy.checksumCRC64NVME = checksumCRC64NVME;
        copy.checksumSHA1 = checksumSHA1;
        copy.checksumSHA256 = checksumSHA256;
        copy.checksumType = checksumType;
        return copy;
    }

    /** The checksum as GetObjectAttributes reports it: a composite value loses the {@code -N} suffix HeadObject carries. */
    public S3Checksum forObjectAttributes() {
        S3Checksum copy = copy();
        if (copy.checksumType == ChecksumType.COMPOSITE) {
            ChecksumAlgorithm algorithm = copy.algorithm();
            copy.setValueFor(algorithm, withoutPartCount(copy.valueFor(algorithm)));
        }
        return copy;
    }

    public static String withoutPartCount(String checksum) {
        return checksum == null ? null : PART_COUNT_SUFFIX.matcher(checksum).replaceFirst("");
    }

    public static String crc32Base64(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        long value = crc.getValue();
        byte[] bytes = new byte[]{
            (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte) value
        };
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String crc32cBase64(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        long value = crc.getValue();
        byte[] bytes = new byte[]{
            (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte) value
        };
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String crc64NvmeBase64(byte[] data) {
        return base64(~crc64NvmeUpdate(~0L, data, 0, data.length), Long.BYTES);
    }

    /** Runs {@code length} bytes through the CRC64NVME register; the final value is its complement. */
    private static long crc64NvmeUpdate(long register, byte[] data, int start, int length) {
        long crc = register;
        int offset = start;
        int end = start + length;
        int blocksEnd = end - length % Long.BYTES;
        while (offset < blocksEnd) {
            crc ^= (data[offset] & 0xFFL)
                    | (data[offset + 1] & 0xFFL) << 8
                    | (data[offset + 2] & 0xFFL) << 16
                    | (data[offset + 3] & 0xFFL) << 24
                    | (data[offset + 4] & 0xFFL) << 32
                    | (data[offset + 5] & 0xFFL) << 40
                    | (data[offset + 6] & 0xFFL) << 48
                    | (data[offset + 7] & 0xFFL) << 56;
            crc = CRC64_TABLES[7][(int)(crc & 0xFF)]
                    ^ CRC64_TABLES[6][(int)((crc >>> 8) & 0xFF)]
                    ^ CRC64_TABLES[5][(int)((crc >>> 16) & 0xFF)]
                    ^ CRC64_TABLES[4][(int)((crc >>> 24) & 0xFF)]
                    ^ CRC64_TABLES[3][(int)((crc >>> 32) & 0xFF)]
                    ^ CRC64_TABLES[2][(int)((crc >>> 40) & 0xFF)]
                    ^ CRC64_TABLES[1][(int)((crc >>> 48) & 0xFF)]
                    ^ CRC64_TABLES[0][(int)(crc >>> 56)];
            offset += Long.BYTES;
        }
        while (offset < end) {
            int idx = (int)((crc ^ data[offset]) & 0xFF);
            crc = CRC64_TABLES[0][idx] ^ (crc >>> 8);
            offset++;
        }
        return crc;
    }

    private static String base64(long value, int byteCount) {
        byte[] bytes = new byte[byteCount];
        for (int i = byteCount - 1; i >= 0; i--) {
            bytes[i] = (byte) (value >>> ((byteCount - 1 - i) * Byte.SIZE));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** A {@link Calculator} for {@code algorithm}, CRC64NVME when no algorithm was declared, as {@link #of}. */
    public static Calculator calculator(ChecksumAlgorithm algorithm) {
        return new Calculator(algorithm != null ? algorithm : ChecksumAlgorithm.CRC64NVME);
    }

    /**
     * Computes a checksum over data fed in pieces, for a body too large to hold in one array. The
     * value is the one {@link ChecksumAlgorithm#compute} gives for the same bytes in one piece.
     */
    public static final class Calculator {

        private final ChecksumAlgorithm algorithm;
        private final Checksum crc;
        private final MessageDigest digest;
        private long crc64Register = ~0L;

        private Calculator(ChecksumAlgorithm algorithm) {
            this.algorithm = algorithm;
            this.crc = switch (algorithm) {
                case CRC32 -> new CRC32();
                case CRC32C -> new CRC32C();
                case CRC64NVME, SHA1, SHA256 -> null;
            };
            this.digest = switch (algorithm) {
                case SHA1 -> newDigest("SHA-1");
                case SHA256 -> newDigest("SHA-256");
                case CRC32, CRC32C, CRC64NVME -> null;
            };
        }

        public ChecksumAlgorithm algorithm() {
            return algorithm;
        }

        public void update(byte[] data, int offset, int length) {
            switch (algorithm) {
                case CRC32, CRC32C -> crc.update(data, offset, length);
                case CRC64NVME -> crc64Register = crc64NvmeUpdate(crc64Register, data, offset, length);
                case SHA1, SHA256 -> digest.update(data, offset, length);
            }
        }

        /** The Base64 checksum of everything fed so far. */
        public String value() {
            return switch (algorithm) {
                case CRC32, CRC32C -> base64(crc.getValue(), Integer.BYTES);
                case CRC64NVME -> base64(~crc64Register, Long.BYTES);
                case SHA1, SHA256 -> Base64.getEncoder().encodeToString(digest.digest());
            };
        }

        /** The value as a full-object checksum, the kind a single-part object carries. */
        public S3Checksum fullObject() {
            S3Checksum checksum = new S3Checksum();
            checksum.setValueFor(algorithm, value());
            checksum.setChecksumType(ChecksumType.FULL_OBJECT);
            return checksum;
        }

        private static MessageDigest newDigest(String name) {
            try {
                return MessageDigest.getInstance(name);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Missing digest algorithm: " + name, e);
            }
        }
    }

    public static String sha256Base64(byte[] data) {
        return digestBase64("SHA-256", data);
    }

    public static String sha1Base64(byte[] data) {
        return digestBase64("SHA-1", data);
    }

    private static String digestBase64(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return Base64.getEncoder().encodeToString(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing digest algorithm: " + algorithm, e);
        }
    }

    private static long[][] buildCrc64Tables() {
        long[][] tables = new long[Long.BYTES][256];
        for (int i = 0; i < 256; i++) {
            long crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ CRC64_NVME_POLY;
                } else {
                    crc >>>= 1;
                }
            }
            tables[0][i] = crc;
        }
        for (int i = 0; i < 256; i++) {
            long crc = tables[0][i];
            for (int n = 1; n < Long.BYTES; n++) {
                crc = tables[0][(int)(crc & 0xFF)] ^ (crc >>> 8);
                tables[n][i] = crc;
            }
        }
        return tables;
    }

    /**
     * Joins the CRCs of two adjacent byte ranges, as zlib's {@code crc32_combine} does, for a reflected
     * CRC whose initial value and final XOR are both all ones (CRC32, CRC32C and CRC64NVME all are).
     * With those parameters crc(A + B) = crc(A) * x^(8 * len(B)) mod P xor crc(B), so the second range's
     * length is all that is needed besides the two CRCs. In the reflected form the top bit is x^0.
     */
    private static final class CrcCombiner {

        private final long polynomial;
        private final int width;
        private final long one;
        // Entry k is x^(2^k) mod P. Lengths are counted in bytes, so x^(8n) starts at k = 3.
        private final long[] xToPowerOfTwo = new long[Long.SIZE + 3];

        CrcCombiner(long polynomial, int width) {
            this.polynomial = polynomial;
            this.width = width;
            this.one = 1L << (width - 1);
            long power = one >>> 1;
            for (int k = 0; k < xToPowerOfTwo.length; k++) {
                xToPowerOfTwo[k] = power;
                power = multiply(power, power);
            }
        }

        long combine(long first, long second, long secondLength) {
            long shift = one;
            int k = 3;
            for (long remaining = secondLength; remaining != 0; remaining >>>= 1) {
                if ((remaining & 1) != 0) {
                    shift = multiply(xToPowerOfTwo[k], shift);
                }
                k++;
            }
            return multiply(shift, first) ^ second;
        }

        long decode(String base64) {
            long crc = 0;
            for (byte b : Base64.getDecoder().decode(base64)) {
                crc = (crc << Byte.SIZE) | (b & 0xFF);
            }
            return crc;
        }

        String encode(long crc) {
            byte[] bytes = new byte[width / Byte.SIZE];
            for (int i = bytes.length - 1; i >= 0; i--) {
                bytes[i] = (byte) (crc >>> ((bytes.length - 1 - i) * Byte.SIZE));
            }
            return Base64.getEncoder().encodeToString(bytes);
        }

        private long multiply(long a, long b) {
            long product = 0;
            for (long bit = one; bit != 0; bit >>>= 1) {
                if ((a & bit) != 0) {
                    product ^= b;
                }
                b = (b & 1) != 0 ? (b >>> 1) ^ polynomial : b >>> 1;
            }
            return product;
        }
    }
}
