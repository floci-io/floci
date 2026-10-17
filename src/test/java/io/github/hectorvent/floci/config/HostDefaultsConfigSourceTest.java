package io.github.hectorvent.floci.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

class HostDefaultsConfigSourceTest {

    @Test
    void emptyOutsideANativeHostRun() {
        HostDefaultsConfigSource source = new HostDefaultsConfigSource(false);
        assertThat(source.getProperties(), aMapWithSize(0));
        assertThat(source.getValue(HostDefaultsConfigSource.PERSISTENT_PATH), nullValue());
    }

    @Test
    void jvmConstructorIsEmpty() {
        assertThat(new HostDefaultsConfigSource().getProperties(), aMapWithSize(0));
    }

    @Test
    void nativeHostRunSetsBothPathsToTheSameDirectory() {
        HostDefaultsConfigSource source = new HostDefaultsConfigSource(true);
        SmallRyeConfig config = configWith(source);
        String dir = source.getValue(HostDefaultsConfigSource.PERSISTENT_PATH);
        assertThat(config.getValue(HostDefaultsConfigSource.PERSISTENT_PATH, String.class), equalTo(dir));
        assertThat(config.getValue(HostDefaultsConfigSource.HOST_PERSISTENT_PATH, String.class), equalTo(dir));
    }

    @Test
    void hostPathFollowsAPersistentPathTheUserConfigures() {
        SmallRyeConfig config = configWith(new HostDefaultsConfigSource(true),
                new PropertiesConfigSource(Map.of(HostDefaultsConfigSource.PERSISTENT_PATH, "/srv/floci"),
                        "config/application.yml", 265));
        assertThat(config.getValue(HostDefaultsConfigSource.HOST_PERSISTENT_PATH, String.class),
                equalTo("/srv/floci"));
    }

    @Test
    void anExplicitHostPathStillWins() {
        SmallRyeConfig config = configWith(new HostDefaultsConfigSource(true),
                new PropertiesConfigSource(Map.of(HostDefaultsConfigSource.PERSISTENT_PATH, "/srv/floci",
                        HostDefaultsConfigSource.HOST_PERSISTENT_PATH, "/mnt/floci"), "env", 300));
        assertThat(config.getValue(HostDefaultsConfigSource.HOST_PERSISTENT_PATH, String.class),
                equalTo("/mnt/floci"));
    }

    @Test
    void nativeHostRunNamespacesChildResourcesByPort() {
        HostDefaultsConfigSource source = new HostDefaultsConfigSource(true);
        assertThat(source.getValue(HostDefaultsConfigSource.RESOURCE_NAMESPACE), equalTo("host-${floci.port}"));

        SmallRyeConfig config = configWith(source,
                new PropertiesConfigSource(Map.of("floci.port", "4597"), "env", 300));
        assertThat(config.getValue(HostDefaultsConfigSource.RESOURCE_NAMESPACE, String.class), equalTo("host-4597"));
    }

    @Test
    void ordinalOverridesOnlyTheBundledYaml() {
        // classpath application.yml is 255, config/application.yml in the working directory 265.
        assertThat(new HostDefaultsConfigSource(false).getOrdinal(), allOf(greaterThan(255), lessThan(265)));
    }

    private static SmallRyeConfig configWith(ConfigSource... sources) {
        // The default interceptors expand ${...} expressions, as the runtime config does.
        return new SmallRyeConfigBuilder().addDefaultInterceptors().withSources(sources).build();
    }
}
