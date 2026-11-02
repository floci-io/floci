package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;
import software.amazon.awssdk.services.s3.model.GetObjectAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.ObjectOwnership;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S3 refuses a request that sets a canned ACL ({@code x-amz-acl}) together with any
 * {@code x-amz-grant-*} header, on every operation that takes ACL headers.
 */
@QuarkusTest
class S3AclHeaderValidationIntegrationTest {

    private static final String MESSAGE = "Specifying both Canned ACLs and Header Grants is not allowed";
    private static final String GRANTEE_ID = "123456789012";
    private static final String GRANTEE = "id=\"" + GRANTEE_ID + "\"";

    @TestHTTPResource("/")
    URI baseUri;

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(baseUri)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        bucket = "acl-header-validation-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket).objectOwnership(ObjectOwnership.OBJECT_WRITER));
        s3.putObject(request -> request.bucket(bucket).key("existing"), RequestBody.fromString("existing"));
    }

    @AfterEach
    void closeClient() {
        s3.close();
    }

    static Stream<String> grantHeaders() {
        return Stream.of("x-amz-grant-read", "x-amz-grant-write", "x-amz-grant-full-control",
                "x-amz-grant-read-acp", "x-amz-grant-write-acp");
    }

    @ParameterizedTest
    @MethodSource("grantHeaders")
    void putBucketAclRefusesCannedAclWithGrantHeader(String grantHeader) {
        List<Grant> before = s3.getBucketAcl(request -> request.bucket(bucket)).grants();

        assertRefused(() -> s3.putBucketAcl(request -> request.bucket(bucket)
                .acl(BucketCannedACL.PRIVATE)
                .overrideConfiguration(o -> o.putHeader(grantHeader, GRANTEE))));

        assertEquals(before, s3.getBucketAcl(request -> request.bucket(bucket)).grants());
    }

    @ParameterizedTest
    @MethodSource("grantHeaders")
    void putObjectAclRefusesCannedAclWithGrantHeader(String grantHeader) {
        List<Grant> before = s3.getObjectAcl(request -> request.bucket(bucket).key("existing")).grants();

        assertRefused(() -> s3.putObjectAcl(request -> request.bucket(bucket).key("existing")
                .acl(ObjectCannedACL.PRIVATE)
                .overrideConfiguration(o -> o.putHeader(grantHeader, GRANTEE))));

        assertEquals(before, s3.getObjectAcl(request -> request.bucket(bucket).key("existing")).grants());
    }

    @ParameterizedTest
    @MethodSource("grantHeaders")
    void putObjectRefusesCannedAclWithGrantHeader(String grantHeader) {
        assertRefused(() -> s3.putObject(request -> request.bucket(bucket).key("refused")
                        .acl(ObjectCannedACL.PRIVATE)
                        .overrideConfiguration(o -> o.putHeader(grantHeader, GRANTEE)),
                RequestBody.fromString("refused")));

        assertObjectAbsent("refused");
    }

    @ParameterizedTest
    @MethodSource("grantHeaders")
    void copyObjectRefusesCannedAclWithGrantHeader(String grantHeader) {
        assertRefused(() -> s3.copyObject(request -> request
                .sourceBucket(bucket).sourceKey("existing")
                .destinationBucket(bucket).destinationKey("copy")
                .acl(ObjectCannedACL.PRIVATE)
                .overrideConfiguration(o -> o.putHeader(grantHeader, GRANTEE))));

        assertObjectAbsent("copy");
    }

    @ParameterizedTest
    @MethodSource("grantHeaders")
    void createMultipartUploadRefusesCannedAclWithGrantHeader(String grantHeader) {
        assertRefused(() -> s3.createMultipartUpload(request -> request.bucket(bucket).key("multipart")
                .acl(ObjectCannedACL.PRIVATE)
                .overrideConfiguration(o -> o.putHeader(grantHeader, GRANTEE))));

        assertThat(s3.listMultipartUploads(request -> request.bucket(bucket)).uploads(), empty());
    }

    @Test
    void putObjectRefusesTypedCannedAclAndGrantFields() {
        assertRefused(() -> s3.putObject(request -> request.bucket(bucket).key("typed")
                        .acl(ObjectCannedACL.BUCKET_OWNER_READ)
                        .grantRead(GRANTEE),
                RequestBody.fromString("typed")));

        assertObjectAbsent("typed");
    }

    @Test
    void cannedAclAloneIsStillApplied() {
        s3.putObject(request -> request.bucket(bucket).key("canned").acl(ObjectCannedACL.BUCKET_OWNER_READ),
                RequestBody.fromString("canned"));
        s3.putObjectAcl(request -> request.bucket(bucket).key("existing").acl(ObjectCannedACL.PRIVATE));
        s3.putBucketAcl(request -> request.bucket(bucket).acl(BucketCannedACL.PRIVATE));

        assertEquals("canned", s3.getObjectAsBytes(request -> request.bucket(bucket).key("canned")).asUtf8String());
    }

    @Test
    void grantsAloneAreStillApplied() {
        s3.putObject(request -> request.bucket(bucket).key("granted").grantRead(GRANTEE),
                RequestBody.fromString("granted"));
        assertGrantedRead("granted");

        s3.putObjectAcl(request -> request.bucket(bucket).key("existing").grantRead(GRANTEE));
        assertGrantedRead("existing");

        s3.putBucketAcl(request -> request.bucket(bucket).grantRead(GRANTEE));
        assertThat(s3.getBucketAcl(request -> request.bucket(bucket)).grants().stream()
                .filter(grant -> grant.permission() == Permission.READ)
                .map(grant -> grant.grantee().id())
                .toList(), hasItem(GRANTEE_ID));
    }

    @Test
    void blankGrantHeaderWithCannedAclIsAccepted() {
        given().header("x-amz-acl", "private").header("x-amz-grant-read", "")
                .body("blank grant")
                .when().put("/" + bucket + "/blank-grant")
                .then().statusCode(200);
        given().header("x-amz-acl", "private").header("x-amz-grant-full-control", " ")
                .when().put("/" + bucket + "?acl")
                .then().statusCode(200);

        assertThat(granteeIds("blank-grant"), not(hasItem(GRANTEE_ID)));
    }

    @Test
    void blankCannedAclWithGrantHeaderAppliesTheGrant() {
        given().header("x-amz-acl", "").header("x-amz-grant-read", GRANTEE)
                .body("blank canned")
                .when().put("/" + bucket + "/blank-canned")
                .then().statusCode(200);

        assertGrantedRead("blank-canned");
    }

    @Test
    void refusalIsAnS3InvalidRequestError() {
        given().header("x-amz-acl", "private").header("x-amz-grant-write-acp", GRANTEE)
                .when().put("/" + bucket + "/existing?acl")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidRequest</Code>"))
                .body(containsString("<Message>" + MESSAGE + "</Message>"));
    }

    private static void assertRefused(Runnable call) {
        S3Exception error = assertThrows(S3Exception.class, call::run);
        assertEquals(400, error.statusCode());
        assertEquals("InvalidRequest", error.awsErrorDetails().errorCode());
        assertEquals(MESSAGE, error.awsErrorDetails().errorMessage());
    }

    private void assertObjectAbsent(String key) {
        assertThrows(NoSuchKeyException.class,
                () -> s3.getObject(request -> request.bucket(bucket).key(key)).close());
    }

    private void assertGrantedRead(String key) {
        GetObjectAclResponse acl = s3.getObjectAcl(request -> request.bucket(bucket).key(key));
        assertThat(acl.grants().stream()
                .filter(grant -> grant.permission() == Permission.READ)
                .map(grant -> grant.grantee().id())
                .toList(), hasItem(GRANTEE_ID));
    }

    private List<String> granteeIds(String key) {
        return s3.getObjectAcl(request -> request.bucket(bucket).key(key)).grants().stream()
                .map(grant -> grant.grantee().id())
                .toList();
    }
}
