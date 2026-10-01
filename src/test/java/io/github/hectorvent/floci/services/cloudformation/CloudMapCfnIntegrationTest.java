package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions an {@code AWS::ServiceDiscovery::PrivateDnsNamespace} with a named and an unnamed
 * {@code AWS::ServiceDiscovery::Service} through a stack, checks Ref and Fn::GetAtt against Cloud
 * Map, that a Number parameter TTL is stored as a number, that Description and TTL update in place,
 * that a later resource failing the update puts those in-place changes back, that renaming the
 * service replaces it, and that deleting the stack removes everything.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudMapCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String CLOUD_MAP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/servicediscovery/aws4_request";
    private static final String CLOUD_MAP_TARGET = "Route53AutoNaming_v20170314.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STACK = "cloudmap-cfn-it";
    private static final String NAMESPACE_NAME =
            "cfn-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".internal";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "NsDescription": {"Type": "String", "Default": "d1"},
            "Ttl": {"Type": "Number", "Default": 60},
            "Fail": {"Type": "String", "Default": "false"}
          },
          "Conditions": {
            "Failing": {"Fn::Equals": [{"Ref": "Fail"}, "true"]}
          },
          "Resources": {
            "Ns": {
              "Type": "AWS::ServiceDiscovery::PrivateDnsNamespace",
              "Properties": {
                "Name": "%s",
                "Vpc": "vpc-0123456789abcdef0",
                "Description": {"Ref": "NsDescription"},
                "Tags": [{"Key": "k1", "Value": "v1"}]
              }
            },
            "Svc": {
              "Type": "AWS::ServiceDiscovery::Service",
              "Properties": {
                "Name": "%s",
                "NamespaceId": {"Fn::GetAtt": ["Ns", "Id"]},
                "Description": "main service",
                "DnsConfig": {
                  "NamespaceId": {"Fn::GetAtt": ["Ns", "Id"]},
                  "RoutingPolicy": "MULTIVALUE",
                  "DnsRecords": [{"Type": "A", "TTL": {"Ref": "Ttl"}}]
                },
                "HealthCheckCustomConfig": {"FailureThreshold": 1}
              }
            },
            "SvcNoName": {
              "Type": "AWS::ServiceDiscovery::Service",
              "Properties": {
                "DnsConfig": {
                  "NamespaceId": {"Fn::GetAtt": ["Ns", "Id"]},
                  "DnsRecords": [{"Type": "A", "TTL": 30}]
                }
              }
            },
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "Condition": "Failing",
              "DependsOn": "Svc",
              "Properties": {"SecretString": "explicit", "GenerateSecretString": {"PasswordLength": 32}}
            }
          },
          "Outputs": {
            "NsRef": {"Value": {"Ref": "Ns"}},
            "NsId": {"Value": {"Fn::GetAtt": ["Ns", "Id"]}},
            "NsArn": {"Value": {"Fn::GetAtt": ["Ns", "Arn"]}},
            "NsHz": {"Value": {"Fn::GetAtt": ["Ns", "HostedZoneId"]}},
            "SvcRef": {"Value": {"Ref": "Svc"}},
            "SvcId": {"Value": {"Fn::GetAtt": ["Svc", "Id"]}},
            "SvcArn": {"Value": {"Fn::GetAtt": ["Svc", "Arn"]}},
            "SvcName": {"Value": {"Fn::GetAtt": ["Svc", "Name"]}},
            "NoNameRef": {"Value": {"Ref": "SvcNoName"}},
            "NoNameName": {"Value": {"Fn::GetAtt": ["SvcNoName", "Name"]}}
          }
        }
        """;

    private static String namespaceId;
    private static String serviceId;
    private static String replacedServiceId;
    private static String noNameServiceId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPublishesIdsAndStoresTheTtlAsANumber() throws Exception {
        cloudFormation("CreateStack", "main", Map.of());
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
        String outputs = describeStacks();

        namespaceId = outputValue(outputs, "NsRef");
        serviceId = outputValue(outputs, "SvcRef");
        noNameServiceId = outputValue(outputs, "NoNameRef");
        assertThat(namespaceId, startsWith("ns-"));
        assertThat(serviceId, startsWith("srv-"));
        assertEquals(namespaceId, outputValue(outputs, "NsId"));
        assertEquals(serviceId, outputValue(outputs, "SvcId"));
        assertThat(outputValue(outputs, "NsArn"), endsWith(":namespace/" + namespaceId));
        assertThat(outputValue(outputs, "SvcArn"), endsWith(":service/" + serviceId));
        String hostedZoneId = MAPPER.readTree(cloudMap("GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
                .then().statusCode(200)
                .extract().asString())
            .path("Namespace").path("Properties").path("DnsProperties").path("HostedZoneId").asText(null);
        assertThat(hostedZoneId, matchesPattern("Z[A-Z0-9]{13}"));
        assertEquals(hostedZoneId, outputValue(outputs, "NsHz"));
        assertEquals("main", outputValue(outputs, "SvcName"));
        assertThat(outputValue(outputs, "NoNameName"), matchesPattern("SvcNoName-[0-9a-f]{12}"));

        List<String> names = listServices(namespaceId).jsonPath().getList("Services.Name");
        assertThat(names, containsInAnyOrder("main", outputValue(outputs, "NoNameName")));
        cloudMap("GetService", "{\"Id\":\"" + serviceId + "\"}")
            .then().statusCode(200)
            .body("Service.DnsConfig.DnsRecords[0].TTL", equalTo(60))
            .body("Service.HealthCheckCustomConfig.FailureThreshold", equalTo(1));
    }

    @Test
    @Order(2)
    void updatingDescriptionAndTtlKeepsTheIds() {
        cloudFormation("UpdateStack", "main", Map.of("NsDescription", "d2", "Ttl", "120"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
        String outputs = describeStacks();

        assertEquals(namespaceId, outputValue(outputs, "NsId"));
        assertEquals(serviceId, outputValue(outputs, "SvcId"));
        cloudMap("GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
            .then().statusCode(200)
            .body("Namespace.Description", equalTo("d2"));
        cloudMap("GetService", "{\"Id\":\"" + serviceId + "\"}")
            .then().statusCode(200)
            .body("Service.DnsConfig.DnsRecords[0].TTL", equalTo(120));
    }

    @Test
    @Order(3)
    void aFailedUpdateRestoresTheInPlaceChanges() {
        cloudFormation("UpdateStack", "main", Map.of("NsDescription", "d3", "Ttl", "90", "Fail", "true"));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
        String outputs = describeStacks();

        assertEquals(namespaceId, outputValue(outputs, "NsId"));
        assertEquals(serviceId, outputValue(outputs, "SvcId"));
        cloudMap("GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
            .then().statusCode(200)
            .body("Namespace.Description", equalTo("d2"));
        cloudMap("GetService", "{\"Id\":\"" + serviceId + "\"}")
            .then().statusCode(200)
            .body("Service.DnsConfig.DnsRecords[0].TTL", equalTo(120));
    }

    @Test
    @Order(4)
    void renamingTheServiceReplacesIt() {
        cloudFormation("UpdateStack", "main2", Map.of("NsDescription", "d2", "Ttl", "120"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
        String outputs = describeStacks();

        replacedServiceId = outputValue(outputs, "SvcId");
        assertNotEquals(serviceId, replacedServiceId);
        assertEquals("main2", outputValue(outputs, "SvcName"));
        cloudMap("GetService", "{\"Id\":\"" + serviceId + "\"}")
            .then().statusCode(404)
            .body("__type", equalTo("ServiceNotFound"));
        List<String> names = listServices(namespaceId).jsonPath().getList("Services.Name");
        assertThat(names, containsInAnyOrder("main2", outputValue(outputs, "NoNameName")));
    }

    @Test
    @Order(5)
    void deletingTheStackRemovesTheNamespaceAndServices() {
        cloudFormation("DeleteStack", "main2", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK);

        cloudMap("GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
            .then().statusCode(404)
            .body("__type", equalTo("NamespaceNotFound"));
        for (String id : List.of(serviceId, replacedServiceId, noNameServiceId)) {
            cloudMap("GetService", "{\"Id\":\"" + id + "\"}")
                .then().statusCode(404)
                .body("__type", equalTo("ServiceNotFound"));
        }
    }

    private static void cloudFormation(String action, String serviceName, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE.formatted(NAMESPACE_NAME, serviceName));
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static Response listServices(String nsId) {
        return cloudMap("ListServices", "{\"Filters\":[{\"Name\":\"NAMESPACE_ID\",\"Values\":[\""
                + nsId + "\"],\"Condition\":\"EQ\"}]}");
    }

    private static Response cloudMap(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", CLOUD_MAP_AUTH)
            .header("X-Amz-Target", CLOUD_MAP_TARGET + action)
            .body(body)
        .when().post("/");
    }
}
