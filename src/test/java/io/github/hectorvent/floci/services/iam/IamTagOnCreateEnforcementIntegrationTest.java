package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that tags the resource it creates also needs the service's tagging action, as AWS checks it: the
 * SNS API reference has "To be able to tag a topic on creation, you must have the {@code sns:CreateTopic}
 * and {@code sns:TagResource} permissions." Without it a caller allowed to create but not to tag could set
 * the tags a tag-based policy trusts.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamTagOnCreateEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    private static final String IMAGE = "ami-0abcdef1234567890";
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aQueueCreatedWithTagsNeedsTagQueueOverEitherProtocol() {
        User user = user("tag-queue");
        String queue = "tag-queue-" + suffix();

        // A queue created without tags needs only its own action.
        user.policy(allow("sqs:CreateQueue"));
        deleteQueueLater(user.query("sqs", "CreateQueue").formParam("QueueName", queue + "-plain").post("/"));
        refused(user.query("sqs", "CreateQueue").formParam("QueueName", queue)
                .formParam("Tag.1.Key", "team").formParam("Tag.1.Value", "blue").post("/").then(), "sqs:TagQueue");
        refusedJson(user.json("sqs", "AmazonSQS.CreateQueue", "1.0")
                .body("{\"QueueName\":\"" + queue + "\",\"tags\":{\"team\":\"blue\"}}").post("/").then(), "sqs:TagQueue");

        // Allowed to create but not to tag, the caller is refused the create, not the tagging.
        user.policy(allow("sqs:TagQueue"));
        refused(user.query("sqs", "CreateQueue").formParam("QueueName", queue)
                .formParam("Tag.1.Key", "team").formParam("Tag.1.Value", "blue").post("/").then(), "sqs:CreateQueue");

        user.policy(allow("sqs:CreateQueue") + "," + allow("sqs:TagQueue"));
        deleteQueueLater(user.query("sqs", "CreateQueue").formParam("QueueName", queue)
                .formParam("Tag.1.Key", "team").formParam("Tag.1.Value", "blue").post("/"));
    }

    /** The create's own action sees the tags too, as the Service Reference lists the keys for it. */
    @Test
    void theCreatesOwnActionIsCheckedWithTheTagsItCarries() {
        User user = user("tag-create-condition");
        String queue = "tag-condition-" + suffix();
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"sqs:CreateQueue\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"}}}," + allow("sqs:TagQueue"));
        refused(createQueue(user, queue, "red").then(), "sqs:CreateQueue");
        refused(user.query("sqs", "CreateQueue").formParam("QueueName", queue).post("/").then(), "sqs:CreateQueue");
        refusedJson(user.json("sqs", "AmazonSQS.CreateQueue", "1.0")
                .body("{\"QueueName\":\"" + queue + "\",\"tags\":{\"team\":\"red\"}}").post("/").then(), "sqs:CreateQueue");
        deleteQueueLater(createQueue(user, queue, "blue"));

        // The usual guard: no create without the tag. It must not refuse a create that carries it.
        user.policy(allow("sqs:CreateQueue") + "," + allow("sqs:TagQueue") + ",{\"Effect\":\"Deny\","
                + "\"Action\":\"sqs:CreateQueue\",\"Resource\":\"*\",\"Condition\":{\"Null\":{\"aws:RequestTag/team\":\"true\"}}}");
        refused(user.query("sqs", "CreateQueue").formParam("QueueName", queue + "-untagged").post("/").then(),
                "sqs:CreateQueue");
        deleteQueueLater(createQueue(user, queue + "-guarded", "blue"));
    }

    /**
     * Only an action the Service Reference gives the request's tags to sees them: Cognito's
     * {@code CreateIdentityPool} does, {@code UpdateIdentityPool} does not, though both carry them.
     */
    @Test
    void anActionTheServiceReferenceGivesNoRequestTagsIsCheckedWithout() {
        User user = user("tag-pool");
        String pool = "tag_pool_" + suffix();
        String created = admin("cognito-identity").header("X-Amz-Target", "AWSCognitoIdentityService.CreateIdentityPool")
                .contentType("application/x-amz-json-1.1")
                .body("{\"IdentityPoolName\":\"" + pool + "\",\"AllowUnauthenticatedIdentities\":false}")
                .post("/").then().statusCode(200).extract().path("IdentityPoolId");
        cleanup.register(() -> admin("cognito-identity").header("X-Amz-Target", "AWSCognitoIdentityService.DeleteIdentityPool")
                .contentType("application/x-amz-json-1.1").body("{\"IdentityPoolId\":\"" + created + "\"}").post("/"));
        user.policy(allow("cognito-identity:CreateIdentityPool") + "," + allow("cognito-identity:UpdateIdentityPool") + ","
                + allow("cognito-identity:TagResource") + ",{\"Effect\":\"Deny\",\"Action\":["
                + "\"cognito-identity:CreateIdentityPool\",\"cognito-identity:UpdateIdentityPool\"],\"Resource\":\"*\","
                + "\"Condition\":{\"ForAnyValue:StringEquals\":{\"aws:TagKeys\":[\"env\"]}}}");

        refusedJson(user.json("cognito-identity", "AWSCognitoIdentityService.CreateIdentityPool", "1.1")
                .body("{\"IdentityPoolName\":\"" + pool + "_new\",\"AllowUnauthenticatedIdentities\":false,"
                        + "\"IdentityPoolTags\":{\"env\":\"dev\"}}").post("/").then(), "cognito-identity:CreateIdentityPool");
        user.json("cognito-identity", "AWSCognitoIdentityService.UpdateIdentityPool", "1.1")
                .body("{\"IdentityPoolId\":\"" + created + "\",\"IdentityPoolName\":\"" + pool + "\","
                        + "\"AllowUnauthenticatedIdentities\":false,\"IdentityPoolTags\":{\"env\":\"dev\"}}")
                .post("/").then().statusCode(200);
    }

    @Test
    void theTaggingActionIsCheckedWithTheTagsTheRequestCarries() {
        User user = user("tag-topic");
        String topic = "tag-topic-" + suffix();
        user.policy(allow("sns:CreateTopic") + ",{\"Effect\":\"Allow\",\"Action\":\"sns:TagResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"},"
                + "\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}}");

        refused(createTopic(user, topic, Map.of("team", "red")), "sns:TagResource");
        refused(createTopic(user, topic, Map.of("team", "blue", "env", "dev")), "sns:TagResource");
        createTopic(user, topic, Map.of("team", "blue")).statusCode(200);
        cleanup.register(() -> admin("sns").formParam("Action", "DeleteTopic")
                .formParam("TopicArn", "arn:aws:sns:us-east-1:" + ACCOUNT + ":" + topic).post("/"));
    }

    @Test
    void aJsonRequestsTagsNeedTheTaggingAction() {
        User user = user("tag-json");
        String table = "tag-table-" + suffix();
        String createTable = """
                {"TableName":"%s","BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "Tags":[{"Key":"team","Value":"blue"}]}""".formatted(table);
        String createKey = "{\"Tags\":[{\"TagKey\":\"team\",\"TagValue\":\"blue\"}]}";

        user.policy(allow("dynamodb:CreateTable") + "," + allow("kms:CreateKey"));
        refusedJson(user.json("dynamodb", "DynamoDB_20120810.CreateTable", "1.0").body(createTable).post("/").then(),
                "dynamodb:TagResource");
        refusedJson(user.json("kms", "TrentService.CreateKey", "1.1").body(createKey).post("/").then(),
                "kms:TagResource");

        user.policy(allow("dynamodb:CreateTable") + "," + allow("dynamodb:TagResource") + ","
                + allow("kms:CreateKey") + "," + allow("kms:TagResource"));
        user.json("dynamodb", "DynamoDB_20120810.CreateTable", "1.0").body(createTable).post("/").then().statusCode(200);
        cleanup.register(() -> admin("dynamodb").header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0").body("{\"TableName\":\"" + table + "\"}").post("/"));
        user.json("kms", "TrentService.CreateKey", "1.1").body(createKey).post("/").then().statusCode(200);
    }

    /**
     * The tagging action is found by the operation, not by the action it is authorized as: Budgets'
     * {@code CreateBudget} is authorized as {@code budgets:ModifyBudget}.
     */
    @Test
    void aBudgetCreatedWithTagsNeedsTagResourceThoughItIsAuthorizedAsModifyBudget() {
        User user = user("tag-budget");
        String budget = "tag-budget-" + suffix();
        String create = """
                {"AccountId":"%s","Budget":{"BudgetName":"%s","BudgetType":"COST","TimeUnit":"MONTHLY",
                 "BudgetLimit":{"Amount":"100","Unit":"USD"}},"ResourceTags":[{"Key":"team","Value":"blue"}]}"""
                .formatted(ACCOUNT, budget);
        String modify = allow("budgets:ModifyBudget");

        user.policy(modify);
        refusedJson(user.json("budgets", "AWSBudgetServiceGateway.CreateBudget", "1.1").body(create).post("/").then(),
                "budgets:TagResource");

        user.policy(modify + "," + allow("budgets:TagResource"));
        user.json("budgets", "AWSBudgetServiceGateway.CreateBudget", "1.1").body(create).post("/").then()
                .statusCode(200);
        cleanup.register(() -> admin("budgets").header("X-Amz-Target", "AWSBudgetServiceGateway.DeleteBudget")
                .contentType("application/x-amz-json-1.1")
                .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + budget + "\"}").post("/"));
    }

    @Test
    void anInstanceLaunchedWithTagsNeedsCreateTagsForRunInstances() {
        User user = user("tag-instance");
        // The tagging permission is scoped to the action that creates the resource, as EC2's examples scope it.
        user.policy(allow("ec2:RunInstances") + "," + createTagsFor("CreateVolume"));
        refused(runInstances(user).then(), "ec2:CreateTags");

        user.policy(allow("ec2:RunInstances") + "," + createTagsFor("RunInstances"));
        String instanceId = runInstances(user).then().statusCode(200).extract().xmlPath()
                .getString("RunInstancesResponse.instancesSet.item.instanceId");
        cleanup.register(() -> admin("ec2").formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/"));
    }

    @Test
    void aRoleCreatedWithTagsNeedsTagRole() {
        User user = user("tag-role");
        String role = "tag-role-" + suffix();
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

        user.policy(allow("iam:CreateRole"));
        refused(user.query("iam", "CreateRole").formParam("RoleName", role).formParam("AssumeRolePolicyDocument", trust)
                .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "blue").post("/").then(),
                "iam:TagRole");

        user.policy(allow("iam:CreateRole") + "," + allow("iam:TagRole"));
        user.query("iam", "CreateRole").formParam("RoleName", role).formParam("AssumeRolePolicyDocument", trust)
                .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "blue").post("/").then()
                .statusCode(200);
        cleanup.register(() -> admin("iam").formParam("Action", "DeleteRole").formParam("RoleName", role).post("/"));
    }

    @Test
    void aFunctionCreatedWithTagsNeedsTagResourceOverItsRestRoute() {
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "tag-fn-" + suffix(),
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}");
        User user = user("tag-function");
        String name = "tag-fn-" + suffix();
        String body = """
                {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s",
                 "Tags":{"team":"blue"}}""".formatted(name, role);
        String passRole = "{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"" + role + "\"}";

        user.policy(allow("lambda:CreateFunction") + "," + passRole);
        refusedRest(user.rest("lambda").body(body).post("/2015-03-31/functions").then(), "lambda:TagResource");

        user.policy(allow("lambda:CreateFunction") + "," + passRole + "," + allow("lambda:TagResource"));
        user.rest("lambda").body(body).post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
    }

    @Test
    void anAlarmPutWithTagsOverCborNeedsTagResource() throws IOException {
        User user = user("tag-alarm");
        String alarm = "tag-alarm-" + suffix();
        byte[] body = CBOR_MAPPER.writeValueAsBytes(Map.of("AlarmName", alarm, "MetricName", "Errors",
                "Namespace", "Tag/OnCreate", "Statistic", "Sum", "Period", 60, "EvaluationPeriods", 1,
                "Threshold", 1.0, "ComparisonOperator", "GreaterThanThreshold",
                "Tags", List.of(Map.of("Key", "team", "Value", "blue"))));

        user.policy(allow("cloudwatch:PutMetricAlarm"));
        Response denied = user.cbor("monitoring").body(body)
                .post("/service/GraniteServiceVersion20100801/operation/PutMetricAlarm");
        JsonNode error = CBOR_MAPPER.readTree(denied.then().statusCode(403).extract().asByteArray());
        assertTrue(error.path("message").asText().contains("cloudwatch:TagResource"), error.toString());

        user.policy(allow("cloudwatch:PutMetricAlarm") + "," + allow("cloudwatch:TagResource"));
        user.cbor("monitoring").body(body).post("/service/GraniteServiceVersion20100801/operation/PutMetricAlarm")
                .then().statusCode(200);
        cleanup.register(() -> admin("monitoring").formParam("Action", "DeleteAlarms")
                .formParam("AlarmNames.member.1", alarm).post("/"));
    }

    private static ValidatableResponse createTopic(User user, String name, Map<String, String> tags) {
        RequestSpecification request = user.query("sns", "CreateTopic").formParam("Name", name);
        int index = 1;
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            request = request.formParam("Tags.member." + index + ".Key", tag.getKey())
                    .formParam("Tags.member." + index + ".Value", tag.getValue());
            index++;
        }
        return request.post("/").then();
    }

    private static Response createQueue(User user, String name, String team) {
        return user.query("sqs", "CreateQueue").formParam("QueueName", name)
                .formParam("Tag.1.Key", "team").formParam("Tag.1.Value", team).post("/");
    }

    private static Response runInstances(User user) {
        return user.query("ec2", "RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("TagSpecification.1.ResourceType", "instance")
                .formParam("TagSpecification.1.Tag.1.Key", "team").formParam("TagSpecification.1.Tag.1.Value", "blue")
                .post("/");
    }

    private void deleteQueueLater(Response created) {
        String url = created.then().statusCode(200).extract().xmlPath()
                .getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        cleanup.register(() -> admin("sqs").formParam("Action", "DeleteQueue").formParam("QueueUrl", url).post("/"));
    }

    private static String createTagsFor(String createAction) {
        return "{\"Effect\":\"Allow\",\"Action\":\"ec2:CreateTags\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"ec2:CreateAction\":\"" + createAction + "\"}}}";
    }

    /** A Query service refuses with {@code AccessDenied}, a 403, naming the permission that is missing. */
    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString("not authorized to perform: " + action));
    }

    /** A JSON-protocol service refuses with {@code AccessDeniedException}, which those protocols send as a 400. */
    private static void refusedJson(ValidatableResponse response, String action) {
        response.statusCode(400).body(containsString("AccessDeniedException"))
                .body(containsString("not authorized to perform: " + action));
    }

    private static void refusedRest(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString("not authorized to perform: " + action))
                .body(not(containsString("iam:PassRole")));
    }

    private static String allow(String action) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"*\"}";
    }

    private User user(String name) {
        String userName = name + "-" + suffix();
        return new User(userName, EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"),
                userName).accessKeyId());
    }

    private static RequestSpecification admin(String service) {
        return given().header("Authorization", auth(ADMIN, service));
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** An IAM user whose one inline policy each test replaces. */
    private record User(String name, String accessKeyId) {

        void policy(String statements) {
            admin("iam").formParam("Action", "PutUserPolicy").formParam("UserName", name)
                    .formParam("PolicyName", "p")
                    .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}")
                    .post("/").then().statusCode(200);
        }

        RequestSpecification query(String service, String action) {
            return given().header("Authorization", auth(accessKeyId, service)).formParam("Action", action);
        }

        RequestSpecification json(String service, String target, String version) {
            return given().header("Authorization", auth(accessKeyId, service)).header("X-Amz-Target", target)
                    .contentType("application/x-amz-json-" + version);
        }

        RequestSpecification rest(String service) {
            return given().header("Authorization", auth(accessKeyId, service)).contentType("application/json");
        }

        RequestSpecification cbor(String service) {
            return given().header("Authorization", auth(accessKeyId, service)).header("smithy-protocol", "rpc-v2-cbor")
                    .contentType("application/cbor").accept("application/cbor");
        }
    }
}
