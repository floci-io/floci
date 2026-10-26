package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doAnswer;

/**
 * Verifies that a VPC association or authorization change on a private hosted zone, made while the
 * previous one is still in progress, is refused with {@code PriorRequestNotComplete} or
 * {@code ConcurrentModification}, and that a retry succeeds once the previous change is done.
 *
 * <p>The configured delay comes from {@link IndependentServiceSettingsProfile}. The monotonic
 * clock is controlled so HTTP transport time cannot consume the overlap window.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class Route53VpcAssociationTransientErrorIntegrationTest {

    private static final String XML = "application/xml";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @InjectSpy
    Route53Service service;

    private final AtomicLong timeNanos = new AtomicLong();

    @BeforeEach
    void controlMutationTime() {
        timeNanos.set(0);
        doAnswer(invocation -> timeNanos.get()).when(service).mutationTimeNanos();
    }

    private void completePreviousMutation() {
        timeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
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

        completePreviousMutation();
        associate(zoneId, "vpc-retry-tertiary").statusCode(200);
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

        completePreviousMutation();
        authorize(zoneId, "vpc-authz-two").statusCode(200);

        deauthorize(zoneId, "vpc-authz-one")
                .statusCode(400)
                .body(containsString("<Code>ConcurrentModification</Code>"));

        completePreviousMutation();
        deauthorize(zoneId, "vpc-authz-one").statusCode(200);
    }

    private static ValidatableResponse associate(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then();
    }

    private static ValidatableResponse authorize(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then();
    }

    private static ValidatableResponse deauthorize(String zoneId, String vpcId) {
        return given().contentType(XML)
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", vpcId))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then();
    }

    private String createPrivateZone(String name, String callerReference, String vpcId) {
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
        String zoneId = location.substring(location.lastIndexOf('/') + 1);
        cleanup.register(() -> given().delete("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200));
        return zoneId;
    }

    private static String vpcRequest(String root, String vpcId) {
        return """
                <%s xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </%s>
                """.formatted(root, vpcId, root);
    }
}
