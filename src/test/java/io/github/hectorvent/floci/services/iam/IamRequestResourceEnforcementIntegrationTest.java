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
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request of a service whose ARNs the vendored table of request resources gives is checked on the resource it
 * names, the ARN its handler mints, so a grant scoped to the resource allows it, and the same grant refuses a
 * request naming another; a deny scoped to the resource holds. Without the table these were checked on {@code *}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamRequestResourceEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ELB = "arn:aws:elasticloadbalancing:" + REGION + ":" + ACCOUNT + ":";
    /** The AWS Load Balancer Controller's create, tag-on-create and listener statements (its policy v2.14.0). */
    private static final String CONTROLLER_CREATE = "{\"Effect\":\"Allow\",\"Action\":[\"elasticloadbalancing:CreateLoadBalancer\","
            + "\"elasticloadbalancing:CreateTargetGroup\"],\"Resource\":\"*\","
            + "\"Condition\":{\"Null\":{\"aws:RequestTag/elbv2.k8s.aws/cluster\":\"false\"}}}";
    private static final String CONTROLLER_LISTENERS = "{\"Effect\":\"Allow\",\"Action\":[\"elasticloadbalancing:CreateListener\","
            + "\"elasticloadbalancing:DeleteListener\",\"elasticloadbalancing:CreateRule\",\"elasticloadbalancing:DeleteRule\"],"
            + "\"Resource\":\"*\"}";
    private static final String CONTROLLER_TAG_LISTENERS = "{\"Effect\":\"Allow\",\"Action\":[\"elasticloadbalancing:AddTags\","
            + "\"elasticloadbalancing:RemoveTags\"],\"Resource\":[\"arn:aws:elasticloadbalancing:*:*:listener/net/*/*/*\","
            + "\"arn:aws:elasticloadbalancing:*:*:listener/app/*/*/*\",\"arn:aws:elasticloadbalancing:*:*:listener-rule/net/*/*/*\","
            + "\"arn:aws:elasticloadbalancing:*:*:listener-rule/app/*/*/*\"]}";
    private static final String CONTROLLER_TAG_ON_CREATE = "{\"Effect\":\"Allow\",\"Action\":[\"elasticloadbalancing:AddTags\"],"
            + "\"Resource\":[\"arn:aws:elasticloadbalancing:*:*:targetgroup/*/*\",\"arn:aws:elasticloadbalancing:*:*:loadbalancer/net/*/*\","
            + "\"arn:aws:elasticloadbalancing:*:*:loadbalancer/app/*/*\"],\"Condition\":{\"StringEquals\":"
            + "{\"elasticloadbalancing:CreateAction\":[\"CreateTargetGroup\",\"CreateLoadBalancer\"]},"
            + "\"Null\":{\"aws:RequestTag/elbv2.k8s.aws/cluster\":\"false\"}}}";
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final String PASS = "{\\\"StartAt\\\":\\\"P\\\",\\\"States\\\":{\\\"P\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aTargetGroupIsCheckedOnItsArnAndANewOneOnItsName() {
        String name = "res-tg-" + suffix();
        String arn = admin("elasticloadbalancing").formParam("Action", "CreateTargetGroup").formParam("Version", "2015-12-01")
                .formParam("Name", name).formParam("Protocol", "HTTP").formParam("Port", "80").formParam("TargetType", "ip")
                .post("/").then().statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> admin("elasticloadbalancing").formParam("Action", "DeleteTargetGroup")
                .formParam("Version", "2015-12-01").formParam("TargetGroupArn", arn).post("/"));
        assertEquals(arn.substring(0, arn.lastIndexOf('/')), "arn:aws:elasticloadbalancing:" + REGION + ":" + ACCOUNT
                + ":targetgroup/" + name);

        User user = user("res-tg");
        user.policy(allowOn("elasticloadbalancing:ModifyTargetGroupAttributes", arn));
        modifyAttributes(user, arn).statusCode(200);
        refused(modifyAttributes(user, arn + "0"), "elasticloadbalancing:ModifyTargetGroupAttributes");

        // A new target group's id is the service's to mint, so a grant on its name covers it.
        String created = "res-new-" + suffix();
        user.policy(allowOn("elasticloadbalancing:CreateTargetGroup", "arn:aws:elasticloadbalancing:" + REGION + ":"
                + ACCOUNT + ":targetgroup/" + created + "/*"));
        refused(createTargetGroup(user, created + "-other"), "elasticloadbalancing:CreateTargetGroup");
        String createdArn = createTargetGroup(user, created).statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> admin("elasticloadbalancing").formParam("Action", "DeleteTargetGroup")
                .formParam("Version", "2015-12-01").formParam("TargetGroupArn", createdArn).post("/"));
    }

    /**
     * The AWS Load Balancer Controller's policy creates on {@code *} with its cluster tag, and tags at creation only
     * a target group or an application or network load balancer, as {@code elasticloadbalancing:CreateAction}
     * says; these statements are the published policy's.
     */
    @Test
    void theLoadBalancerControllersPolicyCreatesTaggedTargetGroupsAndLoadBalancers() {
        User user = user("res-alb-controller");
        user.policy(CONTROLLER_CREATE + "," + CONTROLLER_TAG_ON_CREATE);
        String group = "res-ctl-" + suffix();
        String arn = tagged(user.query("elasticloadbalancing", "CreateTargetGroup").formParam("Version", "2015-12-01")
                .formParam("Name", group).formParam("Protocol", "HTTP").formParam("Port", "80").formParam("TargetType", "ip"))
                .post("/").then().statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> admin("elasticloadbalancing").formParam("Action", "DeleteTargetGroup")
                .formParam("Version", "2015-12-01").formParam("TargetGroupArn", arn).post("/"));
        refused(createTargetGroup(user, group + "-untagged"), "elasticloadbalancing:CreateTargetGroup");

        // Authorized, a load balancer with no subnets is refused by Elastic Load Balancing, not by IAM.
        tagged(user.query("elasticloadbalancing", "CreateLoadBalancer").formParam("Version", "2015-12-01")
                .formParam("Name", "res-ctl-alb")).post("/").then().statusCode(400).body(containsString("ValidationError"));
        tagged(user.query("elasticloadbalancing", "CreateLoadBalancer").formParam("Version", "2015-12-01")
                .formParam("Name", "res-ctl-nlb").formParam("Type", "network")).post("/").then().statusCode(400)
                .body(containsString("ValidationError"));
        refused(user.query("elasticloadbalancing", "CreateLoadBalancer").formParam("Version", "2015-12-01")
                .formParam("Name", "res-ctl-untagged").post("/").then(), "elasticloadbalancing:CreateLoadBalancer");
    }

    /**
     * A listener's and a rule's tags go to the new listener and rule, so the controller's grant of {@code AddTags} on
     * listeners and rules covers a tagged {@code CreateListener} and {@code CreateRule}, which act on a load balancer
     * and a listener.
     */
    @Test
    void theLoadBalancerControllersPolicyCreatesTaggedListenersAndRules() {
        User user = user("res-alb-listener");
        user.policy(CONTROLLER_LISTENERS);
        refused(createListener(user), "elasticloadbalancing:AddTags");
        refused(createRule(user), "elasticloadbalancing:AddTags");

        // Authorized, a listener of no load balancer and a rule of no listener are refused by Elastic Load Balancing.
        user.policy(CONTROLLER_LISTENERS + "," + CONTROLLER_TAG_LISTENERS);
        createListener(user).statusCode(400).body(containsString("LoadBalancerNotFound"));
        createRule(user).statusCode(400).body(containsString("ListenerNotFound"));
    }

    /**
     * A v2 {@code CreateLoadBalancer} of a {@code Type} outside the enum names no v2 load balancer for IAM to check,
     * and Elastic Load Balancing refuses it, as AWS does, so a grant on a classic load balancer's name does not
     * create a v2 one under another.
     */
    @Test
    void aLoadBalancerOfATypeOutsideTheEnumIsNotCreatedUnderAnotherName() {
        String name = "res-protected-" + suffix();
        User user = user("res-lb-type");
        user.policy(allowOn("elasticloadbalancing:CreateLoadBalancer", ELB + "loadbalancer/res-allowed"));
        user.query("elasticloadbalancing", "CreateLoadBalancer").formParam("Version", "2015-12-01")
                .formParam("Name", name).formParam("Type", "other").formParam("LoadBalancerName", "res-allowed")
                .formParam("Subnets.member.1", "subnet-a").formParam("Subnets.member.2", "subnet-b").post("/").then()
                .statusCode(400).body(containsString("ValidationError"))
                .body(containsString("Member must satisfy enum value set: [application, network, gateway]"));
        admin("elasticloadbalancing").formParam("Action", "DescribeLoadBalancers").formParam("Version", "2015-12-01")
                .post("/").then().statusCode(200).body(not(containsString(name)));
    }

    @Test
    void aStateMachineIsCheckedOnItsArnAndADenyOnItHolds() {
        String name = "res-sm-" + suffix();
        String arn = createStateMachine(name);
        assertEquals("arn:aws:states:" + REGION + ":" + ACCOUNT + ":stateMachine:" + name, arn);

        User user = user("res-sm");
        user.policy(allowOn("states:DescribeStateMachine", arn));
        describeStateMachine(user, arn).statusCode(200);
        refusedJson(describeStateMachine(user, arn + "-other"), "states:DescribeStateMachine");

        user.policy(allowOn("states:DescribeStateMachine", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"states:*\","
                + "\"Resource\":\"" + arn + "\"}");
        refusedJson(describeStateMachine(user, arn), "states:DescribeStateMachine");
    }

    /**
     * Over the Smithy RPC v2 route a request is checked for the operation its path dispatches, not one an
     * {@code X-Amz-Target} it carries names: a grant on one state machine allows describing it there, and a
     * request whose header names a create of that machine does not describe another.
     */
    @Test
    void aStateMachineIsCheckedForTheOperationTheRpcV2RouteDispatches() throws IOException {
        String allowedName = "res-rpc-" + suffix();
        String allowed = createStateMachine(allowedName);
        String other = createStateMachine("res-rpc-other-" + suffix());
        User user = user("res-rpc");
        user.policy(allowOn("states:DescribeStateMachine", allowed));
        user.cbor("states").body(CBOR_MAPPER.writeValueAsBytes(Map.of("stateMachineArn", allowed)))
                .post("/service/SFN/operation/DescribeStateMachine").then().statusCode(200);

        byte[] body = CBOR_MAPPER.writeValueAsBytes(Map.of("name", allowedName, "stateMachineArn", other));
        JsonNode error = CBOR_MAPPER.readTree(user.cbor("states")
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine").body(body)
                .post("/service/SFN/operation/DescribeStateMachine").then().statusCode(403).extract().asByteArray());
        assertEquals("AccessDeniedException", error.path("__type").asText());
        assertTrue(error.path("message").asText().contains("states:DescribeStateMachine"), error.toString());
    }

    /**
     * {@code StartExecution} runs the machine before a Step Functions Local test case's {@code #}, so a grant on
     * that machine allows a start that names a test case, and a deny on it holds.
     */
    @Test
    void aStateMachinesTestCaseIsCheckedOnTheMachineItRuns() {
        String arn = createStateMachine("res-tc-" + suffix());
        User user = user("res-tc");
        user.policy(allowOn("states:StartExecution", arn));
        startExecution(user, arn + "#").statusCode(200);

        user.policy(allowOn("states:StartExecution", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"states:StartExecution\","
                + "\"Resource\":\"" + arn + "\"}");
        refusedJson(startExecution(user, arn + "#"), "states:StartExecution");
    }

    /**
     * Step Functions checks a version or an alias on its state machine's ARN, so a grant on the state machine
     * allows describing its alias and starting it through either, and a deny on it holds for both.
     */
    @Test
    void aVersionOrAnAliasIsCheckedOnItsStateMachine() {
        String arn = createStateMachine("res-alias-" + suffix());
        String version = admin("states").header("X-Amz-Target", "AWSStepFunctions.PublishStateMachineVersion")
                .contentType("application/x-amz-json-1.0").body("{\"stateMachineArn\":\"" + arn + "\"}")
                .post("/").then().statusCode(200).extract().path("stateMachineVersionArn");
        String alias = admin("states").header("X-Amz-Target", "AWSStepFunctions.CreateStateMachineAlias")
                .contentType("application/x-amz-json-1.0").body("{\"name\":\"PROD\",\"routingConfiguration\":"
                        + "[{\"stateMachineVersionArn\":\"" + version + "\",\"weight\":100}]}")
                .post("/").then().statusCode(200).extract().path("stateMachineAliasArn");
        assertEquals(arn + ":PROD", alias);

        User user = user("res-alias");
        user.policy(allowOn("states:DescribeStateMachineAlias", arn) + "," + allowOn("states:StartExecution", arn));
        user.json("states", "AWSStepFunctions.DescribeStateMachineAlias", "1.0")
                .body("{\"stateMachineAliasArn\":\"" + alias + "\"}").post("/").then().statusCode(200);
        startExecution(user, alias).statusCode(200);
        startExecution(user, version).statusCode(200);

        user.policy(allowOn("states:StartExecution", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"states:StartExecution\","
                + "\"Resource\":\"" + arn + "\"}");
        refusedJson(startExecution(user, alias), "states:StartExecution");
        refusedJson(startExecution(user, version), "states:StartExecution");
    }

    @Test
    void aUserPoolIsCheckedOnTheArnItsIdNames() {
        String id = admin("cognito-idp").header("X-Amz-Target", "AWSCognitoIdentityProviderService.CreateUserPool")
                .contentType("application/x-amz-json-1.1").body("{\"PoolName\":\"res-pool-" + suffix() + "\"}")
                .post("/").then().statusCode(200).extract().path("UserPool.Id");
        String arn = admin("cognito-idp").header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
                .contentType("application/x-amz-json-1.1").body("{\"UserPoolId\":\"" + id + "\"}")
                .post("/").then().statusCode(200).extract().path("UserPool.Arn");
        cleanup.register(() -> admin("cognito-idp").header("X-Amz-Target", "AWSCognitoIdentityProviderService.DeleteUserPool")
                .contentType("application/x-amz-json-1.1").body("{\"UserPoolId\":\"" + id + "\"}").post("/"));
        assertEquals("arn:aws:cognito-idp:" + REGION + ":" + ACCOUNT + ":userpool/" + id, arn);

        User user = user("res-pool");
        user.policy(allowOn("cognito-idp:DescribeUserPool", arn));
        describeUserPool(user, id).statusCode(200);
        refusedJson(describeUserPool(user, id + "x"), "cognito-idp:DescribeUserPool");
    }

    /** A repository is checked on the ARN its name gives, whether or not it exists. */
    @Test
    void aRepositoryIsCheckedOnTheArnItsNameGives() {
        String name = "res-repo-" + suffix();
        User user = user("res-repo");
        user.policy(allowOn("ecr:DescribeRepositories", "arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/" + name));
        describeRepositories(user, name).statusCode(400).body(containsString("RepositoryNotFoundException"));
        refusedJson(describeRepositories(user, name + "-other"), "ecr:DescribeRepositories");
    }

    /**
     * A list given as one name names no repository, as ECR's handler then lists every one, so a grant on that
     * repository alone does not allow it.
     */
    @Test
    void aRepositoryListGivenAsOneNameNamesNone() {
        String name = "res-repo-" + suffix();
        User user = user("res-repo-list");
        user.policy(allowOn("ecr:DescribeRepositories", "arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/" + name));
        refusedJson(describeRepositoriesWith(user, "{\"repositoryNames\":\"" + name + "\"}"), "ecr:DescribeRepositories");
    }

    /** A repository is checked in the registry the request's {@code registryId} selects, the one ECR acts in. */
    @Test
    void aRepositoryIsCheckedInTheRegistryItsRequestNames() {
        String name = "res-repo-" + suffix();
        String other = "111122223333";
        User user = user("res-repo-registry");
        user.policy(allowOn("ecr:DescribeRepositories", "arn:aws:ecr:" + REGION + ":" + other + ":repository/" + name));
        describeRepositoriesWith(user, "{\"registryId\":\"" + other + "\",\"repositoryNames\":[\"" + name + "\"]}")
                .statusCode(400).body(containsString("RepositoryNotFoundException"));
        refusedJson(describeRepositories(user, name), "ecr:DescribeRepositories");

        user.policy(allowOn("ecr:DescribeRepositories", "arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/" + name));
        refusedJson(describeRepositoriesWith(user, "{\"registryId\":\"" + other + "\",\"repositoryNames\":[\"" + name
                + "\"]}"), "ecr:DescribeRepositories");
    }

    /** A {@code registryId} of JSON null is no registry, as ECR reads it, so a deny on the caller's repository holds. */
    @Test
    void aNullRegistryIsTheCallersOwn() {
        String name = "res-protected-" + suffix();
        User user = user("res-repo-null");
        user.policy(allowOn("ecr:DescribeRepositories", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"ecr:DescribeRepositories\","
                + "\"Resource\":\"arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/" + name + "\"}");
        refusedJson(describeRepositoriesWith(user, "{\"registryId\":null,\"repositoryNames\":[\"" + name + "\"]}"),
                "ecr:DescribeRepositories");
        describeRepositoriesWith(user, "{\"registryId\":null,\"repositoryNames\":[\"" + name + "-other\"]}")
                .statusCode(400).body(containsString("RepositoryNotFoundException"));
    }

    /**
     * ECR's tag handlers keep the ARN's account and use the request's Region, so a deny on that repository holds
     * for an ARN naming it in another Region.
     */
    @Test
    void aDenyHoldsOnTheRepositoryTheTagHandlersActOn() {
        String protectedArn = "arn:aws:ecr:" + REGION + ":111122223333:repository/res-protected-" + suffix();
        User user = user("res-repo-tag");
        user.policy(allowOn("ecr:TagResource", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"ecr:TagResource\","
                + "\"Resource\":\"" + protectedArn + "\"}");
        refusedJson(user.json("ecr", "AmazonEC2ContainerRegistry_V20150921.TagResource", "1.1")
                .body("{\"resourceArn\":\"" + protectedArn.replace(REGION, "eu-west-1")
                        + "\",\"tags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}").post("/").then(), "ecr:TagResource");
    }

    private static RequestSpecification tagged(RequestSpecification request) {
        return request.formParam("Tags.member.1.Key", "elbv2.k8s.aws/cluster").formParam("Tags.member.1.Value", "c");
    }

    private static ValidatableResponse modifyAttributes(User user, String arn) {
        return user.query("elasticloadbalancing", "ModifyTargetGroupAttributes").formParam("Version", "2015-12-01")
                .formParam("TargetGroupArn", arn).formParam("Attributes.member.1.Key", "deregistration_delay.timeout_seconds")
                .formParam("Attributes.member.1.Value", "30").post("/").then();
    }

    private static ValidatableResponse createTargetGroup(User user, String name) {
        return user.query("elasticloadbalancing", "CreateTargetGroup").formParam("Version", "2015-12-01")
                .formParam("Name", name).formParam("Protocol", "HTTP").formParam("Port", "80")
                .formParam("TargetType", "ip").post("/").then();
    }

    private static ValidatableResponse createListener(User user) {
        return tagged(user.query("elasticloadbalancing", "CreateListener").formParam("Version", "2015-12-01")
                .formParam("LoadBalancerArn", ELB + "loadbalancer/app/res-none/0123456789abcdef")
                .formParam("Protocol", "HTTP").formParam("Port", "80")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")).post("/").then();
    }

    private static ValidatableResponse createRule(User user) {
        return tagged(user.query("elasticloadbalancing", "CreateRule").formParam("Version", "2015-12-01")
                .formParam("ListenerArn", ELB + "listener/app/res-none/0123456789abcdef/0123456789abcdef")
                .formParam("Priority", "1").formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.Values.member.1", "/x")
                .formParam("Actions.member.1.Type", "fixed-response")
                .formParam("Actions.member.1.FixedResponseConfig.StatusCode", "200")).post("/").then();
    }

    private String createStateMachine(String name) {
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", name + "-role",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"lambda:*\",\"Resource\":\"*\"}]}");
        String arn = admin("states").header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType("application/x-amz-json-1.0")
                .body("{\"name\":\"" + name + "\",\"roleArn\":\"" + role + "\",\"definition\":\"" + PASS + "\"}")
                .post("/").then().statusCode(200).extract().path("stateMachineArn");
        cleanup.register(() -> admin("states").header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                .contentType("application/x-amz-json-1.0").body("{\"stateMachineArn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    private static ValidatableResponse describeStateMachine(User user, String arn) {
        return user.json("states", "AWSStepFunctions.DescribeStateMachine", "1.0")
                .body("{\"stateMachineArn\":\"" + arn + "\"}").post("/").then();
    }

    private static ValidatableResponse startExecution(User user, String arn) {
        return user.json("states", "AWSStepFunctions.StartExecution", "1.0")
                .body("{\"stateMachineArn\":\"" + arn + "\"}").post("/").then();
    }

    private static ValidatableResponse describeUserPool(User user, String id) {
        return user.json("cognito-idp", "AWSCognitoIdentityProviderService.DescribeUserPool", "1.1")
                .body("{\"UserPoolId\":\"" + id + "\"}").post("/").then();
    }

    private static ValidatableResponse describeRepositories(User user, String name) {
        return describeRepositoriesWith(user, "{\"repositoryNames\":[\"" + name + "\"]}");
    }

    private static ValidatableResponse describeRepositoriesWith(User user, String body) {
        return user.json("ecr", "AmazonEC2ContainerRegistry_V20150921.DescribeRepositories", "1.1")
                .body(body).post("/").then();
    }

    /** A Query service refuses with {@code AccessDenied}, a 403, naming the permission that is missing. */
    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString("not authorized to perform: " + action));
    }

    /** A JSON-protocol service refuses with {@code AccessDeniedException}, which those protocols send as a 400. */
    private static void refusedJson(ValidatableResponse response, String action) {
        response.statusCode(400).body(containsString("AccessDeniedException"))
                .body(containsString("not authorized to perform: " + action)).body(not(containsString("NotFound")));
    }

    private static String allowOn(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
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

        RequestSpecification cbor(String service) {
            return given().header("Authorization", auth(accessKeyId, service)).header("smithy-protocol", "rpc-v2-cbor")
                    .contentType("application/cbor").accept("application/cbor");
        }
    }
}
