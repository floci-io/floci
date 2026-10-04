package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions {@code AWS::ApiGatewayV2::DomainName} and {@code AWS::ApiGatewayV2::ApiMapping} through
 * CloudFormation, with stubbing of unsupported types turned off so a type nothing provisions fails
 * the stack instead of reporting CREATE_COMPLETE. Asserts that {@code Ref} and every
 * {@code Fn::GetAtt} attribute are what the v2 API reports, that a request sent to the custom
 * domain reaches the integration, and that updates behave as on AWS: the domain's configuration and
 * the mapping's key, API and stage change in place with the mapping keeping its id, a new domain
 * name replaces what it names with the displaced domain deleted in the cleanup phase, kept under
 * {@code UpdateReplacePolicy: Retain} and put back when a later resource fails the update.
 *
 * <p>Two more stacks are what {@code aws-cdk-lib} 2.272.0 synthesizes, checked in verbatim with the
 * app that produced them under {@code cloudformation/apigatewayv2-domain/}: an {@code HttpApi} with a
 * {@code defaultDomainMapping}, a keyed mapping and a Route 53 alias, and a REST API on a custom
 * domain under a key with several levels, which CDK maps with {@code AWS::ApiGatewayV2::ApiMapping}.
 */
@QuarkusTest
@TestProfile(CloudFormationUnsupportedResourceTypeStrictIntegrationTest.StrictProfile.class)
class ApiGatewayV2DomainCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/cloudformation/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/route53/aws4_request";
    private static final String ZONE = "apigwv2-domain-cfn-it.example.com";
    private static final String CDK_DOMAIN = "cdk." + ZONE;
    private static final String CDK_REST_DOMAIN = "rest.apigwv2-rest-cfn-it.example.com";
    private static final String BACKEND_BODY = "reached the integration";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The domain, mapping key, mapped stage, certificate name and tag value are parameters, so one
     * template drives the create, the in-place update and the replacement. {@code Break} adds a
     * mapping onto a stage that does not exist after everything else, which fails the stack there.
     */
    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Domain": {"Type": "String"},
            "MappingKey": {"Type": "String"},
            "MappingStage": {"Type": "String"},
            "CertificateName": {"Type": "String"},
            "TagValue": {"Type": "String"},
            "BackendUrl": {"Type": "String"},
            "Break": {"Type": "String", "Default": "no"}
          },
          "Conditions": {"Breaks": {"Fn::Equals": [{"Ref": "Break"}, "yes"]}},
          "Resources": {
            "Cert": {
              "Type": "AWS::CertificateManager::Certificate",
              "Properties": {"DomainName": "*.apigwv2-domain-cfn-it.example.com", "ValidationMethod": "DNS"}
            },
            "Api": {
              "Type": "AWS::ApiGatewayV2::Api",
              "Properties": {"Name": "apigwv2-domain-cfn-it", "ProtocolType": "HTTP"}
            },
            "Backend": {
              "Type": "AWS::ApiGatewayV2::Integration",
              "Properties": {
                "ApiId": {"Ref": "Api"},
                "IntegrationType": "HTTP_PROXY",
                "IntegrationUri": {"Ref": "BackendUrl"},
                "PayloadFormatVersion": "1.0"
              }
            },
            "HelloRoute": {
              "Type": "AWS::ApiGatewayV2::Route",
              "Properties": {
                "ApiId": {"Ref": "Api"},
                "RouteKey": "GET /hello",
                "Target": {"Fn::Join": ["/", ["integrations", {"Ref": "Backend"}]]}
              }
            },
            "DefaultStage": {
              "Type": "AWS::ApiGatewayV2::Stage",
              "Properties": {"ApiId": {"Ref": "Api"}, "StageName": "$default", "AutoDeploy": true}
            },
            "ProdStage": {
              "Type": "AWS::ApiGatewayV2::Stage",
              "Properties": {"ApiId": {"Ref": "Api"}, "StageName": "prod", "AutoDeploy": true}
            },
            "ApiDomain": {
              "Type": "AWS::ApiGatewayV2::DomainName",
              "Properties": {
                "DomainName": {"Ref": "Domain"},
                "DomainNameConfigurations": [{
                  "CertificateArn": {"Ref": "Cert"},
                  "CertificateName": {"Ref": "CertificateName"},
                  "EndpointType": "REGIONAL",
                  "SecurityPolicy": "TLS_1_2"
                }],
                "Tags": {"stack": {"Ref": "TagValue"}}
              }
            },
            "Mapping": {
              "Type": "AWS::ApiGatewayV2::ApiMapping",
              "DependsOn": ["DefaultStage", "ProdStage"],
              "Properties": {
                "DomainName": {"Ref": "ApiDomain"},
                "ApiId": {"Ref": "Api"},
                "Stage": {"Ref": "MappingStage"},
                "ApiMappingKey": {"Ref": "MappingKey"}
              }
            },
            "Breaker": {
              "Type": "AWS::ApiGatewayV2::ApiMapping",
              "Condition": "Breaks",
              "DependsOn": ["Mapping"],
              "Properties": {
                "DomainName": {"Ref": "ApiDomain"},
                "ApiId": {"Ref": "Api"},
                "Stage": "no-such-stage",
                "ApiMappingKey": "broken"
              }
            }
          },
          "Outputs": {
            "CertArn": {"Value": {"Ref": "Cert"}},
            "ApiId": {"Value": {"Ref": "Api"}},
            "DomainRef": {"Value": {"Ref": "ApiDomain"}},
            "DomainNameArn": {"Value": {"Fn::GetAtt": ["ApiDomain", "DomainNameArn"]}},
            "RegionalDomainName": {"Value": {"Fn::GetAtt": ["ApiDomain", "RegionalDomainName"]}},
            "RegionalHostedZoneId": {"Value": {"Fn::GetAtt": ["ApiDomain", "RegionalHostedZoneId"]}},
            "MappingRef": {"Value": {"Ref": "Mapping"}},
            "MappingId": {"Value": {"Fn::GetAtt": ["Mapping", "ApiMappingId"]}}
          }
        }
        """;

    private static HttpServer backend;
    private static final AtomicInteger backendHits = new AtomicInteger();
    private static String backendUrl;

    @BeforeAll
    static void startBackend() throws Exception {
        RestAssuredJsonUtils.configureAwsContentTypes();
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            backendHits.incrementAndGet();
            byte[] body = BACKEND_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        backendUrl = "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void domainAndMappingExposeRealValuesRouteRequestsUpdateInPlaceReplaceAndDelete() {
        String stack = "apigwv2-domain-lifecycle";
        String domain = "api.lifecycle." + ZONE;
        String renamed = "gateway.lifecycle." + ZONE;
        cloudFormation(stack, "CreateStack", TEMPLATE, parameters(domain, "v1", "$default", "first", "created"));

        String stacks = describeStacks(stack, "CREATE_COMPLETE");
        String apiId = outputValue(stacks, "ApiId");
        String certificateArn = outputValue(stacks, "CertArn");
        assertTrue(certificateArn.startsWith("arn:aws:acm:us-east-1:"), certificateArn);
        assertEquals(domain, outputValue(stacks, "DomainRef"));
        assertEquals("arn:aws:apigateway:us-east-1::/domainnames/" + domain, outputValue(stacks, "DomainNameArn"));

        ValidatableResponse created = getDomain(domain).statusCode(200)
            .body("domainNameArn", equalTo(outputValue(stacks, "DomainNameArn")))
            .body("domainNameConfigurations[0].certificateArn", equalTo(certificateArn))
            .body("domainNameConfigurations[0].certificateName", equalTo("first"))
            .body("domainNameConfigurations[0].endpointType", equalTo("REGIONAL"))
            .body("domainNameConfigurations[0].securityPolicy", equalTo("TLS_1_2"))
            .body("tags.stack", equalTo("created"));
        String regionalDomainName = created.extract().path("domainNameConfigurations[0].apiGatewayDomainName");
        assertEquals(regionalDomainName, outputValue(stacks, "RegionalDomainName"));
        assertEquals(created.extract().<String>path("domainNameConfigurations[0].hostedZoneId"),
                outputValue(stacks, "RegionalHostedZoneId"));

        String mappingId = outputValue(stacks, "MappingRef");
        assertEquals(mappingId, outputValue(stacks, "MappingId"), "Ref and GetAtt ApiMappingId are the same id");
        getMappings(domain).statusCode(200).body("items.apiMappingId", hasItem(mappingId));
        getMapping(domain, mappingId).statusCode(200)
            .body("apiId", equalTo(apiId))
            .body("stage", equalTo("$default"))
            .body("apiMappingKey", equalTo("v1"));
        invokeThroughDomain(domain, "/v1/hello");
        invokeThroughDomain(regionalDomainName, "/v1/hello");

        // The domain's configuration and tags and the mapping's key and stage all change in place,
        // as on AWS: the mapping keeps its id, and the domain the regional name a DNS alias points at.
        cloudFormation(stack, "UpdateStack", TEMPLATE, parameters(domain, "v2", "prod", "second", "updated"));

        stacks = describeStacks(stack, "UPDATE_COMPLETE");
        assertEquals(domain, outputValue(stacks, "DomainRef"));
        assertEquals(regionalDomainName, outputValue(stacks, "RegionalDomainName"));
        assertEquals(mappingId, outputValue(stacks, "MappingRef"));
        assertEquals(mappingId, outputValue(stacks, "MappingId"));
        getDomain(domain).statusCode(200)
            .body("domainNameConfigurations[0].apiGatewayDomainName", equalTo(regionalDomainName))
            .body("domainNameConfigurations[0].certificateArn", equalTo(certificateArn))
            .body("domainNameConfigurations[0].certificateName", equalTo("second"))
            .body("tags.stack", equalTo("updated"));
        getMapping(domain, mappingId).statusCode(200)
            .body("apiMappingKey", equalTo("v2"))
            .body("stage", equalTo("prod"));
        getMappings(domain).statusCode(200).body("items.size()", equalTo(1));
        invokeThroughDomain(domain, "/v2/hello");
        assertNotRouted(domain, "/v1/hello");

        // DomainName is createOnly on both types: the new domain and its mapping are created, and the
        // displaced ones are deleted once the update has committed.
        cloudFormation(stack, "UpdateStack", TEMPLATE, parameters(renamed, "v2", "prod", "second", "updated"));

        stacks = describeStacks(stack, "UPDATE_COMPLETE");
        assertEquals(renamed, outputValue(stacks, "DomainRef"));
        assertEquals("arn:aws:apigateway:us-east-1::/domainnames/" + renamed, outputValue(stacks, "DomainNameArn"));
        assertEquals(certificateArn, outputValue(stacks, "CertArn"), "the wildcard certificate is kept");
        String renamedMappingId = outputValue(stacks, "MappingRef");
        assertNotEquals(mappingId, renamedMappingId, "a replaced mapping gets an id of its own");
        getDomain(domain).statusCode(404);
        getDomain(renamed).statusCode(200)
            .body("domainNameConfigurations[0].certificateArn", equalTo(certificateArn))
            .body("domainNameConfigurations[0].certificateName", equalTo("second"))
            .body("tags.stack", equalTo("updated"));
        getMapping(renamed, renamedMappingId).statusCode(200)
            .body("apiId", equalTo(apiId))
            .body("stage", equalTo("prod"))
            .body("apiMappingKey", equalTo("v2"));
        invokeThroughDomain(renamed, "/v2/hello");
        String events = stackEvents(stack);
        assertTrue(events.contains("<ResourceStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</ResourceStatus>"), events);
        assertTrue(hasEvent(events, "ApiDomain", domain, "DELETE_COMPLETE"), "the displaced domain is deleted in the cleanup");
        assertTrue(hasEvent(events, "Mapping", mappingId, "DELETE_COMPLETE"), "the displaced mapping is deleted in the cleanup");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);

        getDomain(renamed).statusCode(404);
        given().when().get("/v2/apis/" + apiId).then().statusCode(404);
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then().statusCode(404);
    }

    @Test
    void aRenameALaterResourceFailsIsRolledBackToTheOriginalDomainAndMapping() {
        String stack = "apigwv2-domain-rename-rollback";
        String domain = "api.rename-rollback." + ZONE;
        String renamed = "gateway.rename-rollback." + ZONE;
        cloudFormation(stack, "CreateStack", TEMPLATE, parameters(domain, "v1", "$default", "first", "created"));
        String mappingId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "MappingRef");

        Map<String, String> breaking = parameters(renamed, "v1", "$default", "first", "created");
        breaking.put("Break", "yes");
        cloudFormation(stack, "UpdateStack", TEMPLATE, breaking);

        String stacks = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(domain, outputValue(stacks, "DomainRef"));
        assertEquals(mappingId, outputValue(stacks, "MappingRef"));
        getDomain(renamed).statusCode(404);
        getDomain(domain).statusCode(200);
        getMapping(domain, mappingId).statusCode(200).body("apiMappingKey", equalTo("v1"));
        invokeThroughDomain(domain, "/v1/hello");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        getDomain(domain).statusCode(404);
    }

    @Test
    void anInPlaceUpdateALaterResourceFailsIsPutBack() {
        String stack = "apigwv2-domain-in-place-rollback";
        String domain = "api.in-place-rollback." + ZONE;
        cloudFormation(stack, "CreateStack", TEMPLATE, parameters(domain, "v1", "$default", "first", "created"));
        String mappingId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "MappingRef");

        Map<String, String> breaking = parameters(domain, "v2", "prod", "second", "updated");
        breaking.put("Break", "yes");
        cloudFormation(stack, "UpdateStack", TEMPLATE, breaking);

        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        getDomain(domain).statusCode(200)
            .body("domainNameConfigurations[0].certificateName", equalTo("first"))
            .body("tags.stack", equalTo("created"));
        getMapping(domain, mappingId).statusCode(200)
            .body("apiMappingKey", equalTo("v1"))
            .body("stage", equalTo("$default"));
        getMappings(domain).statusCode(200).body("items.size()", equalTo(1));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void retainKeepsTheDomainARenameDisplaced() {
        String stack = "apigwv2-domain-retain";
        String domain = "api.retain." + ZONE;
        String renamed = "gateway.retain." + ZONE;
        String template = TEMPLATE.replace("\"Type\": \"AWS::ApiGatewayV2::DomainName\",",
                "\"Type\": \"AWS::ApiGatewayV2::DomainName\", \"UpdateReplacePolicy\": \"Retain\",");
        cloudFormation(stack, "CreateStack", template, parameters(domain, "v1", "$default", "first", "created"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", template, parameters(renamed, "v1", "$default", "first", "created"));

        describeStacks(stack, "UPDATE_COMPLETE");
        getDomain(domain).statusCode(200);
        getMappings(domain).statusCode(200).body("items.size()", equalTo(0));
        getDomain(renamed).statusCode(200);

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        getDomain(renamed).statusCode(404);
        getDomain(domain).statusCode(200);
        given().when().delete("/v2/domainnames/" + domain).then().statusCode(204);
    }

    @Test
    void aCreateALaterResourceFailsRemovesTheDomainAndMapping() {
        String stack = "apigwv2-domain-create-rollback";
        String domain = "api.create-rollback." + ZONE;
        Map<String, String> breaking = parameters(domain, "v1", "$default", "first", "created");
        breaking.put("Break", "yes");

        cloudFormation(stack, "CreateStack", TEMPLATE, breaking);

        describeStacks(stack, "ROLLBACK_COMPLETE");
        getDomain(domain).statusCode(404);
        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void aMappingDroppedFromTheTemplateIsDeleted() {
        String stack = "apigwv2-domain-drop-mapping";
        String domain = "api.drop-mapping." + ZONE;
        cloudFormation(stack, "CreateStack", TEMPLATE, parameters(domain, "v1", "$default", "first", "created"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", withoutMapping(), parameters(domain, "v1", "$default", "first", "created"));

        describeStacks(stack, "UPDATE_COMPLETE");
        getDomain(domain).statusCode(200);
        getMappings(domain).statusCode(200).body("items.size()", equalTo(0));
        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void aStackWhoseDomainWasDeletedOutsideItStillDeletes() {
        String stack = "apigwv2-domain-gone";
        String domain = "api.gone." + ZONE;
        cloudFormation(stack, "CreateStack", TEMPLATE, parameters(domain, "v1", "$default", "first", "created"));
        describeStacks(stack, "CREATE_COMPLETE");
        given().when().delete("/v2/domainnames/" + domain).then().statusCode(204);

        cloudFormation(stack, "DeleteStack", null, Map.of());

        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void cdkHttpApiWithADefaultDomainMappingAKeyedMappingAndAnAliasRecord() {
        String stack = "apigwv2-domain-cdk-http";
        cloudFormation(stack, "CreateStack", template("http-api-domain.template.json"), Map.of("BackendUrl", backendUrl));

        String stacks = describeStacks(stack, "CREATE_COMPLETE");
        String apiId = outputValue(stacks, "ApiId");
        String regionalDomainName = outputValue(stacks, "RegionalDomainName");
        getDomain(CDK_DOMAIN).statusCode(200)
            .body("domainNameConfigurations[0].apiGatewayDomainName", equalTo(regionalDomainName))
            .body("domainNameConfigurations[0].hostedZoneId", equalTo(outputValue(stacks, "RegionalHostedZoneId")));
        getMappings(CDK_DOMAIN).statusCode(200)
            .body("items.size()", equalTo(2))
            .body("items.find { it.apiMappingKey == '' }.stage", equalTo("$default"))
            .body("items.find { it.apiMappingKey == '' }.apiId", equalTo(apiId))
            .body("items.find { it.apiMappingKey == 'prod' }.stage", equalTo("prod"));

        // The alias carries the domain's real regional name and zone, not the literal attribute
        // names an unset Fn::GetAtt would leave there.
        given().header("Authorization", ROUTE53_AUTH)
            .when().get("/2013-04-01/hostedzone/" + outputValue(stacks, "ZoneId") + "/rrset")
            .then().statusCode(200)
            .body(containsString("<DNSName>" + regionalDomainName))
            .body(containsString("<HostedZoneId>" + outputValue(stacks, "RegionalHostedZoneId") + "</HostedZoneId>"));

        invokeThroughDomain(CDK_DOMAIN, "/anything");
        invokeThroughDomain(CDK_DOMAIN, "/prod/anything");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        getDomain(CDK_DOMAIN).statusCode(404);
        given().when().get("/v2/apis/" + apiId).then().statusCode(404);
    }

    @Test
    void cdkRestApiOnACustomDomainUnderAKeyWithSeveralLevels() {
        String stack = "apigwv2-domain-cdk-rest";
        cloudFormation(stack, "CreateStack", template("rest-api-multi-level-domain.template.json"),
                Map.of("BackendUrl", backendUrl));

        String restApiId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "RestApiId");
        getMappings(CDK_REST_DOMAIN).statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].apiId", equalTo(restApiId))
            .body("items[0].stage", equalTo("prod"))
            .body("items[0].apiMappingKey", equalTo("orders/v1"));
        invokeThroughDomain(CDK_REST_DOMAIN, "/orders/v1/items");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        getDomain(CDK_REST_DOMAIN).statusCode(404);
    }

    private static Map<String, String> parameters(String domain, String mappingKey, String mappingStage,
                                                  String certificateName, String tagValue) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Domain", domain);
        parameters.put("MappingKey", mappingKey);
        parameters.put("MappingStage", mappingStage);
        parameters.put("CertificateName", certificateName);
        parameters.put("TagValue", tagValue);
        parameters.put("BackendUrl", backendUrl);
        return parameters;
    }

    /** The lifecycle template with the mappings and every output naming them taken out. */
    private static String withoutMapping() {
        try {
            ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
            ((ObjectNode) template.get("Resources")).remove("Mapping");
            ((ObjectNode) template.get("Resources")).remove("Breaker");
            ((ObjectNode) template.get("Outputs")).remove("MappingRef");
            ((ObjectNode) template.get("Outputs")).remove("MappingId");
            return template.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String template(String name) {
        try (InputStream in = ApiGatewayV2DomainCfnIntegrationTest.class.getResourceAsStream(
                "/cloudformation/apigatewayv2-domain/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test template " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cloudFormation(String stack, String action, String templateBody,
                                       Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    /** Waits for the create or update to settle, then hands back the DescribeStacks body. */
    private static String describeStacks(String stack, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .extract().asString();
    }

    private static String stackEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .extract().asString();
    }

    /** Whether one event names this resource, physical id and status together. */
    private static boolean hasEvent(String events, String logicalId, String physicalId, String status) {
        return Arrays.stream(events.split("<member>"))
            .anyMatch(event -> event.contains("<LogicalResourceId>" + logicalId + "</LogicalResourceId>")
                    && event.contains("<PhysicalResourceId>" + physicalId + "</PhysicalResourceId>")
                    && event.contains("<ResourceStatus>" + status + "</ResourceStatus>"));
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getDomain(String domain) {
        return given().when().get("/v2/domainnames/" + domain).then();
    }

    private static ValidatableResponse getMappings(String domain) {
        return given().when().get("/v2/domainnames/" + domain + "/apimappings").then();
    }

    private static ValidatableResponse getMapping(String domain, String apiMappingId) {
        return given().when().get("/v2/domainnames/" + domain + "/apimappings/" + apiMappingId).then();
    }

    /** The custom domain filter routes on the Host header, the way a DNS alias would deliver the request. */
    private static void invokeThroughDomain(String host, String path) {
        int before = backendHits.get();
        given().header("Host", host).when().get(path).then()
            .statusCode(200)
            .body(equalTo(BACKEND_BODY));
        assertEquals(before + 1, backendHits.get(), "the request through " + host + path + " reached the integration");
    }

    private static void assertNotRouted(String host, String path) {
        int before = backendHits.get();
        given().header("Host", host).when().get(path);
        assertEquals(before, backendHits.get(), "the request through " + host + path + " reached the integration");
    }
}
