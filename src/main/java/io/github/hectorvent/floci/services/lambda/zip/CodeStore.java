package io.github.hectorvent.floci.services.lambda.zip;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Manages on-disk locations of extracted Lambda function code.
 * Each function gets its own directory under {@code <codePath>/<accountId>/}, mirroring
 * the account-prefixed S3 key {@code LambdaService.codeObjectKey} uses for the same
 * deployment package. Without the account segment two accounts' same-named functions in
 * one region share a single extraction directory and overwrite each other's code.
 */
@ApplicationScoped
public class CodeStore {

    private static final Logger LOG = Logger.getLogger(CodeStore.class);

    /**
     * Separates a function's versions directory from any function's own code directory. Deliberately
     * outside {@link #sanitizeName}'s output character set, so the two namespaces cannot overlap.
     */
    private static final String VERSIONS_DIR_SUFFIX = "@versions";

    private final Path baseDir;

    @Inject
    public CodeStore(EmulatorConfig config) {
        this.baseDir = Path.of(config.services().lambda().codePath());
    }

    public CodeStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path getCodePath(String accountId, String functionName) {
        return baseDir.resolve(sanitizeName(accountId)).resolve(sanitizeName(functionName));
    }

    /**
     * The pre-account-scoped path a function's code would have extracted to before this class
     * added an account segment. Retained so {@link #delete} and code re-extraction can reclaim a
     * directory left behind by a function created before that migration.
     */
    public Path getLegacyCodePath(String functionName) {
        return baseDir.resolve(sanitizeName(functionName));
    }

    /**
     * Where a function's published-version code lives: one directory, holding a subdirectory per
     * published version.
     *
     * <p>A sibling of the {@code $LATEST} directory rather than a child of it. Extraction replaces
     * {@link #getCodePath}'s directory wholesale, so anything nested inside would be deleted on the
     * next deploy, which is the very thing a version's copy exists to survive.
     *
     * <p>The {@link #VERSIONS_DIR_SUFFIX} is what keeps that sibling from colliding with a real
     * function. {@link #sanitizeName} maps every name into {@code [a-zA-Z0-9_.-]}, so no function
     * can produce a directory name containing {@code @}, whatever it is called. Floci does not
     * restrict the character set of {@code FunctionName} today, only that it is non-blank, so a
     * plainer {@code <name>.v<n>} sibling carried no such guarantee: a function genuinely named
     * {@code foo.v1} owns the exact directory {@code foo}'s version 1 would otherwise claim, and
     * deleting either one silently corrupts the other.
     */
    public Path getVersionsPath(String accountId, String functionName) {
        return baseDir.resolve(sanitizeName(accountId))
                .resolve(sanitizeName(functionName) + VERSIONS_DIR_SUFFIX);
    }

    /** Where one published version's own copy of the code lives. */
    public Path getVersionCodePath(String accountId, String functionName, String version) {
        return getVersionsPath(accountId, functionName).resolve(sanitizeName(version));
    }

    /**
     * Copies a function's current code into its own directory for {@code version}, replacing any
     * directory already there. Returns null when there is nothing to copy, which is the case for
     * image-backed and hot-reload functions.
     */
    public Path copyForVersion(String accountId, String functionName, String version, Path source)
            throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return null;
        }
        Path target = getVersionCodePath(accountId, functionName, version);
        deleteDirectory(target, functionName);
        try (var walk = Files.walk(source)) {
            for (Path from : walk.toList()) {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    /** Removes every published version's code for a function. */
    public void deleteVersions(String accountId, String functionName) {
        deleteDirectory(getVersionsPath(accountId, functionName), functionName);
    }

    /**
     * Removes one published version's code, leaving the function's other versions and
     * {@code $LATEST} in place. Deleting a single version otherwise left its package on disk for
     * as long as the data directory lived, so a repeated publish/delete cycle grew without bound.
     */
    public void deleteVersion(String accountId, String functionName, String version) {
        deleteDirectory(getVersionCodePath(accountId, functionName, version), functionName);
    }

    public void delete(String accountId, String functionName) {
        deleteDirectory(getCodePath(accountId, functionName), functionName);
        deleteVersions(accountId, functionName);
    }

    /**
     * Best-effort removal of a function's pre-account-scoped directory. Deliberately NOT called
     * automatically from {@link #delete}: the pre-account-scoped layout gave every account's
     * same-named function the exact same directory, so it is only safe to remove once the caller
     * (see {@code LambdaService}) has confirmed no other account's function still references it.
     */
    public void deleteLegacy(String functionName) {
        deleteDirectory(getLegacyCodePath(functionName), functionName);
    }

    private void deleteDirectory(Path path, String functionName) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOG.warnv("Failed to delete {0}: {1}", p, e.getMessage());
                        }
                    });
            LOG.debugv("Deleted code for function: {0}", functionName);
        } catch (IOException e) {
            LOG.warnv("Failed to delete code directory for {0}: {1}", functionName, e.getMessage());
        }
    }

    public boolean exists(String accountId, String functionName) {
        Path codePath = getCodePath(accountId, functionName);
        if (!Files.exists(codePath)) {
            return false;
        }
        try (var listing = Files.list(codePath)) {
            return listing.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Replaces disallowed characters, then collapses any segment that consists entirely of dots
     * ({@code "."}, {@code ".."}, ...) to a safe placeholder: dots alone survive the character
     * replacement above but are special path segments that {@link Path#resolve} would otherwise
     * follow outside {@link #baseDir}.
     */
    private String sanitizeName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        if (sanitized.isEmpty() || sanitized.chars().allMatch(c -> c == '.')) {
            return "_";
        }
        return sanitized;
    }
}
