package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            "accessanalyzer", "acm", "amazonmq", "apigateway", "apigatewayv2", "applicationautoscaling", "appsync",
            "aps", "autoscaling", "backup", "batch", "bcmdataexports", "bedrock", "bedrockagentcorecontrol", "ce",
            "cloudfront", "cloudmap", "cloudwatch", "codebuild", "codedeploy", "codepipeline", "cognito",
            "configservice", "connect", "controlcatalog", "controltower", "datasync", "dlm", "dms", "dsql", "dynamodb", "ec2",
            "ecr", "ecs", "efs", "eks", "elasticache", "elasticbeanstalk", "elb", "elbv2", "emr", "emrserverless",
            "eventbridge", "firehose", "fis", "glue", "guardduty", "iot", "kinesis", "kinesisanalytics", "lambda",
            "lambdamicrovms", "lambdaweb", "memorydb", "msk", "mwaa", "networkfirewall", "oam", "opensearch", "organizations",
            "pipes", "ram", "rds", "redshift", "redshiftserverless", "resourceexplorer2", "resourcegroups", "route53resolver",
            "s3tables", "s3vectors", "sagemaker", "scheduler", "secretsmanager", "servicequotas", "ses", "sns", "sqs",
            "stepfunctions", "timestreaminfluxdb", "transfer", "verifiedpermissions", "wafv2");

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String JSON = "application/json";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void resourceGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "resource-groups");
        String name = unique("smoke-pool", partitionCase);
        String body = "{\"Name\":\"" + name + "\",\"Configuration\":["
                + "{\"Type\":\"AWS::EC2::CapacityReservationPool\"},"
                + "{\"Type\":\"AWS::ResourceGroups::Generic\",\"Parameters\":["
                + "{\"Name\":\"allowed-resource-types\",\"Values\":[\"AWS::EC2::CapacityReservation\"]}]}]}";
        String arn = signed(region, "resource-groups").contentType(JSON).body(body)
                .when().post("/groups").then().statusCode(200).extract().path("Group.GroupArn");
        cleanup.register(() -> signed(region, "resource-groups").contentType(JSON)
                .body("{\"Group\":\"" + arn + "\"}").when().post("/delete-group"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elbClassicLoadBalancerArnInAcmInUseBy(PartitionCase partitionCase) {
        String region = offered(partitionCase, "acm");
        offered(partitionCase, "elasticloadbalancing");
        String certificateArn = json11(region, "acm", "CertificateManager.RequestCertificate",
                "{\"DomainName\":\"" + unique("smoke-elb", partitionCase) + ".example.com\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("CertificateArn");
        cleanup.register(() -> json11(region, "acm", "CertificateManager.DeleteCertificate",
                "{\"CertificateArn\":\"" + certificateArn + "\"}").when().post("/"));
        String name = unique("elb", partitionCase);
        signed(region, "elasticloadbalancing")
            .formParam("Action", "CreateLoadBalancer")
            .formParam("Version", "2012-06-01")
            .formParam("LoadBalancerName", name)
            .formParam("AvailabilityZones.member.1", region + "a")
            .formParam("Listeners.member.1.Protocol", "HTTPS")
            .formParam("Listeners.member.1.LoadBalancerPort", "443")
            .formParam("Listeners.member.1.InstancePort", "8080")
            .formParam("Listeners.member.1.SSLCertificateId", certificateArn)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "elasticloadbalancing")
            .formParam("Action", "DeleteLoadBalancer")
            .formParam("Version", "2012-06-01")
            .formParam("LoadBalancerName", name)
        .when().post("/"));
        String arn = json11(region, "acm", "CertificateManager.DescribeCertificate",
                "{\"CertificateArn\":\"" + certificateArn + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Certificate.InUseBy[0]");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
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

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void sqsQueueArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sqs");
        String name = unique("smoke-queue", partitionCase);
        String queueUrl = json10(region, "sqs", "AmazonSQS.CreateQueue", "{\"QueueName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");
        cleanup.register(() -> json10(region, "sqs", "AmazonSQS.DeleteQueue",
                "{\"QueueUrl\":\"" + queueUrl + "\"}").when().post("/"));
        String arn = json10(region, "sqs", "AmazonSQS.GetQueueAttributes",
                "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Attributes.QueueArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void secretsManagerSecretArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "secretsmanager");
        String name = unique("smoke-secret", partitionCase);
        String arn = json11(region, "secretsmanager", "secretsmanager.CreateSecret",
                "{\"Name\":\"" + name + "\",\"SecretString\":\"smoke\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ARN");
        cleanup.register(() -> json11(region, "secretsmanager", "secretsmanager.DeleteSecret",
                "{\"SecretId\":\"" + arn + "\",\"ForceDeleteWithoutRecovery\":true}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dynamoDbTableArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dynamodb");
        String name = unique("smoke-table", partitionCase);
        String body = """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                  "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(name);
        String arn = json10(region, "dynamodb", "DynamoDB_20120810.CreateTable", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("TableDescription.TableArn");
        cleanup.register(() -> json10(region, "dynamodb", "DynamoDB_20120810.DeleteTable",
                "{\"TableName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchAlarmArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "monitoring");
        String name = unique("smoke-alarm", partitionCase);
        signed(region, "monitoring")
            .formParam("Action", "PutMetricAlarm")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmName", name)
            .formParam("Namespace", "Smoke")
            .formParam("MetricName", "Requests")
            .formParam("Statistic", "Sum")
            .formParam("Period", "60")
            .formParam("EvaluationPeriods", "1")
            .formParam("Threshold", "1")
            .formParam("ComparisonOperator", "GreaterThanThreshold")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "monitoring")
            .formParam("Action", "DeleteAlarms")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmNames.member.1", name)
        .when().post("/"));
        String arn = signed(region, "monitoring")
            .formParam("Action", "DescribeAlarms")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmNames.member.1", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("DescribeAlarmsResponse.DescribeAlarmsResult.MetricAlarms.member.AlarmArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchDashboardArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "monitoring");
        String name = unique("smoke-dashboard", partitionCase);
        signed(region, "monitoring")
            .formParam("Action", "PutDashboard")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardName", name)
            .formParam("DashboardBody", "{\"widgets\":[]}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "monitoring")
            .formParam("Action", "DeleteDashboards")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardNames.member.1", name)
        .when().post("/"));
        String arn = signed(region, "monitoring")
            .formParam("Action", "GetDashboard")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("GetDashboardResponse.GetDashboardResult.DashboardArn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchLogGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "logs");
        String name = unique("smoke-log-group", partitionCase);
        json11(region, "logs", "Logs_20140328.CreateLogGroup", "{\"logGroupName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "logs", "Logs_20140328.DeleteLogGroup",
                "{\"logGroupName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "logs", "Logs_20140328.DescribeLogGroups",
                "{\"logGroupNamePrefix\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("logGroups[0].arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void eventBridgeEventBusArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "events");
        String name = unique("smoke-bus", partitionCase);
        String arn = json11(region, "events", "AWSEvents.CreateEventBus", "{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("EventBusArn");
        cleanup.register(() -> json11(region, "events", "AWSEvents.DeleteEventBus",
                "{\"Name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ecsClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ecs");
        String name = unique("smoke-cluster", partitionCase);
        String arn = json11(region, "ecs", "AmazonEC2ContainerServiceV20141113.CreateCluster",
                "{\"clusterName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("cluster.clusterArn");
        cleanup.register(() -> json11(region, "ecs", "AmazonEC2ContainerServiceV20141113.DeleteCluster",
                "{\"cluster\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void rdsParameterGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "rds");
        String name = unique("smoke-pg", partitionCase);
        String arn = signed(region, "rds")
            .formParam("Action", "CreateDBParameterGroup")
            .formParam("Version", "2014-10-31")
            .formParam("DBParameterGroupName", name)
            .formParam("DBParameterGroupFamily", "postgres16")
            .formParam("Description", "smoke")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateDBParameterGroupResponse.CreateDBParameterGroupResult.DBParameterGroup.DBParameterGroupArn");
        cleanup.register(() -> signed(region, "rds")
            .formParam("Action", "DeleteDBParameterGroup")
            .formParam("Version", "2014-10-31")
            .formParam("DBParameterGroupName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ec2IpamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ec2");
        Response created = signed(region, "ec2")
            .formParam("Action", "CreateIpam")
            .formParam("Version", "2016-11-15")
        .when().post("/").then().statusCode(200).extract().response();
        String ipamId = created.xmlPath().getString("CreateIpamResponse.ipam.ipamId");
        cleanup.register(() -> signed(region, "ec2")
            .formParam("Action", "DeleteIpam")
            .formParam("Version", "2016-11-15")
            .formParam("IpamId", ipamId)
            .formParam("Cascade", "true")
        .when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.xmlPath().getString("CreateIpamResponse.ipam.ipamArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void apiGatewayDomainNameArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "apigateway");
        String domain = unique("smoke-domain", partitionCase) + ".example.com";
        String body = """
                {
                  "domainName": "%s",
                  "endpointConfiguration": {"types": ["REGIONAL"]},
                  "regionalCertificateArn": "arn:%s:acm:%s:000000000000:certificate/smoke"
                }
                """.formatted(domain, partitionCase.partition(), region);
        String arn = signed(region, "apigateway")
            .contentType(JSON)
            .body(body)
        .when().post("/domainnames").then().statusCode(201)
            .extract().jsonPath().getString("domainNameArn");
        cleanup.register(() -> signed(region, "apigateway").when().delete("/domainnames/" + domain));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void sesTenantArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ses");
        String name = unique("smoke-tenant", partitionCase);
        String arn = signed(region, "ses")
            .contentType(JSON)
            .body("{\"TenantName\":\"" + name + "\"}")
        .when().post("/v2/email/tenants").then().statusCode(200)
            .extract().jsonPath().getString("TenantArn");
        cleanup.register(() -> signed(region, "ses")
            .contentType(JSON)
            .body("{\"TenantName\":\"" + name + "\"}")
        .when().post("/v2/email/tenants/delete"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void eksClusterArnAndOidcIssuerHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "eks");
        String name = unique("smoke-eks", partitionCase);
        Response created = signed(region, "eks")
            .contentType(JSON)
            .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:" + partitionCase.partition()
                    + ":iam::000000000000:role/eks-role\"}")
        .when().post("/clusters").then().statusCode(200).extract().response();
        cleanup.register(() -> signed(region, "eks").when().delete("/clusters/" + name));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("cluster.arn"));
        String issuer = signed(region, "eks").when().get("/clusters/" + name).then().statusCode(200)
            .extract().jsonPath().getString("cluster.identity.oidc.issuer");
        assertRegionalHostIn(partitionCase, URI.create(issuer).getHost());
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dsqlClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dsql");
        Response created = signed(region, "dsql")
            .contentType(JSON)
            .body("{\"deletionProtectionEnabled\":false}")
        .when().post("/cluster").then().statusCode(200).extract().response();
        String identifier = created.jsonPath().getString("identifier");
        cleanup.register(() -> signed(region, "dsql").when().delete("/cluster/" + identifier));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cognitoUserPoolArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "cognito-idp");
        String name = unique("smoke-pool", partitionCase);
        Response created = json11(region, "cognito-idp", "AWSCognitoIdentityProviderService.CreateUserPool",
                "{\"PoolName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200).extract().response();
        String poolId = created.jsonPath().getString("UserPool.Id");
        cleanup.register(() -> json11(region, "cognito-idp", "AWSCognitoIdentityProviderService.DeleteUserPool",
                "{\"UserPoolId\":\"" + poolId + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("UserPool.Arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void appSyncGraphqlApiArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "appsync");
        Response created = signed(region, "appsync")
            .contentType(JSON)
            .body("{\"name\":\"" + unique("smoke-api", partitionCase) + "\",\"authenticationType\":\"API_KEY\"}")
        .when().post("/v1/apis").then().statusCode(200).extract().response();
        String apiId = created.jsonPath().getString("graphqlApi.apiId");
        cleanup.register(() -> signed(region, "appsync").when().delete("/v1/apis/" + apiId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("graphqlApi.arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void openSearchDomainArnAndEndpointHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "es");
        String name = "smoke" + Long.toString(System.nanoTime(), 36);
        Response created = signed(region, "es")
            .contentType(JSON)
            .body("{\"DomainName\":\"" + name + "\"}")
        .when().post("/2021-01-01/opensearch/domain").then().statusCode(200).extract().response();
        cleanup.register(() -> signed(region, "es").when().delete("/2021-01-01/opensearch/domain/" + name));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("DomainStatus.ARN"));
        String endpoint = signed(region, "es").when().get("/2021-01-01/opensearch/domain/" + name)
            .then().statusCode(200).extract().jsonPath().getString("DomainStatus.Endpoint");
        assertRegionalHostIn(partitionCase, endpoint);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void amazonMqBrokerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "mq");
        String body = """
                {"brokerName": "%s", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false,
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """.formatted(unique("smoke-broker", partitionCase));
        Response created = signed(region, "mq")
            .contentType(JSON)
            .body(body)
        .when().post("/v1/brokers").then().statusCode(200).extract().response();
        String brokerId = created.jsonPath().getString("brokerId");
        cleanup.register(() -> signed(region, "mq").when().delete("/v1/brokers/" + brokerId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("brokerArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mskClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kafka");
        String arn = signed(region, "kafka")
            .contentType(JSON)
            .body("{\"clusterName\":\"" + unique("smoke-msk", partitionCase) + "\",\"kafkaVersion\":\"3.6.0\"}")
        .when().post("/v1/clusters").then().statusCode(200)
            .extract().jsonPath().getString("clusterArn");
        cleanup.register(() -> signed(region, "kafka").when().delete("/v1/clusters/{arn}", arn));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elastiCacheParameterGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticache");
        String name = unique("smoke-cpg", partitionCase);
        String arn = signed(region, "elasticache")
            .formParam("Action", "CreateCacheParameterGroup")
            .formParam("Version", "2015-02-02")
            .formParam("CacheParameterGroupName", name)
            .formParam("CacheParameterGroupFamily", "redis7")
            .formParam("Description", "smoke")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateCacheParameterGroupResponse.CreateCacheParameterGroupResult.CacheParameterGroup.ARN");
        cleanup.register(() -> signed(region, "elasticache")
            .formParam("Action", "DeleteCacheParameterGroup")
            .formParam("Version", "2015-02-02")
            .formParam("CacheParameterGroupName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void memoryDbAclArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "memorydb");
        String name = unique("smoke-acl", partitionCase);
        String arn = json11(region, "memorydb", "AmazonMemoryDB.CreateACL",
                "{\"ACLName\":\"" + name + "\",\"UserNames\":[\"default\"]}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ACL.ARN");
        cleanup.register(() -> json11(region, "memorydb", "AmazonMemoryDB.DeleteACL",
                "{\"ACLName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void route53ResolverFirewallDomainListArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "route53resolver");
        String name = unique("smoke-domains", partitionCase);
        Response created = json11(region, "route53resolver", "Route53Resolver.CreateFirewallDomainList",
                "{\"CreatorRequestId\":\"" + name + "\",\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200).extract().response();
        String listId = created.jsonPath().getString("FirewallDomainList.Id");
        cleanup.register(() -> json11(region, "route53resolver", "Route53Resolver.DeleteFirewallDomainList",
                "{\"FirewallDomainListId\":\"" + listId + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("FirewallDomainList.Arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bedrockAgentCoreGatewayArnAndUrlHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bedrock-agentcore");
        String body = """
                {"name": "%s", "authorizerType": "AWS_IAM",
                 "roleArn": "arn:%s:iam::000000000000:role/gw"}
                """.formatted("smoke" + Long.toString(System.nanoTime(), 36), partitionCase.partition());
        Response created = signed(region, "bedrock-agentcore")
            .contentType(JSON)
            .body(body)
        .when().post("/gateways/").then().statusCode(202).extract().response();
        String gatewayId = created.jsonPath().getString("gatewayId");
        cleanup.register(() -> signed(region, "bedrock-agentcore").when().delete("/gateways/" + gatewayId + "/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("gatewayArn"));
        assertRegionalHostIn(partitionCase, URI.create(created.jsonPath().getString("gatewayUrl")).getHost());
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudFrontFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "cloudfront");
        String name = unique("smoke-function", partitionCase);
        String code = Base64.getEncoder().encodeToString(
                "function handler(event) { return event.request; }".getBytes(StandardCharsets.UTF_8));
        String body = """
                <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name>
                  <FunctionConfig><Comment>smoke</Comment><Runtime>cloudfront-js-2.0</Runtime></FunctionConfig>
                  <FunctionCode>%s</FunctionCode>
                </CreateFunctionRequest>
                """.formatted(name, code);
        Response created = signed(region, "cloudfront")
            .contentType("application/xml")
            .body(body)
        .when().post("/2020-05-31/function").then().statusCode(201).extract().response();
        String etag = created.header("ETag");
        cleanup.register(() -> signed(region, "cloudfront")
            .header("If-Match", etag)
        .when().delete("/2020-05-31/function/" + name));
        PartitionMatrix.assertGlobalArnIn(partitionCase,
                created.xmlPath().getString("FunctionSummary.FunctionMetadata.FunctionARN"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ramResourceShareArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ram");
        String arn = signed(region, "ram")
            .contentType(JSON)
            .body("{\"name\":\"" + unique("smoke-share", partitionCase) + "\"}")
        .when().post("/createresourceshare").then().statusCode(200)
            .extract().jsonPath().getString("resourceShare.resourceShareArn");
        cleanup.register(() -> signed(region, "ram")
            .queryParam("resourceShareArn", arn)
        .when().delete("/deleteresourceshare"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void oamSinkArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "oam");
        String arn = signed(region, "oam")
            .contentType(JSON)
            .body("{\"Name\":\"" + unique("smoke-sink", partitionCase) + "\"}")
        .when().post("/CreateSink").then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "oam")
            .contentType(JSON)
            .body("{\"Identifier\":\"" + arn + "\"}")
        .when().post("/DeleteSink"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void schedulerScheduleGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "scheduler");
        String name = unique("smoke-group", partitionCase);
        String arn = signed(region, "scheduler")
            .contentType(JSON)
            .body("{}")
        .when().post("/schedule-groups/" + name).then().statusCode(200)
            .extract().jsonPath().getString("ScheduleGroupArn");
        cleanup.register(() -> signed(region, "scheduler").when().delete("/schedule-groups/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void backupVaultArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "backup");
        String name = unique("smoke-vault", partitionCase);
        String arn = signed(region, "backup")
            .contentType(JSON)
            .body("{}")
        .when().put("/backup-vaults/" + name).then().statusCode(200)
            .extract().jsonPath().getString("BackupVaultArn");
        cleanup.register(() -> signed(region, "backup").when().delete("/backup-vaults/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void wafv2RegionalIpSetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "wafv2");
        String name = unique("smoke-ipset", partitionCase);
        Response created = json11(region, "wafv2", "AWSWAF_20190729.CreateIPSet",
                "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\",\"Addresses\":[]}")
        .when().post("/").then().statusCode(200).extract().response();
        String id = created.jsonPath().getString("Summary.Id");
        String lockToken = created.jsonPath().getString("Summary.LockToken");
        cleanup.register(() -> json11(region, "wafv2", "AWSWAF_20190729.DeleteIPSet",
                "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id
                        + "\",\"LockToken\":\"" + lockToken + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("Summary.ARN"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void s3TablesTableBucketArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "s3tables");
        String name = "smoke-" + Long.toString(System.nanoTime(), 36);
        String arn = signed(region, "s3tables")
            .contentType(JSON)
            .body("{\"name\":\"" + name + "\"}")
        .when().put("/buckets").then().statusCode(200)
            .extract().jsonPath().getString("arn");
        cleanup.register(() -> signed(region, "s3tables").when().delete("/buckets/{arn}", arn));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void guardDutyDetectorServiceRoleArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "guardduty");
        String detectorId = signed(region, "guardduty")
            .contentType(JSON)
            .body("{\"enable\":true}")
        .when().post("/detector").then().statusCode(200)
            .extract().jsonPath().getString("detectorId");
        cleanup.register(() -> signed(region, "guardduty").when().delete("/detector/" + detectorId));
        String serviceRole = signed(region, "guardduty").when().get("/detector/" + detectorId)
            .then().statusCode(200).extract().jsonPath().getString("serviceRole");
        PartitionMatrix.assertGlobalArnIn(partitionCase, serviceRole);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void costExplorerAnomalyMonitorArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ce");
        String body = """
                {"AnomalyMonitor": {"MonitorName": "%s", "MonitorType": "DIMENSIONAL",
                                    "MonitorDimension": "SERVICE"}}
                """.formatted(unique("smoke-monitor", partitionCase));
        String arn = json11(region, "ce", "AWSInsightsIndexService.CreateAnomalyMonitor", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("MonitorArn");
        cleanup.register(() -> json11(region, "ce", "AWSInsightsIndexService.DeleteAnomalyMonitor",
                "{\"MonitorArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void autoScalingLaunchConfigurationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "autoscaling");
        String name = unique("smoke-lc", partitionCase);
        signed(region, "autoscaling")
            .formParam("Action", "CreateLaunchConfiguration")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationName", name)
            .formParam("ImageId", "ami-12345678")
            .formParam("InstanceType", "t3.micro")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "autoscaling")
            .formParam("Action", "DeleteLaunchConfiguration")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationName", name)
        .when().post("/"));
        String arn = signed(region, "autoscaling")
            .formParam("Action", "DescribeLaunchConfigurations")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationNames.member.1", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                    + ".LaunchConfigurations.member.LaunchConfigurationARN");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void applicationAutoScalingScalableTargetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "application-autoscaling");
        String resourceId = "table/" + unique("smoke-table", partitionCase);
        String target = """
                "ServiceNamespace": "dynamodb", "ResourceId": "%s",
                "ScalableDimension": "dynamodb:table:ReadCapacityUnits"
                """.formatted(resourceId);
        String arn = json11(region, "application-autoscaling", "AnyScaleFrontendService.RegisterScalableTarget",
                "{" + target + ", \"MinCapacity\": 1, \"MaxCapacity\": 5}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ScalableTargetARN");
        cleanup.register(() -> json11(region, "application-autoscaling",
                "AnyScaleFrontendService.DeregisterScalableTarget", "{" + target + "}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codeDeployOnPremisesInstanceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codedeploy");
        String name = unique("smoke-instance", partitionCase);
        json11(region, "codedeploy", "CodeDeploy_20141006.RegisterOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\",\"iamUserArn\":\"arn:" + partitionCase.partition()
                        + ":iam::000000000000:user/codedeploy-agent\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "codedeploy", "CodeDeploy_20141006.DeregisterOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "codedeploy", "CodeDeploy_20141006.GetOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("instanceInfo.instanceArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void configRuleArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "config");
        String name = unique("smoke-rule", partitionCase);
        String body = """
                {"ConfigRule": {"ConfigRuleName": "%s",
                                "Source": {"Owner": "AWS", "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"}}}
                """.formatted(name);
        json11(region, "config", "StarlingDoveService.PutConfigRule", body)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "config", "StarlingDoveService.DeleteConfigRule",
                "{\"ConfigRuleName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "config", "StarlingDoveService.DescribeConfigRules",
                "{\"ConfigRuleNames\":[\"" + name + "\"]}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ConfigRules[0].ConfigRuleArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void controlTowerBaselineArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "controltower");
        String arn = signed(region, "controltower")
            .contentType(JSON)
            .body("{}")
        .when().post("/list-baselines").then().statusCode(200)
            .extract().jsonPath().getString("baselines[0].arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lambdaMicrovmsManagedImageArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lambda");
        String arn = signed(region, "lambda").when().get("/2025-09-09/managed-microvm-images")
            .then().statusCode(200).extract().jsonPath().getString("items[0].imageArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lambdaWebFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lambda");
        String message = signed(region, "lambda").when().get("/2025-03-07/web-functions/smoke-missing-web-function")
            .then().statusCode(404).extract().jsonPath().getString("message");
        String arn = message.substring("The function ".length(), message.indexOf(" does not exist."));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dlmLifecyclePolicyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dlm");
        String body = """
                {"ExecutionRoleArn": "arn:%s:iam::000000000000:role/dlm",
                 "Description": "smoke", "State": "ENABLED",
                 "PolicyDetails": {"PolicyType": "EBS_SNAPSHOT_MANAGEMENT", "ResourceTypes": ["VOLUME"],
                   "TargetTags": [{"Key": "backup", "Value": "true"}],
                   "Schedules": [{"Name": "daily", "CreateRule": {"Interval": 24, "IntervalUnit": "HOURS"},
                                  "RetainRule": {"Count": 7}}]}}
                """.formatted(partitionCase.partition());
        String policyId = signed(region, "dlm")
            .contentType(JSON)
            .body(body)
        .when().post("/policies").then().statusCode(200)
            .extract().jsonPath().getString("PolicyId");
        cleanup.register(() -> signed(region, "dlm").when().delete("/policies/" + policyId));
        String arn = signed(region, "dlm").when().get("/policies/" + policyId)
            .then().statusCode(200).extract().jsonPath().getString("Policy.PolicyArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void networkFirewallRuleGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "network-firewall");
        String name = unique("smoke-rules", partitionCase);
        String arn = json10(region, "network-firewall", "NetworkFirewall_20201112.CreateRuleGroup",
                "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\",\"Capacity\":100,"
                        + "\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("RuleGroupResponse.RuleGroupArn");
        cleanup.register(() -> json10(region, "network-firewall", "NetworkFirewall_20201112.DeleteRuleGroup",
                "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudMapNamespaceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "servicediscovery");
        String target = "Route53AutoNaming_v20170314.";
        String operationId = json11(region, "servicediscovery", target + "CreateHttpNamespace",
                "{\"Name\":\"" + unique("smoke-ns", partitionCase) + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("OperationId");
        String getOperation = "{\"OperationId\":\"" + operationId + "\"}";
        cleanup.register(() -> json11(region, "servicediscovery", target + "DeleteNamespace",
                "{\"Id\":\"" + json11(region, "servicediscovery", target + "GetOperation", getOperation)
                        .when().post("/").jsonPath().getString("Operation.Targets.NAMESPACE") + "\"}")
                .when().post("/"));
        String namespaceId = json11(region, "servicediscovery", target + "GetOperation", getOperation)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Operation.Targets.NAMESPACE");
        String arn = json11(region, "servicediscovery", target + "GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Namespace.Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void accessAnalyzerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "access-analyzer");
        String name = unique("smoke-analyzer", partitionCase);
        String arn = signed(region, "access-analyzer")
            .contentType(JSON)
            .body("{\"analyzerName\":\"" + name + "\",\"type\":\"ACCOUNT\"}")
        .when().put("/analyzer").then().statusCode(200)
            .extract().jsonPath().getString("arn");
        cleanup.register(() -> signed(region, "access-analyzer").when().delete("/analyzer/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void prometheusWorkspaceArnAndEndpointHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "aps");
        Response created = signed(region, "aps")
            .contentType(JSON)
            .body("{\"alias\":\"" + unique("smoke-workspace", partitionCase) + "\"}")
        .when().post("/workspaces").then().statusCode(202).extract().response();
        String workspaceId = created.jsonPath().getString("workspaceId");
        cleanup.register(() -> signed(region, "aps").when().delete("/workspaces/" + workspaceId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
        String endpoint = signed(region, "aps").when().get("/workspaces/" + workspaceId)
            .then().statusCode(200).extract().jsonPath().getString("workspace.prometheusEndpoint");
        assertRegionalHostIn(partitionCase, URI.create(endpoint).getHost());
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bedrockGuardrailArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bedrock");
        String body = """
                {"name": "%s", "blockedInputMessaging": "blocked", "blockedOutputsMessaging": "blocked",
                 "contentPolicyConfig": {"filtersConfig": [
                   {"type": "HATE", "inputStrength": "HIGH", "outputStrength": "HIGH"}]}}
                """.formatted(unique("smoke-guardrail", partitionCase));
        Response created = signed(region, "bedrock")
            .contentType(JSON)
            .body(body)
        .when().post("/guardrails").then().statusCode(202).extract().response();
        String guardrailId = created.jsonPath().getString("guardrailId");
        cleanup.register(() -> signed(region, "bedrock").when().delete("/guardrails/" + guardrailId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("guardrailArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void connectInstanceArnAndServiceRole(PartitionCase partitionCase) {
        String region = offered(partitionCase, "connect");
        String body = """
                {"IdentityManagementType": "CONNECT_MANAGED", "InstanceAlias": "%s",
                 "InboundCallsEnabled": true, "OutboundCallsEnabled": false}
                """.formatted(unique("smoke-connect", partitionCase));
        Response created = signed(region, "connect")
            .contentType(JSON)
            .body(body)
        .when().put("/instance").then().statusCode(200).extract().response();
        String instanceId = created.jsonPath().getString("Id");
        cleanup.register(() -> signed(region, "connect").when().delete("/instance/" + instanceId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("Arn"));
        String serviceRole = signed(region, "connect").when().get("/instance/" + instanceId)
            .then().statusCode(200).extract().jsonPath().getString("Instance.ServiceRole");
        PartitionMatrix.assertGlobalArnIn(partitionCase, serviceRole);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void controlCatalogControlArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "controlcatalog");
        String arn = signed(region, "controlcatalog")
            .contentType(JSON)
            .body("{}")
        .when().post("/list-controls").then().statusCode(200)
            .extract().jsonPath().getString("Controls[0].Arn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dataSyncAgentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "datasync");
        String arn = json11(region, "datasync", "FmrsService.CreateAgent",
                "{\"ActivationKey\":\"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\",\"AgentName\":\""
                        + unique("smoke-agent", partitionCase) + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("AgentArn");
        cleanup.register(() -> json11(region, "datasync", "FmrsService.DeleteAgent",
                "{\"AgentArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void emrClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticmapreduce");
        String body = """
                {"Name": "%s", "ReleaseLabel": "emr-7.5.0",
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceGroups": [
                   {"Name": "master", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge", "InstanceCount": 1}]}}
                """.formatted(unique("smoke-emr", partitionCase));
        Response created = json11(region, "elasticmapreduce", "ElasticMapReduce.RunJobFlow", body)
        .when().post("/").then().statusCode(200).extract().response();
        String clusterId = created.jsonPath().getString("JobFlowId");
        cleanup.register(() -> json11(region, "elasticmapreduce", "ElasticMapReduce.TerminateJobFlows",
                "{\"JobFlowIds\":[\"" + clusterId + "\"]}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("ClusterArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void emrServerlessApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "emr-serverless");
        String body = """
                {"name": "%s", "releaseLabel": "emr-7.0.0", "type": "SPARK", "clientToken": "%s"}
                """.formatted(unique("smoke-app", partitionCase), unique("token", partitionCase));
        Response created = signed(region, "emr-serverless")
            .contentType(JSON)
            .body(body)
        .when().post("/applications").then().statusCode(200).extract().response();
        String applicationId = created.jsonPath().getString("applicationId");
        cleanup.register(() -> signed(region, "emr-serverless").when().delete("/applications/" + applicationId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bcmDataExportsExportArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bcm-data-exports");
        String target = "AWSBillingAndCostManagementDataExports.";
        String body = "{\"Export\":{\"Name\":\"" + unique("smoke-export", partitionCase) + "\","
                + "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"},"
                + "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"smoke-bucket\","
                + "\"S3Prefix\":\"out/\",\"S3Region\":\"" + region + "\",\"S3OutputConfigurations\":{"
                + "\"Format\":\"PARQUET\",\"Compression\":\"PARQUET\",\"OutputType\":\"CUSTOM\","
                + "\"Overwrite\":\"OVERWRITE_REPORT\"}}},\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        String arn = json11(region, "bcm-data-exports", target + "CreateExport", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ExportArn");
        cleanup.register(() -> json11(region, "bcm-data-exports", target + "DeleteExport",
                "{\"ExportArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void fisExperimentTemplateArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "fis");
        Response created = signed(region, "fis")
            .contentType(JSON)
            .body("{\"clientToken\":\"" + unique("smoke-token", partitionCase) + "\",\"description\":\"smoke\","
                    + "\"roleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/fis-role\","
                    + "\"stopConditions\":[{\"source\":\"none\"}],\"targets\":{},"
                    + "\"actions\":{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}}")
        .when().post("/experimentTemplates").then().statusCode(200).extract().response();
        String templateId = created.jsonPath().getString("experimentTemplate.id");
        cleanup.register(() -> signed(region, "fis").when().delete("/experimentTemplates/" + templateId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("experimentTemplate.arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void pipesPipeArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "pipes");
        String name = unique("smoke-pipe", partitionCase);
        String queuePrefix = "arn:" + partitionCase.partition() + ":sqs:" + region + ":000000000000:";
        String arn = signed(region, "pipes")
            .contentType(JSON)
            .body("{\"Source\":\"" + queuePrefix + "smoke-source\",\"Target\":\"" + queuePrefix + "smoke-target\","
                    + "\"RoleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/pipe-role\","
                    + "\"DesiredState\":\"STOPPED\"}")
        .when().post("/v1/pipes/" + name).then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "pipes").when().delete("/v1/pipes/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void s3VectorsVectorBucketArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "s3vectors");
        String body = "{\"vectorBucketName\":\"" + unique("smoke-vectors", partitionCase) + "\"}";
        String arn = signed(region, "s3vectors")
            .contentType(JSON)
            .body(body)
        .when().post("/CreateVectorBucket").then().statusCode(200)
            .extract().jsonPath().getString("vectorBucketArn");
        cleanup.register(() -> signed(region, "s3vectors").contentType(JSON).body(body)
            .when().post("/DeleteVectorBucket"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void serviceQuotasQuotaArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "servicequotas");
        String arn = json11(region, "servicequotas", "ServiceQuotasV20190624.ListServiceQuotas",
                "{\"ServiceCode\":\"codebuild\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Quotas[0].QuotaArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void transferServerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "transfer");
        String serverId = json11(region, "transfer", "TransferService.CreateServer", "{}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ServerId");
        String byId = "{\"ServerId\":\"" + serverId + "\"}";
        cleanup.register(() -> json11(region, "transfer", "TransferService.DeleteServer", byId).when().post("/"));
        String arn = json11(region, "transfer", "TransferService.DescribeServer", byId)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Server.Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void verifiedPermissionsPolicyStoreArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "verifiedpermissions");
        Response created = json10(region, "verifiedpermissions", "VerifiedPermissions.CreatePolicyStore",
                "{\"validationSettings\":{\"mode\":\"OFF\"}}")
        .when().post("/").then().statusCode(200).extract().response();
        String policyStoreId = created.jsonPath().getString("policyStoreId");
        cleanup.register(() -> json10(region, "verifiedpermissions", "VerifiedPermissions.DeletePolicyStore",
                "{\"policyStoreId\":\"" + policyStoreId + "\"}").when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codePipelineWebhookArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codepipeline");
        String name = unique("smoke-webhook", partitionCase);
        String arn = json11(region, "codepipeline", "CodePipeline_20150709.PutWebhook",
                "{\"webhook\":{\"name\":\"" + name + "\"}}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("webhook.arn");
        cleanup.register(() -> json11(region, "codepipeline", "CodePipeline_20150709.DeleteWebhook",
                "{\"name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dmsEndpointArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dms");
        String arn = json11(region, "dms", "AmazonDMSv20160101.CreateEndpoint",
                "{\"EndpointIdentifier\":\"" + unique("smoke-dms", partitionCase)
                        + "\",\"EndpointType\":\"source\",\"EngineName\":\"mysql\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Endpoint.EndpointArn");
        cleanup.register(() -> json11(region, "dms", "AmazonDMSv20160101.DeleteEndpoint",
                "{\"EndpointArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ecrRepositoryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ecr");
        String name = unique("smoke-repo", partitionCase);
        String target = "AmazonEC2ContainerRegistry_V20150921.";
        String arn = json11(region, "ecr", target + "CreateRepository", "{\"repositoryName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("repository.repositoryArn");
        cleanup.register(() -> json11(region, "ecr", target + "DeleteRepository",
                "{\"repositoryName\":\"" + name + "\",\"force\":true}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elasticBeanstalkApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticbeanstalk");
        String name = unique("smoke-app", partitionCase);
        String arn = signed(region, "elasticbeanstalk")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateApplication")
            .formParam("ApplicationName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateApplicationResponse.CreateApplicationResult.Application.ApplicationArn");
        cleanup.register(() -> signed(region, "elasticbeanstalk")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteApplication")
            .formParam("ApplicationName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kinesisAnalyticsApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kinesisanalytics");
        String name = unique("smoke-flink", partitionCase);
        String target = "KinesisAnalytics_20180523.";
        Response created = json11(region, "kinesisanalytics", target + "CreateApplication",
                "{\"ApplicationName\":\"" + name + "\",\"RuntimeEnvironment\":\"FLINK-1_18\","
                        + "\"ServiceExecutionRole\":\"arn:" + partitionCase.partition()
                        + ":iam::000000000000:role/flink-role\"}")
        .when().post("/").then().statusCode(200).extract().response();
        // DeleteApplication matches CreateTimestamp to the second, and the default float extraction rounds it.
        String createTimestamp = created.jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getString("ApplicationDetail.CreateTimestamp");
        cleanup.register(() -> json11(region, "kinesisanalytics", target + "DeleteApplication",
                "{\"ApplicationName\":\"" + name + "\",\"CreateTimestamp\":" + createTimestamp + "}")
            .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("ApplicationDetail.ApplicationARN"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void redshiftServerlessNamespaceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "redshift-serverless");
        String body = "{\"namespaceName\":\"" + unique("smoke-namespace", partitionCase) + "\"}";
        String arn = json11(region, "redshift-serverless", "RedshiftServerless.CreateNamespace", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("namespace.namespaceArn");
        cleanup.register(() -> json11(region, "redshift-serverless", "RedshiftServerless.DeleteNamespace", body)
            .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void resourceExplorerViewArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "resource-explorer-2");
        String arn = signed(region, "resource-explorer-2")
            .contentType(JSON)
            .body("{\"ViewName\":\"" + unique("smoke-view", partitionCase) + "\"}")
        .when().post("/CreateView").then().statusCode(200)
            .extract().jsonPath().getString("View.ViewArn");
        cleanup.register(() -> signed(region, "resource-explorer-2").contentType(JSON)
            .body("{\"ViewArn\":\"" + arn + "\"}").when().post("/DeleteView"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void sageMakerModelArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sagemaker");
        String name = unique("smoke-model", partitionCase);
        String arn = json11(region, "sagemaker", "SageMaker.CreateModel",
                "{\"ModelName\":\"" + name + "\",\"PrimaryContainer\":{\"Image\":\"busybox:stable\"}}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ModelArn");
        cleanup.register(() -> json11(region, "sagemaker", "SageMaker.DeleteModel",
                "{\"ModelName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void timestreamInfluxDbInstanceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "timestream-influxdb");
        String target = "AmazonTimestreamInfluxDB.";
        Response created = json10(region, "timestream-influxdb", target + "CreateDbInstance",
                "{\"name\":\"" + unique("smoke-tsi", partitionCase) + "\",\"password\":\"password123\","
                        + "\"dbInstanceType\":\"db.influx.medium\",\"allocatedStorage\":20,"
                        + "\"vpcSubnetIds\":[\"subnet-abc123\"],\"vpcSecurityGroupIds\":[\"sg-abc123\"]}")
        .when().post("/").then().statusCode(200).extract().response();
        String id = created.jsonPath().getString("id");
        cleanup.register(() -> json10(region, "timestream-influxdb", target + "DeleteDbInstance",
                "{\"identifier\":\"" + id + "\"}").when().post("/"));
        // DeleteDbInstance leaves the auth secret an instance may have created behind.
        cleanup.register(() -> json11(region, "secretsmanager", "secretsmanager.DeleteSecret",
                "{\"SecretId\":\"READONLY-InfluxDB-auth-parameters-" + id
                        + "\",\"ForceDeleteWithoutRecovery\":true}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mwaaEnvironmentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "airflow");
        String name = unique("smoke-airflow", partitionCase);
        String partition = partitionCase.partition();
        String arn = signed(region, "airflow")
            .contentType(JSON)
            .body("{\"ExecutionRoleArn\":\"arn:" + partition + ":iam::000000000000:role/airflow-role\","
                    + "\"SourceBucketArn\":\"arn:" + partition + ":s3:::smoke-dags\",\"DagS3Path\":\"dags\","
                    + "\"NetworkConfiguration\":{\"SubnetIds\":[\"subnet-1\",\"subnet-2\"],"
                    + "\"SecurityGroupIds\":[\"sg-1\"]}}")
        .when().put("/environments/" + name).then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "airflow").when().delete("/environments/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void organizationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "organizations");
        // An account holds one organization, so this case uses an account no other test class signs with.
        String auth = "AWS4-HMAC-SHA256 Credential=000000004272/20260215/" + region
                + "/organizations/aws4_request, SignedHeaders=host;x-amz-date, Signature=abc";
        String target = "AWSOrganizationsV20161128.";
        Response created = given().header("Authorization", auth)
            .header("X-Amz-Target", target + "CreateOrganization")
            .contentType(JSON_1_1)
            .body("{\"FeatureSet\":\"ALL\"}")
        .when().post("/").then().statusCode(200).extract().response();
        cleanup.register(() -> given().header("Authorization", auth)
            .header("X-Amz-Target", target + "DeleteOrganization")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("Organization.Arn"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("Organization.MasterAccountArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void redshiftSnapshotCopyGrantKeyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "redshift");
        String name = unique("smoke-grant", partitionCase);
        String arn = signed(region, "redshift")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("Version", "2012-12-01")
            .formParam("SnapshotCopyGrantName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateSnapshotCopyGrantResponse.CreateSnapshotCopyGrantResult.SnapshotCopyGrant.KmsKeyId");
        cleanup.register(() -> signed(region, "redshift")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("Version", "2012-12-01")
            .formParam("SnapshotCopyGrantName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lambdaFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lambda");
        String name = unique("smoke-fn", partitionCase);
        // By name, before the create: a failure reading the response then leaves nothing behind.
        cleanup.register(() -> signed(region, "lambda").when().delete("/2015-03-31/functions/" + name));
        String arn = signed(region, "lambda")
            .contentType(JSON)
            .body("{\"FunctionName\":\"" + name + "\",\"Runtime\":\"nodejs20.x\",\"Handler\":\"index.handler\","
                    + "\"Role\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/lambda-role\"}")
        .when().post("/2015-03-31/functions").then().statusCode(201)
            .extract().jsonPath().getString("FunctionArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void stepFunctionsStateMachineArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "states");
        String name = unique("smoke-sm", partitionCase);
        String definition = "{\\\"StartAt\\\":\\\"Done\\\","
                + "\\\"States\\\":{\\\"Done\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";
        // By the ARN the name gives, before the create: a failure reading the response leaves nothing behind.
        cleanup.register(() -> json10(region, "states", "AWSStepFunctions.DeleteStateMachine",
                "{\"stateMachineArn\":\"arn:" + partitionCase.partition() + ":states:" + region + ":"
                        + PartitionMatrix.ACCOUNT + ":stateMachine:" + name + "\"}").when().post("/"));
        String arn = json10(region, "states", "AWSStepFunctions.CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + definition + "\","
                        + "\"roleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/sfn-role\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("stateMachineArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    /**
     * The case's region, or the test is skipped where the partition does not publish the service.
     * A service no partition lists (the newer ones {@code endpoints.json} omits) runs everywhere,
     * the same reading strict partition mode applies: silence there is not absence.
     */
    private static String offered(PartitionCase partitionCase, String signingName) {
        assumeTrue(!AwsPartitions.publishesSomewhere(signingName)
                        || AwsPartitions.byId(partitionCase.partition()).offersSigningName(signingName),
                partitionCase.partition() + " does not offer " + signingName);
        return partitionCase.region();
    }

    /** The host is in the case's partition and names the case's region, not another one of that partition. */
    private static void assertRegionalHostIn(PartitionCase partitionCase, String host) {
        PartitionMatrix.assertHostIn(partitionCase, host);
        assertTrue(host.contains("." + partitionCase.region() + "."),
                host + " should name the region " + partitionCase.region());
    }

    private static String unique(String prefix, PartitionCase partitionCase) {
        return prefix + "-" + partitionCase.partition() + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static RequestSpecification signed(String region, String signingName) {
        return given().header("Authorization", PartitionMatrix.sigV4Auth(region, signingName));
    }

    private static RequestSpecification json10(String region, String signingName, String target, String body) {
        return signed(region, signingName)
            .header("X-Amz-Target", target)
            .contentType(JSON_1_0)
            .body(body);
    }

    private static RequestSpecification json11(String region, String signingName, String target, String body) {
        return signed(region, signingName)
            .header("X-Amz-Target", target)
            .contentType(JSON_1_1)
            .body(body);
    }
}
