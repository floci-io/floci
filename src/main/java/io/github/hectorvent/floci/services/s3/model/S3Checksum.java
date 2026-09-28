package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

@RegisterForReflection
public class S3Checksum {

    private static final Pattern PART_COUNT_SUFFIX = Pattern.compile("-\\d+$");

    private static final long CRC64_NVME_POLY = 0x9a6c9329ac4bc9b5L;
    // Slicing-by-8: row n holds the CRC of each byte value followed by n zero bytes.
    private static final long[][] CRC64_TABLES = buildCrc64Tables();

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
        long crc = 0xFFFFFFFFFFFFFFFFL;
        int offset = 0;
        int blocksEnd = data.length - data.length % Long.BYTES;
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
        while (offset < data.length) {
            int idx = (int)((crc ^ data[offset]) & 0xFF);
            crc = CRC64_TABLES[0][idx] ^ (crc >>> 8);
            offset++;
        }
        crc ^= 0xFFFFFFFFFFFFFFFFL;
        byte[] bytes = new byte[]{
            (byte)(crc >> 56), (byte)(crc >> 48), (byte)(crc >> 40), (byte)(crc >> 32),
            (byte)(crc >> 24), (byte)(crc >> 16), (byte)(crc >> 8),  (byte) crc
        };
        return Base64.getEncoder().encodeToString(bytes);
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
}
