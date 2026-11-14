package io.floci.aws.services.s3;

import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3ConfiguredEndpointIntegrationTest.Profile.class)
class S3ConfiguredEndpointIntegrationTest {
    private static final String ENDPOINT = "configured-endpoint.localhost.floci.io";

    @TestHTTPResource("/")
    URI baseUri;

    @ParameterizedTest
    @ValueSource(strings = {ENDPOINT, "alias." + ENDPOINT})
    void signedEndpointListsBucketsWhileItsSubdomainStillAddressesABucket(String hostname) throws Exception {
        String bucket = "configured-endpoint";
        try (S3Client client = client()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                assertTrue(client.listBuckets().buckets().stream().anyMatch(row -> bucket.equals(row.name())));
                URI endpoint = endpoint(hostname, "/");
                List<String> buckets = signedRequest("GET", endpoint, new byte[0])
                        .when().get("/")
                        .then().statusCode(200)
                        .extract().xmlPath().getList("ListAllMyBucketsResult.Buckets.Bucket.Name", String.class);
                assertTrue(buckets.contains(bucket));

                URI virtualHost = endpoint(bucket + "." + hostname, "/");
                signedRequest("GET", virtualHost, new byte[0])
                        .when().get("/")
                        .then().statusCode(200)
                        .body("ListBucketResult.Name", is(bucket));
            } finally {
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ENDPOINT, "alias." + ENDPOINT})
    void endpointRootPutCannotAddressPrefixBucket(String hostname) throws Exception {
        String bucket = hostname.substring(0, hostname.indexOf(".localhost"));
        String key = "endpoint-object";
        byte[] body = "endpoint-root-payload".getBytes(StandardCharsets.UTF_8);
        try (S3Client client = client()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                signedRequest("PUT", endpoint(hostname, "/" + key), body)
                        .header("Content-Disposition", "attachment")
                        .when().put("/" + key)
                        .then().statusCode(400).body("Error.Code", is("MalformedXML"));
                assertTrue(client.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());

                signedRequest("PUT", endpoint(bucket + "." + hostname, "/" + key), body)
                        .header("Content-Disposition", "attachment")
                        .when().put("/" + key)
                        .then().statusCode(200);
                assertEquals("endpoint-root-payload", client.getObjectAsBytes(request -> request
                        .bucket(bucket).key(key)).asUtf8String());
            } finally {
                client.deleteObject(request -> request.bucket(bucket).key(key));
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ENDPOINT, "alias." + ENDPOINT})
    void endpointRootMultipartCannotAddressPrefixBucket(String hostname) throws Exception {
        String bucket = hostname.substring(0, hostname.indexOf(".localhost"));
        String path = "/endpoint-multipart?uploads";
        try (S3Client client = client()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                signedRequest("POST", endpoint(hostname, path), new byte[0])
                        .when().post(path)
                        .then().statusCode(405).body("Error.Code", is("MethodNotAllowed"));
                assertTrue(client.listMultipartUploads(request -> request.bucket(bucket)).uploads().isEmpty());

                String uploadId = signedRequest("POST", endpoint(bucket + "." + hostname, path), new byte[0])
                        .when().post(path)
                        .then().statusCode(200)
                        .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
                assertFalse(uploadId.isBlank());
                assertEquals(1, client.listMultipartUploads(request -> request.bucket(bucket)).uploads().size());
            } finally {
                for (MultipartUpload upload : client.listMultipartUploads(request -> request.bucket(bucket)).uploads()) {
                    client.abortMultipartUpload(request -> request.bucket(bucket)
                            .key(upload.key()).uploadId(upload.uploadId()));
                }
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    private URI endpoint(String hostname, String path) {
        return URI.create("http://" + hostname + ":" + baseUri.getPort() + path);
    }

    private RequestSpecification signedRequest(String method, URI uri, byte[] body) throws Exception {
        RequestSpecification request = given().headers(S3RequestSigner.signedAs("test", "test")
                        .headersFor(method, uri, body))
                .header("Host", uri.getAuthority());
        if (body.length > 0) {
            request.body(body);
        }
        return request;
    }

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(baseUri)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.hostname", ENDPOINT,
                    "floci.dns.extra-suffixes", "alias." + ENDPOINT,
                    "floci.services.s3.enforce-auth", "true");
        }
    }
}
