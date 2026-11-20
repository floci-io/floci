package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsJsonCborServiceRoutingIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final Set<String> JSON_1_0_SERVICES = Set.of(
            "dynamodb", "sqs", "sns", "states", "swf", "monitoring", "paymentcryptography",
            "cloudcontrol", "network-firewall", "verifiedpermissions", "bcm-pricing-calculator",
            "timestream-influxdb");
    private static final Map<String, Operation> OPERATIONS = Map.ofEntries(
            operation("ssm", "DescribeParameters"),
            operation("sqs", "ListQueues"),
            operation("dynamodb", "ListTables"),
            operation("sns", "ListTopics"),
            operation("memorydb", "DescribeClusters"),
            operation("redshift-data", "ListStatements"),
            operation("redshift-serverless", "ListNamespaces"),
            operation("events", "ListRules", "{\"EventBusName\":\"default\"}"),
            operation("servicediscovery", "ListNamespaces"),
            operation("elasticmapreduce", "ListClusters"),
            operation("wafv2", "ListWebACLs", "{\"Scope\":\"REGIONAL\",\"Limit\":10}"),
            operation("logs", "DescribeLogGroups"),
            operation("monitoring", "ListMetrics"),
            operation("paymentcryptography", "ListKeys"),
            operation("secretsmanager", "ListSecrets"),
            operation("apigatewayv2", "GetApis"),
            operation("kinesis", "ListStreams"),
            operation("kinesisanalytics", "ListApplications"),
            operation("kms", "ListKeys"),
            operation("cognito-idp", "ListUserPools", "{\"MaxResults\":10}"),
            operation("states", "ListStateMachines"),
            operation("swf", "ListDomains", "{\"registrationStatus\":\"REGISTERED\"}"),
            operation("acm", "ListCertificates"),
            operation("athena", "ListWorkGroups"),
            operation("glue", "GetDatabases"),
            operation("firehose", "ListDeliveryStreams"),
            operation("ecs", "ListClusters"),
            operation("ecr", "DescribeRepositories"),
            operation("tagging", "GetResources"),
            operation("codebuild", "ListProjects"),
            operation("sagemaker", "ListModels"),
            operation("codedeploy", "ListApplications"),
            operation("codepipeline", "ListPipelines"),
            operation("servicequotas", "ListServices"),
            operation("config", "DescribeConfigRules"),
            operation("cloudtrail", "DescribeTrails"),
            operation("lightsail", "GetInstances"),
            operation("cloudcontrol", "ListResources", "{\"TypeName\":\"AWS::S3::Bucket\"}"),
            Map.entry("sso", new Operation("DescribeInstance", "{}", 400)),
            operation("identitystore", "ListUsers", "{\"IdentityStoreId\":\"d-9067f2a3c1\"}"),
            operation("budgets", "DescribeBudgets", "{\"AccountId\":\"000000000000\"}"),
            operation("application-autoscaling", "DescribeScalableTargets", "{\"ServiceNamespace\":\"ecs\"}"),
            operation("ec2messages", "GetEndpoint"),
            Map.entry("ec2-instance-connect", new Operation("SendSSHPublicKey", "{}", 400)),
            operation("transfer", "ListServers"),
            Map.entry("textract", new Operation("GetDocumentTextDetection", "{}", 400)),
            Map.entry("comprehend", new Operation("DetectSentiment", "{}", 400)),
            operation("rekognition", "ListCollections"),
            operation("pricing", "DescribeServices"),
            operation("transcribe", "ListTranscriptionJobs"),
            operation("translate", "ListLanguages"),
            operation("ce", "GetAnomalyMonitors"),
            operation("cur", "DescribeReportDefinitions"),
            Map.entry("bcm-pricing-calculator", new Operation("CreateWorkloadEstimate", "{}", 400)),
            operation("timestream-influxdb", "ListDbInstances"),
            operation("bcm-data-exports", "ListExports"),
            operation("cloudhsmv2", "DescribeClusters"),
            Map.entry("organizations", new Operation("DescribeOrganization", "{}", 400)),
            operation("route53resolver", "ListResolverEndpoints"),
            operation("cognito-identity", "ListIdentityPools", "{\"MaxResults\":10}"),
            operation("globalaccelerator", "ListAccelerators"),
            operation("datasync", "ListAgents"),
            operation("network-firewall", "ListFirewalls"),
            operation("servicecatalog", "ListPortfolios"),
            operation("verifiedpermissions", "ListPolicyStores", "{\"maxResults\":10}"),
            operation("marketplace", "GetEntitlements", "{\"ProductCode\":\"cbor-routing-test\"}"),
            operation("dms", "DescribeReplicationInstances"));

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyTargetRoutedJsonServiceHasARegressionCase() {
        Set<String> services = catalog.all().stream()
                .filter(descriptor -> descriptor.supportsProtocol(ServiceProtocol.JSON))
                .filter(descriptor -> !descriptor.targetPrefixes().isEmpty())
                .map(ServiceDescriptor::externalKey)
                .collect(Collectors.toSet());

        assertEquals(services, OPERATIONS.keySet());
    }

    Stream<String> jsonServices() {
        return OPERATIONS.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0} via rpc-v2-cbor")
    @MethodSource("jsonServices")
    void cborReturnsTheSameServiceResponseAsJson(String service) throws Exception {
        ServiceDescriptor descriptor = catalog.byExternalKey(service).orElseThrow();
        Operation operation = OPERATIONS.get(service);
        String prefix = targetPrefix(descriptor);
        String target = prefix + operation.action();
        String jsonContentType = JSON_1_0_SERVICES.contains(service)
                ? "application/x-amz-json-1.0" : "application/x-amz-json-1.1";
        Response json = given()
                .contentType(jsonContentType)
                .header("X-Amz-Target", target)
                .body(operation.request().getBytes(StandardCharsets.UTF_8))
                .when().post("/");
        assertEquals(operation.status(), json.statusCode(), service + " JSON baseline: " + json.asString());
        JsonNode jsonBody = JSON.readTree(json.asByteArray());
        assertFalse(jsonBody.isEmpty(), service + " must return a service-specific response");

        String path = "/service/" + prefix.substring(0, prefix.length() - 1)
                + "/operation/" + operation.action();
        Response cbor = given()
                .contentType("application/cbor")
                .accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR.writeValueAsBytes(JSON.readTree(operation.request())))
                .when().post(path);

        assertEquals(json.statusCode(), cbor.statusCode(), service + " RPC v2 must reach its JSON handler");
        assertEquals("application/cbor", cbor.contentType());
        assertEquals(jsonBody, CBOR.readTree(cbor.asByteArray()), service + " RPC v2 response");
    }

    @ParameterizedTest(name = "{0} reaches its Marketplace handler")
    @CsvSource({
            "AWSMPCommerceService_v20200301, SearchAgreements, application/x-amz-json-1.0",
            "AWSMPMeteringService, ResolveCustomer, application/x-amz-json-1.1"
    })
    void marketplacePrefixesReturnTheirOwnServiceErrors(String serviceId, String action, String contentType)
            throws Exception {
        Response json = given()
                .contentType(contentType)
                .header("X-Amz-Target", serviceId + "." + action)
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .when().post("/");
        assertEquals(400, json.statusCode(), "Marketplace JSON baseline: " + json.asString());
        JsonNode jsonBody = JSON.readTree(json.asByteArray());
        assertFalse(jsonBody.path("__type").asText().contains("UnknownOperation"));

        Response cbor = given()
                .contentType("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR.writeValueAsBytes(Map.of()))
                .when().post("/service/" + serviceId + "/operation/" + action);

        assertEquals(json.statusCode(), cbor.statusCode());
        assertEquals(jsonBody, CBOR.readTree(cbor.asByteArray()), serviceId + " must select its own handler");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "42", "\"scalar\""})
    void genericCborServiceRejectsNonStructureInputs(String payload) throws Exception {
        Response response = given()
                .contentType("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR.writeValueAsBytes(JSON.readTree(payload)))
                .when().post("/service/CodeBuild_20161006/operation/ListProjects");

        assertEquals(400, response.statusCode(), "CodeBuild input must be a structure: " + payload);
        assertEquals("SerializationException", CBOR.readTree(response.asByteArray()).path("__type").asText());
    }

    @Test
    void unknownRpcV2ServiceStillReturnsAnAwsError() throws Exception {
        Response response = given()
                .contentType("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR.writeValueAsBytes(Map.of()))
                .when().post("/service/UnknownCborService/operation/ListThings");

        assertEquals(404, response.statusCode());
        assertEquals("UnknownOperationException", CBOR.readTree(response.asByteArray()).path("__type").asText());
    }

    private static String targetPrefix(ServiceDescriptor descriptor) {
        if ("dynamodb".equals(descriptor.externalKey())) {
            return "DynamoDB_20120810.";
        }
        if ("marketplace".equals(descriptor.externalKey())) {
            return "AWSMPEntitlementService.";
        }
        return descriptor.targetPrefixes().stream()
                .filter(prefix -> !descriptor.jsonOnlyTargetPrefixes().contains(prefix))
                .min(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()))
                .orElseThrow();
    }

    private static Map.Entry<String, Operation> operation(String service, String action) {
        return operation(service, action, "{}");
    }

    private static Map.Entry<String, Operation> operation(String service, String action, String request) {
        return Map.entry(service, new Operation(action, request, 200));
    }

    private record Operation(String action, String request, int status) {
    }

}
