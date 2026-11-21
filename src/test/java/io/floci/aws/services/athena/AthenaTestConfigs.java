package io.floci.aws.services.athena;

import io.floci.aws.config.EmulatorConfig;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Configurations Athena tests construct {@link AthenaService} with. */
final class AthenaTestConfigs {

    private AthenaTestConfigs() {
    }

    /** A configuration under which a query is handed to the query engine rather than answered at once. */
    static EmulatorConfig realQueries() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.athena()).thenReturn(athena);
        when(athena.mock()).thenReturn(false);
        return config;
    }
}
