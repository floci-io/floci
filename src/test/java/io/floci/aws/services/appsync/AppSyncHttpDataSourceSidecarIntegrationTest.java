package io.floci.aws.services.appsync;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.AppSyncHttpTestPolicies;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.DataSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SDK management, real GraphQL/Node sidecars, resolver callbacks and signed local AWS requests. */
@QuarkusTest
@TestProfile(AppSyncHttpDataSourceSidecarIntegrationTest.EnforcedSidecars.class)
class AppSyncHttpDataSourceSidecarIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String TARGET_REGION = "us-west-2";

    public static class EnforcedSidecars extends SidecarContainersProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.iam.enforcement-enabled", "true");
            overrides.put("floci.auth.validate-signatures", "true");
            return overrides;
        }
    }

    @Inject
    IamService iam;

    private AppSyncClient client;
    private String apiId;
    private String apiKey;
    private String roleName;

    @BeforeAll
    static void requireSidecars() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.js-runtime.image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createApi() {
        client = AwsTestClients.create(AppSyncClient.builder(), "test", Region.of(REGION));
        String suffix = UUID.randomUUID().toString();
        apiId = client.createGraphqlApi(r -> r.name("http-" + suffix).authenticationType("API_KEY")).graphqlApi().apiId();
        String apiArn = client.getGraphqlApi(r -> r.apiId(apiId)).graphqlApi().arn();
        apiKey = client.createApiKey(r -> r.apiId(apiId)).apiKey().id();
        client.startSchemaCreation(r -> r.apiId(apiId).definition(SdkBytes.fromUtf8String(
                "type HttpResult { statusCode: Int! body: String! } type Query { lookup: HttpResult }")));
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCESS".equals(
                client.getSchemaCreationStatus(r -> r.apiId(apiId)).statusAsString()));
        roleName = "http-resolver-" + suffix;
        String roleArn = RequestScopes.callAs(ACCOUNT, REGION, () -> iam.createRole(roleName, "/",
                AppSyncHttpTestPolicies.trust(apiArn, ACCOUNT), null, 3600, Map.of()).getArn());
        setPolicy("Allow");
        client.createDataSource(r -> r.apiId(apiId).name("Schedules").type("HTTP").serviceRoleArn(roleArn)
                .httpConfig(h -> h.endpoint("https://scheduler." + TARGET_REGION + ".amazonaws.com")
                        .authorizationConfig(a -> a.authorizationType("AWS_IAM").awsIamConfig(i -> i
                                .signingRegion(TARGET_REGION).signingServiceName("scheduler")))));
    }

    @AfterEach
    void cleanUp() {
        try {
            if (apiId != null) {
                client.deleteGraphqlApi(r -> r.apiId(apiId));
            }
        } finally {
            try {
                if (roleName != null) {
                    RequestScopes.runAs(ACCOUNT, REGION, () -> {
                        iam.deleteRolePolicy(roleName, "schedule");
                        iam.deleteRole(roleName);
                    });
                }
            } finally {
                if (client != null) {
                    client.close();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsAndVtlResolversPreserveSignedServiceResponsesAndDenials(boolean vtl) {
        if (vtl) {
            client.createResolver(r -> r.apiId(apiId).typeName("Query").fieldName("lookup").kind("UNIT")
                    .dataSourceName("Schedules").requestMappingTemplate("""
                            {"version":"2018-05-29","method":"GET","resourcePath":"/schedules/missing"}
                            """).responseMappingTemplate("$util.toJson($ctx.result)"));
        } else {
            client.createResolver(r -> r.apiId(apiId).typeName("Query").fieldName("lookup").kind("UNIT")
                    .dataSourceName("Schedules").runtime(t -> t.name("APPSYNC_JS").runtimeVersion("1.0.0")).code("""
                            export function request(ctx) {
                              return { method: "GET", resourcePath: "/schedules/missing" };
                            }
                            export function response(ctx) { return ctx.result; }
                            """));
        }
        assertGraphqlResponse(404, "ResourceNotFoundException");
        setPolicy("Deny");
        assertGraphqlResponse(403, "AccessDeniedException");
    }

    @Test
    void cloudFormationPreservesTheHttpEndpointAndSigningConfiguration() {
        String stack = "http-source-" + UUID.randomUUID();
        String roleArn = client.getDataSource(r -> r.apiId(apiId).name("Schedules")).dataSource().serviceRoleArn();
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.of(REGION))) {
            cfn.createStack(r -> r.stackName(stack).templateBody("""
                    {"Resources":{"Source":{"Type":"AWS::AppSync::DataSource","Properties":{
                    "ApiId":"%s","Name":"CfnSchedules","Type":"HTTP","ServiceRoleArn":"%s",
                    "HttpConfig":{"Endpoint":"https://scheduler.%s.amazonaws.com",
                    "AuthorizationConfig":{"AuthorizationType":"AWS_IAM","AwsIamConfig":{
                    "SigningRegion":"%s","SigningServiceName":"scheduler"}}}}}}}
                    """.formatted(apiId, roleArn, TARGET_REGION, TARGET_REGION)));
            try {
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals("CREATE_COMPLETE",
                        cfn.describeStacks(r -> r.stackName(stack)).stacks().getFirst().stackStatusAsString()));
                DataSource source = client.getDataSource(r -> r.apiId(apiId).name("CfnSchedules")).dataSource();
                assertEquals("HTTP", source.typeAsString());
                assertEquals(roleArn, source.serviceRoleArn());
                assertEquals("https://scheduler." + TARGET_REGION + ".amazonaws.com", source.httpConfig().endpoint());
                assertEquals("AWS_IAM", source.httpConfig().authorizationConfig().authorizationTypeAsString());
                assertEquals(TARGET_REGION, source.httpConfig().authorizationConfig().awsIamConfig().signingRegion());
                assertEquals("scheduler", source.httpConfig().authorizationConfig().awsIamConfig().signingServiceName());
            } finally {
                cfn.deleteStack(r -> r.stackName(stack));
                await().atMost(Duration.ofSeconds(30)).until(() -> {
                    try {
                        cfn.describeStacks(r -> r.stackName(stack));
                        return false;
                    } catch (CloudFormationException e) {
                        if ("ValidationError".equals(e.awsErrorDetails().errorCode())) {
                            return true;
                        }
                        throw e;
                    }
                });
            }
        }
    }

    private void assertGraphqlResponse(int status, String code) {
        given().header("x-api-key", apiKey).contentType("application/json")
                .body(Map.of("query", "{ lookup { statusCode body } }"))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200).body("errors", nullValue())
                .body("data.lookup.statusCode", equalTo(status)).body("data.lookup.body", containsString(code));
    }

    private void setPolicy(String effect) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> iam.putRolePolicy(roleName, "schedule",
                AppSyncHttpTestPolicies.getSchedule(effect, TARGET_REGION, ACCOUNT)));
    }
}
