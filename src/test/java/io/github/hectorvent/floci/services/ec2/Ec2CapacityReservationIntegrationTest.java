package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for EC2 Capacity Reservations over the Query protocol: create with inline
 * tags, describe by id and by filter, modify in place, tag round trip through CreateTags, and
 * cancel (lex00/floci#64 - previously entirely unimplemented, blocking
 * terraform-aws-modules/terraform-aws-autoscaling's flagship "complete" example, which declares
 * an {@code aws_ec2_capacity_reservation} targeted by a warm pool).
 *
 * <p>Ordered because the cases walk one reservation through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2CapacityReservationIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String capacityReservationId;

    @Test
    @Order(1)
    void createReturnsActiveOnTheFirstRead() {
        capacityReservationId = given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.micro")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("InstanceCount", "3")
            .formParam("InstanceMatchCriteria", "targeted")
            .formParam("TagSpecification.1.ResourceType", "capacity-reservation")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "warm-pool-reservation")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateCapacityReservationResponse.capacityReservation.state", equalTo("active"))
            .body("CreateCapacityReservationResponse.capacityReservation.instanceType", equalTo("t3.micro"))
            .body("CreateCapacityReservationResponse.capacityReservation.instancePlatform", equalTo("Linux/UNIX"))
            .body("CreateCapacityReservationResponse.capacityReservation.availabilityZone", equalTo("us-east-1a"))
            .body("CreateCapacityReservationResponse.capacityReservation.totalInstanceCount", equalTo("3"))
            .body("CreateCapacityReservationResponse.capacityReservation.availableInstanceCount", equalTo("3"))
            .body("CreateCapacityReservationResponse.capacityReservation.instanceMatchCriteria", equalTo("targeted"))
            .body("CreateCapacityReservationResponse.capacityReservation.tenancy", equalTo("default"))
            .body("CreateCapacityReservationResponse.capacityReservation.endDateType", equalTo("unlimited"))
            .body("CreateCapacityReservationResponse.capacityReservation.capacityReservationArn",
                    containsString(":capacity-reservation/cr-"))
            .body("CreateCapacityReservationResponse.capacityReservation.tagSet.item.key", equalTo("Name"))
            .body("CreateCapacityReservationResponse.capacityReservation.tagSet.item.value", equalTo("warm-pool-reservation"))
            .extract().path("CreateCapacityReservationResponse.capacityReservation.capacityReservationId");

        assertTrue(capacityReservationId.startsWith("cr-"), "id must use the cr- prefix");
    }

    @Test
    @Order(2)
    void describeByIdReturnsTheCreatedReservationStillActive() {
        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", capacityReservationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.capacityReservationId",
                    equalTo(capacityReservationId))
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.state", equalTo("active"));
    }

    @Test
    @Order(3)
    void describeSupportsInstanceTypeAndTagFilters() {
        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("Filter.1.Name", "instance-type")
            .formParam("Filter.1.Value.1", "t3.micro")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.capacityReservationId",
                    equalTo(capacityReservationId));

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "warm-pool-reservation")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.capacityReservationId",
                    equalTo(capacityReservationId));
    }

    @Test
    @Order(4)
    void createTagsIsVisibleOnTheNextDescribe() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", capacityReservationId)
            .formParam("Tag.1.Key", "env")
            .formParam("Tag.1.Value", "staging")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("Filter.1.Name", "tag:env")
            .formParam("Filter.1.Value.1", "staging")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.capacityReservationId",
                    equalTo(capacityReservationId));
    }

    @Test
    @Order(5)
    void modifyChangesInstanceCountAndAvailableCountTogether() {
        given()
            .formParam("Action", "ModifyCapacityReservation")
            .formParam("CapacityReservationId", capacityReservationId)
            .formParam("InstanceCount", "5")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyCapacityReservationResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", capacityReservationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.totalInstanceCount", equalTo("5"))
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.availableInstanceCount", equalTo("5"));
    }

    @Test
    @Order(6)
    void createWithoutInstanceCountIsRejected() {
        given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.nano")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("AvailabilityZone", "us-east-1b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"))
            .body("Response.Errors.Error.Message", containsString("InstanceCount"));
    }

    @Test
    @Order(7)
    void nonPositiveInstanceCountIsRejectedOnCreateAndModify() {
        given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.nano")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("InstanceCount", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "ModifyCapacityReservation")
            .formParam("CapacityReservationId", capacityReservationId)
            .formParam("InstanceCount", "-1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", capacityReservationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.totalInstanceCount", equalTo("5"))
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.availableInstanceCount", equalTo("5"));
    }

    @Test
    @Order(8)
    void createAcceptsAvailabilityZoneIdInsteadOfAvailabilityZone() {
        given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.nano")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("AvailabilityZoneId", "use1-az2")
            .formParam("InstanceCount", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateCapacityReservationResponse.capacityReservation.availabilityZoneId", equalTo("use1-az2"))
            .body("CreateCapacityReservationResponse.capacityReservation.totalInstanceCount", equalTo("1"))
            .body("CreateCapacityReservationResponse.capacityReservation.instanceMatchCriteria", equalTo("open"));

        given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.nano")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("InstanceCount", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(9)
    void missingRequiredParameterIsRejected() {
        given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(10)
    void cancelMarksTheReservationCancelledRatherThanDeletingIt() {
        given()
            .formParam("Action", "CancelCapacityReservation")
            .formParam("CapacityReservationId", capacityReservationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CancelCapacityReservationResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", capacityReservationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.state", equalTo("cancelled"))
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.availableInstanceCount", equalTo("0"));
    }

    @Test
    @Order(11)
    void describingAMissingReservationReturnsTheModelledError() {
        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", "cr-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidCapacityReservationId.NotFound"))
            .body("Response.Errors.Error.Message", containsString("cr-00000000000000000"));
    }

    /**
     * {@code parseTagsForResource} accepts both TagSpecification spellings for every action.
     * Order(1) covers the singular form. This test covers the plural form and reads the tags
     * back through Describe.
     */
    @Test
    @Order(12)
    void createHonoursThePluralTagSpecificationsParameter() {
        String id = given()
            .formParam("Action", "CreateCapacityReservation")
            .formParam("InstanceType", "t3.micro")
            .formParam("InstancePlatform", "Linux/UNIX")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("InstanceCount", "1")
            .formParam("InstanceMatchCriteria", "targeted")
            .formParam("TagSpecifications.1.ResourceType", "capacity-reservation")
            .formParam("TagSpecifications.1.Tag.1.Key", "Name")
            .formParam("TagSpecifications.1.Tag.1.Value", "plural-form-reservation")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateCapacityReservationResponse.capacityReservation.tagSet.item.key", equalTo("Name"))
            .body("CreateCapacityReservationResponse.capacityReservation.tagSet.item.value", equalTo("plural-form-reservation"))
            .extract().path("CreateCapacityReservationResponse.capacityReservation.capacityReservationId");

        given()
            .formParam("Action", "DescribeCapacityReservations")
            .formParam("CapacityReservationId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.tagSet.item.key", equalTo("Name"))
            .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.tagSet.item.value", equalTo("plural-form-reservation"));

        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"))
            .body("DescribeTagsResponse.tagSet.item.value", equalTo("plural-form-reservation"));
    }
}
