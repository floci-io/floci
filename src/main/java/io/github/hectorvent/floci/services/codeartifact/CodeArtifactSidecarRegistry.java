package io.github.hectorvent.floci.services.codeartifact;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a CodeArtifact package format to the {@link RepositorySidecarManager} that serves it.
 * {@link CodeArtifactService} consults this to clean up a deleted repository's sidecar containers
 * without knowing which formats exist or which sidecar backs any of them.
 */
@ApplicationScoped
public class CodeArtifactSidecarRegistry {

    private final Map<String, RepositorySidecarManager> byFormat = new HashMap<>();

    @Inject
    public CodeArtifactSidecarRegistry(Instance<RepositorySidecarManager> managers) {
        managers.forEach(this::register);
    }

    /** Factory constructor: build a registry from an explicit list, bypassing CDI (tests). */
    public CodeArtifactSidecarRegistry(Collection<RepositorySidecarManager> managers) {
        managers.forEach(this::register);
    }

    private void register(RepositorySidecarManager manager) {
        RepositorySidecarManager existing = byFormat.put(manager.format(), manager);
        if (existing != null) {
            throw new IllegalStateException("Duplicate CodeArtifact sidecar manager for format "
                    + manager.format() + ": " + existing.getClass().getSimpleName() + " and "
                    + manager.getClass().getSimpleName());
        }
    }

    public Optional<RepositorySidecarManager> forFormat(String format) {
        return Optional.ofNullable(byFormat.get(format));
    }
}
