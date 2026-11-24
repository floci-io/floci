package io.floci.aws.services.organizations;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.Session;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code CloseAccount} end to end with IAM enforcement off, Floci's default: the account reads as closed in its
 * organization, and every credential that resolves to it is refused across the wire protocols.
 *
 * <p>Runs as its own management account, so it never contends for one with the other Organizations suites. As on
 * AWS, a closed account stays in its organization and nothing in it can be deleted, so each test ends with
 * {@code /_floci/state/reset}. Test classes run one at a time, so the reset clears only what this class and the
 * ones before it created.
 */
@QuarkusTest
class OrganizationsCloseAccountIntegrationTest {

    private static final String MANAGEMENT_ACCOUNT = "858585858585";
    private static final String SECRET = "test";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final OrganizationsFixture organizations = new OrganizationsFixture();
    private String closed;
    private String open;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createOrganizationWithTwoMembers() {
        organizations.request(MANAGEMENT_ACCOUNT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        closed = organizations.createAccount(MANAGEMENT_ACCOUNT, "closed@example.com", "Closed");
        open = organizations.createAccount(MANAGEMENT_ACCOUNT, "open@example.com", "Open");
    }

    /** Runs before the cleanup extension, which then finds no user left to delete. */
    @AfterEach
    void resetState() {
        given().post("/_floci/state/reset").then().statusCode(200);
    }

    @Test
    void aClosedAccountReadsClosedWhereverTheOrganizationListsIt() {
        closeAccount(closed);

        organizations.request(MANAGEMENT_ACCOUNT, "DescribeAccount", "{\"AccountId\":\"" + closed + "\"}")
                .post("/").then().statusCode(200)
                .body("Account.State", equalTo("CLOSED"))
                .body("Account.Status", equalTo("SUSPENDED"));
        String root = organizations.request(MANAGEMENT_ACCOUNT, "ListRoots", "{}")
                .post("/").then().statusCode(200).extract().jsonPath().getString("Roots[0].Id");
        assertListedAsClosed(organizations.request(MANAGEMENT_ACCOUNT, "ListAccounts", "{}"));
        assertListedAsClosed(organizations.request(MANAGEMENT_ACCOUNT, "ListAccountsForParent",
                "{\"ParentId\":\"" + root + "\"}"));
    }

    @Test
    void closingAClosedAccountAgainIsRefused() {
        closeAccount(closed);

        organizations.request(MANAGEMENT_ACCOUNT, "CloseAccount", "{\"AccountId\":\"" + closed + "\"}")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("AccountAlreadyClosedException"))
                .body("message", equalTo("You attempted to close an account that is already closed."));
    }

    @Test
    void theClosedAccountsOwnKeyIsRefusedInEveryProtocol() {
        AwsCredentials key = AwsBasicCredentials.create(closed, SECRET);
        assertEquals(closed, callerAccount(key));
        closeAccount(closed);

        assertRefused(key, StsClient.builder(), StsClient::getCallerIdentity, 403, "InvalidClientTokenId");
        assertRefused(key, DynamoDbClient.builder(), DynamoDbClient::listTables, 403, "UnrecognizedClientException");
        assertRefused(key, S3Client.builder(), S3Client::listBuckets, 403, "InvalidAccessKeyId");
        // URLConnection cannot read the body of EC2's 401, so the SDK would see no error code: read it directly.
        given().header("Authorization", auth(closed, "ec2"))
                .formParam("Action", "DescribeVpcs").formParam("Version", "2016-11-15")
                .post("/").then().statusCode(401)
                .body("Response.Errors.Error.Code", equalTo("AuthFailure"));
    }

    /** A presigned POST carries its credential in the form body, not in a header or the query string. */
    @Test
    void theClosedAccountsPresignedPostIsRefused() {
        String bucket = "closed-account-post-" + UUID.randomUUID();
        // Unsigned, like the POST below, so both reach the default account.
        given().put("/" + bucket).then().statusCode(200);
        closeAccount(closed);

        presignedPost(bucket, open).statusCode(204);
        presignedPost(bucket, closed).statusCode(403).body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    @Test
    void theClosedAccountCannotBeRemovedFromTheOrganization() {
        closeAccount(closed);

        organizations.request(MANAGEMENT_ACCOUNT, "RemoveAccountFromOrganization", "{\"AccountId\":\"" + closed + "\"}")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("ConstraintViolationException"));
        assertRefused(AwsBasicCredentials.create(closed, SECRET), StsClient.builder(), StsClient::getCallerIdentity,
                403, "InvalidClientTokenId");
    }

    @Test
    void anIamUsersKeyInTheClosedAccountIsRefused() {
        UserCredentials user = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(closed, "iam"),
                "closing-user");
        AwsCredentials key = AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey());
        assertEquals(closed, callerAccount(key));
        closeAccount(closed);

        assertRefused(key, StsClient.builder(), StsClient::getCallerIdentity, 403, "InvalidClientTokenId");
    }

    @Test
    void aSessionInTheClosedAccountIsRefused() {
        Session session = EnforcementFixtures.assumeRole(endpoint.getPort(), MANAGEMENT_ACCOUNT,
                "arn:aws:iam::" + closed + ":role/OrganizationAccountAccessRole");
        AwsCredentials key = AwsSessionCredentials.create(session.accessKeyId(), SECRET, session.sessionToken());
        assertEquals(closed, callerAccount(key));
        closeAccount(closed);

        assertRefused(key, StsClient.builder(), StsClient::getCallerIdentity, 403, "InvalidClientTokenId");
    }

    @Test
    void theOtherAccountsKeepWorking() {
        closeAccount(closed);

        assertEquals(open, callerAccount(AwsBasicCredentials.create(open, SECRET)));
        assertEquals(MANAGEMENT_ACCOUNT, callerAccount(AwsBasicCredentials.create(MANAGEMENT_ACCOUNT, SECRET)));
    }

    private void assertListedAsClosed(RequestSpecification listing) {
        listing.post("/").then().statusCode(200)
                .body("Accounts.findAll { it.Id == '" + closed + "' }.State", contains("CLOSED"))
                .body("Accounts.findAll { it.Id == '" + closed + "' }.Status", contains("SUSPENDED"))
                .body("Accounts.findAll { it.Id == '" + open + "' }.State", contains("ACTIVE"));
    }

    private <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C extends SdkClient> void assertRefused(
            AwsCredentials credentials, B builder, Function<C, ?> call, int status, String errorCode) {
        try (C client = AwsTestClients.create(builder, credentials, Region.US_EAST_1, endpoint)) {
            AwsServiceException refused = assertThrows(AwsServiceException.class, () -> call.apply(client));
            assertEquals(status, refused.statusCode());
            assertEquals(errorCode, refused.awsErrorDetails().errorCode());
        }
    }

    private ValidatableResponse presignedPost(String bucket, String accountId) {
        return given().multiPart("key", accountId)
                .multiPart("x-amz-credential", accountId + "/20261124/us-east-1/s3/aws4_request")
                .multiPart("file", accountId, "body".getBytes(StandardCharsets.UTF_8), "text/plain")
                .when().post("/" + bucket).then();
    }

    private void closeAccount(String accountId) {
        organizations.request(MANAGEMENT_ACCOUNT, "CloseAccount", "{\"AccountId\":\"" + accountId + "\"}")
                .post("/").then().statusCode(200);
    }

    private String callerAccount(AwsCredentials credentials) {
        try (StsClient sts = AwsTestClients.create(StsClient.builder(), credentials, Region.US_EAST_1, endpoint)) {
            return sts.getCallerIdentity().account();
        }
    }
}
