package io.floci.aws.services.s3;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DeleteObjects is authorized per object, as S3 does it: there is no {@code s3:DeleteObjects}
 * action, so each entry needs {@code s3:DeleteObject} (or {@code s3:DeleteObjectVersion} for an
 * entry naming a version) on its own key, and a denied entry comes back as an {@code AccessDenied}
 * {@code <Error>} in a 200 response while the allowed entries are deleted. The DeleteObjects API
 * reference's mixed success/error example is the shape asserted here.
 *
 * <p>Only IAM enforcement is on, so S3's own bucket-policy evaluation does not run and every
 * decision below comes from the IAM layer, bucket policies included.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3DeleteObjectsIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";

    @Inject
    IamService iamService;

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anIdentityPolicyOnOnePrefixDeletesOnlyThatPrefix() {
        String bucket = bucket("delobj-prefix");
        put(bucket, "allowed/a.txt");
        put(bucket, "protected/b.txt");
        String user = uniqueName("delobj-prefix");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/allowed/*"));

        XmlPath result = deleteObjects(accessKeyId, "s3", bucket, false, entry("allowed/a.txt"), entry("protected/b.txt"));

        assertEquals(List.of("allowed/a.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "protected/b.txt");
        assertEquals(List.of(), result.getList("DeleteResult.Error.VersionId"),
                "an entry naming no version reports none");
        assertGone(bucket, "allowed/a.txt");
        assertPresent(bucket, "protected/b.txt");
    }

    @Test
    void quietModeStillReportsTheDeniedKey() {
        String bucket = bucket("delobj-quiet");
        put(bucket, "allowed/a.txt");
        put(bucket, "protected/b.txt");
        String user = uniqueName("delobj-quiet");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/allowed/*"));

        XmlPath result = deleteObjects(accessKeyId, "s3", bucket, true, entry("allowed/a.txt"), entry("protected/b.txt"));

        assertEquals(List.of(), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "protected/b.txt");
        assertGone(bucket, "allowed/a.txt");
        assertPresent(bucket, "protected/b.txt");
    }

    @Test
    void anEntryNamingAVersionNeedsDeleteObjectVersion() {
        String bucket = bucket("delobj-version");
        enableVersioning(bucket);
        String olderVersionId = put(bucket, "versioned.txt");
        String versionId = put(bucket, "versioned.txt");
        String user = uniqueName("delobj-version");
        String accessKeyId = createUser(user);
        String objects = "arn:aws:s3:::" + bucket + "/*";

        userPolicy(user, allow("s3:DeleteObject", objects));
        XmlPath denied = deleteObjects(accessKeyId, "s3", bucket, false,
                versionEntry("versioned.txt", olderVersionId), versionEntry("versioned.txt", versionId));
        assertEquals(List.of(), denied.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("AccessDenied", "AccessDenied"), denied.getList("DeleteResult.Error.Code"));
        assertEquals(List.of(olderVersionId, versionId), denied.getList("DeleteResult.Error.VersionId"),
                "each error names the version it refused");
        assertVersionPresent(bucket, "versioned.txt", olderVersionId);
        assertVersionPresent(bucket, "versioned.txt", versionId);

        userPolicy(user, allow("s3:DeleteObjectVersion", objects));
        XmlPath allowed = deleteObjects(accessKeyId, "s3", bucket, false, versionEntry("versioned.txt", versionId));
        assertEquals(List.of("versioned.txt"), allowed.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of(versionId), allowed.getList("DeleteResult.Deleted.VersionId"));
        assertEquals(List.of(), allowed.getList("DeleteResult.Error.Key"));
    }

    @Test
    void anETagConditionedEntryAlsoNeedsGetObject() {
        String bucket = bucket("delobj-etag");
        for (String key : List.of("etag.txt", "stale.txt", "star.txt", "quoted-star.txt", "padded-star.txt")) {
            put(bucket, key);
        }
        String eTag = given().header("Authorization", auth(ADMIN, "s3")).when().head("/" + bucket + "/etag.txt")
                .then().statusCode(200).extract().header("ETag");
        String staleETag = "\"00000000000000000000000000000000\"";
        String user = uniqueName("delobj-etag");
        String accessKeyId = createUser(user);
        String objects = "arn:aws:s3:::" + bucket + "/*";

        userPolicy(user, allow("s3:DeleteObject", objects));
        XmlPath deleteOnly = deleteObjects(accessKeyId, "s3", bucket, false,
                eTagEntry("etag.txt", eTag), eTagEntry("stale.txt", staleETag),
                eTagEntry("star.txt", "*"), eTagEntry("quoted-star.txt", "\"*\""),
                eTagEntry("padded-star.txt", " * "));
        assertEquals(List.of("star.txt", "quoted-star.txt", "padded-star.txt"),
                deleteOnly.getList("DeleteResult.Deleted.Key"),
                "an existence check needs only the delete");
        assertEquals(List.of("etag.txt", "stale.txt"), deleteOnly.getList("DeleteResult.Error.Key"),
                "an ETag condition with no GetObject is denied, before its ETag is compared");
        assertEquals(List.of("AccessDenied", "AccessDenied"), deleteOnly.getList("DeleteResult.Error.Code"));
        assertEquals(List.of("Access Denied", "Access Denied"), deleteOnly.getList("DeleteResult.Error.Message"));
        assertPresent(bucket, "etag.txt");
        assertPresent(bucket, "stale.txt");

        userPolicy(user, allow(List.of("s3:DeleteObject", "s3:GetObject"), objects));
        XmlPath withRead = deleteObjects(accessKeyId, "s3", bucket, false,
                eTagEntry("etag.txt", eTag), eTagEntry("stale.txt", staleETag));
        assertEquals(List.of("etag.txt"), withRead.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("stale.txt"), withRead.getList("DeleteResult.Error.Key"));
        assertEquals(List.of("PreconditionFailed"), withRead.getList("DeleteResult.Error.Code"),
                "with GetObject the condition itself is evaluated");
        assertPresent(bucket, "stale.txt");
    }

    @Test
    void aBucketPolicyGrantOnAPrefixDeletesOnlyThatPrefix() {
        String bucket = bucket("delobj-bp-grant");
        put(bucket, "shared/a.txt");
        put(bucket, "private/b.txt");
        String user = uniqueName("delobj-bp-grant");
        String accessKeyId = createUser(user);
        bucketPolicy(bucket, """
                {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:user/%s"},
                 "Action":"s3:DeleteObject","Resource":"arn:aws:s3:::%s/shared/*"}""".formatted(ACCOUNT, user, bucket));

        XmlPath result = deleteObjects(accessKeyId, "s3", bucket, false, entry("shared/a.txt"), entry("private/b.txt"));

        assertEquals(List.of("shared/a.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "private/b.txt");
        assertGone(bucket, "shared/a.txt");
        assertPresent(bucket, "private/b.txt");
    }

    @Test
    void aBucketPolicyDenyOverridesAnIdentityAllow() {
        assertBucketPolicyDenyApplies("s3", "delobj-bp-deny");
    }

    @Test
    void aBucketPolicyDenyAppliesToAnS3ExpressScopedCredential() {
        assertBucketPolicyDenyApplies("s3express", "delobj-express");
    }

    // A lambda-scoped request to this route is answered 404 by AwsRestRouteScopeFilter before the
    // handler runs, so ssm, whose scope does reach it, stands in for a credential scoped to another service.
    @Test
    void aBucketPolicyDenyAppliesToACredentialScopedToAnotherService() {
        assertBucketPolicyDenyApplies("ssm", "delobj-other-scope");
    }

    // filter() returns before its key checks on DeleteObjects, a route no IAM action maps, so the per-key
    // check is where an unusable key is refused. An s3express alias is answered in S3's vocabulary, not
    // with the codes other services use.
    @Test
    void aDeactivatedKeySignedForS3ExpressIsRefusedAsS3RefusesIt() {
        String bucket = bucket("delobj-inactive");
        put(bucket, "a.txt");
        String user = uniqueName("delobj-inactive");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/*"));
        given().formParam("Action", "UpdateAccessKey").formParam("UserName", user)
                .formParam("AccessKeyId", accessKeyId).formParam("Status", "Inactive")
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);

        XmlPath result = deleteObjects(accessKeyId, "s3express", bucket, false, entry("a.txt"));

        assertEquals(List.of(), result.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("a.txt"), result.getList("DeleteResult.Error.Key"));
        assertEquals(List.of("InvalidAccessKeyId"), result.getList("DeleteResult.Error.Code"));
        assertPresent(bucket, "a.txt");
    }

    @Test
    void anExpiredSessionSignedForS3ExpressIsRefusedAsS3RefusesIt() {
        String bucket = bucket("delobj-expired");
        put(bucket, "a.txt");
        String accessKeyId = "ASIADELOBJ" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase(Locale.ROOT);
        iamService.registerSessionForAccount(ACCOUNT, accessKeyId, "secret",
                "arn:aws:iam::" + ACCOUNT + ":role/delobj-expired", Instant.parse("2020-01-01T00:00:00Z"), null);
        try {
            XmlPath result = deleteObjects(accessKeyId, "s3express", bucket, false, entry("a.txt"));

            assertEquals(List.of(), result.getList("DeleteResult.Deleted.Key"));
            assertEquals(List.of("a.txt"), result.getList("DeleteResult.Error.Key"));
            assertEquals(List.of("ExpiredToken"), result.getList("DeleteResult.Error.Code"));
            assertPresent(bucket, "a.txt");
        } finally {
            iamService.unregisterSession(ACCOUNT, accessKeyId);
        }
    }

    @Test
    void bypassingGovernanceRetentionNeedsTheBypassPermission() {
        String bucket = bucket("delobj-governance");
        enableObjectLock(bucket);
        String versionId = putGovernanceRetained(bucket, "retained.txt");
        String plainVersionId = put(bucket, "plain.txt");
        String user = uniqueName("delobj-governance");
        String accessKeyId = createUser(user);
        String objects = "arn:aws:s3:::" + bucket + "/*";

        // The header asks for the bypass on both entries, but only the retained one needs the permission.
        userPolicy(user, allow("s3:DeleteObjectVersion", objects) + "," + deny("s3:BypassGovernanceRetention", objects));
        XmlPath denied = deleteObjects(accessKeyId, "s3", bucket, false, true,
                versionEntry("retained.txt", versionId), versionEntry("plain.txt", plainVersionId));
        assertEquals(List.of("plain.txt"), denied.getList("DeleteResult.Deleted.Key"));
        assertDenied(denied, "retained.txt");
        assertVersionPresent(bucket, "retained.txt", versionId);

        userPolicy(user, allow(List.of("s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"), objects));
        // Without the header the retention itself refuses the delete, and that error names the version too.
        XmlPath retained = deleteObjects(accessKeyId, "s3", bucket, false, false, versionEntry("retained.txt", versionId));
        assertEquals(List.of("retained.txt"), retained.getList("DeleteResult.Error.Key"));
        assertEquals(List.of(versionId), retained.getList("DeleteResult.Error.VersionId"));
        assertVersionPresent(bucket, "retained.txt", versionId);

        XmlPath allowed = deleteObjects(accessKeyId, "s3", bucket, false, true, versionEntry("retained.txt", versionId));
        assertEquals(List.of("retained.txt"), allowed.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of(versionId), allowed.getList("DeleteResult.Deleted.VersionId"));
        assertEquals(List.of(), allowed.getList("DeleteResult.Error.Key"));
    }

    // An object written before versioning was enabled is the null version, which an entry names as
    // VersionId "null". Retention set on it once the bucket has Object Lock protects it as any version's
    // does, so the bypass needs the permission.
    @Test
    void bypassingGovernanceRetentionOnTheNullVersionNeedsTheBypassPermission() {
        String bucket = bucket("delobj-governance-null");
        put(bucket, "retained.txt");
        enableObjectLock(bucket);
        given().header("Authorization", auth(ADMIN, "s3")).queryParam("versionId", "null")
                .body("<Retention><Mode>GOVERNANCE</Mode>"
                        + "<RetainUntilDate>2099-01-01T00:00:00Z</RetainUntilDate></Retention>")
                .when().put("/" + bucket + "/retained.txt?retention").then().statusCode(200);
        String user = uniqueName("delobj-governance-null");
        String accessKeyId = createUser(user);
        String objects = "arn:aws:s3:::" + bucket + "/*";

        userPolicy(user, allow("s3:DeleteObjectVersion", objects) + "," + deny("s3:BypassGovernanceRetention", objects));
        XmlPath denied = deleteObjects(accessKeyId, "s3", bucket, false, true, versionEntry("retained.txt", "null"));
        assertEquals(List.of(), denied.getList("DeleteResult.Deleted.Key"));
        assertDenied(denied, "retained.txt");
        assertPresent(bucket, "retained.txt");

        userPolicy(user, allow(List.of("s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"), objects));
        XmlPath allowed = deleteObjects(accessKeyId, "s3", bucket, false, true, versionEntry("retained.txt", "null"));
        assertEquals(List.of("retained.txt"), allowed.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("null"), allowed.getList("DeleteResult.Deleted.VersionId"));
        assertEquals(List.of(), allowed.getList("DeleteResult.Error.Key"));
        assertGone(bucket, "retained.txt");
    }

    // With no pre-versioning object there is no null version, so the retained current version at the
    // plain key does not make a "null" entry need the bypass permission.
    @Test
    void aNullVersionEntryDoesNotInheritTheRetentionOfAVersionedObject() {
        String bucket = bucket("delobj-governance-no-null");
        enableObjectLock(bucket);
        String versionId = putGovernanceRetained(bucket, "retained.txt");
        String user = uniqueName("delobj-governance-no-null");
        String accessKeyId = createUser(user);
        String objects = "arn:aws:s3:::" + bucket + "/*";

        userPolicy(user, allow("s3:DeleteObjectVersion", objects) + "," + deny("s3:BypassGovernanceRetention", objects));
        XmlPath result = deleteObjects(accessKeyId, "s3", bucket, false, true, versionEntry("retained.txt", "null"));
        // S3 answers a delete of a version that does not exist as deleted.
        assertEquals(List.of("retained.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("null"), result.getList("DeleteResult.Deleted.VersionId"));
        assertEquals(List.of(), result.getList("DeleteResult.Error.Key"));
        assertVersionPresent(bucket, "retained.txt", versionId);
    }

    @Test
    void aPresignedRequestIsAuthorizedPerKey() {
        String bucket = bucket("delobj-presigned");
        put(bucket, "allowed/a.txt");
        put(bucket, "protected/b.txt");
        String user = uniqueName("delobj-presigned");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/allowed/*"));

        XmlPath result = presignedDeleteObjects(given(), bucket, accessKeyId,
                entry("allowed/a.txt"), entry("protected/b.txt"));

        assertEquals(List.of("allowed/a.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "protected/b.txt");
        assertGone(bucket, "allowed/a.txt");
        assertPresent(bucket, "protected/b.txt");
    }

    // The filter treats an empty Authorization header as absent and evaluates the query credential,
    // so the per-key checks must read the same credential rather than the empty header.
    @Test
    void aPresignedRequestWithAnEmptyAuthorizationHeaderIsStillAuthorizedPerKey() {
        String bucket = bucket("delobj-presigned-empty");
        put(bucket, "allowed/a.txt");
        put(bucket, "protected/b.txt");
        String user = uniqueName("delobj-presigned-empty");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/allowed/*"));

        XmlPath result = presignedDeleteObjects(given().header("Authorization", ""), bucket, accessKeyId,
                entry("allowed/a.txt"), entry("protected/b.txt"));

        assertEquals(List.of("allowed/a.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "protected/b.txt");
        assertGone(bucket, "allowed/a.txt");
        assertPresent(bucket, "protected/b.txt");
    }

    private void assertBucketPolicyDenyApplies(String signingScope, String prefix) {
        String bucket = bucket(prefix);
        put(bucket, "open/a.txt");
        put(bucket, "protected/b.txt");
        String user = uniqueName(prefix);
        String accessKeyId = createUser(user);
        userPolicy(user, allow("s3:DeleteObject", "arn:aws:s3:::" + bucket + "/*"));
        bucketPolicy(bucket, """
                {"Effect":"Deny","Principal":"*","Action":"s3:DeleteObject",
                 "Resource":"arn:aws:s3:::%s/protected/*"}""".formatted(bucket));

        XmlPath result = deleteObjects(accessKeyId, signingScope, bucket, false,
                entry("open/a.txt"), entry("protected/b.txt"));

        assertEquals(List.of("open/a.txt"), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "protected/b.txt");
        assertGone(bucket, "open/a.txt");
        assertPresent(bucket, "protected/b.txt");
    }

    private static void assertDenied(XmlPath result, String key) {
        assertEquals(List.of(key), result.getList("DeleteResult.Error.Key"));
        assertEquals(List.of("AccessDenied"), result.getList("DeleteResult.Error.Code"));
        assertEquals(List.of("Access Denied"), result.getList("DeleteResult.Error.Message"));
    }

    private static XmlPath deleteObjects(String accessKeyId, String signingScope, String bucket, boolean quiet,
                                         String... entries) {
        return deleteObjects(accessKeyId, signingScope, bucket, quiet, false, entries);
    }

    private static XmlPath deleteObjects(String accessKeyId, String signingScope, String bucket, boolean quiet,
                                         boolean bypassGovernanceRetention, String... entries) {
        RequestSpecification request = given().header("Authorization", auth(accessKeyId, signingScope));
        if (bypassGovernanceRetention) {
            request.header("x-amz-bypass-governance-retention", "true");
        }
        String response = request
                .contentType("application/xml")
                .body(deleteBody(quiet, entries))
        .when()
                .post("/" + bucket + "?delete")
        .then()
                .statusCode(200)
                .extract().asString();
        return XmlPath.from(response);
    }

    private static XmlPath presignedDeleteObjects(RequestSpecification request, String bucket, String accessKeyId,
                                                  String... entries) {
        String response = request
                .urlEncodingEnabled(false)
                .contentType("application/xml")
                .body(deleteBody(false, entries))
        .when()
                .post(presignedDeletePath(bucket, accessKeyId))
        .then()
                .statusCode(200)
                .extract().asString();
        return XmlPath.from(response);
    }

    private static String deleteBody(boolean quiet, String... entries) {
        return "<Delete>" + (quiet ? "<Quiet>true</Quiet>" : "") + String.join("", entries) + "</Delete>";
    }

    private static String entry(String key) {
        return "<Object><Key>" + key + "</Key></Object>";
    }

    private static String versionEntry(String key, String versionId) {
        return "<Object><Key>" + key + "</Key><VersionId>" + versionId + "</VersionId></Object>";
    }

    private static String eTagEntry(String key, String eTag) {
        return "<Object><Key>" + key + "</Key><ETag>" + eTag + "</ETag></Object>";
    }

    private static String presignedDeletePath(String bucket, String accessKeyId) {
        return "/" + bucket + "?delete&" + EnforcementFixtures.presignedQuery(accessKeyId, "s3");
    }

    private static String bucket(String prefix) {
        String bucket = uniqueName(prefix);
        given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String put(String bucket, String key) {
        return given().header("Authorization", auth(ADMIN, "s3")).body("seed")
                .when().put("/" + bucket + "/" + key).then().statusCode(200)
                .extract().header("x-amz-version-id");
    }

    private static String putGovernanceRetained(String bucket, String key) {
        return given().header("Authorization", auth(ADMIN, "s3"))
                .header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2099-01-01T00:00:00Z")
                .body("seed")
                .when().put("/" + bucket + "/" + key).then().statusCode(200)
                .extract().header("x-amz-version-id");
    }

    private static void enableVersioning(String bucket) {
        given().header("Authorization", auth(ADMIN, "s3")).contentType("application/xml")
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
    }

    /** Object Lock on an existing bucket, which needs versioning: "Object Lock works only with versioned buckets". */
    private static void enableObjectLock(String bucket) {
        enableVersioning(bucket);
        given().header("Authorization", auth(ADMIN, "s3")).contentType("application/xml")
                .body("<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>")
                .when().put("/" + bucket + "?object-lock").then().statusCode(200);
    }

    private static void assertPresent(String bucket, String key) {
        given().header("Authorization", auth(ADMIN, "s3")).when().head("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private static void assertGone(String bucket, String key) {
        given().header("Authorization", auth(ADMIN, "s3")).when().head("/" + bucket + "/" + key)
                .then().statusCode(404);
    }

    private static void assertVersionPresent(String bucket, String key, String versionId) {
        given().header("Authorization", auth(ADMIN, "s3")).when()
                .head("/" + bucket + "/" + key + "?versionId=" + versionId).then().statusCode(200);
    }

    private String createUser(String user) {
        return EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), user).accessKeyId();
    }

    private static String allow(String action, String resource) {
        return allow(List.of(action), resource);
    }

    private static String allow(List<String> actions, String resource) {
        String actionList = String.join("\",\"", actions);
        return "{\"Effect\":\"Allow\",\"Action\":[\"" + actionList + "\"],\"Resource\":\"" + resource + "\"}";
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void userPolicy(String user, String statement) {
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
    }

    private static void bucketPolicy(String bucket, String statement) {
        given().header("Authorization", auth(ADMIN, "s3")).contentType("application/json")
                .body("{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .when().put("/" + bucket + "?policy").then().statusCode(200);
    }
}
