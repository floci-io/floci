package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for managed prefix lists over the EC2 Query protocol: create, describe with
 * filters, entry versioning through modify, and delete.
 *
 * <p>Ordered because the cases build on one prefix list, mirroring how a client drives the
 * resource through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2ManagedPrefixListIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String LIST_NAME = "integration-prefix-list";

    private static String prefixListId;

    @Test
    @Order(1)
    void createManagedPrefixList() {
        prefixListId = given()
            .formParam("Action", "CreateManagedPrefixList")
            .formParam("PrefixListName", LIST_NAME)
            .formParam("AddressFamily", "IPv4")
            .formParam("MaxEntries", "5")
            .formParam("Entry.1.Cidr", "10.0.0.0/8")
            .formParam("Entry.1.Description", "corporate")
            .formParam("TagSpecification.1.ResourceType", "prefix-list")
            .formParam("TagSpecification.1.Tag.1.Key", "env")
            .formParam("TagSpecification.1.Tag.1.Value", "test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateManagedPrefixListResponse.prefixList.prefixListName", equalTo(LIST_NAME))
            .body("CreateManagedPrefixListResponse.prefixList.addressFamily", equalTo("IPv4"))
            .body("CreateManagedPrefixListResponse.prefixList.state", equalTo("create-complete"))
            .body("CreateManagedPrefixListResponse.prefixList.version", equalTo("1"))
            .body("CreateManagedPrefixListResponse.prefixList.maxEntries", equalTo("5"))
            .body("CreateManagedPrefixListResponse.prefixList.prefixListArn",
                    containsString(":prefix-list/pl-"))
            .body("CreateManagedPrefixListResponse.prefixList.tagSet.item.key", equalTo("env"))
            .extract().path("CreateManagedPrefixListResponse.prefixList.prefixListId");

        org.junit.jupiter.api.Assertions.assertTrue(prefixListId.startsWith("pl-"));
    }

    @Test
    @Order(2)
    void describeByIdReturnsTheCreatedList() {
        given()
            .formParam("Action", "DescribeManagedPrefixLists")
            .formParam("PrefixListId.1", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeManagedPrefixListsResponse.prefixListSet.item.prefixListName", equalTo(LIST_NAME));
    }

    @Test
    @Order(3)
    void describeIncludesAwsManagedGatewayLists() {
        given()
            .formParam("Action", "DescribeManagedPrefixLists")
            .formParam("Filter.1.Name", "prefix-list-name")
            .formParam("Filter.1.Value.1", "com.amazonaws.us-east-1.s3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeManagedPrefixListsResponse.prefixListSet.item.prefixListId", equalTo("pl-63a5400a"))
            .body("DescribeManagedPrefixListsResponse.prefixListSet.item.ownerId", equalTo("AWS"));
    }

    @Test
    @Order(4)
    void getEntriesReturnsTheInitialEntry() {
        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetManagedPrefixListEntriesResponse.entrySet.item.cidr", equalTo("10.0.0.0/8"))
            .body("GetManagedPrefixListEntriesResponse.entrySet.item.description", equalTo("corporate"));
    }

    @Test
    @Order(5)
    void modifyAddsAnEntryAndBumpsVersion() {
        given()
            .formParam("Action", "ModifyManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .formParam("AddEntry.1.Cidr", "192.168.0.0/16")
            .formParam("AddEntry.1.Description", "lab")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyManagedPrefixListResponse.prefixList.version", equalTo("2"))
            .body("ModifyManagedPrefixListResponse.prefixList.state", equalTo("modify-complete"));

        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetManagedPrefixListEntriesResponse.entrySet.item.cidr",
                    hasItems("10.0.0.0/8", "192.168.0.0/16"));
    }

    @Test
    @Order(6)
    void earlierVersionRemainsRetrievable() {
        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .formParam("TargetVersion", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetManagedPrefixListEntriesResponse.entrySet.item.cidr", equalTo("10.0.0.0/8"));
    }

    @Test
    @Order(7)
    void modifyWithStaleCurrentVersionIsRejected() {
        given()
            .formParam("Action", "ModifyManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .formParam("CurrentVersion", "1")
            .formParam("AddEntry.1.Cidr", "172.16.0.0/12")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("PrefixListVersionMismatch"));
    }

    @Test
    @Order(8)
    void modifyingAnAwsManagedListIsRejected() {
        given()
            .formParam("Action", "ModifyManagedPrefixList")
            .formParam("PrefixListId", "pl-63a5400a")
            .formParam("AddEntry.1.Cidr", "10.1.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
    }

    @Test
    @Order(9)
    void describeUnknownIdIsRejected() {
        given()
            .formParam("Action", "DescribeManagedPrefixLists")
            .formParam("PrefixListId.1", "pl-doesnotexist")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidPrefixListID.NotFound"));
    }

    @Test
    @Order(10)
    void legacyDescribePrefixListsStillServesGatewayLists() {
        given()
            .formParam("Action", "DescribePrefixLists")
            .formParam("Filter.1.Name", "prefix-list-name")
            .formParam("Filter.1.Value.1", "com.amazonaws.us-east-1.s3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribePrefixListsResponse.prefixListSet.item.prefixListId", equalTo("pl-63a5400a"))
            .body("DescribePrefixListsResponse.prefixListSet.item.cidrSet.item",
                    hasItems("52.216.0.0/15", "54.231.0.0/16"));
    }

    @Test
    @Order(11)
    void malformedNumericParametersReturnAClientError() {
        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .formParam("TargetVersion", "not-a-number")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "CreateManagedPrefixList")
            .formParam("PrefixListName", "malformed-max-entries")
            .formParam("AddressFamily", "IPv4")
            .formParam("MaxEntries", "plenty")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    /**
     * A blank value is present-but-malformed, not absent. Treating it as absent would drop the
     * conditional-version check and let a modification through that CurrentVersion asked to gate.
     */
    @Test
    @Order(11)
    void blankNumericParametersReturnAClientError() {
        given()
            .formParam("Action", "ModifyManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .formParam("CurrentVersion", "   ")
            .formParam("AddEntry.1.Cidr", "172.16.0.0/12")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .formParam("TargetVersion", "")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(12)
    void deleteManagedPrefixList() {
        given()
            .formParam("Action", "DeleteManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteManagedPrefixListResponse.prefixList.state", equalTo("delete-complete"));

        given()
            .formParam("Action", "DescribeManagedPrefixLists")
            .formParam("PrefixListId.1", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidPrefixListID.NotFound"));
    }
}
