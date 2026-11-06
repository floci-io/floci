package io.floci.aws.services.lambda.zip;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/**
 * A .zip package on its way to a function or layer: the bytes when they are already in memory,
 * or a copy staged on disk so a package read from S3 is never held in the heap.
 */
public final class DeploymentPackage implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DeploymentPackage.class);

    private final byte[] bytes;
    private final Path file;
    private final long size;
    private final String sha256;

    private DeploymentPackage(byte[] bytes, Path file, long size, String sha256) {
        this.bytes = bytes;
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
    }

    public static DeploymentPackage of(byte[] bytes) {
        return new DeploymentPackage(bytes, null, bytes.length,
                Base64.getEncoder().encodeToString(Digests.sha256(bytes)));
    }

    /**
     * Takes over {@code object} and closes it. S3 in memory mode already holds the bytes, and a
     * tasks bucket copy put from them shares them, so they are kept as they are. Otherwise the body
     * is staged in {@code stagingDir}, unless it is over the compressed limit extraction enforces.
     */
    public static DeploymentPackage read(S3Service.ObjectRead object, Path stagingDir) throws IOException {
        try (object) {
            byte[] held = object.object().getData();
            if (held != null) {
                return of(held);
            }
            long declared = object.object().getSize();
            if (ZipExtractor.S3_MAX_COMPRESSED_BYTES < declared) {
                return new DeploymentPackage(null, null, declared, null);
            }
            Files.createDirectories(stagingDir);
            Path staged = Files.createTempFile(stagingDir, ".floci-staging-", ".zip");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size;
                try (OutputStream out = new DigestOutputStream(Files.newOutputStream(staged), digest)) {
                    size = object.body().transferTo(out);
                }
                return new DeploymentPackage(null, staged, size, Base64.getEncoder().encodeToString(digest.digest()));
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(staged);
                throw e;
            } catch (NoSuchAlgorithmException e) {
                Files.deleteIfExists(staged);
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
    }

    public long size() {
        return size;
    }

    /** The base64 SHA-256 of the package, as Lambda reports it in {@code CodeSha256}. */
    public String sha256() {
        return sha256;
    }

    public void extractTo(ZipExtractor extractor, Path targetDir, int maxEntries, Runnable beforeInstall)
            throws IOException {
        if (file != null) {
            extractor.extractTo(file, targetDir, maxEntries, beforeInstall);
        } else if (bytes != null) {
            extractor.extractTo(bytes, targetDir, maxEntries, beforeInstall);
        } else {
            // Over the compressed limit, so never staged: rejected with the message a staged copy would get.
            ZipExtractor.checkLimits(maxEntries, size);
            throw new IllegalStateException("A package of " + size + " bytes passed the compressed limit");
        }
    }

    public void putObject(S3Service s3Service, String bucket, String key, String contentType) {
        if (file == null) {
            s3Service.putObject(bucket, key, bytes, contentType, Map.of());
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            s3Service.putObjectFromStream(bucket, key, in, contentType, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the staged package " + file, e);
        }
    }

    @Override
    public void close() {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warnv("Could not delete the staged package {0}: {1}", file, e.getMessage());
        }
    }
}
