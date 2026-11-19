package io.floci.aws.services.appsync;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.Api;
import software.amazon.awssdk.services.appsync.model.AuthMode;
import software.amazon.awssdk.services.appsync.model.AuthProvider;
import software.amazon.awssdk.services.appsync.model.AuthenticationType;
import software.amazon.awssdk.services.appsync.model.BadRequestException;
import software.amazon.awssdk.services.appsync.model.ChannelNamespace;
import software.amazon.awssdk.services.appsync.model.EventConfig;
import software.amazon.awssdk.services.appsync.model.EventLogLevel;
import software.amazon.awssdk.services.appsync.model.ListApisResponse;
import software.amazon.awssdk.services.appsync.model.NotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AppSync Events API management through the AWS SDK, which parses the {@code /v2/apis} responses
 * strictly: a timestamp in the wrong unit or a missing wrapper member fails the call. Timestamps
 * are compared with the emulator's own clock, which the test profile may pin.
 */
@QuarkusTest
class AppSyncEventsApiIntegrationTest {

    @Inject
    Clock clock;

    private static final String ACCOUNT = "741648700017";
    private static final String AUTHORIZER_ARN =
            "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:my-authorizer";

    private static EventConfig liveEventsConfig() {
        return EventConfig.builder()
                .authProviders(
                        AuthProvider.builder().authType(AuthenticationType.AWS_IAM).build(),
                        AuthProvider.builder().authType(AuthenticationType.AWS_LAMBDA)
                                .lambdaAuthorizerConfig(config -> config.authorizerUri(AUTHORIZER_ARN)
                                        .authorizerResultTtlInSeconds(300))
                                .build())
                .connectionAuthModes(mode(AuthenticationType.AWS_LAMBDA))
                .defaultPublishAuthModes(mode(AuthenticationType.AWS_IAM))
                .defaultSubscribeAuthModes(mode(AuthenticationType.AWS_LAMBDA))
                .logConfig(log -> log.logLevel(EventLogLevel.ERROR)
                        .cloudWatchLogsRoleArn("arn:aws:iam::" + ACCOUNT + ":role/appsync-logs"))
                .build();
    }

    private static AuthMode mode(AuthenticationType type) {
        return AuthMode.builder().authType(type).build();
    }

    private static AppSyncClient client() {
        return AwsTestClients.create(AppSyncClient.builder(), ACCOUNT, Region.US_EAST_1);
    }

    @Test
    void eventsApiLifecycleRoundTripsThroughTheSdk() {
        try (AppSyncClient client = client()) {
            Instant before = clock.instant().minusSeconds(5);
            Api created = client.createApi(request -> request.name("live-events")
                    .ownerContact("platform team")
                    .tags(Map.of("env", "local"))
                    .eventConfig(liveEventsConfig())).api();
            String apiId = created.apiId();
            try {
                assertEquals(26, apiId.length());
                assertEquals("arn:aws:appsync:us-east-1:" + ACCOUNT + ":apis/" + apiId, created.apiArn());
                assertEquals(apiId + ".appsync-api.us-east-1.amazonaws.com", created.dns().get("HTTP"));
                assertEquals(apiId + ".appsync-realtime-api.us-east-1.amazonaws.com", created.dns().get("REALTIME"));
                assertTrue(created.created().isAfter(before), "created " + created.created());
                assertTrue(created.created().isBefore(clock.instant().plus(Duration.ofMinutes(1))),
                        "created " + created.created());
                assertEquals("local", created.tags().get("env"));
                assertEquals(2, created.eventConfig().authProviders().size());
                assertEquals(AUTHORIZER_ARN,
                        created.eventConfig().authProviders().get(1).lambdaAuthorizerConfig().authorizerUri());
                assertEquals(EventLogLevel.ERROR, created.eventConfig().logConfig().logLevel());

                Api fetched = client.getApi(request -> request.apiId(apiId)).api();
                assertEquals("live-events", fetched.name());
                assertEquals("platform team", fetched.ownerContact());
                assertEquals(AuthenticationType.AWS_IAM,
                        fetched.eventConfig().defaultPublishAuthModes().get(0).authType());

                ListApisResponse listed = client.listApis(request -> request.maxResults(25));
                assertTrue(listed.apis().stream().anyMatch(api -> apiId.equals(api.apiId())));

                Api updated = client.updateApi(request -> request.apiId(apiId).name("live-events-renamed")
                        .eventConfig(liveEventsConfig().toBuilder()
                                .defaultSubscribeAuthModes(mode(AuthenticationType.AWS_IAM)).build())).api();
                assertEquals("live-events-renamed", updated.name());
                assertEquals(AuthenticationType.AWS_IAM,
                        updated.eventConfig().defaultSubscribeAuthModes().get(0).authType());
                assertEquals(created.created(), updated.created());
                assertEquals(created.dns(), updated.dns());

                client.tagResource(request -> request.resourceArn(created.apiArn()).tags(Map.of("team", "platform")));
                Map<String, String> tags = client.listTagsForResource(request -> request.resourceArn(created.apiArn()))
                        .tags();
                assertEquals("platform", tags.get("team"));
                assertEquals("local", tags.get("env"));
            } finally {
                client.deleteApi(request -> request.apiId(apiId));
            }
            assertThrows(NotFoundException.class, () -> client.getApi(request -> request.apiId(apiId)));
        }
    }

