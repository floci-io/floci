package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

@RegisterForReflection
public class S3Checksum {

    private static final long CRC64_NVME_POLY = 0x9a6c9329ac4bc9b5L;
    private static final long[] CRC64_TABLE = buildCrc64Table();

    private String checksumCRC32;
    private String checksumCRC32C;
    private String checksumCRC64NVME;
    private String checksumSHA1;
    private String checksumSHA256;
    private String checksumType;

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

    public String getChecksumType() { return checksumType; }
    public void setChecksumType(String checksumType) { this.checksumType = checksumType; }

    public boolean hasAnyValue() {
        return checksumCRC32 != null || checksumCRC32C != null || checksumCRC64NVME != null
                || checksumSHA1 != null || checksumSHA256 != null;
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
        for (byte b : data) {
            int idx = (int)((crc ^ b) & 0xFF);
            crc = CRC64_TABLE[idx] ^ (crc >>> 8);
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

    private static long[] buildCrc64Table() {
        long[] table = new long[256];
        for (int i = 0; i < 256; i++) {
            long crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ CRC64_NVME_POLY;
                } else {
                    crc >>>= 1;
                }
            }
            table[i] = crc;
        }
        return table;
    }
}
