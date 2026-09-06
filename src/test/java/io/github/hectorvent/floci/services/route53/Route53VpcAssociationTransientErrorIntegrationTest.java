package io.github.hectorvent.floci.services.route53;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(Route53VpcAssociationTransientErrorIntegrationTest.DelayProfile.class)
class Route53VpcAssociationTransientErrorIntegrationTest {

    private static final String XML = "application/xml";

    public static final class DelayProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "memory",
                    "floci.services.route53.vpc-association-control-plane-delay-ms", "150");
        }
    }

    @Test
    void associationOverlapReturnsPriorRequestNotCompleteThenRetrySucceeds() {
        String zoneId = createPrivateZone(
                "retry-association.internal.", "retry-association", "vpc-retry-primary");

        associate(zoneId, "vpc-retry-secondary")
                .statusCode(200);

        associate(zoneId, "vpc-retry-tertiary")
                .statusCode(400)
                .body(containsString("<Code>PriorRequestNotComplete</Code>"));

        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> associate(zoneId, "vpc-retry-tertiary")
                        .statusCode(200));
    }

    @Test
    void authorizationOverlapReturnsConcurrentModificationThenRetrySucceeds() {
        String zoneId = createPrivateZone(
                "retry-authorization.internal.", "retry-authorization", "vpc-authz-primary");

        authorize(zoneId, "vpc-authz-one")
                .statusCode(200);

        authorize(zoneId, "vpc-authz-two")
                .statusCode(400)
                .body(containsString("<Code>ConcurrentModification</Code>"));

        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> authorize(zoneId, "vpc-authz-two")
                        .statusCode(200));

        deauthorize(zoneId, "vpc-authz-one")
                .statusCode(400)
                .body(containsString("<Code>ConcurrentModification</Code>"));

        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> deauthorize(zoneId, "vpc-authz-one")
                        .statusCode(200));
    }

    private static io.restassured.response.ValidatableResponse associate(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then();
    }

    private static io.restassured.response.ValidatableResponse authorize(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then();
    }

    private static io.restassured.response.ValidatableResponse deauthorize(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then();
    }

    private static String createPrivateZone(String name, String callerReference, String vpcId) {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference, vpcId);
        String location = given().contentType(XML)
                .body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201)
                .extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static String vpcRequest(String root, String vpcId) {
        return """
                <%s xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </%s>
                """.formatted(root, vpcId, root);
    }
}
