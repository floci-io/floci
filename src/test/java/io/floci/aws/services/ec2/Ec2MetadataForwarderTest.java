package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * A relay that holds 169.254.169.254 for containers that cannot hold it themselves names each caller in
 * {@code X-Forwarded-For}, which the server reads from a relay it trusts alone, so no other caller can claim another
 * instance's metadata by sending the header itself.
 */
class Ec2MetadataForwarderTest {

    // The address a relay's caller has on its cluster's network.
    private static final String CALLER = "169.254.1.5";

    private final HttpClient client = HttpClient.newHttpClient();
    private Ec2MetadataTestFixture fixture;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new Ec2MetadataTestFixture();
        fixture.startUnregistered(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        Instance node = Ec2MetadataTestFixture.instance("i-00000000000000001");
        fixture.server().registerContainer(CALLER, node.getInstanceId(), node);
    }

    @AfterEach
    void stopServer() throws Exception {
        try (client) {
            fixture.close();
        }
    }

    @Test
    void aTrustedRelaysCallerIsAnsweredAsTheInstanceItsAddressIs() throws Exception {
        fixture.server().trustForwarder(Ec2MetadataTestFixture.CALLER_IP);

        HttpResponse<String> answered = get("/latest/meta-data/instance-id", CALLER, null);

        assertEquals(200, answered.statusCode());
        assertEquals("i-00000000000000001", answered.body());
    }

    @Test
    void aTrustedRelaysCallerGetsAndUsesAnImdsV2Token() throws Exception {
        fixture.server().trustForwarder(Ec2MetadataTestFixture.CALLER_IP);
        HttpResponse<String> token = client.send(HttpRequest.newBuilder(
                        URI.create(fixture.endpoint() + "/latest/api/token")).timeout(Duration.ofSeconds(5))
                .header(Ec2MetadataTestFixture.TTL_HEADER, "60").header("X-Forwarded-For", CALLER)
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> answered = get("/latest/meta-data/instance-id", CALLER, token.body());

        assertEquals(200, answered.statusCode());
        assertEquals("i-00000000000000001", answered.body());
    }

    @Test
    void anotherCallerNamingAnAddressIsAnsweredForItsOwn() throws Exception {
        HttpResponse<String> refused = get("/latest/meta-data/instance-id", CALLER, null);

        assertEquals(404, refused.statusCode());
    }

    @Test
    void aRelayNoLongerTrustedIsAnsweredForItsOwnAddress() throws Exception {
        fixture.server().trustForwarder(Ec2MetadataTestFixture.CALLER_IP);
        fixture.server().distrustForwarder(Ec2MetadataTestFixture.CALLER_IP);

        HttpResponse<String> refused = get("/latest/meta-data/instance-id", CALLER, null);

        assertEquals(404, refused.statusCode());
    }

    private HttpResponse<String> get(String path, String forwardedFor, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(fixture.endpoint() + path))
                .timeout(Duration.ofSeconds(5)).header("X-Forwarded-For", forwardedFor).GET();
        if (token != null) {
            request.header(Ec2MetadataTestFixture.TOKEN_HEADER, token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
