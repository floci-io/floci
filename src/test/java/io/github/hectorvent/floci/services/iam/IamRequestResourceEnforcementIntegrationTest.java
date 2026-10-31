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
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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
    private static final String EVENTS = "arn:aws:events:" + REGION + ":" + ACCOUNT + ":";
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
        String version = publishVersion(arn);
        String alias = createAlias(ADMIN, version).statusCode(200)
                .extract().path("stateMachineAliasArn");
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

    /**
     * An alias is created on the state machine its routing configuration's versions are of, so a grant on that
     * machine allows it and one on another does not.
     */
    @Test
    void anAliasIsCreatedOnTheStateMachineItsVersionsAreOf() {
        String arn = createStateMachine("res-new-alias-" + suffix());
        String version = publishVersion(arn);
        User user = user("res-new-alias");
        user.policy(allowOn("states:CreateStateMachineAlias", arn + "-other"));
        refusedJson(createAlias(user.accessKeyId(), version), "states:CreateStateMachineAlias");
        user.policy(allowOn("states:CreateStateMachineAlias", arn));
        createAlias(user.accessKeyId(), version).statusCode(200);
    }

    /** {@code SetRulePriorities} is checked on the rules its priorities name. */
    @Test
    void rulePrioritiesAreCheckedOnTheRulesTheyName() {
        String rule = ELB + "listener-rule/app/res-none/0123456789abcdef/0123456789abcdef/0123456789abcdef";
        User user = user("res-priorities");
        user.policy(allowOn("elasticloadbalancing:SetRulePriorities", rule));
        setRulePriority(user, rule).statusCode(400).body(containsString("RuleNotFound"));
        refused(setRulePriority(user, rule.replace("0123456789abcdef/0123456789abcdef/0123456789abcdef",
                "0123456789abcdef/0123456789abcdef/fedcba9876543210")), "elasticloadbalancing:SetRulePriorities");
    }

    /**
     * An EventBridge rule is checked on the rule its request names, on the event bus it names by name or ARN, or on
     * the default one when it names none, whose rules have no bus in their ARN, as EventBridge mints them.
     */
    @Test
    void aRuleIsCheckedOnTheEventBusItsRequestNames() {
        String bus = "res-bus-" + suffix();
        String name = "res-rule-" + suffix();
        admin("events").header("X-Amz-Target", "AWSEvents.CreateEventBus").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + bus + "\"}").post("/").then().statusCode(200);
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + bus + "\"}").post("/"));
        String custom = putRule(name, ",\"EventBusName\":\"" + bus + "\"");
        String onDefault = putRule(name, "");
        assertEquals(EVENTS + "rule/" + bus + "/" + name, custom);
        assertEquals(EVENTS + "rule/" + name, onDefault);

        User user = user("res-rule");
        user.policy(allowOn("events:DescribeRule", custom));
        describeRule(user, name, ",\"EventBusName\":\"" + bus + "\"").statusCode(200);
        describeRule(user, name, ",\"EventBusName\":\"" + EVENTS + "event-bus/" + bus + "\"").statusCode(200);
        refusedJson(describeRule(user, name, ""), "events:DescribeRule");

        user.policy(allowOn("events:DescribeRule", onDefault));
        describeRule(user, name, "").statusCode(200);
        describeRule(user, name, ",\"EventBusName\":\"default\"").statusCode(200);
        refusedJson(describeRule(user, name, ",\"EventBusName\":\"" + bus + "\""), "events:DescribeRule");
    }

    /**
     * A bus ARN leaving its account and Region empty names the caller's bus, and a null bus the default one, as the
     * handler reads them, so a deny on the caller's rules holds.
     */
    @Test
    void aDenyHoldsOnTheRuleOfTheBusTheHandlerActsOn() {
        String bus = "res-bus-" + suffix();
        String name = "res-rule-" + suffix();
        admin("events").header("X-Amz-Target", "AWSEvents.CreateEventBus").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + bus + "\"}").post("/").then().statusCode(200);
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + bus + "\"}").post("/"));
        String custom = putRule(name, ",\"EventBusName\":\"" + bus + "\"");
        String onDefault = putRule(name, "");
        String noAccount = ",\"EventBusName\":\"arn:aws:events:::event-bus/" + bus + "\"";

        User user = user("res-rule-deny");
        user.policy(allowOn("events:DescribeRule", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"events:DescribeRule\","
                + "\"Resource\":[\"" + custom + "\",\"" + onDefault + "\"]}");
        refusedJson(describeRule(user, name, noAccount), "events:DescribeRule");
        refusedJson(describeRule(user, name, ",\"EventBusName\":null"), "events:DescribeRule");
        describeRule(user, name + "-other", noAccount).statusCode(400)
                .body(containsString("ResourceNotFoundException"));
        describeRule(user, name + "-other", ",\"EventBusName\":null").statusCode(400)
                .body(containsString("ResourceNotFoundException"));
    }

    /**
     * A connection or an API destination named by its name is checked on the ARN EventBridge stored it under, which
     * follows the name with the id it minted, so a grant on that ARN or on {@code <name>/*} allows it, one on the
     * name alone does not, and a deny on that ARN holds.
     */
    @Test
    void aConnectionNamedByItsNameIsCheckedOnTheArnItWasMintedUnder() {
        String name = "res-conn-" + suffix();
        String connection = admin("events").header("X-Amz-Target", "AWSEvents.CreateConnection")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + name + "\",\"AuthorizationType\":\"API_KEY\","
                        + "\"AuthParameters\":{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"k\",\"ApiKeyValue\":\"v\"}}}")
                .post("/").then().statusCode(200).extract().path("ConnectionArn");
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteConnection")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + name + "\"}").post("/"));
        String destination = admin("events").header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + name + "\",\"ConnectionArn\":\""
                        + connection + "\",\"InvocationEndpoint\":\"https://example.com/hook\",\"HttpMethod\":\"POST\"}")
                .post("/").then().statusCode(200).extract().path("ApiDestinationArn");
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteApiDestination")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + name + "\"}").post("/"));
        assertTrue(connection.startsWith(EVENTS + "connection/" + name + "/"), connection);
        assertTrue(destination.startsWith(EVENTS + "api-destination/" + name + "/"), destination);

        User user = user("res-conn");
        user.policy(allowOn("events:DescribeConnection", connection));
        describeByName(user, "DescribeConnection", name).statusCode(200);
        user.policy(allowOn("events:DescribeConnection", EVENTS + "connection/" + name + "/*"));
        describeByName(user, "DescribeConnection", name).statusCode(200);
        user.policy(allowOn("events:DescribeConnection", EVENTS + "connection/" + name));
        refusedJson(describeByName(user, "DescribeConnection", name), "events:DescribeConnection");
        user.policy(allowOn("events:DescribeConnection", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"events:DescribeConnection\",\"Resource\":\"" + connection + "\"}");
        refusedJson(describeByName(user, "DescribeConnection", name), "events:DescribeConnection");
        user.policy(allowOn("events:DescribeApiDestination", destination));
        describeByName(user, "DescribeApiDestination", name).statusCode(200);
        refusedJson(describeByName(user, "DescribeApiDestination", name + "-other"), "events:DescribeApiDestination");
    }

    /** A request naming no event bus describes the default one, so it is checked on that bus. */
    @Test
    void anEventBusRequestNamingNoneIsCheckedOnTheDefaultOne() {
        User user = user("res-default-bus");
        user.policy(allowOn("events:DescribeEventBus", EVENTS + "event-bus/default"));
        user.json("events", "AWSEvents.DescribeEventBus", "1.1").body("{}").post("/").then().statusCode(200);
        refusedJson(user.json("events", "AWSEvents.DescribeEventBus", "1.1").body("{\"Name\":\"res-other\"}").post("/")
                .then(), "events:DescribeEventBus");
    }

    /**
     * Publishing is authorized on the bus of every entry, the one it names by name or ARN or the default one when
     * it names none, so a grant on one bus refuses a batch with an entry for another, and a deny on one bus holds
     * however many entries go elsewhere.
     */
    @Test
    void eachEventIsCheckedOnTheBusItsEntryNames() {
        String bus = "res-bus-" + suffix();
        admin("events").header("X-Amz-Target", "AWSEvents.CreateEventBus").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + bus + "\"}").post("/").then().statusCode(200);
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + bus + "\"}").post("/"));
        String byName = ",\"EventBusName\":\"" + bus + "\"";
        String byArn = ",\"EventBusName\":\"" + EVENTS + "event-bus/" + bus + "\"";

        User user = user("res-put-events");
        user.policy(allowOn("events:PutEvents", EVENTS + "event-bus/" + bus));
        putEvents(user, byName, byArn).statusCode(200).body("FailedEntryCount", equalTo(0));
        refusedJson(putEvents(user, byName, ""), "events:PutEvents");
        refusedJson(putEvents(user, ""), "events:PutEvents");
        user.policy(allowOn("events:PutEvents", EVENTS + "event-bus/" + bus) + ","
                + allowOn("events:PutEvents", EVENTS + "event-bus/default"));
        putEvents(user, byName, "").statusCode(200).body("FailedEntryCount", equalTo(0));

        user.policy(allowOn("events:PutEvents", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"events:PutEvents\","
                + "\"Resource\":\"" + EVENTS + "event-bus/" + bus + "\"}");
        refusedJson(putEvents(user, "", "", byArn), "events:PutEvents");
        putEvents(user, "", ",\"EventBusName\":null").statusCode(200).body("FailedEntryCount", equalTo(0));
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

    @Test
    void aDeliveryStreamIsCheckedOnTheArnItsNameGives() {
        String name = "res-stream-" + suffix();
        String arn = adminJson("firehose", "Firehose_20150804.CreateDeliveryStream")
                .body("{\"DeliveryStreamName\":\"" + name + "\"}").post("/").then().statusCode(200)
                .extract().path("DeliveryStreamARN");
        cleanup.register(() -> adminJson("firehose", "Firehose_20150804.DeleteDeliveryStream")
                .body("{\"DeliveryStreamName\":\"" + name + "\"}").post("/"));
        assertEquals("arn:aws:firehose:" + REGION + ":" + ACCOUNT + ":deliverystream/" + name, arn);

        User user = user("res-stream");
        user.policy(allowOn("firehose:DescribeDeliveryStream", arn));
        describeDeliveryStream(user, name).statusCode(200);
        refusedJson(describeDeliveryStream(user, name + "-other"), "firehose:DescribeDeliveryStream");
    }

    /** An identity pool is checked on the ARN its id gives, the one Cognito tags it under. */
    @Test
    void anIdentityPoolIsCheckedOnTheArnItsIdGives() {
        String id = adminJson("cognito-identity", "AWSCognitoIdentityService.CreateIdentityPool")
                .body("{\"IdentityPoolName\":\"res pool " + suffix() + "\",\"AllowUnauthenticatedIdentities\":false}")
                .post("/").then().statusCode(200).extract().path("IdentityPoolId");
        cleanup.register(() -> adminJson("cognito-identity", "AWSCognitoIdentityService.DeleteIdentityPool")
                .body("{\"IdentityPoolId\":\"" + id + "\"}").post("/"));
        String arn = "arn:aws:cognito-identity:" + REGION + ":" + ACCOUNT + ":identitypool/" + id;
        adminJson("cognito-identity", "AWSCognitoIdentityService.TagResource")
                .body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":{\"a\":\"b\"}}").post("/").then().statusCode(200);

        User user = user("res-identity-pool");
        user.policy(allowOn("cognito-identity:DescribeIdentityPool", arn));
        describeIdentityPool(user, id).statusCode(200);
        refusedJson(describeIdentityPool(user, REGION + ":" + UUID.randomUUID()), "cognito-identity:DescribeIdentityPool");
    }

    @Test
    void aCloudMapNamespaceAndServiceAreCheckedOnTheArnsTheirIdsGive() {
        String namespaceId = cloudMapNamespace("res-ns-" + suffix());
        String namespaceArn = adminJson("servicediscovery", "Route53AutoNaming_v20170314.GetNamespace")
                .body("{\"Id\":\"" + namespaceId + "\"}").post("/").then().statusCode(200).extract().path("Namespace.Arn");
        assertEquals("arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT + ":namespace/" + namespaceId, namespaceArn);
        Map<String, String> service = adminJson("servicediscovery", "Route53AutoNaming_v20170314.CreateService")
                .body("{\"Name\":\"res-svc\",\"NamespaceId\":\"" + namespaceId + "\"}").post("/").then().statusCode(200)
                .extract().path("Service");
        cleanup.register(() -> adminJson("servicediscovery", "Route53AutoNaming_v20170314.DeleteService")
                .body("{\"Id\":\"" + service.get("Id") + "\"}").post("/"));
        assertEquals("arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT + ":service/" + service.get("Id"),
                service.get("Arn"));

        User user = user("res-cloud-map");
        user.policy(allowOn("servicediscovery:GetNamespace", namespaceArn) + ","
                + allowOn("servicediscovery:GetService", service.get("Arn")));
        cloudMap(user, "GetNamespace", namespaceId).statusCode(200);
        refusedJson(cloudMap(user, "GetNamespace", "ns-" + suffix()), "servicediscovery:GetNamespace");
        cloudMap(user, "GetService", service.get("Id")).statusCode(200);
        refusedJson(cloudMap(user, "GetService", "srv-" + suffix()), "servicediscovery:GetService");
    }

    @Test
    void aCertificateIsCheckedOnItsArn() {
        String arn = adminJson("acm", "CertificateManager.RequestCertificate")
                .body("{\"DomainName\":\"res-" + suffix() + ".example.com\"}").post("/").then().statusCode(200)
                .extract().path("CertificateArn");
        cleanup.register(() -> adminJson("acm", "CertificateManager.DeleteCertificate")
                .body("{\"CertificateArn\":\"" + arn + "\"}").post("/"));
        assertTrue(arn.startsWith("arn:aws:acm:" + REGION + ":" + ACCOUNT + ":certificate/"), arn);

        User user = user("res-certificate");
        user.policy(allowOn("acm:DescribeCertificate", arn));
        describeCertificate(user, arn).statusCode(200);
        refusedJson(describeCertificate(user, "arn:aws:acm:" + REGION + ":" + ACCOUNT + ":certificate/" + UUID.randomUUID()),
                "acm:DescribeCertificate");
    }

    /**
     * A pipeline is checked on the ARN its name gives, a request naming it by a bare {@code name} too, and a webhook
     * on {@code webhook:<name>}, the ARN CodePipeline gives it.
     */
    @Test
    void aPipelineAndAWebhookAreCheckedOnTheArnsTheirNamesGive() {
        String name = "res-pipeline-" + suffix();
        adminJson("codepipeline", "CodePipeline_20150709.CreatePipeline").body(pipeline(name)).post("/")
                .then().statusCode(200);
        cleanup.register(() -> adminJson("codepipeline", "CodePipeline_20150709.DeletePipeline")
                .body("{\"name\":\"" + name + "\"}").post("/"));
        String pipelineArn = adminJson("codepipeline", "CodePipeline_20150709.GetPipeline")
                .body("{\"name\":\"" + name + "\"}").post("/").then().statusCode(200).extract().path("metadata.pipelineArn");
        assertEquals("arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":" + name, pipelineArn);

        User user = user("res-pipeline");
        user.policy(allowOn("codepipeline:GetPipeline", pipelineArn) + ","
                + allowOn("codepipeline:GetPipelineState", pipelineArn));
        codePipeline(user, "GetPipeline", "{\"name\":\"" + name + "\"}").statusCode(200);
        codePipeline(user, "GetPipelineState", "{\"name\":\"" + name + "\"}").statusCode(200);
        refusedJson(codePipeline(user, "GetPipelineState", "{\"name\":\"" + name + "-other\"}"),
                "codepipeline:GetPipelineState");

        String hook = "res-hook-" + suffix();
        String hookArn = adminJson("codepipeline", "CodePipeline_20150709.PutWebhook").body("{\"webhook\":{\"name\":\""
                + hook + "\",\"targetPipeline\":\"" + name + "\",\"targetAction\":\"SourceAction\",\"filters\":"
                + "[{\"jsonPath\":\"$.ref\",\"matchEquals\":\"refs/heads/main\"}],\"authentication\":\"UNAUTHENTICATED\"}}")
                .post("/").then().statusCode(200).extract().path("webhook.arn");
        assertEquals("arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":webhook:" + hook, hookArn);
        user.policy(allowOn("codepipeline:DeleteWebhook", hookArn));
        refusedJson(codePipeline(user, "DeleteWebhook", "{\"name\":\"" + hook + "-other\"}"), "codepipeline:DeleteWebhook");
        codePipeline(user, "DeleteWebhook", "{\"name\":\"" + hook + "\"}").statusCode(200);
    }

    /** {@code StartPipelineExecution} names its pipeline by a bare {@code name}, on which it is checked. */
    @Test
    void aPipelineExecutionIsCheckedOnThePipelineItsNameGives() {
        String name = "res-pipeline-" + suffix();
        User user = user("res-pipeline-start");
        user.policy(allowOn("codepipeline:StartPipelineExecution",
                "arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":" + name));
        codePipeline(user, "StartPipelineExecution", "{\"name\":\"" + name + "\"}").statusCode(400)
                .body(containsString("PipelineNotFoundException"));
        refusedJson(codePipeline(user, "StartPipelineExecution", "{\"name\":\"" + name + "-other\"}"),
                "codepipeline:StartPipelineExecution");
    }

    /**
     * An update is checked on the pipeline its {@code pipeline.name} names, which it changes, not on one its
     * {@code roleArn} holds: a grant on one pipeline does not let a request naming another through by giving the
     * first's ARN as its role.
     */
    @Test
    void anUpdateIsCheckedOnThePipelineItNamesNotOneItsRoleHolds() {
        String allowed = "res-pipeline-" + suffix();
        String other = allowed + "-other";
        for (String name : List.of(allowed, other)) {
            adminJson("codepipeline", "CodePipeline_20150709.CreatePipeline").body(pipeline(name)).post("/")
                    .then().statusCode(200);
            cleanup.register(() -> adminJson("codepipeline", "CodePipeline_20150709.DeletePipeline")
                    .body("{\"name\":\"" + name + "\"}").post("/"));
        }
        String allowedArn = "arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":" + allowed;
        User user = user("res-pipeline-update");
        user.policy(allowOn("codepipeline:UpdatePipeline", allowedArn) + ","
                + allowOn("iam:PassRole", "arn:aws:iam::" + ACCOUNT + ":role/codepipeline-role"));
        refusedJson(codePipeline(user, "UpdatePipeline", pipeline(other).replace(
                "arn:aws:iam::" + ACCOUNT + ":role/codepipeline-role", allowedArn)), "codepipeline:UpdatePipeline");
        codePipeline(user, "UpdatePipeline", pipeline(allowed)).statusCode(200);
    }

    /** A webhook's tags are checked on the webhook, not on the pipeline it connects to, which they do not go on. */
    @Test
    void aWebhooksTagsAreCheckedOnTheWebhookNotItsPipeline() {
        String name = "res-pipeline-" + suffix();
        adminJson("codepipeline", "CodePipeline_20150709.CreatePipeline").body(pipeline(name)).post("/")
                .then().statusCode(200);
        cleanup.register(() -> adminJson("codepipeline", "CodePipeline_20150709.DeletePipeline")
                .body("{\"name\":\"" + name + "\"}").post("/"));
        String hook = "res-hook-" + suffix();
        String codePipeline = "arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":";
        User user = user("res-webhook-tags");
        user.policy(allowOn("codepipeline:PutWebhook", codePipeline + "webhook:" + hook) + ","
                + allowOn("codepipeline:PutWebhook", codePipeline + name) + ","
                + allowOn("codepipeline:TagResource", codePipeline + "webhook:*"));
        codePipeline(user, "PutWebhook", "{\"webhook\":{\"name\":\"" + hook + "\",\"targetPipeline\":\"" + name
                + "\",\"targetAction\":\"SourceAction\",\"filters\":[{\"jsonPath\":\"$.ref\",\"matchEquals\":"
                + "\"refs/heads/main\"}],\"authentication\":\"UNAUTHENTICATED\"},\"tags\":[{\"key\":\"a\",\"value\":\"b\"}]}")
                .statusCode(200);
        cleanup.register(() -> adminJson("codepipeline", "CodePipeline_20150709.DeleteWebhook")
                .body("{\"name\":\"" + hook + "\"}").post("/"));
    }

    /**
     * A service is created in the namespace its {@code DnsConfig.NamespaceId} names when the request gives no
     * {@code NamespaceId}, or a JSON null for it, as Cloud Map reads it, so a deny on that namespace holds.
     */
    @Test
    void aServiceIsCheckedOnTheNamespaceItsDnsConfigNames() {
        String namespaceId = cloudMapNamespace("res-ns-dns-" + suffix());
        User user = user("res-cloud-map-dns");
        user.policy(allowOn("servicediscovery:CreateService", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"servicediscovery:CreateService\",\"Resource\":\"arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT
                + ":namespace/" + namespaceId + "\"}");
        String dnsConfig = "\"DnsConfig\":{\"NamespaceId\":\"" + namespaceId + "\",\"DnsRecords\":"
                + "[{\"Type\":\"A\",\"TTL\":60}]}";
        refusedJson(user.json("servicediscovery", "Route53AutoNaming_v20170314.CreateService", "1.1")
                .body("{\"Name\":\"res-svc\"," + dnsConfig + "}").post("/").then(), "servicediscovery:CreateService");
        refusedJson(user.json("servicediscovery", "Route53AutoNaming_v20170314.CreateService", "1.1")
                .body("{\"Name\":\"res-svc\",\"NamespaceId\":null," + dnsConfig + "}").post("/").then(),
                "servicediscovery:CreateService");
    }

    /** A service's tags are checked on the service it creates, not on the namespace it creates it in. */
    @Test
    void aServicesTagsAreCheckedOnTheServiceNotItsNamespace() {
        String namespaceId = cloudMapNamespace("res-ns-tags-" + suffix());
        String cloudMap = "arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT + ":";
        User user = user("res-cloud-map-tags");
        user.policy(allowOn("servicediscovery:CreateService", cloudMap + "namespace/" + namespaceId) + ","
                + allowOn("servicediscovery:CreateService", cloudMap + "service/*") + ","
                + allowOn("servicediscovery:TagResource", cloudMap + "service/*"));
        String serviceId = user.json("servicediscovery", "Route53AutoNaming_v20170314.CreateService", "1.1")
                .body("{\"Name\":\"res-svc\",\"NamespaceId\":\"" + namespaceId + "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}")
                .post("/").then().statusCode(200).extract().path("Service.Id");
        cleanup.register(() -> adminJson("servicediscovery", "Route53AutoNaming_v20170314.DeleteService")
                .body("{\"Id\":\"" + serviceId + "\"}").post("/"));
    }

    /**
     * A custom action type is checked on {@code actiontype:Custom/...}, its owner being {@code Custom}, which the
     * request does not give, so a grant on the ARN CodePipeline gives it allows creating and deleting it.
     */
    @Test
    void aCustomActionTypeIsCheckedAsCustoms() {
        String provider = "Res" + suffix();
        String arn = "arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":actiontype:Custom/Build/" + provider + "/1";
        User user = user("res-action-type");
        user.policy(allowOn("codepipeline:CreateCustomActionType", arn) + ","
                + allowOn("codepipeline:DeleteCustomActionType", arn));
        String type = "\"category\":\"Build\",\"provider\":\"" + provider + "\",\"version\":\"1\"";
        codePipeline(user, "CreateCustomActionType", "{" + type + ",\"inputArtifactDetails\":{\"minimumCount\":0,"
                + "\"maximumCount\":1},\"outputArtifactDetails\":{\"minimumCount\":0,\"maximumCount\":1}}")
                .statusCode(200).body("actionType.id.owner", equalTo("Custom"));
        refusedJson(codePipeline(user, "DeleteCustomActionType", "{" + type.replace(provider, provider + "x") + "}"),
                "codepipeline:DeleteCustomActionType");
        codePipeline(user, "DeleteCustomActionType", "{" + type + "}").statusCode(200);
    }

    /** A Cloud Map HTTP namespace, created for the test and deleted after it; its id. */
    private String cloudMapNamespace(String name) {
        adminJson("servicediscovery", "Route53AutoNaming_v20170314.CreateHttpNamespace")
                .body("{\"Name\":\"" + name + "\"}").post("/").then().statusCode(200);
        String id = adminJson("servicediscovery", "Route53AutoNaming_v20170314.ListNamespaces").body("{}")
                .post("/").then().statusCode(200).extract().path("Namespaces.find { it.Name == '" + name + "' }.Id");
        cleanup.register(() -> adminJson("servicediscovery", "Route53AutoNaming_v20170314.DeleteNamespace")
                .body("{\"Id\":\"" + id + "\"}").post("/"));
        return id;
    }

    /**
     * A build is checked on its project, which its id names before its {@code :}, as CodeBuild authorizes builds,
     * and a project on the ARN its name gives, a list naming it by name and by ARN alike.
     */
    @Test
    void aBuildIsCheckedOnTheProjectItsIdNames() {
        String project = "res-project-" + suffix();
        String arn = adminJson("codebuild", "CodeBuild_20161006.CreateProject").body("{\"name\":\"" + project
                + "\",\"source\":{\"type\":\"NO_SOURCE\",\"buildspec\":\"version: 0.2\"},\"artifacts\":{\"type\":"
                + "\"NO_ARTIFACTS\"},\"environment\":{\"type\":\"LINUX_CONTAINER\",\"image\":\"aws/codebuild/standard:7.0\","
                + "\"computeType\":\"BUILD_GENERAL1_SMALL\"},\"serviceRole\":\"arn:aws:iam::" + ACCOUNT
                + ":role/codebuild-role\"}").post("/").then().statusCode(200).extract().path("project.arn");
        cleanup.register(() -> adminJson("codebuild", "CodeBuild_20161006.DeleteProject")
                .body("{\"name\":\"" + project + "\"}").post("/"));
        assertEquals("arn:aws:codebuild:" + REGION + ":" + ACCOUNT + ":project/" + project, arn);

        User user = user("res-build");
        user.policy(allowOn("codebuild:BatchGetProjects", arn) + "," + allowOn("codebuild:BatchGetBuilds", arn) + ","
                + allowOn("codebuild:StopBuild", arn));
        codeBuild(user, "BatchGetProjects", "{\"names\":[\"" + project + "\"]}").statusCode(200);
        codeBuild(user, "BatchGetProjects", "{\"names\":[\"" + project + "\",\"" + arn + "\"]}").statusCode(200);
        codeBuild(user, "BatchGetBuilds", "{\"ids\":[\"" + project + ":1\"]}").statusCode(200);
        refusedJson(codeBuild(user, "BatchGetBuilds", "{\"ids\":[\"" + project + ":1\",\"" + project + "-other:1\"]}"),
                "codebuild:BatchGetBuilds");
        codeBuild(user, "StopBuild", "{\"id\":\"" + project + ":1\"}").statusCode(400)
                .body(containsString("ResourceNotFoundException"));
        refusedJson(codeBuild(user, "StopBuild", "{\"id\":\"" + project + "-other:1\"}"), "codebuild:StopBuild");
    }

    /** A Managed Service for Apache Flink application is checked on the ARN its name gives. */
    @Test
    void anApplicationIsCheckedOnTheArnItsNameGives() {
        String name = "res-app-" + suffix();
        String arn = adminJson("kinesisanalytics", "KinesisAnalytics_20180523.CreateApplication")
                .body("{\"ApplicationName\":\"" + name + "\",\"RuntimeEnvironment\":\"FLINK-1_18\","
                        + "\"ServiceExecutionRole\":\"arn:aws:iam::" + ACCOUNT + ":role/flink-role\"}")
                .post("/").then().statusCode(200).extract().path("ApplicationDetail.ApplicationARN");
        cleanup.register(() -> adminJson("kinesisanalytics", "KinesisAnalytics_20180523.DeleteApplication")
                .body("{\"ApplicationName\":\"" + name + "\",\"CreateTimestamp\":"
                        + adminJson("kinesisanalytics", "KinesisAnalytics_20180523.DescribeApplication")
                        .body("{\"ApplicationName\":\"" + name + "\"}").post("/").then().statusCode(200).extract()
                        .jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                        .getString("ApplicationDetail.CreateTimestamp") + "}").post("/"));
        assertEquals("arn:aws:kinesisanalytics:" + REGION + ":" + ACCOUNT + ":application/" + name, arn);

        User user = user("res-application");
        user.policy(allowOn("kinesisanalytics:DescribeApplication", arn));
        describeApplication(user, name).statusCode(200);
        refusedJson(describeApplication(user, name + "-other"), "kinesisanalytics:DescribeApplication");
    }

    /**
     * An Athena query is checked on the workgroup its request names, the primary one when it names none, where Athena
     * runs and lists it.
     */
    @Test
    void aQueryNamingNoWorkgroupIsCheckedOnThePrimaryOne() {
        String primary = "arn:aws:athena:" + REGION + ":" + ACCOUNT + ":workgroup/primary";
        User user = user("res-athena");
        user.policy(allowOn("athena:ListQueryExecutions", primary) + "," + allowOn("athena:GetWorkGroup", primary) + ","
                + allowOn("athena:StartQueryExecution", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"athena:StartQueryExecution\",\"Resource\":\"" + primary + "\"}");
        send(user, "athena", "AmazonAthena.ListQueryExecutions", "1.1", "{}").statusCode(200);
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{}").statusCode(200);
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{\"WorkGroup\":null}").statusCode(200)
                .body("WorkGroup.Name", equalTo("primary"));
        refusedJson(send(user, "athena", "AmazonAthena.ListQueryExecutions", "1.1", "{\"WorkGroup\":\"res-other\"}"),
                "athena:ListQueryExecutions");
        refusedJson(send(user, "athena", "AmazonAthena.StartQueryExecution", "1.1", "{\"QueryString\":\"SELECT 1\"}"),
                "athena:StartQueryExecution");
    }

    /**
     * An Athena catalog read is checked on the catalog its {@code CatalogName} names and on {@code AwsDataCatalog},
     * the Glue Data Catalog Floci reads whichever it names, so a grant on another catalog alone does not reach it.
     */
    @Test
    void aCatalogReadIsCheckedOnTheGlueCatalogFlociReads() {
        String catalogs = "arn:aws:athena:" + REGION + ":" + ACCOUNT + ":datacatalog/";
        User both = user("res-catalogs");
        both.policy(allowOn("athena:ListDatabases", catalogs + "AwsDataCatalog") + ","
                + allowOn("athena:ListDatabases", catalogs + "res-allowed"));
        send(both, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"res-allowed\"}").statusCode(200);
        refusedJson(send(both, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"res-other\"}"),
                "athena:ListDatabases");
        User named = user("res-catalog");
        named.policy(allowOn("athena:ListDatabases", catalogs + "res-allowed"));
        refusedJson(send(named, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"res-allowed\"}"),
                "athena:ListDatabases");
    }

    /**
     * A Data Exports export is checked on its ARN, and a create not on the ARN its {@code Export.ExportArn} gives,
     * which is the new export's own, so a table's there checks nothing.
     */
    @Test
    void anExportIsCheckedOnItsArnNotOnATableItsExportArnGives() {
        String name = "res-export-" + suffix();
        String arn = adminJson("bcm-data-exports", "AWSBillingAndCostManagementDataExports.CreateExport")
                .body("{\"Export\":" + export(name, null) + "}").post("/").then().statusCode(200).extract()
                .path("ExportArn");
        cleanup.register(() -> adminJson("bcm-data-exports", "AWSBillingAndCostManagementDataExports.DeleteExport")
                .body("{\"ExportArn\":\"" + arn + "\"}").post("/"));
        String table = "arn:aws:bcm-data-exports:" + REGION + ":" + ACCOUNT + ":table/COST_AND_USAGE_REPORT";

        User user = user("res-export");
        user.policy(allowOn("bcm-data-exports:GetExport", arn) + "," + allowOn("bcm-data-exports:CreateExport", table));
        send(user, "bcm-data-exports", "AWSBillingAndCostManagementDataExports.GetExport", "1.1",
                "{\"ExportArn\":\"" + arn + "\"}").statusCode(200);
        refusedJson(send(user, "bcm-data-exports", "AWSBillingAndCostManagementDataExports.GetExport", "1.1",
                "{\"ExportArn\":\"" + arn + "-other\"}"), "bcm-data-exports:GetExport");
        refusedJson(send(user, "bcm-data-exports", "AWSBillingAndCostManagementDataExports.CreateExport", "1.1",
                "{\"Export\":" + export(name + "-other", table) + "}"), "bcm-data-exports:CreateExport");
    }

    /** A Pricing Calculator workload estimate is checked on {@code workload-estimate/<id>}, with no Region. */
    @Test
    void aWorkloadEstimateIsCheckedOnTheArnItsIdGives() {
        String id = adminJson("bcm-pricing-calculator", "AWSBCMPricingCalculator.CreateWorkloadEstimate", "1.0")
                .body("{\"name\":\"res-estimate\",\"clientToken\":\"" + suffix() + "\"}").post("/").then()
                .statusCode(200).extract().path("id");
        cleanup.register(() -> adminJson("bcm-pricing-calculator", "AWSBCMPricingCalculator.DeleteWorkloadEstimate",
                "1.0").body("{\"identifier\":\"" + id + "\"}").post("/"));

        User user = user("res-estimate");
        user.policy(allowOn("bcm-pricing-calculator:GetWorkloadEstimate",
                "arn:aws:bcm-pricing-calculator::" + ACCOUNT + ":workload-estimate/" + id));
        send(user, "bcm-pricing-calculator", "AWSBCMPricingCalculator.GetWorkloadEstimate", "1.0",
                "{\"identifier\":\"" + id + "\"}").statusCode(200);
        refusedJson(send(user, "bcm-pricing-calculator", "AWSBCMPricingCalculator.GetWorkloadEstimate", "1.0",
                "{\"identifier\":\"" + UUID.randomUUID() + "\"}"), "bcm-pricing-calculator:GetWorkloadEstimate");
    }

    /**
     * A Cost Explorer anomaly monitor or subscription is checked on its ARN, which carries no Region, and a list of
     * them on each ARN its list gives.
     */
    @Test
    void anAnomalyMonitorOrSubscriptionIsCheckedOnItsArn() {
        String arn = adminJson("ce", "AWSInsightsIndexService.CreateAnomalyMonitor").body("{\"AnomalyMonitor\":"
                + "{\"MonitorName\":\"res-monitor\",\"MonitorType\":\"DIMENSIONAL\",\"MonitorDimension\":\"SERVICE\"}}")
                .post("/").then().statusCode(200).extract().path("MonitorArn");
        cleanup.register(() -> adminJson("ce", "AWSInsightsIndexService.DeleteAnomalyMonitor")
                .body("{\"MonitorArn\":\"" + arn + "\"}").post("/"));
        assertTrue(arn.startsWith("arn:aws:ce::" + ACCOUNT + ":anomalymonitor/"), arn);
        String subscription = adminJson("ce", "AWSInsightsIndexService.CreateAnomalySubscription")
                .body("{\"AnomalySubscription\":{\"SubscriptionName\":\"res-subscription\",\"Frequency\":\"DAILY\","
                        + "\"MonitorArnList\":[\"" + arn + "\"],\"Subscribers\":[{\"Address\":\"alerts@example.com\","
                        + "\"Type\":\"EMAIL\"}],\"ThresholdExpression\":{\"Dimensions\":{\"Key\":"
                        + "\"ANOMALY_TOTAL_IMPACT_ABSOLUTE\",\"Values\":[\"100\"],\"MatchOptions\":"
                        + "[\"GREATER_THAN_OR_EQUAL\"]}}}}")
                .post("/").then().statusCode(200).extract().path("SubscriptionArn");
        cleanup.register(() -> adminJson("ce", "AWSInsightsIndexService.DeleteAnomalySubscription")
                .body("{\"SubscriptionArn\":\"" + subscription + "\"}").post("/"));
        String otherMonitor = arn.substring(0, arn.lastIndexOf('/') + 1) + UUID.randomUUID();
        String otherSubscription = subscription.substring(0, subscription.lastIndexOf('/') + 1) + UUID.randomUUID();

        User user = user("res-monitor");
        user.policy(allowOn("ce:UpdateAnomalyMonitor", arn) + "," + allowOn("ce:GetAnomalyMonitors", arn) + ","
                + allowOn("ce:GetAnomalySubscriptions", subscription));
        send(user, "ce", "AWSInsightsIndexService.UpdateAnomalyMonitor", "1.1", "{\"MonitorArn\":\"" + arn
                + "\",\"MonitorName\":\"res-renamed\"}").statusCode(200);
        refusedJson(send(user, "ce", "AWSInsightsIndexService.UpdateAnomalyMonitor", "1.1", "{\"MonitorArn\":\""
                + otherMonitor + "\",\"MonitorName\":\"res-renamed\"}"), "ce:UpdateAnomalyMonitor");
        send(user, "ce", "AWSInsightsIndexService.GetAnomalyMonitors", "1.1", "{\"MonitorArnList\":[\"" + arn
                + "\"]}").statusCode(200);
        refusedJson(send(user, "ce", "AWSInsightsIndexService.GetAnomalyMonitors", "1.1", "{\"MonitorArnList\":[\""
                + arn + "\",\"" + otherMonitor + "\"]}"), "ce:GetAnomalyMonitors");
        send(user, "ce", "AWSInsightsIndexService.GetAnomalySubscriptions", "1.1", "{\"SubscriptionArnList\":[\""
                + subscription + "\"]}").statusCode(200);
        refusedJson(send(user, "ce", "AWSInsightsIndexService.GetAnomalySubscriptions", "1.1",
                "{\"SubscriptionArnList\":[\"" + otherSubscription + "\"]}"), "ce:GetAnomalySubscriptions");
    }

    /**
     * A Cost Explorer cost read is checked on {@code *}, not on the billing view its {@code BillingViewArn} gives:
     * Floci serves no billing views and answers the account's costs whichever it names.
     */
    @Test
    void aBillingViewGrantDoesNotReachTheAccountsCosts() {
        String view = "arn:aws:billing::" + ACCOUNT + ":billingview/res-view";
        User user = user("res-view");
        user.policy(allowOn("ce:GetCostAndUsage", view));
        refusedJson(send(user, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", "{\"TimePeriod\":{\"Start\":"
                + "\"2026-01-01\",\"End\":\"2026-02-01\"},\"Granularity\":\"MONTHLY\",\"Metrics\":[\"UnblendedCost\"],"
                + "\"BillingViewArn\":\"" + view + "\"}"), "ce:GetCostAndUsage");
    }

    /**
     * A trail is checked on {@code trail/<name>}: its status on the one its bare {@code Name} names, by its name or its
     * ARN, and its tags on the one the ARN in its {@code ResourceId} gives.
     */
    @Test
    void aTrailIsCheckedOnTheArnItsNameGives() {
        String name = "res-trail-" + suffix();
        String other = name + "-other";
        for (String trail : List.of(name, other)) {
            adminJson("cloudtrail", "CloudTrail_20131101.CreateTrail")
                    .body("{\"Name\":\"" + trail + "\",\"S3BucketName\":\"res-trail-logs\"}").post("/").then()
                    .statusCode(200);
            cleanup.register(() -> adminJson("cloudtrail", "CloudTrail_20131101.DeleteTrail")
                    .body("{\"Name\":\"" + trail + "\"}").post("/"));
        }
        String arn = "arn:aws:cloudtrail:" + REGION + ":" + ACCOUNT + ":trail/" + name;

        User user = user("res-trail");
        user.policy(allowOn("cloudtrail:GetTrailStatus", arn) + "," + allowOn("cloudtrail:AddTags", arn));
        send(user, "cloudtrail", "CloudTrail_20131101.GetTrailStatus", "1.1", "{\"Name\":\"" + name + "\"}")
                .statusCode(200);
        send(user, "cloudtrail", "CloudTrail_20131101.GetTrailStatus", "1.1", "{\"Name\":\"" + arn + "\"}")
                .statusCode(200);
        refusedJson(send(user, "cloudtrail", "CloudTrail_20131101.GetTrailStatus", "1.1",
                "{\"Name\":\"" + other + "\"}"), "cloudtrail:GetTrailStatus");
        String tags = "\",\"TagsList\":[{\"Key\":\"a\",\"Value\":\"b\"}]}";
        send(user, "cloudtrail", "CloudTrail_20131101.AddTags", "1.1", "{\"ResourceId\":\"" + arn + tags)
                .statusCode(200);
        refusedJson(send(user, "cloudtrail", "CloudTrail_20131101.AddTags", "1.1",
                "{\"ResourceId\":\"" + arn + "-other" + tags), "cloudtrail:AddTags");
    }

    /** A CloudTrail dashboard is checked on the one its {@code DashboardId}, a name or an ARN, names. */
    @Test
    void aDashboardIsCheckedOnTheOneItsIdNames() {
        String dashboard = "arn:aws:cloudtrail:" + REGION + ":" + ACCOUNT + ":dashboard/res-denied";
        User user = user("res-dashboard");
        user.policy(allowOn("cloudtrail:GetDashboard", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"cloudtrail:GetDashboard\",\"Resource\":\"" + dashboard + "\"}");
        refusedJson(send(user, "cloudtrail", "CloudTrail_20131101.GetDashboard", "1.1",
                "{\"DashboardId\":\"res-denied\"}"), "cloudtrail:GetDashboard");
        refusedJson(send(user, "cloudtrail", "CloudTrail_20131101.GetDashboard", "1.1",
                "{\"DashboardId\":\"" + dashboard + "\"}"), "cloudtrail:GetDashboard");
        send(user, "cloudtrail", "CloudTrail_20131101.GetDashboard", "1.1", "{\"DashboardId\":\"res-other\"}")
                .body(not(containsString("not authorized")));
    }

    /**
     * A Global Accelerator accelerator is checked on its ARN, which carries no Region, and listing its custom routing
     * port mappings on it alone, not on the endpoint group whose ARN extends it.
     */
    @Test
    void anAcceleratorIsCheckedOnItsArnNotOnAnEndpointGroupOfIt() {
        String arn = adminJson("globalaccelerator", "GlobalAccelerator_V20180706.CreateAccelerator")
                .body("{\"Name\":\"res-accelerator\",\"IdempotencyToken\":\"" + suffix() + "\",\"Enabled\":false}")
                .post("/").then().statusCode(200).extract().path("Accelerator.AcceleratorArn");
        cleanup.register(() -> adminJson("globalaccelerator", "GlobalAccelerator_V20180706.DeleteAccelerator")
                .body("{\"AcceleratorArn\":\"" + arn + "\"}").post("/"));
        assertTrue(arn.startsWith("arn:aws:globalaccelerator::" + ACCOUNT + ":accelerator/"), arn);

        User user = user("res-accelerator");
        user.policy(allowOn("globalaccelerator:DescribeAccelerator", arn) + ","
                + allowOn("globalaccelerator:ListCustomRoutingPortMappings", arn));
        send(user, "globalaccelerator", "GlobalAccelerator_V20180706.DescribeAccelerator", "1.1",
                "{\"AcceleratorArn\":\"" + arn + "\"}").statusCode(200);
        refusedJson(send(user, "globalaccelerator", "GlobalAccelerator_V20180706.DescribeAccelerator", "1.1",
                "{\"AcceleratorArn\":\"" + arn + "-other\"}"), "globalaccelerator:DescribeAccelerator");
        send(user, "globalaccelerator", "GlobalAccelerator_V20180706.ListCustomRoutingPortMappings", "1.1",
                "{\"AcceleratorArn\":\"" + arn + "\",\"EndpointGroupArn\":\"" + arn
                        + "/listener/0123abcd/endpoint-group/0123456789ab\"}")
                .body(not(containsString("not authorized")));
    }

    /** A Timestream for InfluxDB parameter group is checked on {@code db-parameter-group/<id>}, the id it was given. */
    @Test
    void aParameterGroupIsCheckedOnTheArnItsIdGives() {
        String id = adminJson("timestream-influxdb", "AmazonTimestreamInfluxDB.CreateDbParameterGroup", "1.0")
                .body("{\"name\":\"res-group-" + suffix() + "\"}").post("/").then().statusCode(200).extract()
                .path("id");
        String arn = "arn:aws:timestream-influxdb:" + REGION + ":" + ACCOUNT + ":db-parameter-group/" + id;

        User user = user("res-group");
        user.policy(allowOn("timestream-influxdb:GetDbParameterGroup", arn));
        send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"" + id + "\"}").statusCode(200).body("arn", equalTo(arn));
        refusedJson(send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"res" + suffix() + "\"}"), "timestream-influxdb:GetDbParameterGroup");
    }

    /** A Timestream for InfluxDB cluster reboot is checked on each member instance its {@code instanceIds} names. */
    @Test
    void aClusterRebootIsCheckedOnTheInstancesItNames() {
        User user = user("res-reboot");
        user.policy(allowOn("timestream-influxdb:RebootDbCluster", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"timestream-influxdb:RebootDbCluster\",\"Resource\":\"arn:aws:timestream-influxdb:" + REGION + ":"
                + ACCOUNT + ":db-instance/resdenied\"}");
        refusedJson(send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.RebootDbCluster", "1.0",
                "{\"dbClusterId\":\"rescluster\",\"instanceIds\":[\"resallowed\",\"resdenied\"]}"),
                "timestream-influxdb:RebootDbCluster");
        send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.RebootDbCluster", "1.0",
                "{\"dbClusterId\":\"rescluster\",\"instanceIds\":[\"resallowed\"]}")
                .body(containsString("ResourceNotFoundException"));
    }

    /**
     * A budget is checked on {@code budget/<name>}, with no Region, the ARN Budgets tags it under, and an update on the
     * budget its {@code NewBudget} names, not on one its billing view ARN gives.
     */
    @Test
    void aBudgetIsCheckedOnTheArnItsNameGives() {
        String name = "res-budget-" + suffix();
        String other = name + "-other";
        for (String budget : List.of(name, other)) {
            adminJson("budgets", "AWSBudgetServiceGateway.CreateBudget")
                    .body("{\"AccountId\":\"" + ACCOUNT + "\",\"Budget\":" + budget(budget, null) + "}").post("/")
                    .then().statusCode(200);
            cleanup.register(() -> adminJson("budgets", "AWSBudgetServiceGateway.DeleteBudget")
                    .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + budget + "\"}").post("/"));
        }
        String arn = "arn:aws:budgets::" + ACCOUNT + ":budget/" + name;
        adminJson("budgets", "AWSBudgetServiceGateway.TagResource").body("{\"ResourceARN\":\"" + arn
                + "\",\"ResourceTags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}").post("/").then().statusCode(200);

        User user = user("res-budget");
        user.policy(allowOn("budgets:ViewBudget", arn) + "," + allowOn("budgets:ModifyBudget", arn));
        budgets(user, "DescribeBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + name + "\"}")
                .statusCode(200);
        refusedJson(budgets(user, "DescribeBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + other
                + "\"}"), "budgets:ViewBudget");
        refusedJson(budgets(user, "UpdateBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"NewBudget\":"
                + budget(other, arn) + "}"), "budgets:ModifyBudget");
        budgets(user, "UpdateBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"NewBudget\":" + budget(name, null) + "}")
                .statusCode(200);
    }

    /**
     * A Cost and Usage Report definition is checked on {@code definition/<name>}, a put on the one its
     * {@code ReportDefinition.ReportName} names and a modify on the one its {@code ReportName} names, neither on one
     * its billing view ARN gives.
     */
    @Test
    void aReportDefinitionIsCheckedOnTheArnItsNameGives() {
        String name = "res-report-" + suffix();
        String arn = "arn:aws:cur:" + REGION + ":" + ACCOUNT + ":definition/" + name;
        User user = user("res-report");
        user.policy(allowOn("cur:PutReportDefinition", arn) + "," + allowOn("cur:ModifyReportDefinition", arn) + ","
                + allowOn("cur:DeleteReportDefinition", arn));
        refusedJson(cur(user, "PutReportDefinition", "{\"ReportDefinition\":" + reportDefinition(name + "-other", arn)
                + "}"), "cur:PutReportDefinition");
        cur(user, "PutReportDefinition", "{\"ReportDefinition\":" + reportDefinition(name, null) + "}").statusCode(200);
        cur(user, "ModifyReportDefinition", "{\"ReportName\":\"" + name + "\",\"ReportDefinition\":"
                + reportDefinition(name, arn + "-other") + "}").statusCode(200);
        refusedJson(cur(user, "ModifyReportDefinition", "{\"ReportName\":\"" + name + "-other\",\"ReportDefinition\":"
                + reportDefinition(name + "-other", arn) + "}"), "cur:ModifyReportDefinition");
        cur(user, "DeleteReportDefinition", "{\"ReportName\":\"" + name + "\"}").statusCode(200);
    }

    /** A service quota is checked on {@code <service code>/<quota code>}, the ARN Service Quotas gives it. */
    @Test
    void aServiceQuotaIsCheckedOnItsArn() {
        String arn = adminJson("servicequotas", "ServiceQuotasV20190624.GetServiceQuota")
                .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-2DC20C30\"}").post("/").then().statusCode(200)
                .extract().path("Quota.QuotaArn");
        assertEquals("arn:aws:servicequotas:" + REGION + ":" + ACCOUNT + ":codebuild/L-2DC20C30", arn);

        User user = user("res-quota");
        user.policy(allowOn("servicequotas:GetServiceQuota", arn));
        serviceQuota(user, "L-2DC20C30").statusCode(200);
        refusedJson(serviceQuota(user, "L-00000000"), "servicequotas:GetServiceQuota");
    }

    /** A translation is checked on the terminology its {@code TerminologyNames} give, {@code terminology/<name>}. */
    @Test
    void aTranslationIsCheckedOnTheTerminologyItNames() {
        String name = "res-terminology-" + suffix();
        String arn = "arn:aws:translate:" + REGION + ":" + ACCOUNT + ":terminology/" + name;
        String text = "\"Text\":\"hello\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"";
        String document = "\"Document\":{\"Content\":\""
                + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))
                + "\",\"ContentType\":\"text/plain\"},\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"";
        User user = user("res-terminology");
        user.policy(allowOn("translate:TranslateText", arn) + "," + allowOn("translate:TranslateDocument", arn));
        translate(user, "TranslateText", "{" + text + ",\"TerminologyNames\":[\"" + name + "\"]}").statusCode(200);
        refusedJson(translate(user, "TranslateText", "{" + text + ",\"TerminologyNames\":[\"" + name + "-other\"]}"),
                "translate:TranslateText");
        translate(user, "TranslateDocument", "{" + document + ",\"TerminologyNames\":[\"" + name + "\"]}")
                .statusCode(200);
        refusedJson(translate(user, "TranslateDocument", "{" + document + ",\"TerminologyNames\":[\"" + name
                + "-other\"]}"), "translate:TranslateDocument");
    }

    /** A monthly cost budget of the name, with a billing view ARN when one is given. */
    private static String budget(String name, String billingViewArn) {
        return "{\"BudgetName\":\"" + name + "\",\"BudgetType\":\"COST\",\"TimeUnit\":\"MONTHLY\",\"BudgetLimit\":"
                + "{\"Amount\":\"100\",\"Unit\":\"USD\"}" + (billingViewArn == null ? ""
                : ",\"BillingViewArn\":\"" + billingViewArn + "\"") + "}";
    }

    /** A daily Parquet report definition of the name, with a billing view ARN when one is given. */
    private static String reportDefinition(String name, String billingViewArn) {
        return "{\"ReportName\":\"" + name + "\",\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\","
                + "\"AdditionalSchemaElements\":[\"RESOURCES\"],\"S3Bucket\":\"my-bucket\",\"S3Prefix\":\"reports/\","
                + "\"S3Region\":\"" + REGION + "\"" + (billingViewArn == null ? ""
                : ",\"BillingViewArn\":\"" + billingViewArn + "\"") + "}";
    }

    private static ValidatableResponse budgets(User user, String operation, String body) {
        return user.json("budgets", "AWSBudgetServiceGateway." + operation, "1.1").body(body).post("/").then();
    }

    private static ValidatableResponse cur(User user, String operation, String body) {
        return user.json("cur", "AWSOrigamiServiceGatewayService." + operation, "1.1").body(body).post("/").then();
    }

    private static ValidatableResponse serviceQuota(User user, String quotaCode) {
        return user.json("servicequotas", "ServiceQuotasV20190624.GetServiceQuota", "1.1")
                .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + quotaCode + "\"}").post("/").then();
    }

    private static ValidatableResponse translate(User user, String operation, String body) {
        return user.json("translate", "AWSShineFrontendService_20170701." + operation, "1.1").body(body).post("/")
                .then();
    }

    private static ValidatableResponse codeBuild(User user, String operation, String body) {
        return user.json("codebuild", "CodeBuild_20161006." + operation, "1.1").body(body).post("/").then();
    }

    private static ValidatableResponse describeApplication(User user, String name) {
        return user.json("kinesisanalytics", "KinesisAnalytics_20180523.DescribeApplication", "1.1")
                .body("{\"ApplicationName\":\"" + name + "\"}").post("/").then();
    }

    /** The user's JSON-protocol request to the target, {@code <prefix>.<operation>}, with the body. */
    private static ValidatableResponse send(User user, String service, String target, String version, String body) {
        return user.json(service, target, version).body(body).post("/").then();
    }

    /** A Data Exports export of the name reading the cost and usage table, with an export ARN when one is given. */
    private static String export(String name, String exportArn) {
        return "{\"Name\":\"" + name + "\"," + (exportArn == null ? "" : "\"ExportArn\":\"" + exportArn + "\",")
                + "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"},"
                + "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"my-bucket\",\"S3Prefix\":\"out/\","
                + "\"S3Region\":\"" + REGION + "\",\"S3OutputConfigurations\":{\"Format\":\"PARQUET\",\"Compression\":"
                + "\"PARQUET\",\"OutputType\":\"CUSTOM\",\"Overwrite\":\"OVERWRITE_REPORT\"}}},"
                + "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}";
    }

    private static RequestSpecification adminJson(String service, String target) {
        return adminJson(service, target, "1.1");
    }

    private static RequestSpecification adminJson(String service, String target, String version) {
        return admin(service).header("X-Amz-Target", target).contentType("application/x-amz-json-" + version);
    }

    private static ValidatableResponse describeDeliveryStream(User user, String name) {
        return user.json("firehose", "Firehose_20150804.DescribeDeliveryStream", "1.1")
                .body("{\"DeliveryStreamName\":\"" + name + "\"}").post("/").then();
    }

    private static ValidatableResponse describeIdentityPool(User user, String id) {
        return user.json("cognito-identity", "AWSCognitoIdentityService.DescribeIdentityPool", "1.1")
                .body("{\"IdentityPoolId\":\"" + id + "\"}").post("/").then();
    }

    private static ValidatableResponse cloudMap(User user, String operation, String id) {
        return user.json("servicediscovery", "Route53AutoNaming_v20170314." + operation, "1.1")
                .body("{\"Id\":\"" + id + "\"}").post("/").then();
    }

    private static ValidatableResponse describeCertificate(User user, String arn) {
        return user.json("acm", "CertificateManager.DescribeCertificate", "1.1")
                .body("{\"CertificateArn\":\"" + arn + "\"}").post("/").then();
    }

    private static ValidatableResponse codePipeline(User user, String operation, String body) {
        return user.json("codepipeline", "CodePipeline_20150709." + operation, "1.1").body(body).post("/").then();
    }

    /** A pipeline with a source and a build stage, the two stages CodePipeline requires. */
    private static String pipeline(String name) {
        return "{\"pipeline\":{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/codepipeline-role\","
                + "\"artifactStore\":{\"type\":\"S3\",\"location\":\"codepipeline-artifacts\"},\"stages\":["
                + "{\"name\":\"Source\",\"actions\":[{\"name\":\"SourceAction\",\"actionTypeId\":{\"category\":\"Source\","
                + "\"owner\":\"AWS\",\"provider\":\"S3\",\"version\":\"1\"},\"runOrder\":1}]},"
                + "{\"name\":\"Build\",\"actions\":[{\"name\":\"BuildAction\",\"actionTypeId\":{\"category\":\"Build\","
                + "\"owner\":\"AWS\",\"provider\":\"CodeBuild\",\"version\":\"1\"},\"runOrder\":1}]}]}}";
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

    /** Puts a rule of the name on the bus {@code bus} names, a JSON member or none, and gives its ARN. */
    private String putRule(String name, String bus) {
        String arn = admin("events").header("X-Amz-Target", "AWSEvents.PutRule").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + name + "\",\"ScheduleExpression\":\"rate(1 hour)\"" + bus + "}")
                .post("/").then().statusCode(200).extract().path("RuleArn");
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteRule")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + name + "\"" + bus + "}").post("/"));
        return arn;
    }

    private static ValidatableResponse describeByName(User user, String operation, String name) {
        return user.json("events", "AWSEvents." + operation, "1.1").body("{\"Name\":\"" + name + "\"}").post("/").then();
    }

    private static ValidatableResponse describeRule(User user, String name, String bus) {
        return user.json("events", "AWSEvents.DescribeRule", "1.1").body("{\"Name\":\"" + name + "\"" + bus + "}")
                .post("/").then();
    }

    /** Publishes an event for each of {@code buses}, the bus of its entry as a JSON member or none. */
    private static ValidatableResponse putEvents(User user, String... buses) {
        StringBuilder entries = new StringBuilder();
        for (String bus : buses) {
            entries.append(entries.isEmpty() ? "" : ",")
                    .append("{\"Source\":\"res.test\",\"DetailType\":\"t\",\"Detail\":\"{}\"").append(bus).append('}');
        }
        return user.json("events", "AWSEvents.PutEvents", "1.1").body("{\"Entries\":[" + entries + "]}")
                .post("/").then();
    }

    private static String publishVersion(String arn) {
        return admin("states").header("X-Amz-Target", "AWSStepFunctions.PublishStateMachineVersion")
                .contentType("application/x-amz-json-1.0").body("{\"stateMachineArn\":\"" + arn + "\"}")
                .post("/").then().statusCode(200).extract().path("stateMachineVersionArn");
    }

    /** An alias {@code PROD} routing every execution to the version. */
    private static ValidatableResponse createAlias(String accessKeyId, String version) {
        return given().header("Authorization", auth(accessKeyId, "states"))
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachineAlias")
                .contentType("application/x-amz-json-1.0").body("{\"name\":\"PROD\",\"routingConfiguration\":"
                        + "[{\"stateMachineVersionArn\":\"" + version + "\",\"weight\":100}]}").post("/").then();
    }

    private static ValidatableResponse setRulePriority(User user, String rule) {
        return user.query("elasticloadbalancing", "SetRulePriorities").formParam("Version", "2015-12-01")
                .formParam("RulePriorities.member.1.RuleArn", rule).formParam("RulePriorities.member.1.Priority", "5")
                .post("/").then();
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
