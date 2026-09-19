package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code s3:ExistingObjectTag/<key>} and {@code s3:RequestObjectTag/<key>} under IAM enforcement.
 *
 * <p>Every expectation here is a row measured against real AWS (us-east-2, 2026-09-18, an IAM user
 * whose only policy was the statement under test, each policy proven live before it was tested),
 * for choudoufu's record-store isolation model (INTENTIUS/choudoufu#1342). Two of the rows are not
 * what the documentation alone would lead you to write, and they are the reason this test states
 * its provenance:
 *
 * <ul>
 *   <li>{@code s3:DeleteObject} is NOT given {@code s3:ExistingObjectTag}. An allow conditioned on it
 *       denies the delete of a correctly tagged object.</li>
 *   <li>A {@code PutObject} carrying {@code If-Match} is also authorized as {@code s3:GetObject}, and
 *       that check is made WITHOUT the object's tags: with a tag-conditioned GetObject allow the
 *       conditional write is denied, with a prefix-scoped one it is allowed.</li>
 * </ul>
 *
 * <p>Before this, neither key was resolved at all, so a statement conditioned on one never matched
 * and everything was denied - including what AWS allows.
 */
@QuarkusTest
@TestProfile(S3ObjectTagConditionEnforcementIntegrationTest.IamEnforcementProfile.class)
class S3ObjectTagConditionEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String REGION = "us-east-1";

    private static final String EXISTING_A = "\"Condition\":{\"StringEquals\":{\"s3:ExistingObjectTag/tofu-estate\":\"a\"}}";
    private static final String REQUEST_A = "\"Condition\":{\"StringEquals\":{\"s3:RequestObjectTag/tofu-estate\":\"a\"}}";

    @Test
    void existingObjectTagGatesReads() {
        Fixture f = new Fixture("read");
        // Control: with no policy at all the user is denied, so enforcement is on.
        assertEquals(403, f.get("a/tagged-a"));

        f.policy(allow("s3:GetObject", f.objects(), EXISTING_A));
        assertEquals(200, f.get("a/tagged-a"), "own tag");
        assertEquals(403, f.get("a/tagged-b"), "a foreign tag");
        assertEquals(403, f.get("a/untagged"), "no tag at all: StringEquals on an absent key does not match");
    }

    @Test
    void requestObjectTagGatesWritesAndExistingObjectTagCannot() {
        Fixture f = new Fixture("write");
        f.policy(allow("\"s3:PutObject\",\"s3:PutObjectTagging\"", f.objects(), REQUEST_A));
        assertEquals(200, f.put("a/new-1", "tofu-estate=a", null), "first create, tagged for this estate");
        assertEquals(200, f.put("a/new-2", "tofu-estate=a&tofu-address=aws_thing.x", null), "extra tags beside it");
        assertEquals(403, f.put("a/new-3", null, null), "untagged");
        assertEquals(403, f.put("a/new-4", "tofu-estate=b", null), "tagged for another estate");

        // The policy that reviews correctly and bricks a new estate: a create cannot be gated on
        // the tags of an object that does not exist yet.
        f.policy(allow("\"s3:PutObject\",\"s3:PutObjectTagging\"", f.objects(), EXISTING_A));
        assertEquals(403, f.put("a/brand-new", "tofu-estate=a", null), "ExistingObjectTag alone locks out the first write");
    }

    @Test
    void deleteIsNotGivenTheExistingObjectTag() {
        Fixture f = new Fixture("delete");
        f.policy(allow("s3:DeleteObject", f.objects(), EXISTING_A));
        assertEquals(403, f.delete("a/tagged-a"), "measured on AWS: a tag-conditioned delete is denied even for a correctly tagged object");

        f.policy(allow("s3:DeleteObject", f.objects(), null));
        assertEquals(204, f.delete("a/tagged-a"), "scoped by prefix alone it is allowed");
    }

    @Test
    void aConditionalWriteAlsoNeedsGetObjectWithoutTheTags() {
        Fixture f = new Fixture("cas");
        String write = allow("\"s3:PutObject\",\"s3:PutObjectTagging\"", f.objects(), REQUEST_A);

        f.policy(write + "," + allow("s3:GetObject", f.objects(), EXISTING_A));
        String etag = f.etag("a/tagged-a");
        assertEquals(200, f.put("a/tagged-a", "tofu-estate=a", null), "an overwrite with no precondition");
        etag = f.etag("a/tagged-a");
        assertEquals(403, f.put("a/tagged-a", "tofu-estate=a", etag), "If-Match under a tag-conditioned GetObject");

        f.policy(write + "," + allow("s3:GetObject", f.objects(), null));
        assertEquals(200, f.put("a/tagged-a", "tofu-estate=a", etag), "If-Match under a prefix-scoped GetObject");

        f.policy(write);
        etag = f.etag("a/tagged-a");
        assertEquals(403, f.put("a/tagged-a", "tofu-estate=a", etag), "If-Match with no GetObject at all");
        assertEquals(200, f.put("a/created", "tofu-estate=a", "*none*"), "If-None-Match needs no GetObject");
    }

    @Test
    void aDenyOnAForeignTagCatchesAMisScopedPrefixForReadsOnly() {
        Fixture f = new Fixture("deny");
        // The allow is deliberately too wide: both estates' prefixes.
        f.policy(allow("\"s3:GetObject\",\"s3:DeleteObject\"", f.objects(), null) + ","
                + allow("\"s3:PutObject\",\"s3:PutObjectTagging\"", f.objects(), REQUEST_A) + ","
                + "{\"Effect\":\"Deny\",\"Action\":[\"s3:GetObject\",\"s3:DeleteObject\",\"s3:PutObject\"],\"Resource\":\"" + f.objects() + "\","
                + "\"Condition\":{\"StringNotEquals\":{\"s3:ExistingObjectTag/tofu-estate\":\"a\"},\"Null\":{\"s3:ExistingObjectTag/tofu-estate\":\"false\"}}}");
        assertEquals(200, f.get("a/tagged-a"), "own object");
        assertEquals(403, f.get("b/theirs"), "the neighbour's object: the tag still denies the read");
        assertEquals(200, f.get("a/untagged"), "an untagged object is readable under this shape");
        assertEquals(200, f.put("a/tagged-a", "tofu-estate=a", f.etag("a/tagged-a")), "the CAS write works");
        assertEquals(200, f.put("b/theirs", "tofu-estate=a", null), "measured on AWS: overwriting the neighbour is NOT caught by its tag");
        assertEquals(204, f.delete("b/theirs-2"), "measured on AWS: deleting the neighbour is NOT caught by its tag");
    }

    private static String allow(String actions, String resource, String condition) {
        String action = actions.startsWith("\"") ? "[" + actions + "]" : "\"" + actions + "\"";
        return "{\"Effect\":\"Allow\",\"Action\":" + action + ",\"Resource\":\"" + resource + "\""
                + (condition == null ? "" : "," + condition) + "}";
    }

    /** One bucket, a few tagged objects seeded as the admin, and one IAM user with an access key. */
    private static final class Fixture {
        final String bucket;
        final String user;
        final String accessKeyId;

        Fixture(String name) {
            String suffix = Long.toString(System.nanoTime(), 36);
            bucket = "objtag-" + name + "-" + suffix;
            user = "objtag-" + name + "-" + suffix;
            given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket).then().statusCode(200);
            seed("a/tagged-a", "tofu-estate=a");
            seed("a/tagged-b", "tofu-estate=b");
            seed("a/untagged", null);
            seed("b/theirs", "tofu-estate=b");
            seed("b/theirs-2", "tofu-estate=b");
            given().formParam("Action", "CreateUser").formParam("UserName", user)
                    .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
            accessKeyId = given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                    .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200)
                    .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        }

        String objects() {
            return "arn:aws:s3:::" + bucket + "/*";
        }

        void seed(String key, String tagging) {
            var req = given().header("Authorization", auth(ADMIN, "s3")).body("seed");
            if (tagging != null) {
                req = req.header("x-amz-tagging", tagging);
            }
            req.when().put("/" + bucket + "/" + key).then().statusCode(200);
        }

        void policy(String statements) {
            given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                    .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}")
                    .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
        }

        int get(String key) {
            return given().header("Authorization", auth(accessKeyId, "s3")).when().get("/" + bucket + "/" + key).statusCode();
        }

        int delete(String key) {
            return given().header("Authorization", auth(accessKeyId, "s3")).when().delete("/" + bucket + "/" + key).statusCode();
        }

        String etag(String key) {
            return given().header("Authorization", auth(ADMIN, "s3")).when().head("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().header("ETag");
        }

        /** ifMatch: null for none, "*none*" for If-None-Match: *, anything else is the If-Match value. */
        int put(String key, String tagging, String ifMatch) {
            var req = given().header("Authorization", auth(accessKeyId, "s3")).body("written");
            if (tagging != null) {
                req = req.header("x-amz-tagging", tagging);
            }
            if ("*none*".equals(ifMatch)) {
                req = req.header("If-None-Match", "*");
            } else if (ifMatch != null) {
                req = req.header("If-Match", ifMatch);
            }
            return req.when().put("/" + bucket + "/" + key).statusCode();
        }
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260629/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
