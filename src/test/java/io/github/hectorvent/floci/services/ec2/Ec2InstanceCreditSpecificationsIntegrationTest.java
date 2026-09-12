package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * DescribeInstanceCreditSpecifications on the Query wire. Terraform's aws_instance resource
 * reads credit_specification from this action and from nowhere else, since CreditSpecification
 * is not a member of the Instance shape DescribeInstances returns. Without it the read came back
 * empty and every plan after an apply showed a credit_specification diff for an instance nobody
 * had touched.
 */
@QuarkusTest
class Ec2InstanceCreditSpecificationsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ITEM =
            "DescribeInstanceCreditSpecificationsResponse.instanceCreditSpecificationSet.item.";

    @Test
    void t2DefaultsToStandardAndTheOtherBurstableFamiliesDefaultToUnlimited() {
        assertCpuCredits(launch("t2.micro", null), "standard");
        assertCpuCredits(launch("t3.micro", null), "unlimited");
        assertCpuCredits(launch("t3a.micro", null), "unlimited");
        assertCpuCredits(launch("t4g.micro", null), "unlimited");
    }

    @Test
    void anExplicitLaunchTimeCreditOptionWinsOverTheFamilyDefault() {
        assertCpuCredits(launch("t2.micro", "unlimited"), "unlimited");
        assertCpuCredits(launch("t3.micro", "standard"), "standard");
    }

    @Test
    void aNonBurstableInstanceIdIsAnError() {
        given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .formParam("InstanceId.1", launch("m5.large", null))
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    void anUnknownInstanceIdIsAnError() {
        given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .formParam("InstanceId.1", "i-00000000000000000")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    void anInvalidLaunchTimeCreditOptionIsRejected() {
        given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .formParam("CreditSpecification.CpuCredits", "boundless")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    /**
     * The unfiltered form reports only the instances running on the unlimited option, so a t2 on
     * its standard default is absent from it while the t3 beside it is present. Naming that same
     * t2's id still reports it, which is the difference the two request shapes carry.
     */
    @Test
    void theUnfilteredFormReportsOnlyUnlimitedInstances() {
        String standardInstance = launch("t2.micro", null);
        String unlimitedInstance = launch("t3.micro", null);

        List<String> reported = given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().xmlPath().getList(ITEM + "instanceId", String.class);

        assertTrue(reported.contains(unlimitedInstance));
        assertFalse(reported.contains(standardInstance));
    }

    @Test
    void maxResultsCapsThePageAndCannotBeCombinedWithInstanceIds() {
        launch("t3.micro", null);
        launch("t3.small", null);

        List<String> page = given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .formParam("MaxResults", "5")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().xmlPath().getList(ITEM + "instanceId", String.class);

        assertTrue(page.size() <= 5);

        given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .formParam("InstanceId.1", launch("t3.micro", null))
                .formParam("MaxResults", "5")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * Terraform checks BurstablePerformanceSupported on DescribeInstanceTypes before it reads a
     * credit specification at all, so the flag has to be right or the action is never called.
     */
    @Test
    void describeInstanceTypesReportsBurstablePerformanceSupport() {
        assertBurstablePerformanceSupported("t3.micro", "true");
        assertBurstablePerformanceSupported("t2.micro", "true");
        assertBurstablePerformanceSupported("t4g.medium", "true");
        assertBurstablePerformanceSupported("m5.large", "false");
    }

    private void assertBurstablePerformanceSupported(String instanceType, String expected) {
        given()
                .formParam("Action", "DescribeInstanceTypes")
                .formParam("InstanceType.1", instanceType)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstanceTypesResponse.instanceTypeSet.item.burstablePerformanceSupported",
                        equalTo(expected));
    }

    private void assertCpuCredits(String instanceId, String expected) {
        given()
                .formParam("Action", "DescribeInstanceCreditSpecifications")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(ITEM + "instanceId", equalTo(instanceId))
                .body(ITEM + "cpuCredits", equalTo(expected));
    }

    private String launch(String instanceType, String cpuCredits) {
        // t4g is Graviton, so it needs an arm64 image or the launch fails the architecture check.
        String imageId = instanceType.startsWith("t4g.") ? "ami-ubuntu2404-arm64" : "ami-0abcdef1234567890";
        RequestSpecification request = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", imageId)
                .formParam("InstanceType", instanceType)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER);
        if (cpuCredits != null) {
            request = request.formParam("CreditSpecification.CpuCredits", cpuCredits);
        }
        String instanceId = request
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        assertTrue(instanceId.startsWith("i-"));
        return instanceId;
    }
}
