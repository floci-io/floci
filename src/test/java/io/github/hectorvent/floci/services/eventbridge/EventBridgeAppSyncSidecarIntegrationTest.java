package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.AppSyncParameters;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.InputTransformer;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.eventbridge.EventBridgeAppSyncFixtures.policy;
import static io.github.hectorvent.floci.services.eventbridge.EventBridgeAppSyncFixtures.trust;
import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real SDK delivery through a CFN rule, GraphQL sidecar, resolver callback and DynamoDB. */
@QuarkusTest
@TestProfile(EventBridgeAppSyncSidecarIntegrationTest.EnforcedSidecars.class)
class EventBridgeAppSyncSidecarIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-west-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261104/" + REGION + "/cloudformation/aws4_request";
    private static final String OPERATION = "mutation Publish($id: ID!) { publish(id: $id) { id caller } }";
    private static final String SCHEMA = "type Message { id: ID! caller: String } type Query { unused: String } "
            + "type Mutation { publish(id: ID!): Message }";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class EnforcedSidecars extends SidecarContainersProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.iam.enforcement-enabled", "true");
            return overrides;
        }
    }

    @Inject
    IamService iam;
    @Inject
    GraphqlSidecarClient sidecar;

    private String stack;
    private String rule;
    private String table;
    private String targetRoleName;
    private String dataRoleName;
    private EventBridgeClient events;
    private DynamoDbClient dynamo;
    private SqsClient sqs;
    private boolean stackCreated;

    @BeforeAll
    static void requireSidecar() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createStack() throws Exception {
        sidecar.validateSchema(SCHEMA, Map.of());
        String suffix = UUID.randomUUID().toString();
        stack = "eb-appsync-" + suffix;
        rule = stack + "-rule";
        table = stack + "-items";
        targetRoleName = stack + "-target";
        dataRoleName = stack + "-data";
        events = AwsTestClients.create(EventBridgeClient.builder(), "test", Region.of(REGION));
        dynamo = AwsTestClients.create(DynamoDbClient.builder(), "test", Region.of(REGION));
        sqs = AwsTestClients.create(SqsClient.builder(), "test", Region.of(REGION));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            iam.createRole(targetRoleName, "/", trust("events", "arn:aws:events:" + REGION + ":" + ACCOUNT + ":rule/" + rule, ACCOUNT),
                    null, 3600, Map.of());
            iam.putRolePolicy(targetRoleName, "invoke", policy("Allow", "arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":apis/*"));
            iam.createRole(dataRoleName, "/", trust("appsync", null, ACCOUNT), null, 3600, Map.of());
            iam.putRolePolicy(dataRoleName, "invoke", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"dynamodb:PutItem",
                    "Resource":"arn:aws:dynamodb:%s:%s:table/%s"}]}""".formatted(REGION, ACCOUNT, table));
        });
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        ObjectNode resolver = (ObjectNode) template.path("Resources").path("Resolver").path("Properties");
        resolver.put("RequestMappingTemplate", """
                {"version":"2018-05-29","operation":"PutItem",
                "key":{"id":$util.dynamodb.toDynamoDBJson($ctx.args.id)},
                "attributeValues":{"caller":$util.dynamodb.toDynamoDBJson($ctx.identity.userArn)}}""");
        ObjectNode target = (ObjectNode) template.path("Resources").path("Rule").path("Properties").path("Targets").get(0);
        target.putObject("AppSyncParameters").put("GraphQLOperation", OPERATION);
        target.putObject("InputTransformer").putObject("InputPathsMap").put("id", "$.detail.id");
        ((ObjectNode) target.path("InputTransformer")).put("InputTemplate", "{\"id\":<id>}");
        cfn("CreateStack", stack).header("Authorization", AUTH).formParam("TemplateBody", MAPPER.writeValueAsString(template))
                .formParam("Parameters.member.1.ParameterKey", "RuleName").formParam("Parameters.member.1.ParameterValue", rule)
                .formParam("Parameters.member.2.ParameterKey", "TableName").formParam("Parameters.member.2.ParameterValue", table)
                .formParam("Parameters.member.3.ParameterKey", "TargetRole").formParam("Parameters.member.3.ParameterValue", roleArn(targetRoleName))
                .formParam("Parameters.member.4.ParameterKey", "DataRole").formParam("Parameters.member.4.ParameterValue", roleArn(dataRoleName))
                .when().post("/").then().statusCode(200);
        stackCreated = true;
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, AUTH);
        assertEquals("CREATE_COMPLETE", state.status(), state.reason());
    }

    @AfterEach
    void cleanUp() {
        try {
            if (stackCreated) {
                cfn("DeleteStack", stack).header("Authorization", AUTH).post("/").then().statusCode(200);
                CfnStackWaits.awaitStackDeleted(stack, AUTH);
            }
        } finally {
            if (targetRoleName != null) {
                RequestScopes.runAs(ACCOUNT, REGION, () -> {
                    iam.deleteRolePolicy(targetRoleName, "invoke");
                    iam.deleteRole(targetRoleName);
                    iam.deleteRolePolicy(dataRoleName, "invoke");
                    iam.deleteRole(dataRoleName);
                });
            }
            if (events != null) { events.close(); }
            if (dynamo != null) { dynamo.close(); }
            if (sqs != null) { sqs.close(); }
        }
    }

    @Test
    void sdkEventRunsTheCfnMutationAndPersistsTheTargetRoleIdentity() {
        Target target = events.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
        assertTrue(target.arn().startsWith("arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":endpoints/graphql-api/"));
        assertEquals(OPERATION, target.appSyncParameters().graphQLOperation());
        assertEquals(roleArn(targetRoleName), target.roleArn());
        send("delivered");
        Map<String, AttributeValue> item = item("delivered");
        assertNotNull(item.get("id"));
        assertEquals("delivered", item.get("id").s());
        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + targetRoleName + "/EventBridge", item.get("caller").s());
    }

    @Test
    void aDeniedTargetRoleDeadLettersTheEventWithoutWriting() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> iam.putRolePolicy(targetRoleName, "invoke",
                policy("Deny", "arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":apis/*")));
        send("denied");
        assertTrue(item("denied").isEmpty());
        assertEquals("NO_PERMISSIONS", deadLetter().messageAttributes().get("ERROR_CODE").stringValue());
    }

    @Test
    void realVariableCoercionFailureIsDeadLetteredInsteadOfAcknowledged() {
        Target target = events.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
        events.putTargets(request -> request.rule(rule).targets(target.toBuilder()
                .inputTransformer((InputTransformer) null).input("{}").build()));
        send("invalid");
        assertTrue(item("invalid").isEmpty());
        assertEquals("INVALID_PARAMETER", deadLetter().messageAttributes().get("ERROR_CODE").stringValue());
    }

    @Test
    void realParserRejectsInvalidSettingsBeforeReplacingTheStoredTarget() {
        Target original = events.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
        List<Target> invalid = List.of(
                original.toBuilder().appSyncParameters((AppSyncParameters) null).build(),
                original.toBuilder().appSyncParameters(AppSyncParameters.builder().build()).build(),
                original.toBuilder().appSyncParameters(p -> p.graphQLOperation("mutation {")).build(),
                original.toBuilder().appSyncParameters(p -> p.graphQLOperation("query { unused }")).build(),
                original.toBuilder().appSyncParameters(p -> p.graphQLOperation(
                        "mutation First { one } mutation Second { two }")).build());
        for (Target replacement : invalid) {
            EventBridgeException error = assertThrows(EventBridgeException.class,
                    () -> events.putTargets(request -> request.rule(rule).targets(replacement)));
            assertEquals("ValidationException", error.awsErrorDetails().errorCode());
            assertEquals(400, error.statusCode());
            assertEquals(List.of(original), events.listTargetsByRule(request -> request.rule(rule)).targets());
        }
        String operation = """
                # A keyword in a comment or string is not another operation.
                mutation Publish { publish(id: "mutation { ignored }") { ...MessageFields } }
                fragment MessageFields on Message { id }
                """;
        assertEquals(0, events.putTargets(request -> request.rule(rule).targets(original.toBuilder()
                .appSyncParameters(p -> p.graphQLOperation(operation)).build())).failedEntryCount());
        assertEquals(operation, events.listTargetsByRule(request -> request.rule(rule))
                .targets().getFirst().appSyncParameters().graphQLOperation());
        String maximum = OPERATION + "#" + "x".repeat(1048576 - OPERATION.length() - 1);
        assertEquals(0, events.putTargets(request -> request.rule(rule).targets(original.toBuilder()
                .appSyncParameters(p -> p.graphQLOperation(maximum)).build())).failedEntryCount());
        assertEquals(maximum, events.listTargetsByRule(request -> request.rule(rule))
                .targets().getFirst().appSyncParameters().graphQLOperation());
    }

    private void send(String id) {
        assertEquals(0, events.putEvents(request -> request.entries(entry -> entry.source("test.appsync")
                .detailType("publish").detail("{\"id\":\"" + id + "\"}"))).failedEntryCount());
    }

    private Map<String, AttributeValue> item(String id) {
        return dynamo.getItem(request -> request.tableName(table).key(Map.of("id", AttributeValue.fromS(id)))).item();
    }

    private Message deadLetter() {
        String url = sqs.getQueueUrl(request -> request.queueName(rule + "-dlq")).queueUrl();
        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(url).messageAttributeNames("All")).messages();
        assertEquals(1, messages.size());
        return messages.getFirst();
    }

    private static String roleArn(String name) {
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    private static final String TEMPLATE = """
            {"Parameters":{"RuleName":{"Type":"String"},"TableName":{"Type":"String"},
              "TargetRole":{"Type":"String"},"DataRole":{"Type":"String"}},
             "Resources":{
              "Api":{"Type":"AWS::AppSync::GraphQLApi","Properties":{"Name":{"Ref":"RuleName"},"AuthenticationType":"AWS_IAM"}},
              "Schema":{"Type":"AWS::AppSync::GraphQLSchema","Properties":{"ApiId":{"Fn::GetAtt":["Api","ApiId"]},
                "Definition":"type Message { id: ID! caller: String } type Query { unused: String } type Mutation { publish(id: ID!): Message }"}},
              "Table":{"Type":"AWS::DynamoDB::Table","Properties":{"TableName":{"Ref":"TableName"},"BillingMode":"PAY_PER_REQUEST",
                "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],"KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}},
              "Data":{"Type":"AWS::AppSync::DataSource","DependsOn":"Schema","Properties":{"ApiId":{"Fn::GetAtt":["Api","ApiId"]},
                "Name":"items","Type":"AMAZON_DYNAMODB","ServiceRoleArn":{"Ref":"DataRole"},
                "DynamoDBConfig":{"AwsRegion":{"Ref":"AWS::Region"},"TableName":{"Ref":"Table"}}}},
              "Resolver":{"Type":"AWS::AppSync::Resolver","DependsOn":"Schema","Properties":{"ApiId":{"Fn::GetAtt":["Api","ApiId"]},
                "TypeName":"Mutation","FieldName":"publish","Kind":"UNIT","DataSourceName":{"Fn::GetAtt":["Data","Name"]},
                "ResponseMappingTemplate":"$util.toJson($ctx.result)"}},
              "Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":{"Fn::Sub":"${RuleName}-dlq"}}},
              "Rule":{"Type":"AWS::Events::Rule","DependsOn":"Resolver","Properties":{"Name":{"Ref":"RuleName"},
                "EventPattern":{"source":["test.appsync"]},"Targets":[{"Id":"mutation","Arn":{"Fn::GetAtt":["Api","GraphQLEndpointArn"]},
                  "RoleArn":{"Ref":"TargetRole"},"DeadLetterConfig":{"Arn":{"Fn::GetAtt":["Queue","Arn"]}}}]}}}}
            """;
}
