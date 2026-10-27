package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * With {@code iam.enforcement-enabled=true}, S3 Control's routes are authorized as the {@code s3:}
 * actions the Service Authorization Reference gives them (S3 Control clients sign with the s3
 * scope), against the resources those routes name, not as the data-plane object operations their
 * {@code /v20180820} paths resemble. Issue #6537: a ListTagsForResource was checked as
 * s3:GetObject on a "bucket" named v20180820, so Terraform's AWS provider, which reads every
 * bucket's tags through S3 Control, saw 403 under enforcement and never fell back, leaving the
 * bucket unrefreshable.
 *
 * <p>The deny answer is also S3 Control's own REST-XML envelope, not S3's bare {@code <Error>}: the
 * s3control SDK deserializers read only the {@code <ErrorResponse>} wrapper (issue #557), and the
 * provider falls back when the message names {@code s3:ListTagsForResource}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3ControlIamEnforcementIntegrationTest {

    @TestHTTPResource
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String S3_CONTROL_ERROR_ENVELOPE =
            "<ErrorResponse xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">";

    @Test
    void listTagsForResourceIsAllowedByItsOwnActionOnTheBucket() {
        String bucket = bucketWithTags("control-allow");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource","Resource":"arn:aws:s3:::%1$s"}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(200)
                .body(containsString("<ListTagsForResourceResult"))
                .body(containsString("<Key>Env</Key>"))
                .body(containsString("<Value>dev</Value>"));
    }

    @Test
    void listTagsForResourceAcceptsTheGoSdkWireFormOfTheSameArn() {
        String bucket = bucketWithTags("control-encoded");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource","Resource":"arn:aws:s3:::%1$s"}]}"""
                .formatted(bucket));

        String encodedArn = "arn%3Aaws%3As3%3A%3A%3A" + bucket;
        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/" + encodedArn)
        .then()
                .statusCode(200)
                .body(containsString("<Key>Env</Key>"));
    }

    @Test
    void aWildcardS3PolicyOnTheBucketAllowsListTagsForResource() {
        String bucket = bucketWithTags("control-wildcard");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":["arn:aws:s3:::%1$s","arn:aws:s3:::%1$s/*"]}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(200);
    }

    /**
     * The leak issue #6537 reports in the other direction: with the request checked as
     * s3:GetObject, an object-read policy authorized reading a bucket's tags through the control
     * plane. The tagging action is its own, so nothing below s3:ListTagsForResource grants it.
     */
    @Test
    void anObjectReadPolicyDoesNotAuthorizeListTagsForResource() {
        String bucket = bucketWithTags("control-no-objects");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(403)
                .body(containsString(S3_CONTROL_ERROR_ENVELOPE))
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("is not authorized to perform: s3:ListTagsForResource"));
    }

    /**
     * A query parameter the control plane ignores must not re-point the authorization at the
     * data-plane action it names: S3ControlController reads the bucket's tags whatever
     * {@code ?tagging} says, so a caller holding only s3:GetObjectTagging on everything is
     * denied, and a caller holding only the control action is allowed.
     */
    @Test
    void aTaggingQueryOnTheControlRouteDoesNotRepointTheAuthorization() {
        String bucket = bucketWithTags("control-query");
        String objectTagger = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObjectTagging","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(objectTagger, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket + "?tagging")
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:ListTagsForResource"));

        String controlReader = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource","Resource":"arn:aws:s3:::%1$s"}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(controlReader, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket + "?tagging")
        .then()
                .statusCode(200)
                .body(containsString("<Key>Env</Key>"));
    }

    /**
     * The regional ARN form, the one the tagging operation's own request pattern and the AWS
     * CLI examples use, is the request's resource as written: a policy naming it authorizes the
     * read, because the ARN is not rewritten into the global form a data-plane path mints.
     */
    @Test
    void listTagsForResourceIsAuthorizedByAPolicyOnTheRegionalArnForm() {
        String bucket = bucketWithTags("control-regional");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource",
                   "Resource":"arn:aws:s3:us-east-1:000000000000:bucket/%1$s"}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:us-east-1:000000000000:bucket/" + bucket)
        .then()
                .statusCode(200)
                .body(containsString("<ListTagsForResourceResult"))
                .body(containsString("<Key>Env</Key>"));
    }

    /**
     * A bucket policy is resolved from the bucket the tagged ARN names, not from the bucket the
     * data-plane reading of the ARN's resource part would find: the regional form's resource is
     * {@code bucket/<name>}, and the real bucket's policy denying the control action must deny a
     * caller whose identity policy allows it.
     */
    @Test
    void aBucketPolicyOnTheTaggedBucketDeniesTheControlRead() {
        String bucket = bucketWithTags("control-bucket-policy");
        given()
                .header("Authorization", auth("test", "s3"))
                .contentType("application/json")
                .body("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Deny","Principal":"*","Action":"s3:ListTagsForResource",
                           "Resource":"arn:aws:s3:us-east-1:000000000000:bucket/%1$s"}]}"""
                        .formatted(bucket))
        .when()
                .put("/" + bucket + "?policy")
        .then()
                .statusCode(200);

        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:us-east-1:000000000000:bucket/" + bucket)
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:ListTagsForResource"));
    }

    /** The data-plane tagging read is a different action from the control-plane one, as on AWS. */
    @Test
    void getBucketTaggingDoesNotAuthorizeListTagsForResource() {
        String bucket = bucketWithTags("control-dataplane");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetBucketTagging","Resource":"arn:aws:s3:::%1$s"}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:ListTagsForResource"));
    }

    @Test
    void tagResourceAndUntagResourceAreCheckedAsTheirOwnActions() {
        String bucket = bucketWithTags("control-write");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:TagResource","s3:UntagResource","s3:ListTagsForResource"],
                   "Resource":"arn:aws:s3:::%1$s"}]}"""
                .formatted(bucket));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .contentType("application/xml")
                .body("<Tagging><Tags><Tag><Key>Owner</Key><Value>team</Value></Tag></Tags></Tagging>")
        .when()
                .post("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(204);

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(200)
                .body(containsString("<Key>Owner</Key>"));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .queryParam("tagKeys", "Owner")
        .when()
                .delete("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(204);
    }

    @Test
    void anObjectWritePolicyDoesNotAuthorizeTagResource() {
        String bucket = bucketWithTags("control-no-write");
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .contentType("application/xml")
                .body("<Tagging><Tags><Tag><Key>Owner</Key><Value>team</Value></Tag></Tags></Tagging>")
        .when()
                .post("/v20180820/tags/arn:aws:s3:::" + bucket)
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:TagResource"));
    }

    /**
     * The account-level Block Public Access is a security control, so the account's own actions
     * gate it: the S3 User Guide requires s3:PutAccountPublicAccessBlock and
     * s3:GetAccountPublicAccessBlock, and a delete the same action as the write it undoes. The
     * bucket-level s3:PutBucketPublicAccessBlock must not reach it.
     */
    @Test
    void accountPublicAccessBlockIsAuthorizedAsTheAccountActions() {
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:PutAccountPublicAccessBlock","s3:GetAccountPublicAccessBlock"],
                   "Resource":"*"}]}""");
        try {
            given()
                    .header("Authorization", auth(user, "s3"))
                    .header("x-amz-account-id", ACCOUNT)
                    .body("<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>"
                            + "</PublicAccessBlockConfiguration>")
            .when()
                    .put("/v20180820/configuration/publicAccessBlock")
            .then()
                    .statusCode(200);

            given()
                    .header("Authorization", auth(user, "s3"))
                    .header("x-amz-account-id", ACCOUNT)
            .when()
                    .get("/v20180820/configuration/publicAccessBlock")
            .then()
                    .statusCode(200)
                    .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"));
        } finally {
            given()
                    .header("Authorization", auth("test", "s3"))
                    .header("x-amz-account-id", ACCOUNT)
            .when()
                    .delete("/v20180820/configuration/publicAccessBlock")
            .then()
                    .statusCode(204);
        }
    }

    @Test
    void aBucketLevelPublicAccessBlockPolicyDoesNotAuthorizeTheAccountControl() {
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutBucketPublicAccessBlock","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .body("<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>"
                        + "</PublicAccessBlockConfiguration>")
        .when()
                .put("/v20180820/configuration/publicAccessBlock")
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:PutAccountPublicAccessBlock"));
    }

    @Test
    void listAccessPointsIsAuthorizedAsItsOwnAction() {
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListAccessPoints","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/accesspoint")
        .then()
                .statusCode(200)
                .body(containsString("<AccessPointList"));
    }

    @Test
    void aListBucketPolicyDoesNotAuthorizeListAccessPoints() {
        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListBucket","s3:ListAllMyBuckets"],"Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/accesspoint")
        .then()
                .statusCode(403)
                .body(containsString("is not authorized to perform: s3:ListAccessPoints"));
    }

    /**
     * The regional form names the account whose bucket it acts on, so a grant on
     * {@code arn:aws:s3:us-east-1:222222222222:bucket/<name>} authorizes a caller in another
     * account, whom the bucket's own policy admits, to read and change the tags of
     * 222222222222's bucket, and never of its own same-named one, which the plain bucket name
     * would otherwise resolve to. The bucket's policy is found in the account the ARN names,
     * the same bucket the read and the write act on.
     */
    @Test
    void aRegionalArnActsOnTheBucketOfTheAccountItNames() {
        String bucket = "control-cross-account-" + UUID.randomUUID().toString().substring(0, 8);
        String regionalArn = "arn:aws:s3:us-east-1:" + OTHER_ACCOUNT + ":bucket/" + bucket;
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                auth(OTHER_ACCOUNT, "s3"), bucket));
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                auth("test", "s3"), bucket));
        createBucketWithTag(bucket, "Team", "other", auth(OTHER_ACCOUNT, "s3"));
        createBucketWithTag(bucket, "Team", "own", auth("test", "s3"));

        String userName = "s3-control-cross-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                baseUri.getPort(), auth("test", "iam"), userName);
        String userArn = "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
        String identityPolicy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:ListTagsForResource","s3:TagResource"],
                   "Resource":"%1$s"}]}"""
                .formatted(regionalArn);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth("test", "iam"), userName, "p",
                identityPolicy);
        String user = credentials.accessKeyId();

        given()
                .header("Authorization", auth(OTHER_ACCOUNT, "s3"))
                .contentType("application/json")
                .body("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Principal":{"AWS":"%1$s"},
                           "Action":["s3:ListTagsForResource","s3:TagResource"],
                           "Resource":"%2$s"}]}"""
                        .formatted(userArn, regionalArn))
        .when()
                .put("/" + bucket + "?policy")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/" + regionalArn)
        .then()
                .statusCode(200)
                .body(containsString("<Key>Team</Key>"))
                .body(containsString("<Value>other</Value>"));

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .contentType("application/xml")
                .body("<Tagging><Tags><Tag><Key>Owner</Key><Value>team</Value></Tag></Tags></Tagging>")
        .when()
                .post("/v20180820/tags/" + regionalArn)
        .then()
                .statusCode(204);

        given()
                .header("Authorization", auth(OTHER_ACCOUNT, "s3"))
        .when()
                .get("/" + bucket + "?tagging")
        .then()
                .statusCode(200)
                .body(containsString("<Key>Owner</Key>"))
                .body(not(containsString("<Value>own</Value>")));

        given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .get("/" + bucket + "?tagging")
        .then()
                .statusCode(200)
                .body(containsString("<Value>own</Value>"))
                .body(not(containsString("<Key>Owner</Key>")));
    }

    /**
     * A tagging ARN naming a bucket its account does not own answers {@code NoSuchBucket}, not
     * the tags of the caller's same-named bucket: the handler acts on the bucket the ARN names
     * or on nothing.
     */
    @Test
    void aRegionalArnNamingABucketItsAccountDoesNotOwnAnswersNoSuchBucket() {
        String bucket = "control-absent-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                auth("test", "s3"), bucket));
        createBucketWithTag(bucket, "Team", "own", auth("test", "s3"));

        String user = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListTagsForResource","Resource":"*"}]}""");

        given()
                .header("Authorization", auth(user, "s3"))
                .header("x-amz-account-id", ACCOUNT)
        .when()
                .get("/v20180820/tags/arn:aws:s3:us-east-1:" + OTHER_ACCOUNT + ":bucket/" + bucket)
        .then()
                .statusCode(404)
                .body(containsString(S3_CONTROL_ERROR_ENVELOPE))
                .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    /** A bucket created by the root stand-in, tagged through the data plane, as Terraform reads it. */
    private String bucketWithTags(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                auth("test", "s3"), bucket));
        createBucketWithTag(bucket, "Env", "dev", auth("test", "s3"));
        return bucket;
    }

    /** A bucket carrying one tag, created and tagged through the data plane under {@code authorization}. */
    private static void createBucketWithTag(String bucket, String key, String value, String authorization) {
        given()
                .header("Authorization", authorization)
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>" + key + "</Key><Value>" + value
                        + "</Value></Tag></TagSet></Tagging>")
        .when()
                .put("/" + bucket + "?tagging")
        .then()
                .statusCode(204);
    }

    /** The access key of a new IAM user holding {@code policy} inline. */
    private String userWith(String policy) {
        String userName = "s3-control-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                baseUri.getPort(), auth("test", "iam"), userName);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth("test", "iam"), userName, "p", policy);
        return credentials.accessKeyId();
    }
}
