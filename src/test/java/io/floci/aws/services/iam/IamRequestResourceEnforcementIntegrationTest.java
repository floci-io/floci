package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessRuntime;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ApplicationAutoScalingException;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScheduledActionRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTargetAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

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
    private static final String TARGETS = "arn:aws:application-autoscaling:" + REGION + ":" + ACCOUNT + ":scalable-target/";
    private static final String REDSHIFT = "arn:aws:redshift:" + REGION + ":" + ACCOUNT + ":";
    /** The X-Amz-Target prefix of ECS operations. */
    private static final String ECS_API = "AmazonEC2ContainerServiceV20141113.";
    private static final String WORKGROUPS = "arn:aws:redshift-serverless:" + REGION + ":" + ACCOUNT + ":workgroup/";
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

    /** A serverless workgroup's PostgreSQL container is not needed to check a request on it, so none starts. */
    @InjectMock
    RedshiftServerlessRuntime runtime;

    /** ECR's backing registry is not needed to check a request on a repository's metadata, so none starts. */
    @InjectMock
    EcrRegistryManager ecrRegistry;

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
     * An {@code elasticloadbalancing:ResourceTag} or {@code aws:ResourceTag} condition reads the tags Elastic Load
     * Balancing holds on the target group or the Classic Load Balancer a request names, as a tag call or the create
     * leaves them, so an allow on one team's tag lets the caller change that team's resources and not another's.
     */
    @Test
    void anElbResourceTagConditionReadsTheTagsOfTheTargetGroupAndTheClassicLoadBalancer() {
        String tagged = elbTargetGroup();
        String other = elbTargetGroup();
        admin("elasticloadbalancing").formParam("Action", "AddTags").formParam("Version", "2015-12-01")
                .formParam("ResourceArns.member.1", tagged).formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "data").post("/").then().statusCode(200);
        String taggedLoadBalancer = classicLoadBalancer("data");
        String otherLoadBalancer = classicLoadBalancer("web");

        User user = user("res-elb-team");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"elasticloadbalancing:ModifyTargetGroupAttributes\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"elasticloadbalancing:ResourceTag/team\":"
                + "\"data\"}}},{\"Effect\":\"Allow\",\"Action\":\"elasticloadbalancing:ConfigureHealthCheck\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        modifyAttributes(user, tagged).statusCode(200);
        refused(modifyAttributes(user, other), "elasticloadbalancing:ModifyTargetGroupAttributes");
        configureHealthCheck(user, taggedLoadBalancer).statusCode(200);
        refused(configureHealthCheck(user, otherLoadBalancer), "elasticloadbalancing:ConfigureHealthCheck");
    }

    /** An IP target group an administrator creates, deleted after the test; its ARN. */
    private String elbTargetGroup() {
        String arn = admin("elasticloadbalancing").formParam("Action", "CreateTargetGroup")
                .formParam("Version", "2015-12-01").formParam("Name", "res-tg-team-" + suffix())
                .formParam("Protocol", "HTTP").formParam("Port", "80").formParam("TargetType", "ip").post("/").then()
                .statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> admin("elasticloadbalancing").formParam("Action", "DeleteTargetGroup")
                .formParam("Version", "2015-12-01").formParam("TargetGroupArn", arn).post("/").then().statusCode(200));
        return arn;
    }

    /** A Classic Load Balancer an administrator creates, tagged with {@code team}, deleted after the test; its name. */
    private String classicLoadBalancer(String team) {
        String name = "res-clb-" + suffix();
        admin("elasticloadbalancing").formParam("Action", "CreateLoadBalancer").formParam("Version", "2012-06-01")
                .formParam("LoadBalancerName", name).formParam("Listeners.member.1.Protocol", "TCP")
                .formParam("Listeners.member.1.LoadBalancerPort", "443")
                .formParam("Listeners.member.1.InstancePort", "443").formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", team).post("/").then()
                .statusCode(200);
        cleanup.register(() -> admin("elasticloadbalancing").formParam("Action", "DeleteLoadBalancer")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerName", name).post("/").then()
                .statusCode(200));
        return name;
    }

    private static ValidatableResponse configureHealthCheck(User user, String loadBalancerName) {
        return user.query("elasticloadbalancing", "ConfigureHealthCheck").formParam("Version", "2012-06-01")
                .formParam("LoadBalancerName", loadBalancerName).formParam("HealthCheck.Target", "TCP:443")
                .formParam("HealthCheck.Interval", "10").formParam("HealthCheck.Timeout", "3")
                .formParam("HealthCheck.HealthyThreshold", "2").formParam("HealthCheck.UnhealthyThreshold", "2")
                .post("/").then();
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
     * A Step Functions Local {@code <stateMachineArn>#<test case>} is checked as given: it is no ARN AWS has, and
     * nothing botocore or the Service Reference says reads it otherwise. So a grant on the machine alone does not
     * reach a start naming a test case, a grant covering it does, and a deny on the machine alone does not apply.
     */
    @Test
    void aStateMachinesTestCaseIsCheckedAsGiven() {
        String arn = createStateMachine("res-tc-" + suffix());
        User user = user("res-tc");
        user.policy(allowOn("states:StartExecution", arn));
        refusedJson(startExecution(user, arn + "#"), "states:StartExecution");
        user.policy(allowOn("states:StartExecution", arn + "*"));
        startExecution(user, arn + "#").statusCode(200);

        user.policy(allowOn("states:StartExecution", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"states:StartExecution\","
                + "\"Resource\":\"" + arn + "\"}");
        startExecution(user, arn + "#").statusCode(200);
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

    /**
     * EventBridge V2 is authorized as the same {@code events} actions, on the bus or subscriber its request's ARN
     * names: a grant on one V2 bus publishes to it and to no other, the default bus never standing in for it, a deny
     * on it holds, a subscriber is checked on its own ARN rather than {@code *}, and a new bus on its name.
     */
    @Test
    void anEventBridgeV2RequestIsCheckedOnTheBusOrSubscriberItsArnNames() throws IOException {
        String bus = createEventBusV2("res-v2-" + suffix());
        String other = createEventBusV2("res-v2-other-" + suffix());
        User user = user("res-v2");

        user.policy(allowOn("events:PutEvents", bus));
        eventsV2(user, "PutEvents", putEventsV2(bus)).statusCode(200).body(not(containsString("ErrorCode")));
        refusedCbor(eventsV2(user, "PutEvents", putEventsV2(other)), "events:PutEvents");
        user.policy(allowOn("events:PutEvents", EVENTS + "event-bus/default"));
        refusedCbor(eventsV2(user, "PutEvents", putEventsV2(bus)), "events:PutEvents");
        user.policy(allowOn("events:PutEvents", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"events:PutEvents\","
                + "\"Resource\":\"" + bus + "\"}");
        refusedCbor(eventsV2(user, "PutEvents", putEventsV2(bus)), "events:PutEvents");
        eventsV2(user, "PutEvents", putEventsV2(other)).statusCode(200);

        String subscriber = EVENTS + "subscriber/res-v2-sub/" + suffix();
        user.policy(allowOn("events:DescribeSubscriber", subscriber));
        eventsV2(user, "DescribeSubscriber", Map.of("SubscriberArn", subscriber)).statusCode(404)
                .body(containsString("ResourceNotFoundException"));
        refusedCbor(eventsV2(user, "DescribeSubscriber", Map.of("SubscriberArn", subscriber + "0")),
                "events:DescribeSubscriber");

        String created = "res-v2-new-" + suffix();
        user.policy(allowOn("events:CreateEventBus", EVENTS + "event-busv2/" + created + "/*"));
        refusedCbor(eventsV2(user, "CreateEventBus", Map.of("Name", created + "-other", "ClientToken", suffix())),
                "events:CreateEventBus");
        String createdArn = CBOR_MAPPER.readTree(eventsV2(user, "CreateEventBus",
                Map.of("Name", created, "ClientToken", suffix())).statusCode(200).extract().asByteArray())
                .path("EventBusArn").asText();
        cleanup.register(() -> deleteEventBusV2(createdArn));
        assertTrue(createdArn.startsWith(EVENTS + "event-busv2/" + created + "/"), createdArn);

        // A subscriber's tags are checked on the subscriber it makes, not on the bus it names.
        String tagged = "res-v2-tagged-" + suffix();
        String role = "arn:aws:iam::" + ACCOUNT + ":role/res-v2-delivery";
        user.policy(allowOn("iam:PassRole", role) + "," + allowOn("events:CreateSubscriber", bus) + ","
                + allowOn("events:CreateSubscriber", EVENTS + "subscriber/" + tagged + "/*") + ","
                + allowOn("events:TagResource", EVENTS + "subscriber/*"));
        Map<String, Object> subscriberRequest = Map.of("Name", tagged, "EventBusArn", bus,
                "InvokeConfiguration", Map.of("TargetArn", "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":res-v2-queue",
                        "RoleArn", role),
                "Tags", Map.of("team", "orders"));
        String subscriberArn = CBOR_MAPPER.readTree(eventsV2(user, "CreateSubscriber", subscriberRequest)
                .statusCode(200).extract().asByteArray()).path("SubscriberArn").asText();
        cleanup.register(() -> deleteSubscriberV2(subscriberArn));
        user.policy(allowOn("iam:PassRole", role) + "," + allowOn("events:CreateSubscriber", bus) + ","
                + allowOn("events:CreateSubscriber", EVENTS + "subscriber/*") + ","
                + allowOn("events:TagResource", bus));
        refusedCbor(eventsV2(user, "CreateSubscriber", Map.of("Name", tagged + "-other", "EventBusArn", bus,
                "InvokeConfiguration", subscriberRequest.get("InvokeConfiguration"), "Tags", Map.of("team", "orders"))),
                "events:TagResource");

        // An EventBridge request adding the ARN of a V2 bus the caller may delete is still checked on its own bus.
        String locked = "res-v1-locked-" + suffix();
        admin("events").header("X-Amz-Target", "AWSEvents.CreateEventBus").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + locked + "\"}").post("/").then().statusCode(200);
        cleanup.register(() -> admin("events").header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"" + locked + "\"}").post("/"));
        user.policy(allowOn("events:DeleteEventBus", bus) + "," + allowOn("events:DescribeEventBus", bus));
        for (String operation : List.of("DeleteEventBus", "DescribeEventBus")) {
            refusedJson(user.json("events", "AWSEvents." + operation, "1.1")
                    .body("{\"Name\":\"" + locked + "\",\"EventBusArn\":\"" + bus + "\"}").post("/").then(),
                    "events:" + operation);
        }
        admin("events").header("X-Amz-Target", "AWSEvents.DescribeEventBus").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + locked + "\"}").post("/").then().statusCode(200);
    }

    @Test
    void aUserPoolIsCheckedOnTheArnItsIdNames() {
        String id = userPool(null);
        String arn = admin("cognito-idp").header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
                .contentType("application/x-amz-json-1.1").body("{\"UserPoolId\":\"" + id + "\"}")
                .post("/").then().statusCode(200).extract().path("UserPool.Arn");
        assertEquals("arn:aws:cognito-idp:" + REGION + ":" + ACCOUNT + ":userpool/" + id, arn);

        User user = user("res-pool");
        user.policy(allowOn("cognito-idp:DescribeUserPool", arn));
        describeUserPool(user, id).statusCode(200);
        refusedJson(describeUserPool(user, id + "x"), "cognito-idp:DescribeUserPool");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Cognito holds on the user pool a request names by its id, as
     * a tag call leaves them, so an allow on one team's tag lets the caller describe that team's pool and not
     * another's.
     */
    @Test
    void aCognitoResourceTagConditionReadsTheTagsOfTheUserPool() {
        String tagged = userPool("{\"team\":\"web\"}");
        String other = userPool("{\"team\":\"web\"}");
        adminJson("cognito-idp", "AWSCognitoIdentityProviderService.TagResource").body("{\"ResourceArn\":\""
                + "arn:aws:cognito-idp:" + REGION + ":" + ACCOUNT + ":userpool/" + tagged
                + "\",\"Tags\":{\"team\":\"data\"}}").post("/").then().statusCode(200);

        User user = user("res-pool-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"cognito-idp:DescribeUserPool\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeUserPool(user, tagged).statusCode(200);
        refusedJson(describeUserPool(user, other), "cognito-idp:DescribeUserPool");
    }

    /** The id of a user pool an administrator creates, with the {@code tags} object if any, deleted afterwards. */
    private String userPool(String tags) {
        String id = adminJson("cognito-idp", "AWSCognitoIdentityProviderService.CreateUserPool")
                .body("{\"PoolName\":\"res-pool-" + suffix() + "\"" + (tags == null ? "" : ",\"UserPoolTags\":" + tags)
                        + "}").post("/").then().statusCode(200).extract().path("UserPool.Id");
        cleanup.register(() -> adminJson("cognito-idp", "AWSCognitoIdentityProviderService.DeleteUserPool")
                .body("{\"UserPoolId\":\"" + id + "\"}").post("/"));
        return id;
    }

    /**
     * A key is named by its id or by an alias, and an alias names the key it points at, so a request naming a key by
     * an alias is checked on the key the alias names, as the handler resolves it: a grant on that key allows it, a
     * grant on the alias alone does not, and a deny on the key holds.
     */
    @Test
    void aKeyNamedByAnAliasIsCheckedOnTheKeyTheAliasNames() {
        String key = createPaymentKey();
        String alias = "alias/res-card-" + suffix();
        createPaymentAlias(alias, key).statusCode(200);

        User user = user("res-key");
        user.policy(allowOn("payment-cryptography:GetKey", key));
        getPaymentKey(user, alias).statusCode(200);
        getPaymentKey(user, key).statusCode(200);
        refusedJson(getPaymentKey(user, "alias/res-other-" + suffix()), "payment-cryptography:GetKey");

        user.policy(allowOn("payment-cryptography:GetKey", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"payment-cryptography:GetKey\",\"Resource\":\"" + key + "\"}");
        refusedJson(getPaymentKey(user, alias), "payment-cryptography:GetKey");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Payment Cryptography holds on the key a request names, so an
     * allow on one team's tag lets the caller read that team's key and not another's.
     */
    @Test
    void aPaymentCryptographyResourceTagConditionReadsTheTagsOfTheKey() {
        String tagged = taggedPaymentKey("data");
        String other = taggedPaymentKey("web");

        User user = user("res-key-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"payment-cryptography:GetKey\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        getPaymentKey(user, tagged).statusCode(200);
        refusedJson(getPaymentKey(user, other), "payment-cryptography:GetKey");
    }

    /** A Payment Cryptography key an administrator creates and tags with the team; its ARN. */
    private String taggedPaymentKey(String team) {
        String key = createPaymentKey();
        admin("payment-cryptography").header("X-Amz-Target", "PaymentCryptographyControlPlane.TagResource")
                .contentType("application/x-amz-json-1.0").body("{\"ResourceArn\":\"" + key
                        + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}")
                .post("/").then().statusCode(200);
        return key;
    }

    /**
     * A tagging request names its key by the ARN of one, as the reference takes it there, so a request naming it by
     * its id names no key for the check and is refused: a deny on the key is not skipped by naming it another way.
     */
    @Test
    void aTaggingRequestNamesItsKeyByItsArn() {
        String key = createPaymentKey();
        String id = key.substring(key.lastIndexOf('/') + 1);

        User user = user("res-key-tag");
        user.policy(allowOn("payment-cryptography:TagResource", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"payment-cryptography:TagResource\",\"Resource\":\"" + key + "\"}");
        // The key's own ARN is checked on the key, and the deny holds.
        refusedJson(tagPaymentKey(user, key), "payment-cryptography:TagResource");
        // Its id names no ARN for the check, so the request is refused rather than tagging the denied key.
        tagPaymentKey(user, id).statusCode(400).body(containsString("ValidationException"));
        admin("payment-cryptography").header("X-Amz-Target", "PaymentCryptographyControlPlane.ListTagsForResource")
                .contentType("application/x-amz-json-1.0").body("{\"ResourceArn\":\"" + key + "\"}")
                .post("/").then().statusCode(200).body("Tags", hasSize(0));
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

    /**
     * An {@code ecr:ResourceTag} or {@code aws:ResourceTag} condition reads the tags ECR holds on the repository a
     * request names, so an allow on one team's tag lets the caller read that team's repository and not another's.
     */
    @Test
    void anEcrResourceTagConditionReadsTheTagsOfTheRepository() {
        String tagged = ecrRepository("res-tag-repo-" + suffix(), "data");
        String other = ecrRepository("res-tag-repo-" + suffix(), "web");
        String arns = "arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/";

        User user = user("res-ecr-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ecr:DescribeRepositories\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"ecr:ResourceTag/team\":\"data\"}}},"
                + "{\"Effect\":\"Allow\",\"Action\":\"ecr:ListTagsForResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeRepositories(user, tagged).statusCode(200);
        refusedJson(describeRepositories(user, other), "ecr:DescribeRepositories");
        send(user, "ecr", "AmazonEC2ContainerRegistry_V20150921.ListTagsForResource", "1.1",
                "{\"resourceArn\":\"" + arns + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "ecr", "AmazonEC2ContainerRegistry_V20150921.ListTagsForResource", "1.1",
                "{\"resourceArn\":\"" + arns + other + "\"}"), "ecr:ListTagsForResource");
    }

    /**
     * An {@code ecs:ResourceTag} or {@code aws:ResourceTag} condition reads the tags ECS holds on the cluster a request
     * names, by its name or by its ARN, as a tag call leaves them, so an allow on one team's tag lets the caller read
     * that team's cluster and not another's.
     */
    @Test
    void anEcsResourceTagConditionReadsTheTagsOfTheCluster() {
        String tagged = ecsCluster("res-tag-cluster-" + suffix(), "web");
        String other = ecsCluster("res-tag-cluster-" + suffix(), "web");
        String arns = "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":cluster/";
        adminJson("ecs", ECS_API + "TagResource").body("{\"resourceArn\":\"" + arns + tagged
                + "\",\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}").post("/").then().statusCode(200);

        User user = user("res-ecs-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ecs:DescribeClusters\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"ecs:ResourceTag/team\":\"data\"}}},"
                + "{\"Effect\":\"Allow\",\"Action\":\"ecs:ListTagsForResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "ecs", ECS_API + "DescribeClusters", "1.1", "{\"clusters\":[\"" + tagged + "\"]}").statusCode(200);
        refusedJson(send(user, "ecs", ECS_API + "DescribeClusters", "1.1", "{\"clusters\":[\"" + other + "\"]}"),
                "ecs:DescribeClusters");
        send(user, "ecs", ECS_API + "ListTagsForResource", "1.1", "{\"resourceArn\":\"" + arns + tagged + "\"}")
                .statusCode(200);
        refusedJson(send(user, "ecs", ECS_API + "ListTagsForResource", "1.1", "{\"resourceArn\":\"" + arns + other
                + "\"}"), "ecs:ListTagsForResource");
    }

    /** An {@code aws:ResourceTag} condition reads the tags attached to the task set a delete names. */
    @Test
    void anEcsTaskSetResourceTagConditionReadsTheTagsOfTheTaskSet() {
        String cluster = "res-tag-task-set-" + suffix();
        String family = cluster + "-td";
        String service = cluster + "-svc";
        adminJson("ecs", ECS_API + "CreateCluster").body("{\"clusterName\":\"" + cluster + "\"}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("ecs", ECS_API + "DeleteCluster")
                .body("{\"cluster\":\"" + cluster + "\"}").post("/"));

        String taskDefinitionArn = adminJson("ecs", ECS_API + "RegisterTaskDefinition")
                .body("{\"family\":\"" + family + "\",\"containerDefinitions\":[{\"name\":\"web\","
                        + "\"image\":\"nginx\",\"memory\":128}]}")
                .post("/").then().statusCode(200).extract().path("taskDefinition.taskDefinitionArn");
        cleanup.register(() -> adminJson("ecs", ECS_API + "DeregisterTaskDefinition")
                .body("{\"taskDefinition\":\"" + taskDefinitionArn + "\"}").post("/"));

        adminJson("ecs", ECS_API + "CreateService")
                .body("{\"cluster\":\"" + cluster + "\",\"serviceName\":\"" + service
                        + "\",\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,"
                        + "\"deploymentController\":{\"type\":\"EXTERNAL\"}}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("ecs", ECS_API + "DeleteService")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + service + "\",\"force\":true}")
                .post("/"));

        String tagged = adminJson("ecs", ECS_API + "CreateTaskSet")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + service + "\","
                        + "\"taskDefinition\":\"" + family + "\",\"scale\":{\"value\":0,\"unit\":\"PERCENT\"},"
                        + "\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .post("/").then().statusCode(200).extract().path("taskSet.id");
        cleanup.register(() -> adminJson("ecs", ECS_API + "DeleteTaskSet")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + service
                        + "\",\"taskSet\":\"" + tagged + "\",\"force\":true}").post("/"));
        String other = adminJson("ecs", ECS_API + "CreateTaskSet")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + service + "\","
                        + "\"taskDefinition\":\"" + family + "\",\"scale\":{\"value\":0,\"unit\":\"PERCENT\"},"
                        + "\"tags\":[{\"key\":\"team\",\"value\":\"web\"}]}")
                .post("/").then().statusCode(200).extract().path("taskSet.id");
        cleanup.register(() -> adminJson("ecs", ECS_API + "DeleteTaskSet")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + service
                        + "\",\"taskSet\":\"" + other + "\",\"force\":true}").post("/"));

        String taggedArn = "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":task-set/" + cluster + "/" + service
                + "/" + tagged;
        User deniedUser = user("res-ecs-task-set-full-arn-deny");
        deniedUser.policy("{\"Effect\":\"Allow\",\"Action\":\"ecs:DeleteTaskSet\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"ecs:DeleteTaskSet\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        refusedJson(send(deniedUser, "ecs", ECS_API + "DeleteTaskSet", "1.1", "{\"cluster\":\"" + cluster + "\","
                + "\"service\":\"" + service + "\",\"taskSet\":\"" + taggedArn + "\",\"force\":true}"),
                "ecs:DeleteTaskSet");

        User user = user("res-ecs-task-set-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ecs:DeleteTaskSet\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "ecs", ECS_API + "DeleteTaskSet", "1.1", "{\"cluster\":\"" + cluster + "\","
                + "\"service\":\"" + service + "\",\"taskSet\":\"" + tagged + "\",\"force\":true}")
                .statusCode(200);
        refusedJson(send(user, "ecs", ECS_API + "DeleteTaskSet", "1.1", "{\"cluster\":\"" + cluster + "\","
                + "\"service\":\"" + service + "\",\"taskSet\":\"" + other + "\",\"force\":true}"),
                "ecs:DeleteTaskSet");
        String otherArn = "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":task-set/" + cluster + "/" + service
                + "/" + other;
        send(deniedUser, "ecs", ECS_API + "DeleteTaskSet", "1.1", "{\"cluster\":\"" + cluster + "\","
                + "\"service\":\"" + service + "\",\"taskSet\":\"" + otherArn + "\",\"force\":true}")
                .statusCode(200);
    }

    /**
     * An {@code ssm:resourceTag} or {@code aws:ResourceTag} condition reads the tags SSM holds on the parameter or the
     * document a request names, so an allow on one team's tag lets the caller read that team's and not another's.
     */
    @Test
    void anSsmResourceTagConditionReadsTheTagsOfTheParameterAndTheDocument() {
        String tagged = ssmParameter("/res-tag/" + suffix(), "data");
        String other = ssmParameter("/res-tag/" + suffix(), "web");
        String taggedDocument = ssmDocument("res-tag-doc-" + suffix(), "data");
        String otherDocument = ssmDocument("res-tag-doc-" + suffix(), "web");

        User user = user("res-ssm-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ssm:GetParameter\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"ssm:resourceTag/team\":\"data\"}}},"
                + "{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeDocument\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "ssm", "AmazonSSM.GetParameter", "1.1", "{\"Name\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "ssm", "AmazonSSM.GetParameter", "1.1", "{\"Name\":\"" + other + "\"}"),
                "ssm:GetParameter");
        send(user, "ssm", "AmazonSSM.DescribeDocument", "1.1", "{\"Name\":\"" + taggedDocument + "\"}")
                .statusCode(200);
        refusedJson(send(user, "ssm", "AmazonSSM.DescribeDocument", "1.1", "{\"Name\":\"" + otherDocument + "\"}"),
                "ssm:DescribeDocument");
    }

    /** An SSM String parameter of the name, tagged with the team, deleted afterwards. */
    private String ssmParameter(String name, String team) {
        return created("ssm", "AmazonSSM.PutParameter", "{\"Name\":\"" + name + "\",\"Value\":\"v\",\"Type\":"
                + "\"String\",\"Tags\":" + teamTag(team) + "}", "AmazonSSM.DeleteParameter",
                "{\"Name\":\"" + name + "\"}", name);
    }

    /** An SSM Command document of the name, tagged with the team, deleted afterwards. */
    private String ssmDocument(String name, String team) {
        return created("ssm", "AmazonSSM.CreateDocument", "{\"Name\":\"" + name + "\",\"DocumentType\":\"Command\","
                + "\"Content\":\"{\\\"schemaVersion\\\":\\\"2.2\\\",\\\"mainSteps\\\":[]}\",\"Tags\":"
                + teamTag(team) + "}", "AmazonSSM.DeleteDocument", "{\"Name\":\"" + name + "\"}", name);
    }

    /** An ECR repository of the name, tagged with the team, deleted afterwards. */
    private String ecrRepository(String name, String team) {
        return created("ecr", "AmazonEC2ContainerRegistry_V20150921.CreateRepository", "{\"repositoryName\":\""
                + name + "\",\"tags\":" + teamTag(team) + "}", "AmazonEC2ContainerRegistry_V20150921.DeleteRepository",
                "{\"repositoryName\":\"" + name + "\",\"force\":true}", name);
    }

    /** An ECS cluster of the name, tagged with the team, deleted afterwards. */
    private String ecsCluster(String name, String team) {
        return created("ecs", ECS_API + "CreateCluster", "{\"clusterName\":\"" + name + "\",\"tags\":[{\"key\":"
                + "\"team\",\"value\":\"" + team + "\"}]}", ECS_API + "DeleteCluster", "{\"cluster\":\"" + name + "\"}",
                name);
    }

    /**
     * {@code name}, after an administrator creates the resource with the JSON 1.1 {@code create} call and registers
     * the {@code delete} call that removes it after the test.
     */
    private String created(String service, String create, String createBody, String delete, String deleteBody,
                           String name) {
        adminJson(service, create).body(createBody).post("/").then().statusCode(200);
        cleanup.register(() -> adminJson(service, delete).body(deleteBody).post("/"));
        return name;
    }

    /** A tag list holding the one tag {@code team=<team>}. */
    private static String teamTag(String team) {
        return "[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]";
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags Firehose holds on the delivery stream a request names, as a
     * tag call leaves them, so an allow on one team's tag lets the caller describe that team's stream and not
     * another's.
     */
    @Test
    void aFirehoseResourceTagConditionReadsTheTagsOfTheDeliveryStream() {
        String tagged = deliveryStream("res-tag-stream-" + suffix(), "web");
        String other = deliveryStream("res-tag-stream-" + suffix(), "web");
        adminJson("firehose", "Firehose_20150804.TagDeliveryStream").body("{\"DeliveryStreamName\":\"" + tagged
                + "\",\"Tags\":" + teamTag("data") + "}").post("/").then().statusCode(200);

        User user = user("res-stream-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"firehose:DescribeDeliveryStream\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeDeliveryStream(user, tagged).statusCode(200);
        refusedJson(describeDeliveryStream(user, other), "firehose:DescribeDeliveryStream");
    }

    /** A Firehose delivery stream of the name, tagged with the team, deleted afterwards. */
    private String deliveryStream(String name, String team) {
        return created("firehose", "Firehose_20150804.CreateDeliveryStream", "{\"DeliveryStreamName\":\"" + name
                + "\",\"Tags\":" + teamTag(team) + "}", "Firehose_20150804.DeleteDeliveryStream",
                "{\"DeliveryStreamName\":\"" + name + "\"}", name);
    }

    /** An identity pool is checked on the ARN its id gives, the one Cognito tags it under. */
    @Test
    void anIdentityPoolIsCheckedOnTheArnItsIdGives() {
        String id = identityPool(null);
        String arn = identityPoolArn(id);
        tagIdentityPool(arn, "{\"a\":\"b\"}");

        User user = user("res-identity-pool");
        user.policy(allowOn("cognito-identity:DescribeIdentityPool", arn));
        describeIdentityPool(user, id).statusCode(200);
        refusedJson(describeIdentityPool(user, REGION + ":" + UUID.randomUUID()), "cognito-identity:DescribeIdentityPool");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Cognito holds on the identity pool a request names, as
     * TagResource leaves them, so an allow on one team's tag lets the caller describe that team's pool and not
     * another's.
     */
    @Test
    void aCognitoIdentityResourceTagConditionReadsTheTagsOfTheIdentityPool() {
        String tagged = identityPool("{\"team\":\"web\"}");
        String other = identityPool("{\"team\":\"web\"}");
        tagIdentityPool(identityPoolArn(tagged), "{\"team\":\"data\"}");

        User user = user("res-identity-pool-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"cognito-identity:DescribeIdentityPool\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeIdentityPool(user, tagged).statusCode(200);
        refusedJson(describeIdentityPool(user, other), "cognito-identity:DescribeIdentityPool");
    }

    /** The id of an identity pool an administrator creates, with the {@code tags} object if any, deleted afterwards. */
    private String identityPool(String tags) {
        String id = adminJson("cognito-identity", "AWSCognitoIdentityService.CreateIdentityPool")
                .body("{\"IdentityPoolName\":\"res pool " + suffix() + "\",\"AllowUnauthenticatedIdentities\":false"
                        + (tags == null ? "" : ",\"IdentityPoolTags\":" + tags) + "}")
                .post("/").then().statusCode(200).extract().path("IdentityPoolId");
        cleanup.register(() -> adminJson("cognito-identity", "AWSCognitoIdentityService.DeleteIdentityPool")
                .body("{\"IdentityPoolId\":\"" + id + "\"}").post("/"));
        return id;
    }

    private static String identityPoolArn(String id) {
        return "arn:aws:cognito-identity:" + REGION + ":" + ACCOUNT + ":identitypool/" + id;
    }

    private static void tagIdentityPool(String arn, String tags) {
        adminJson("cognito-identity", "AWSCognitoIdentityService.TagResource")
                .body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":" + tags + "}").post("/").then().statusCode(200);
    }

    /**
     * An SQS request is checked on the queue its QueueUrl reaches, over the Query and the JSON protocol alike, so a
     * grant on one queue's ARN does not reach another; and an {@code aws:ResourceTag} condition reads the tags SQS
     * holds on that queue, as TagQueue leaves them.
     */
    @Test
    void anSqsRequestIsCheckedOnTheQueueItsUrlReachesWithItsTags() {
        String tagged = sqsQueue("res-queue-" + suffix());
        String other = sqsQueue("res-queue-" + suffix());
        admin("sqs").formParam("Action", "TagQueue").formParam("QueueUrl", tagged).formParam("Tag.1.Key", "team")
                .formParam("Tag.1.Value", "data").post("/").then().statusCode(200);
        String queues = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":";

        User user = user("res-queue");
        user.policy(allowOn("sqs:SendMessage", queues + tagged.substring(tagged.lastIndexOf('/') + 1)) + ","
                + "{\"Effect\":\"Allow\",\"Action\":\"sqs:GetQueueAttributes\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        user.query("sqs", "SendMessage").formParam("QueueUrl", tagged).formParam("MessageBody", "x").post("/").then()
                .statusCode(200);
        refused(user.query("sqs", "SendMessage").formParam("QueueUrl", other).formParam("MessageBody", "x")
                .post("/").then(), "sqs:SendMessage");
        refusedJson(user.json("sqs", "AmazonSQS.SendMessage", "1.0").body("{\"QueueUrl\":\"" + other
                + "\",\"MessageBody\":\"x\"}").post("/").then(), "sqs:SendMessage");
        user.json("sqs", "AmazonSQS.GetQueueAttributes", "1.0").body("{\"QueueUrl\":\"" + tagged + "\"}").post("/")
                .then().statusCode(200);
        refusedJson(user.json("sqs", "AmazonSQS.GetQueueAttributes", "1.0").body("{\"QueueUrl\":\"" + other + "\"}")
                .post("/").then(), "sqs:GetQueueAttributes");
        refused(user.query("sqs", "GetQueueAttributes").formParam("QueueUrl", other).post("/").then(),
                "sqs:GetQueueAttributes");
    }

    /** An SQS queue an administrator creates, deleted after the test; its URL. */
    private String sqsQueue(String name) {
        String url = admin("sqs").formParam("Action", "CreateQueue").formParam("QueueName", name).post("/").then()
                .statusCode(200).extract().path("CreateQueueResponse.CreateQueueResult.QueueUrl");
        cleanup.register(() -> admin("sqs").formParam("Action", "DeleteQueue").formParam("QueueUrl", url).post("/")
                .then().statusCode(200));
        return url;
    }

    /**
     * An SNS request is checked on the topic its TopicArn names, from the generated table, so a grant on one topic
     * does not reach another; and an {@code aws:ResourceTag} condition reads the tags SNS holds on that topic, as
     * TagResource leaves them.
     */
    @Test
    void anSnsRequestIsCheckedOnTheTopicItNamesWithItsTags() {
        String tagged = snsTopic("res-topic-" + suffix());
        String other = snsTopic("res-topic-" + suffix());
        admin("sns").formParam("Action", "TagResource").formParam("ResourceArn", tagged)
                .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "data").post("/").then()
                .statusCode(200);

        User user = user("res-topic");
        user.policy(allowOn("sns:Publish", tagged) + ",{\"Effect\":\"Allow\",\"Action\":\"sns:GetTopicAttributes\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        user.query("sns", "Publish").formParam("TopicArn", tagged).formParam("Message", "x").post("/").then()
                .statusCode(200);
        refused(user.query("sns", "Publish").formParam("TopicArn", other).formParam("Message", "x").post("/")
                .then(), "sns:Publish");
        user.query("sns", "GetTopicAttributes").formParam("TopicArn", tagged).post("/").then().statusCode(200);
        refused(user.query("sns", "GetTopicAttributes").formParam("TopicArn", other).post("/").then(),
                "sns:GetTopicAttributes");
    }

    /** An SNS topic an administrator creates, deleted after the test; its ARN. */
    private String snsTopic(String name) {
        String arn = admin("sns").formParam("Action", "CreateTopic").formParam("Name", name).post("/").then()
                .statusCode(200).extract().path("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> admin("sns").formParam("Action", "DeleteTopic").formParam("TopicArn", arn).post("/")
                .then().statusCode(200));
        return arn;
    }

    /**
     * A Kinesis request is checked on the stream its StreamName or StreamARN names, from the generated table, so a
     * grant on one stream does not reach another; and an {@code aws:ResourceTag} condition reads the tags Kinesis
     * holds on that stream, as AddTagsToStream leaves them.
     */
    @Test
    void aKinesisRequestIsCheckedOnTheStreamItNamesWithItsTags() {
        String tagged = kinesisStream("res-stream-" + suffix());
        String other = kinesisStream("res-stream-" + suffix());
        adminJson("kinesis", "Kinesis_20131202.AddTagsToStream").body("{\"StreamName\":\"" + tagged
                + "\",\"Tags\":{\"team\":\"data\"}}").post("/").then().statusCode(200);
        String streams = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT + ":stream/";

        User user = user("res-stream");
        user.policy(allowOn("kinesis:DescribeStreamSummary", streams + tagged) + ",{\"Effect\":\"Allow\","
                + "\"Action\":\"kinesis:ListShards\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":"
                + "{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "kinesis", "Kinesis_20131202.DescribeStreamSummary", "1.1", "{\"StreamName\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(send(user, "kinesis", "Kinesis_20131202.DescribeStreamSummary", "1.1",
                "{\"StreamARN\":\"" + streams + other + "\"}"), "kinesis:DescribeStreamSummary");
        send(user, "kinesis", "Kinesis_20131202.ListShards", "1.1", "{\"StreamName\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(send(user, "kinesis", "Kinesis_20131202.ListShards", "1.1", "{\"StreamName\":\"" + other + "\"}"),
                "kinesis:ListShards");
    }

    /** A Kinesis stream of one shard an administrator creates, deleted after the test; its name. */
    private String kinesisStream(String name) {
        adminJson("kinesis", "Kinesis_20131202.CreateStream").body("{\"StreamName\":\"" + name
                + "\",\"ShardCount\":1}").post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("kinesis", "Kinesis_20131202.DeleteStream").body("{\"StreamName\":\""
                + name + "\"}").post("/").then().statusCode(200));
        return name;
    }

    /**
     * EC2 Instance Connect's SendSSHPublicKey and SendSerialConsoleSSHPublicKey are checked on the instance their
     * {@code InstanceId} names, under the ARN EC2 gives it, so a grant on one instance does not reach another.
     */
    @Test
    void anInstanceConnectKeyIsCheckedOnTheInstanceItIsSentTo() {
        String instanceId = admin("ec2").formParam("Action", "RunInstances").formParam("Version", "2016-11-15")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1").post("/").then().statusCode(200).extract()
                .path("RunInstancesResponse.instancesSet.item.instanceId");
        cleanup.register(() -> admin("ec2").formParam("Action", "TerminateInstances").formParam("Version", "2016-11-15")
                .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200));
        // Mock mode settles a launch on the first DescribeInstances; a key needs a running instance.
        admin("ec2").formParam("Action", "DescribeInstances").formParam("Version", "2016-11-15")
                .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
        String instance = "arn:aws:ec2:" + REGION + ":" + ACCOUNT + ":instance/";

        User user = user("res-instance-connect");
        user.policy(allowOn("ec2-instance-connect:SendSSHPublicKey", instance + instanceId) + ","
                + allowOn("ec2-instance-connect:SendSerialConsoleSSHPublicKey", instance + instanceId));
        sendSshPublicKey(user, "SendSSHPublicKey", instanceId).statusCode(200);
        refusedJson(sendSshPublicKey(user, "SendSSHPublicKey", "i-0000000000000000f"),
                "ec2-instance-connect:SendSSHPublicKey");
        refusedJson(sendSshPublicKey(user, "SendSerialConsoleSSHPublicKey", "i-0000000000000000f"),
                "ec2-instance-connect:SendSerialConsoleSSHPublicKey");
    }

    private static ValidatableResponse sendSshPublicKey(User user, String operation, String instanceId) {
        return user.json("ec2-instance-connect", "AWSEC2InstanceConnectService." + operation, "1.1")
                .body("{\"InstanceId\":\"" + instanceId + "\",\"InstanceOSUser\":\"ec2-user\",\"SSHPublicKey\":"
                        + "\"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl "
                        + "user@host\"}").post("/").then();
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
        String arn = certificate(null);
        assertTrue(arn.startsWith("arn:aws:acm:" + REGION + ":" + ACCOUNT + ":certificate/"), arn);

        User user = user("res-certificate");
        user.policy(allowOn("acm:DescribeCertificate", arn));
        describeCertificate(user, arn).statusCode(200);
        refusedJson(describeCertificate(user, "arn:aws:acm:" + REGION + ":" + ACCOUNT + ":certificate/" + UUID.randomUUID()),
                "acm:DescribeCertificate");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags ACM holds on the certificate a request names, as a tag call
     * leaves them, so an allow on one team's tag lets the caller read that team's certificate and not another's, and
     * a deny on it holds, also for the certificate's id under another Region's ARN, which is checked in the request's
     * Region too, where the handler reads it.
     */
    @Test
    void anAcmResourceTagConditionReadsTheTagsOfTheCertificate() {
        String tagged = certificate(teamTag("web"));
        String other = certificate(teamTag("web"));
        adminJson("acm", "CertificateManager.AddTagsToCertificate").body("{\"CertificateArn\":\"" + tagged
                + "\",\"Tags\":" + teamTag("data") + "}").post("/").then().statusCode(200);

        User user = user("res-acm-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"acm:DescribeCertificate\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeCertificate(user, tagged).statusCode(200);
        refusedJson(describeCertificate(user, other), "acm:DescribeCertificate");

        User denied = user("res-acm-tag-deny");
        denied.policy(allowOn("acm:DescribeCertificate", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"acm:DescribeCertificate\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":"
                + "{\"aws:ResourceTag/team\":\"data\"}}}");
        refusedJson(describeCertificate(denied, tagged), "acm:DescribeCertificate");
        describeCertificate(denied, other).statusCode(200);
        refusedJson(describeCertificate(denied, tagged.replace(":" + REGION + ":", ":eu-west-1:")),
                "acm:DescribeCertificate");
    }

    /** The ARN of a certificate an administrator requests, with the {@code tags} list if any, deleted afterwards. */
    private String certificate(String tags) {
        String arn = adminJson("acm", "CertificateManager.RequestCertificate")
                .body("{\"DomainName\":\"res-" + suffix() + ".example.com\"" + (tags == null ? "" : ",\"Tags\":" + tags)
                        + "}").post("/").then().statusCode(200).extract().path("CertificateArn");
        cleanup.register(() -> adminJson("acm", "CertificateManager.DeleteCertificate")
                .body("{\"CertificateArn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    /**
     * A pipeline is checked on the ARN its name gives, a request naming it by a bare {@code name} too, and a webhook
     * on {@code webhook:<name>}, the ARN CodePipeline gives it.
     */
    @Test
    void aPipelineAndAWebhookAreCheckedOnTheArnsTheirNamesGive() {
        String name = "res-pipeline-" + suffix();
        createPipeline(name, null);
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags CodePipeline holds on the pipeline a request names, as
     * TagResource leaves them, so an allow on one team's tag lets the caller read that team's pipeline and not
     * another's.
     */
    @Test
    void aCodePipelineResourceTagConditionReadsTheTagsOfThePipeline() {
        String web = "[{\"key\":\"team\",\"value\":\"web\"}]";
        String tagged = "res-pipeline-tagged-" + suffix();
        String other = "res-pipeline-other-" + suffix();
        createPipeline(tagged, web);
        createPipeline(other, web);
        adminJson("codepipeline", "CodePipeline_20150709.TagResource")
                .body("{\"resourceArn\":\"arn:aws:codepipeline:" + REGION + ":" + ACCOUNT + ":" + tagged
                        + "\",\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-pipeline-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"codepipeline:GetPipeline\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        codePipeline(user, "GetPipeline", "{\"name\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(codePipeline(user, "GetPipeline", "{\"name\":\"" + other + "\"}"), "codepipeline:GetPipeline");
    }

    /** Creates the pipeline {@code name} as an administrator, with the {@code tags} list if any, deleted afterwards. */
    private void createPipeline(String name, String tags) {
        adminJson("codepipeline", "CodePipeline_20150709.CreatePipeline").body(pipeline(name, tags)).post("/")
                .then().statusCode(200);
        cleanup.register(() -> adminJson("codepipeline", "CodePipeline_20150709.DeletePipeline")
                .body("{\"name\":\"" + name + "\"}").post("/"));
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
        createPipeline(allowed, null);
        createPipeline(other, null);
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
        createPipeline(name, null);
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags Cloud Map holds on the namespace or the service a request
     * names by its id, as a tag call or the create leaves them, so an allow on one team's tag lets the caller read that
     * team's namespace and service and not another's.
     */
    @Test
    void aCloudMapResourceTagConditionReadsTheTagsOfTheNamespaceAndTheService() {
        String tagged = cloudMapNamespace("res-ns-team-" + suffix());
        String other = cloudMapNamespace("res-ns-team-" + suffix());
        adminJson("servicediscovery", "Route53AutoNaming_v20170314.TagResource").body("{\"ResourceARN\":\""
                + "arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT + ":namespace/" + tagged + "\",\"Tags\":"
                + teamTag("data") + "}").post("/").then().statusCode(200);
        String taggedService = cloudMapService(tagged, "data");
        String otherService = cloudMapService(other, "web");

        User user = user("res-cloud-map-team");
        user.policy(onTeam("servicediscovery:GetNamespace") + "," + onTeam("servicediscovery:GetService"));
        cloudMap(user, "GetNamespace", tagged).statusCode(200);
        refusedJson(cloudMap(user, "GetNamespace", other), "servicediscovery:GetNamespace");
        cloudMap(user, "GetService", taggedService).statusCode(200);
        refusedJson(cloudMap(user, "GetService", otherService), "servicediscovery:GetService");
    }

    /**
     * Cloud Map's handlers find a namespace by its id in every Region, so a request sent to another Region acts on it
     * and is checked with its tags: a deny on its team's tag holds there too.
     */
    @Test
    void aCloudMapTagDenyHoldsForARequestSentToAnotherRegion() {
        String namespaceId = cloudMapNamespace("res-ns-deny-" + suffix());
        adminJson("servicediscovery", "Route53AutoNaming_v20170314.TagResource").body("{\"ResourceARN\":\""
                + "arn:aws:servicediscovery:" + REGION + ":" + ACCOUNT + ":namespace/" + namespaceId + "\",\"Tags\":"
                + teamTag("data") + "}").post("/").then().statusCode(200);

        User user = user("res-cloud-map-deny");
        user.policy(allowOn("servicediscovery:GetNamespace", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"servicediscovery:GetNamespace\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":"
                + "{\"aws:ResourceTag/team\":\"data\"}}}");
        refusedJson(given().header("Authorization", auth(user.accessKeyId(), "us-west-2", "servicediscovery"))
                .header("X-Amz-Target", "Route53AutoNaming_v20170314.GetNamespace")
                .contentType("application/x-amz-json-1.1").body("{\"Id\":\"" + namespaceId + "\"}").post("/").then(),
                "servicediscovery:GetNamespace");
    }

    /** An allow of {@code action} on every resource whose {@code team} tag is {@code data}. */
    private static String onTeam(String action) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}";
    }

    /** A Cloud Map service in the namespace, tagged with the team when created and deleted after the test; its id. */
    private String cloudMapService(String namespaceId, String team) {
        String id = adminJson("servicediscovery", "Route53AutoNaming_v20170314.CreateService")
                .body("{\"Name\":\"res-svc-" + suffix() + "\",\"NamespaceId\":\"" + namespaceId + "\",\"Tags\":"
                        + teamTag(team) + "}").post("/").then().statusCode(200).extract().path("Service.Id");
        cleanup.register(() -> adminJson("servicediscovery", "Route53AutoNaming_v20170314.DeleteService")
                .body("{\"Id\":\"" + id + "\"}").post("/").then().statusCode(200));
        return id;
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
     * A build is checked on the project CodeBuild holds it under, and one it holds none of on {@code *}: nothing
     * botocore or the Service Reference says gives a build id's project. A project is checked on the ARN its name
     * gives, a list naming it by name and by ARN alike.
     */
    @Test
    void aBuildIsCheckedOnTheProjectItIsHeldUnder() {
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
        // CodeBuild holds no build of these ids, so none names a project and each request is checked on *.
        refusedJson(codeBuild(user, "BatchGetBuilds", "{\"ids\":[\"" + project + ":1\"]}"), "codebuild:BatchGetBuilds");
        refusedJson(codeBuild(user, "StopBuild", "{\"id\":\"" + project + ":1\"}"), "codebuild:StopBuild");
        User any = user("res-build-any");
        any.policy(allowOn("codebuild:BatchGetBuilds", "*") + "," + allowOn("codebuild:StopBuild", "*"));
        codeBuild(any, "BatchGetBuilds", "{\"ids\":[\"" + project + ":1\"]}").statusCode(200);
        codeBuild(any, "StopBuild", "{\"id\":\"" + project + ":1\"}").statusCode(400)
                .body(containsString("ResourceNotFoundException"));
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags CodeBuild holds on a project and a report group, so an allow
     * on one team's tag lets the caller read that team's and not another's.
     */
    @Test
    void aCodeBuildResourceTagConditionReadsTheTagsOfTheProjectAndTheReportGroup() {
        String tagged = codeBuildProject("res-tag-project-" + suffix(), "data");
        String other = codeBuildProject("res-tag-project-" + suffix(), "web");
        String taggedGroup = codeBuildReportGroup("res-tag-group-" + suffix(), "data");
        String otherGroup = codeBuildReportGroup("res-tag-group-" + suffix(), "web");

        User user = user("res-codebuild-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"codebuild:BatchGetProjects\","
                + "\"codebuild:BatchGetReportGroups\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        codeBuild(user, "BatchGetProjects", "{\"names\":[\"" + tagged + "\"]}").statusCode(200);
        refusedJson(codeBuild(user, "BatchGetProjects", "{\"names\":[\"" + other + "\"]}"),
                "codebuild:BatchGetProjects");
        codeBuild(user, "BatchGetReportGroups", "{\"reportGroupArns\":[\"" + taggedGroup + "\"]}").statusCode(200);
        refusedJson(codeBuild(user, "BatchGetReportGroups", "{\"reportGroupArns\":[\"" + otherGroup + "\"]}"),
                "codebuild:BatchGetReportGroups");
    }

    private String codeBuildProject(String name, String team) {
        adminJson("codebuild", "CodeBuild_20161006.CreateProject").body("{\"name\":\"" + name
                + "\",\"source\":{\"type\":\"NO_SOURCE\",\"buildspec\":\"version: 0.2\"},"
                + "\"artifacts\":{\"type\":\"NO_ARTIFACTS\"},\"environment\":{\"type\":\"LINUX_CONTAINER\","
                + "\"image\":\"aws/codebuild/standard:7.0\",\"computeType\":\"BUILD_GENERAL1_SMALL\"},"
                + "\"serviceRole\":\"arn:aws:iam::" + ACCOUNT + ":role/codebuild-role\","
                + "\"tags\":[{\"key\":\"team\",\"value\":\"" + team + "\"}]}").post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("codebuild", "CodeBuild_20161006.DeleteProject")
                .body("{\"name\":\"" + name + "\"}").post("/"));
        return name;
    }

    private String codeBuildReportGroup(String name, String team) {
        String arn = adminJson("codebuild", "CodeBuild_20161006.CreateReportGroup").body("{\"name\":\"" + name
                + "\",\"type\":\"TEST\",\"exportConfig\":{\"exportConfigType\":\"NO_EXPORT\"},"
                + "\"tags\":[{\"key\":\"team\",\"value\":\"" + team + "\"}]}").post("/").then().statusCode(200)
                .extract().path("reportGroup.arn");
        cleanup.register(() -> adminJson("codebuild", "CodeBuild_20161006.DeleteReportGroup")
                .body("{\"arn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    /** A Managed Service for Apache Flink application is checked on the ARN its name gives. */
    @Test
    void anApplicationIsCheckedOnTheArnItsNameGives() {
        String name = "res-app-" + suffix();
        String arn = application(name, null);
        assertEquals("arn:aws:kinesisanalytics:" + REGION + ":" + ACCOUNT + ":application/" + name, arn);

        User user = user("res-application");
        user.policy(allowOn("kinesisanalytics:DescribeApplication", arn));
        describeApplication(user, name).statusCode(200);
        refusedJson(describeApplication(user, name + "-other"), "kinesisanalytics:DescribeApplication");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Managed Service for Apache Flink holds on the application a
     * request names, as TagResource leaves them, so an allow on one team's tag lets the caller describe that team's
     * application and not another's.
     */
    @Test
    void aKinesisAnalyticsResourceTagConditionReadsTheTagsOfTheApplication() {
        String web = "[{\"Key\":\"team\",\"Value\":\"web\"}]";
        String tagged = "res-app-tagged-" + suffix();
        String other = "res-app-other-" + suffix();
        String taggedArn = application(tagged, web);
        application(other, web);
        adminJson("kinesisanalytics", "KinesisAnalytics_20180523.TagResource")
                .body("{\"ResourceARN\":\"" + taggedArn + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-application-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"kinesisanalytics:DescribeApplication\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeApplication(user, tagged).statusCode(200);
        refusedJson(describeApplication(user, other), "kinesisanalytics:DescribeApplication");
    }

    /**
     * The ARN of the application {@code name} an administrator creates, with the {@code tags} list if any, deleted
     * afterwards.
     */
    private String application(String name, String tags) {
        String arn = adminJson("kinesisanalytics", "KinesisAnalytics_20180523.CreateApplication")
                .body("{\"ApplicationName\":\"" + name + "\",\"RuntimeEnvironment\":\"FLINK-1_18\","
                        + "\"ServiceExecutionRole\":\"arn:aws:iam::" + ACCOUNT + ":role/flink-role\""
                        + (tags == null ? "" : ",\"Tags\":" + tags) + "}")
                .post("/").then().statusCode(200).extract().path("ApplicationDetail.ApplicationARN");
        cleanup.register(() -> adminJson("kinesisanalytics", "KinesisAnalytics_20180523.DeleteApplication")
                .body("{\"ApplicationName\":\"" + name + "\",\"CreateTimestamp\":"
                        + adminJson("kinesisanalytics", "KinesisAnalytics_20180523.DescribeApplication")
                        .body("{\"ApplicationName\":\"" + name + "\"}").post("/").then().statusCode(200).extract()
                        .jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                        .getString("ApplicationDetail.CreateTimestamp") + "}").post("/"));
        return arn;
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
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{}").statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{\"WorkGroup\":null}").statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{\"WorkGroup\":\"primary\"}").statusCode(200)
                .body("WorkGroup.Name", equalTo("primary"));
        refusedJson(send(user, "athena", "AmazonAthena.ListQueryExecutions", "1.1", "{\"WorkGroup\":\"res-other\"}"),
                "athena:ListQueryExecutions");
        refusedJson(send(user, "athena", "AmazonAthena.StartQueryExecution", "1.1", "{\"QueryString\":\"SELECT 1\"}"),
                "athena:StartQueryExecution");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Athena holds on a workgroup and a data catalog, so an allow
     * on one team's tag lets the caller read that team's and not another's.
     */
    @Test
    void anAthenaResourceTagConditionReadsTheTagsOfTheWorkgroupAndTheDataCatalog() {
        String workGroup = athenaTagged("CreateWorkGroup", "res-tag-wg-" + suffix(), "data");
        String otherWorkGroup = athenaTagged("CreateWorkGroup", "res-tag-wg-" + suffix(), "web");
        String catalog = athenaTagged("CreateDataCatalog", "res_tag_cat_" + suffix(), "data");
        String otherCatalog = athenaTagged("CreateDataCatalog", "res_tag_cat_" + suffix(), "web");

        User user = user("res-athena-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"athena:GetWorkGroup\",\"athena:GetDataCatalog\"],"
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{\"WorkGroup\":\"" + workGroup + "\"}")
                .statusCode(200);
        refusedJson(send(user, "athena", "AmazonAthena.GetWorkGroup", "1.1", "{\"WorkGroup\":\"" + otherWorkGroup
                + "\"}"), "athena:GetWorkGroup");
        send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{\"Name\":\"" + catalog + "\"}").statusCode(200);
        refusedJson(send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{\"Name\":\"" + otherCatalog
                + "\"}"), "athena:GetDataCatalog");
    }

    /** An Athena workgroup or {@code LAMBDA} data catalog of the name, tagged with the team, deleted afterwards. */
    private String athenaTagged(String operation, String name, String team) {
        String body = "CreateWorkGroup".equals(operation) ? "{\"Name\":\"" + name + "\""
                : "{\"Name\":\"" + name + "\",\"Type\":\"LAMBDA\"";
        adminJson("athena", "AmazonAthena." + operation).body(body + ",\"Tags\":[{\"Key\":\"team\",\"Value\":\""
                + team + "\"}]}").post("/").then().statusCode(200);
        if ("CreateWorkGroup".equals(operation)) {
            cleanup.register(() -> adminJson("athena", "AmazonAthena.DeleteWorkGroup")
                    .body("{\"WorkGroup\":\"" + name + "\"}").post("/"));
        } else {
            cleanup.register(() -> adminJson("athena", "AmazonAthena.DeleteDataCatalog")
                    .body("{\"Name\":\"" + name + "\"}").post("/"));
        }
        return name;
    }

    /**
     * An Athena catalog read is checked on the catalog its {@code CatalogName} names, and Floci reads the metadata of
     * none but the account's Glue Data Catalog: a catalog of another type is refused, so a grant on it does not reach
     * the Glue data, nor does one on a Glue catalog reach {@code AwsDataCatalog}'s grant.
     */
    @Test
    void aCatalogReadIsCheckedOnTheCatalogItNamesAndReadsNoOther() {
        String catalogs = "arn:aws:athena:" + REGION + ":" + ACCOUNT + ":datacatalog/";
        String glue = "res-glue-" + suffix();
        String lambda = "res-lambda-" + suffix();
        for (String catalog : List.of("{\"Name\":\"" + glue + "\",\"Type\":\"GLUE\",\"Parameters\":{\"catalog-id\":\""
                        + ACCOUNT + "\"}}",
                "{\"Name\":\"" + lambda + "\",\"Type\":\"LAMBDA\",\"Parameters\":{\"function\":\"arn:aws:lambda:"
                        + REGION + ":" + ACCOUNT + ":function:meta\"}}")) {
            adminJson("athena", "AmazonAthena.CreateDataCatalog").body(catalog).post("/").then().statusCode(200);
        }
        for (String name : List.of(glue, lambda)) {
            cleanup.register(() -> adminJson("athena", "AmazonAthena.DeleteDataCatalog")
                    .body("{\"Name\":\"" + name + "\"}").post("/"));
        }
        User user = user("res-catalogs");
        user.policy(allowOn("athena:ListDatabases", catalogs + glue) + ","
                + allowOn("athena:ListDatabases", catalogs + lambda));
        send(user, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"" + glue + "\"}")
                .statusCode(200);
        send(user, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"" + lambda + "\"}")
                .statusCode(400).body("__type", equalTo("MetadataException"));
        refusedJson(send(user, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"AwsDataCatalog\"}"),
                "athena:ListDatabases");
        refusedJson(send(user, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":\"res-other\"}"),
                "athena:ListDatabases");
    }

    /**
     * Athena authorization checks the default Glue catalog before model validation rejects a missing required
     * catalog name. A grant reaches validation; a deny still refuses the request before it.
     */
    @Test
    void aCatalogRequestNamingNoCatalogIsCheckedOnTheGlueOne() {
        String glue = "arn:aws:athena:" + REGION + ":" + ACCOUNT + ":datacatalog/AwsDataCatalog";
        User user = user("res-glue-catalog");
        user.policy(allowOn("athena:GetDataCatalog", glue) + "," + allowOn("athena:ListDatabases", glue));
        send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{}").statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{\"Name\":null}").statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        send(user, "athena", "AmazonAthena.ListDatabases", "1.1", "{\"CatalogName\":null}").statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{\"Name\":\"AwsDataCatalog\"}").statusCode(200)
                .body("DataCatalog.Name", equalTo("AwsDataCatalog"));
        refusedJson(send(user, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{\"Name\":\"res-other\"}"),
                "athena:GetDataCatalog");
        User denied = user("res-glue-denied");
        denied.policy(allowOn("athena:GetDataCatalog", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"athena:GetDataCatalog\",\"Resource\":\"" + glue + "\"}");
        refusedJson(send(denied, "athena", "AmazonAthena.GetDataCatalog", "1.1", "{}"), "athena:GetDataCatalog");
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
     * An {@code aws:ResourceTag} condition reads the tags Pricing Calculator holds on the workload estimate a request
     * names by its id, the ones it was created with, so an allow on one team's tag lets the caller read that team's
     * estimate and not another's.
     */
    @Test
    void aPricingCalculatorResourceTagConditionReadsTheTagsOfTheWorkloadEstimate() {
        String tagged = workloadEstimate("data");
        String other = workloadEstimate("web");

        User user = user("res-estimate-team");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"bcm-pricing-calculator:GetWorkloadEstimate\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "bcm-pricing-calculator", "AWSBCMPricingCalculator.GetWorkloadEstimate", "1.0",
                "{\"identifier\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "bcm-pricing-calculator", "AWSBCMPricingCalculator.GetWorkloadEstimate", "1.0",
                "{\"identifier\":\"" + other + "\"}"), "bcm-pricing-calculator:GetWorkloadEstimate");
    }

    /** A workload estimate an administrator creates, tagged with {@code team}, deleted after the test; its id. */
    private String workloadEstimate(String team) {
        String id = adminJson("bcm-pricing-calculator", "AWSBCMPricingCalculator.CreateWorkloadEstimate", "1.0")
                .body("{\"name\":\"res-estimate-" + team + "\",\"clientToken\":\"" + suffix() + "\",\"tags\":"
                        + "{\"team\":\"" + team + "\"}}").post("/").then().statusCode(200).extract().path("id");
        cleanup.register(() -> adminJson("bcm-pricing-calculator", "AWSBCMPricingCalculator.DeleteWorkloadEstimate",
                "1.0").body("{\"identifier\":\"" + id + "\"}").post("/").then().statusCode(200));
        return id;
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
     * An {@code aws:ResourceTag} condition reads the tags Cost Explorer holds on the anomaly monitor a request names,
     * as TagResource leaves them, so an allow on one team's tag lets the caller read that team's monitor and not
     * another's.
     */
    @Test
    void aCostExplorerResourceTagConditionReadsTheTagsOfTheAnomalyMonitor() {
        String tagged = anomalyMonitor("[{\"Key\":\"team\",\"Value\":\"web\"}]");
        String other = anomalyMonitor("[{\"Key\":\"team\",\"Value\":\"web\"}]");
        adminJson("ce", "AWSInsightsIndexService.TagResource")
                .body("{\"ResourceArn\":\"" + tagged + "\",\"ResourceTags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-monitor-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"ce:GetAnomalyMonitors\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "ce", "AWSInsightsIndexService.GetAnomalyMonitors", "1.1",
                "{\"MonitorArnList\":[\"" + tagged + "\"]}").statusCode(200);
        refusedJson(send(user, "ce", "AWSInsightsIndexService.GetAnomalyMonitors", "1.1",
                "{\"MonitorArnList\":[\"" + other + "\"]}"), "ce:GetAnomalyMonitors");
    }

    /** The ARN of a custom anomaly monitor an administrator creates with the {@code tags} list, deleted afterwards. */
    private String anomalyMonitor(String tags) {
        String arn = adminJson("ce", "AWSInsightsIndexService.CreateAnomalyMonitor").body("{\"AnomalyMonitor\":"
                + "{\"MonitorName\":\"res-monitor-" + suffix() + "\",\"MonitorType\":\"CUSTOM\","
                + "\"MonitorSpecification\":{\"Dimensions\":{\"Key\":\"LINKED_ACCOUNT\",\"Values\":[\"" + ACCOUNT
                + "\"]}}},\"ResourceTags\":" + tags + "}")
                .post("/").then().statusCode(200).extract().path("MonitorArn");
        cleanup.register(() -> adminJson("ce", "AWSInsightsIndexService.DeleteAnomalyMonitor")
                .body("{\"MonitorArn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    /**
     * A Cost Explorer cost read is checked on the billing view its {@code BillingViewArn} gives, and Floci answers the
     * costs of none but the caller's primary view: another is refused, so a grant on it does not reach the account's
     * costs, nor does one on the primary view reach another's.
     */
    @Test
    void aCostReadIsCheckedOnTheBillingViewItNamesAndAnswersNoOther() {
        String views = "arn:aws:billing::" + ACCOUNT + ":billingview/";
        User user = user("res-view");
        user.policy(allowOn("ce:GetCostAndUsage", views + "primary") + ","
                + allowOn("ce:GetCostAndUsage", views + "res-view"));
        send(user, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", costs(views + "primary")).statusCode(200);
        send(user, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", costs(views + "res-view")).statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        refusedJson(send(user, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", costs(views + "res-other")),
                "ce:GetCostAndUsage");
        User primary = user("res-view-primary");
        primary.policy(allowOn("ce:GetCostAndUsage", views + "primary"));
        refusedJson(send(primary, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", costs(views + "res-view")),
                "ce:GetCostAndUsage");
        // A deny on the primary view is not sidestepped by naming it under another service, which IAM checks as *.
        User denied = user("res-view-denied");
        denied.policy(allowOn("ce:GetCostAndUsage", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"ce:GetCostAndUsage\","
                + "\"Resource\":\"" + views + "primary\"}");
        refusedJson(send(denied, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1", costs(views + "primary")),
                "ce:GetCostAndUsage");
        send(denied, "ce", "AWSInsightsIndexService.GetCostAndUsage", "1.1",
                costs("arn:aws:ce::" + ACCOUNT + ":billingview/primary")).statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * A trail is checked on {@code trail/<name>}: its status on the one its bare {@code Name} names, by its name or its
     * ARN, and its tags on the one the ARN in its {@code ResourceId} gives.
     */
    @Test
    void aTrailIsCheckedOnTheArnItsNameGives() {
        String name = "res-trail-" + suffix();
        String other = name + "-other";
        String arn = trail(name, null);
        trail(other, null);

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

    /**
     * An {@code aws:ResourceTag} condition reads the tags CloudTrail holds on the trail a request names, as AddTags
     * leaves them, so an allow on one team's tag lets the caller read that team's trail status and not another's.
     */
    @Test
    void aCloudTrailResourceTagConditionReadsTheTagsOfTheTrail() {
        String web = "[{\"Key\":\"team\",\"Value\":\"web\"}]";
        String tagged = "res-trail-tagged-" + suffix();
        String other = "res-trail-untagged-" + suffix();
        String taggedArn = trail(tagged, web);
        trail(other, web);
        adminJson("cloudtrail", "CloudTrail_20131101.AddTags")
                .body("{\"ResourceId\":\"" + taggedArn + "\",\"TagsList\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-trail-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"cloudtrail:GetTrailStatus\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "cloudtrail", "CloudTrail_20131101.GetTrailStatus", "1.1", "{\"Name\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(send(user, "cloudtrail", "CloudTrail_20131101.GetTrailStatus", "1.1",
                "{\"Name\":\"" + other + "\"}"), "cloudtrail:GetTrailStatus");
    }

    /** The ARN of the trail {@code name} an administrator creates, with the {@code tags} list if any, deleted after. */
    private String trail(String name, String tags) {
        adminJson("cloudtrail", "CloudTrail_20131101.CreateTrail")
                .body("{\"Name\":\"" + name + "\",\"S3BucketName\":\"res-trail-logs\""
                        + (tags == null ? "" : ",\"TagsList\":" + tags) + "}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("cloudtrail", "CloudTrail_20131101.DeleteTrail")
                .body("{\"Name\":\"" + name + "\"}").post("/"));
        return "arn:aws:cloudtrail:" + REGION + ":" + ACCOUNT + ":trail/" + name;
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
        String arn = accelerator(null);
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags Global Accelerator holds on the accelerator a request names,
     * as TagResource leaves them, so an allow on one team's tag lets the caller describe that team's accelerator and
     * not another's.
     */
    @Test
    void aGlobalAcceleratorResourceTagConditionReadsTheTagsOfTheAccelerator() {
        String web = "[{\"Key\":\"team\",\"Value\":\"web\"}]";
        String tagged = accelerator(web);
        String other = accelerator(web);
        adminJson("globalaccelerator", "GlobalAccelerator_V20180706.TagResource")
                .body("{\"ResourceArn\":\"" + tagged + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-accelerator-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"globalaccelerator:DescribeAccelerator\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "globalaccelerator", "GlobalAccelerator_V20180706.DescribeAccelerator", "1.1",
                "{\"AcceleratorArn\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "globalaccelerator", "GlobalAccelerator_V20180706.DescribeAccelerator", "1.1",
                "{\"AcceleratorArn\":\"" + other + "\"}"), "globalaccelerator:DescribeAccelerator");
    }

    /** The ARN of a disabled accelerator an administrator creates, with the {@code tags} list if any, deleted after. */
    private String accelerator(String tags) {
        String arn = adminJson("globalaccelerator", "GlobalAccelerator_V20180706.CreateAccelerator")
                .body("{\"Name\":\"res-accelerator\",\"IdempotencyToken\":\"" + suffix() + "\",\"Enabled\":false"
                        + (tags == null ? "" : ",\"Tags\":" + tags) + "}")
                .post("/").then().statusCode(200).extract().path("Accelerator.AcceleratorArn");
        cleanup.register(() -> adminJson("globalaccelerator", "GlobalAccelerator_V20180706.DeleteAccelerator")
                .body("{\"AcceleratorArn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    /** A Timestream for InfluxDB parameter group is checked on {@code db-parameter-group/<id>}, the id it was given. */
    @Test
    void aParameterGroupIsCheckedOnTheArnItsIdGives() {
        String id = influxParameterGroup(null);
        String arn = influxParameterGroupArn(id);

        User user = user("res-group");
        user.policy(allowOn("timestream-influxdb:GetDbParameterGroup", arn));
        send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"" + id + "\"}").statusCode(200).body("arn", equalTo(arn));
        refusedJson(send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"res" + suffix() + "\"}"), "timestream-influxdb:GetDbParameterGroup");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Timestream for InfluxDB holds on the resource a request
     * names, as TagResource leaves them, so an allow on one team's tag lets the caller read that team's parameter group
     * and not another's.
     */
    @Test
    void aTimestreamInfluxDbResourceTagConditionReadsTheTagsOfTheParameterGroup() {
        String tagged = influxParameterGroup("{\"team\":\"web\"}");
        String other = influxParameterGroup("{\"team\":\"web\"}");
        adminJson("timestream-influxdb", "AmazonTimestreamInfluxDB.TagResource", "1.0")
                .body("{\"resourceArn\":\"" + influxParameterGroupArn(tagged) + "\",\"tags\":{\"team\":\"data\"}}")
                .post("/").then().statusCode(200);

        User user = user("res-group-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"timestream-influxdb:GetDbParameterGroup\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "timestream-influxdb", "AmazonTimestreamInfluxDB.GetDbParameterGroup", "1.0",
                "{\"identifier\":\"" + other + "\"}"), "timestream-influxdb:GetDbParameterGroup");
    }

    /** The id of a parameter group an administrator creates, with the {@code tags} object if any. */
    private static String influxParameterGroup(String tags) {
        return adminJson("timestream-influxdb", "AmazonTimestreamInfluxDB.CreateDbParameterGroup", "1.0")
                .body("{\"name\":\"res-group-" + suffix() + "\"" + (tags == null ? "" : ",\"tags\":" + tags) + "}")
                .post("/").then().statusCode(200).extract().path("id");
    }

    private static String influxParameterGroupArn(String id) {
        return "arn:aws:timestream-influxdb:" + REGION + ":" + ACCOUNT + ":db-parameter-group/" + id;
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
        String arn = createdBudget(name, null);
        createdBudget(other, null);
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
     * An {@code aws:ResourceTag} condition reads the tags Budgets holds on the budget a request names, as TagResource
     * leaves them, so an allow on one team's tag lets the caller read that team's budget and not another's.
     */
    @Test
    void aBudgetsResourceTagConditionReadsTheTagsOfTheBudget() {
        String web = "[{\"Key\":\"team\",\"Value\":\"web\"}]";
        String tagged = "res-budget-tagged-" + suffix();
        String other = "res-budget-untagged-" + suffix();
        String taggedArn = createdBudget(tagged, web);
        createdBudget(other, web);
        adminJson("budgets", "AWSBudgetServiceGateway.TagResource").body("{\"ResourceARN\":\"" + taggedArn
                + "\",\"ResourceTags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}").post("/").then().statusCode(200);

        User user = user("res-budget-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"budgets:ViewBudget\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        budgets(user, "DescribeBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(budgets(user, "DescribeBudget", "{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + other
                + "\"}"), "budgets:ViewBudget");
    }

    /** The ARN of the budget {@code name} an administrator creates, with the {@code tags} list if any. */
    private String createdBudget(String name, String tags) {
        adminJson("budgets", "AWSBudgetServiceGateway.CreateBudget")
                .body("{\"AccountId\":\"" + ACCOUNT + "\",\"Budget\":" + budget(name, null)
                        + (tags == null ? "" : ",\"ResourceTags\":" + tags) + "}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("budgets", "AWSBudgetServiceGateway.DeleteBudget")
                .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + name + "\"}").post("/"));
        return "arn:aws:budgets::" + ACCOUNT + ":budget/" + name;
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
                + reportDefinition(name, "arn:aws:billing::" + ACCOUNT + ":billingview/primary") + "}").statusCode(200);
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

    /**
     * A scalable target, named by its service namespace, resource id and scalable dimension, is checked on the ARN
     * Application Auto Scaling registered it under, which ends in the id it assigned, so a grant on that ARN allows
     * it, one on another target's does not, and a deny on it holds; registering a new one is checked with its id
     * open, and its tags by its ARN. Before, these were checked on {@code *}.
     */
    @Test
    void aScalableTargetIsCheckedOnTheArnItWasRegisteredUnder() {
        String target = scalableTarget("service/res-" + suffix() + "/web");
        String arn = registerScalableTarget(target);
        assertTrue(arn.startsWith(TARGETS), arn);

        User user = user("res-target");
        user.policy(allowOn("application-autoscaling:RegisterScalableTarget", arn));
        scaling(user, "RegisterScalableTarget", target + ",\"MaxCapacity\":3}").statusCode(200);
        user.policy(allowOn("application-autoscaling:RegisterScalableTarget", TARGETS + "other"));
        refusedJson(scaling(user, "RegisterScalableTarget", target + ",\"MaxCapacity\":3}"),
                "application-autoscaling:RegisterScalableTarget");
        // Its tags on an update are checked on the target too.
        user.policy(allowOn("application-autoscaling:RegisterScalableTarget", arn) + ","
                + allowOn("application-autoscaling:TagResource", TARGETS + "other"));
        refusedJson(scaling(user, "RegisterScalableTarget", target + ",\"Tags\":{\"team\":\"a\"}}"),
                "application-autoscaling:TagResource");
        user.policy(allowOn("application-autoscaling:RegisterScalableTarget", arn) + ","
                + allowOn("application-autoscaling:TagResource", arn));
        scaling(user, "RegisterScalableTarget", target + ",\"Tags\":{\"team\":\"a\"}}").statusCode(200);
        // A new target has no id yet, so it is checked on scalable-target/*, which a grant on another does not cover.
        String other = scalableTarget("service/res-" + suffix() + "/api");
        refusedJson(scaling(user, "RegisterScalableTarget", other + ",\"MinCapacity\":1,\"MaxCapacity\":2}"),
                "application-autoscaling:RegisterScalableTarget");
        user.policy(allowOn("application-autoscaling:RegisterScalableTarget", TARGETS + "*"));
        cleanup.register(() -> adminJson("application-autoscaling", "AnyScaleFrontendService.DeregisterScalableTarget")
                .body(other + "}").post("/"));
        scaling(user, "RegisterScalableTarget", other + ",\"MinCapacity\":1,\"MaxCapacity\":2}").statusCode(200);
        // A deny on the target holds, on a policy put on it and on its tags read by its ARN, and on no other target.
        user.policy(allowOn("application-autoscaling:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":["
                + "\"application-autoscaling:PutScalingPolicy\",\"application-autoscaling:ListTagsForResource\"],"
                + "\"Resource\":\"" + arn + "\"}");
        refusedJson(scaling(user, "PutScalingPolicy", target.replace("{", "{\"PolicyName\":\"p\","
                + "\"PolicyType\":\"StepScaling\",") + "}"), "application-autoscaling:PutScalingPolicy");
        refusedJson(scaling(user, "ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}"),
                "application-autoscaling:ListTagsForResource");
        scaling(user, "DeregisterScalableTarget", other + "}").statusCode(200);
    }

    @Test
    void scheduledActionWritesUseTargetArnAndDescribeRequiresWildcard() {
        User user = user("res-scheduled-actions");
        String resource = "service/res-" + suffix() + "/scheduled";
        try (ApplicationAutoScalingClient adminClient = AwsTestClients.create(ApplicationAutoScalingClient.builder(), ADMIN, Region.US_EAST_1);
                ApplicationAutoScalingClient client = AwsTestClients.create(ApplicationAutoScalingClient.builder(), user.accessKeyId(), Region.US_EAST_1)) {
            String arn = adminClient.registerScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource)
                    .scalableDimension("ecs:service:DesiredCount").minCapacity(0).maxCapacity(2)).scalableTargetARN();
            try {
                PutScheduledActionRequest put = PutScheduledActionRequest.builder().serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension("ecs:service:DesiredCount").scheduledActionName("scheduled")
                        .schedule("at(2099-01-01T00:00:00)")
                        .scalableTargetAction(ScalableTargetAction.builder().minCapacity(0).build()).build();
                user.policy(allowOn("application-autoscaling:PutScheduledAction", TARGETS + "other"));
                assertEquals("AccessDeniedException", assertThrows(ApplicationAutoScalingException.class,
                        () -> client.putScheduledAction(put)).awsErrorDetails().errorCode());
                user.policy(allowOn("application-autoscaling:PutScheduledAction", arn));
                client.putScheduledAction(put);
                user.policy(allowOn("application-autoscaling:DescribeScheduledActions", arn));
                assertThrows(ApplicationAutoScalingException.class, () -> client.describeScheduledActions(b -> b.serviceNamespace("ecs").resourceId(resource)));
                user.policy(allowOn("application-autoscaling:DescribeScheduledActions", "*"));
                assertEquals(1, client.describeScheduledActions(b -> b.serviceNamespace("ecs").resourceId(resource)).scheduledActions().size());
                user.policy(allowOn("application-autoscaling:DeleteScheduledAction", TARGETS + "other"));
                assertThrows(ApplicationAutoScalingException.class, () -> client.deleteScheduledAction(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).scalableDimension("ecs:service:DesiredCount").scheduledActionName("scheduled")));
                user.policy(allowOn("application-autoscaling:DeleteScheduledAction", arn));
                client.deleteScheduledAction(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension("ecs:service:DesiredCount").scheduledActionName("scheduled"));
            } finally {
                adminClient.deregisterScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource).scalableDimension("ecs:service:DesiredCount"));
            }
        }
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Application Auto Scaling holds on the scalable target a
     * request names, so an allow on one team's tag lets the caller list that team's target's tags and not another's.
     */
    @Test
    void anApplicationAutoScalingResourceTagConditionReadsTheTagsOfTheScalableTarget() {
        String tagged = taggedScalableTarget("data");
        String other = taggedScalableTarget("web");

        User user = user("res-target-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"application-autoscaling:ListTagsForResource\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        scaling(user, "ListTagsForResource", "{\"ResourceARN\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(scaling(user, "ListTagsForResource", "{\"ResourceARN\":\"" + other + "\"}"),
                "application-autoscaling:ListTagsForResource");
    }

    /** A scalable target the admin registers and tags with the team; its ARN. */
    private String taggedScalableTarget(String team) {
        String arn = registerScalableTarget(scalableTarget("service/res-" + suffix() + "/web"));
        adminJson("application-autoscaling", "AnyScaleFrontendService.TagResource").body("{\"ResourceARN\":\"" + arn
                + "\",\"Tags\":{\"team\":\"" + team + "\"}}").post("/").then().statusCode(200);
        return arn;
    }

    /** The members naming an ECS service's desired count as a scalable target, an open JSON object. */
    private static String scalableTarget(String resourceId) {
        return "{\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"" + resourceId + "\","
                + "\"ScalableDimension\":\"ecs:service:DesiredCount\"";
    }

    /** Registers the scalable target as the admin and gives its ARN. */
    private String registerScalableTarget(String target) {
        String arn = adminJson("application-autoscaling", "AnyScaleFrontendService.RegisterScalableTarget")
                .body(target + ",\"MinCapacity\":1,\"MaxCapacity\":2}").post("/").then().statusCode(200)
                .extract().path("ScalableTargetARN");
        cleanup.register(() -> adminJson("application-autoscaling", "AnyScaleFrontendService.DeregisterScalableTarget")
                .body(target + "}").post("/"));
        return arn;
    }

    private static ValidatableResponse scaling(User user, String operation, String body) {
        return user.json("application-autoscaling", "AnyScaleFrontendService." + operation, "1.1").body(body).post("/")
                .then();
    }

    /**
     * A Data API request is checked on the cluster it names, or on the serverless workgroup its name or ARN finds,
     * whose ARN ends in the id Redshift Serverless gave it; one naming a workgroup there is none of is checked with
     * that id open. Before, these were checked on {@code *}.
     */
    @Test
    void aDataApiRequestIsCheckedOnTheClusterOrWorkgroupItNames() {
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        String name = "res-wg-" + suffix();
        String arn = createWorkgroup(name);
        assertTrue(arn.startsWith(WORKGROUPS), arn);

        User user = user("res-data");
        user.policy(allowOn("redshift-data:ListDatabases", arn) + ","
                + allowOn("redshift-data:ListDatabases", REDSHIFT + "cluster:res-cluster"));
        // Authorized, a database the workgroup does not have, or a cluster Floci does not have, is refused by the
        // Data API, not by IAM.
        for (String named : List.of(name, arn)) {
            listDatabases(user, "\"WorkgroupName\":\"" + named + "\"").statusCode(400)
                    .body(containsString("does not exist in workgroup"));
        }
        listDatabases(user, "\"ClusterIdentifier\":\"res-cluster\",\"DbUser\":\"admin\"").statusCode(400)
                .body(containsString("was not found"));
        refusedJson(listDatabases(user, "\"ClusterIdentifier\":\"res-other\",\"DbUser\":\"admin\""),
                "redshift-data:ListDatabases");
        refusedJson(listDatabases(user, "\"WorkgroupName\":\"res-wg-missing\""), "redshift-data:ListDatabases");
        // A deny on the workgroup holds, whether a request names it by its name or by its ARN.
        user.policy(allowOn("redshift-data:*", "*") + ",{\"Effect\":\"Deny\","
                + "\"Action\":\"redshift-data:ListDatabases\",\"Resource\":\"" + arn + "\"}");
        for (String named : List.of(name, arn)) {
            refusedJson(listDatabases(user, "\"WorkgroupName\":\"" + named + "\""), "redshift-data:ListDatabases");
        }
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Redshift Serverless holds on a namespace and a workgroup, so
     * an allow on one team's tag lets the caller read that team's and not another's.
     */
    @Test
    void aRedshiftServerlessResourceTagConditionReadsTheTagsOfTheNamespaceAndTheWorkgroup() {
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        String tagged = "res-tag-rss-" + suffix();
        String other = "res-tag-rss-" + suffix();
        taggedWorkgroup(tagged, "data");
        taggedWorkgroup(other, "web");

        User user = user("res-rss-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"redshift-serverless:GetNamespace\","
                + "\"redshift-serverless:GetWorkgroup\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        redshiftServerless(user, "GetNamespace", "{\"namespaceName\":\"" + tagged + "-ns\"}").statusCode(200);
        refusedJson(redshiftServerless(user, "GetNamespace", "{\"namespaceName\":\"" + other + "-ns\"}"),
                "redshift-serverless:GetNamespace");
        redshiftServerless(user, "GetWorkgroup", "{\"workgroupName\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(redshiftServerless(user, "GetWorkgroup", "{\"workgroupName\":\"" + other + "\"}"),
                "redshift-serverless:GetWorkgroup");
    }

    /** Creates a serverless workgroup and its namespace as the admin, both tagged with the team. */
    private void taggedWorkgroup(String name, String team) {
        String tags = ",\"tags\":[{\"key\":\"team\",\"value\":\"" + team + "\"}]";
        adminJson("redshift-serverless", "RedshiftServerless.CreateNamespace")
                .body("{\"namespaceName\":\"" + name + "-ns\",\"adminUsername\":\"admin\","
                        + "\"adminUserPassword\":\"Secret123!\"" + tags + "}").post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("redshift-serverless", "RedshiftServerless.DeleteNamespace")
                .body("{\"namespaceName\":\"" + name + "-ns\"}").post("/"));
        adminJson("redshift-serverless", "RedshiftServerless.CreateWorkgroup")
                .body("{\"workgroupName\":\"" + name + "\",\"namespaceName\":\"" + name + "-ns\"" + tags + "}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("redshift-serverless", "RedshiftServerless.DeleteWorkgroup")
                .body("{\"workgroupName\":\"" + name + "\"}").post("/"));
    }

    private static ValidatableResponse redshiftServerless(User user, String operation, String body) {
        return user.json("redshift-serverless", "RedshiftServerless." + operation, "1.1").body(body).post("/").then();
    }

    /** Creates a serverless workgroup and its namespace as the admin and gives the workgroup's ARN. */
    private String createWorkgroup(String name) {
        adminJson("redshift-serverless", "RedshiftServerless.CreateNamespace")
                .body("{\"namespaceName\":\"" + name + "-ns\",\"adminUsername\":\"admin\","
                        + "\"adminUserPassword\":\"Secret123!\"}").post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("redshift-serverless", "RedshiftServerless.DeleteNamespace")
                .body("{\"namespaceName\":\"" + name + "-ns\"}").post("/"));
        String arn = adminJson("redshift-serverless", "RedshiftServerless.CreateWorkgroup")
                .body("{\"workgroupName\":\"" + name + "\",\"namespaceName\":\"" + name + "-ns\"}").post("/").then()
                .statusCode(200).extract().path("workgroup.workgroupArn");
        cleanup.register(() -> adminJson("redshift-serverless", "RedshiftServerless.DeleteWorkgroup")
                .body("{\"workgroupName\":\"" + name + "\"}").post("/"));
        return arn;
    }

    /** A ListDatabases of a database no workgroup or cluster has, on the target the members name. */
    private static ValidatableResponse listDatabases(User user, String members) {
        return user.json("redshift-data", "RedshiftData.ListDatabases", "1.1")
                .body("{\"Database\":\"res-none\"," + members + "}").post("/").then();
    }

    /** A DataSync agent is checked on its ARN, which ends in the id DataSync gave it, to describe it or to tag it. */
    @Test
    void aDataSyncAgentIsCheckedOnItsArn() {
        String arn = dataSyncAgent(null);
        String agents = "arn:aws:datasync:" + REGION + ":" + ACCOUNT + ":agent/";
        assertTrue(arn.startsWith(agents + "agent-"), arn);
        String other = agents + "agent-0123456789abcdef0";

        User user = user("res-agent");
        user.policy(allowOn("datasync:DescribeAgent", arn) + "," + allowOn("datasync:TagResource", arn));
        dataSync(user, "DescribeAgent", "{\"AgentArn\":\"" + arn + "\"}").statusCode(200);
        refusedJson(dataSync(user, "DescribeAgent", "{\"AgentArn\":\"" + other + "\"}"), "datasync:DescribeAgent");
        String tags = "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}";
        dataSync(user, "TagResource", "{\"ResourceArn\":\"" + arn + tags).statusCode(200);
        refusedJson(dataSync(user, "TagResource", "{\"ResourceArn\":\"" + other + tags), "datasync:TagResource");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags DataSync holds on the agent a request names, as TagResource
     * leaves them, so an allow on one team's tag lets the caller describe that team's agent and not another's.
     */
    @Test
    void aDataSyncResourceTagConditionReadsTheTagsOfTheAgent() {
        String web = "[{\"Key\":\"team\",\"Value\":\"web\"}]";
        String tagged = dataSyncAgent(web);
        String other = dataSyncAgent(web);
        adminJson("datasync", "FmrsService.TagResource")
                .body("{\"ResourceArn\":\"" + tagged + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);

        User user = user("res-agent-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"datasync:DescribeAgent\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        dataSync(user, "DescribeAgent", "{\"AgentArn\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(dataSync(user, "DescribeAgent", "{\"AgentArn\":\"" + other + "\"}"), "datasync:DescribeAgent");
    }

    /** The ARN of an agent an administrator creates, with the {@code tags} list if any, deleted afterwards. */
    private String dataSyncAgent(String tags) {
        String arn = adminJson("datasync", "FmrsService.CreateAgent")
                .body("{\"ActivationKey\":\"RES-ACTIVATION\"" + (tags == null ? "" : ",\"Tags\":" + tags) + "}")
                .post("/").then().statusCode(200).extract().path("AgentArn");
        cleanup.register(() -> adminJson("datasync", "FmrsService.DeleteAgent")
                .body("{\"AgentArn\":\"" + arn + "\"}").post("/"));
        return arn;
    }

    /**
     * An EMR cluster is checked on {@code cluster/<id>}, the ARN EMR gives it, whether a request names it by its
     * {@code ClusterId} or among its {@code JobFlowIds}, and a tag request on the cluster its {@code ResourceId}
     * names, the one Floci tags, not on the one its {@code ClusterId} gives.
     */
    @Test
    void anEmrClusterIsCheckedOnTheArnItsIdGives() {
        String arn = emrCluster("res-emr");
        String id = arn.substring(arn.lastIndexOf('/') + 1);

        User user = user("res-emr");
        user.policy(allowOn("elasticmapreduce:DescribeCluster", arn) + ","
                + allowOn("elasticmapreduce:SetTerminationProtection", arn) + ","
                + allowOn("elasticmapreduce:AddTags", arn));
        emr(user, "DescribeCluster", "{\"ClusterId\":\"" + id + "\"}").statusCode(200);
        refusedJson(emr(user, "DescribeCluster", "{\"ClusterId\":\"j-RESOTHER\"}"), "elasticmapreduce:DescribeCluster");
        String unprotected = "],\"TerminationProtected\":false}";
        emr(user, "SetTerminationProtection", "{\"JobFlowIds\":[\"" + id + "\"" + unprotected).statusCode(200);
        refusedJson(emr(user, "SetTerminationProtection", "{\"JobFlowIds\":[\"" + id + "\",\"j-RESOTHER\""
                + unprotected), "elasticmapreduce:SetTerminationProtection");
        String tags = "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}";
        emr(user, "AddTags", "{\"ResourceId\":\"" + id + tags).statusCode(200);
        refusedJson(emr(user, "AddTags", "{\"ClusterId\":\"" + id + "\",\"ResourceId\":\"j-RESOTHER" + tags),
                "elasticmapreduce:AddTags");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags EMR holds on the cluster a request names, so an allow on one
     * team's tag lets the caller describe that team's cluster and not another's.
     */
    @Test
    void anEmrResourceTagConditionReadsTheTagsOfTheCluster() {
        String tagged = emrClusterId(emrCluster("res-emr-tag"), "data");
        String other = emrClusterId(emrCluster("res-emr-tag"), "web");

        User user = user("res-emr-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"elasticmapreduce:DescribeCluster\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        emr(user, "DescribeCluster", "{\"ClusterId\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(emr(user, "DescribeCluster", "{\"ClusterId\":\"" + other + "\"}"),
                "elasticmapreduce:DescribeCluster");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags EMR holds on the session a request names, those it started
     * with, so an allow on one team's tag lets the caller read that team's session and not another's.
     */
    @Test
    void anEmrResourceTagConditionReadsTheTagsOfTheSession() {
        String clusterId = adminJson("elasticmapreduce", "ElasticMapReduce.RunJobFlow")
                .body("{\"Name\":\"res-emr-sessions\",\"ReleaseLabel\":\"emr-spark-8.0.0\",\"Applications\":[{\"Name\":"
                        + "\"Spark\"}],\"SessionEnabled\":true,\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"Name\":\"primary\",\"InstanceRole\":\"MASTER\",\"InstanceType\":"
                        + "\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .post("/").then().statusCode(200).extract().path("JobFlowId");
        cleanup.register(() -> adminJson("elasticmapreduce", "ElasticMapReduce.TerminateJobFlows")
                .body("{\"JobFlowIds\":[\"" + clusterId + "\"]}").post("/"));
        String tagged = emrSession(clusterId, "data");
        String other = emrSession(clusterId, "web");

        User user = user("res-emr-session-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"elasticmapreduce:GetSession\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        emr(user, "GetSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(emr(user, "GetSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + other + "\"}"),
                "elasticmapreduce:GetSession");
    }

    /** The id of a session an administrator starts on the cluster, tagged with the team. */
    private static String emrSession(String clusterId, String team) {
        return adminJson("elasticmapreduce", "ElasticMapReduce.StartSession")
                .body("{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"res-" + team + "\",\"Tags\":[{\"Key\":\"team\","
                        + "\"Value\":\"" + team + "\"}]}")
                .post("/").then().statusCode(200).extract().path("Id");
    }

    /** The id of the cluster {@code arn} names, tagged with the team by an administrator. */
    private static String emrClusterId(String arn, String team) {
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        adminJson("elasticmapreduce", "ElasticMapReduce.AddTags").body("{\"ResourceId\":\"" + id
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}").post("/").then().statusCode(200);
        return id;
    }

    /**
     * A Rekognition stream processor is checked on the ARN its {@code Name} gives, and a moderation analysis on the
     * custom model whose ARN its {@code ProjectVersion} gives. Floci serves no stream processor and no custom model,
     * so an allowed request is answered by the handler, never by IAM.
     */
    @Test
    void aStreamProcessorIsCheckedOnTheArnItsNameGives() {
        String processor = "arn:aws:rekognition:" + REGION + ":" + ACCOUNT + ":streamprocessor/res-denied";
        String version = "arn:aws:rekognition:" + REGION + ":" + ACCOUNT + ":project/res/version/res-denied/1";
        User user = user("res-rekognition");
        user.policy(allowOn("rekognition:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"rekognition:*\","
                + "\"Resource\":[\"" + processor + "\",\"" + version + "\"]}");
        refusedJson(rekognition(user, "StartStreamProcessor", "{\"Name\":\"res-denied\"}"),
                "rekognition:StartStreamProcessor");
        rekognition(user, "StartStreamProcessor", "{\"Name\":\"res-other\"}")
                .body(not(containsString("not authorized")));
        String image = "{\"Image\":{\"Bytes\":\"AA==\"}";
        refusedJson(rekognition(user, "DetectModerationLabels", image + ",\"ProjectVersion\":\"" + version + "\"}"),
                "rekognition:DetectModerationLabels");
        rekognition(user, "DetectModerationLabels", image + "}").body(not(containsString("not authorized")));
    }

    /**
     * An EMR {@code ModifyInstanceGroups} naming its instance groups by their ids alone, with no {@code ClusterId} or
     * a null one, is checked on the cluster each group is in, the one EMR finds and modifies it in, so a grant on
     * another cluster does not reach it and a deny on its own holds; an {@code InstanceGroups} that is no list is
     * refused, not read as its values.
     */
    @Test
    void anInstanceGroupNamedWithoutItsClusterIsCheckedOnTheClusterItIsIn() {
        String arn = emrCluster("res-emr-groups");
        String group = adminJson("elasticmapreduce", "ElasticMapReduce.ListInstanceGroups")
                .body("{\"ClusterId\":\"" + arn.substring(arn.lastIndexOf('/') + 1) + "\"}").post("/").then()
                .statusCode(200).extract().path("InstanceGroups[0].Id");
        String modify = "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + group + "\",\"InstanceCount\":1}]}";

        User user = user("res-emr-groups");
        user.policy(allowOn("elasticmapreduce:ModifyInstanceGroups", arn));
        emr(user, "ModifyInstanceGroups", modify).statusCode(200);
        emr(user, "ModifyInstanceGroups", "{\"ClusterId\":null," + modify.substring(1)).statusCode(200);
        user.policy(allowOn("elasticmapreduce:ModifyInstanceGroups", arn.substring(0, arn.lastIndexOf('/') + 1)
                + "j-RESOTHER"));
        refusedJson(emr(user, "ModifyInstanceGroups", modify), "elasticmapreduce:ModifyInstanceGroups");
        user.policy(allowOn("elasticmapreduce:ModifyInstanceGroups", "*") + ",{\"Effect\":\"Deny\","
                + "\"Action\":\"elasticmapreduce:ModifyInstanceGroups\",\"Resource\":\"" + arn + "\"}");
        refusedJson(emr(user, "ModifyInstanceGroups", modify), "elasticmapreduce:ModifyInstanceGroups");
        // An InstanceGroups that is not a list names no group to the check, and EMR changes none.
        emr(user, "ModifyInstanceGroups", "{\"InstanceGroups\":{\"first\":{\"InstanceGroupId\":\"" + group
                + "\",\"InstanceCount\":1}}}").statusCode(400)
                .body(containsString("InstanceGroups must contain at least one instance group."));
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags IAM Identity Center holds on the permission set a request
     * names, so an allow on one team's tag lets the caller describe that team's permission set and not another's.
     */
    @Test
    void anIdentityCenterResourceTagConditionReadsTheTagsOfThePermissionSet() {
        String instance = ssoInstance();
        String tagged = taggedPermissionSet(instance, "data");
        String other = taggedPermissionSet(instance, "web");

        User user = user("res-sso-tags");
        user.policy(allowOn("sso:DescribePermissionSet", instance) + ",{\"Effect\":\"Allow\","
                + "\"Action\":\"sso:DescribePermissionSet\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        String describe = "{\"InstanceArn\":\"" + instance + "\",\"PermissionSetArn\":\"";
        ssoAdmin(user, "DescribePermissionSet", describe + tagged + "\"}").statusCode(200);
        refusedJson(ssoAdmin(user, "DescribePermissionSet", describe + other + "\"}"), "sso:DescribePermissionSet");
    }

    /** A permission set of the instance, tagged with the team by an administrator's TagResource. */
    private String taggedPermissionSet(String instance, String team) {
        String permissionSet = permissionSet(instance);
        adminJson("sso", "SWBExternalService.TagResource").body("{\"InstanceArn\":\"" + instance
                + "\",\"ResourceArn\":\"" + permissionSet + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team
                + "\"}]}").post("/").then().statusCode(200);
        return permissionSet;
    }

    /**
     * An IAM Identity Center account assignment is checked on the account its {@code TargetId} names beside the
     * instance and the permission set, so a grant scoped to one account does not reach another, and a deny on an
     * account holds.
     */
    @Test
    void anAccountAssignmentIsCheckedOnTheAccountItsTargetIdNames() {
        String instance = ssoInstance();
        String permissionSet = permissionSet(instance);
        String assigned = assignment(instance, permissionSet, "111111111111");
        String other = assignment(instance, permissionSet, "222222222222");

        User user = user("res-sso");
        user.policy(allowOn("sso:CreateAccountAssignment", instance) + ","
                + allowOn("sso:CreateAccountAssignment", permissionSet) + ","
                + allowOn("sso:CreateAccountAssignment", "arn:aws:sso:::account/111111111111"));
        ssoAdmin(user, "CreateAccountAssignment", assigned).statusCode(200);
        cleanup.register(() -> adminJson("sso", "SWBExternalService.DeleteAccountAssignment").body(assigned)
                .post("/"));
        refusedJson(ssoAdmin(user, "CreateAccountAssignment", other), "sso:CreateAccountAssignment");
        user.policy(allowOn("sso:CreateAccountAssignment", "*") + ",{\"Effect\":\"Deny\","
                + "\"Action\":\"sso:CreateAccountAssignment\",\"Resource\":\"arn:aws:sso:::account/222222222222\"}");
        refusedJson(ssoAdmin(user, "CreateAccountAssignment", other), "sso:CreateAccountAssignment");
    }

    /** An assignment of the permission set to a group, in the account. */
    /**
     * An IAM Identity Center permission set provisioned to all the accounts it is provisioned to, with no
     * {@code TargetId} or a null one, is checked on its instance and itself: nothing botocore or the Service Reference
     * says names those accounts, so a grant on the instance and the permission set lets it through and a deny on one of
     * its accounts does not apply. One provisioned to an account is checked on that account too.
     */
    @Test
    void aPermissionSetProvisionedToAllItsAccountsIsCheckedOnItsInstanceAndItself() {
        String instance = ssoInstance();
        String permissionSet = permissionSet(instance);
        for (String account : List.of("111111111111", "222222222222")) {
            String assigned = assignment(instance, permissionSet, account);
            adminJson("sso", "SWBExternalService.CreateAccountAssignment").body(assigned).post("/").then()
                    .statusCode(200);
            cleanup.register(() -> adminJson("sso", "SWBExternalService.DeleteAccountAssignment").body(assigned)
                    .post("/"));
        }
        String provision = "{\"InstanceArn\":\"" + instance + "\",\"PermissionSetArn\":\"" + permissionSet + "\",";
        String all = provision + "\"TargetType\":\"ALL_PROVISIONED_ACCOUNTS\"}";
        String one = provision + "\"TargetType\":\"AWS_ACCOUNT\",\"TargetId\":\"111111111111\"}";

        User user = user("res-sso-provision");
        user.policy(allowOn("sso:ProvisionPermissionSet", instance) + ","
                + allowOn("sso:ProvisionPermissionSet", permissionSet));
        ssoAdmin(user, "ProvisionPermissionSet", all).statusCode(200);
        ssoAdmin(user, "ProvisionPermissionSet", provision + "\"TargetType\":\"ALL_PROVISIONED_ACCOUNTS\","
                + "\"TargetId\":null}").statusCode(200);
        refusedJson(ssoAdmin(user, "ProvisionPermissionSet", one), "sso:ProvisionPermissionSet");
        user.policy(allowOn("sso:ProvisionPermissionSet", "*") + ",{\"Effect\":\"Deny\","
                + "\"Action\":\"sso:ProvisionPermissionSet\",\"Resource\":\"arn:aws:sso:::account/222222222222\"}");
        ssoAdmin(user, "ProvisionPermissionSet", all).statusCode(200);
        user.policy(allowOn("sso:ProvisionPermissionSet", instance) + ","
                + allowOn("sso:ProvisionPermissionSet", permissionSet) + ","
                + allowOn("sso:ProvisionPermissionSet", "arn:aws:sso:::account/111111111111"));
        ssoAdmin(user, "ProvisionPermissionSet", one).statusCode(200);
    }

    /**
     * An IAM Identity Center permission set a request creates is checked on the permission sets of the instance its
     * {@code InstanceArn} names, {@code permissionSet/<instance id>/*}, so a grant on those lets it through and one on
     * another instance's does not.
     */
    @Test
    void aPermissionSetIsCreatedUnderTheInstanceItsRequestNames() {
        String instance = ssoInstance();
        String permissionSets = "arn:aws:sso:::permissionSet/";
        String create = "{\"InstanceArn\":\"" + instance + "\",\"Name\":\"res-" + suffix() + "\"}";

        User user = user("res-sso-create");
        user.policy(allowOn("sso:CreatePermissionSet", instance) + ","
                + allowOn("sso:CreatePermissionSet", permissionSets + "ssoins-0000000000000000/*"));
        refusedJson(ssoAdmin(user, "CreatePermissionSet", create), "sso:CreatePermissionSet");
        user.policy(allowOn("sso:CreatePermissionSet", instance) + ","
                + allowOn("sso:CreatePermissionSet", permissionSets + instance.substring(instance.lastIndexOf('/') + 1)
                + "/*"));
        String permissionSet = ssoAdmin(user, "CreatePermissionSet", create).statusCode(200).extract()
                .path("PermissionSet.PermissionSetArn");
        cleanup.register(() -> adminJson("sso", "SWBExternalService.DeletePermissionSet")
                .body("{\"InstanceArn\":\"" + instance + "\",\"PermissionSetArn\":\"" + permissionSet + "\"}")
                .post("/"));
    }

    /** A cluster an administrator runs, kept alive with no steps, terminated after the test; its ARN. */
    private String emrCluster(String name) {
        String arn = adminJson("elasticmapreduce", "ElasticMapReduce.RunJobFlow")
                .body("{\"Name\":\"" + name + "\",\"ReleaseLabel\":\"emr-7.5.0\",\"Instances\":{"
                        + "\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":[{\"Name\":\"master\","
                        + "\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .post("/").then().statusCode(200).extract().path("ClusterArn");
        String clusters = "arn:aws:elasticmapreduce:" + REGION + ":" + ACCOUNT + ":cluster/";
        assertTrue(arn.startsWith(clusters + "j-"), arn);
        String id = arn.substring(clusters.length());
        cleanup.register(() -> adminJson("elasticmapreduce", "ElasticMapReduce.TerminateJobFlows")
                .body("{\"JobFlowIds\":[\"" + id + "\"]}").post("/"));
        return arn;
    }

    /** The IAM Identity Center instance ListInstances gives. */
    private static String ssoInstance() {
        return adminJson("sso", "SWBExternalService.ListInstances").body("{}").post("/").then()
                .statusCode(200).extract().path("Instances[0].InstanceArn");
    }

    /** A permission set an administrator creates in the instance, deleted after the test. */
    private String permissionSet(String instance) {
        String permissionSet = adminJson("sso", "SWBExternalService.CreatePermissionSet")
                .body("{\"InstanceArn\":\"" + instance + "\",\"Name\":\"res-" + suffix() + "\"}").post("/").then()
                .statusCode(200).extract().path("PermissionSet.PermissionSetArn");
        cleanup.register(() -> adminJson("sso", "SWBExternalService.DeletePermissionSet")
                .body("{\"InstanceArn\":\"" + instance + "\",\"PermissionSetArn\":\"" + permissionSet + "\"}")
                .post("/"));
        return permissionSet;
    }

    private static String assignment(String instance, String permissionSet, String account) {
        return "{\"InstanceArn\":\"" + instance + "\",\"TargetId\":\"" + account + "\",\"TargetType\":\"AWS_ACCOUNT\","
                + "\"PermissionSetArn\":\"" + permissionSet + "\",\"PrincipalType\":\"GROUP\","
                + "\"PrincipalId\":\"11111111-2222-3333-4444-555555555555\"}";
    }

    private static ValidatableResponse dataSync(User user, String operation, String body) {
        return send(user, "datasync", "FmrsService." + operation, "1.1", body);
    }

    private static ValidatableResponse emr(User user, String operation, String body) {
        return send(user, "elasticmapreduce", "ElasticMapReduce." + operation, "1.1", body);
    }

    private static ValidatableResponse rekognition(User user, String operation, String body) {
        return send(user, "rekognition", "RekognitionService." + operation, "1.1", body);
    }

    private static ValidatableResponse ssoAdmin(User user, String operation, String body) {
        return send(user, "sso", "SWBExternalService." + operation, "1.1", body);
    }

    /** A monthly cost budget of the name, with a billing view ARN when one is given. */
    /**
     * A Redshift parameter group is checked on {@code parametergroup:<name>}, the ARN its handler tags it under, and a
     * tag request on the ARN its {@code ResourceName} gives, a member whose name does not end in Arn.
     */
    @Test
    void aRedshiftParameterGroupIsCheckedOnTheArnItsHandlerMints() {
        String name = "res-pg-" + suffix();
        admin("redshift").formParam("Action", "CreateClusterParameterGroup").formParam("Version", "2012-12-01")
                .formParam("ParameterGroupName", name).formParam("ParameterGroupFamily", "redshift-1.0")
                .formParam("Description", "d").post("/").then().statusCode(200);
        cleanup.register(() -> admin("redshift").formParam("Action", "DeleteClusterParameterGroup")
                .formParam("Version", "2012-12-01").formParam("ParameterGroupName", name).post("/"));
        String arn = REDSHIFT + "parametergroup:" + name;

        User user = user("res-redshift-pg");
        user.policy(allowOn("redshift:CreateTags", arn) + "," + allowOn("redshift:DescribeClusterParameters", arn));
        redshiftTag(user, arn).statusCode(200);
        refused(redshiftTag(user, arn + "-other"), "redshift:CreateTags");
        user.query("redshift", "DescribeClusterParameters").formParam("Version", "2012-12-01")
                .formParam("ParameterGroupName", name).post("/").then().statusCode(200);
        refused(user.query("redshift", "DescribeClusterParameters").formParam("Version", "2012-12-01")
                .formParam("ParameterGroupName", name + "-other").post("/").then(), "redshift:DescribeClusterParameters");
        admin("redshift").formParam("Action", "DescribeTags").formParam("Version", "2012-12-01")
                .formParam("ResourceName", arn).post("/").then().statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TaggedResources.TaggedResource.ResourceName",
                        equalTo(arn));
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Redshift holds on the parameter group a request names, so an
     * allow on one team's tag lets the caller read that team's group and not another's.
     */
    @Test
    void aRedshiftResourceTagConditionReadsTheTagsOfTheParameterGroup() {
        String tagged = redshiftParameterGroup("res-tag-pg-" + suffix(), "data");
        String other = redshiftParameterGroup("res-tag-pg-" + suffix(), "web");

        User user = user("res-redshift-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"redshift:DescribeClusterParameters\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describeClusterParameters(user, tagged).statusCode(200);
        refused(describeClusterParameters(user, other), "redshift:DescribeClusterParameters");
    }

    /** A Redshift parameter group of the name, tagged with the team, deleted afterwards. */
    private String redshiftParameterGroup(String name, String team) {
        admin("redshift").formParam("Action", "CreateClusterParameterGroup").formParam("Version", "2012-12-01")
                .formParam("ParameterGroupName", name).formParam("ParameterGroupFamily", "redshift-1.0")
                .formParam("Description", "d").formParam("Tags.Tag.1.Key", "team").formParam("Tags.Tag.1.Value", team)
                .post("/").then().statusCode(200);
        cleanup.register(() -> admin("redshift").formParam("Action", "DeleteClusterParameterGroup")
                .formParam("Version", "2012-12-01").formParam("ParameterGroupName", name).post("/"));
        return name;
    }

    private static ValidatableResponse describeClusterParameters(User user, String group) {
        return user.query("redshift", "DescribeClusterParameters").formParam("Version", "2012-12-01")
                .formParam("ParameterGroupName", group).post("/").then();
    }

    /** A Redshift cluster is checked on {@code cluster:<identifier>}, before the cluster is looked up. */
    @Test
    void aRedshiftClusterIsCheckedOnItsIdentifier() {
        String cluster = "res-cluster-" + suffix();
        User user = user("res-redshift-cluster");
        user.policy(allowOn("redshift:DescribeClusters", REDSHIFT + "cluster:" + cluster));
        describeCluster(user, cluster).statusCode(404).body(containsString("ClusterNotFound"));
        refused(describeCluster(user, cluster + "-other"), "redshift:DescribeClusters");
    }

    /**
     * A restore is checked on the snapshot of the cluster its {@code SnapshotClusterIdentifier} names, the cluster the
     * snapshot was taken from, and on the new cluster its {@code ClusterIdentifier} names.
     */
    @Test
    void aRedshiftRestoreIsCheckedOnTheSnapshotOfItsSourceCluster() {
        String source = "res-source-" + suffix();
        String restored = "res-restored-" + suffix();
        User user = user("res-redshift-restore");
        user.policy(allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "cluster:" + restored) + ","
                + allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "snapshot:" + source + "/snap"));
        restore(user, restored, source).statusCode(404).body(containsString("ClusterSnapshotNotFound"));

        user.policy(allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "cluster:" + restored) + ","
                + allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "snapshot:" + restored + "/snap"));
        refused(restore(user, restored, source), "redshift:RestoreFromClusterSnapshot");
    }

    @Test
    void aRedshiftRestoreWithOwnerAccountIsCheckedOnTheOwnerSnapshot() {
        String source = "res-source-" + suffix();
        String restored = "res-restored-" + suffix();
        String ownerAccount = "111122223333";
        String ownerSnapshotArn = "arn:aws:redshift:us-east-1:" + ownerAccount + ":snapshot:" + source + "/snap";
        User user = user("res-redshift-restore-owner");

        user.policy(allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "cluster:" + restored) + ","
                + allowOn("redshift:RestoreFromClusterSnapshot", ownerSnapshotArn));
        restoreWithOwner(user, restored, source, ownerAccount)
                .statusCode(404).body(containsString("ClusterSnapshotNotFound"));

        user.policy(allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "cluster:" + restored) + ","
                + allowOn("redshift:RestoreFromClusterSnapshot", "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"redshift:RestoreFromClusterSnapshot\",\"Resource\":\""
                + ownerSnapshotArn + "\"}");
        refused(restoreWithOwner(user, restored, source, ownerAccount), "redshift:RestoreFromClusterSnapshot");

        user.policy(allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "cluster:" + restored) + ","
                + allowOn("redshift:RestoreFromClusterSnapshot", REDSHIFT + "snapshot:" + source + "/snap"));
        refused(restoreWithOwner(user, restored, source, ownerAccount), "redshift:RestoreFromClusterSnapshot");
    }

    private static ValidatableResponse restoreWithOwner(User user, String cluster, String snapshotCluster,
                                                        String ownerAccount) {
        return user.query("redshift", "RestoreFromClusterSnapshot").formParam("Version", "2012-12-01")
                .formParam("ClusterIdentifier", cluster).formParam("SnapshotIdentifier", "snap")
                .formParam("SnapshotClusterIdentifier", snapshotCluster)
                .formParam("OwnerAccount", ownerAccount).post("/").then();
    }

    private static ValidatableResponse redshiftTag(User user, String arn) {
        return user.query("redshift", "CreateTags").formParam("Version", "2012-12-01").formParam("ResourceName", arn)
                .formParam("Tags.Tag.1.Key", "team").formParam("Tags.Tag.1.Value", "data").post("/").then();
    }

    private static ValidatableResponse describeCluster(User user, String cluster) {
        return user.query("redshift", "DescribeClusters").formParam("Version", "2012-12-01")
                .formParam("ClusterIdentifier", cluster).post("/").then();
    }

    private static ValidatableResponse restore(User user, String cluster, String snapshotCluster) {
        return user.query("redshift", "RestoreFromClusterSnapshot").formParam("Version", "2012-12-01")
                .formParam("ClusterIdentifier", cluster).formParam("SnapshotIdentifier", "snap")
                .formParam("SnapshotClusterIdentifier", snapshotCluster).post("/").then();
    }

    /** A month's unblended cost of the billing view. */
    private static String costs(String billingViewArn) {
        return "{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-02-01\"},\"Granularity\":\"MONTHLY\","
                + "\"Metrics\":[\"UnblendedCost\"],\"BillingViewArn\":\"" + billingViewArn + "\"}";
    }

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
    /**
     * An {@code aws:ResourceTag} condition reads a Step Functions state machine's tags for the state machine, its
     * version and its execution, and an activity's own, so an allow on one team's tag lets the caller read that
     * team's and not another's.
     */
    @Test
    void aStepFunctionsResourceTagConditionReadsTheStateMachineTagsAndTheActivityTags() {
        String definition = "{\\\"StartAt\\\":\\\"Done\\\",\\\"States\\\":{\\\"Done\\\":{\\\"Type\\\":\\\"Pass\\\","
                + "\\\"End\\\":true}}}";
        String tagged = stateMachine("res-tag-sm-" + suffix(), definition, "data");
        String other = stateMachine("res-tag-sm-" + suffix(), definition, "web");
        String version = sfn("PublishStateMachineVersion", "{\"stateMachineArn\":\"" + tagged + "\"}")
                .statusCode(200).extract().path("stateMachineVersionArn");
        String execution = sfn("StartExecution", "{\"stateMachineArn\":\"" + tagged + "\"}")
                .statusCode(200).extract().path("executionArn");
        String otherExecution = sfn("StartExecution", "{\"stateMachineArn\":\"" + other + "\"}")
                .statusCode(200).extract().path("executionArn");
        String activity = activity("res-tag-act-" + suffix(), "data");
        String otherActivity = activity("res-tag-act-" + suffix(), "web");

        User user = user("res-sfn-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"states:DescribeStateMachine\",\"states:DescribeExecution\","
                + "\"states:DescribeActivity\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        describe(user, "DescribeStateMachine", "stateMachineArn", tagged).statusCode(200);
        describe(user, "DescribeStateMachine", "stateMachineArn", version).statusCode(200);
        refusedJson(describe(user, "DescribeStateMachine", "stateMachineArn", other), "states:DescribeStateMachine");
        describe(user, "DescribeExecution", "executionArn", execution).statusCode(200);
        refusedJson(describe(user, "DescribeExecution", "executionArn", otherExecution), "states:DescribeExecution");
        describe(user, "DescribeActivity", "activityArn", activity).statusCode(200);
        refusedJson(describe(user, "DescribeActivity", "activityArn", otherActivity), "states:DescribeActivity");
    }

    private String stateMachine(String name, String definition, String team) {
        String arn = sfn("CreateStateMachine", "{\"name\":\"" + name + "\",\"definition\":\"" + definition
                + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/sfn\",\"tags\":[{\"key\":\"team\",\"value\":\""
                + team + "\"}]}").statusCode(200).extract().path("stateMachineArn");
        cleanup.register(() -> sfn("DeleteStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}"));
        return arn;
    }

    private String activity(String name, String team) {
        String arn = sfn("CreateActivity", "{\"name\":\"" + name + "\",\"tags\":[{\"key\":\"team\",\"value\":\""
                + team + "\"}]}").statusCode(200).extract().path("activityArn");
        cleanup.register(() -> sfn("DeleteActivity", "{\"activityArn\":\"" + arn + "\"}"));
        return arn;
    }

    private static ValidatableResponse sfn(String operation, String body) {
        return adminJson("states", "AWSStepFunctions." + operation, "1.0").body(body).post("/").then();
    }

    private static ValidatableResponse describe(User user, String operation, String member, String arn) {
        return send(user, "states", "AWSStepFunctions." + operation, "1.0", "{\"" + member + "\":\"" + arn + "\"}");
    }

    /**
     * A Glue table is checked on the account's catalog, its database and itself, the types AWS gives
     * {@code glue:GetTable}, so a grant on all three allows it, the same grant refuses another table of the database,
     * a grant leaving out the catalog refuses it, and a deny scoped to the table holds under an allow on everything.
     */
    @Test
    void aGlueTableIsCheckedOnTheCatalogItsDatabaseAndItself() {
        String database = "res_db_" + suffix();
        String glueArn = "arn:aws:glue:" + REGION + ":" + ACCOUNT + ":";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"orders\"}}")
                .statusCode(200);
        cleanup.register(() -> glue("DeleteTable", "{\"DatabaseName\":\"" + database + "\",\"Name\":\"orders\"}"));
        String catalog = allowOn("glue:GetTable", glueArn + "catalog");
        String scoped = allowOn("glue:GetTable", glueArn + "database/" + database) + ","
                + allowOn("glue:GetTable", glueArn + "table/" + database + "/orders");

        User user = user("res-glue-table");
        user.policy(catalog + "," + scoped);
        getTable(user, database, "orders").statusCode(200);
        refusedJson(getTable(user, database, "other"), "glue:GetTable");
        user.policy(scoped);
        refusedJson(getTable(user, database, "orders"), "glue:GetTable");
        user.policy(allowOn("glue:GetTable", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"glue:GetTable\",\"Resource\":\""
                + glueArn + "table/" + database + "/orders\"}");
        refusedJson(getTable(user, database, "orders"), "glue:GetTable");
    }

    /** A Glue database is checked on the catalog and {@code database/<name>}, and a job on {@code job/<name>}. */
    @Test
    void aGlueDatabaseAndAJobAreCheckedOnTheArnsGlueGivesThem() {
        String database = "res_db_" + suffix();
        String job = "res-job-" + suffix();
        String glueArn = "arn:aws:glue:" + REGION + ":" + ACCOUNT + ":";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));
        glue("CreateJob", "{\"Name\":\"" + job + "\",\"Role\":\"arn:aws:iam::" + ACCOUNT + ":role/glue\","
                + "\"Command\":{\"Name\":\"glueetl\",\"ScriptLocation\":\"s3://scripts/job.py\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteJob", "{\"JobName\":\"" + job + "\"}"));

        User user = user("res-glue-db");
        user.policy(allowOn("glue:GetDatabase", glueArn + "catalog") + ","
                + allowOn("glue:GetDatabase", glueArn + "database/" + database) + ","
                + allowOn("glue:GetJob", glueArn + "job/" + job));
        send(user, "glue", "AWSGlue.GetDatabase", "1.1", "{\"Name\":\"" + database + "\"}").statusCode(200);
        refusedJson(send(user, "glue", "AWSGlue.GetDatabase", "1.1", "{\"Name\":\"" + database + "_other\"}"),
                "glue:GetDatabase");
        send(user, "glue", "AWSGlue.GetJob", "1.1", "{\"JobName\":\"" + job + "\"}").statusCode(200);
        refusedJson(send(user, "glue", "AWSGlue.GetJob", "1.1", "{\"JobName\":\"" + job + "-other\"}"), "glue:GetJob");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Glue holds on the resource a request names, a job's and a
     * schema registry's, so an allow on one team's tag lets the caller read that team's job and registry and not
     * another's.
     */
    @Test
    void aGlueResourceTagConditionReadsTheTagsOfTheJobAndTheRegistry() {
        String tagged = "res-tag-job-" + suffix();
        String other = "res-tag-job-" + suffix();
        String jobRest = "\",\"Role\":\"arn:aws:iam::" + ACCOUNT + ":role/glue\","
                + "\"Command\":{\"Name\":\"glueetl\",\"ScriptLocation\":\"s3://scripts/job.py\"}";
        glue("CreateJob", "{\"Name\":\"" + tagged + jobRest + ",\"Tags\":{\"team\":\"data\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteJob", "{\"JobName\":\"" + tagged + "\"}"));
        glue("CreateJob", "{\"Name\":\"" + other + jobRest + ",\"Tags\":{\"team\":\"web\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteJob", "{\"JobName\":\"" + other + "\"}"));
        String taggedRegistry = "res-tag-reg-" + suffix();
        String otherRegistry = "res-tag-reg-" + suffix();
        glue("CreateRegistry", "{\"RegistryName\":\"" + taggedRegistry + "\",\"Tags\":{\"team\":\"data\"}}")
                .statusCode(200);
        cleanup.register(() -> glue("DeleteRegistry", "{\"RegistryId\":{\"RegistryName\":\"" + taggedRegistry
                + "\"}}"));
        glue("CreateRegistry", "{\"RegistryName\":\"" + otherRegistry + "\"}").statusCode(200);
        cleanup.register(() -> glue("DeleteRegistry", "{\"RegistryId\":{\"RegistryName\":\"" + otherRegistry
                + "\"}}"));

        User user = user("res-glue-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":[\"glue:GetJob\",\"glue:GetRegistry\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "glue", "AWSGlue.GetJob", "1.1", "{\"JobName\":\"" + tagged + "\"}").statusCode(200);
        refusedJson(send(user, "glue", "AWSGlue.GetJob", "1.1", "{\"JobName\":\"" + other + "\"}"), "glue:GetJob");
        send(user, "glue", "AWSGlue.GetRegistry", "1.1", "{\"RegistryId\":{\"RegistryName\":\"" + taggedRegistry
                + "\"}}").statusCode(200);
        refusedJson(send(user, "glue", "AWSGlue.GetRegistry", "1.1", "{\"RegistryId\":{\"RegistryName\":\""
                + otherRegistry + "\"}}"), "glue:GetRegistry");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags DynamoDB holds on the table a request names, as created and
     * as tagged later, so an allow on one team's tag lets the caller describe that team's tables and not another's.
     */
    @Test
    void aDynamoDbResourceTagConditionReadsTheTagsOfTheTable() {
        String tagged = dynamoDbTable("res-tag-ddb-" + suffix(), "data");
        String other = dynamoDbTable("res-tag-ddb-" + suffix(), "web");

        User user = user("res-ddb-tags");
        user.policy("{\"Effect\":\"Allow\",\"Action\":\"dynamodb:DescribeTable\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        send(user, "dynamodb", "DynamoDB_20120810.DescribeTable", "1.0", "{\"TableName\":\"" + tagged + "\"}")
                .statusCode(200);
        refusedJson(send(user, "dynamodb", "DynamoDB_20120810.DescribeTable", "1.0", "{\"TableName\":\"" + other
                + "\"}"), "dynamodb:DescribeTable");

        adminJson("dynamodb", "DynamoDB_20120810.TagResource", "1.0").body("{\"ResourceArn\":\"arn:aws:dynamodb:"
                + REGION + ":" + ACCOUNT + ":table/" + other + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .post("/").then().statusCode(200);
        send(user, "dynamodb", "DynamoDB_20120810.DescribeTable", "1.0", "{\"TableName\":\"" + other + "\"}")
                .statusCode(200);
    }

    /** A DynamoDB table of the name, keyed on {@code id} and tagged with the team, deleted afterwards. */
    private String dynamoDbTable(String name, String team) {
        adminJson("dynamodb", "DynamoDB_20120810.CreateTable", "1.0").body("{\"TableName\":\"" + name + "\","
                + "\"KeySchema\":[{\"AttributeName\":\"id\",\"KeyType\":\"HASH\"}],"
                + "\"AttributeDefinitions\":[{\"AttributeName\":\"id\",\"AttributeType\":\"S\"}],"
                + "\"BillingMode\":\"PAY_PER_REQUEST\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("dynamodb", "DynamoDB_20120810.DeleteTable", "1.0")
                .body("{\"TableName\":\"" + name + "\"}").post("/"));
        return name;
    }

    /**
     * What Glue's batch deletes name, the tables of {@code TablesToDelete} and the connections of
     * {@code ConnectionNameList}, is checked one by one, so a deny scoped to one of them holds under an allow on
     * everything.
     */
    @Test
    void aGlueBatchDeleteIsCheckedOnEachTableAndConnectionItNames() {
        String database = "res_db_" + suffix();
        String glueArn = "arn:aws:glue:" + REGION + ":" + ACCOUNT + ":";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));

        User user = user("res-glue-batch");
        user.policy(allowOn("glue:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":[\"glue:BatchDeleteTable\","
                + "\"glue:BatchDeleteConnection\"],\"Resource\":[\"" + glueArn + "table/" + database + "/kept\",\""
                + glueArn + "connection/kept-" + database + "\"]}");
        refusedJson(send(user, "glue", "AWSGlue.BatchDeleteTable", "1.1", "{\"DatabaseName\":\"" + database
                + "\",\"TablesToDelete\":[\"gone\",\"kept\"]}"), "glue:BatchDeleteTable");
        send(user, "glue", "AWSGlue.BatchDeleteTable", "1.1", "{\"DatabaseName\":\"" + database
                + "\",\"TablesToDelete\":[\"gone\"]}").statusCode(200);
        refusedJson(send(user, "glue", "AWSGlue.BatchDeleteConnection", "1.1", "{\"ConnectionNameList\":[\"gone-"
                + database + "\",\"kept-" + database + "\"]}"), "glue:BatchDeleteConnection");
        send(user, "glue", "AWSGlue.BatchDeleteConnection", "1.1", "{\"ConnectionNameList\":[\"gone-" + database
                + "\"]}").statusCode(200);
    }

    /**
     * A Glue database's creation is checked on the catalog and the database its {@code DatabaseInput.Name} names, and
     * a connection's tags on the connection its {@code ConnectionInput.Name} names, the resource they go on.
     */
    @Test
    void aGlueCreateIsCheckedOnWhatItsInputNames() {
        String database = "res_db_" + suffix();
        String connection = "res-conn-" + suffix();
        String glueArn = "arn:aws:glue:" + REGION + ":" + ACCOUNT + ":";
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));
        cleanup.register(() -> glue("DeleteConnection", "{\"ConnectionName\":\"" + connection + "\"}"));

        User user = user("res-glue-create");
        user.policy(allowOn("glue:CreateDatabase", glueArn + "catalog") + ","
                + allowOn("glue:CreateDatabase", glueArn + "database/" + database));
        refusedJson(send(user, "glue", "AWSGlue.CreateDatabase", "1.1",
                "{\"DatabaseInput\":{\"Name\":\"" + database + "_other\"}}"), "glue:CreateDatabase");
        send(user, "glue", "AWSGlue.CreateDatabase", "1.1", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}")
                .statusCode(200);

        String create = "{\"ConnectionInput\":{\"Name\":\"" + connection + "\",\"ConnectionType\":\"JDBC\","
                + "\"ConnectionProperties\":{\"JDBC_CONNECTION_URL\":\"jdbc:postgresql://db:5432/app\"}},"
                + "\"Tags\":{\"team\":\"data\"}}";
        user.policy(allowOn("glue:CreateConnection", "*") + "," + allowOn("glue:TagResource", "*")
                + ",{\"Effect\":\"Deny\",\"Action\":\"glue:TagResource\",\"Resource\":\"" + glueArn + "connection/"
                + connection + "\"}");
        refusedJson(send(user, "glue", "AWSGlue.CreateConnection", "1.1", create), "glue:TagResource");
        user.policy(allowOn("glue:CreateConnection", "*") + ","
                + allowOn("glue:TagResource", glueArn + "connection/" + connection));
        send(user, "glue", "AWSGlue.CreateConnection", "1.1", create).statusCode(200);
    }

    /** A Glue workflow run is checked on the workflow its {@code Name} names. */
    @Test
    void aGlueWorkflowRunIsCheckedOnItsWorkflow() {
        String workflow = "res-wf-" + suffix();
        User user = user("res-glue-run");
        user.policy(allowOn("glue:GetWorkflowRun", "arn:aws:glue:" + REGION + ":" + ACCOUNT + ":workflow/" + workflow));
        refusedJson(send(user, "glue", "AWSGlue.GetWorkflowRun", "1.1",
                "{\"Name\":\"" + workflow + "-other\",\"RunId\":\"wr_0\"}"), "glue:GetWorkflowRun");
        send(user, "glue", "AWSGlue.GetWorkflowRun", "1.1", "{\"Name\":\"" + workflow + "\",\"RunId\":\"wr_0\"}")
                .body(not(containsString("AccessDeniedException")));
    }

    /**
     * A CloudWatch alarm is checked on the ARN CloudWatch mints for it, {@code alarm:<name>}: a grant on one alarm lets
     * the caller create it and not another, and a deny on one alarm refuses a request whose list names it, while the
     * same request naming another alarm goes through.
     */
    @Test
    void aCloudWatchAlarmIsCheckedOnTheArnCloudWatchGivesIt() {
        String alarm = "res-alarm-" + suffix();
        String other = alarm + "-other";
        String alarmArn = "arn:aws:cloudwatch:" + REGION + ":" + ACCOUNT + ":alarm:" + alarm;
        cleanup.register(() -> admin("monitoring").formParam("Action", "DeleteAlarms").formParam("Version", "2010-08-01")
                .formParam("AlarmNames.member.1", alarm).formParam("AlarmNames.member.2", other).post("/"));
        User user = user("res-cw-alarm");
        user.policy(allowOn("cloudwatch:PutMetricAlarm", alarmArn));
        putMetricAlarm(user.query("monitoring", "PutMetricAlarm"), alarm).statusCode(200);
        refused(putMetricAlarm(user.query("monitoring", "PutMetricAlarm"), other), "cloudwatch:PutMetricAlarm");

        putMetricAlarm(admin("monitoring").formParam("Action", "PutMetricAlarm"), other).statusCode(200);
        user.policy(allowOn("cloudwatch:DisableAlarmActions", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"cloudwatch:DisableAlarmActions\",\"Resource\":\"" + alarmArn + "\"}");
        refused(user.query("monitoring", "DisableAlarmActions").formParam("Version", "2010-08-01")
                .formParam("AlarmNames.member.1", other).formParam("AlarmNames.member.2", alarm).post("/").then(),
                "cloudwatch:DisableAlarmActions");
        user.query("monitoring", "DisableAlarmActions").formParam("Version", "2010-08-01")
                .formParam("AlarmNames.member.1", other).post("/").then().statusCode(200);
    }

    /**
     * A CloudWatch dashboard is checked on its ARN, which has no Region, as AWS gives it and CloudWatch mints it, so a
     * grant on one dashboard lets the caller write and read it and not another.
     */
    @Test
    void aCloudWatchDashboardIsCheckedOnItsArnWithoutARegion() {
        String dashboard = "res-dash-" + suffix();
        cleanup.register(() -> admin("monitoring").formParam("Action", "DeleteDashboards")
                .formParam("Version", "2010-08-01").formParam("DashboardNames.member.1", dashboard).post("/"));
        User user = user("res-cw-dash");
        user.policy(allowOn("cloudwatch:PutDashboard", "arn:aws:cloudwatch::" + ACCOUNT + ":dashboard/" + dashboard)
                + "," + allowOn("cloudwatch:GetDashboard", "arn:aws:cloudwatch::" + ACCOUNT + ":dashboard/" + dashboard));
        putDashboard(user, dashboard).statusCode(200);
        refused(putDashboard(user, dashboard + "-other"), "cloudwatch:PutDashboard");
        user.query("monitoring", "GetDashboard").formParam("Version", "2010-08-01").formParam("DashboardName", dashboard)
                .post("/").then().statusCode(200).body(containsString(":dashboard/" + dashboard));
    }

    private static ValidatableResponse putMetricAlarm(RequestSpecification request, String name) {
        return request.formParam("Version", "2010-08-01").formParam("AlarmName", name).formParam("Namespace", "Res")
                .formParam("MetricName", "Requests").formParam("Statistic", "Sum").formParam("Period", "60")
                .formParam("EvaluationPeriods", "1").formParam("Threshold", "1")
                .formParam("ComparisonOperator", "GreaterThanThreshold").post("/").then();
    }

    private static ValidatableResponse putDashboard(User user, String name) {
        return user.query("monitoring", "PutDashboard").formParam("Version", "2010-08-01")
                .formParam("DashboardName", name).formParam("DashboardBody", "{\"widgets\":[]}").post("/").then();
    }

    /**
     * An AWS Config rule is checked on the ARN Config stores it under, which ends in the id Config minted for it and
     * not in its name, so a grant on that ARN lets the caller act on the rule a request names by name, and not on
     * another.
     */
    @Test
    void aConfigRuleIsCheckedOnTheArnConfigStoresItUnder() {
        String rule = "res-rule-" + suffix();
        config("PutConfigRule", "{\"ConfigRule\":{\"ConfigRuleName\":\"" + rule + "\",\"Source\":{\"Owner\":\"AWS\","
                + "\"SourceIdentifier\":\"S3_BUCKET_VERSIONING_ENABLED\"}}}").statusCode(200);
        cleanup.register(() -> config("DeleteConfigRule", "{\"ConfigRuleName\":\"" + rule + "\"}"));
        String arn = config("DescribeConfigRules", "{\"ConfigRuleNames\":[\"" + rule + "\"]}").statusCode(200)
                .extract().jsonPath().getString("ConfigRules[0].ConfigRuleArn");
        assertTrue(arn.startsWith("arn:aws:config:" + REGION + ":" + ACCOUNT + ":config-rule/config-rule-"), arn);

        User user = user("res-config-rule");
        user.policy(allowOn("config:GetComplianceDetailsByConfigRule", arn));
        send(user, "config", "StarlingDoveService.GetComplianceDetailsByConfigRule", "1.1",
                "{\"ConfigRuleName\":\"" + rule + "\"}").statusCode(200);
        refusedJson(send(user, "config", "StarlingDoveService.GetComplianceDetailsByConfigRule", "1.1",
                "{\"ConfigRuleName\":\"" + rule + "-other\"}"), "config:GetComplianceDetailsByConfigRule");
    }

    /**
     * A PutConfigRule naming one rule by its id and another by its name updates the rule its id names, as the
     * PutConfigRule reference lets an update name its rule by id, so the rule it is checked on is the one it changes:
     * a grant on the first rule does not let the caller change the second.
     */
    @Test
    void aConfigRulePutUpdatesTheRuleItsIdNames() {
        String first = configRule("res-rule-a-" + suffix());
        String second = configRule("res-rule-b-" + suffix());
        JsonNode firstRule = describeConfigRule(first);
        User user = user("res-config-put");
        user.policy(allowOn("config:PutConfigRule", firstRule.path("ConfigRuleArn").asText()));

        send(user, "config", "StarlingDoveService.PutConfigRule", "1.1", "{\"ConfigRule\":{\"ConfigRuleId\":\""
                + firstRule.path("ConfigRuleId").asText() + "\",\"ConfigRuleName\":\"" + second + "\","
                + "\"Description\":\"changed\",\"Source\":{\"Owner\":\"AWS\","
                + "\"SourceIdentifier\":\"S3_BUCKET_VERSIONING_ENABLED\"}}}").statusCode(200);
        assertEquals("changed", describeConfigRule(first).path("Description").asText());
        assertTrue(!"changed".equals(describeConfigRule(second).path("Description").asText()));
    }

    /**
     * An AWS Config aggregation authorization is checked on the ARN Config gives it, in the caller's account with the
     * account and Region it authorizes in its path, so a grant on that ARN lets the caller put it and not another.
     */
    @Test
    void aConfigAggregationAuthorizationIsCheckedOnTheArnConfigGivesIt() {
        String region = "eu-west-" + (1 + (int) (System.nanoTime() % 3));
        cleanup.register(() -> config("DeleteAggregationAuthorization",
                "{\"AuthorizedAccountId\":\"111122223333\",\"AuthorizedAwsRegion\":\"" + region + "\"}"));
        User user = user("res-config-aggregation");
        user.policy(allowOn("config:PutAggregationAuthorization", "arn:aws:config:" + REGION + ":" + ACCOUNT
                + ":aggregation-authorization/111122223333/" + region));
        String arn = send(user, "config", "StarlingDoveService.PutAggregationAuthorization", "1.1",
                "{\"AuthorizedAccountId\":\"111122223333\",\"AuthorizedAwsRegion\":\"" + region + "\"}")
                .statusCode(200).extract().jsonPath().getString("AggregationAuthorization.AggregationAuthorizationArn");
        assertEquals("arn:aws:config:" + REGION + ":" + ACCOUNT + ":aggregation-authorization/111122223333/" + region,
                arn);
        refusedJson(send(user, "config", "StarlingDoveService.PutAggregationAuthorization", "1.1",
                "{\"AuthorizedAccountId\":\"444455556666\",\"AuthorizedAwsRegion\":\"" + region + "\"}"),
                "config:PutAggregationAuthorization");
    }

    /**
     * An AWS Config recorder put is checked on the recorder its ConfigurationRecorder structure names, the ARN Config
     * stores it under, so a grant on that ARN lets the caller update it, and a grant on another refuses it.
     */
    @Test
    void aConfigRecorderPutIsCheckedOnTheRecorderItsStructureNames() {
        String role = "arn:aws:iam::" + ACCOUNT + ":role/res-config-role";
        String body = "{\"ConfigurationRecorder\":{\"name\":\"default\",\"roleARN\":\"" + role + "\"}}";
        config("PutConfigurationRecorder", body).statusCode(200);
        cleanup.register(() -> config("DeleteConfigurationRecorder", "{\"ConfigurationRecorderName\":\"default\"}"));
        String arn = config("DescribeConfigurationRecorders", "{}").statusCode(200).extract().jsonPath()
                .getString("ConfigurationRecorders[0].arn");
        User user = user("res-config-recorder");
        user.policy(allowOn("config:PutConfigurationRecorder", arn) + "," + allowOn("iam:PassRole", role));
        send(user, "config", "StarlingDoveService.PutConfigurationRecorder", "1.1", body).statusCode(200);
        user.policy(allowOn("config:PutConfigurationRecorder", arn + "0") + "," + allowOn("iam:PassRole", role));
        refusedJson(send(user, "config", "StarlingDoveService.PutConfigurationRecorder", "1.1", body),
                "config:PutConfigurationRecorder");
    }

    /** A config rule of the name, created as the admin and deleted after the test; its name. */
    private String configRule(String name) {
        config("PutConfigRule", "{\"ConfigRule\":{\"ConfigRuleName\":\"" + name + "\",\"Source\":{\"Owner\":\"AWS\","
                + "\"SourceIdentifier\":\"S3_BUCKET_VERSIONING_ENABLED\"}}}").statusCode(200);
        cleanup.register(() -> config("DeleteConfigRule", "{\"ConfigRuleName\":\"" + name + "\"}"));
        return name;
    }

    private static JsonNode describeConfigRule(String name) {
        try {
            return new ObjectMapper().readTree(config("DescribeConfigRules", "{\"ConfigRuleNames\":[\"" + name
                    + "\"]}").statusCode(200).extract().asString()).path("ConfigRules").path(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An AWS Config conformance pack is checked on the ARN Config stores it under, its name followed by the id Config
     * minted for it, so a grant on that ARN lets the caller update the pack, and a pack Config holds none of is checked
     * on its name with the id open.
     */
    @Test
    void aConfigConformancePackIsCheckedOnTheArnConfigStoresItUnder() {
        String pack = "res-pack-" + suffix();
        String body = "\",\"TemplateS3Uri\":\"s3://templates/pack.yaml\"}";
        config("PutConformancePack", "{\"ConformancePackName\":\"" + pack + body).statusCode(200);
        cleanup.register(() -> config("DeleteConformancePack", "{\"ConformancePackName\":\"" + pack + "\"}"));
        String arn = config("DescribeConformancePacks", "{\"ConformancePackNames\":[\"" + pack + "\"]}")
                .statusCode(200).extract().jsonPath().getString("ConformancePackDetails[0].ConformancePackArn");

        User user = user("res-config-pack");
        user.policy(allowOn("config:PutConformancePack", arn));
        send(user, "config", "StarlingDoveService.PutConformancePack", "1.1",
                "{\"ConformancePackName\":\"" + pack + body).statusCode(200);
        refusedJson(send(user, "config", "StarlingDoveService.PutConformancePack", "1.1",
                "{\"ConformancePackName\":\"" + pack + "-other" + body), "config:PutConformancePack");
    }

    private static ValidatableResponse config(String operation, String body) {
        return adminJson("config", "StarlingDoveService." + operation).body(body).post("/").then();
    }

    /**
     * A DMS subnet group is checked on the ARN format the Service Reference gives it,
     * {@code arn:aws:dms:<region>:<account>:subgrp:*}, so a grant on that pattern lets the request reach DMS, which
     * answers that the group does not exist, and a grant on another type's refuses it.
     */
    @Test
    void aDmsSubnetGroupIsCheckedOnTheFormatTheReferenceGivesIt() {
        String body = "{\"ReplicationSubnetGroupIdentifier\":\"res-dms-missing-" + suffix() + "\"}";
        User user = user("res-dms");
        user.policy(allowOn("dms:DeleteReplicationSubnetGroup", "arn:aws:dms:" + REGION + ":" + ACCOUNT + ":subgrp:*"));
        send(user, "dms", "AmazonDMSv20160101.DeleteReplicationSubnetGroup", "1.1", body).statusCode(400)
                .body(containsString("ResourceNotFoundFault"));
        user.policy(allowOn("dms:DeleteReplicationSubnetGroup", "arn:aws:dms:" + REGION + ":" + ACCOUNT + ":rep:*"));
        refusedJson(send(user, "dms", "AmazonDMSv20160101.DeleteReplicationSubnetGroup", "1.1", body),
                "dms:DeleteReplicationSubnetGroup");
    }

    /**
     * An ElastiCache parameter group is checked on the ARN ElastiCache mints for it, {@code parametergroup:<name>}, so a
     * grant on one lets the caller create it and not another.
     */
    @Test
    void anElastiCacheParameterGroupIsCheckedOnTheArnItsNameGives() {
        String group = "res-pg-" + suffix();
        cleanup.register(() -> admin("elasticache").formParam("Action", "DeleteCacheParameterGroup")
                .formParam("Version", "2015-02-02").formParam("CacheParameterGroupName", group).post("/"));
        User user = user("res-elasticache");
        user.policy(allowOn("elasticache:CreateCacheParameterGroup",
                "arn:aws:elasticache:" + REGION + ":" + ACCOUNT + ":parametergroup:" + group));
        createCacheParameterGroup(user, group).statusCode(200);
        refused(createCacheParameterGroup(user, group + "-other"), "elasticache:CreateCacheParameterGroup");
    }

    /**
     * An ElastiCache user group's change is checked on each user it adds or removes too, so a deny scoped to one user
     * refuses a change that adds that user, and not one that adds another.
     */
    @Test
    void anElastiCacheUserGroupChangeIsCheckedOnTheUsersItAdds() {
        User user = user("res-elasticache-users");
        user.policy(allowOn("elasticache:ModifyUserGroup", "*") + ",{\"Effect\":\"Deny\",\"Action\":"
                + "\"elasticache:ModifyUserGroup\",\"Resource\":\"arn:aws:elasticache:" + REGION + ":" + ACCOUNT
                + ":user:res-denied\"}");
        refused(user.query("elasticache", "ModifyUserGroup").formParam("Version", "2015-02-02")
                .formParam("UserGroupId", "res-group").formParam("UserIdsToAdd.member.1", "res-denied").post("/")
                .then(), "elasticache:ModifyUserGroup");
        user.query("elasticache", "ModifyUserGroup").formParam("Version", "2015-02-02")
                .formParam("UserGroupId", "res-group").formParam("UserIdsToAdd.member.1", "res-other").post("/")
                .then().body(not(containsString("not authorized to perform")));
    }

    private static ValidatableResponse createCacheParameterGroup(User user, String name) {
        return user.query("elasticache", "CreateCacheParameterGroup").formParam("Version", "2015-02-02")
                .formParam("CacheParameterGroupName", name).formParam("CacheParameterGroupFamily", "redis7")
                .formParam("Description", "res").post("/").then();
    }

    /**
     * An Identity Store user is checked on the identity store and on the user its id names, so a grant on both lets the
     * caller read that user and not another.
     */
    @Test
    void anIdentityStoreUserIsCheckedOnItsStoreAndItself() {
        String store = "d-1234567890";
        String allowed = identityStoreUser(store);
        String other = identityStoreUser(store);
        User user = user("res-identitystore");
        user.policy(allowOn("identitystore:DescribeUser", "arn:aws:identitystore::" + ACCOUNT + ":identitystore/" + store)
                + "," + allowOn("identitystore:DescribeUser", "arn:aws:identitystore:::user/" + allowed));
        send(user, "identitystore", "AWSIdentityStore.DescribeUser", "1.1",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + allowed + "\"}").statusCode(200);
        refusedJson(send(user, "identitystore", "AWSIdentityStore.DescribeUser", "1.1",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + other + "\"}"),
                "identitystore:DescribeUser");
    }

    /**
     * An Identity Store membership is checked on its group and its user too, which the Service Authorization Reference
     * requires its actions on and its record holds, so a deny on one group refuses reading a membership of it and not
     * one of another group.
     */
    @Test
    void anIdentityStoreMembershipIsCheckedOnItsGroupAndItsUser() {
        String store = "d-1234567890";
        String member = identityStoreUser(store);
        String denied = identityStoreGroup(store, "res-denied-" + suffix());
        String inDenied = identityStoreMembership(store, denied, member);
        String inOther = identityStoreMembership(store, identityStoreGroup(store, "res-other-" + suffix()), member);
        User user = user("res-identitystore-membership");
        user.policy(allowOn("identitystore:DescribeGroupMembership", "*") + ","
                + denyOn("identitystore:DescribeGroupMembership", "arn:aws:identitystore:::group/" + denied));
        refusedJson(send(user, "identitystore", "AWSIdentityStore.DescribeGroupMembership", "1.1",
                "{\"IdentityStoreId\":\"" + store + "\",\"MembershipId\":\"" + inDenied + "\"}"),
                "identitystore:DescribeGroupMembership");
        send(user, "identitystore", "AWSIdentityStore.DescribeGroupMembership", "1.1",
                "{\"IdentityStoreId\":\"" + store + "\",\"MembershipId\":\"" + inOther + "\"}").statusCode(200);
    }

    /**
     * An Identity Store lookup is checked on what it finds, which the Service Authorization Reference requires it on:
     * {@code GetUserId} on the user its alternate identifier names, {@code GetGroupMembershipId} on the membership of
     * its group and member, so a deny on one refuses that lookup and not another's.
     */
    @Test
    void anIdentityStoreLookupIsCheckedOnWhatItFinds() {
        String store = "d-1234567890";
        String deniedName = "res-denied-" + suffix();
        String denied = identityStoreUser(store, deniedName);
        String otherName = "res-other-" + suffix();
        String other = identityStoreUser(store, otherName);
        String group = identityStoreGroup(store, "res-group-" + suffix());
        String deniedMembership = identityStoreMembership(store, group, denied);
        identityStoreMembership(store, group, other);
        User user = user("res-identitystore-lookup");
        String membershipArn = "arn:aws:identitystore:::membership/" + deniedMembership;
        user.policy(allowOn("identitystore:GetUserId", "*") + "," + allowOn("identitystore:GetGroupMembershipId", "*")
                + "," + denyOn("identitystore:GetUserId", "arn:aws:identitystore:::user/" + denied) + ","
                + denyOn("identitystore:GetGroupMembershipId", membershipArn));
        refusedJson(getUserId(user, store, deniedName), "identitystore:GetUserId");
        getUserId(user, store, otherName).statusCode(200).body("UserId", equalTo(other));
        refusedJson(getGroupMembershipId(user, store, group, denied), "identitystore:GetGroupMembershipId");
        getGroupMembershipId(user, store, group, other).statusCode(200);
    }

    private static ValidatableResponse getUserId(User user, String store, String userName) {
        return send(user, "identitystore", "AWSIdentityStore.GetUserId", "1.1", "{\"IdentityStoreId\":\"" + store
                + "\",\"AlternateIdentifier\":{\"UniqueAttribute\":{\"AttributePath\":\"userName\","
                + "\"AttributeValue\":\"" + userName + "\"}}}");
    }

    private static ValidatableResponse getGroupMembershipId(User user, String store, String group, String member) {
        return send(user, "identitystore", "AWSIdentityStore.GetGroupMembershipId", "1.1", "{\"IdentityStoreId\":\""
                + store + "\",\"GroupId\":\"" + group + "\",\"MemberId\":{\"UserId\":\"" + member + "\"}}");
    }

    /** A group of the identity store, created as the admin and deleted after the test; its id. */
    private String identityStoreGroup(String store, String displayName) {
        String groupId = adminJson("identitystore", "AWSIdentityStore.CreateGroup")
                .body("{\"IdentityStoreId\":\"" + store + "\",\"DisplayName\":\"" + displayName + "\"}")
                .post("/").then().statusCode(200).extract().path("GroupId");
        cleanup.register(() -> adminJson("identitystore", "AWSIdentityStore.DeleteGroup")
                .body("{\"IdentityStoreId\":\"" + store + "\",\"GroupId\":\"" + groupId + "\"}").post("/"));
        return groupId;
    }

    /** The membership of a user in a group of the identity store, created as the admin; its id. */
    private String identityStoreMembership(String store, String group, String member) {
        return adminJson("identitystore", "AWSIdentityStore.CreateGroupMembership")
                .body("{\"IdentityStoreId\":\"" + store + "\",\"GroupId\":\"" + group + "\",\"MemberId\":{"
                        + "\"UserId\":\"" + member + "\"}}")
                .post("/").then().statusCode(200).extract().path("MembershipId");
    }

    /** A user of the identity store, created as the admin and deleted after the test; its id. */
    private String identityStoreUser(String store) {
        return identityStoreUser(store, "res-" + suffix());
    }

    /** A user of the identity store of that name, created as the admin and deleted after the test; its id. */
    private String identityStoreUser(String store, String userName) {
        String userId = adminJson("identitystore", "AWSIdentityStore.CreateUser")
                .body("{\"IdentityStoreId\":\"" + store + "\",\"UserName\":\"" + userName + "\","
                        + "\"DisplayName\":\"Res\"}")
                .post("/").then().statusCode(200).extract().path("UserId");
        cleanup.register(() -> adminJson("identitystore", "AWSIdentityStore.DeleteUser")
                .body("{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + userId + "\"}").post("/"));
        return userId;
    }

    /**
     * A MemoryDB ACL is checked on the ARN its name gives, {@code acl/<name>}, the one MemoryDB mints, so a grant on one
     * lets the caller describe it and not another.
     */
    @Test
    void aMemoryDbAclIsCheckedOnTheArnItsNameGives() {
        String acl = "res-acl-" + suffix();
        adminJson("memorydb", "AmazonMemoryDB.CreateACL")
                .body("{\"ACLName\":\"" + acl + "\",\"UserNames\":[\"default\"]}").post("/").then().statusCode(200);
        cleanup.register(() -> adminJson("memorydb", "AmazonMemoryDB.DeleteACL")
                .body("{\"ACLName\":\"" + acl + "\"}").post("/"));
        User user = user("res-memorydb");
        user.policy(allowOn("memorydb:DescribeAcls", "arn:aws:memorydb:" + REGION + ":" + ACCOUNT + ":acl/" + acl));
        send(user, "memorydb", "AmazonMemoryDB.DescribeACLs", "1.1", "{\"ACLName\":\"" + acl + "\"}")
                .statusCode(200);
        refusedJson(send(user, "memorydb", "AmazonMemoryDB.DescribeACLs", "1.1",
                "{\"ACLName\":\"" + acl + "-other\"}"), "memorydb:DescribeAcls");
    }

    /**
     * A Route 53 Resolver firewall domain list is checked on the ARN its id gives, {@code firewall-domain-list/<id>},
     * the one Route 53 Resolver mints, so a grant on one lets the caller read it and not another.
     */
    @Test
    void aRoute53ResolverDomainListIsCheckedOnTheArnItsIdGives() {
        String allowed = domainList("res-fdl-" + suffix());
        String other = domainList("res-fdl-other-" + suffix());
        User user = user("res-resolver");
        user.policy(allowOn("route53resolver:GetFirewallDomainList",
                "arn:aws:route53resolver:" + REGION + ":" + ACCOUNT + ":firewall-domain-list/" + allowed));
        send(user, "route53resolver", "Route53Resolver.GetFirewallDomainList", "1.1",
                "{\"FirewallDomainListId\":\"" + allowed + "\"}").statusCode(200);
        refusedJson(send(user, "route53resolver", "Route53Resolver.GetFirewallDomainList", "1.1",
                "{\"FirewallDomainListId\":\"" + other + "\"}"), "route53resolver:GetFirewallDomainList");
    }

    /**
     * A managed firewall domain list, which AWS owns, is checked on the ARN Route 53 Resolver gives it, so a grant on
     * the ARN ListFirewallDomainLists returns lets the caller read that list.
     */
    @Test
    void aManagedRoute53ResolverDomainListIsCheckedOnTheArnTheServiceGivesIt() {
        String id = adminJson("route53resolver", "Route53Resolver.ListFirewallDomainLists").body("{}").post("/")
                .then().statusCode(200).extract().path("FirewallDomainLists.find { it.ManagedOwnerName != null }.Id");
        String arn = adminJson("route53resolver", "Route53Resolver.ListFirewallDomainLists").body("{}").post("/")
                .then().statusCode(200).extract()
                .path("FirewallDomainLists.find { it.Id == '" + id + "' }.Arn");
        User user = user("res-resolver-managed");
        user.policy(allowOn("route53resolver:GetFirewallDomainList", arn));
        send(user, "route53resolver", "Route53Resolver.GetFirewallDomainList", "1.1",
                "{\"FirewallDomainListId\":\"" + id + "\"}").statusCode(200);
    }

    /** A firewall domain list of the name, created as the admin and deleted after the test; its id. */
    private String domainList(String name) {
        String id = adminJson("route53resolver", "Route53Resolver.CreateFirewallDomainList")
                .body("{\"CreatorRequestId\":\"" + name + "\",\"Name\":\"" + name + "\"}")
                .post("/").then().statusCode(200).extract().path("FirewallDomainList.Id");
        cleanup.register(() -> adminJson("route53resolver", "Route53Resolver.DeleteFirewallDomainList")
                .body("{\"FirewallDomainListId\":\"" + id + "\"}").post("/"));
        return id;
    }

    /**
     * A SageMaker model is checked on the ARN its name gives, {@code model/<name>}, so a grant on one lets the request
     * reach SageMaker, which answers that the model does not exist, and the same grant refuses another.
     */
    @Test
    void aSageMakerModelIsCheckedOnTheArnItsNameGives() {
        String model = "res-model-" + suffix();
        User user = user("res-sagemaker");
        user.policy(allowOn("sagemaker:DescribeModel", "arn:aws:sagemaker:" + REGION + ":" + ACCOUNT + ":model/" + model));
        send(user, "sagemaker", "SageMaker.DescribeModel", "1.1", "{\"ModelName\":\"" + model + "\"}")
                .statusCode(400).body(containsString("Could not find model"));
        refusedJson(send(user, "sagemaker", "SageMaker.DescribeModel", "1.1",
                "{\"ModelName\":\"" + model + "-other\"}"), "sagemaker:DescribeModel");
    }

    /**
     * A SageMaker create that tags what it makes also needs {@code sagemaker:AddTags}, which the Service Reference
     * lists among CreateModel's authorized actions: a caller allowed the create alone is refused it with tags, and
     * one allowed AddTags too is not.
     */
    @Test
    void aTaggedSageMakerCreateAlsoNeedsAddTags() {
        String model = "res-tagged-model-" + suffix();
        String request = "{\"ModelName\":\"" + model + "\",\"ExecutionRoleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/res-sagemaker-role\",\"PrimaryContainer\":{\"Image\":\"example/model:1\"},"
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}";
        cleanup.register(() -> adminJson("sagemaker", "SageMaker.DeleteModel")
                .body("{\"ModelName\":\"" + model + "\"}").post("/"));
        User creator = user("res-sagemaker-create");
        creator.policy(allowOn("sagemaker:CreateModel", "*") + "," + allowOn("iam:PassRole", "*"));
        refusedJson(send(creator, "sagemaker", "SageMaker.CreateModel", "1.1", request), "sagemaker:AddTags");
        User tagger = user("res-sagemaker-tag");
        tagger.policy(allowOn("sagemaker:CreateModel", "*") + "," + allowOn("iam:PassRole", "*") + ","
                + allowOn("sagemaker:AddTags", "*"));
        send(tagger, "sagemaker", "SageMaker.CreateModel", "1.1", request)
                .body(not(containsString("not authorized to perform")));
    }

    /**
     * A tagged SageMaker CreateEndpoint needs {@code sagemaker:AddTags} on the endpoint it makes alone, not on the
     * endpoint configuration it runs: a caller allowed to tag the new endpoint creates it, one allowed to tag the
     * configuration alone is refused.
     */
    @Test
    void aTaggedSageMakerEndpointNeedsAddTagsOnTheEndpointAlone() {
        String endpoint = "res-tagged-endpoint-" + suffix();
        String config = "res-endpoint-config-" + suffix();
        String prefix = "arn:aws:sagemaker:" + REGION + ":" + ACCOUNT + ":";
        String request = "{\"EndpointName\":\"" + endpoint + "\",\"EndpointConfigName\":\"" + config + "\","
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}";
        String create = allowOn("sagemaker:CreateEndpoint", prefix + "endpoint/" + endpoint) + ","
                + allowOn("sagemaker:CreateEndpoint", prefix + "endpoint-config/" + config);
        cleanup.register(() -> adminJson("sagemaker", "SageMaker.DeleteEndpoint")
                .body("{\"EndpointName\":\"" + endpoint + "\"}").post("/"));
        User endpointTagger = user("res-sagemaker-endpoint-tag");
        endpointTagger.policy(create + "," + allowOn("sagemaker:AddTags", prefix + "endpoint/" + endpoint));
        send(endpointTagger, "sagemaker", "SageMaker.CreateEndpoint", "1.1", request)
                .body(not(containsString("not authorized to perform")));
        User configTagger = user("res-sagemaker-config-tag");
        configTagger.policy(create + "," + allowOn("sagemaker:AddTags", prefix + "endpoint-config/" + config));
        refusedJson(send(configTagger, "sagemaker", "SageMaker.CreateEndpoint", "1.1", request), "sagemaker:AddTags");
    }

    /**
     * A Transcribe transcription job is checked on the ARN its name gives, {@code transcription-job/<name>}, so a grant
     * on one lets the request reach Transcribe, which answers that the job does not exist, and refuses another.
     */
    @Test
    void aTranscriptionJobIsCheckedOnTheArnItsNameGives() {
        String job = "res-job-" + suffix();
        User user = user("res-transcribe");
        user.policy(allowOn("transcribe:GetTranscriptionJob",
                "arn:aws:transcribe:" + REGION + ":" + ACCOUNT + ":transcription-job/" + job));
        send(user, "transcribe", "Transcribe.GetTranscriptionJob", "1.1", "{\"TranscriptionJobName\":\"" + job + "\"}")
                .statusCode(400).body(containsString("NotFoundException"));
        refusedJson(send(user, "transcribe", "Transcribe.GetTranscriptionJob", "1.1",
                "{\"TranscriptionJobName\":\"" + job + "-other\"}"), "transcribe:GetTranscriptionJob");
    }

    /**
     * A Transfer Family server is checked on the ARN its id gives, {@code server/<id>}, so a grant on one lets the
     * request reach the service, which answers that the server does not exist, and refuses another.
     */
    @Test
    void aTransferServerIsCheckedOnTheArnItsIdGives() {
        String server = "s-0123456789abcdef0";
        User user = user("res-transfer");
        user.policy(allowOn("transfer:DescribeServer", "arn:aws:transfer:" + REGION + ":" + ACCOUNT + ":server/" + server));
        send(user, "transfer", "TransferService.DescribeServer", "1.1", "{\"ServerId\":\"" + server + "\"}")
                .body(containsString("ResourceNotFoundException"));
        refusedJson(send(user, "transfer", "TransferService.DescribeServer", "1.1",
                "{\"ServerId\":\"s-0123456789abcdef1\"}"), "transfer:DescribeServer");
    }

    private static ValidatableResponse glue(String operation, String body) {
        return adminJson("glue", "AWSGlue." + operation).body(body).post("/").then();
    }

    private static ValidatableResponse getTable(User user, String database, String table) {
        return send(user, "glue", "AWSGlue.GetTable", "1.1",
                "{\"DatabaseName\":\"" + database + "\",\"Name\":\"" + table + "\"}");
    }

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
        return pipeline(name, null);
    }

    /** A CreatePipeline or UpdatePipeline body for the pipeline {@code name}, with the {@code tags} list if any. */
    private static String pipeline(String name, String tags) {
        return "{\"pipeline\":{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/codepipeline-role\","
                + "\"artifactStore\":{\"type\":\"S3\",\"location\":\"codepipeline-artifacts\"},\"stages\":["
                + "{\"name\":\"Source\",\"actions\":[{\"name\":\"SourceAction\",\"actionTypeId\":{\"category\":\"Source\","
                + "\"owner\":\"AWS\",\"provider\":\"S3\",\"version\":\"1\"},\"runOrder\":1}]},"
                + "{\"name\":\"Build\",\"actions\":[{\"name\":\"BuildAction\",\"actionTypeId\":{\"category\":\"Build\","
                + "\"owner\":\"AWS\",\"provider\":\"CodeBuild\",\"version\":\"1\"},\"runOrder\":1}]}]}"
                + (tags == null ? "" : ",\"tags\":" + tags) + "}";
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

    private String createEventBusV2(String name) throws IOException {
        String arn = CBOR_MAPPER.readTree(admin("events").header("smithy-protocol", "rpc-v2-cbor")
                .contentType("application/cbor").accept("application/cbor")
                .body(CBOR_MAPPER.writeValueAsBytes(Map.of("Name", name)))
                .post("/service/AWSEventsV2/operation/CreateEventBus").then().statusCode(200).extract().asByteArray())
                .path("EventBusArn").asText();
        cleanup.register(() -> deleteEventBusV2(arn));
        return arn;
    }

    private static void deleteEventBusV2(String arn) {
        try {
            admin("events").header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor")
                    .accept("application/cbor").body(CBOR_MAPPER.writeValueAsBytes(Map.of("EventBusArn", arn)))
                    .post("/service/AWSEventsV2/operation/DeleteEventBus");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteSubscriberV2(String arn) {
        try {
            admin("events").header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor")
                    .accept("application/cbor").body(CBOR_MAPPER.writeValueAsBytes(Map.of("SubscriberArn", arn)))
                    .post("/service/AWSEventsV2/operation/DeleteSubscriber");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One event published to the EventBridge V2 bus. */
    private static Map<String, Object> putEventsV2(String bus) {
        return Map.of("EventBusArn", bus, "Entries", List.of(Map.of("Source", "res.test", "DetailType", "t",
                "Detail", "{}")));
    }

    private static ValidatableResponse eventsV2(User user, String operation, Map<String, ?> body) throws IOException {
        return user.cbor("events").body(CBOR_MAPPER.writeValueAsBytes(body))
                .post("/service/AWSEventsV2/operation/" + operation).then();
    }

    /** An RPC v2 CBOR service refuses with {@code AccessDeniedException}, a 403, naming the missing permission. */
    private static void refusedCbor(ValidatableResponse response, String action) throws IOException {
        JsonNode error = CBOR_MAPPER.readTree(response.statusCode(403).extract().asByteArray());
        assertEquals("AccessDeniedException", error.path("__type").asText());
        assertTrue(error.path("message").asText().contains(action), error.toString());
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

    /** A key of the account, made as the administrator. */
    private String createPaymentKey() {
        return admin("payment-cryptography").header("X-Amz-Target", "PaymentCryptographyControlPlane.CreateKey")
                .contentType("application/x-amz-json-1.0").body("{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                        + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                        + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}},\"Exportable\":true}")
                .post("/").then().statusCode(200).extract().path("Key.KeyArn");
    }

    /** An alias naming a key, made as the administrator. */
    private static ValidatableResponse createPaymentAlias(String alias, String key) {
        return admin("payment-cryptography").header("X-Amz-Target", "PaymentCryptographyControlPlane.CreateAlias")
                .contentType("application/x-amz-json-1.0")
                .body("{\"AliasName\":\"" + alias + "\",\"KeyArn\":\"" + key + "\"}").post("/").then();
    }

    /** A tagging request naming its key the way the request gives it. */
    private static ValidatableResponse tagPaymentKey(User user, String resource) {
        return user.json("payment-cryptography", "PaymentCryptographyControlPlane.TagResource", "1.0")
                .body("{\"ResourceArn\":\"" + resource + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                .post("/").then();
    }

    /** A read of the key a request names by its id, its alias or its ARN. */
    private static ValidatableResponse getPaymentKey(User user, String identifier) {
        return user.json("payment-cryptography", "PaymentCryptographyControlPlane.GetKey", "1.0")
                .body("{\"KeyIdentifier\":\"" + identifier + "\"}").post("/").then();
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

    private static String denyOn(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
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
