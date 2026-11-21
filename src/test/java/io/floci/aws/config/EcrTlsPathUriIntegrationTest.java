package io.floci.aws.config;

import io.floci.aws.testing.IotMqttTlsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.nio.file.Path;

@QuarkusTest
@TestProfile(IotMqttTlsProfile.class)
class EcrTlsPathUriIntegrationTest extends EcrTlsUriIntegrationTest {

    @Override
    String expectedRepositoryUri(String repository) {
        return "localhost.floci.io:" + ADVERTISED_PORT + "/" + ACCOUNT + "/" + REGION + "/" + repository;
    }

    @Override
    Path tlsDir() {
        return Path.of(IotMqttTlsProfile.TLS_DIR);
    }
}
