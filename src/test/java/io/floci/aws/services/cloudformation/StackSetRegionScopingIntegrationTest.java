package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.StackSet;
import io.floci.aws.services.cloudformation.model.StackSetAutoDeploymentTarget;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StackSetErrors.assertNotFound;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A stack set is Regional, as on AWS: it is listed, described, changed and deleted only in the Region it was created
 * in, its administrator Region, and so are its instances and operations, while its instances deploy stacks in the
 * Regions they name. Its name can be used again in another Region, and automatic deployment runs in its Region,
 * whatever Region the Organizations call was sent to, the default Region for one stored before, without a Region, and
 * the stack set's own for one of a Region no partition publishes, served with {@code allow-unknown-regions} (from
 * {@link SidecarContainersProfile}). Each test works in an account of its own.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class StackSetRegionScopingIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(732200000000L);
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";
    /** The deployment's default Region, where the Organizations calls are sent unless a test sends one elsewhere. */
    private static final String DEFAULT_REGION = "us-east-1";
    /** A Region no partition publishes, served with {@code allow-unknown-regions}. */
    private static final String UNKNOWN = "polygondwanaland-west-1";
    private static final String OU_TARGET = "DeploymentTargets.OrganizationalUnitIds.member.1";
    private static final String INSTANCES = "ListStackInstancesResponse.ListStackInstancesResult.Summaries.member";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    StorageFactory storageFactory;

    private final OrganizationsFixture organizations = new OrganizationsFixture();
    private String account;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void accountOfItsOwn() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
    }

    /**
     * A stack set of {@code eu-west-1} with an instance in {@code eu-central-1} is neither listed nor reached from
     * {@code eu-central-1}, where its instance's stack is: not described, changed, deployed or deleted there, nor are
     * its instances and operations.
     */
    @Test
    void aStackSetIsListedAndReachedOnlyInTheRegionItWasCreatedIn() {
        String stackSet = "regional";
        createStackSet(HOME, stackSet, "regional-q").statusCode(200);
        String operation = cfn(HOME, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("Accounts.member.1", account)
                .formParam("Regions.member.1", OTHER)
                .post("/").then().statusCode(200)
                .extract().path("CreateStackInstancesResponse.CreateStackInstancesResult.OperationId");
        cleanup.register(() -> deleteWithInstances(HOME, stackSet, "Accounts.member.1", account));

        assertEquals(List.of(), stackSetNames(OTHER));
        assertNotFound(cfn(OTHER, "DescribeStackSet").formParam("StackSetName", stackSet), stackSet);
        assertNotFound(cfn(OTHER, "ListStackInstances").formParam("StackSetName", stackSet), stackSet);
        assertNotFound(cfn(OTHER, "DescribeStackInstance").formParam("StackSetName", stackSet)
                .formParam("StackInstanceAccount", account).formParam("StackInstanceRegion", OTHER), stackSet);
        assertNotFound(cfn(OTHER, "ListStackSetOperations").formParam("StackSetName", stackSet), stackSet);
        assertNotFound(cfn(OTHER, "DescribeStackSetOperation").formParam("StackSetName", stackSet)
                .formParam("OperationId", operation), stackSet);
        assertNotFound(cfn(OTHER, "UpdateStackSet").formParam("StackSetName", stackSet)
                .formParam("UsePreviousTemplate", "true"), stackSet);
        assertNotFound(cfn(OTHER, "CreateStackInstances").formParam("StackSetName", stackSet)
                .formParam("Accounts.member.1", account).formParam("Regions.member.1", HOME), stackSet);
        assertNotFound(cfn(OTHER, "DeleteStackInstances").formParam("StackSetName", stackSet)
                .formParam("Accounts.member.1", account).formParam("Regions.member.1", OTHER)
                .formParam("RetainStacks", "false"), stackSet);
        assertNotFound(cfn(OTHER, "DeleteStackSet").formParam("StackSetName", stackSet), stackSet);

        assertEquals(List.of(stackSet), stackSetNames(HOME));
        cfn(HOME, "DescribeStackSet").formParam("StackSetName", stackSet).post("/").then().statusCode(200)
                .body("DescribeStackSetResponse.DescribeStackSetResult.StackSet.StackSetARN",
                        startsWith("arn:aws:cloudformation:" + HOME + ":" + account + ":stackset/" + stackSet + ":"));
        cfn(HOME, "DescribeStackSetOperation").formParam("StackSetName", stackSet).formParam("OperationId", operation)
                .post("/").then().statusCode(200)
                .body("DescribeStackSetOperationResponse.DescribeStackSetOperationResult.StackSetOperation.Status",
                        equalTo("SUCCEEDED"));
        cfn(HOME, "ListStackInstances").formParam("StackSetName", stackSet).post("/").then().statusCode(200)
                .body("ListStackInstancesResponse.ListStackInstancesResult.Summaries.member.Region", equalTo(OTHER))
                .body("ListStackInstancesResponse.ListStackInstancesResult.Summaries.member.StackId",
                        startsWith("arn:aws:cloudformation:" + OTHER + ":" + account + ":stack/StackSet-" + stackSet));
        queueUrl(account, OTHER, "regional-q").statusCode(200)
                .body("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl", endsWith("/" + account + "/regional-q"));
    }

    /** A name is unique in its Region: each Region holds a stack set of its own under it, and deletes only its own. */
    @Test
    void aStackSetNameCanBeUsedAgainInAnotherRegion() {
        String stackSet = "shared";
        String home = createStackSet(HOME, stackSet, "shared-home-q").statusCode(200)
                .extract().path("CreateStackSetResponse.CreateStackSetResult.StackSetId");
        cleanup.register(() -> cfn(HOME, "DeleteStackSet").formParam("StackSetName", stackSet).post("/"));
        String other = createStackSet(OTHER, stackSet, "shared-other-q").statusCode(200)
                .extract().path("CreateStackSetResponse.CreateStackSetResult.StackSetId");
        cleanup.register(() -> cfn(OTHER, "DeleteStackSet").formParam("StackSetName", stackSet).post("/"));
        assertNotEquals(home, other);

        createStackSet(HOME, stackSet, "shared-again-q").statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("NameAlreadyExistsException"));
        cfn(OTHER, "DeleteStackSet").formParam("StackSetName", stackSet).post("/").then().statusCode(200);

        assertNotFound(cfn(OTHER, "DescribeStackSet").formParam("StackSetName", stackSet), stackSet);
        cfn(HOME, "DescribeStackSet").formParam("StackSetName", stackSet).post("/").then().statusCode(200)
                .body("DescribeStackSetResponse.DescribeStackSetResult.StackSet.StackSetId", equalTo(home));
    }

    /**
     * Automatic deployment runs in each stack set's Region: an account moved, by an Organizations call sent to
     * {@code us-east-1}, under the target of two service-managed stack sets of one name, one of {@code eu-west-1}
     * and one of {@code eu-central-1}, gets each one's instance, each automatic operation is its stack set's, in its
     * Region, and {@code us-east-1} holds no such stack set.
     */
    @Test
    void automaticDeploymentRunsInEachStackSetsRegion() {
        cleanup.register(organizations::cleanup);
        String root = organizations.createWithTrustedAccess(account);
        String target = organizations.createOu(account, root, "RegionalTarget");
        String stackSet = "regional-auto";
        for (String region : List.of(HOME, OTHER)) {
            cfn(region, "CreateStackSet")
                    .formParam("StackSetName", stackSet)
                    .formParam("TemplateBody", queueTemplate(region + "-auto-q"))
                    .formParam("PermissionModel", "SERVICE_MANAGED")
                    .formParam("AutoDeployment.Enabled", "true")
                    .formParam("AutoDeployment.RetainStacksOnAccountRemoval", "false")
                    .post("/").then().statusCode(200);
            cfn(region, "CreateStackInstances")
                    .formParam("StackSetName", stackSet)
                    .formParam(OU_TARGET, target)
                    .formParam("Regions.member.1", OTHER)
                    .post("/").then().statusCode(200);
            cleanup.register(() -> deleteWithInstances(region, stackSet, OU_TARGET, target));
        }

        String member = organizations.createAccount(account, "regional-auto-" + account + "@example.com",
                "RegionalAuto");
        organizations.moveAccount(account, member, root, target);

        for (String region : List.of(HOME, OTHER)) {
            awaitInstance(region, stackSet, member);
            queueUrl(member, OTHER, region + "-auto-q").statusCode(200);
            assertEquals(List.of("CREATE", "CREATE"), operationActions(region, stackSet));
        }
        assertEquals(List.of(), stackSetNames(DEFAULT_REGION));
        assertNotFound(cfn(DEFAULT_REGION, "ListStackSetOperations").formParam("StackSetName", stackSet), stackSet);
    }

    /**
     * An automatically deploying stack set stored before, without a Region, is the default Region's: an account moved
     * under its target by an Organizations call sent to {@code eu-west-1} gets its instance, its automatic operation is
     * recorded in {@code us-east-1}, and {@code eu-west-1} holds no such stack set.
     */
    @Test
    void aStackSetStoredBeforeDeploysAutomaticallyInTheDefaultRegion() {
        cleanup.register(organizations::cleanup);
        String root = organizations.createWithTrustedAccess(account);
        String target = organizations.createOu(account, root, "StoredBeforeTarget");
        String stackSet = "stored-before";
        StackSet stored = new StackSet();
        stored.setStackSetName(stackSet);
        stored.setStackSetId(stackSet + ":" + UUID.randomUUID());
        stored.setTemplateBody(queueTemplate("stored-before-q"));
        stored.setPermissionModel("SERVICE_MANAGED");
        stored.setAutoDeploymentEnabled(true);
        storageFactory.create("cloudformation", "cloudformation-stacksets.json",
                new TypeReference<Map<String, StackSet>>() {}).putForAccount(account, stackSet, stored);
        storageFactory.create("cloudformation", "cloudformation-stackset-auto-targets.json",
                new TypeReference<Map<String, StackSetAutoDeploymentTarget>>() {})
                .putForAccount(account, stackSet + "::" + target,
                        new StackSetAutoDeploymentTarget(stackSet, target, List.of(OTHER)));
        cleanup.register(() -> deleteWithInstances(DEFAULT_REGION, stackSet, OU_TARGET, target));

        String member = organizations.createAccount(account, "stored-before-" + account + "@example.com",
                "StoredBefore");
        organizations.moveAccount(account, HOME, member, root, target);

        awaitInstance(DEFAULT_REGION, stackSet, member);
        queueUrl(member, OTHER, "stored-before-q").statusCode(200);
        assertEquals(List.of("CREATE"), operationActions(DEFAULT_REGION, stackSet));
        assertEquals(List.of(), stackSetNames(HOME));
    }

    /**
     * A stack set of a Region no partition publishes deploys automatically in that Region: an account moved under its
     * target, by an Organizations call sent to {@code us-east-1}, gets its instance, and its automatic operation is
     * recorded there.
     */
    @Test
    void aStackSetOfAnUnknownRegionDeploysAutomaticallyThere() {
        cleanup.register(organizations::cleanup);
        String root = organizations.createWithTrustedAccess(account);
        String target = organizations.createOu(account, root, "UnknownRegionTarget");
        String stackSet = "unknown-region-auto";
        cfn(UNKNOWN, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate("unknown-region-auto-q"))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .formParam("AutoDeployment.Enabled", "true")
                .formParam("AutoDeployment.RetainStacksOnAccountRemoval", "false")
                .post("/").then().statusCode(200);
        cfn(UNKNOWN, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam(OU_TARGET, target)
                .formParam("Regions.member.1", OTHER)
                .post("/").then().statusCode(200);
        cleanup.register(() -> deleteWithInstances(UNKNOWN, stackSet, OU_TARGET, target));

        String member = organizations.createAccount(account, "unknown-region-" + account + "@example.com",
                "UnknownRegion");
        organizations.moveAccount(account, member, root, target);

        awaitInstance(UNKNOWN, stackSet, member);
        queueUrl(member, OTHER, "unknown-region-auto-q").statusCode(200);
        assertEquals(List.of("CREATE", "CREATE"), operationActions(UNKNOWN, stackSet));
    }

    private ValidatableResponse createStackSet(String region, String stackSet, String queue) {
        return cfn(region, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .post("/").then();
    }

    /** The names {@code ListStackSets} lists in {@code region}. */
    private List<String> stackSetNames(String region) {
        return cfn(region, "ListStackSets").post("/").then().statusCode(200).extract().xmlPath()
                .getList("ListStackSetsResponse.ListStackSetsResult.Summaries.member.StackSetName", String.class);
    }

    /** The actions of the operations {@code ListStackSetOperations} lists for the {@code region} stack set. */
    private List<String> operationActions(String region, String stackSet) {
        return cfn(region, "ListStackSetOperations").formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200).extract().xmlPath().getList(
                        "ListStackSetOperationsResponse.ListStackSetOperationsResult.Summaries.member.Action",
                        String.class);
    }

    /** Waits until the {@code region} stack set has a successful instance in {@code member}'s {@code eu-central-1}. */
    private void awaitInstance(String region, String stackSet, String member) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                cfn(region, "ListStackInstances").formParam("StackSetName", stackSet)
                        .formParam("StackInstanceAccount", member)
                        .post("/").then().statusCode(200)
                        .body(INSTANCES + ".Region", equalTo(OTHER))
                        .body(INSTANCES + ".StackInstanceStatus.DetailedStatus", equalTo("SUCCEEDED")));
    }

    /**
     * Deletes the {@code region} stack set's instances in {@code eu-central-1} of the account or the root or OU
     * {@code target} names, as the request member {@code targetMember}, with their stacks, then the stack set.
     */
    private void deleteWithInstances(String region, String stackSet, String targetMember, String target) {
        cfn(region, "DeleteStackInstances").formParam("StackSetName", stackSet)
                .formParam(targetMember, target).formParam("Regions.member.1", OTHER)
                .formParam("RetainStacks", "false").post("/");
        cfn(region, "DeleteStackSet").formParam("StackSetName", stackSet).post("/");
    }

    private RequestSpecification cfn(String region, String action) {
        return CfnRequests.cfn(action, null).header("Authorization", auth(account, region, "cloudformation"));
    }

    private static ValidatableResponse queueUrl(String owner, String region, String queue) {
        return given().header("Authorization", auth(owner, region, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queue)
                .post("/").then();
    }

    private static String queueTemplate(String queue) {
        return "{\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":\""
                + queue + "\"}}}}";
    }
}
