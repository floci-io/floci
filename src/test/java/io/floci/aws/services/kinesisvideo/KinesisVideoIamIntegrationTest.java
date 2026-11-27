package io.floci.aws.services.kinesisvideo;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.IamTestPolicies.allow;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Kinesis Video Streams requests are checked against the caller's policies through the generated routes: a stream
 * call on the stream it names, by its ARN or by its name through the ARN the stream is stored under, with the tags the
 * stream holds for {@code aws:ResourceTag}; {@code ListStreams} on {@code *}; and a {@code CreateStream} that tags the
 * stream it makes also as {@code kinesisvideo:TagStream}.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class KinesisVideoIamIntegrationTest {

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "kinesisvideo");

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aStreamReadIsRefusedWithoutThePermissionAllowedWithItAndRefusedOnceItIsRemoved() throws Exception {
        String arn = createStream(uniqueName());
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-iam-")) {
            denied(send(user, "/describeStream", byArn(arn)), "kinesisvideo:DescribeStream");
            try {
                SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "access",
                        "PolicyDocument", allow("\"kinesisvideo:DescribeStream\"", arn))).then().statusCode(200);
                send(user, "/describeStream", byArn(arn)).then().statusCode(200)
                        .body("StreamInfo.StreamARN", equalTo(arn));
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPolicy",
                        Map.of("UserName", user.name(), "PolicyName", "access")).then().statusCode(200);
            }
            denied(send(user, "/describeStream", byArn(arn)), "kinesisvideo:DescribeStream");
        } finally {
            deleteStream(arn);
        }
    }

    @Test
    void aCallerWithoutPermissionsIsRefusedBeforeAStreamIsCreatedListedOrDeleted() throws Exception {
        String name = uniqueName();
        String arn = createStream(name);
        String refused = uniqueName();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-iam-")) {
            denied(send(user, "/createStream", "{\"StreamName\":\"" + refused + "\"}"), "kinesisvideo:CreateStream");
            denied(send(user, "/listStreams", "{}"), "kinesisvideo:ListStreams");
            denied(send(user, "/deleteStream", byArn(arn)), "kinesisvideo:DeleteStream");
            send(admin, "/describeStream", "{\"StreamName\":\"" + refused + "\"}").then().statusCode(404);
            send(admin, "/describeStream", byArn(arn)).then().statusCode(200);
        } finally {
            deleteStream(arn);
        }
    }

    @Test
    void aStreamScopedAllowReachesThatStreamAndRefusesAnother() throws Exception {
        String allowed = createStream(uniqueName());
        String other = createStream(uniqueName());
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access",
                allow("[\"kinesisvideo:DescribeStream\",\"kinesisvideo:DeleteStream\"]", allowed))) {
            send(user, "/describeStream", byArn(allowed)).then().statusCode(200)
                    .body("StreamInfo.StreamARN", equalTo(allowed));
            denied(send(user, "/describeStream", byArn(other)), "kinesisvideo:DescribeStream");
            denied(send(user, "/deleteStream", byArn(other)), "kinesisvideo:DeleteStream");
            send(admin, "/describeStream", byArn(other)).then().statusCode(200);
            send(user, "/deleteStream", byArn(allowed)).then().statusCode(200);
            send(admin, "/describeStream", byArn(allowed)).then().statusCode(404);
        } finally {
            deleteStream(other);
        }
    }

    /**
     * A request naming the stream by {@code StreamName} is checked on the ARN the stream is stored under, creation time
     * included: an allow on that exact ARN reads it by name, and a deny on it refuses the read by name even beside an
     * allow on every resource.
     */
    @Test
    void aNameBasedReadIsCheckedOnTheStreamsStoredArn() throws Exception {
        String name = uniqueName();
        String arn = createStream(name);
        String byName = "{\"StreamName\":\"" + name + "\"}";
        String denyExact = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"kinesisvideo:DescribeStream\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"kinesisvideo:DescribeStream\",\"Resource\":\"" + arn + "\"}]}";
        try (SignedIamUsers.User allowed = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access",
                     allow("\"kinesisvideo:DescribeStream\"", arn));
             SignedIamUsers.User refused = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access", denyExact)) {
            send(allowed, "/describeStream", byName).then().statusCode(200)
                    .body("StreamInfo.StreamARN", equalTo(arn));
            denied(send(refused, "/describeStream", byName), "kinesisvideo:DescribeStream");
            denied(send(refused, "/describeStream", byArn(arn)), "kinesisvideo:DescribeStream");
        } finally {
            deleteStream(arn);
        }
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags the stream was created with, whether the request names the
     * stream by its ARN or by its name: an allow on one team's tag reads that team's stream and not an untagged one,
     * and a deny on it refuses that stream beside an allow on every resource.
     */
    @Test
    void aResourceTagConditionReadsTheTagsTheStreamHolds() throws Exception {
        String taggedName = uniqueName();
        String tagged = send(admin, "/createStream", tagged(taggedName, "video")).then().statusCode(200)
                .extract().path("StreamARN");
        String untagged = createStream(uniqueName());
        String onTeamVideo = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "\"kinesisvideo:DescribeStream\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":"
                + "{\"aws:ResourceTag/team\":\"video\"}}}]}";
        String denyTeamVideo = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"kinesisvideo:DescribeStream\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"kinesisvideo:DescribeStream\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"video\"}}}]}";
        try (SignedIamUsers.User team = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access", onTeamVideo);
             SignedIamUsers.User others = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access", denyTeamVideo)) {
            send(team, "/describeStream", byArn(tagged)).then().statusCode(200);
            send(team, "/describeStream", "{\"StreamName\":\"" + taggedName + "\"}").then().statusCode(200);
            denied(send(team, "/describeStream", byArn(untagged)), "kinesisvideo:DescribeStream");

            denied(send(others, "/describeStream", byArn(tagged)), "kinesisvideo:DescribeStream");
            denied(send(others, "/describeStream", "{\"StreamName\":\"" + taggedName + "\"}"),
                    "kinesisvideo:DescribeStream");
            send(others, "/describeStream", byArn(untagged)).then().statusCode(200);
        } finally {
            deleteStream(tagged);
            deleteStream(untagged);
        }
    }

    @Test
    void listStreamsIsAllowedOnAnyResource() throws Exception {
        String arn = createStream(uniqueName());
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access",
                allow("\"kinesisvideo:ListStreams\"", "*"))) {
            send(user, "/listStreams", "{}").then().statusCode(200);
        } finally {
            deleteStream(arn);
        }
    }

    @Test
    void aCreateThatTagsTheStreamAlsoNeedsTheTaggingPermission() throws Exception {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "\"kinesisvideo:CreateStream\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"kinesisvideo:TagStream\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"video\"}}}]}";
        String refused = uniqueName();
        String created = uniqueName();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-iam-", "access", policy)) {
            denied(send(user, "/createStream", tagged(refused, "audio")), "kinesisvideo:TagStream");
            send(admin, "/describeStream", "{\"StreamName\":\"" + refused + "\"}").then().statusCode(404);

            String arn = send(user, "/createStream", tagged(created, "video")).then().statusCode(200)
                    .extract().path("StreamARN");
            deleteStream(arn);
            String untagged = send(user, "/createStream", "{\"StreamName\":\"" + created + "\"}").then()
                    .statusCode(200).extract().path("StreamARN");
            deleteStream(untagged);
        }
    }

    /** IAM refused the request with the 401 Kinesis Video Streams' model declares for {@code AccessDeniedException}. */
    private static void denied(Response response, String action) {
        response.then().statusCode(401)
                .body("__type", equalTo("AccessDeniedException"))
                .body(containsString("not authorized to perform: " + action));
    }

    private static String uniqueName() {
        return "kvs-iam-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String byArn(String arn) {
        return "{\"StreamARN\":\"" + arn + "\"}";
    }

    private static String tagged(String name, String team) {
        return "{\"StreamName\":\"" + name + "\",\"Tags\":{\"team\":\"" + team + "\"}}";
    }

    private String createStream(String name) throws Exception {
        return send(admin, "/createStream", "{\"StreamName\":\"" + name + "\"}").then().statusCode(200)
                .extract().path("StreamARN");
    }

    private void deleteStream(String arn) throws Exception {
        send(admin, "/deleteStream", byArn(arn));
    }

    private Response send(SignedIamUsers.User user, String path, String body) throws Exception {
        return send(user.signer("kinesisvideo"), path, body);
    }

    private Response send(AwsRequestSigner signer, String path, String body) throws Exception {
        return SignedRestRequests.send(endpoint, signer, "POST", path, body, "application/json");
    }
}
