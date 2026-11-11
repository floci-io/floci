package io.floci.aws.services.redshift;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.QueryRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.redshift.model.DeleteIntegrationResponse;
import software.amazon.awssdk.services.redshift.model.DescribeIntegrationsResponse;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftS3EventIntegrationTest {

    private static final String BUCKET = "s3-event-lake";
    private static final String NAMESPACE = "s3-event-ns";

    @Inject
    S3Service s3Service;

    @TestHTTPResource
    URI endpoint;

    private String namespaceArn;
    private boolean created;

    @BeforeEach
    void ensureResources() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (created) {
            return;
        }
        s3Service.createBucket(BUCKET, "us-east-1");
        namespaceArn = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"Secret123","dbName":"dev"}
                """.formatted(NAMESPACE)).then().statusCode(200)
                .extract().path("namespace.namespaceArn");
        created = true;
    }

    @Test
    void aBucketIsIntegratedWithANamespaceDescribedFilteredAndDeleted() {
        String bucketArn = "arn:aws:s3:::" + BUCKET;
        String response = redshift("CreateIntegration", "IntegrationName", "s3-event-one",
                "SourceArn", bucketArn, "TargetArn", namespaceArn)
                .then().statusCode(200)
                .body(containsString("<SourceArn>" + bucketArn + "</SourceArn>"))
                .body(containsString("<SourceType>s3-event-notifications</SourceType>"))
                .body(containsString("<Status>active</Status>"))
                .extract().body().asString();
        String integrationArn = response.substring(response.indexOf("<IntegrationArn>") + "<IntegrationArn>".length(),
                response.indexOf("</IntegrationArn>"));
        try {
            redshift("DescribeIntegrations", "IntegrationArn", integrationArn)
                    .then().statusCode(200)
                    .body(containsString("<SourceType>s3-event-notifications</SourceType>"))
                    .body(containsString("<TargetArn>" + namespaceArn + "</TargetArn>"));
            redshift("DescribeIntegrations", "Filters.DescribeIntegrationsFilter.1.Name", "source-types",
                    "Filters.DescribeIntegrationsFilter.1.Values.Value.1", "s3-event-notifications")
                    .then().statusCode(200).body(containsString(integrationArn));
            redshift("CreateIntegration", "IntegrationName", "s3-event-two",
                    "SourceArn", bucketArn, "TargetArn", namespaceArn)
                    .then().statusCode(400).body(containsString("IntegrationConflictOperationFault"));
        } finally {
            redshift("DeleteIntegration", "IntegrationArn", integrationArn).then().statusCode(200);
        }
        redshift("DescribeIntegrations").then().statusCode(200).body(not(containsString(integrationArn)));
    }

    @Test
    void aBucketThatDoesNotExistIsIntegrationSourceNotFound() {
        redshift("CreateIntegration", "IntegrationName", "s3-event-missing",
                "SourceArn", "arn:aws:s3:::no-such-bucket-here", "TargetArn", namespaceArn)
                .then().statusCode(404).body(containsString("IntegrationSourceNotFoundFault"));
    }

    @Test
    void aBucketNameWithAPeriodIsRefused() {
        redshift("CreateIntegration", "IntegrationName", "s3-event-period",
                "SourceArn", "arn:aws:s3:::lake.bucket", "TargetArn", namespaceArn)
                .then().statusCode(400).body(containsString("InvalidParameterValue"));
    }

    @Test
    void aBucketCreatedWithoutRegionConstraintCanJoinAnIntegrationInDefaultRegion() {
        String bucketName = "s3-event-no-region";
        s3Service.createBucket(bucketName, null);
        String bucketArn = "arn:aws:s3:::" + bucketName;
        String response = redshift("CreateIntegration", "IntegrationName", "s3-event-no-region-int",
                "SourceArn", bucketArn, "TargetArn", namespaceArn)
                .then().statusCode(200)
                .body(containsString("<SourceArn>" + bucketArn + "</SourceArn>"))
                .body(containsString("<Status>active</Status>"))
                .extract().body().asString();
        String integrationArn = response.substring(response.indexOf("<IntegrationArn>") + "<IntegrationArn>".length(),
                response.indexOf("</IntegrationArn>"));
        redshift("DeleteIntegration", "IntegrationArn", integrationArn).then().statusCode(200);
    }

    @Test
    void sdkRejectsNoncanonicalBucketArns() {
        try (RedshiftClient client = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            for (String sourceArn : List.of("arn:aws:s3:us-east-1::" + BUCKET,
                    "arn:aws:s3::000000000000:" + BUCKET, "arn:aws-cn:s3:::" + BUCKET)) {
                RedshiftException error = assertThrows(RedshiftException.class, () -> client.createIntegration(
                        request -> request.integrationName("s3-sdk-alternate").sourceArn(sourceArn)
                                .targetArn(namespaceArn)));
                assertEquals(400, error.statusCode());
                assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
            }
            CreateIntegrationResponse createdIntegration = client.createIntegration(
                    request -> request.integrationName("s3-sdk-roundtrip")
                            .sourceArn("arn:aws:s3:::" + BUCKET).targetArn(namespaceArn));
            assertNotNull(createdIntegration.integrationArn());
            assertEquals("s3-sdk-roundtrip", createdIntegration.integrationName());
            assertEquals("arn:aws:s3:::" + BUCKET, createdIntegration.sourceArn());
            assertEquals(namespaceArn, createdIntegration.targetArn());
            assertEquals("active", createdIntegration.statusAsString());
            DescribeIntegrationsResponse described = client.describeIntegrations(
                    request -> request.integrationArn(createdIntegration.integrationArn()));
            assertEquals(1, described.integrations().size());
            assertEquals(createdIntegration.integrationArn(), described.integrations().getFirst().integrationArn());
            DeleteIntegrationResponse deletedIntegration = client.deleteIntegration(
                    request -> request.integrationArn(createdIntegration.integrationArn()));
            assertEquals(createdIntegration.integrationArn(), deletedIntegration.integrationArn());
            assertEquals("s3-sdk-roundtrip", deletedIntegration.integrationName());
        }
    }

    private static Response redshift(String action, String... params) {
        return QueryRequests.request("test", "redshift", action, params)
                .formParam("Version", "2012-12-01")
                .when().post("/");
    }
}
