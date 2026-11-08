package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.model.LaunchSpecification;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class Ec2UserDataAttributeIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @ValueSource(strings = {"YQ==", "YQ", "/w", "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=", ""})
    void describesUserDataAsBase64(String encoded) {
        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("UserData", encoded).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.instanceId", equalTo(id))
                    .body("DescribeInstanceAttributeResponse.userData.size()", equalTo(1))
                    .body("DescribeInstanceAttributeResponse.userData.value.text()", equalTo(encoded));
        } finally {
            terminate(id);
        }
    }

    @Test
    void describesAbsentUserDataWithoutInventingAValue() {
        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.userData.size()", equalTo(1))
                    .body("DescribeInstanceAttributeResponse.userData.value.size()", equalTo(0));
        } finally {
            terminate(id);
        }
    }

    @Test
    void modifyingUserDataNeedsAStoppedInstanceAndIsDescribedBack() {
        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("UserData", "b2xk").post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            // A running instance refuses the change, as on AWS.
            given().header("Authorization", AUTH).formParam("Action", "ModifyInstanceAttribute")
                    .formParam("InstanceId", id).formParam("UserData.Value", "bmV3")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("IncorrectInstanceState"));
            given().header("Authorization", AUTH).formParam("Action", "StopInstances")
                    .formParam("InstanceId.1", id).post("/").then().statusCode(200);

            given().header("Authorization", AUTH).formParam("Action", "ModifyInstanceAttribute")
                    .formParam("InstanceId", id).formParam("UserData.Value", "bmV3")
                    .post("/").then().statusCode(200)
                    .body("ModifyInstanceAttributeResponse.return", equalTo("true"));
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.userData.value.text()", equalTo("bmV3"));
        } finally {
            terminate(id);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"YQ==", "YQ", "/w", "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=", ""})
    void spotLaunchPreservesOriginalUserData(String encoded) {
        Response response = requestSpotInstance(encoded);
        String id = response.path("RequestSpotInstancesResponse.spotInstanceRequestSet.item.instanceId");
        String requestId = response.path("RequestSpotInstancesResponse.spotInstanceRequestSet.item.spotInstanceRequestId");
        try {
            String spec = "RequestSpotInstancesResponse.spotInstanceRequestSet.item.launchSpecification.userData";
            if (encoded == null) {
                response.then().body(spec + ".size()", equalTo(0));
            } else {
                response.then().body(spec + ".text()", equalTo(encoded));
            }
            given().header("Authorization", AUTH).formParam("Action", "DescribeSpotInstanceRequests")
                    .formParam("SpotInstanceRequestId.1", requestId).post("/").then().statusCode(200)
                    .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item.launchSpecification.userData.text()",
                            equalTo(encoded == null ? "" : encoded));
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.userData.value.text()", equalTo(encoded == null ? "" : encoded));
        } finally {
            terminateAndCancelSpotRequest(id, requestId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " \t\r\n"})
    void spotLaunchTreatsWhitespaceUserDataAsAbsent(String encoded) {
        Response response = requestSpotInstance(encoded);
        String id = response.path("RequestSpotInstancesResponse.spotInstanceRequestSet.item.instanceId");
        String requestId = response.path("RequestSpotInstancesResponse.spotInstanceRequestSet.item.spotInstanceRequestId");
        try {
            response.then().body("RequestSpotInstancesResponse.spotInstanceRequestSet.item.launchSpecification.userData.size()",
                    equalTo(0));
            given().header("Authorization", AUTH).formParam("Action", "DescribeSpotInstanceRequests")
                    .formParam("SpotInstanceRequestId.1", requestId).post("/").then().statusCode(200)
                    .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item.launchSpecification.userData.size()",
                            equalTo(0));
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.userData.value.size()", equalTo(0));
        } finally {
            terminateAndCancelSpotRequest(id, requestId);
        }
    }

    @Test
    void spotLaunchRejectsInvalidEncodedUserDataBeforeCreatingRequests() {
        for (String encoded : new String[]{"not-base64!", "H4sI", Base64.getEncoder().encodeToString(new byte[16385])}) {
            int before = given().header("Authorization", AUTH).formParam("Action", "DescribeSpotInstanceRequests")
                    .post("/").then().statusCode(200).extract()
                    .path("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item.size()");
            given().header("Authorization", AUTH).formParam("Action", "RequestSpotInstances")
                    .formParam("LaunchSpecification.ImageId", "ami-0abcdef1234567890")
                    .formParam("LaunchSpecification.InstanceType", "t3.micro")
                    .formParam("LaunchSpecification.UserData", encoded)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            given().header("Authorization", AUTH).formParam("Action", "DescribeSpotInstanceRequests")
                    .post("/").then().statusCode(200)
                    .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item.size()", equalTo(before));
        }
    }

    @Test
    void spotLaunchSpecificationKeepsEncodingAcrossStoredJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LaunchSpecification spec = new LaunchSpecification();
        spec.setUserData("execution text");
        spec.setEncodedUserData("/w");
        LaunchSpecification restored = mapper.readValue(mapper.writeValueAsBytes(spec), LaunchSpecification.class);
        assertEquals("/w", restored.getEncodedUserData());
        assertEquals("execution text", restored.getUserData());
        LaunchSpecification legacy = mapper.readValue("{\"userData\":\"legacy text\"}", LaunchSpecification.class);
        assertNull(legacy.getEncodedUserData());
        assertEquals("legacy text", legacy.getUserData());
    }

    private Response requestSpotInstance(String encoded) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "RequestSpotInstances")
                .formParam("LaunchSpecification.ImageId", "ami-0abcdef1234567890")
                .formParam("LaunchSpecification.InstanceType", "t3.micro");
        if (encoded != null) {
            request.formParam("LaunchSpecification.UserData", encoded);
        }
        Response response = request.post("/");
        response.then().statusCode(200);
        return response;
    }

    private void terminateAndCancelSpotRequest(String id, String requestId) {
        try {
            terminate(id);
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "CancelSpotInstanceRequests")
                    .formParam("SpotInstanceRequestId.1", requestId).post("/").then().statusCode(200);
        }
    }

    private void terminate(String id) {
        given().header("Authorization", AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", id).post("/").then().statusCode(200);
    }
}
