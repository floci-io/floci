package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudtrail.CloudTrailLogWriter;
import io.floci.aws.testing.CloudTrailLogFiles;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.QueryRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.CloudTrailRequests.invokeCloudTrail;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/**
 * IAM API calls reach a trail's log files as CloudTrail management events, whether IAM ran them,
 * refused them, or IAM enforcement refused them first.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamCloudTrailIntegrationTest {

    private static final String ACCOUNT = "661100000231";

    @TestHTTPResource("/")
    static URI baseUri;

    @Inject
    CloudTrailLogWriter writer;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void iamCallsAreRecordedAsManagementEvents() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "ct-user-" + suffix;
        String logs = startTrail(suffix);

        createUser(user, "Tags.member.1.Key", "team", "Tags.member.1.Value", "it");
        String accessKeyId = createAccessKey(user);
        iam(ACCOUNT, "GetUser", "UserName", user).statusCode(200);
        XmlPath refusal = iam(accessKeyId, "CreateUser", "UserName", user + "-other").statusCode(403)
                .extract().xmlPath();
        iam(ACCOUNT, "UpdateAccessKey", "UserName", user, "AccessKeyId", accessKeyId,
                "Status", "Inactive").statusCode(200);
        iam(ACCOUNT, "DeleteUser", "UserName", "ct-missing-" + suffix).statusCode(404);

        List<JsonNode> records = iamRecords(logs, suffix);

        JsonNode createUser = only(records, "CreateUser", ACCOUNT);
        assertThat(createUser.path("eventCategory").asText(), equalTo("Management"));
        assertThat(createUser.path("managementEvent").asBoolean(), equalTo(true));
        assertThat(createUser.path("readOnly").asBoolean(), equalTo(false));
        assertThat(createUser.path("awsRegion").asText(), equalTo("us-east-1"));
        assertThat(createUser.path("eventTime").asText(), matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
        assertThat(createUser.path("recipientAccountId").asText(), equalTo(ACCOUNT));
        assertThat(createUser.path("sourceIPAddress").asText(), equalTo("127.0.0.1"));
        assertThat(createUser.path("userAgent").asText(), not(emptyOrNullString()));
        assertThat(createUser.path("requestParameters").path("userName").asText(), equalTo(user));
        assertThat(createUser.path("requestParameters").path("tags").get(0).path("key").asText(), equalTo("team"));
        assertThat(createUser.path("requestParameters").path("tags").get(0).path("value").asText(), equalTo("it"));
        assertThat(createUser.path("responseElements").path("user").path("userName").asText(), equalTo(user));
        assertThat(createUser.path("responseElements").path("user").path("createDate").asText(),
                matchesPattern("[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M"));

        JsonNode createKey = only(records, "CreateAccessKey", ACCOUNT);
        JsonNode created = createKey.path("responseElements").path("accessKey");
        assertThat(created.path("accessKeyId").asText(), equalTo(accessKeyId));
        assertThat(created.path("secretAccessKey").isMissingNode(), equalTo(true));

        JsonNode getUser = only(records, "GetUser", ACCOUNT);
        assertThat(getUser.path("readOnly").asBoolean(), equalTo(true));
        assertThat(getUser.path("responseElements").isNull(), equalTo(true));

        JsonNode denied = only(records, "CreateUser", accessKeyId);
        assertThat(denied.path("errorCode").asText(), equalTo("AccessDenied"));
        // The record carries what the refusal answered, so the two can be matched by message and request ID.
        assertThat(denied.path("errorMessage").asText(), equalTo(refusal.getString("ErrorResponse.Error.Message")));
        assertThat(denied.path("requestID").asText(), equalTo(refusal.getString("ErrorResponse.RequestId")));
        assertThat(denied.path("userIdentity").path("userName").asText(), equalTo(user));
        assertThat(denied.path("requestParameters").isNull(), equalTo(true));
        assertThat(denied.path("responseElements").isNull(), equalTo(true));

        JsonNode deactivated = only(records, "UpdateAccessKey", ACCOUNT);
        assertThat(deactivated.path("requestParameters").path("accessKeyId").asText(), equalTo(accessKeyId));
        assertThat(deactivated.path("requestParameters").path("status").asText(), equalTo("Inactive"));
        assertThat(deactivated.path("responseElements").isNull(), equalTo(true));

        JsonNode missing = only(records, "DeleteUser", ACCOUNT);
        assertThat(missing.path("errorCode").asText(), equalTo("NoSuchEntityException"));
        assertThat(missing.path("requestParameters").path("userName").asText(), equalTo("ct-missing-" + suffix));
        assertThat(missing.path("errorMessage").asText(), not(emptyOrNullString()));
    }

    @Test
    void passwordsAreLeftOutOfTheRecord() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "ct-login-" + suffix;
        String logs = startTrail(suffix);
        createUser(user);
        iam(ACCOUNT, "CreateLoginProfile", "UserName", user, "Password", "Sup3r-secret-" + suffix,
                "PasswordResetRequired", "true").statusCode(200);
        iam(ACCOUNT, "DeleteLoginProfile", "UserName", user).statusCode(200);

        JsonNode login = only(iamRecords(logs, suffix), "CreateLoginProfile", ACCOUNT);
        assertThat(login.path("requestParameters").path("userName").asText(), equalTo(user));
        assertThat(login.path("requestParameters").path("passwordResetRequired").isBoolean(), equalTo(true));
        assertThat(login.path("requestParameters").has("password"), equalTo(false));
        assertThat(login.toString().contains("Sup3r-secret-"), equalTo(false));
        assertThat(login.path("responseElements").path("loginProfile").path("passwordResetRequired").isBoolean(),
                equalTo(true));
    }

    /** Starts a trail named after {@code suffix}, including global service events, and returns its bucket. */
    private String startTrail(String suffix) {
        String bucket = "ct-iam-logs-" + suffix;
        startTrail("ct-iam-" + suffix, bucket, true);
        return bucket;
    }

    /** Creates {@code user} with {@code params}, removing it after the test. */
    private void createUser(String user, String... params) {
        String[] request = new String[params.length + 2];
        request[0] = "UserName";
        request[1] = user;
        System.arraycopy(params, 0, request, 2, params.length);
        iam(ACCOUNT, "CreateUser", request).statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ACCOUNT, "iam"), user));
    }

    private String createAccessKey(String user) {
        return iam(ACCOUNT, "CreateAccessKey", "UserName", user).statusCode(200).extract().xmlPath()
                .getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private void startTrail(String trail, String bucket, boolean includeGlobalServiceEvents) {
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3"))
                .when().put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth(ACCOUNT, "s3"), bucket));
        cloudTrail("CreateTrail", "{\"Name\":\"" + trail + "\",\"S3BucketName\":\"" + bucket
                + "\",\"IncludeGlobalServiceEvents\":" + includeGlobalServiceEvents + "}");
        cleanup.register(() -> cloudTrail("DeleteTrail", "{\"Name\":\"" + trail + "\"}"));
        cloudTrail("StartLogging", "{\"Name\":\"" + trail + "\"}");
    }

    /** A key that deletes itself is recorded as its user's, as the caller was before the call removed the key. */
    @Test
    void aKeyDeletingItselfIsRecordedAsItsUser() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "ct-rotate-" + suffix;
        String logs = startTrail(suffix);
        createUser(user);
        iam(ACCOUNT, "PutUserPolicy", "UserName", user, "PolicyName", "rotate", "PolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iam:DeleteAccessKey\","
                        + "\"Resource\":\"*\"}]}").statusCode(200);
        String accessKeyId = createAccessKey(user);

        iam(accessKeyId, "DeleteAccessKey", "UserName", user, "AccessKeyId", accessKeyId).statusCode(200);

        JsonNode deleted = only(iamRecords(logs, suffix), "DeleteAccessKey", accessKeyId);
        assertThat(deleted.path("userIdentity").path("userName").asText(), equalTo(user));
        assertThat(deleted.path("errorCode").isMissingNode(), equalTo(true));
    }

    /**
     * A call a session token without MFA cannot make is refused before IAM's policies are read, and recorded as
     * the refusal answered it.
     */
    @Test
    void aSessionTokenRefusalIsRecorded() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "ct-session-" + suffix;
        String logs = startTrail(suffix);
        createUser(user);
        String accessKeyId = createAccessKey(user);
        XmlPath session = QueryRequests.request(accessKeyId, "sts", "GetSessionToken").formParam("Version", "2011-06-15")
                .when().post("/").then().statusCode(200).extract().xmlPath();
        String credentials = "GetSessionTokenResponse.GetSessionTokenResult.Credentials.";
        String sessionKey = session.getString(credentials + "AccessKeyId");

        XmlPath refusal = QueryRequests.request(sessionKey, "iam", "ListUsers")
                .header("X-Amz-Security-Token", session.getString(credentials + "SessionToken"))
                .formParam("Version", "2010-05-08").when().post("/").then().statusCode(403).extract().xmlPath();

        JsonNode refused = only(iamRecords(logs, sessionKey), "ListUsers", sessionKey);
        assertThat(refused.path("errorCode").asText(), equalTo("AccessDenied"));
        assertThat(refused.path("errorMessage").asText(), equalTo(refusal.getString("ErrorResponse.Error.Message")));
        assertThat(refused.path("requestParameters").isNull(), equalTo(true));
    }

    /** Event history keeps every management event, IAM's among them, whether or not a trail records it. */
    @Test
    void iamCallsReachEventHistoryWithoutATrail() {
        String user = "ct-history-" + UUID.randomUUID().toString().substring(0, 8);
        createUser(user);

        List<String> events = invokeCloudTrail(auth(ACCOUNT, "cloudtrail"), "LookupEvents",
                "{\"LookupAttributes\":[{\"AttributeKey\":\"EventName\",\"AttributeValue\":\"CreateUser\"}]}")
                .then().statusCode(200).extract().jsonPath().getList("Events.CloudTrailEvent", String.class);
        assertThat(events.stream().filter(event -> event.contains("\"eventName\":\"CreateUser\"")
                && event.contains("\"userName\":\"" + user + "\"")).count(), equalTo(1L));
    }

    /** IAM is a global service, so a trail that leaves out global service events records none of its calls. */
    @Test
    void onlyATrailIncludingGlobalServiceEventsRecordsIamCalls() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "ct-global-" + suffix;
        String included = startTrail(suffix);
        String excluded = "ct-iam-regional-" + suffix;
        startTrail(excluded, excluded, false);

        createUser(user);

        only(iamRecords(included, suffix), "CreateUser", ACCOUNT);
        assertThat(iamRecords(excluded, suffix), hasSize(0));
    }

    private void cloudTrail(String action, String body) {
        invokeCloudTrail(auth(ACCOUNT, "cloudtrail"), action, body).then().statusCode(200);
    }

    private ValidatableResponse iam(String accessKeyId, String action, String... params) {
        return QueryRequests.request(accessKeyId, "iam", action, params).formParam("Version", "2010-05-08")
                .when().post("/").then();
    }

    /**
     * The IAM records of this test's calls, read from the log files the writer delivers to {@code bucket}: those
     * whose parameters, caller or caller's key carry {@code marker}.
     */
    private List<JsonNode> iamRecords(String bucket, String marker) throws IOException {
        writer.flushNow();
        return CloudTrailLogFiles.records(bucket, auth(ACCOUNT, "s3")).stream()
                .filter(record -> "iam.amazonaws.com".equals(record.path("eventSource").asText()))
                .filter(record -> record.path("requestParameters").toString().contains(marker)
                        || record.path("userIdentity").path("userName").asText().contains(marker)
                        || record.path("userIdentity").path("accessKeyId").asText().equals(marker))
                .toList();
    }

    private static JsonNode only(List<JsonNode> records, String eventName, String accessKeyId) {
        List<JsonNode> found = records.stream()
                .filter(record -> eventName.equals(record.path("eventName").asText()))
                .filter(record -> accessKeyId.equals(record.path("userIdentity").path("accessKeyId").asText()))
                .toList();
        assertThat(eventName + " by " + accessKeyId + " in " + records, found, hasSize(1));
        return found.getFirst();
    }
}
