package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

class FlociVersionTest {

    @Test
    void overrideWinsOverBuildVersion() {
        assertThat(FlociVersion.resolve("nightly-10152026", "2.3.0"), equalTo("nightly-10152026"));
    }

    @Test
    void blankOverrideFallsBackToBuildVersion() {
        assertThat(FlociVersion.resolve("  ", "2.3.0"), equalTo("2.3.0"));
        assertThat(FlociVersion.resolve(null, "2.3.0"), equalTo("2.3.0"));
    }

    @Test
    void devOnlyWhenNoVersionIsKnown() {
        assertThat(FlociVersion.resolve(null, null), equalTo("dev"));
        assertThat(FlociVersion.resolve("", " "), equalTo("dev"));
    }

    @Test
    void buildStampsTheProjectVersion() {
        // Only meaningful when FLOCI_VERSION is unset, which is how the suite runs.
        String version = FlociVersion.current();
        assertThat(version, not(equalTo("dev")));
        assertThat(version, matchesPattern("\\d+\\.\\d+\\.\\d+.*"));
    }
}
