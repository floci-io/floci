package io.floci.aws.config;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.WebhookAuthConfiguration;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;

import static io.floci.aws.config.TlsTestSupport.handshake;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A client that trusts only the local CA and verifies the host name accepts the webhook URL that
 * CreateEventSource answers, with no restart in between.
 */
@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
class TlsEventBridgeWebhookHostIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/WebhookRole";
    private static final Region REGION = Region.EU_WEST_2;

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @Test
    void theHostOfTheWebhookUrlCreateEventSourceReturnsPassesIdentityVerification() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        try (EventBridgeV2Client events = AwsTestClients.create(EventBridgeV2Client.builder(), "000000000000",
                REGION)) {
            String bus = events.createEventBus(request -> request.name("v2-tls-" + suffix)).eventBusArn();
            String probe = "probe.c01.events-webhook.eu-west-2.localhost";
            assertThrows(SSLHandshakeException.class, () -> handshake(sslPort, probe, true),
                    "precondition: the Region has no webhook host in the certificate");

            CreateEventSourceResponse created = events.createEventSource(request -> request.name("hook-" + suffix)
                    .eventBusArn(bus)
                    .configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(webhook -> webhook
                            .roleArn(ROLE).authConfiguration(WebhookAuthConfiguration.builder()
                                    .staticTokenAuthConfiguration(token -> token.tokenHttpHeader("X-Token")
                                            .secretValue("s3cret-token")).build()))));

            String host = URI.create(created.eventSourceDetails().webhookDetails().webhookUrl()).getHost();
            handshake(sslPort, host, true);
        } finally {
            given().when().post("/_floci/state/reset").then().statusCode(200);
        }
    }
}
