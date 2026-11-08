package io.floci.aws.services.lambdaweb;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.FileTrees;
import io.floci.aws.services.lambdaweb.model.WebFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The code package each revision copied from S3 when it was created. AWS prepares its own copy at
 * creation, so a later write or delete in the bucket does not change what a revision runs.
 */
@ApplicationScoped
public class LambdaWebCode {

    private static final Logger LOG = Logger.getLogger(LambdaWebCode.class);

    /** The {@code @} keeps this tree apart from the Lambda function directories, whose names never have one. */
    private static final String ROOT = "@web-functions";

    /** AWS refuses a larger object and fails a revision whose files add up to more. */
    static final long MAX_BYTES = 512L * 1024 * 1024;

    private final Path baseDir;

    @Inject
    public LambdaWebCode(EmulatorConfig config) {
        this(Path.of(config.services().lambda().codePath()).resolve(ROOT));
    }

    LambdaWebCode(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** Returns the number of bytes kept. */
    long store(String accountId, String region, String functionName, String revisionId, InputStream zip) {
        Path file = file(accountId, region, functionName, revisionId);
        try {
            Files.createDirectories(file.getParent());
            return Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteRevision(accountId, region, functionName, revisionId);
            throw new UncheckedIOException("Could not keep the code of " + functionName + " revision " + revisionId, e);
        }
    }

    /** The unsigned sum of the sizes the zip declares for its files, or empty when the code is not a zip. */
    OptionalLong unzippedSize(String accountId, String region, String functionName, String revisionId) {
        try (ZipFile zip = new ZipFile(file(accountId, region, functionName, revisionId).toFile())) {
            return OptionalLong.of(zip.stream().mapToLong(ZipEntry::getSize).reduce(0, LambdaWebCode::addSaturating));
        } catch (ZipException e) {
            return OptionalLong.empty();
        } catch (IOException e) {
            deleteRevision(accountId, region, functionName, revisionId);
            throw new UncheckedIOException("Could not read the code of " + functionName + " revision " + revisionId, e);
        }
    }

    /** AWS adds the sizes as unsigned 64-bit numbers and stops at the largest one. */
    private static long addSaturating(long total, long size) {
        long sum = total + size;
        return Long.compareUnsigned(sum, total) < 0 ? -1L : sum;
    }

    void deleteRevision(String accountId, String region, String functionName, String revisionId) {
        try {
            Files.deleteIfExists(file(accountId, region, functionName, revisionId));
        } catch (IOException e) {
            LOG.warnv("Could not delete the code of {0} revision {1}: {2}", functionName, revisionId, e.getMessage());
        }
    }

    void deleteFunction(String accountId, String region, String functionName) {
        deleteTree(functionDir(accountId, region, functionName));
    }

    void clear() {
        deleteTree(baseDir);
    }

    /**
     * Removes every copy no stored revision names: those a restart in memory mode orphaned, and
     * any a failed delete left behind.
     */
    void retainOnly(List<WebFunction> functions) {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        Set<Path> kept = new HashSet<>();
        for (WebFunction fn : functions) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.functionArn);
            for (String revisionId : fn.revisions.keySet()) {
                kept.add(file(arn.accountId(), arn.region(), fn.functionName, revisionId));
            }
        }
        try (Stream<Path> walk = Files.walk(baseDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> Files.isDirectory(path) ? isEmpty(path) : !kept.contains(path))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOG.warnv("Could not delete {0}: {1}", path, e.getMessage());
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            LOG.warnv("Could not clean {0}: {1}", baseDir, e.getMessage());
        }
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    Path file(String accountId, String region, String functionName, String revisionId) {
        return functionDir(accountId, region, functionName).resolve(segment(revisionId) + ".zip");
    }

    private Path functionDir(String accountId, String region, String functionName) {
        return baseDir.resolve(segment(accountId)).resolve(segment(region)).resolve(segment(functionName));
    }

    /**
     * Escapes every byte but letters, digits, {@code -} and {@code _}, so two regions never share a
     * directory and {@code .} or {@code ..} cannot navigate.
     */
    private static String segment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if (('a' <= b && b <= 'z') || ('A' <= b && b <= 'Z') || ('0' <= b && b <= '9') || b == '-' || b == '_') {
                out.append((char) b);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static void deleteTree(Path root) {
        try {
            FileTrees.delete(root);
        } catch (IOException e) {
            LOG.warnv("Could not delete {0}: {1}", root, e.getMessage());
        }
    }
}
