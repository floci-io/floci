package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each S3 operation is checked as the action AWS documents for it: the multipart upload operations
 * and the other POST operations, which no rule mapped, the multipart operations that were checked as
 * a plain read or delete, the object-lock operations, the version actions a versionId selects, and
 * the bucket configurations. A request naming several sub-resources is checked as the one S3 runs,
 * and the version actions carry the version id and the version's tags. An operation also needs the
 * actions it requires besides its own: an object creation those of the settings it applies,
 * GetObjectAttributes its own action beside the read, and a governance bypass its permission.
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
        f.admin().header("x-amz-storage-class", "GLACIER").header("x-amz-tagging", "env=a")
                .body("id,name\n1,one\n").when().put(f.path("a/archived")).then().statusCode(200);

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
    void bypassingGovernanceRetentionNeedsBypassGovernanceRetention() {
        Fixture f = new Fixture("bypass", true);
        f.seed("a/governed");
        f.admin().queryParam("retention", "")
                .body(retention("2031-01-01T00:00:00Z"))
                .when().put(f.path("a/governed")).then().statusCode(200);

        // Extending the lock overrides nothing, so the header asks for nothing more; shortening it does.
        f.policy(f.allow("s3:PutObjectRetention"));
        f.putRetention("a/governed", "2032-01-01T00:00:00Z", true).statusCode(200);
        denied(f.putRetention("a/governed", "2030-01-01T00:00:00Z", true), "s3:BypassGovernanceRetention");

        f.policy(f.allow("s3:PutObjectRetention", "s3:BypassGovernanceRetention"));
        f.putRetention("a/governed", "2030-01-01T00:00:00Z", true).statusCode(200);
    }

    @Test
    void deletingWithTheBypassNeedsBypassGovernanceRetentionOnlyToRemoveALockedVersion() {
        // The permissions table: s3:BypassGovernanceRetention is "Required if you want to delete an
        // object that's protected by governance mode", so the header on a delete that removes no
        // locked version asks for nothing more.
        Fixture f = new Fixture("delete-bypass", true);
        String versionId = f.seedVersion("a/governed");
        String free = f.seedVersion("a/free");
        f.admin().queryParam("retention", "").queryParam("versionId", versionId)
                .body(retention("2031-01-01T00:00:00Z"))
                .when().put(f.path("a/governed")).then().statusCode(200);

        f.policy(f.allow("s3:DeleteObject", "s3:DeleteObjectVersion"));
        // An unlocked version, and a delete that only adds a delete marker over the locked one.
        f.user().header("x-amz-bypass-governance-retention", "true").queryParam("versionId", free)
                .when().delete(f.path("a/free")).then().statusCode(204);
        f.user().header("x-amz-bypass-governance-retention", "true")
                .when().delete(f.path("a/governed")).then().statusCode(204);
        denied(f.user().header("x-amz-bypass-governance-retention", "true").queryParam("versionId", versionId)
                .when().delete(f.path("a/governed")).then(), "s3:BypassGovernanceRetention");

        f.policy(f.allow("s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"));
        f.user().header("x-amz-bypass-governance-retention", "true").queryParam("versionId", versionId)
                .when().delete(f.path("a/governed")).then().statusCode(204);
    }

    @Test
    void theBypassIsDecidedOnTheKeyARequestNamesEvenWithALeadingSlash() {
        // A key that starts with "/" travels as "//" after the bucket, which JAX-RS collapses to the
        // key without it. The lock is looked up on the key the request names, so a governance lock on
        // "/governed" still needs s3:BypassGovernanceRetention to shorten or delete.
        Fixture f = new Fixture("slash-bypass", true);
        HttpResponse<String> put = f.raw("PUT", "/governed", null, ADMIN, Map.of(), "version");
        assertEquals(200, put.statusCode(), put.body());
        String versionId = put.headers().firstValue("x-amz-version-id").orElseThrow();
        String version = "versionId=" + versionId;
        assertEquals(200, f.raw("PUT", "/governed", "retention&" + version, ADMIN, Map.of(),
                retention("2031-01-01T00:00:00Z")).statusCode());
        Map<String, String> bypass = Map.of("x-amz-bypass-governance-retention", "true");

        f.policy(f.allow("s3:PutObjectRetention", "s3:DeleteObjectVersion"));
        rawDenied(f.raw("PUT", "/governed", "retention&" + version, f.accessKeyId, bypass,
                retention("2030-01-01T00:00:00Z")), "s3:BypassGovernanceRetention");
        rawDenied(f.raw("DELETE", "/governed", version, f.accessKeyId, bypass, null),
                "s3:BypassGovernanceRetention");

        f.policy(f.allow("s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"));
        assertEquals(204, f.raw("DELETE", "/governed", version, f.accessKeyId, bypass, null).statusCode());
    }

    @Test
    void anExistingObjectTagConditionReadsTheTagsOfTheKeyARequestNames() {
        // "/doc" and "doc" are two objects; a read of "/doc" is authorized against its own tags.
        Fixture f = new Fixture("slash-tags", true);
        assertEquals(200, f.raw("PUT", "/doc", null, ADMIN, Map.of("x-amz-tagging", "env=a"), "slash").statusCode());
        f.admin().header("x-amz-tagging", "env=b").body("plain").when().put(f.path("doc")).then().statusCode(200);

        f.policy(f.allowWhen("{\"StringEquals\":{\"s3:ExistingObjectTag/env\":\"a\"}}", "s3:GetObject"));
        HttpResponse<String> slash = f.raw("GET", "/doc", null, f.accessKeyId, Map.of(), null);
        assertEquals(200, slash.statusCode(), slash.body());
        assertEquals("slash", slash.body());
        denied(f.user().when().get(f.path("doc")).then(), "s3:GetObject");
    }

    @Test
    void aKeyOfOnlySlashesIsCheckedAsTheObjectItNames() {
        // PUT /bucket// writes the key "/". Collapsed to the bucket's path, it ran CreateBucket, checked as that.
        Fixture f = new Fixture("slash-only", false);
        f.policy(f.allowOnBucket("s3:CreateBucket", "s3:ListBucket", "s3:DeleteBucket"));
        rawDenied(f.raw("PUT", "/", null, f.accessKeyId, Map.of(), "slash"), "s3:PutObject");

        f.policy("{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],"
                + "\"Resource\":\"arn:aws:s3:::" + f.bucket + "//\"}");
        assertEquals(200, f.raw("PUT", "/", null, f.accessKeyId, Map.of(), "slash").statusCode());
        assertEquals("slash", f.raw("GET", "/", null, f.accessKeyId, Map.of(), null).body());
        assertEquals(204, f.raw("DELETE", "/", null, f.accessKeyId, Map.of(), null).statusCode());
    }

    @Test
    void aKeyThatStartsWithASlashIsCheckedAsTheObjectItNames() {
        // PUT /bucket//email.txt writes the key "/email.txt". Read from the collapsed path, it was
        // checked on arn:aws:s3:::bucket/email.txt, the other object.
        Fixture f = new Fixture("leading-slash", false);
        f.policy("{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],"
                + "\"Resource\":\"arn:aws:s3:::" + f.bucket + "/email.txt\"}");
        rawDenied(f.raw("PUT", "/email.txt", null, f.accessKeyId, Map.of(), "slash"), "s3:PutObject");

        f.policy("{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],"
                + "\"Resource\":\"arn:aws:s3:::" + f.bucket + "//*\"}");
        assertEquals(200, f.raw("PUT", "/email.txt", null, f.accessKeyId, Map.of(), "slash").statusCode());
        assertEquals("slash", f.raw("GET", "/email.txt", null, f.accessKeyId, Map.of(), null).body());
        assertEquals(204, f.raw("DELETE", "/email.txt", null, f.accessKeyId, Map.of(), null).statusCode());
    }

    @Test
    void aBucketSubresourceFlociDoesNotServeIsCheckedAsTheOperationItNames() {
        // DELETE ?metadataTable fell through to deleting the bucket, and was checked as s3:DeleteBucket.
        Fixture f = new Fixture("metadata-table", false);
        f.policy(f.allowOnBucket("s3:DeleteBucket"));
        denied(f.user().queryParam("metadataTable", "").when().delete("/" + f.bucket).then(),
                "s3:DeleteBucketMetadataTableConfiguration");
        // POST ?metadataTable, which no IAM rule mapped, went unchecked.
        denied(f.user().queryParam("metadataTable", "").when().post("/" + f.bucket).then(),
                "s3:CreateBucketMetadataTableConfiguration");

        f.policy(f.allowOnBucket("s3:DeleteBucketMetadataTableConfiguration",
                "s3:CreateBucketMetadataTableConfiguration"));
        f.user().queryParam("metadataTable", "").when().delete("/" + f.bucket).then().statusCode(501);
        f.user().queryParam("metadataTable", "").when().post("/" + f.bucket).then().statusCode(501);
        f.admin().when().head("/" + f.bucket).then().statusCode(200);
    }

    @Test
    void deletingAnAnnotationWithTheBypassHeaderNeedsNoBypassPermission() {
        // The user guide: "The BypassGovernanceRetention permission does not apply to annotation
        // operations."
        Fixture f = new Fixture("annotation-bypass", false);
        f.seed("a/doc");
        f.admin().queryParam("annotation", "").queryParam("annotationName", "labels").body("x")
                .when().put(f.path("a/doc")).then().statusCode(200);

        f.policy(f.allow("s3:DeleteObjectAnnotation"));
        f.annotation(null).header("x-amz-bypass-governance-retention", "true")
                .when().delete(f.path("a/doc")).then().statusCode(204);
    }

    @Test
    void getObjectAttributesNeedsItsOwnActionAndTheRead() {
        // The API reference: s3:GetObject and s3:GetObjectAttributes, or for a version
        // s3:GetObjectVersion and s3:GetObjectVersionAttributes.
        Fixture f = new Fixture("attributes", true);
        String versionId = f.seedVersion("a/doc");

        f.policy(f.allow("s3:GetObject", "s3:GetObjectVersion"));
        denied(f.attributes("a/doc", null), "s3:GetObjectAttributes");
        denied(f.attributes("a/doc", versionId), "s3:GetObjectVersionAttributes");

        f.policy(f.allow("s3:GetObjectAttributes", "s3:GetObjectVersionAttributes"));
        denied(f.attributes("a/doc", null), "s3:GetObject");
        denied(f.attributes("a/doc", versionId), "s3:GetObjectVersion");

        f.policy(f.allow("s3:GetObject", "s3:GetObjectAttributes"));
        f.attributes("a/doc", null).statusCode(200);
        f.policy(f.allow("s3:GetObjectVersion", "s3:GetObjectVersionAttributes"));
        f.attributes("a/doc", versionId).statusCode(200);
    }

    @Test
    void anObjectCreatedWithTagsOrAnAclNeedsThoseActionsToo() {
        // The permissions table: s3:PutObjectTagging and s3:PutObjectAcl are conditionally required
        // for PutObject, CopyObject and CreateMultipartUpload.
        Fixture f = new Fixture("create-extras", false);
        f.seed("a/source");

        f.policy(f.allow("s3:PutObject", "s3:GetObject"));
        f.user().body("plain").when().put(f.path("a/plain")).then().statusCode(200);
        denied(f.user().header("x-amz-tagging", "env=a").body("tagged").when().put(f.path("a/tagged")).then(),
                "s3:PutObjectTagging");
        denied(f.user().header("x-amz-acl", "bucket-owner-full-control").body("acl")
                .when().put(f.path("a/acl")).then(), "s3:PutObjectAcl");
        denied(f.user().header("x-amz-grant-read", "id=000000000000").body("grant")
                .when().put(f.path("a/grant")).then(), "s3:PutObjectAcl");
        denied(f.user().header("x-amz-tagging", "env=a").queryParam("uploads", "")
                .when().post(f.path("a/upload")).then(), "s3:PutObjectTagging");
        // A multipart upload ignores x-amz-copy-source and applies its own tags, so the header does not
        // make it a copy whose tags come from a source.
        denied(f.user().header("x-amz-tagging", "env=a").header("x-amz-copy-source", f.bucket + "/a/source")
                .queryParam("uploads", "").when().post(f.path("a/upload")).then(), "s3:PutObjectTagging");
        denied(f.user().header("x-amz-acl", "private").queryParam("uploads", "")
                .when().post(f.path("a/upload")).then(), "s3:PutObjectAcl");
        denied(f.copy().header("x-amz-tagging-directive", "REPLACE").header("x-amz-tagging", "env=a")
                .when().put(f.path("a/copy")).then(), "s3:PutObjectTagging");
        denied(f.copy().header("x-amz-tagging-directive", "REPLACE").header("x-amz-acl", "private")
                .when().put(f.path("a/copy")).then(), "s3:PutObjectAcl");
        // Without REPLACE a copy puts the source's tags on the new object, so a tagged source needs
        // the tagging action too, and an untagged one does not, whatever tags the request sends.
        denied(f.copy().when().put(f.path("a/copy")).then(), "s3:PutObjectTagging");
        f.admin().body("untagged").when().put(f.path("a/untagged")).then().statusCode(200);
        f.copyOf("a/untagged").header("x-amz-tagging", "env=a").when().put(f.path("a/copy")).then()
                .statusCode(200);

        f.policy(f.allow("s3:PutObject", "s3:GetObject", "s3:PutObjectTagging", "s3:PutObjectAcl"));
        f.user().header("x-amz-tagging", "env=a").body("tagged").when().put(f.path("a/tagged")).then()
                .statusCode(200);
        f.user().header("x-amz-acl", "bucket-owner-full-control").body("acl").when().put(f.path("a/acl")).then()
                .statusCode(200);
        f.copy().header("x-amz-tagging-directive", "REPLACE").header("x-amz-tagging", "env=a")
                .when().put(f.path("a/copy")).then().statusCode(200);
        f.copy().when().put(f.path("a/copied-tags")).then().statusCode(200);
        f.admin().queryParam("tagging", "").when().get(f.path("a/copied-tags")).then()
                .statusCode(200).body(containsString("<Value>a</Value>"));
    }

    @Test
    void aCopyIsNotAuthorizedByTheTagsItIgnores() {
        // Under the default COPY directive a copy ignores x-amz-tagging, so the tags it names cannot
        // satisfy a condition on the new object's tags, on s3:PutObject or on an extra action.
        Fixture f = new Fixture("copy-ignored-tags", false);
        f.admin().body("untagged").when().put(f.path("a/untagged")).then().statusCode(200);
        String taggedB = "{\"StringEquals\":{\"s3:RequestObjectTag/env\":\"b\"}}";

        f.policy(f.allow("s3:GetObject") + "," + f.allowWhen(taggedB, "s3:PutObject"));
        denied(f.copyOf("a/untagged").header("x-amz-tagging", "env=b").when().put(f.path("a/copy")).then(),
                "s3:PutObject");

        f.policy(f.allow("s3:GetObject", "s3:PutObject") + ","
                + f.allowWhen(taggedB, "s3:PutObjectAcl", "s3:PutObjectTagging"));
        denied(f.copyOf("a/untagged").header("x-amz-tagging", "env=b").header("x-amz-acl", "bucket-owner-full-control")
                .when().put(f.path("a/copy")).then(), "s3:PutObjectAcl");
        f.copyOf("a/untagged").header("x-amz-tagging-directive", "REPLACE").header("x-amz-tagging", "env=b")
                .header("x-amz-acl", "bucket-owner-full-control").when().put(f.path("a/copy")).then().statusCode(200);
    }

    @Test
    void aPresignedCopyOfATaggedSourceNeedsTheTaggingActionToo() {
        // The source's tags are checked against the presigned URL's credential, as a signed copy's
        // are against its Authorization header, and an empty header does not shed that credential.
        Fixture f = new Fixture("presigned-copy", false);
        f.seed("a/source");
        String source = "/" + f.bucket + "/a/source";

        f.policy(f.allow("s3:PutObject", "s3:GetObject"));
        denied(f.presigned().header("x-amz-copy-source", source)
                .when().put(f.presignedPath("a/copy")).then(), "s3:PutObjectTagging");
        denied(f.presigned().header("Authorization", "").header("x-amz-copy-source", source)
                .when().put(f.presignedPath("a/copy")).then(), "s3:PutObjectTagging");

        f.policy(f.allow("s3:PutObject", "s3:GetObject", "s3:PutObjectTagging"));
        f.presigned().header("x-amz-copy-source", source)
                .when().put(f.presignedPath("a/copy")).then().statusCode(200);
    }

    @Test
    void aCopyFromAMissingSourceIsReportedMissing() {
        // A source that is not there has no tags to check, and the copy answers for it.
        Fixture f = new Fixture("copy-missing", false);
        f.policy(f.allow("s3:PutObject", "s3:GetObject"));
        f.copyOf("a/missing").when().put(f.path("a/copy")).then()
                .statusCode(404).body(containsString("NoSuchKey"));
    }

    @Test
    void anObjectCreatedWithObjectLockSettingsNeedsTheirActions() {
        // The permissions table: s3:PutObjectRetention and s3:PutObjectLegalHold are conditionally
        // required for PutObject, CopyObject and CreateMultipartUpload.
        Fixture f = new Fixture("create-lock", true);

        f.policy(f.allow("s3:PutObject"));
        denied(f.user().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z").body("locked")
                .when().put(f.path("a/retained")).then(), "s3:PutObjectRetention");
        denied(f.user().header("x-amz-object-lock-legal-hold", "OFF").body("held")
                .when().put(f.path("a/held")).then(), "s3:PutObjectLegalHold");
        denied(f.user().header("x-amz-object-lock-legal-hold", "OFF").queryParam("uploads", "")
                .when().post(f.path("a/upload")).then(), "s3:PutObjectLegalHold");

        f.policy(f.allow("s3:PutObject", "s3:PutObjectRetention", "s3:PutObjectLegalHold"));
        f.user().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z").body("locked")
                .when().put(f.path("a/retained")).then().statusCode(200);
        f.user().header("x-amz-object-lock-legal-hold", "OFF").body("held")
                .when().put(f.path("a/held")).then().statusCode(200);
    }

    @Test
    void objectLockConditionKeysLimitWhatARequestMaySet() {
        // The keys come from the retention and legal-hold bodies, and from an object creation's
        // headers, which reach the extra actions it is checked as.
        Fixture f = new Fixture("lock-keys", true);
        f.seed("a/locked");
        f.policy(f.allow("s3:PutObject", "s3:PutObjectRetention", "s3:PutObjectLegalHold") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:PutObjectRetention\",\"Resource\":\"arn:aws:s3:::"
                + f.bucket + "/*\",\"Condition\":{\"DateGreaterThan\":"
                + "{\"s3:object-lock-retain-until-date\":\"2035-01-01T00:00:00Z\"}}},"
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:PutObjectLegalHold\",\"Resource\":\"arn:aws:s3:::"
                + f.bucket + "/*\",\"Condition\":{\"StringEquals\":{\"s3:object-lock-legal-hold\":\"ON\"}}}");

        denied(f.putRetention("a/locked", "2036-01-01T00:00:00Z"), "s3:PutObjectRetention");
        f.putRetention("a/locked", "2031-01-01T00:00:00Z").statusCode(200);
        denied(f.legalHold("a/locked", "ON"), "s3:PutObjectLegalHold");
        f.legalHold("a/locked", "OFF").statusCode(200);

        denied(f.user().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2036-01-01T00:00:00Z").body("locked")
                .when().put(f.path("a/created")).then(), "s3:PutObjectRetention");
        f.user().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z").body("locked")
                .when().put(f.path("a/created")).then().statusCode(200);
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

    private static void rawDenied(HttpResponse<String> response, String action) {
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("AccessDenied") && response.body().contains(action), response.body());
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

        /**
         * Sends a request on {@code key} with the path as written, signed as {@code accessKeyId}:
         * RestAssured, like JAX-RS, collapses the double slash a key starting with "/" travels as.
         */
        HttpResponse<String> raw(String method, String key, String query, String accessKeyId,
                                 Map<String, String> headers, String body) {
            URI uri = URI.create("http://localhost:" + baseUri.getPort() + path(key) + (query == null ? "" : "?" + query));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .header("Authorization", auth(accessKeyId, "s3"))
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            try (HttpClient client = HttpClient.newHttpClient()) {
                return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
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
            return putRetention(key, until, false);
        }

        ValidatableResponse putRetention(String key, String until, boolean bypass) {
            RequestSpecification request = user().queryParam("retention", "").body(retention(until));
            if (bypass) {
                request = request.header("x-amz-bypass-governance-retention", "true");
            }
            return request.when().put(path(key)).then();
        }

        ValidatableResponse legalHold(String key, String status) {
            return user().queryParam("legal-hold", "")
                    .body("<LegalHold><Status>" + status + "</Status></LegalHold>")
                    .when().put(path(key)).then();
        }

        ValidatableResponse attributes(String key, String versionId) {
            RequestSpecification request = user().queryParam("attributes", "")
                    .header("x-amz-object-attributes", "ETag");
            if (versionId != null) {
                request = request.queryParam("versionId", versionId);
            }
            return request.when().get(path(key)).then();
        }

        /** A user CopyObject request from the seeded {@code a/source}. */
        RequestSpecification copy() {
            return copyOf("a/source");
        }

        RequestSpecification copyOf(String key) {
            return user().header("x-amz-copy-source", "/" + bucket + "/" + key);
        }

        /** A request the user presigns with {@link #presignedPath}, which carries its credential. */
        RequestSpecification presigned() {
            return given().urlEncodingEnabled(false);
        }

        String presignedPath(String key) {
            return path(key) + "?" + EnforcementFixtures.presignedQuery(accessKeyId, "s3");
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
                    if (keys.get(i).startsWith("/")) {
                        assertEquals(204, raw("DELETE", keys.get(i), "versionId=" + ids.get(i), ADMIN,
                                Map.of("x-amz-bypass-governance-retention", "true"), null).statusCode());
                        continue;
                    }
                    admin().header("x-amz-bypass-governance-retention", "true")
                            .queryParam("versionId", ids.get(i))
                            .when().delete(path(keys.get(i))).then().statusCode(204);
                }
            }
            admin().when().delete("/" + bucket).then().statusCode(204);
        }
    }
}
