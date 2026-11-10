package io.floci.aws.services.controltower;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.controltower.ControlTowerClient;
import software.amazon.awssdk.services.controltower.model.ControlTowerException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ControlTowerTagOnCreateIntegrationTest {

    private static final Map<String, String> TAGS = Map.of("team", "blocked");
    private static final String CONTROL = "arn:aws:controlcatalog:::control/fixture";

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "create", "tag"})
    void landingZoneTagDenialPreventsCreation(String policy) throws Exception {
        String[] created = {null};
        try (ControlTowerClient admin = client("test", "test");
             SignedIamUsers.User user = user("CreateLandingZone", policy);
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            try {
                assertEquals(403, assertThrows(ControlTowerException.class, () -> created[0] = caller.createLandingZone(r ->
                        r.manifest(Document.fromMap(Map.of())).version("3.3").tags(TAGS)).arn()).statusCode());
                // A permitted create in the same scope proves the refused request stored no landing zone.
                created[0] = admin.createLandingZone(r -> r.manifest(Document.fromMap(Map.of()))
                        .version("3.3").tags(TAGS)).arn();
                assertEquals(TAGS, admin.listTagsForResource(r -> r.resourceArn(created[0])).tags());
            } finally {
                if (created[0] != null) {
                    admin.deleteLandingZone(r -> r.landingZoneIdentifier(created[0]));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "create", "tag"})
    void enabledControlTagDenialPreventsCreation(String policy) throws Exception {
        String target = "arn:aws:organizations::000000000000:ou/o-test/ou-" + UUID.randomUUID();
        try (ControlTowerClient admin = client("test", "test");
             SignedIamUsers.User user = user("EnableControl", policy);
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            assertEquals(403, assertThrows(ControlTowerException.class, () -> caller.enableControl(r ->
                    r.controlIdentifier(CONTROL).targetIdentifier(target).tags(TAGS))).statusCode());
            assertEquals(0, admin.listEnabledControls(r -> r.targetIdentifier(target)).enabledControls().size());
        }
    }

    @Test
    void landingZoneAllowsTagsWithBothPermissionsAndNeedsNoTagPermissionWithoutTags() throws Exception {
        for (String policy : new String[]{"allow", "missing"}) {
            Map<String, String> tags = "allow".equals(policy) ? TAGS : Map.of();
            try (ControlTowerClient admin = client("test", "test");
                 SignedIamUsers.User user = user("CreateLandingZone", policy);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                String arn = caller.createLandingZone(r -> r.manifest(Document.fromMap(Map.of()))
                        .version("3.3").tags(tags)).arn();
                try {
                    assertEquals(tags, admin.listTagsForResource(r -> r.resourceArn(arn)).tags());
                } finally {
                    admin.deleteLandingZone(r -> r.landingZoneIdentifier(arn));
                }
            }
        }
    }

    @Test
    void enabledControlAllowsTagsWithBothPermissionsAndNeedsNoTagPermissionWithoutTags() throws Exception {
        for (String policy : new String[]{"allow", "missing"}) {
            Map<String, String> tags = "allow".equals(policy) ? TAGS : Map.of();
            String target = "arn:aws:organizations::000000000000:ou/o-test/ou-" + UUID.randomUUID();
            try (ControlTowerClient admin = client("test", "test");
                 SignedIamUsers.User user = user("EnableControl", policy);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                String arn = caller.enableControl(r -> r.controlIdentifier(CONTROL).targetIdentifier(target).tags(tags)).arn();
                // DisableControl is not served; these unique in-memory targets die with the test application.
                assertEquals(tags, admin.listTagsForResource(r -> r.resourceArn(arn)).tags());
            }
        }
    }

    private SignedIamUsers.User user(String operation, String mode) throws Exception {
        String actions = "\"controltower:" + operation + "\""
                + ("missing".equals(mode) ? "" : ",\"controltower:TagResource\"");
        String deny = "create".equals(mode) || "tag".equals(mode)
                ? ",{\"Effect\":\"Deny\",\"Action\":\"controltower:"
                        + ("create".equals(mode) ? operation : "TagResource") + "\",\"Resource\":\"*\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blocked\"}}}" : "";
        return SignedIamUsers.createUser(endpoint, "ct-create-tags-", "access",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[" + actions
                        + "],\"Resource\":\"*\"}" + deny + "]}");
    }

    private ControlTowerClient client(String key, String secret) {
        return ControlTowerClient.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }
}
