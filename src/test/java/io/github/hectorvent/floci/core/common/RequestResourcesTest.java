package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@link RequestResources} builds the ARNs of the resources a request names. */
class RequestResourcesTest {

    private static final String MACHINE = "arn:aws:states:us-east-1:000000000000:stateMachine:orders";

    @Test
    void aMemberHoldingAnArnOfATypeTheActionActsOnNamesThatResource() {
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + MACHINE + "\"}")));
    }

    /** {@code CreateStateMachine}'s {@code roleArn} is an IAM role, no resource of Step Functions. */
    @Test
    void anArnOfAnotherTypeIsNotChecked() {
        assertEquals(List.of(MACHINE), of("states:CreateStateMachine", json(
                "{\"name\":\"orders\",\"roleArn\":\"arn:aws:iam::000000000000:role/sfn\",\"definition\":\"{}\"}")));
    }

    /**
     * A handler may act on the resource the ARN names or on one of that name in the caller's account or the
     * request's Region, or both (ECR's tag handlers keep the ARN's account in the request's Region), so each is
     * checked.
     */
    @Test
    void anArnOfAnotherAccountOrRegionIsCheckedAsGivenAndAsTheCallersOwn() {
        String other = "arn:aws:states:eu-west-1:111122223333:stateMachine:orders";
        assertEquals(List.of(other, "arn:aws:states:us-east-1:111122223333:stateMachine:orders",
                "arn:aws:states:eu-west-1:000000000000:stateMachine:orders", MACHINE), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + other + "\"}")));
        assertEquals(List.of("arn:aws:ecr:eu-west-1:111122223333:repository/web",
                "arn:aws:ecr:us-east-1:111122223333:repository/web", "arn:aws:ecr:eu-west-1:000000000000:repository/web",
                "arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:TagResource",
                json("{\"resourceArn\":\"arn:aws:ecr:eu-west-1:111122223333:repository/web\",\"tags\":[]}")));
    }

    @Test
    void aTypesVariablesAreFilledFromTheRequestAndAListGivesAResourceForEachValue() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web",
                "arn:aws:ecr:us-east-1:000000000000:repository/api"), of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"web\",\"api\"]}")));
        assertEquals(List.of("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc"),
                of("cognito-idp:DescribeUserPool", json("{\"UserPoolId\":\"us-east-1_abc\"}")));
    }

    /** A value that is itself an ARN of the type names that resource. */
    @Test
    void aValueThatIsAnArnOfTheTypeNamesItsResource() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"arn:aws:ecr:us-east-1:000000000000:repository/web\"]}")));
    }

    /** The service mints a new target group's id, so it is open. */
    @Test
    void aVariableTheRequestDoesNotFillIsOpen() {
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/*"),
                of("elasticloadbalancing:CreateTargetGroup",
                        form("Action=CreateTargetGroup&Name=web&Protocol=HTTP&Port=80")));
    }

    /** A v2 load balancer's type, application when none is given, picks the ARN it is created under. */
    @Test
    void aLoadBalancerIsCreatedUnderTheArnOfItsType() {
        String prefix = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/";
        assertEquals(List.of(prefix + "app/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web")));
        assertEquals(List.of(prefix + "net/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=network")));
        assertEquals(List.of(prefix + "gwy/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=gateway")));
        assertEquals(List.of(), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=other")));
        // The classic API names it by LoadBalancerName.
        assertEquals(List.of(prefix + "classic"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&LoadBalancerName=classic&Listeners.member.1.Protocol=HTTP")));
    }

    /**
     * A listener is made under a load balancer, and a rule under a listener: their ARNs are the parent's with its
     * type word replaced and their own id open, of the parent's type.
     */
    @Test
    void aResourceCreatedUnderAParentIsNamedFromTheParentsArn() {
        String prefix = "arn:aws:elasticloadbalancing:us-east-1:000000000000:";
        assertEquals(List.of(prefix + "listener/app/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=" + prefix + "loadbalancer/app/web/abc&Protocol=HTTP&Port=80")));
        assertEquals(List.of(prefix + "listener/net/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=" + prefix + "loadbalancer/net/web/abc&Protocol=TCP&Port=80")));
        assertEquals(List.of(prefix + "listener-rule/app/web/abc/def/*"), created("elasticloadbalancing:CreateRule",
                form("Action=CreateRule&ListenerArn=" + prefix + "listener/app/web/abc/def&Priority=1")));
        // A parent of another account is named as given and as the caller's own.
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:111122223333:listener/app/web/abc/*",
                prefix + "listener/app/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=arn:aws:elasticloadbalancing:us-east-1:111122223333:"
                        + "loadbalancer/app/web/abc")));
        // An ARN of no parent, a create of a type its action acts on, and no create name nothing.
        assertEquals(List.of(), created("elasticloadbalancing:CreateListener",
                form("Action=CreateListener&LoadBalancerArn=" + prefix + "targetgroup/web/abc")));
        assertEquals(List.of(), created("elasticloadbalancing:CreateTargetGroup",
                form("Action=CreateTargetGroup&Name=web")));
        assertEquals(List.of(), created("elasticloadbalancing:DeleteListener",
                form("Action=DeleteListener&ListenerArn=" + prefix + "listener/app/web/abc/def")));
    }

    /**
     * The operation is the one the request is dispatched as: an {@code X-Amz-Target} or {@code Action} naming
     * another, whose members name an allowed resource, does not change what it is checked on.
     */
    @Test
    void theOperationIsTheOneTheRequestIsDispatchedAsNotOneTheRequestNames() {
        ContainerRequestContext request = json("{\"name\":\"allowed\",\"stateMachineArn\":\"" + MACHINE + "\"}");
        when(request.getHeaderString("X-Amz-Target")).thenReturn("AWSStepFunctions.CreateStateMachine");
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine", request));
        String group = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/1";
        assertEquals(List.of(group), of("elasticloadbalancing:DeleteTargetGroup",
                form("Action=CreateTargetGroup&Name=allowed&TargetGroupArn=" + group)));
        assertEquals(List.of(), created("elasticloadbalancing:DeleteLoadBalancer", form("Action=CreateListener"
                + "&LoadBalancerArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/web/abc")));
    }

    @Test
    void aFormsListIsReadFromOneToTheFirstMissingNumber() {
        String first = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/a/1";
        String second = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/b/2";
        assertEquals(List.of(first, second), of("elasticloadbalancing:AddTags", form("Action=AddTags"
                + "&ResourceArns.member.1=" + first + "&ResourceArns.member.2=" + second
                + "&ResourceArns.member.4=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/c/3"
                + "&Tags.member.1.Key=team")));
        // A classic load balancer goes by its name.
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/classic"),
                of("elasticloadbalancing:DeleteLoadBalancer",
                        form("Action=DeleteLoadBalancer&LoadBalancerName=classic")));
    }

    /**
     * A member is read as its handler reads it: a list only from a list, so a single name for one gives none, as
     * ECR's handler then lists every repository, a number, boolean or null as its text, and a structure as none.
     */
    @Test
    void aMemberIsReadAsItsHandlerReadsIt() {
        assertEquals(List.of(), of("ecr:DescribeRepositories", json("{\"repositoryNames\":\"allowed\"}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/123",
                "arn:aws:ecr:us-east-1:000000000000:repository/null"),
                of("ecr:DescribeRepositories", json("{\"repositoryNames\":[123,{\"a\":1},null]}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/7"),
                of("ecr:DeleteRepository", json("{\"repositoryName\":7}")));
        assertEquals(List.of(), of("ecr:DeleteRepository", json("{\"repositoryName\":[\"web\"]}")));
    }

    /**
     * ECR's {@code registryId} selects the registry its handler acts in, so the repositories are that account's;
     * one that is no account id names none.
     */
    @Test
    void aRepositoryIsInTheRegistryTheRequestNames() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:111122223333:repository/web"), of("ecr:DescribeRepositories",
                json("{\"registryId\":\"111122223333\",\"repositoryNames\":[\"web\"]}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories",
                json("{\"repositoryNames\":[\"web\"]}")));
        assertEquals(List.of(), of("ecr:DescribeRepositories",
                json("{\"registryId\":\"1:2\",\"repositoryNames\":[\"web\"]}")));
        // A JSON null is no registry, as ECR reads it: the caller's.
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories",
                json("{\"registryId\":null,\"repositoryNames\":[\"web\"]}")));
        // The scanning configuration's request has no registryId: its repositories are the caller's.
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"),
                of("ecr:BatchGetRepositoryScanningConfiguration",
                        json("{\"registryId\":\"111122223333\",\"repositoryNames\":[\"web\"]}")));
    }

    /**
     * {@code StartExecution} takes Step Functions Local's {@code <stateMachineArn>#<test case>} and runs the
     * machine the part before the {@code #} names, so that machine is checked; other operations read it whole.
     */
    @Test
    void aStateMachinesTestCaseIsCheckedOnTheMachineItRuns() {
        assertEquals(List.of(MACHINE), of("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + "#HappyPath\"}")));
        assertEquals(List.of(MACHINE), of("states:StartSyncExecution", json("{\"stateMachineArn\":\"" + MACHINE + "#\"}")));
        assertEquals(List.of(MACHINE + "#x"), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + MACHINE + "#x\"}")));
        assertEquals(List.of("arn:aws:states:us-east-1:000000000000:execution:orders:*"), created("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + "#HappyPath\"}")));
    }

    /**
     * Step Functions checks a version or an alias on its state machine's ARN, so a request naming one is checked on
     * the state machine, and an execution it starts is the state machine's; other types keep their ARN whole.
     */
    @Test
    void aVersionOrAnAliasIsCheckedOnItsStateMachine() {
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachineAlias",
                json("{\"stateMachineAliasArn\":\"" + MACHINE + ":PROD\"}")));
        assertEquals(List.of(MACHINE), of("states:DeleteStateMachineVersion",
                json("{\"stateMachineVersionArn\":\"" + MACHINE + ":3\"}")));
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine", json("{\"stateMachineArn\":\"" + MACHINE + ":1\"}")));
        assertEquals(List.of(MACHINE), of("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + ":PROD#HappyPath\"}")));
        assertEquals(List.of("arn:aws:states:us-east-1:000000000000:execution:orders:*"), created("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + ":PROD\"}")));
        String execution = "arn:aws:states:us-east-1:000000000000:execution:orders:run-1";
        assertEquals(List.of(execution), of("states:DescribeExecution", json("{\"executionArn\":\"" + execution + "\"}")));
    }

    /** A request value is a name, so a {@code ${...}} in it is kept, not read as a variable of the format. */
    @Test
    void aValueIsTakenLiterally() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/${Account}"),
                of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"${Account}\"]}")));
    }

    @Test
    void aRequestNamingNoResourceOrOfANamespaceTheTableDoesNotReadIsCheckedOnStar() {
        assertEquals(List.of(), of("ecr:DescribeRepositories", json("{}")));
        assertEquals(List.of(), of("ecr:GetAuthorizationToken", json("{}")));
        assertEquals(List.of(), of("ecr:NoSuchOperation", json("{\"repositoryName\":\"web\"}")));
        assertEquals(List.of(), of(null, json("{\"repositoryName\":\"web\"}")));
        assertEquals(List.of(), of("lambda:GetFunction", json("{\"FunctionName\":\"f\"}")));
    }

    private static List<String> created(String operation, ContainerRequestContext request) {
        return RequestResources.created(operation, request, "aws", "us-east-1", "000000000000");
    }

    private static List<String> of(String operation, ContainerRequestContext request) {
        return RequestResources.of(operation, request, "aws", "us-east-1", "000000000000");
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body);
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body);
    }

    private static ContainerRequestContext request(String mediaType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
