package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DisableImageBlockPublicAccessRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.GetImageBlockPublicAccessStateRequest;
import software.amazon.awssdk.services.ec2.model.GetImageBlockPublicAccessStateResponse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2ImageBlockPublicAccessIntegrationTest {

    private static final String ERROR_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=814500000002/20261031/us-east-1/ec2/aws4_request";

    @Test
    void sdkRoundTripUsesAwsResponseShape() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "814500000001", Region.US_EAST_1)) {
            GetImageBlockPublicAccessStateResponse initial = getState(client);
            assertEquals("unblocked", initial.imageBlockPublicAccessState());
            assertEquals("account", initial.managedByAsString());

            assertEquals("block-new-sharing", client.enableImageBlockPublicAccess(request -> request
                    .imageBlockPublicAccessState("block-new-sharing"))
                    .imageBlockPublicAccessStateAsString());
            assertEquals("block-new-sharing", getState(client).imageBlockPublicAccessState());

            assertEquals("unblocked", client.disableImageBlockPublicAccess(
                            DisableImageBlockPublicAccessRequest.builder().build())
                    .imageBlockPublicAccessStateAsString());
            assertEquals("unblocked", getState(client).imageBlockPublicAccessState());
        }
    }

    @Test
    void stateIsScopedByRegion() {
        try (Ec2Client home = AwsTestClients.create(Ec2Client.builder(), "814500000003", Region.US_EAST_1);
             Ec2Client away = AwsTestClients.create(Ec2Client.builder(), "814500000003", Region.EU_WEST_1)) {
            home.enableImageBlockPublicAccess(request -> request
                    .imageBlockPublicAccessState("block-new-sharing"));

            assertEquals("block-new-sharing", getState(home).imageBlockPublicAccessState());
            assertEquals("unblocked", getState(away).imageBlockPublicAccessState());
        }
    }

    @Test
    void enableValidatesStateBeforeDryRun() {
        given()
            .formParam("Action", "EnableImageBlockPublicAccess")
            .formParam("ImageBlockPublicAccessState", "unblocked")
            .formParam("DryRun", "true")
            .header("Authorization", ERROR_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "EnableImageBlockPublicAccess")
            .formParam("DryRun", "true")
            .header("Authorization", ERROR_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void dryRunDoesNotChangeState() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "814500000004", Region.US_EAST_1)) {
            client.enableImageBlockPublicAccess(request -> request
                    .imageBlockPublicAccessState("block-new-sharing"));

            Ec2Exception disableError = assertThrows(Ec2Exception.class,
                    () -> client.disableImageBlockPublicAccess(request -> request.dryRun(true)));
            assertEquals("DryRunOperation", disableError.awsErrorDetails().errorCode());
            assertEquals("block-new-sharing", getState(client).imageBlockPublicAccessState());

            Ec2Exception getError = assertThrows(Ec2Exception.class,
                    () -> client.getImageBlockPublicAccessState(request -> request.dryRun(true)));
            assertEquals("DryRunOperation", getError.awsErrorDetails().errorCode());
        }
    }

    @Test
    void onlyGetReturnsManagedBy() {
        String enableBody = given()
            .formParam("Action", "EnableImageBlockPublicAccess")
            .formParam("ImageBlockPublicAccessState", "block-new-sharing")
            .header("Authorization", ERROR_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnableImageBlockPublicAccessResponse.imageBlockPublicAccessState",
                    equalTo("block-new-sharing"))
            .extract().asString();
        assertThat(enableBody, not(containsString("managedBy")));

        given()
            .formParam("Action", "GetImageBlockPublicAccessState")
            .header("Authorization", ERROR_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetImageBlockPublicAccessStateResponse.managedBy", equalTo("account"));
    }

    private static GetImageBlockPublicAccessStateResponse getState(Ec2Client client) {
        return client.getImageBlockPublicAccessState(GetImageBlockPublicAccessStateRequest.builder().build());
    }
}
