package io.floci.aws.services.route53;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Route53Exception;
import software.amazon.awssdk.services.route53.model.Tag;
import software.amazon.awssdk.services.route53.model.TagResourceType;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hosted zone requests are checked on the hosted zone their path names
 * ({@code arn:aws:route53:::hostedzone/<id>}), and a tag request on the resource its
 * {@code ResourceType} and {@code ResourceId} labels name, before the handler changes anything.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Route53HostedZonesIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "741686000031";

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup CLEANUP = new PartitionCleanup();
    @RegisterExtension
    final AfterAllCallback classCleanup = CLEANUP::afterEach;
    private Route53Client root;
    private String selectedZone;
    private String otherZone;

    @BeforeAll
    void createSharedFixtures() {
        root = client(ACCOUNT);
        CLEANUP.register(root::close);
        selectedZone = createZone(root);
        otherZone = createZone(root);
    }

    @Test
    void aCallerWithoutPermissionsIsRefusedBeforeAZoneIsCreated() {
        String name = "none-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com.";
        try (Route53Client client = client(userWithPolicy(null))) {
            assertDenied(() -> client.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString())));
            assertDenied(() -> client.getHostedZone(r -> r.id(selectedZone)));
            assertDenied(client::listHostedZones);
        }
        assertFalse(root.listHostedZones().hostedZones().stream().anyMatch(zone -> zone.name().equals(name)));
    }

    @Test
    void aZoneScopedAllowGrantsOnlyThatZone() {
        String key = userWithPolicy(allow("[\"route53:GetHostedZone\",\"route53:UpdateHostedZoneComment\","
                + "\"route53:ListResourceRecordSets\"]", zoneArn(selectedZone)));
        try (Route53Client client = client(key)) {
            assertEquals(selectedZone, bare(client.getHostedZone(r -> r.id(selectedZone)).hostedZone().id()));
            client.updateHostedZoneComment(r -> r.id(selectedZone).comment("scoped"));
            client.listResourceRecordSets(r -> r.hostedZoneId(selectedZone));
            assertDenied(() -> client.getHostedZone(r -> r.id(otherZone)));
            assertDenied(() -> client.updateHostedZoneComment(r -> r.id(otherZone).comment("scoped")));
            assertDenied(() -> client.listResourceRecordSets(r -> r.hostedZoneId(otherZone)));
        }
    }

    @Test
    void anExplicitDenyOnAZoneKeepsItFromBeingDeleted() {
        String zone = createZone(root);
        String key = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"route53:*","Resource":"*"},
                {"Effect":"Deny","Action":"route53:DeleteHostedZone","Resource":"%s"}]}
                """.formatted(zoneArn(zone)));
        try (Route53Client client = client(key)) {
            assertDenied(() -> client.deleteHostedZone(r -> r.id(zone)));
            // Named by the path Route 53 returns its id under, the zone is the same one, and so is the deny.
            given().urlEncodingEnabled(false).header("Authorization", auth(key, "route53"))
                    .delete("/2013-04-01/hostedzone/%2Fhostedzone%2F" + zone)
                    .then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
            assertEquals(zone, bare(root.getHostedZone(r -> r.id(zone)).hostedZone().id()));
        }
    }

    @Test
    void aTagRequestIsCheckedOnTheResourceItsTypeAndIdName() {
        String key = userWithPolicy(allow("[\"route53:ChangeTagsForResource\",\"route53:ListTagsForResource\"]",
                zoneArn(selectedZone)));
        Tag tag = Tag.builder().key("team").value("dns").build();
        try (Route53Client client = client(key)) {
            client.changeTagsForResource(r -> r.resourceType(TagResourceType.HOSTEDZONE).resourceId(selectedZone)
                    .addTags(tag));
            assertEquals(1, client.listTagsForResource(r -> r.resourceType(TagResourceType.HOSTEDZONE)
                    .resourceId(selectedZone)).resourceTagSet().tags().size());
            assertDenied(() -> client.changeTagsForResource(r -> r.resourceType(TagResourceType.HOSTEDZONE)
                    .resourceId(otherZone).addTags(tag)));
            // The same id under another type is another resource.
            assertDenied(() -> client.listTagsForResource(r -> r.resourceType(TagResourceType.HEALTHCHECK)
                    .resourceId(selectedZone)));
        }
    }

    private String createZone(Route53Client client) {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        String id = bare(client.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString()))
                .hostedZone().id());
        CLEANUP.register(() -> root.deleteHostedZone(r -> r.id(id)));
        return id;
    }

    /** A hosted zone's id without the {@code /hostedzone/} path Route 53 returns it under. */
    private static String bare(String id) {
        return id.replace("/hostedzone/", "");
    }

    private static String zoneArn(String id) {
        return "arn:aws:route53:::hostedzone/" + id;
    }

    private static String allow(String actions, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,"Resource":"%s"}]}
                """.formatted(actions, resource);
    }

    private String userWithPolicy(String policy) {
        String user = "r53-zones-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(CLEANUP, baseUri.getPort(), auth(ACCOUNT, "iam"), user).accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "zones", policy);
        }
        return key;
    }

    private Route53Client client(String key) {
        return AwsTestClients.create(Route53Client.builder(), key, Region.US_EAST_1, baseUri);
    }

    private void assertDenied(Runnable call) {
        Route53Exception error = assertThrows(Route53Exception.class, call::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }
}
