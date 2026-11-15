package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.auth.SigV4RequestValidator;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passes an asset upload through while computing every {@code CodeArtifactService
 * .ASSET_HASH_ALGORITHMS} digest and its size, so a large asset can be staged to disk without ever
 * being held whole in a JVM array. Once more than {@code maxSize} bytes have been read, throws
 * {@link AssetTooLargeException} rather than the digest/size this stream would otherwise report;
 * each caller maps that to whatever response shape it already uses for an over-quota asset.
 */
final class AssetDigestingInputStream extends FilterInputStream {

    private static final List<String> ALGORITHMS = List.of("MD5", "SHA-1", "SHA-256", "SHA-512");

    /** Thrown once a read would push the running size past the cap this stream was given. */
    static final class AssetTooLargeException extends RuntimeException {
        AssetTooLargeException(long maxSize) {
            super("Asset exceeded the maximum allowed size of " + maxSize + " bytes.");
        }
    }

    private final Map<String, MessageDigest> digests = new LinkedHashMap<>();
    private final long maxSize;
    private long size;
    private Map<String, String> hashesHex;

    AssetDigestingInputStream(InputStream in, long maxSize) {
        super(in);
        this.maxSize = maxSize;
        for (String algorithm : ALGORITHMS) {
            digests.put(algorithm, newDigest(algorithm));
        }
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support " + algorithm, e);
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

    /**
     * Every external-connection client's own streamed fetch shares this: copy {@code in} to
     * {@code destination}, capped at {@code maxSize} during the copy itself rather than checked
     * only afterward, since the real public registry's own file size is otherwise unbounded.
     * Returns the real byte count copied.
     */
    static long copyCapped(InputStream in, Path destination, long maxSize) throws IOException {
        AssetDigestingInputStream digesting = new AssetDigestingInputStream(in, maxSize);
        Files.copy(digesting, destination, StandardCopyOption.REPLACE_EXISTING);
        return digesting.size();
    }

    /**
     * Every {@code ASSET_HASH_ALGORITHMS} digest of everything read so far, as lowercase hex.
     * Finalizes each {@link MessageDigest}, so reading on afterward is not supported; the result
     * is cached once asked for, since asking twice would otherwise re-finalize an already-reset
     * digest and return the wrong value.
     */
    Map<String, String> hashesHex() {
        if (hashesHex == null) {
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Map.Entry<String, MessageDigest> entry : digests.entrySet()) {
                hashes.put(entry.getKey(), SigV4RequestValidator.hexEncode(entry.getValue().digest()));
            }
            hashesHex = hashes;
        }
        return hashesHex;
    }

    private void update(byte[] buffer, int offset, int length) {
        size += length;
        if (size > maxSize) {
            throw new AssetTooLargeException(maxSize);
        }
        for (MessageDigest digest : digests.values()) {
            digest.update(buffer, offset, length);
        }
    }
}
