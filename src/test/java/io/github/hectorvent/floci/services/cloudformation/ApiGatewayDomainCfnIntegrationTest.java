package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::ApiGateway::DomainName} and an {@code AWS::ApiGateway::BasePathMapping}
 * through a CloudFormation stack, laid out the way CDK emits a regional custom domain: an ACM
 * certificate, the domain referencing it, and a mapping onto a REST API stage. Asserts that every
 * {@code Fn::GetAtt} attribute is the value {@code GetDomainName} reports rather than the literal
 * {@code ApiDomain.RegionalDomainName} the stub arm would leave in the record, that a request sent
 * to the custom domain reaches the mapped API, that mutable properties update in place while a
 * renamed domain is replaced, and that deleting the stack removes the mapping before the REST API
 * that refuses to go while a mapping points at it.
 */
@QuarkusTest
class ApiGatewayDomainCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260904/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "apigw-domain-cfn-it";
    private static final String INLINE_API_STACK = "apigw-domain-cfn-inline-it";
    private static final String DOMAIN = "api.apigw-domain-cfn-it.example.com";
    private static final String RENAMED_DOMAIN = "gateway.apigw-domain-cfn-it.example.com";
    private static final String INLINE_DOMAIN = "inline.apigw-domain-cfn-it.example.com";

    /**
     * The domain, stage, security policy and tag value are parameters so the same template drives
     * the create, the in-place update and the replacement. A wildcard certificate keeps a domain
     * rename from replacing the certificate as well.
     */
    private static final String TEMPLATE = """
        {
          "Parameters": {
            "ApiId": {"Type": "String"},
            "StageName": {"Type": "String"},
            "Domain": {"Type": "String"},
            "SecurityPolicy": {"Type": "String"},
            "TagValue": {"Type": "String"},
            "EndpointType": {"Type": "String", "Default": "REGIONAL"}
          },
          "Resources": {
            "Cert": {
              "Type": "AWS::CertificateManager::Certificate",
              "Properties": {"DomainName": "*.apigw-domain-cfn-it.example.com", "ValidationMethod": "DNS"}
            },
            "ApiDomain": {
              "Type": "AWS::ApiGateway::DomainName",
              "Properties": {
                "DomainName": {"Ref": "Domain"},
                "EndpointConfiguration": {"Types": [{"Ref": "EndpointType"}]},
                "RegionalCertificateArn": {"Ref": "Cert"},
                "SecurityPolicy": {"Ref": "SecurityPolicy"},
                "Tags": [{"Key": "stack", "Value": {"Ref": "TagValue"}}]
              }
            },
            "Mapping": {
              "Type": "AWS::ApiGateway::BasePathMapping",
              "Properties": {
                "DomainName": {"Ref": "ApiDomain"},
                "BasePath": "v1",
                "RestApiId": {"Ref": "ApiId"},
                "Stage": {"Ref": "StageName"}
              }
            }
          },
          "Outputs": {
            "CertArn": {"Value": {"Ref": "Cert"}},
            "DomainRef": {"Value": {"Ref": "ApiDomain"}},
            "DomainNameArn": {"Value": {"Fn::GetAtt": ["ApiDomain", "DomainNameArn"]}},
            "RegionalDomainName": {"Value": {"Fn::GetAtt": ["ApiDomain", "RegionalDomainName"]}},
            "RegionalHostedZoneId": {"Value": {"Fn::GetAtt": ["ApiDomain", "RegionalHostedZoneId"]}},
            "DistributionDomainName": {"Value": {"Fn::GetAtt": ["ApiDomain", "DistributionDomainName"]}},
            "DistributionHostedZoneId": {"Value": {"Fn::GetAtt": ["ApiDomain", "DistributionHostedZoneId"]}},
            "MappingRef": {"Value": {"Ref": "Mapping"}}
          }
        }
        """;

    /** The REST API lives in the stack, as it does when one template owns the whole API. */
    private static final String INLINE_API_TEMPLATE = """
        {
          "Resources": {
            "Api": {
              "Type": "AWS::ApiGateway::RestApi",
              "Properties": {"Name": "apigw-domain-cfn-inline-it"}
            },
            "ApiDomain": {
              "Type": "AWS::ApiGateway::DomainName",
              "Properties": {
                "DomainName": "%s",
                "RegionalCertificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555"
              }
            },
            "Mapping": {
              "Type": "AWS::ApiGateway::BasePathMapping",
              "Properties": {"DomainName": {"Ref": "ApiDomain"}, "RestApiId": {"Ref": "Api"}}
            }
          },
          "Outputs": {
            "ApiId": {"Value": {"Ref": "Api"}},
            "MappingRef": {"Value": {"Ref": "Mapping"}}
          }
        }
        """.formatted(INLINE_DOMAIN);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void domainStackExposesRealAttributesRoutesRequestsUpdatesInPlaceReplacesOnRenameAndDeletes() throws Exception {
        String apiId = createMockApi("apigw-domain-cfn-it-api");
        String deploymentId = createDeployment(apiId);
        createStage(apiId, "prod", deploymentId);
        createStage(apiId, "dev", deploymentId);

        cloudFormation(STACK, "CreateStack", TEMPLATE, parameters(apiId, "prod", DOMAIN, "TLS_1_2", "created"));

        String stacks = describeStacks(STACK, "CREATE_COMPLETE");
        String certificateArn = outputValue(stacks, "CertArn");
        assertTrue(certificateArn.startsWith("arn:aws:acm:us-east-1:"), certificateArn);
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertEquals("arn:aws:apigateway:us-east-1::/domainnames/" + DOMAIN, outputValue(stacks, "DomainNameArn"));
        assertEquals(DOMAIN + "|v1", outputValue(stacks, "MappingRef"));
        assertEquals("", outputValue(stacks, "DistributionDomainName"),
                "a regional domain has no distribution, and must not resolve to the literal attribute name");
        assertEquals("", outputValue(stacks, "DistributionHostedZoneId"));

        ValidatableResponse domain = getDomain(DOMAIN).statusCode(200);
        String regionalDomainName = domain.extract().path("regionalDomainName");
        assertEquals(regionalDomainName, outputValue(stacks, "RegionalDomainName"));
        assertEquals(domain.extract().path("regionalHostedZoneId"), outputValue(stacks, "RegionalHostedZoneId"));
        domain.body("domainNameArn", equalTo(outputValue(stacks, "DomainNameArn")))
              .body("regionalCertificateArn", equalTo(certificateArn))
              .body("securityPolicy", equalTo("TLS_1_2"))
              .body("endpointConfiguration.types[0]", equalTo("REGIONAL"))
              .body("tags.stack", equalTo("created"));
        getMapping(DOMAIN, "v1").statusCode(200)
              .body("restApiId", equalTo(apiId))
              .body("stage", equalTo("prod"));
        invokeThroughDomain(DOMAIN, "/v1/items");

        // Everything but the name updates in place: the domain keeps its identity and its regional
        // name, which is what a DNS record outside the stack points at.
        cloudFormation(STACK, "UpdateStack", TEMPLATE, parameters(apiId, "dev", DOMAIN, "TLS_1_0", "updated"));

        stacks = describeStacks(STACK, "UPDATE_COMPLETE");
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertEquals(DOMAIN + "|v1", outputValue(stacks, "MappingRef"));
        assertEquals(regionalDomainName, outputValue(stacks, "RegionalDomainName"));
        getDomain(DOMAIN).statusCode(200)
              .body("regionalDomainName", equalTo(regionalDomainName))
              .body("regionalCertificateArn", equalTo(certificateArn))
              .body("securityPolicy", equalTo("TLS_1_0"))
              .body("tags.stack", equalTo("updated"));
        getMapping(DOMAIN, "v1").statusCode(200).body("stage", equalTo("dev"));
        invokeThroughDomain(DOMAIN, "/v1/items");

        // Moving to EDGE is also in place: the domain keeps its identity and regional name, and the
        // distribution attributes a DNS alias for an edge domain points at appear.
        cloudFormation(STACK, "UpdateStack", TEMPLATE,
                parameters(apiId, "dev", DOMAIN, "TLS_1_0", "updated", "EDGE"));

        stacks = describeStacks(STACK, "UPDATE_COMPLETE");
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertEquals(regionalDomainName, outputValue(stacks, "RegionalDomainName"));
        String distribution = outputValue(stacks, "DistributionDomainName");
        assertTrue(distribution.endsWith(".cloudfront.net"), "an edge domain has a distribution: " + distribution);
        assertEquals("Z2FDTNDATAQYW2", outputValue(stacks, "DistributionHostedZoneId"));
        getDomain(DOMAIN).statusCode(200)
              .body("endpointConfiguration.types[0]", equalTo("EDGE"))
              .body("distributionDomainName", equalTo(distribution));
        invokeThroughDomain(DOMAIN, "/v1/items");

        // And back: a regional domain has no distribution, so the attributes empty again.
        cloudFormation(STACK, "UpdateStack", TEMPLATE, parameters(apiId, "dev", DOMAIN, "TLS_1_0", "updated"));

        stacks = describeStacks(STACK, "UPDATE_COMPLETE");
        assertEquals("", outputValue(stacks, "DistributionDomainName"));
        assertEquals("", outputValue(stacks, "DistributionHostedZoneId"));
        getDomain(DOMAIN).statusCode(200)
              .body("endpointConfiguration.types[0]", equalTo("REGIONAL"))
              .body("distributionDomainName", nullValue());

        // DomainName is createOnly: a new name is a replacement, created before the old domain goes,
        // and the mapping follows it because its DomainName is createOnly too.
        cloudFormation(STACK, "UpdateStack", TEMPLATE, parameters(apiId, "dev", RENAMED_DOMAIN, "TLS_1_0", "updated"));

        stacks = describeStacks(STACK, "UPDATE_COMPLETE");
        assertEquals(RENAMED_DOMAIN, outputValue(stacks, "DomainRef"));
        assertEquals(RENAMED_DOMAIN + "|v1", outputValue(stacks, "MappingRef"));
        assertEquals(certificateArn, outputValue(stacks, "CertArn"), "the wildcard certificate is kept");
        getDomain(DOMAIN).statusCode(404);
        getDomain(RENAMED_DOMAIN).statusCode(200)
              .body("regionalCertificateArn", equalTo(certificateArn))
              .body("securityPolicy", equalTo("TLS_1_0"));
        getMapping(RENAMED_DOMAIN, "v1").statusCode(200)
              .body("restApiId", equalTo(apiId))
              .body("stage", equalTo("dev"));
        invokeThroughDomain(RENAMED_DOMAIN, "/v1/items");

        cloudFormation(STACK, "DeleteStack", null, Map.of());
        awaitStackDeleted(STACK);

        getDomain(RENAMED_DOMAIN).statusCode(404);
        getMapping(RENAMED_DOMAIN, "v1").statusCode(404);
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then().statusCode(404);
        deleteApi(apiId);
    }

    @Test
    void mappingWithoutABasePathOntoAnApiInTheSameStackIsCreatedAndDeletedWithIt() throws Exception {
        cloudFormation(INLINE_API_STACK, "CreateStack", INLINE_API_TEMPLATE, Map.of());

        String stacks = describeStacks(INLINE_API_STACK, "CREATE_COMPLETE");
        String apiId = outputValue(stacks, "ApiId");
        assertEquals(INLINE_DOMAIN + "|(none)", outputValue(stacks, "MappingRef"),
                "an omitted BasePath is the API's (none)");
        getMapping(INLINE_DOMAIN, "(none)").statusCode(200)
              .body("restApiId", equalTo(apiId))
              .body("stage", nullValue());

        cloudFormation(INLINE_API_STACK, "DeleteStack", null, Map.of());
        awaitStackDeleted(INLINE_API_STACK);

        getDomain(INLINE_DOMAIN).statusCode(404);
        given().when().get("/restapis/" + apiId).then().statusCode(404);
    }

    private static Map<String, String> parameters(String apiId, String stage, String domain,
                                                  String securityPolicy, String tagValue) {
        return parameters(apiId, stage, domain, securityPolicy, tagValue, "REGIONAL");
    }

    private static Map<String, String> parameters(String apiId, String stage, String domain,
                                                  String securityPolicy, String tagValue, String endpointType) {
        return Map.of("ApiId", apiId, "StageName", stage, "Domain", domain,
                "SecurityPolicy", securityPolicy, "TagValue", tagValue, "EndpointType", endpointType);
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

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getDomain(String domain) {
        return given().when().get("/domainnames/" + domain).then();
    }

    private static ValidatableResponse getMapping(String domain, String basePath) {
        return given().when().get("/domainnames/" + domain + "/basepathmappings/" + basePath).then();
    }

    /** The custom domain filter routes on the Host header, the way a DNS alias would deliver the request. */
    private static void invokeThroughDomain(String domain, String path) {
        given().header("Host", domain).when().get(path).then()
            .statusCode(200)
            .body("message", equalTo("via-custom-domain"));
    }

    /** A REST API whose GET /items is a MOCK integration answering a fixed JSON body. */
    private static String createMockApi(String name) {
        String apiId = given().contentType(ContentType.JSON).body("{\"name\":\"" + name + "\"}")
            .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
            .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body("{\"pathPart\":\"items\"}")
            .when().post("/restapis/" + apiId + "/resources/" + rootId).then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
            .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"responseParameters\":{}}")
            .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
            .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":"
                    + "\"{\\\"message\\\":\\\"via-custom-domain\\\"}\"}}")
            .when().put(method + "/integration/responses/200").then().statusCode(201);
        return apiId;
    }

    private static String createDeployment(String apiId) {
        return given().contentType(ContentType.JSON).body("{\"description\":\"v1\"}")
            .when().post("/restapis/" + apiId + "/deployments").then().statusCode(201).extract().path("id");
    }

    private static void createStage(String apiId, String stage, String deploymentId) {
        given().contentType(ContentType.JSON)
            .body("{\"stageName\":\"" + stage + "\",\"deploymentId\":\"" + deploymentId + "\"}")
            .when().post("/restapis/" + apiId + "/stages").then().statusCode(201);
    }

    private static void deleteApi(String apiId) {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}
