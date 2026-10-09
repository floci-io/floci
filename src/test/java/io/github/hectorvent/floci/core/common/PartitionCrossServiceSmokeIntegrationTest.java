package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.Set;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One resource per service, created by a request signed in each non-commercial partition, must
 * come back with an ARN (or a host) of that partition and region. The literal gate only catches
 * {@code arn:aws:} written in source; this catches a service that mints through the shared
 * helpers with the wrong region source. A service is skipped only in a partition the published
 * data says does not offer it.
 *
 * <p>{@link #COVERED_PACKAGES} names the service packages this class covers;
 * {@code PartitionSmokeInventoryTest} requires every package that mints ARNs or hosts to be here
 * or in {@code partition/smoke-exemptions.tsv}.
 */
@QuarkusTest
class PartitionCrossServiceSmokeIntegrationTest {

    static final Set<String> COVERED_PACKAGES = Set.of(
            "acm", "apigatewayv2", "batch", "codebuild", "efs", "elbv2", "firehose", "glue", "iot",
            "kinesis", "sns");

    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String JSON = "application/json";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static Stream<PartitionCase> nonCommercialCases() {
        return PartitionMatrix.cases().filter(c -> !"aws".equals(c.partition()));
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void snsTopicArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sns");
        String name = unique("smoke-topic", partitionCase);
        String arn = signed(region, "sns")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> signed(region, "sns")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteTopic")
            .formParam("TopicArn", arn)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void kinesisStreamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kinesis");
        String name = unique("smoke-stream", partitionCase);
        json11(region, "kinesis", "Kinesis_20131202.CreateStream",
                "{\"StreamName\":\"" + name + "\",\"ShardCount\":1}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "kinesis", "Kinesis_20131202.DeleteStream",
                "{\"StreamName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "kinesis", "Kinesis_20131202.DescribeStreamSummary",
                "{\"StreamName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void firehoseDeliveryStreamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "firehose");
        String name = unique("smoke-firehose", partitionCase);
        String arn = json11(region, "firehose", "Firehose_20150804.CreateDeliveryStream",
                "{\"DeliveryStreamName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("DeliveryStreamARN");
        cleanup.register(() -> json11(region, "firehose", "Firehose_20150804.DeleteDeliveryStream",
                "{\"DeliveryStreamName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void httpApiEndpointHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "apigateway");
        String name = unique("smoke-http-api", partitionCase);
        Response created = signed(region, "apigateway")
            .contentType(JSON)
            .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"}")
        .when().post("/v2/apis").then().statusCode(201).extract().response();
        String apiId = created.jsonPath().getString("apiId");
        cleanup.register(() -> signed(region, "apigateway").when().delete("/v2/apis/" + apiId));
        String host = URI.create(created.jsonPath().getString("apiEndpoint")).getHost();
        PartitionMatrix.assertHostIn(partitionCase, host);
        assertEquals(apiId + ".execute-api." + region + "." + partitionCase.dnsSuffix(), host);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void elbv2TargetGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticloadbalancing");
        String name = unique("smoke-tg", partitionCase);
        String arn = signed(region, "elasticloadbalancing")
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", name)
            .formParam("Protocol", "HTTP")
            .formParam("Port", "80")
            .formParam("VpcId", "vpc-00000001")
            .formParam("TargetType", "ip")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> signed(region, "elasticloadbalancing")
            .formParam("Action", "DeleteTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("TargetGroupArn", arn)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void glueSchemaRegistryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "glue");
        String name = unique("smoke-registry", partitionCase);
        String arn = json11(region, "glue", "AWSGlue.CreateRegistry", "{\"RegistryName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("RegistryArn");
        cleanup.register(() -> json11(region, "glue", "AWSGlue.DeleteRegistry",
                "{\"RegistryId\":{\"RegistryName\":\"" + name + "\"}}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void iotThingArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iot");
        String name = unique("smoke-thing", partitionCase);
        String arn = signed(region, "iot")
            .contentType(JSON)
            .body("{}")
        .when().post("/things/" + name).then().statusCode(200)
            .extract().jsonPath().getString("thingArn");
        cleanup.register(() -> signed(region, "iot").when().delete("/things/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void acmCertificateArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "acm");
        String domain = unique("smoke-cert", partitionCase) + ".example.com";
        String arn = json11(region, "acm", "CertificateManager.RequestCertificate",
                "{\"DomainName\":\"" + domain + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("CertificateArn");
        cleanup.register(() -> json11(region, "acm", "CertificateManager.DeleteCertificate",
                "{\"CertificateArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void codeBuildProjectArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codebuild");
        String name = unique("smoke-project", partitionCase);
        String body = """
                {
                  "name": "%s",
                  "source": {"type": "S3", "location": "smoke-bucket/source.zip"},
                  "artifacts": {"type": "NO_ARTIFACTS"},
                  "environment": {"type": "LINUX_CONTAINER", "image": "aws/codebuild/standard:7.0",
                                  "computeType": "BUILD_GENERAL1_SMALL"},
                  "serviceRole": "arn:%s:iam::000000000000:role/codebuild-role"
                }
                """.formatted(name, partitionCase.partition());
        String arn = json11(region, "codebuild", "CodeBuild_20161006.CreateProject", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("project.arn");
        cleanup.register(() -> json11(region, "codebuild", "CodeBuild_20161006.DeleteProject",
                "{\"name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void batchComputeEnvironmentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "batch");
        String name = unique("smoke-ce", partitionCase);
        String arn = signed(region, "batch")
            .contentType(JSON)
            .body("{\"computeEnvironmentName\":\"" + name + "\",\"type\":\"MANAGED\","
                    + "\"computeResources\":{\"type\":\"FARGATE\",\"maxvCpus\":4}}")
        .when().post("/v1/createcomputeenvironment").then().statusCode(200)
            .extract().jsonPath().getString("computeEnvironmentArn");
        cleanup.register(() -> {
            signed(region, "batch")
                .contentType(JSON)
                .body("{\"computeEnvironment\":\"" + name + "\",\"state\":\"DISABLED\"}")
            .when().post("/v1/updatecomputeenvironment");
            signed(region, "batch")
                .contentType(JSON)
                .body("{\"computeEnvironment\":\"" + name + "\"}")
            .when().post("/v1/deletecomputeenvironment");
        });
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("nonCommercialCases")
    void efsFileSystemArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticfilesystem");
        Response created = signed(region, "elasticfilesystem")
            .contentType(JSON)
            .body("{\"CreationToken\":\"" + unique("smoke-efs", partitionCase) + "\"}")
        .when().post("/2015-02-01/file-systems").then().statusCode(201).extract().response();
        String fileSystemId = created.jsonPath().getString("FileSystemId");
        cleanup.register(() -> signed(region, "elasticfilesystem")
            .when().delete("/2015-02-01/file-systems/" + fileSystemId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("FileSystemArn"));
    }

    /** The case's region, or the test is skipped where the partition does not publish the service. */
    private static String offered(PartitionCase partitionCase, String signingName) {
        assumeTrue(AwsPartitions.byId(partitionCase.partition()).offersSigningName(signingName),
                partitionCase.partition() + " does not offer " + signingName);
        return partitionCase.region();
    }

    private static String unique(String prefix, PartitionCase partitionCase) {
        return prefix + "-" + partitionCase.partition() + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static RequestSpecification signed(String region, String signingName) {
        return given().header("Authorization", PartitionMatrix.sigV4Auth(region, signingName));
    }

    private static RequestSpecification json11(String region, String signingName, String target, String body) {
        return signed(region, signingName)
            .header("X-Amz-Target", target)
            .contentType(JSON_1_1)
            .body(body);
    }
}
