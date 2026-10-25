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
        terminateLater(runInstances(user));
    }

    /**
     * EC2 checks {@code ec2:CreateTags} on each resource a tag specification names, as its examples scope the grant,
     * so one scoped to instances covers an instance's tags and not a volume's.
     */
    @Test
    void anInstancesTagsAreCheckedOnTheInstanceAndAVolumesOnTheVolume() {
        User user = user("tag-instance-scoped");
        user.policy(allow("ec2:RunInstances") + ",{\"Effect\":\"Allow\",\"Action\":\"ec2:CreateTags\","
                + "\"Resource\":\"arn:aws:ec2:us-east-1:" + ACCOUNT + ":instance/*\","
                + "\"Condition\":{\"StringEquals\":{\"ec2:CreateAction\":\"RunInstances\"}}}");
        terminateLater(runInstances(user, "instance", "blue"));
        refused(runInstances(user, "instance", "blue", "volume", "blue").then(), "ec2:CreateTags");
    }

    /** Each resource type's tags are checked apart, so a volume's cannot stand in for an instance's. */
    @Test
    void eachResourceTypesTagsAreCheckedApart() {
        User user = user("tag-instance-sets");
        user.policy(allow("ec2:RunInstances") + ",{\"Effect\":\"Allow\",\"Action\":\"ec2:CreateTags\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"}}}");
        refused(runInstances(user, "instance", "red", "volume", "blue").then(), "ec2:CreateTags");
        terminateLater(runInstances(user, "instance", "blue", "volume", "blue"));
    }

    /** The create's own action is checked with each resource's tags apart too. */
    @Test
    void theCreatesOwnActionIsCheckedWithEachResourcesTagsApart() {
        User user = user("tag-vpc");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ec2:CreateVpc\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"}}}," + allow("ec2:CreateTags"));
        refused(createVpc(user, "vpc", "red", "vpc-flow-log", "blue").then(), "ec2:CreateVpc");
        String vpcId = createVpc(user, "vpc", "blue").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteVpc").formParam("VpcId", vpcId).post("/"));
    }

    /**
     * A tag the handler would not apply is not taken for one the resource gets: SQS's handler stops at the first
     * missing number, SNS's reads {@code Tags.member.N} only.
     */
    @Test
    void aTagTheHandlerWouldNotApplyDoesNotMeetAPolicyRequiringIt() {
        User user = user("tag-unread");
        String name = "tag-unread-" + suffix();
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"sqs:CreateQueue\",\"sns:CreateTopic\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"}}}," + allow("sqs:TagQueue") + ","
                + allow("sns:TagResource"));
        refused(user.query("sqs", "CreateQueue").formParam("QueueName", name)
                .formParam("Tag.2.Key", "team").formParam("Tag.2.Value", "blue").post("/").then(), "sqs:CreateQueue");
        refused(user.query("sns", "CreateTopic").formParam("Name", name)
                .formParam("Tags.1.Key", "team").formParam("Tags.1.Value", "blue").post("/").then(), "sns:CreateTopic");
        deleteQueueLater(createQueue(user, name, "blue"));
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

    /** A function's tags are checked on the function its body names, as its own action is. */
    @Test
    void aFunctionsTagsAreCheckedOnTheFunctionItCreates() {
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "tag-fn-" + suffix(),
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}");
        User user = user("tag-function-scoped");
        String name = "tag-fn-" + suffix();
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + name;
        user.policy(allow("lambda:CreateFunction") + ",{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\","
                + "\"Resource\":\"" + role + "\"},{\"Effect\":\"Allow\",\"Action\":\"lambda:TagResource\","
                + "\"Resource\":\"" + function + "\"},{\"Effect\":\"Deny\",\"Action\":\"lambda:CreateFunction\","
                + "\"Resource\":\"" + function + "-denied\"}");
        refusedRest(user.rest("lambda").body(function(name + "-other", role)).post("/2015-03-31/functions").then(),
                "lambda:TagResource");
        refusedRest(user.rest("lambda").body(function(name + "-denied", role)).post("/2015-03-31/functions").then(),
                "lambda:CreateFunction");
        user.rest("lambda").body(function(name, role)).post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
    }

    /**
     * CodeDeploy's handler reads a tag's key and value in either case, so the tagging action is checked for both,
     * and the create's own action sees them.
     */
    @Test
    void anApplicationsTagsInEitherCaseNeedTagResource() {
        User user = user("tag-application");
        String application = "tag-app-" + suffix();
        String create = "{\"applicationName\":\"" + application + "\",\"tags\":[{\"key\":\"team\",\"value\":\"red\"}]}";
        user.policy(allow("codedeploy:CreateApplication"));
        refusedJson(user.json("codedeploy", "CodeDeploy_20141006.CreateApplication", "1.1").body(create).post("/").then(),
                "codedeploy:TagResource");
        // The handler keeps a key that is a number as its text, so it needs the tagging action too.
        refusedJson(user.json("codedeploy", "CodeDeploy_20141006.CreateApplication", "1.1")
                .body("{\"applicationName\":\"" + application + "\",\"tags\":[{\"key\":123,\"value\":\"red\"}]}")
                .post("/").then(), "codedeploy:TagResource");

        user.policy("{\"Effect\":\"Allow\",\"Action\":\"codedeploy:CreateApplication\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"red\"}}}," + allow("codedeploy:TagResource"));
        user.json("codedeploy", "CodeDeploy_20141006.CreateApplication", "1.1").body(create).post("/").then()
                .statusCode(200);
        cleanup.register(() -> admin("codedeploy").header("X-Amz-Target", "CodeDeploy_20141006.DeleteApplication")
                .contentType("application/x-amz-json-1.1").body("{\"applicationName\":\"" + application + "\"}").post("/"));

        // The handler iterates an object's values as tags, so a deny on one holds however they are shaped.
        user.policy(allow("codedeploy:CreateApplication") + "," + allow("codedeploy:TagResource")
                + ",{\"Effect\":\"Deny\",\"Action\":\"codedeploy:CreateApplication\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"red\"}}}");
        refusedJson(user.json("codedeploy", "CodeDeploy_20141006.CreateApplication", "1.1")
                .body("{\"applicationName\":\"" + application + "-object\",\"tags\":{\"0\":{\"key\":\"team\",\"value\":\"red\"}}}")
                .post("/").then(), "codedeploy:CreateApplication");
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

    private static String function(String name, String role) {
        return """
                {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s",
                 "Tags":{"team":"blue"}}""".formatted(name, role);
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
        return runInstances(user, "instance", "blue");
    }

    /** An instance launched with a tag specification for each resource type given, each tagging its team. */
    private static Response runInstances(User user, String... typesAndTeams) {
        return tagSpecifications(user.query("ec2", "RunInstances").formParam("ImageId", IMAGE)
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1"),
                typesAndTeams).post("/");
    }

    private static Response createVpc(User user, String... typesAndTeams) {
        return tagSpecifications(user.query("ec2", "CreateVpc").formParam("CidrBlock", "10.20.0.0/16"), typesAndTeams)
                .post("/");
    }

    private static RequestSpecification tagSpecifications(RequestSpecification request, String... typesAndTeams) {
        for (int i = 0; i < typesAndTeams.length; i += 2) {
            String specification = "TagSpecification." + (i / 2 + 1);
            request = request.formParam(specification + ".ResourceType", typesAndTeams[i])
                    .formParam(specification + ".Tag.1.Key", "team")
                    .formParam(specification + ".Tag.1.Value", typesAndTeams[i + 1]);
        }
        return request;
    }

    private void terminateLater(Response launched) {
        String instanceId = launched.then().statusCode(200).extract().xmlPath()
                .getString("RunInstancesResponse.instancesSet.item.instanceId");
        cleanup.register(() -> admin("ec2").formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/"));
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
