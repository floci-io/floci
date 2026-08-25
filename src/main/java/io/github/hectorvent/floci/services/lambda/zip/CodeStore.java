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

    public void delete(String accountId, String functionName) {
        Path codePath = getCodePath(accountId, functionName);
        if (!Files.exists(codePath)) {
            return;
        }
        try {
            Files.walk(codePath)
                    .sorted(Comparator.reverseOrder())
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
        try {
            return Files.exists(codePath) && Files.list(codePath).findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }
}
