package io.floci.aws.services.s3;

import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
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
        try (S3Client client = S3Client.builder()
                .endpointOverride(baseUri)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                assertTrue(client.listBuckets().buckets().stream().anyMatch(row -> bucket.equals(row.name())));
                URI endpoint = URI.create("http://" + hostname + ":" + baseUri.getPort() + "/");
                List<String> buckets = given().headers(S3RequestSigner.signedAs("test", "test")
                                .headersFor("GET", endpoint, new byte[0]))
                        .header("Host", endpoint.getAuthority())
                        .when().get("/")
                        .then().statusCode(200)
                        .extract().xmlPath().getList("ListAllMyBucketsResult.Buckets.Bucket.Name", String.class);
                assertTrue(buckets.contains(bucket));

                URI virtualHost = URI.create("http://" + bucket + "." + hostname + ":" + baseUri.getPort() + "/");
                given().headers(S3RequestSigner.signedAs("test", "test")
                                .headersFor("GET", virtualHost, new byte[0]))
                        .header("Host", virtualHost.getAuthority())
                        .when().get("/")
                        .then().statusCode(200)
                        .body("ListBucketResult.Name", is(bucket));
            } finally {
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
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
