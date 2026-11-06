package io.floci.aws.services.resourcegroups;

import io.floci.aws.core.common.ServiceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ResourceGroupsIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";

    @Inject
    ServiceRegistry registry;

    @Test
    void standardServiceRegistrationIncludesResourceGroups() {
        assertTrue(registry.getEnabledServices().contains("resource-groups"));
    }

    private static String auth(String service, String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261008/" + REGION + "/" + service + "/aws4_request";
    }

    private static ValidatableResponse call(String path, Object body) {
        return given().header("Authorization", auth("resource-groups", ACCOUNT))
                .contentType("application/json").body(body).post(path).then();
    }

    @Test
    void publicCapacityPoolLifecycleUsesRealEc2Records() {
        String name = "pool-" + UUID.randomUUID();
        String reservation = given().header("Authorization", auth("ec2", ACCOUNT))
                .formParam("Action", "CreateCapacityReservation").formParam("InstanceType", "t3.micro")
                .formParam("InstancePlatform", "Linux/UNIX").formParam("AvailabilityZone", REGION + "a")
                .formParam("InstanceCount", 1).post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateCapacityReservationResponse.capacityReservation.capacityReservationArn");
        String id = reservation.substring(reservation.lastIndexOf('/') + 1);
        try {
            String arn = call("/groups", "{\"Name\":\"" + name + "\",\"Configuration\":"
                    + ResourceGroupsServiceTest.CONFIGURATION + "}").statusCode(200)
                    .body("GroupConfiguration.Status", equalTo("UPDATE_COMPLETE"))
                    .extract().path("Group.GroupArn");
            try {
                call("/get-group", Map.of("GroupName", name)).statusCode(200).body("Group.GroupArn", equalTo(arn));
                call("/group-resources", Map.of("Group", arn, "ResourceArns", new String[]{reservation}))
                        .statusCode(200).body("Succeeded", contains(reservation)).body("Failed", empty())
                        .body("Pending", empty());
                call("/list-group-resources", Map.of("Group", arn)).statusCode(200)
                        .body("ResourceIdentifiers[0].ResourceArn", equalTo(reservation))
                        .body("Resources[0].Identifier.ResourceType", equalTo(ResourceGroupsService.RESOURCE_TYPE));
                given().header("Authorization", auth("resource-groups", "444455556666"))
                        .contentType("application/json").body(Map.of("GroupName", name))
                        .post("/get-group").then().statusCode(404).body("__type", equalTo("NotFoundException"));
            } finally {
                call("/delete-group", Map.of("GroupName", name)).statusCode(200).body("Group.GroupArn", equalTo(arn));
            }
            call("/get-group", Map.of("GroupName", name)).statusCode(404);
            given().header("Authorization", auth("ec2", ACCOUNT))
                    .formParam("Action", "DescribeCapacityReservations").formParam("CapacityReservationId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.state", equalTo("active"));
        } finally {
            given().header("Authorization", auth("ec2", ACCOUNT))
                    .formParam("Action", "CancelCapacityReservation").formParam("CapacityReservationId", id)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void standardRestErrorsRefuseUnsupportedAndMalformedRequests() {
        call("/groups", Map.of("Name", "unsupported", "ResourceQuery", Map.of())).statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        call("/groups", "[]").statusCode(400);
        call("/groups", "{").statusCode(400);
        call("/get-group", Map.of("GroupName", "missing-" + UUID.randomUUID())).statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "get-group", "delete-group", "group-resources", "list-group-resources"})
    void s3BucketPostsKeepTheirOwnRoutes(String bucket) {
        String authorization = auth("s3", ACCOUNT);
        given().header("Authorization", authorization).contentType("application/xml")
                .body("<CreateBucketConfiguration><LocationConstraint>" + REGION
                        + "</LocationConstraint></CreateBucketConfiguration>")
                .put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", authorization).body("value").put("/" + bucket + "/key")
                    .then().statusCode(200);
            given().header("Authorization", authorization).contentType("application/xml")
                    .body("<Delete><Object><Key>key</Key></Object></Delete>").post("/" + bucket + "?delete")
                    .then().statusCode(200).body("DeleteResult.Deleted.Key", equalTo("key"));
            given().header("Authorization", authorization).get("/" + bucket + "/key")
                    .then().statusCode(404);
        } finally {
            given().header("Authorization", authorization).delete("/" + bucket + "/key");
            given().header("Authorization", authorization).delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void querySignedResourceGroupsRequestsReachTheSameController() {
        given().queryParam("X-Amz-Credential", ACCOUNT + "/20261020/" + REGION + "/resource-groups/aws4_request")
                .contentType("application/json").body(Map.of("Group", "missing-" + UUID.randomUUID()))
                .post("/get-group").then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().contentType("application/json").body("{}").post("/_rg/groups").then().statusCode(400)
                .body(containsString("<Code>InvalidBucketName</Code>"));
    }
}
