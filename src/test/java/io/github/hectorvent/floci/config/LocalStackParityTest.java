package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** The cases of the former docker/test-localstack-parity.sh, plus the TLS mappings it did not cover. */
class LocalStackParityTest {

    private static String value(String property, String... env) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < env.length; i += 2) {
            map.put(env[i], env[i + 1]);
        }
        return LocalStackParity.translate(map).get(property);
    }

    @Test
    void persistence() {
        assertThat(value("floci.storage.mode", "PERSISTENCE", "1"), equalTo("persistent"));
        assertThat(value("floci.storage.mode", "PERSISTENCE", "true"), equalTo("persistent"));
        assertThat(value("floci.storage.mode", "PERSIST_STATE", "1"), equalTo("persistent"));
        assertThat(value("floci.storage.mode", "PERSISTENCE", "0"), nullValue());
        assertThat(value("floci.storage.mode", "PERSISTENCE", "1", "FLOCI_STORAGE_MODE", "hybrid"), nullValue());
    }

    @Test
    void edgePort() {
        assertThat(value("floci.port", "EDGE_PORT", "4567"), equalTo("4567"));
        assertThat(value("floci.port", "EDGE_PORT", "4567", "FLOCI_PORT", "4568"), nullValue());
    }

    @Test
    void hostname() {
        assertThat(value("floci.hostname", "LOCALSTACK_HOST", "myhost"), equalTo("myhost"));
        assertThat(value("floci.hostname", "LOCALSTACK_HOSTNAME", "myhost2"), equalTo("myhost2"));
        assertThat(value("floci.hostname", "LOCALSTACK_HOST", "primary", "LOCALSTACK_HOSTNAME", "secondary"),
                equalTo("primary"));
        assertThat(value("floci.hostname", "LOCALSTACK_HOST", "myhost", "FLOCI_HOSTNAME", "explicit"), nullValue());
    }

    @Test
    void gatewayListen() {
        assertThat(value("quarkus.http.host", "GATEWAY_LISTEN", "0.0.0.0"), equalTo("0.0.0.0"));
        assertThat(value("floci.security.allow-unsafe-network-exposure", "GATEWAY_LISTEN", "0.0.0.0"), equalTo("true"));
        assertThat(value("floci.security.allow-unsafe-network-exposure", "GATEWAY_LISTEN", "0.0.0.0",
                "FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE", "false"), nullValue());
        assertThat(value("floci.security.allow-unsafe-network-exposure"), nullValue());
    }

    @Test
    void logLevel() {
        assertThat(value("quarkus.log.level", "LS_LOG", "WARN"), equalTo("WARN"));
        assertThat(value("quarkus.log.level", "DEBUG", "1"), equalTo("DEBUG"));
        assertThat(value("quarkus.log.level", "LS_LOG", "TRACE", "DEBUG", "1"), equalTo("TRACE"));
        assertThat(value("quarkus.log.level", "LS_LOG", "DEBUG", "QUARKUS_LOG_LEVEL", "INFO"), nullValue());
    }

    @Test
    void lambda() {
        assertThat(value("floci.services.lambda.docker-network", "LAMBDA_DOCKER_NETWORK", "mynet"), equalTo("mynet"));
        assertThat(value("floci.services.lambda.docker-network", "LAMBDA_DOCKER_NETWORK", "mynet",
                "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK", "floci-net"), nullValue());
        assertThat(value("floci.services.lambda.ephemeral", "LAMBDA_REMOVE_CONTAINERS", "1"), equalTo("true"));
        assertThat(value("floci.services.lambda.ephemeral", "LAMBDA_REMOVE_CONTAINERS", "true"), equalTo("true"));
        assertThat(value("floci.services.lambda.ephemeral", "LAMBDA_REMOVE_CONTAINERS", "1",
                "FLOCI_SERVICES_LAMBDA_EPHEMERAL", "false"), nullValue());
    }

    @Test
    void dockerHostAndNetwork() {
        assertThat(value("floci.docker.docker-host", "DOCKER_HOST", "unix:///var/run/docker.sock"),
                equalTo("unix:///var/run/docker.sock"));
        assertThat(value("floci.services.docker-network", "DOCKER_NETWORK", "shared"), equalTo("shared"));
        assertThat(value("floci.services.docker-network", "DOCKER_NETWORK", "shared",
                "FLOCI_SERVICES_DOCKER_NETWORK", "override"), nullValue());
    }

    @Test
    void dnsSuffixesAreAppendedToWhateverIsConfigured() {
        // Not a translated property: it would outrank and replace suffixes set in application.yml.
        assertThat(value("floci.dns.extra-suffixes"), nullValue());
        assertThat(LocalStackParity.withDnsSuffixes(Optional.empty(), Map.of()),
                equalTo(Optional.of(List.of("localhost.localstack.cloud", "localhost.floci.io"))));
        assertThat(LocalStackParity.withDnsSuffixes(Optional.of(List.of("custom.internal", "localhost.floci.io")),
                        Map.of()),
                equalTo(Optional.of(List.of("custom.internal", "localhost.floci.io", "localhost.localstack.cloud"))));
        assertThat(LocalStackParity.withDnsSuffixes(Optional.of(List.of("custom.internal")),
                        Map.of("LOCALSTACK_PARITY", "false")),
                equalTo(Optional.of(List.of("custom.internal"))));
    }

    @Test
    void gatewayListenWithAPortGivesTheHostAndThePort() {
        assertThat(value("quarkus.http.host", "GATEWAY_LISTEN", "0.0.0.0:4566"), equalTo("0.0.0.0"));
        assertThat(value("floci.port", "GATEWAY_LISTEN", "0.0.0.0:4567"), equalTo("4567"));
        assertThat(value("quarkus.http.host", "GATEWAY_LISTEN", "[::]:4566,0.0.0.0:443"), equalTo("::"));
        assertThat(value("quarkus.http.host", "GATEWAY_LISTEN", "0.0.0.0"), equalTo("0.0.0.0"));
        assertThat(value("floci.port", "GATEWAY_LISTEN", "0.0.0.0:4567", "EDGE_PORT", "4570"), equalTo("4570"));
        assertThat(value("floci.port", "GATEWAY_LISTEN", "0.0.0.0:4567", "FLOCI_PORT", "4571"), nullValue());
    }

    @Test
    void hooksGetTheTranslationAsVariables() {
        Map<String, String> env = LocalStackParity.environment(Map.of("EDGE_PORT", "4567", "PERSISTENCE", "1"));
        assertThat(env.get("FLOCI_PORT"), equalTo("4567"));
        assertThat(env.get("FLOCI_STORAGE_MODE"), equalTo("persistent"));
        assertThat(env.get("FLOCI_DNS_EXTRA_SUFFIXES"), equalTo("localhost.localstack.cloud,localhost.floci.io"));
        assertThat(LocalStackParity.environment(Map.of("EDGE_PORT", "4567", "FLOCI_PORT", "4568")),
                not(hasKey("FLOCI_PORT")));
        assertThat(LocalStackParity.environment(Map.of("LOCALSTACK_PARITY", "false", "EDGE_PORT", "4567")),
                anEmptyMap());
    }

    @Test
    void tls() {
        assertThat(value("floci.tls.enabled", "USE_SSL", "1"), equalTo("true"));
        assertThat(value("floci.tls.enabled", "USE_SSL", "0"), nullValue());
        assertThat(value("floci.tls.enabled", "USE_SSL", "1", "FLOCI_TLS_ENABLED", "false"), nullValue());
        assertThat(value("floci.tls.cert-path", "CUSTOM_SSL_CERT_PATH", "/certs/ls.pem"), equalTo("/certs/ls.pem"));
        assertThat(value("floci.tls.key-path", "CUSTOM_SSL_CERT_PATH", "/certs/ls.pem"), equalTo("/certs/ls.pem"));
    }

    @Test
    void disabledTranslatesNothing() {
        assertThat(LocalStackParity.translate(Map.of("LOCALSTACK_PARITY", "false", "EDGE_PORT", "4567")),
                aMapWithSize(0));
        assertThat(LocalStackParity.translate(Map.of("LOCALSTACK_PARITY", "true", "EDGE_PORT", "4567")),
                hasKey("floci.port"));
    }

    @Test
    void ignoredVariablesProduceNothing() {
        Map<String, String> out = LocalStackParity.translate(Map.of("LAMBDA_EXECUTOR", "local", "SERVICES", "s3"));
        assertThat(out.keySet(), equalTo(Set.of()));
        assertThat(out, not(hasKey("floci.services.lambda.executor")));
    }

    @Test
    void ordinalSitsBelowEnvironmentAndTlsButAboveTheConfigFiles() {
        // .env is 295, config/application.yml 265; environment variables and TlsConfigSource are 300.
        assertThat(new LocalStackParityConfigSource(Map.of()).getOrdinal(), allOf(greaterThan(295), lessThan(300)));
        assertThat(new TlsConfigSource().getOrdinal(), greaterThan(new LocalStackParityConfigSource(Map.of()).getOrdinal()));
    }
}
