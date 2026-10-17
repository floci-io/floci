package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Each S3 operation is checked as the action AWS documents for it: the multipart upload operations
 * and the other POST operations, which no rule mapped, the multipart operations that were checked as
 * a plain read or delete, the object-lock operations, the version actions a versionId selects, and
 * the bucket configurations. A request naming several sub-resources is checked as the one S3 runs,
 * and the version actions carry the version id and the version's tags.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3ObjectOperationsIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String EXISTING_A = "{\"StringEquals\":{\"s3:ExistingObjectTag/env\":\"a\"}}";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void creatingAMultipartUploadNeedsPutObject() {
        Fixture f = new Fixture("create", false);
        f.policy(f.allow("s3:GetObject"));
        denied(f.user().queryParam("uploads", "").when().post(f.path("a/big")).then(), "s3:PutObject");

        f.policy(f.allow("s3:PutObject"));
        f.user().queryParam("uploads", "").when().post(f.path("a/big")).then().statusCode(200);
    }

    @Test
    void completingAMultipartUploadNeedsPutObject() {
        Fixture f = new Fixture("complete", false);
        Upload upload = f.uploadOnePart("a/big");

        f.policy(f.allow("s3:GetObject"));
        denied(f.complete(upload), "s3:PutObject");

        f.policy(f.allow("s3:PutObject"));
        f.complete(upload).statusCode(200);
    }

    @Test
    void onlyTheWritesThatTakeIfMatchNeedGetObjectForIt() {
        // The user guide lists PutObject, CopyObject and CompleteMultipartUpload as the operations
        // that take If-Match. Creating an upload and uploading a part ignore it, so it adds no
        // s3:GetObject check to them, while completing the upload with it does.
        Fixture f = new Fixture("if-match", false);
        f.seed("a/big");
        String etag = f.etag("a/big");
        f.policy(f.allow("s3:PutObject"));

        String uploadId = f.user().header("If-Match", etag).queryParam("uploads", "")
                .when().post(f.path("a/big")).then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        cleanup.register(() -> f.admin().queryParam("uploadId", uploadId).when().delete(f.path("a/big")));
        String partEtag = f.user().header("If-Match", etag)
                .queryParam("uploadId", uploadId).queryParam("partNumber", 1).body("part one")
                .when().put(f.path("a/big")).then().statusCode(200).extract().header("ETag");
        // A PUT naming an upload but no part number is not UploadPart: S3Controller runs it as a
        // PutObject, which compares If-Match.
        denied(f.user().header("If-Match", etag).queryParam("uploadId", uploadId).body("replaced")
                .when().put(f.path("a/big")).then(), "s3:GetObject");
        Upload upload = new Upload("a/big", uploadId, partEtag);
        // A POST naming both ?uploads and an uploadId creates an upload, which S3Controller runs first.
        String another = f.user().header("If-Match", etag).queryParam("uploads", "").queryParam("uploadId", uploadId)
                .when().post(f.path("a/big")).then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        cleanup.register(() -> f.admin().queryParam("uploadId", another).when().delete(f.path("a/big")));
        denied(f.complete(upload, etag), "s3:GetObject");

        f.policy(f.allow("s3:PutObject", "s3:GetObject"));
        f.complete(upload, etag).statusCode(200);
    }

    @Test
    void abortingAMultipartUploadNeedsAbortMultipartUpload() {
        Fixture f = new Fixture("abort", false);
        Upload upload = f.uploadOnePart("a/big");

        f.policy(f.allow("s3:DeleteObject"));
        denied(f.user().queryParam("uploadId", upload.id()).when().delete(f.path(upload.key())).then(),
                "s3:AbortMultipartUpload");

        f.policy(f.allow("s3:AbortMultipartUpload"));
        f.user().queryParam("uploadId", upload.id()).when().delete(f.path(upload.key())).then().statusCode(204);
    }

    @Test
    void listingThePartsOfAnUploadNeedsListMultipartUploadParts() {
        Fixture f = new Fixture("parts", false);
        Upload upload = f.uploadOnePart("a/big");

        f.policy(f.allow("s3:GetObject"));
        denied(f.user().queryParam("uploadId", upload.id()).when().get(f.path(upload.key())).then(),
                "s3:ListMultipartUploadParts");

        f.policy(f.allow("s3:ListMultipartUploadParts"));
        f.user().queryParam("uploadId", upload.id()).when().get(f.path(upload.key())).then().statusCode(200);
    }

    @Test
    void restoringAnObjectNeedsRestoreObject() {
        Fixture f = new Fixture("restore", false);
        f.seed("a/archived");

        f.policy(f.allow("s3:GetObject", "s3:PutObject"));
        denied(f.restore("a/archived"), "s3:RestoreObject");

        f.policy(f.allow("s3:RestoreObject"));
        f.restore("a/archived").statusCode(202);
    }

    @Test
    void selectingFromAnObjectNeedsGetObject() {
        Fixture f = new Fixture("select", false);
        f.seed("a/rows.csv");

        f.policy(f.allow("s3:PutObject"));
        denied(f.select("a/rows.csv"), "s3:GetObject");

        f.policy(f.allow("s3:GetObject"));
        f.select("a/rows.csv").statusCode(200);
    }

    @Test
    void selectingFromAVersionNeedsGetObjectVersion() {
        // S3Controller selects from the version named, so it is checked as reading that version.
        Fixture f = new Fixture("select-version", true);
        String versionId = f.seedVersion("a/rows.csv");

        f.policy(f.allow("s3:GetObject"));
        denied(f.select("a/rows.csv", versionId), "s3:GetObjectVersion");

        f.policy(f.allow("s3:GetObjectVersion"));
        f.select("a/rows.csv", versionId).statusCode(200);
    }

    @Test
    void retentionAndLegalHoldNeedTheirOwnActions() {
        Fixture f = new Fixture("lock", true);
        f.seed("a/locked");

        f.policy(f.allow("s3:GetObject", "s3:PutObject"));
        denied(f.putRetention("a/locked", "2031-01-01T00:00:00Z"), "s3:PutObjectRetention");
        denied(f.user().queryParam("retention", "").when().get(f.path("a/locked")).then(),
                "s3:GetObjectRetention");
        denied(f.user().queryParam("legal-hold", "").when().get(f.path("a/locked")).then(),
                "s3:GetObjectLegalHold");
        denied(f.user().queryParam("legal-hold", "")
                .body("<LegalHold><Status>OFF</Status></LegalHold>")
                .when().put(f.path("a/locked")).then(), "s3:PutObjectLegalHold");

        f.policy(f.allow("s3:PutObjectRetention", "s3:GetObjectRetention"));
        f.putRetention("a/locked", "2031-01-01T00:00:00Z").statusCode(200);
        f.user().queryParam("retention", "").when().get(f.path("a/locked")).then().statusCode(200);
    }

    @Test
    void aRequestNamingTwoSubresourcesIsCheckedAsTheOneThatRuns() {
        // S3 serves ?tagging before ?acl, so this request returns the object's tags.
        Fixture f = new Fixture("order", false);
        f.seed("a/tagged");

        f.policy(f.allow("s3:GetObjectAcl"));
        denied(f.user().queryParam("acl", "").queryParam("tagging", "").when().get(f.path("a/tagged")).then(),
                "s3:GetObjectTagging");

        f.policy(f.allow("s3:GetObjectTagging"));
        f.user().queryParam("acl", "").queryParam("tagging", "").when().get(f.path("a/tagged")).then()
                .statusCode(200)
                .body(containsString("<Tagging"));
    }

    @Test
    void readingAVersionNeedsGetObjectVersion() {
        // The API reference: with a versionId, s3:GetObjectVersion is needed and s3:GetObject is not.
        Fixture f = new Fixture("version-read", true);
        String versionId = f.seedVersion("a/doc");

        f.policy(f.allow("s3:GetObject"));
        denied(f.user().queryParam("versionId", versionId).when().get(f.path("a/doc")).then(),
                "s3:GetObjectVersion");
        f.user().when().get(f.path("a/doc")).then().statusCode(200);

        f.policy(f.allow("s3:GetObjectVersion"));
        f.user().queryParam("versionId", versionId).when().get(f.path("a/doc")).then().statusCode(200);
    }

    @Test
    void aDenyOnDeleteObjectVersionStopsAPermanentDelete() {
        Fixture f = new Fixture("version-delete", true);
        String versionId = f.seedVersion("a/kept");
        f.policy(f.allow("s3:*") + ",{\"Effect\":\"Deny\",\"Action\":\"s3:DeleteObjectVersion\","
                + "\"Resource\":\"arn:aws:s3:::" + f.bucket + "/*\"}");

        denied(f.user().queryParam("versionId", versionId).when().delete(f.path("a/kept")).then(),
                "s3:DeleteObjectVersion");
        // A plain delete only adds a delete marker, which s3:DeleteObject allows.
        f.user().when().delete(f.path("a/kept")).then().statusCode(204);
    }

    @Test
    void aConditionalDeleteOfAVersionAlsoNeedsGetObject() {
        // The conditional-deletes guide: an ETag condition needs s3:GetObject beside the delete, and
        // is evaluated against the current version only, so the read is of that one.
        Fixture f = new Fixture("version-cas", true);
        String versionId = f.seedVersion("a/doc");
        String etag = f.etag("a/doc");

        f.policy(f.allow("s3:DeleteObjectVersion"));
        denied(f.user().header("If-Match", etag).queryParam("versionId", versionId)
                .when().delete(f.path("a/doc")).then(), "s3:GetObject");
        f.user().header("If-Match", "*").queryParam("versionId", versionId)
                .when().delete(f.path("a/doc")).then().statusCode(204);

        String next = f.seedVersion("a/doc");
        f.policy(f.allow("s3:DeleteObjectVersion", "s3:GetObject"));
        f.user().header("If-Match", f.etag("a/doc")).queryParam("versionId", next)
                .when().delete(f.path("a/doc")).then().statusCode(204);
    }

    @Test
    void theTagsOfAVersionNeedTheVersionTaggingActions() {
        Fixture f = new Fixture("version-tags", true);
        String versionId = f.seedVersion("a/tagged");

        f.policy(f.allow("s3:GetObjectTagging", "s3:PutObjectTagging", "s3:DeleteObjectTagging"));
        denied(f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .when().get(f.path("a/tagged")).then(), "s3:GetObjectVersionTagging");
        denied(f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .body("<Tagging><TagSet/></Tagging>").when().put(f.path("a/tagged")).then(),
                "s3:PutObjectVersionTagging");
        denied(f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .when().delete(f.path("a/tagged")).then(), "s3:DeleteObjectVersionTagging");

        f.policy(f.allow("s3:GetObjectVersionTagging", "s3:PutObjectVersionTagging",
                "s3:DeleteObjectVersionTagging"));
        f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .body("<Tagging><TagSet/></Tagging>").when().put(f.path("a/tagged")).then().statusCode(200);
        f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .when().get(f.path("a/tagged")).then().statusCode(200);
        f.user().queryParam("tagging", "").queryParam("versionId", versionId)
                .when().delete(f.path("a/tagged")).then().statusCode(204);
    }

    @Test
    void theTagsAVersionTaggingConditionReadsAreThoseOfTheVersionItChanges() {
        Fixture f = new Fixture("version-tag-condition", true);
        String older = f.seedVersion("a/doc", "env=a");
        String current = f.seedVersion("a/doc", "env=b");
        f.policy(f.allowWhen("{\"StringEquals\":{\"s3:ExistingObjectTag/env\":\"a\"}}",
                "s3:PutObjectVersionTagging"));
        String tagging = "<Tagging><TagSet><Tag><Key>env</Key><Value>a</Value></Tag>"
                + "<Tag><Key>seen</Key><Value>yes</Value></Tag></TagSet></Tagging>";

        f.user().queryParam("tagging", "").queryParam("versionId", older).body(tagging)
                .when().put(f.path("a/doc")).then().statusCode(200).header("x-amz-version-id", older);
        denied(f.user().queryParam("tagging", "").queryParam("versionId", current).body(tagging)
                .when().put(f.path("a/doc")).then(), "s3:PutObjectVersionTagging");

        f.admin().queryParam("tagging", "").queryParam("versionId", older).when().get(f.path("a/doc"))
                .then().statusCode(200).body(containsString("<Key>seen</Key>"));
        f.admin().queryParam("tagging", "").when().get(f.path("a/doc"))
                .then().statusCode(200).body(not(containsString("<Key>seen</Key>")));
    }

    @Test
    void aVersionConditionMatchesTheVersionNamed() {
        Fixture f = new Fixture("version-id", true);
        String allowed = f.seedVersion("a/doc");
        String other = f.seedVersion("a/doc");
        f.policy(f.allowWhen("{\"StringEquals\":{\"s3:versionid\":\"" + allowed + "\"}}",
                "s3:GetObjectVersion"));

        f.user().queryParam("versionId", allowed).when().get(f.path("a/doc")).then().statusCode(200);
        denied(f.user().queryParam("versionId", other).when().get(f.path("a/doc")).then(), "s3:GetObjectVersion");
    }

    @Test
    void theAnnotationsOfAVersionNeedTheVersionAnnotationActions() {
        Fixture f = new Fixture("version-annotation", true);
        String versionId = f.seedVersion("a/doc");
        f.admin().queryParam("annotation", "").queryParam("annotationName", "labels")
                .queryParam("versionId", versionId).body("reviewed")
                .when().put(f.path("a/doc")).then().statusCode(200);

        f.policy(f.allow("s3:GetObjectAnnotation", "s3:ListObjectAnnotations", "s3:PutObjectAnnotation",
                "s3:DeleteObjectAnnotation"));
        denied(f.annotation(versionId).when().get(f.path("a/doc")).then(), "s3:GetObjectVersionAnnotation");
        denied(f.user().queryParam("annotation", "").queryParam("versionId", versionId)
                .when().get(f.path("a/doc")).then(), "s3:ListObjectVersionAnnotations");
        denied(f.annotation(versionId).body("changed").when().put(f.path("a/doc")).then(),
                "s3:PutObjectVersionAnnotation");
        denied(f.annotation(versionId).when().delete(f.path("a/doc")).then(), "s3:DeleteObjectVersionAnnotation");

        f.policy(f.allow("s3:GetObjectVersionAnnotation", "s3:DeleteObjectVersionAnnotation"));
        f.annotation(versionId).when().get(f.path("a/doc")).then().statusCode(200);
        f.annotation(versionId).when().delete(f.path("a/doc")).then().statusCode(204);
    }

    @Test
    void anAnnotationIsAuthorizedAgainstTheObjectsTags() {
        Fixture f = new Fixture("annotation-tags", false);
        f.seed("a/ours");
        f.admin().header("x-amz-tagging", "env=b").body("theirs").when().put(f.path("a/theirs"))
                .then().statusCode(200);
        for (String key : List.of("a/ours", "a/theirs")) {
            f.admin().queryParam("annotation", "").queryParam("annotationName", "labels").body("x")
                    .when().put(f.path(key)).then().statusCode(200);
        }
        f.policy(f.allowWhen(EXISTING_A, "s3:GetObjectAnnotation"));

        f.annotation(null).when().get(f.path("a/ours")).then().statusCode(200);
        denied(f.annotation(null).when().get(f.path("a/theirs")).then(), "s3:GetObjectAnnotation");
    }

    @Test
    void bucketConfigurationsAndTagRemovalUseTheActionsAwsChecks() {
        Fixture f = new Fixture("bucket-config", false);

        f.policy(f.allowOnBucket("s3:ListBucket", "s3:CreateBucket", "s3:DeleteBucket"));
        denied(f.user().queryParam("analytics", "").queryParam("id", "a1").when().get("/" + f.bucket).then(),
                "s3:GetAnalyticsConfiguration");
        denied(f.user().queryParam("inventory", "").when().get("/" + f.bucket).then(),
                "s3:GetInventoryConfiguration");
        denied(f.user().queryParam("intelligent-tiering", "").queryParam("id", "t1")
                .when().delete("/" + f.bucket).then(), "s3:PutIntelligentTieringConfiguration");

        // s3:DeleteBucketTagging is no IAM action: AWS checks DeleteBucketTagging as s3:PutBucketTagging.
        f.policy(f.allowOnBucket("s3:DeleteBucketTagging"));
        denied(f.user().queryParam("tagging", "").when().delete("/" + f.bucket).then(), "s3:PutBucketTagging");

        f.policy(f.allowOnBucket("s3:PutBucketTagging"));
        f.user().queryParam("tagging", "").when().delete("/" + f.bucket).then().statusCode(204);
    }

    private static void denied(ValidatableResponse response, String action) {
        response.statusCode(403)
                .body(containsString("AccessDenied"))
                .body(containsString(action));
    }

    private static String retention(String until) {
        return """
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>GOVERNANCE</Mode>
                  <RetainUntilDate>%s</RetainUntilDate>
                </Retention>
                """.formatted(until);
    }

    private record Upload(String key, String id, String partEtag) {}

    /** One bucket seeded as the admin, and one IAM user whose only policy each test replaces. */
    private final class Fixture {
        final String bucket;
        final String userName;
        final String accessKeyId;

        Fixture(String name, boolean objectLock) {
            String suffix = Long.toString(System.nanoTime(), 36);
            bucket = "s3ops-" + name + "-" + suffix;
            userName = "s3ops-" + name + "-" + suffix;
            cleanup.register(() -> removeBucket(objectLock));
            RequestSpecification create = admin();
            if (objectLock) {
                create = create.header("x-amz-bucket-object-lock-enabled", "true");
            }
            create.when().put("/" + bucket).then().statusCode(200);
            accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                    auth(ADMIN, "iam"), userName).accessKeyId();
        }

        RequestSpecification admin() {
            return given().header("Authorization", auth(ADMIN, "s3"));
        }

        RequestSpecification user() {
            return given().header("Authorization", auth(accessKeyId, "s3"));
        }

        String path(String key) {
            return "/" + bucket + "/" + key;
        }

        String allowOnBucket(String... actions) {
            return "{\"Effect\":\"Allow\",\"Action\":[\"" + String.join("\",\"", actions)
                    + "\"],\"Resource\":\"arn:aws:s3:::" + bucket + "\"}";
        }

        String allowWhen(String condition, String... actions) {
            String allow = allow(actions);
            return allow.substring(0, allow.length() - 1) + ",\"Condition\":" + condition + "}";
        }

        String allow(String... actions) {
            return "{\"Effect\":\"Allow\",\"Action\":[\"" + String.join("\",\"", actions)
                    + "\"],\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}";
        }

        void policy(String statement) {
            given().formParam("Action", "PutUserPolicy").formParam("UserName", userName)
                    .formParam("PolicyName", "p")
                    .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                    .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
        }

        void seed(String key) {
            admin().header("x-amz-tagging", "env=a").body("id,name\n1,one\n")
                    .when().put(path(key)).then().statusCode(200);
        }

        /** Writes a version of {@code key} as the admin and returns its version id. */
        String seedVersion(String key) {
            return seedVersion(key, "env=a");
        }

        String seedVersion(String key, String tagging) {
            return admin().header("x-amz-tagging", tagging).body("version")
                    .when().put(path(key)).then().statusCode(200).extract().header("x-amz-version-id");
        }

        /** A user request on the annotation "labels", of the version {@code versionId} names when set. */
        RequestSpecification annotation(String versionId) {
            RequestSpecification request = user().queryParam("annotation", "").queryParam("annotationName", "labels");
            return versionId == null ? request : request.queryParam("versionId", versionId);
        }

        String etag(String key) {
            return admin().when().head(path(key)).then().statusCode(200).extract().header("ETag");
        }

        Upload uploadOnePart(String key) {
            String uploadId = admin().queryParam("uploads", "").when().post(path(key))
                    .then().statusCode(200)
                    .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            cleanup.register(() -> admin().queryParam("uploadId", uploadId).when().delete(path(key)));
            String partEtag = admin().queryParam("uploadId", uploadId).queryParam("partNumber", 1)
                    .body("part one")
                    .when().put(path(key)).then().statusCode(200).extract().header("ETag");
            return new Upload(key, uploadId, partEtag);
        }

        ValidatableResponse complete(Upload upload) {
            return complete(upload, null);
        }

        /** Completes {@code upload} as the user, with {@code ifMatch} as its If-Match header when set. */
        ValidatableResponse complete(Upload upload, String ifMatch) {
            RequestSpecification request = user();
            if (ifMatch != null) {
                request = request.header("If-Match", ifMatch);
            }
            return request.queryParam("uploadId", upload.id())
                    .contentType("application/xml")
                    .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                            + upload.partEtag() + "</ETag></Part></CompleteMultipartUpload>")
                    .when().post(path(upload.key())).then();
        }

        ValidatableResponse restore(String key) {
            return user().queryParam("restore", "").contentType("application/xml")
                    .body("<RestoreRequest><Days>1</Days></RestoreRequest>")
                    .when().post(path(key)).then();
        }

        ValidatableResponse select(String key) {
            return select(key, null);
        }

        ValidatableResponse select(String key, String versionId) {
            RequestSpecification request = user();
            if (versionId != null) {
                request = request.queryParam("versionId", versionId);
            }
            return request.queryParam("select", "").queryParam("select-type", "2")
                    .contentType("application/xml")
                    .body("""
                            <SelectObjectContentRequest>
                              <Expression>SELECT * FROM S3Object</Expression>
                              <ExpressionType>SQL</ExpressionType>
                              <InputSerialization><CSV><FileHeaderInfo>USE</FileHeaderInfo></CSV></InputSerialization>
                              <OutputSerialization><CSV/></OutputSerialization>
                            </SelectObjectContentRequest>
                            """)
                    .when().post(path(key)).then();
        }

        ValidatableResponse putRetention(String key, String until) {
            return user().queryParam("retention", "").body(retention(until)).when().put(path(key)).then();
        }

        /**
         * Removes every version and delete marker, bypassing governance retention where a test set
         * one, then the bucket. No test sets compliance retention or a legal hold, which nothing
         * could remove.
         */
        void removeBucket(boolean objectLock) {
            if (!objectLock) {
                EnforcementFixtures.removeBucket(baseUri.getPort(), auth(ADMIN, "s3"), bucket);
                return;
            }
            XmlPath versions = admin().queryParam("versions", "").when().get("/" + bucket)
                    .then().statusCode(200).extract().xmlPath();
            for (String entry : List.of("Version", "DeleteMarker")) {
                List<String> keys = versions.getList("ListVersionsResult." + entry + ".Key", String.class);
                List<String> ids = versions.getList("ListVersionsResult." + entry + ".VersionId", String.class);
                for (int i = 0; i < keys.size(); i++) {
                    admin().header("x-amz-bypass-governance-retention", "true")
                            .queryParam("versionId", ids.get(i))
                            .when().delete(path(keys.get(i))).then().statusCode(204);
                }
            }
            admin().when().delete("/" + bucket).then().statusCode(204);
        }
    }
}
