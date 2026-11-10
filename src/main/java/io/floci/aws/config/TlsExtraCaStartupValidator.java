package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * Refuses to start when {@code floci.tls.extra-ca-path} names a file Floci's own outbound HTTPS
 * clients could not trust anything from: a missing or unreadable file, or one that holds no
 * certificate. Checked whether or not TLS is enabled, as the extra CAs apply in both modes, and
 * at startup rather than on the first outbound call, so a mistyped path is reported at once
 * instead of surfacing later as a refused handshake. A private key kept in the file is ignored.
 */
@ApplicationScoped
public class TlsExtraCaStartupValidator {

    private static final Logger LOG = Logger.getLogger(TlsExtraCaStartupValidator.class);

    private final EmulatorConfig config;

    @Inject
    public TlsExtraCaStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        Optional<Path> extraCaFile = ContainerCaBundle.extraCaFile(config);
        if (extraCaFile.isPresent()) {
            validate(extraCaFile.get());
            LOG.infov("TLS: outbound HTTPS from Floci also trusts the CAs in {0}", extraCaFile.get());
        }
    }

    /**
     * Checks that {@code file} can be read and holds at least one certificate.
     *
     * @throws IllegalStateException when it cannot, naming the file and the reason
     */
    private static void validate(Path file) {
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("TLS extra CA file not found or not readable: " + file);
        }
        try {
            ContainerCaBundle.certificates(file);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("TLS extra CA file cannot be used: " + file + ": " + e.getMessage(), e);
        }
    }
}
