package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Passes an upload body through while computing its MD5, the checksums it is checked against or
 * stored with, and its size, so a body can be validated and stored without being held whole.
 */
final class DigestingInputStream extends FilterInputStream {

    private final MessageDigest md5;
    private final Map<ChecksumAlgorithm, S3Checksum.Calculator> calculators = new EnumMap<>(ChecksumAlgorithm.class);
    private final Map<ChecksumAlgorithm, String> checksums = new EnumMap<>(ChecksumAlgorithm.class);
    private byte[] md5Digest;
    private long size;

    DigestingInputStream(InputStream in, Collection<ChecksumAlgorithm> algorithms) {
        super(in);
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm is not available", e);
        }
        for (ChecksumAlgorithm algorithm : algorithms) {
            calculators.put(algorithm, S3Checksum.calculator(algorithm));
        }
    }

    @Override
    public int read() throws IOException {
        int next = super.read();
        if (next >= 0) {
            byte[] single = {(byte) next};
            update(single, 0, 1);
        }
        return next;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            update(buffer, offset, read);
        }
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        // Skipped bytes would be missing from the digests, so they are read instead.
        byte[] buffer = new byte[8192];
        long skipped = 0;
        while (skipped < n) {
            int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    long size() {
        return size;
    }

    /** The MD5 of everything read; reading on after asking for it is not supported. */
    byte[] md5() {
        if (md5Digest == null) {
            md5Digest = md5.digest();
        }
        return md5Digest.clone();
    }

    /** The MD5 as an S3 ETag, quoted lowercase hex. */
    String eTag() {
        return "\"" + HexFormat.of().formatHex(md5()) + "\"";
    }

    /**
     * The Base64 checksum of everything read, for an algorithm the stream was created with. Like
     * {@link #md5}, it is final once asked for.
     */
    String checksum(ChecksumAlgorithm algorithm) {
        S3Checksum.Calculator calculator = calculators.get(algorithm);
        if (calculator == null) {
            throw new IllegalArgumentException(algorithm + " was not computed for this body");
        }
        return checksums.computeIfAbsent(algorithm, ignored -> calculator.value());
    }

    private void update(byte[] buffer, int offset, int length) {
        md5.update(buffer, offset, length);
        for (S3Checksum.Calculator calculator : calculators.values()) {
            calculator.update(buffer, offset, length);
        }
        size += length;
    }
}
