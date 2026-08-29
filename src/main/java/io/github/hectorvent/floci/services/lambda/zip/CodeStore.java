package io.github.hectorvent.floci.services.lambda.zip;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
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

    public void delete(String accountId, String functionName) {
        deleteDirectory(getCodePath(accountId, functionName), functionName);
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
