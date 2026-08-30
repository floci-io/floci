package io.github.hectorvent.floci.services.codegurureviewer;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeGuruReviewerIntegrationTest {

    private static final String CONNECTION_ARN =
            "arn:aws:codestar-connections:us-east-1:000000000000:connection/11111111-2222-3333-4444-555555555555";

    private static String codeCommitAssociationArn;
    private static String bitbucketAssociationArn;
    private static String s3AssociationArn;

    @Test
    @Order(1)
    void associateCodeCommitRepository() {
        codeCommitAssociationArn = given()
            .contentType("application/json")
            .body("""
                {"Repository": {"CodeCommit": {"Name": "floci-service"}},
                 "Tags": {"team": "reviewers"}}
                """)
        .when()
            .post("/associations")
        .then()
            .statusCode(200)
            .body("RepositoryAssociation.AssociationId", notNullValue())
            .body("RepositoryAssociation.AssociationArn",
                    matchesPattern("^arn:aws:codeguru-reviewer:us-east-1:000000000000:association:"
                            + "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"))
            .body("RepositoryAssociation.Name", equalTo("floci-service"))
            .body("RepositoryAssociation.Owner", equalTo("000000000000"))
            .body("RepositoryAssociation.ProviderType", equalTo("CodeCommit"))
            .body("RepositoryAssociation.State", equalTo("Associated"))
            .body("RepositoryAssociation.StateReason", notNullValue())
            .body("RepositoryAssociation.CreatedTimeStamp", notNullValue())
            .body("RepositoryAssociation.LastUpdatedTimeStamp", notNullValue())
            .body("RepositoryAssociation.KMSKeyDetails.EncryptionOption", equalTo("AWS_OWNED_CMK"))
            .body("Tags.team", equalTo("reviewers"))
            .extract().path("RepositoryAssociation.AssociationArn");
    }

    @Test
    @Order(2)
    void describeIsAssociatedOnFirstRead() {
        given()
        .when()
            .get("/associations/" + codeCommitAssociationArn)
        .then()
            .statusCode(200)
            .body("RepositoryAssociation.AssociationArn", equalTo(codeCommitAssociationArn))
            .body("RepositoryAssociation.State", equalTo("Associated"))
            .body("RepositoryAssociation.ProviderType", equalTo("CodeCommit"))
            .body("Tags.team", equalTo("reviewers"));
    }

    @Test
    @Order(3)
    void describeMissingAssociationReturnsNotFound() {
        given()
        .when()
            .get("/associations/arn:aws:codeguru-reviewer:us-east-1:000000000000:association:"
                    + "00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(4)
    void associatingTheSameRepositoryTwiceConflicts() {
        given()
            .contentType("application/json")
            .body("{\"Repository\": {\"CodeCommit\": {\"Name\": \"floci-service\"}}}")
        .when()
            .post("/associations")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(5)
    void associateRejectsRepositoryWithoutExactlyOneMember() {
        given()
            .contentType("application/json")
            .body("{\"Repository\": {}}")
        .when()
            .post("/associations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .contentType("application/json")
            .body("""
                {"Repository": {"CodeCommit": {"Name": "a"},
                                "S3Bucket": {"Name": "b", "BucketName": "codeguru-floci"}}}
                """)
        .when()
            .post("/associations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void associateBitbucketRepositoryDerivesOwnerAndProviderType() {
        bitbucketAssociationArn = given()
            .contentType("application/json")
            .body("""
                {"Repository": {"Bitbucket": {"Name": "floci-web", "Owner": "floci-team",
                                              "ConnectionArn": "%s"}}}
                """.formatted(CONNECTION_ARN))
        .when()
            .post("/associations")
        .then()
            .statusCode(200)
            .body("RepositoryAssociation.Name", equalTo("floci-web"))
            .body("RepositoryAssociation.Owner", equalTo("floci-team"))
            .body("RepositoryAssociation.ProviderType", equalTo("Bitbucket"))
            .body("RepositoryAssociation.ConnectionArn", equalTo(CONNECTION_ARN))
            .body("RepositoryAssociation.State", equalTo("Associated"))
            .extract().path("RepositoryAssociation.AssociationArn");
    }

    @Test
    @Order(7)
    void bitbucketRepositoryRequiresConnectionArn() {
        given()
            .contentType("application/json")
            .body("{\"Repository\": {\"Bitbucket\": {\"Name\": \"floci-api\", \"Owner\": \"floci-team\"}}}")
        .when()
            .post("/associations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(8)
    void associateS3RepositoryCarriesBucketDetails() {
        s3AssociationArn = given()
            .contentType("application/json")
            .body("""
                {"Repository": {"S3Bucket": {"Name": "floci-batch", "BucketName": "codeguru-reviewer-floci"}},
                 "KMSKeyDetails": {"EncryptionOption": "CUSTOMER_MANAGED_CMK",
                                   "KMSKeyId": "11111111-2222-3333-4444-555555555555"}}
                """)
        .when()
            .post("/associations")
        .then()
            .statusCode(200)
            .body("RepositoryAssociation.Name", equalTo("floci-batch"))
            .body("RepositoryAssociation.ProviderType", equalTo("S3Bucket"))
            .body("RepositoryAssociation.Owner", equalTo("000000000000"))
            .body("RepositoryAssociation.S3RepositoryDetails.BucketName", equalTo("codeguru-reviewer-floci"))
            .body("RepositoryAssociation.KMSKeyDetails.EncryptionOption", equalTo("CUSTOMER_MANAGED_CMK"))
            .body("RepositoryAssociation.KMSKeyDetails.KMSKeyId", equalTo("11111111-2222-3333-4444-555555555555"))
            .extract().path("RepositoryAssociation.AssociationArn");
    }

    @Test
    @Order(9)
    void customerManagedKeyRequiresKeyId() {
        given()
            .contentType("application/json")
            .body("""
                {"Repository": {"CodeCommit": {"Name": "floci-keyless"}},
                 "KMSKeyDetails": {"EncryptionOption": "CUSTOMER_MANAGED_CMK"}}
                """)
        .when()
            .post("/associations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void listRepositoryAssociations() {
        given()
        .when()
            .get("/associations")
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries.Name",
                    hasItem("floci-service"))
            .body("RepositoryAssociationSummaries.Name", hasItem("floci-web"))
            .body("RepositoryAssociationSummaries.State", hasItem("Associated"));

        given()
        .when()
            .get("/associations?ProviderType=Bitbucket")
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries.size()", equalTo(1))
            .body("RepositoryAssociationSummaries[0].Name", equalTo("floci-web"))
            .body("RepositoryAssociationSummaries[0].AssociationArn", equalTo(bitbucketAssociationArn))
            .body("RepositoryAssociationSummaries[0].ConnectionArn", equalTo(CONNECTION_ARN));

        given()
        .when()
            .get("/associations?Name=nothing-here")
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries", emptyIterable());
    }

    @Test
    @Order(11)
    void listRepositoryAssociationsPaginates() {
        String firstPageArn =
        given()
        .when()
            .get("/associations?MaxResults=1")
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries.size()", equalTo(1))
            .body("NextToken", notNullValue())
        .extract()
            .path("RepositoryAssociationSummaries[0].AssociationArn");

        String nextToken =
        given()
        .when()
            .get("/associations?MaxResults=1")
        .then()
        .extract()
            .path("NextToken");

        given()
        .when()
            .get("/associations?MaxResults=1&NextToken=" + nextToken)
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries.size()", equalTo(1))
            .body("RepositoryAssociationSummaries[0].AssociationArn", not(equalTo(firstPageArn)));

        given()
        .when()
            .get("/associations?MaxResults=0")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
        .when()
            .get("/associations?MaxResults=abc")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(12)
    void tagRoundTripOverTheSharedTagsPath() {
        given()
        .when()
            .get("/tags/" + codeCommitAssociationArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("reviewers"));

        given()
            .contentType("application/json")
            .body("{\"Tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/" + codeCommitAssociationArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + codeCommitAssociationArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("reviewers"))
            .body("Tags.env", equalTo("test"));

        given()
        .when()
            .delete("/tags/" + codeCommitAssociationArn + "?tagKeys=env")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + codeCommitAssociationArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("reviewers"))
            .body("Tags.env", equalTo(null));

        given()
        .when()
            .get("/associations/" + codeCommitAssociationArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("reviewers"));
    }

    @Test
    @Order(13)
    void tagsForUnknownAssociationReturnResourceNotFound() {
        given()
        .when()
            .get("/tags/arn:aws:codeguru-reviewer:us-east-1:000000000000:association:"
                    + "00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(14)
    void disassociateRepository() {
        given()
        .when()
            .delete("/associations/" + s3AssociationArn)
        .then()
            .statusCode(200)
            .body("RepositoryAssociation.AssociationArn", equalTo(s3AssociationArn))
            .body("RepositoryAssociation.State", equalTo("Disassociated"));

        given()
        .when()
            .get("/associations/" + s3AssociationArn)
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));

        given()
        .when()
            .get("/associations?Name=floci-batch")
        .then()
            .statusCode(200)
            .body("RepositoryAssociationSummaries", emptyIterable());
    }

    @Test
    @Order(15)
    void describeRejectsAnArnFromAnotherService() {
        given()
        .when()
            .get("/associations/arn:aws:s3:::not-an-association")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("association ARN"));
    }
}
