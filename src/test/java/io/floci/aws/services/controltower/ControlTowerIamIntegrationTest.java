package io.floci.aws.services.controltower;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.controltower.ControlTowerClient;
import software.amazon.awssdk.services.controltower.model.ControlTowerException;
import software.amazon.awssdk.services.controltower.model.ListLandingZonesRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ControlTowerIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void inferredLandingZoneArnKeepsMissingContextWithoutNamingTheSeededZone() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            String raw = "arn:aws:controltower:::landingzone/" + AwsArnUtils.parse(arn).resource().substring("landingzone/".length());
            try (SignedIamUsers.User user = user("Allow", "controltower:*", raw);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                assertEquals(404, assertThrows(ControlTowerException.class,
                        () -> caller.getLandingZone(r -> r.landingZoneIdentifier(raw))).statusCode());
                assertEquals(404, assertThrows(ControlTowerException.class,
                        () -> caller.deleteLandingZone(r -> r.landingZoneIdentifier(raw))).statusCode());
            }
            try (SignedIamUsers.User user = user("Allow", "controltower:*", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                denied(() -> caller.getLandingZone(r -> r.landingZoneIdentifier(raw)));
                denied(() -> caller.deleteLandingZone(r -> r.landingZoneIdentifier(raw)));
            }
            assertEquals(arn, admin.getLandingZone(r -> r.landingZoneIdentifier(arn)).landingZone().arn());
        }
    }

    @Test
    void landingZoneScopedAllowAcceptsIdAndArnButRefusesAnotherResource() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            try (SignedIamUsers.User user = user("Allow", "controltower:GetLandingZone", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                assertEquals(arn, caller.getLandingZone(r -> r.landingZoneIdentifier(arn)).landingZone().arn());
                assertEquals(arn, caller.getLandingZone(r -> r.landingZoneIdentifier(
                        AwsArnUtils.parse(arn).resource().substring("landingzone/".length()))).landingZone().arn());
                denied(() -> caller.getLandingZone(r -> r.landingZoneIdentifier("different")));
            }
        }
    }

    @Test
    void explicitResourceDenyPreventsLandingZoneDeletion() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            try (SignedIamUsers.User user = user("Deny", "controltower:DeleteLandingZone", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                denied(() -> caller.deleteLandingZone(r -> r.landingZoneIdentifier(arn)));
                assertEquals(arn, admin.getLandingZone(r -> r.landingZoneIdentifier(arn)).landingZone().arn());
            }
        }
    }

    @Test
    void listRequiresWildcardGrant() throws Exception {
        try (SignedIamUsers.User user = user("Allow", "controltower:GetLandingZone", "*");
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> caller.listLandingZones(ListLandingZonesRequest.builder().build()));
        }
        try (SignedIamUsers.User user = user("Allow", "controltower:ListLandingZones", "*");
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            assertEquals(1, caller.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().size());
        }
    }

    @Test
    void generatedTagResourceUsesArnPathAndDenialPreservesTags() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            try (SignedIamUsers.User user = user("Allow", "controltower:ListTagsForResource", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                assertEquals(admin.listTagsForResource(r -> r.resourceArn(arn)).tags(),
                        caller.listTagsForResource(r -> r.resourceArn(arn)).tags());
                denied(() -> caller.tagResource(r -> r.resourceArn(arn).tags(Map.of("blocked", "yes"))));
                assertFalse(admin.listTagsForResource(r -> r.resourceArn(arn)).tags().containsKey("blocked"));
            }
        }
    }

    @Test
    void enabledControlResourceAllowAndDenyUseEnabledArnRatherThanCatalogArn() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String target = "arn:aws:organizations::000000000000:ou/o-test/ou-" + UUID.randomUUID();
            String arn = admin.enableControl(r -> r.controlIdentifier("arn:aws:controlcatalog:::control/fixture")
                    .targetIdentifier(target)).arn();
            String before = admin.getEnabledControl(r -> r.enabledControlIdentifier(arn))
                    .enabledControlDetails().statusSummary().lastOperationIdentifier();
            try (SignedIamUsers.User user = user("Allow", "controltower:GetEnabledControl", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                assertEquals(arn, caller.getEnabledControl(r -> r.enabledControlIdentifier(arn)).enabledControlDetails().arn());
                denied(() -> caller.getEnabledControl(r -> r.enabledControlIdentifier(arn + "other")));
            }
            try (SignedIamUsers.User user = user("Deny", "controltower:ResetEnabledControl", arn);
                 ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
                denied(() -> caller.resetEnabledControl(r -> r.enabledControlIdentifier(arn)));
                assertEquals(before, admin.getEnabledControl(r -> r.enabledControlIdentifier(arn))
                        .enabledControlDetails().statusSummary().lastOperationIdentifier());
            }
        }
    }

    @Test
    void enableControlChecksMintedResourceAndDenialDoesNotCreateControl() throws Exception {
        String target = "arn:aws:organizations::000000000000:ou/o-test/ou-" + UUID.randomUUID();
        String catalog = "arn:aws:controlcatalog:::control/fixture";
        String enabled = "arn:aws:controltower:us-east-1:000000000000:enabledcontrol/*";
        try (ControlTowerClient admin = client("test", "test");
             SignedIamUsers.User user = user("Deny", "controltower:EnableControl", enabled);
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> caller.enableControl(r -> r.controlIdentifier(catalog).targetIdentifier(target)));
            assertEquals(0, admin.listEnabledControls(r -> r.targetIdentifier(target)).enabledControls().size());
        }
        try (SignedIamUsers.User user = user("Allow", "controltower:EnableControl", enabled);
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            String arn = caller.enableControl(r -> r.controlIdentifier(catalog).targetIdentifier(target)).arn();
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            assertEquals(enabled.substring(0, enabled.length() - 1), new AwsArnUtils.Arn(parsed.partition(),
                    parsed.service(), parsed.region(), parsed.accountId(), "enabledcontrol/").toString());
        }
    }

    @Test
    void wildcardGrantStillReachesMissingResourceValidation() throws Exception {
        try (SignedIamUsers.User user = user("Allow", "controltower:GetEnabledControl", "*");
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            assertEquals(404, assertThrows(ControlTowerException.class, () -> caller.getEnabledControl(r ->
                    r.enabledControlIdentifier("arn:aws:controltower:us-east-1:000000000000:enabledcontrol/missing")))
                    .statusCode());
        }
    }

    @Test
    void enabledBaselineScopedGrantReachesItsHandler() throws Exception {
        String arn = "arn:aws:controltower:us-east-1:000000000000:enabledbaseline/missing";
        try (SignedIamUsers.User user = user("Allow", "controltower:GetEnabledBaseline", arn);
             ControlTowerClient caller = client(user.accessKeyId(), user.secretAccessKey())) {
            assertEquals(404, assertThrows(ControlTowerException.class,
                    () -> caller.getEnabledBaseline(r -> r.enabledBaselineIdentifier(arn))).statusCode());
        }
    }

    @Test
    void bareTagsReadsBodyArnAndKeepsScopedGrant() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            try (SignedIamUsers.User user = user("Allow", "controltower:TagResource", arn)) {
                String body = "{\"resourceArn\":\"" + arn + "\",\"tags\":{\"bare\":\"yes\"}}";
                URI target = endpoint.resolve("/tags?resourceArn="
                        + URLEncoder.encode(arn + "-foreign", StandardCharsets.UTF_8));
                try {
                    given().urlEncodingEnabled(false).headers(user.signer("controltower").headersFor("POST", target, Map.of(),
                                    body.getBytes(StandardCharsets.UTF_8)))
                            .contentType("application/json").body(body).post(target).then().statusCode(200);
                    assertEquals("yes", admin.listTagsForResource(r -> r.resourceArn(arn)).tags().get("bare"));
                } finally {
                    admin.untagResource(r -> r.resourceArn(arn).tagKeys("bare"));
                }
            }
        }
    }

    @Test
    void bareTagQueryRoutesIgnoreBodyAndRequireScopedQueryResource() throws Exception {
        try (ControlTowerClient admin = client("test", "test")) {
            String arn = admin.listLandingZones(ListLandingZonesRequest.builder().build()).landingZones().getFirst().arn();
            try (SignedIamUsers.User user = user("Allow", "controltower:*", arn)) {
                String body = "{\"resourceArn\":\"" + arn + "\"}";
                URI permitted = endpoint.resolve("/tags/?resourceArn=" + URLEncoder.encode(arn, StandardCharsets.UTF_8)
                        + "&tagKeys=bare-query");
                URI foreign = endpoint.resolve("/tags?resourceArn="
                        + URLEncoder.encode(arn + "-foreign", StandardCharsets.UTF_8) + "&tagKeys=bare-query");
                admin.tagResource(r -> r.resourceArn(arn).tags(Map.of("bare-query", "retained")));
                try {
                    for (String method : new String[]{"GET", "DELETE"}) {
                        for (URI denied : new URI[]{foreign, endpoint.resolve("/tags")}) {
                            given().urlEncodingEnabled(false).headers(user.signer("controltower").headersFor(method, denied, Map.of(),
                                            body.getBytes(StandardCharsets.UTF_8)))
                                    .contentType("application/json").body(body).request(method, denied)
                                    .then().statusCode(403).body(containsString("not authorized to perform: controltower:"));
                        }
                    }
                    assertEquals("retained", admin.listTagsForResource(r -> r.resourceArn(arn)).tags().get("bare-query"));
                    for (String method : new String[]{"GET", "DELETE"}) {
                        given().urlEncodingEnabled(false).headers(user.signer("controltower").headersFor(method, permitted, Map.of(), new byte[0]))
                                .request(method, permitted).then().statusCode(200);
                    }
                    assertFalse(admin.listTagsForResource(r -> r.resourceArn(arn)).tags().containsKey("bare-query"));
                } finally {
                    admin.untagResource(r -> r.resourceArn(arn).tagKeys("bare-query"));
                }
            }
        }
    }

    private SignedIamUsers.User user(String effect, String action, String resource) throws Exception {
        String allow = "Deny".equals(effect) ? "{\"Effect\":\"Allow\",\"Action\":\"controltower:*\",\"Resource\":\"*\"}," : "";
        return SignedIamUsers.createUser(endpoint, "ct-generated-", "access",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + allow + "{\"Effect\":\"" + effect
                        + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}]}");
    }

    private ControlTowerClient client(String key, String secret) {
        return AwsTestClients.create(ControlTowerClient.builder(), key, secret, Region.US_EAST_1, endpoint);
    }

    private static void denied(Runnable request) {
        assertEquals(403, assertThrows(ControlTowerException.class, request::run).statusCode());
    }
}
