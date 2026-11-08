package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.TemplateStage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A SAM REST API whose OpenAPI root security names its own SigV4 scheme, with
 * {@code Auth.DefaultAuthorizer: AWS_IAM}. SAM adds the default to each operation's own security
 * and leaves the root security as written, so the operation carries one IAM requirement rather
 * than two, which the importer would refuse.
 */
@DisplayName("SAM REST API root security")
class SamRestApiRootSecurityTest {

    private static final Duration DEPLOY_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CloudFormationClient cfn;
    private static ApiGatewayClient apiGateway;

    private final List<String> stacks = new ArrayList<>();

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        apiGateway = TestFixtures.apiGatewayClient();
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        apiGateway.close();
    }

    @AfterEach
    void deleteStacks() {
        // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
        stacks.forEach(stack -> cfn.deleteStack(request -> request.stackName(stack)));
    }

    @ParameterizedTest(name = "swagger={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the IAM default is the operation's only requirement and root security is kept")
    void iamDefaultIsTheOperationsOnlyRequirement(boolean swagger) throws Exception {
        String stack = TestFixtures.uniqueName("compat-sam-root-security");
        stacks.add(stack);
        cfn.createStack(request -> request.stackName(stack).templateBody(template(swagger))
                .capabilities(Capability.CAPABILITY_AUTO_EXPAND));
        assertThat(TestFixtures.awaitStackTerminal(cfn, stack, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        JsonNode body = MAPPER.readTree(cfn.getTemplate(request -> request.stackName(stack)
                .templateStage(TemplateStage.PROCESSED)).templateBody()).at("/Resources/Api/Properties/Body");
        assertThat(body.get("security")).isEqualTo(MAPPER.readTree("[{\"sigv4\":[]}]"));
        assertThat(body.at("/paths/~1items/get/security")).isEqualTo(MAPPER.readTree("[{\"AWS_IAM\":[]}]"));

        String apiId = cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId("Api"))
                .stackResourceDetail().physicalResourceId();
        String resourceId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                .filter(resource -> "/items".equals(resource.path()))
                .findFirst().orElseThrow().id();
        GetMethodResponse method = apiGateway.getMethod(request -> request.restApiId(apiId)
                .resourceId(resourceId).httpMethod("GET"));
        assertThat(method.authorizationType()).isEqualTo("AWS_IAM");
        assertThat(method.apiKeyRequired()).isFalse();
    }

    private static String template(boolean swagger) {
        String version = swagger ? "\"swagger\": \"2.0\"" : "\"openapi\": \"3.0.1\"";
        String sigv4 = """
                {"sigv4": {"type": "apiKey", "name": "Authorization", "in": "header",
                           "x-amazon-apigateway-authtype": "awsSigv4"}}""";
        String schemes = swagger ? "\"securityDefinitions\": " + sigv4
                : "\"components\": {\"securitySchemes\": " + sigv4 + "}";
        return """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Resources": {
                    "Api": {
                      "Type": "AWS::Serverless::Api",
                      "Properties": {
                        "StageName": "test",
                        "Auth": {"DefaultAuthorizer": "AWS_IAM"},
                        "DefinitionBody": {
                          %s,
                          "info": {"title": "SAM root security", "version": "1"},
                          %s,
                          "security": [{"sigv4": []}],
                          "paths": {"/items": {"get": {
                            "responses": {"200": {"description": "ok"}},
                            "x-amazon-apigateway-integration": {
                              "type": "mock",
                              "requestTemplates": {"application/json": "{\\"statusCode\\": 200}"},
                              "responses": {"default": {"statusCode": "200"}}
                            }
                          }}}
                        }
                      }
                    }
                  }
                }
                """.formatted(version, schemes);
    }
}
