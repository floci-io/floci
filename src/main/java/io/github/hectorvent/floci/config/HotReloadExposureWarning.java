package io.github.hectorvent.floci.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Warns at startup when Lambda hot-reload is enabled with no allow-list, because any absolute host
 * path is then a legal bind-mount target. Unlike {@link NetworkExposureGuard} this only warns: the
 * combination is the documented default of the published compose file, so refusing to start would
 * break it.
 *
 * <p>Startup rather than first use: the operator who most needs the message is the one who has not
 * touched Lambda yet.
 */
@ApplicationScoped
public class HotReloadExposureWarning {

    private static final Logger LOG = Logger.getLogger(HotReloadExposureWarning.class);

    private final EmulatorConfig config;

    @Inject
    public HotReloadExposureWarning(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (hotReloadAcceptsAnyPath(config)) {
            LOG.warn("Lambda hot-reload is enabled without FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS: "
                    + "any absolute path on the Docker host can be bind-mounted into a function container");
        }
    }

    static boolean hotReloadAcceptsAnyPath(EmulatorConfig config) {
        EmulatorConfig.LambdaServiceConfig.HotReload hotReload = config.services().lambda().hotReload();
        return hotReload.enabled() && hotReload.allowedPaths().isEmpty();
    }
}