    @Test
    void channelNamespaceOnAnEventsApiKeepsItsAuthModesAndCascadesOnDelete() {
        try (AppSyncClient client = client()) {
            String apiId = client.createApi(request -> request.name("ns-" + UUID.randomUUID().toString().substring(0, 8))
                    .eventConfig(liveEventsConfig())).api().apiId();
            Instant before = clock.instant().minusSeconds(5);
            ChannelNamespace ns = client.createChannelNamespace(request -> request.apiId(apiId).name("v7-6-0")
                    .publishAuthModes(mode(AuthenticationType.AWS_IAM))
                    .subscribeAuthModes(mode(AuthenticationType.AWS_LAMBDA))
                    .tags(Map.of("version", "7.6.0"))).channelNamespace();

            assertEquals("arn:aws:appsync:us-east-1:" + ACCOUNT + ":apis/" + apiId + "/channelNamespace/v7-6-0",
                    ns.channelNamespaceArn());
            assertTrue(ns.created().isAfter(before), "created " + ns.created());
            assertEquals(List.of(mode(AuthenticationType.AWS_IAM)), ns.publishAuthModes());
            assertEquals(List.of(mode(AuthenticationType.AWS_LAMBDA)), ns.subscribeAuthModes());
            assertEquals("7.6.0", ns.tags().get("version"));

            BadRequestException unconfigured = assertThrows(BadRequestException.class,
                    () -> client.createChannelNamespace(request -> request.apiId(apiId).name("keyed")
                            .publishAuthModes(mode(AuthenticationType.API_KEY))));
            assertTrue(unconfigured.getMessage().contains("API_KEY"), unconfigured.getMessage());

            ChannelNamespace updated = client.updateChannelNamespace(request -> request.apiId(apiId).name("v7-6-0")
                    .subscribeAuthModes(mode(AuthenticationType.AWS_IAM))).channelNamespace();
            assertEquals(List.of(mode(AuthenticationType.AWS_IAM)), updated.subscribeAuthModes());
            assertEquals(List.of(mode(AuthenticationType.AWS_IAM)), updated.publishAuthModes());

            client.createApiKey(request -> request.apiId(apiId));
            assertEquals(1, client.listApiKeys(request -> request.apiId(apiId)).apiKeys().size());

            client.deleteApi(request -> request.apiId(apiId));

            assertThrows(NotFoundException.class,
                    () -> client.listChannelNamespaces(request -> request.apiId(apiId)));
            assertTrue(client.listApiKeys(request -> request.apiId(apiId)).apiKeys().isEmpty());
        }
    }

    @Test
    void createApiRefusesAModeThatIsNotAConfiguredProvider() {
        try (AppSyncClient client = client()) {
            String name = "refused-" + UUID.randomUUID().toString().substring(0, 8);
            BadRequestException refused = assertThrows(BadRequestException.class,
                    () -> client.createApi(request -> request.name(name).eventConfig(liveEventsConfig().toBuilder()
                            .defaultPublishAuthModes(mode(AuthenticationType.API_KEY)).build())));

            assertEquals(400, refused.statusCode());
            assertTrue(refused.getMessage().contains("defaultPublishAuthModes"), refused.getMessage());
            assertFalse(client.listApis(request -> request.maxResults(25)).apis().stream()
                    .anyMatch(api -> name.equals(api.name())));
        }
    }

    @Test
    void channelNamespaceOnAMissingApiIsNotFound() {
        try (AppSyncClient client = client()) {
            assertThrows(NotFoundException.class, () -> client.createChannelNamespace(
                    request -> request.apiId("missing0000000000000000000").name("ns")));
            assertThrows(NotFoundException.class, () -> client.getChannelNamespace(
                    request -> request.apiId("missing0000000000000000000").name("ns")));
        }
    }
}
