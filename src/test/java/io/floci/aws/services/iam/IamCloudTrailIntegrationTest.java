package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudtrail.CloudTrailLogWriter;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String logs = "ct-iam-logs-" + suffix;
        String trail = "ct-iam-" + suffix;
        startTrail(trail, logs);

        iam(ACCOUNT, "CreateUser", "UserName", user, "Tags.member.1.Key", "team",
                "Tags.member.1.Value", "it").statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ACCOUNT, "iam"), user));
        XmlPath key = iam(ACCOUNT, "CreateAccessKey", "UserName", user).statusCode(200).extract().xmlPath();
        String accessKeyId = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        iam(ACCOUNT, "GetUser", "UserName", user).statusCode(200);
        iam(accessKeyId, "CreateUser", "UserName", user + "-other").statusCode(403);
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
        assertThat(denied.path("errorMessage").asText(),
                matchesPattern("User: arn:aws:iam::" + ACCOUNT + ":\\S+ is not authorized to perform: iam:CreateUser .*"));
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
        String logs = "ct-iam-logs-" + suffix;
        startTrail("ct-iam-" + suffix, logs);
        iam(ACCOUNT, "CreateUser", "UserName", user).statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ACCOUNT, "iam"), user));
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

    private void startTrail(String trail, String bucket) {
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3"))
                .when().put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth(ACCOUNT, "s3"), bucket));
        cloudTrail("CreateTrail", "{\"Name\":\"" + trail + "\",\"S3BucketName\":\"" + bucket + "\"}");
        cleanup.register(() -> cloudTrail("DeleteTrail", "{\"Name\":\"" + trail + "\"}"));
        cloudTrail("StartLogging", "{\"Name\":\"" + trail + "\"}");
    }

    private void cloudTrail(String action, String body) {
        given().port(baseUri.getPort())
                .header("Authorization", auth(ACCOUNT, "cloudtrail"))
                .header("X-Amz-Target", "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }

    private ValidatableResponse iam(String accessKeyId, String action, String... params) {
        RequestSpecification request = given().port(baseUri.getPort())
                .header("Authorization", auth(accessKeyId, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        for (int i = 0; i < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    /** The IAM records of this test's calls, read from the log files the writer delivers to {@code bucket}. */
    private List<JsonNode> iamRecords(String bucket, String suffix) throws IOException {
        writer.flushNow();
        List<String> keys = given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3"))
                .queryParam("list-type", "2").queryParam("prefix", "AWSLogs/")
                .when().get("/" + bucket)
                .then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
        List<JsonNode> records = new ArrayList<>();
        for (String key : keys) {
            byte[] gz = given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3"))
                    .when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                for (JsonNode record : MAPPER.readTree(in.readAllBytes()).path("Records")) {
                    if ("iam.amazonaws.com".equals(record.path("eventSource").asText())
                            && (record.path("requestParameters").toString().contains(suffix)
                                || record.path("userIdentity").path("userName").asText().contains(suffix))) {
                        records.add(record);
                    }
                }
            }
        }
        return records;
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
