package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StepFunctionsTestClient.LAMBDA_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.STATES_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.trusting;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ECS, EMR and EMR Serverless tasks under IAM enforcement. All three run for real in their test defaults, with no
 * container behind them (ECS in mock mode, EMR's and EMR Serverless's work simulated), so the tests read what a task
 * did from the service itself.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsJobTaskIamIntegrationTest {
    private static final String ACCOUNT = "000000000471";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMR_SERVERLESS_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"emr-serverless.amazonaws.com"}}]}
            """;
    private static final String READ_ONLY = allow("*", "sns:ListTopics");
    private static final String ASSUME = allow("*", "sts:AssumeRole");
    private static final String ALLOW_ALL =
            allow("*", "ecs:*", "elasticmapreduce:*", "emr-serverless:*", "sts:AssumeRole");
    private static final String ANY_RESOURCE = "*";
    private static final Check NOTHING_TO_READ = () -> { };
    private static final Map<String, Object> GROUPS = Map.of("KeepJobFlowAliveWhenNoSteps", true, "InstanceGroups",
            List.of(Map.of("Name", "Primary", "InstanceRole", "MASTER", "InstanceType", "m5.xlarge", "InstanceCount", 1),
                    Map.of("Name", "Core", "InstanceRole", "CORE", "InstanceType", "m5.xlarge", "InstanceCount", 2)));
    private static final Map<String, Object> FLEETS = Map.of("KeepJobFlowAliveWhenNoSteps", true, "InstanceFleets",
            List.of(Map.of("Name", "Primary", "InstanceFleetType", "MASTER", "TargetOnDemandCapacity", 1,
                            "InstanceTypeConfigs", List.of(Map.of("InstanceType", "m5.xlarge"))),
                    Map.of("Name", "Core", "InstanceFleetType", "CORE", "TargetOnDemandCapacity", 2,
                            "InstanceTypeConfigs", List.of(Map.of("InstanceType", "m5.xlarge")))));
    private static final Map<String, Object> STEP = Map.of("Name", "job", "ActionOnFailure", "CONTINUE",
            "HadoopJarStep", Map.of("Jar", "command-runner.jar", "Args", List.of("spark-submit", "s3://bucket/job.py")));
    private static final Map<String, Object> JOB_DRIVER =
            Map.of("SparkSubmit", Map.of("EntryPoint", "s3://bucket/jobs/app.py"));

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Every API an integration serves, each with what it reaches. */
    static Stream<String> everyApi() {
        return Stream.of("ecs:runTask", "aws-sdk:ecs:updateService", "aws-sdk:ecs:listServices",
                "aws-sdk:ecs:describeServices", "elasticmapreduce:createCluster",
                "elasticmapreduce:setClusterTerminationProtection", "elasticmapreduce:terminateCluster",
                "elasticmapreduce:addStep", "elasticmapreduce:cancelStep", "elasticmapreduce:modifyInstanceFleetByName",
                "elasticmapreduce:modifyInstanceGroupByName", "emr-serverless:createApplication",
                "emr-serverless:startApplication", "emr-serverless:stopApplication", "emr-serverless:deleteApplication",
                "emr-serverless:startJobRun", "emr-serverless:cancelJobRun");
    }

    /** One of each integration, for what does not differ between an integration's APIs. */
    static Stream<String> eachIntegration() {
        return Stream.of("ecs:runTask", "aws-sdk:ecs:updateService", "elasticmapreduce:addStep",
                "emr-serverless:startApplication");
    }

    static Stream<Arguments> eachIntegrationInBothLanguages() {
        return eachIntegration().flatMap(integration -> Stream.of(Arguments.of(integration, "jsonpath"),
                Arguments.of(integration, "jsonata")));
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void anExecutionRoleAllowedTheActionOnTheResourceReachesTheService(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(jsonPathTask(target, null), role(STATES_TRUST, target.grant())), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void anExecutionRoleNotAllowedTheActionIsRefused(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(jsonPathTask(target, null), role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, target.error());
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains(target.action()), cause);
        assertTrue(cause.contains(target.resourceArn()), cause);
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void theActionOnAnotherResourceDoesNotCount(String integration) throws Exception {
        Target target = target(integration);
        assumeFalse(ANY_RESOURCE.equals(target.resourceArn()), "the service reference gives it no resource");
        String role = role(STATES_TRUST, allow(target.resourceArn() + "-elsewhere", target.actions()));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), target.error());
        target.notReached().check();
    }

    /** {@code *} is what these are evaluated on: a role allowed them only on some ARN is refused. */
    @ParameterizedTest
    @MethodSource("everyApi")
    void anActionWithNoResourceTypeNeedsAGrantOnAnyResource(String integration) throws Exception {
        Target target = target(integration);
        assumeTrue(ANY_RESOURCE.equals(target.resourceArn()), "the service reference gives it a resource");
        String role = role(STATES_TRUST, allow("arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":cluster/x", target.action()));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), target.error());
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void anExecutionRoleThatDoesNotTrustStepFunctionsIsRefusedWhateverItsPolicies(String integration) throws Exception {
        Target target = target(integration);
        String role = role(LAMBDA_TRUST, target.grant());

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void anExecutionRoleThatDoesNotExistIsRefused(String integration) throws Exception {
        Target target = target(integration);
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegrationInBothLanguages")
    void credentialsReachTheServiceAsTheirRoleWithoutTheExecutionRolesPermission(String integration, String form)
            throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), target.grant());
        Map<String, Object> task = "jsonata".equals(form) ? jsonataTask(target, "{% $states.input.role %}")
                : jsonPathTask(target, taskRole);

        String execution = start(machine(task, executionRole), MAPPER.writeValueAsString(Map.of("role", taskRole)));
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
        assertEquals(taskRole, eventsOfType(execution, "TaskScheduled").getFirst()
                .path("taskScheduledEventDetails").path("taskCredentials").path("roleArn").asText());
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aRoleTheExecutionRoleCannotAssumeFailsInTheNestedForm(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, target.grant());
        String taskRole = role(STATES_TRUST, target.grant());

        Response completed = terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").contains("is not authorized to assume"), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aCredentialsRoleNotAllowedTheActionIsRefusedEvenIfTheExecutionRoleIs(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ALLOW_ALL);
        String taskRole = role(trusting(executionRole), READ_ONLY);

        assertFailed(terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}")), target.error());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aRefusalCanBeRetriedAndCaught(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask(target, null));
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of(target.error()), "IntervalSeconds", 1,
                "BackoffRate", 1.0, "MaxAttempts", 1)));
        task.put("Catch", List.of(Map.of("ErrorEquals", List.of(target.error()), "Next", "Recovered")));
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", task,
                "Recovered", Map.of("Type", "Pass", "End", true))));
        String execution = start(machineWithDefinition(definition, role(STATES_TRUST, READ_ONLY)), "{}");

        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(2, eventsOfType(execution, "TaskFailed").size());
        assertEquals(target.error(), eventsOfType(execution, "TaskFailed").getLast()
                .path("taskFailedEventDetails").path("error").asText());
    }

    /** A family is the revision it would run: the grant on that revision is what counts, however it is named. */
    @ParameterizedTest
    @ValueSource(strings = {"family", "revision", "arn"})
    void aTaskDefinitionIsEvaluatedAsTheRevisionItRuns(String form) throws Exception {
        String family = family();
        registerRevision(family);
        String latest = taskDefinitionArn(family, 2);
        String named = switch (form) {
            case "family" -> family;
            case "revision" -> family + ":2";
            default -> latest;
        };
        String cluster = ecsCluster();
        Map<String, Object> task = task("ecs:runTask", Map.of("Cluster", cluster, "TaskDefinition", named));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(latest, "ecs:RunTask"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(1, runningTasks(cluster).size());

        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow(taskDefinitionArn(family, 1), "ecs:RunTask"))), "{}"));
        assertFailed(refused, "ECS.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(latest), refused.asString());
        assertEquals(1, runningTasks(cluster).size());
    }

    @Test
    void anOlderRevisionNamedOutrightIsThatRevision() throws Exception {
        String family = family();
        registerRevision(family);
        String cluster = ecsCluster();
        Map<String, Object> task = task("ecs:runTask", Map.of("Cluster", cluster, "TaskDefinition", family + ":1"));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allow(taskDefinitionArn(family, 1), "ecs:RunTask"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(1, runningTasks(cluster).size());
    }

    /** The CDK grants {@code ecs:RunTask} on {@code <family>:*} without its fix's feature flag. */
    @Test
    void aGrantOnEveryRevisionOfAFamilyCovers() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        Map<String, Object> task = task("ecs:runTask", Map.of("Cluster", cluster, "TaskDefinition", family));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allow("arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":task-definition/" + family + ":*", "ecs:RunTask"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    @Test
    void aTaskDefinitionEcsDoesNotHaveIsLeftToEcs() throws Exception {
        Map<String, Object> task = task("ecs:runTask", Map.of("TaskDefinition", "no-such-family-" + UUID.randomUUID()));

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "ECS.ClientException");
    }

    /** {@code .sync} needs the starting call alone: the wait reads what the task launched with no Describe grant. */
    @Test
    void runTaskSyncNeedsOnlyRunTaskAndWaits() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        Map<String, Object> task = task("ecs:runTask.sync", Map.of("Cluster", cluster, "TaskDefinition", family));
        String role = role(STATES_TRUST, allow(taskDefinitionArn(family, 1), "ecs:RunTask"));

        String execution = start(machine(task, role), "{}");
        String launched = awaitRunningTask(cluster);
        assertEquals("RUNNING", request("DescribeExecution", Map.of("executionArn", execution))
                .jsonPath().getString("status"));
        ecs("StopTask", Map.of("cluster", cluster, "task", launched)).then().statusCode(200);

        Response completed = terminal(execution);
        assertFalse(String.valueOf(completed.jsonPath().getString("error")).endsWith("AccessDeniedException"),
                completed.asString());
    }

    @Test
    void runTaskSyncRefusedLaunchesNothing() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        Map<String, Object> task = task("ecs:runTask.sync", Map.of("Cluster", cluster, "TaskDefinition", family));

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "ECS.AccessDeniedException");
        assertEquals(List.of(), runningTasks(cluster));
    }

    @Test
    void runTaskWaitForTaskTokenAllowedLaunchesAndWaits() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        Map<String, Object> task = new LinkedHashMap<>(task("ecs:runTask.waitForTaskToken",
                Map.of("Cluster", cluster, "TaskDefinition", family, "StartedBy.$", "$$.Task.Token")));
        task.put("TimeoutSeconds", 60);
        String role = role(STATES_TRUST, allow(taskDefinitionArn(family, 1), "ecs:RunTask"));

        String execution = start(machine(task, role), "{}");
        String launched = awaitRunningTask(cluster);
        Response described = ecs("DescribeTasks", Map.of("cluster", cluster, "tasks", List.of(launched)));
        String token = described.jsonPath().getString("tasks[0].startedBy");
        request("SendTaskSuccess", Map.of("taskToken", token, "output", "{\"done\":true}")).then().statusCode(200);

        Response completed = terminal(execution);
        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @Test
    void runTaskWaitForTaskTokenRefusedDoesNotWaitAndLaunchesNothing() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        Map<String, Object> task = new LinkedHashMap<>(task("ecs:runTask.waitForTaskToken",
                Map.of("Cluster", cluster, "TaskDefinition", family, "StartedBy.$", "$$.Task.Token")));
        task.put("TimeoutSeconds", 60);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "ECS.AccessDeniedException");
        assertEquals(List.of(), runningTasks(cluster));
    }

    /** {@code .sync} authorizes the starting call: none of these policies holds the read the wait makes. */
    @ParameterizedTest
    @CsvSource({"elasticmapreduce:addStep.sync,elasticmapreduce:addStep",
            "elasticmapreduce:createCluster.sync,elasticmapreduce:createCluster",
            "emr-serverless:startApplication.sync,emr-serverless:startApplication"})
    void aSyncTaskNeedsOnlyItsStartingCall(String resource, String api) throws Exception {
        Target target = target(api);
        Map<String, Object> task = task(resource, target.arguments());

        Response completed = terminal(start(machine(task, role(STATES_TRUST, target.grant())), "{}"), 60);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @ParameterizedTest
    @CsvSource({"elasticmapreduce:addStep.sync,elasticmapreduce:addStep,EMR.AccessDeniedException",
            "emr-serverless:startApplication.sync,emr-serverless:startApplication,EmrServerless.AccessDeniedException"})
    void aSyncTaskRefusedStartsNothing(String resource, String api, String error) throws Exception {
        Target target = target(api);

        assertFailed(terminal(start(machine(task(resource, target.arguments()), role(STATES_TRUST, READ_ONLY)), "{}")),
                error);
        target.notReached().check();
    }

    /** A ByName API lists the cluster's members to find the id, so either action alone stops it. */
    @ParameterizedTest
    @CsvSource({
            "elasticmapreduce:modifyInstanceFleetByName,elasticmapreduce:ListInstanceFleets",
            "elasticmapreduce:modifyInstanceFleetByName,elasticmapreduce:ModifyInstanceFleet",
            "elasticmapreduce:modifyInstanceGroupByName,elasticmapreduce:ListInstanceGroups",
            "elasticmapreduce:modifyInstanceGroupByName,elasticmapreduce:ModifyInstanceGroups"})
    void aByNameApiNeedsBothItsActions(String integration, String alone) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, allow(target.resourceArn(), alone));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), "EMR.AccessDeniedException");
        target.notReached().check();
    }

    /** The service ARN's own cluster is not what {@code UpdateService} reads: the task acts on the request's. */
    @Test
    void updateServiceIsEvaluatedOnTheServiceItActsOn() throws Exception {
        String family = family();
        String named = ecsCluster();
        String other = ecsCluster();
        String name = "svc-" + UUID.randomUUID();
        String inNamed = ecsService(named, name, family);
        String inOther = ecsService(other, name, family);
        Map<String, Object> task = task("aws-sdk:ecs:updateService",
                Map.of("Cluster", other, "Service", inNamed, "DesiredCount", 1));

        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(inNamed, "ecs:UpdateService"))), "{}"));
        assertFailed(refused, "Ecs.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(inOther), refused.asString());
        assertEquals(0, desiredCount(other, name));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(inOther, "ecs:UpdateService"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(1, desiredCount(other, name));
        assertEquals(0, desiredCount(named, name));
    }

    @Test
    void describeServicesNeedsTheActionOnEveryService() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        String first = ecsService(cluster, "first", family);
        String second = ecsService(cluster, "second", family);
        Map<String, Object> task = task("aws-sdk:ecs:describeServices",
                Map.of("Cluster", cluster, "Services", List.of("first", second)));

        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(first, "ecs:DescribeServices"))), "{}"));
        assertFailed(refused, "Ecs.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(second), refused.asString());

        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allow(serviceArn(cluster, "*"), "ecs:DescribeServices"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(2, MAPPER.readTree(allowed.jsonPath().getString("output")).path("Services").size());
    }

    /** What a service does not have reaches nothing: a role allowed nothing gets the service's own answer. */
    @Test
    void aServiceEcsDoesNotHaveIsLeftToEcs() throws Exception {
        String cluster = ecsCluster();
        String role = role(STATES_TRUST, READ_ONLY);

        assertFailed(terminal(start(machine(task("aws-sdk:ecs:updateService",
                Map.of("Cluster", cluster, "Service", "no-such-service")), role), "{}")), "Ecs.ServiceNotFoundException");
        assertFailed(terminal(start(machine(task("aws-sdk:ecs:updateService",
                Map.of("Cluster", "no-such-cluster", "Service", "any")), role), "{}")), "Ecs.ClusterNotFoundException");
        Response described = terminal(start(machine(task("aws-sdk:ecs:describeServices",
                Map.of("Cluster", cluster, "Services", List.of("no-such-service"))), role), "{}"));
        assertEquals("SUCCEEDED", described.jsonPath().getString("status"), described.asString());
        assertEquals("MISSING", MAPPER.readTree(described.jsonPath().getString("output"))
                .path("Failures").get(0).path("Reason").asText());
    }

    /** A form or an API the integration does not serve keeps its existing answer, whatever the role may do. */
    @ParameterizedTest
    @ValueSource(strings = {"elasticmapreduce:createCluster.waitForTaskToken",
            "elasticmapreduce:addStep.waitForTaskToken", "elasticmapreduce:cancelStep.sync",
            "elasticmapreduce:listClusters", "emr-serverless:createApplication.waitForTaskToken",
            "emr-serverless:startJobRun.sync:2", "emr-serverless:listApplications", "aws-sdk:ecs:runTask",
            "aws-sdk:ecs:updateService.sync", "ecs:describeTasks"})
    void aFormOrAnApiNotServedStaysUnsupported(String resource) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(task(resource,
                Map.of("ClusterId", "j-any", "ApplicationId", "00any", "Service", "any", "TaskDefinition", "any")));
        task.put("TimeoutSeconds", 60);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").contains("Unsupported"), completed.asString());
    }

    /** The integration's own validation answers a request naming nothing, whatever the role may do. */
    @ParameterizedTest
    @CsvSource({"elasticmapreduce:addStep,The field ClusterId is required.",
            "elasticmapreduce:terminateCluster,The field ClusterId is required.",
            "emr-serverless:startApplication,The field ApplicationId is required.",
            "emr-serverless:startJobRun,The field ApplicationId is required.",
            "ecs:runTask,TaskDefinition is required for the ecs:runTask integration"})
    void aRequestNamingNothingIsLeftToTheIntegration(String resource, String cause) throws Exception {
        Response completed = terminal(start(machine(task(resource, Map.of("Other", "x")),
                role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertEquals(cause, completed.jsonPath().getString("cause"), completed.asString());
    }

    @Test
    void cancelJobRunNamingNoRunIsLeftToTheIntegration() throws Exception {
        String application = application();
        Response completed = terminal(start(machine(task("emr-serverless:cancelJobRun",
                Map.of("ApplicationId", application)), role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertEquals("The field JobRunId is required.", completed.jsonPath().getString("cause"), completed.asString());
    }

    /** A Task may write an EMR Serverless id in the API's camelCase: it is still the same resource. */
    @Test
    void anApplicationIdWrittenInCamelCaseIsStillEvaluated() throws Exception {
        String application = application();
        Map<String, Object> task = task("emr-serverless:startApplication", Map.of("applicationId", application));

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")),
                "EmrServerless.AccessDeniedException");
    }

    /**
     * What a task acts on and how to observe it.
     *
     * @param actions the actions the call needs, all on {@code resourceArn}, in the order they are evaluated
     */
    private record Target(String integration, String error, String resourceArn, Map<String, Object> arguments,
                          Check reached, Check notReached, String... actions) {

        String action() {
            return actions[0];
        }

        String grant() {
            return allow(resourceArn, actions);
        }
    }

    @FunctionalInterface
    private interface Check {
        void check() throws Exception;
    }


    private Target target(String integration) throws Exception {
        return switch (integration) {
            case "ecs:runTask" -> runTaskTarget();
            case "aws-sdk:ecs:updateService" -> updateServiceTarget();
            case "aws-sdk:ecs:listServices" -> new Target(integration, "Ecs.AccessDeniedException", ANY_RESOURCE,
                    Map.of("Cluster", ecsCluster()), NOTHING_TO_READ, NOTHING_TO_READ, "ecs:ListServices");
            case "aws-sdk:ecs:describeServices" -> describeServicesTarget();
            case "elasticmapreduce:createCluster" -> createClusterTarget();
            case "elasticmapreduce:setClusterTerminationProtection" -> terminationProtectionTarget();
            case "elasticmapreduce:terminateCluster" -> terminateClusterTarget();
            case "elasticmapreduce:addStep" -> addStepTarget();
            case "elasticmapreduce:cancelStep" -> cancelStepTarget();
            case "elasticmapreduce:modifyInstanceFleetByName" -> fleetTarget();
            case "elasticmapreduce:modifyInstanceGroupByName" -> groupTarget();
            case "emr-serverless:createApplication" -> createApplicationTarget();
            case "emr-serverless:startApplication" -> startApplicationTarget();
            case "emr-serverless:stopApplication" -> stopApplicationTarget();
            case "emr-serverless:deleteApplication" -> deleteApplicationTarget();
            case "emr-serverless:startJobRun" -> startJobRunTarget();
            case "emr-serverless:cancelJobRun" -> cancelJobRunTarget();
            default -> throw new IllegalArgumentException(integration);
        };
    }

    private Target runTaskTarget() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        return new Target("ecs:runTask", "ECS.AccessDeniedException", taskDefinitionArn(family, 1),
                Map.of("Cluster", cluster, "TaskDefinition", family),
                () -> assertEquals(1, runningTasks(cluster).size()),
                () -> assertEquals(List.of(), runningTasks(cluster)), "ecs:RunTask");
    }

    private Target updateServiceTarget() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        String service = ecsService(cluster, "svc", family);
        return new Target("aws-sdk:ecs:updateService", "Ecs.AccessDeniedException", service,
                Map.of("Cluster", cluster, "Service", "svc", "DesiredCount", 1),
                () -> assertEquals(1, desiredCount(cluster, "svc")),
                () -> assertEquals(0, desiredCount(cluster, "svc")), "ecs:UpdateService");
    }

    private Target describeServicesTarget() throws Exception {
        String family = family();
        String cluster = ecsCluster();
        String service = ecsService(cluster, "svc", family);
        return new Target("aws-sdk:ecs:describeServices", "Ecs.AccessDeniedException", service,
                Map.of("Cluster", cluster, "Services", List.of("svc")), NOTHING_TO_READ, NOTHING_TO_READ,
                "ecs:DescribeServices");
    }

    private Target createClusterTarget() {
        String name = "sfn-jobs-" + UUID.randomUUID();
        cleanup.register(() -> {
            for (String id : clusterIdsNamed(name)) {
                emr("TerminateJobFlows", Map.of("JobFlowIds", List.of(id))).then().statusCode(200);
            }
        });
        return new Target("elasticmapreduce:createCluster", "EMR.AccessDeniedException", ANY_RESOURCE,
                Map.of("Name", name, "ReleaseLabel", "emr-7.5.0", "Instances", GROUPS),
                () -> assertEquals(1, clusterIdsNamed(name).size()),
                () -> assertEquals(List.of(), clusterIdsNamed(name)), "elasticmapreduce:RunJobFlow");
    }

    private Target terminationProtectionTarget() throws Exception {
        String id = emrCluster(GROUPS);
        return new Target("elasticmapreduce:setClusterTerminationProtection", "EMR.AccessDeniedException",
                emrClusterArn(id), Map.of("ClusterId", id, "TerminationProtected", true),
                () -> assertTrue(describeCluster(id).path("TerminationProtected").asBoolean()),
                () -> assertFalse(describeCluster(id).path("TerminationProtected").asBoolean()),
                "elasticmapreduce:SetTerminationProtection");
    }

    private Target terminateClusterTarget() throws Exception {
        String id = emrCluster(GROUPS);
        return new Target("elasticmapreduce:terminateCluster", "EMR.AccessDeniedException", emrClusterArn(id),
                Map.of("ClusterId", id),
                () -> assertTrue(describeCluster(id).path("Status").path("State").asText().startsWith("TERMINAT")),
                () -> assertEquals("WAITING", describeCluster(id).path("Status").path("State").asText()),
                "elasticmapreduce:TerminateJobFlows");
    }

    private Target addStepTarget() throws Exception {
        String id = emrCluster(GROUPS);
        return new Target("elasticmapreduce:addStep", "EMR.AccessDeniedException", emrClusterArn(id),
                Map.of("ClusterId", id, "Step", STEP),
                () -> assertEquals(1, steps(id).size()),
                () -> assertEquals(0, steps(id).size()), "elasticmapreduce:AddJobFlowSteps");
    }

    private Target cancelStepTarget() throws Exception {
        String id = emrCluster(GROUPS);
        Response added = emr("AddJobFlowSteps", Map.of("JobFlowId", id, "Steps", List.of(STEP)));
        added.then().statusCode(200);
        String stepId = added.jsonPath().getString("StepIds[0]");
        return new Target("elasticmapreduce:cancelStep", "EMR.AccessDeniedException", emrClusterArn(id),
                Map.of("ClusterId", id, "StepId", stepId), NOTHING_TO_READ, NOTHING_TO_READ,
                "elasticmapreduce:CancelSteps");
    }

    private Target fleetTarget() throws Exception {
        String id = emrCluster(FLEETS);
        return new Target("elasticmapreduce:modifyInstanceFleetByName", "EMR.AccessDeniedException", emrClusterArn(id),
                Map.of("ClusterId", id, "InstanceFleetName", "Core", "InstanceFleet", Map.of("TargetOnDemandCapacity", 5)),
                () -> assertEquals(5, fleetCapacity(id)), () -> assertEquals(2, fleetCapacity(id)),
                "elasticmapreduce:ListInstanceFleets", "elasticmapreduce:ModifyInstanceFleet");
    }

    private Target groupTarget() throws Exception {
        String id = emrCluster(GROUPS);
        return new Target("elasticmapreduce:modifyInstanceGroupByName", "EMR.AccessDeniedException", emrClusterArn(id),
                Map.of("ClusterId", id, "InstanceGroupName", "Core", "InstanceGroup", Map.of("InstanceCount", 4)),
                () -> assertEquals(4, groupCount(id)), () -> assertEquals(2, groupCount(id)),
                "elasticmapreduce:ListInstanceGroups", "elasticmapreduce:ModifyInstanceGroups");
    }

    private Target createApplicationTarget() {
        String name = "sfn-jobs-" + UUID.randomUUID();
        cleanup.register(() -> {
            for (String id : applicationIdsNamed(name)) {
                removeApplication(id);
            }
        });
        return new Target("emr-serverless:createApplication", "EmrServerless.AccessDeniedException", ANY_RESOURCE,
                Map.of("Name", name, "ReleaseLabel", "emr-7.5.0", "Type", "SPARK"),
                () -> assertEquals(1, applicationIdsNamed(name).size()),
                () -> assertEquals(List.of(), applicationIdsNamed(name)), "emr-serverless:CreateApplication");
    }

    private Target startApplicationTarget() throws Exception {
        String id = application();
        return new Target("emr-serverless:startApplication", "EmrServerless.AccessDeniedException", applicationArn(id),
                Map.of("ApplicationId", id),
                () -> assertTrue(applicationState(id).startsWith("START"), applicationState(id)),
                () -> assertEquals("CREATED", applicationState(id)), "emr-serverless:StartApplication");
    }

    private Target stopApplicationTarget() throws Exception {
        String id = application();
        emrServerless().post("/applications/" + id + "/start").then().statusCode(200);
        awaiting("application " + id + " started").until(() -> "STARTED".equals(applicationState(id)));
        return new Target("emr-serverless:stopApplication", "EmrServerless.AccessDeniedException", applicationArn(id),
                Map.of("ApplicationId", id),
                () -> assertTrue(applicationState(id).startsWith("STOP"), applicationState(id)),
                () -> assertEquals("STARTED", applicationState(id)), "emr-serverless:StopApplication");
    }

    private Target deleteApplicationTarget() throws Exception {
        String id = application();
        return new Target("emr-serverless:deleteApplication", "EmrServerless.AccessDeniedException", applicationArn(id),
                Map.of("ApplicationId", id),
                () -> assertEquals("TERMINATED", applicationState(id)),
                () -> assertEquals("CREATED", applicationState(id)), "emr-serverless:DeleteApplication");
    }

    private Target startJobRunTarget() throws Exception {
        String id = application();
        return new Target("emr-serverless:startJobRun", "EmrServerless.AccessDeniedException", applicationArn(id),
                Map.of("ApplicationId", id, "ExecutionRoleArn", jobRole(), "JobDriver", JOB_DRIVER),
                () -> assertEquals(1, jobRuns(id).size()),
                () -> assertEquals(0, jobRuns(id).size()), "emr-serverless:StartJobRun");
    }

    private Target cancelJobRunTarget() throws Exception {
        String id = application();
        Response started = emrServerless().body(MAPPER.writeValueAsString(Map.of("clientToken", UUID.randomUUID().toString(),
                        "mode", "STREAMING", "executionRoleArn", jobRole(),
                        "jobDriver", Map.of("sparkSubmit", Map.of("entryPoint", "s3://bucket/jobs/app.py")))))
                .post("/applications/" + id + "/jobruns");
        started.then().statusCode(200);
        String run = started.jsonPath().getString("jobRunId");
        return new Target("emr-serverless:cancelJobRun", "EmrServerless.AccessDeniedException",
                applicationArn(id) + "/jobruns/" + run, Map.of("ApplicationId", id, "JobRunId", run),
                () -> assertTrue(jobRunState(id, run).startsWith("CANCEL"), jobRunState(id, run)),
                () -> assertFalse(jobRunState(id, run).startsWith("CANCEL"), jobRunState(id, run)),
                "emr-serverless:CancelJobRun");
    }

    private String family() throws Exception {
        String family = "sfn-jobs-" + UUID.randomUUID();
        registerRevision(family);
        cleanup.register(() -> {
            for (int revision : List.of(2, 1)) {
                String reference = family + ":" + revision;
                if (ecs("DeregisterTaskDefinition", Map.of("taskDefinition", reference)).statusCode() == 200) {
                    ecs("DeleteTaskDefinitions", Map.of("taskDefinitions", List.of(reference))).then().statusCode(200);
                }
            }
        });
        return family;
    }

    private void registerRevision(String family) {
        ecs("RegisterTaskDefinition", Map.of("family", family, "containerDefinitions",
                List.of(Map.of("name", "runner", "image", "floci-test/runner:latest", "memory", 128))))
                .then().statusCode(200);
    }

    private static String taskDefinitionArn(String family, int revision) {
        return "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":task-definition/" + family + ":" + revision;
    }

    private String ecsCluster() {
        String name = "sfn-jobs-" + UUID.randomUUID();
        ecs("CreateCluster", Map.of("clusterName", name)).then().statusCode(200);
        cleanup.register(() -> {
            for (String task : runningTasks(name)) {
                ecs("StopTask", Map.of("cluster", name, "task", task)).then().statusCode(200);
            }
            ecs("DeleteCluster", Map.of("cluster", name)).then().statusCode(200);
        });
        return name;
    }

    private String ecsService(String cluster, String name, String family) {
        Response created = ecs("CreateService", Map.of("cluster", cluster, "serviceName", name,
                "taskDefinition", family, "desiredCount", 0));
        created.then().statusCode(200);
        cleanup.register(() -> ecs("DeleteService", Map.of("cluster", cluster, "service", name, "force", true))
                .then().statusCode(200));
        return created.jsonPath().getString("service.serviceArn");
    }

    private static String serviceArn(String cluster, String service) {
        return "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":service/" + cluster + "/" + service;
    }

    private List<String> runningTasks(String cluster) {
        Response listed = ecs("ListTasks", Map.of("cluster", cluster));
        listed.then().statusCode(200);
        return listed.jsonPath().getList("taskArns");
    }

    private String awaitRunningTask(String cluster) throws Exception {
        awaiting("a task in " + cluster).until(() -> !runningTasks(cluster).isEmpty());
        return runningTasks(cluster).getFirst();
    }

    private int desiredCount(String cluster, String service) {
        Response described = ecs("DescribeServices", Map.of("cluster", cluster, "services", List.of(service)));
        described.then().statusCode(200);
        return described.jsonPath().getInt("services[0].desiredCount");
    }

    private Response ecs(String action, Map<String, ?> input) {
        return sfn().json(ACCOUNT, "ecs", "AmazonEC2ContainerServiceV20141113." + action, "application/x-amz-json-1.1", input);
    }

    private String emrCluster(Map<String, Object> instances) throws Exception {
        Response created = emr("RunJobFlow", Map.of("Name", "sfn-jobs-" + UUID.randomUUID(),
                "ReleaseLabel", "emr-7.5.0", "Instances", instances));
        created.then().statusCode(200);
        String id = created.jsonPath().getString("JobFlowId");
        cleanup.register(() -> {
            emr("SetTerminationProtection", Map.of("JobFlowIds", List.of(id), "TerminationProtected", false));
            emr("TerminateJobFlows", Map.of("JobFlowIds", List.of(id))).then().statusCode(200);
        });
        awaiting("cluster " + id).until(() -> "WAITING".equals(describeCluster(id).path("Status").path("State").asText()));
        return id;
    }

    private static String emrClusterArn(String id) {
        return "arn:aws:elasticmapreduce:" + REGION + ":" + ACCOUNT + ":cluster/" + id;
    }

    private JsonNode describeCluster(String id) throws Exception {
        Response described = emr("DescribeCluster", Map.of("ClusterId", id));
        described.then().statusCode(200);
        return MAPPER.readTree(described.asString()).path("Cluster");
    }

    private List<String> clusterIdsNamed(String name) {
        Response listed = emr("ListClusters", Map.of());
        listed.then().statusCode(200);
        return listed.jsonPath().getList("Clusters.findAll { it.Name == '" + name + "' }.Id");
    }

    private JsonNode steps(String id) throws Exception {
        Response listed = emr("ListSteps", Map.of("ClusterId", id));
        listed.then().statusCode(200);
        return MAPPER.readTree(listed.asString()).path("Steps");
    }

    private int fleetCapacity(String id) {
        return emr("ListInstanceFleets", Map.of("ClusterId", id)).jsonPath()
                .getInt("InstanceFleets.find { it.Name == 'Core' }.TargetOnDemandCapacity");
    }

    private int groupCount(String id) {
        return emr("ListInstanceGroups", Map.of("ClusterId", id)).jsonPath()
                .getInt("InstanceGroups.find { it.Name == 'Core' }.RequestedInstanceCount");
    }

    private Response emr(String action, Map<String, ?> input) {
        return sfn().json(ACCOUNT, "elasticmapreduce", "ElasticMapReduce." + action, "application/x-amz-json-1.1", input);
    }

    private String application() throws Exception {
        Response created = emrServerless().body(MAPPER.writeValueAsString(Map.of(
                        "name", "sfn-jobs-" + UUID.randomUUID(), "releaseLabel", "emr-7.5.0", "type", "SPARK",
                        "clientToken", UUID.randomUUID().toString())))
                .post("/applications");
        created.then().statusCode(200);
        String id = created.jsonPath().getString("applicationId");
        cleanup.register(() -> removeApplication(id));
        awaiting("application " + id).until(() -> "CREATED".equals(applicationState(id)));
        return id;
    }

    private void removeApplication(String id) {
        if ("TERMINATED".equals(applicationState(id))) {
            return;
        }
        for (String run : emrServerless().get("/applications/" + id + "/jobruns").jsonPath()
                .<String>getList("jobRuns.id")) {
            emrServerless().delete("/applications/" + id + "/jobruns/" + run);
        }
        emrServerless().post("/applications/" + id + "/stop");
        awaiting("application " + id + " stopped")
                .until(() -> !applicationState(id).startsWith("START") && !"STOPPING".equals(applicationState(id)));
        emrServerless().delete("/applications/" + id).then().statusCode(200);
    }

    private static String applicationArn(String id) {
        return "arn:aws:emr-serverless:" + REGION + ":" + ACCOUNT + ":/applications/" + id;
    }

    private String applicationState(String id) {
        return emrServerless().get("/applications/" + id).jsonPath().getString("application.state");
    }

    private List<String> applicationIdsNamed(String name) {
        Response listed = emrServerless().get("/applications");
        listed.then().statusCode(200);
        return listed.jsonPath().getList("applications.findAll { it.name == '" + name + "' }.id");
    }

    private List<String> jobRuns(String application) {
        Response listed = emrServerless().get("/applications/" + application + "/jobruns");
        listed.then().statusCode(200);
        return listed.jsonPath().getList("jobRuns.id");
    }

    private String jobRunState(String application, String run) {
        return emrServerless().get("/applications/" + application + "/jobruns/" + run).jsonPath()
                .getString("jobRun.state");
    }

    private String jobRole() {
        return role(EMR_SERVERLESS_TRUST, READ_ONLY);
    }

    private RequestSpecification emrServerless() {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "emr-serverless"))
                .contentType("application/json");
    }


    private static Map<String, Object> task(String resource, Map<String, ?> arguments) {
        return Map.of("Type", "Task", "Resource", "arn:aws:states:::" + resource, "Parameters", arguments, "End", true);
    }

    private Map<String, Object> jsonPathTask(Target target, String taskRole) {
        Map<String, Object> task = new LinkedHashMap<>(task(target.integration(), target.arguments()));
        if (taskRole != null) {
            task.put("Credentials", Map.of("RoleArn", taskRole));
        }
        return task;
    }

    private Map<String, Object> jsonataTask(Target target, String roleExpression) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task", "QueryLanguage", "JSONata",
                "Resource", "arn:aws:states:::" + target.integration(),
                "Arguments", target.arguments(), "End", true));
        if (roleExpression != null) {
            task.put("Credentials", Map.of("RoleArn", roleExpression));
        }
        return task;
    }

    private static String allow(String resource, String... actions) {
        return policy(List.of(Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", List.of(resource))));
    }

    private static String policy(List<Map<String, Object>> statements) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    private String role(String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/",
                "sfn-jobs-" + UUID.randomUUID(), trust, policy);
    }

    private String machine(Map<String, Object> task, String role) throws Exception {
        return machineWithDefinition(MAPPER.writeValueAsString(
                Map.of("StartAt", "Send", "States", Map.of("Send", task))), role);
    }

    private String machineWithDefinition(String definition, String role) {
        return sfn().createMachine(ACCOUNT, "sfn-jobs-iam-", definition, "STANDARD", role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return terminal(execution, 20);
    }

    private Response terminal(String execution, int seconds) throws Exception {
        return sfn().terminal(ACCOUNT, execution, seconds);
    }

    private List<JsonNode> eventsOfType(String execution, String type) throws Exception {
        return sfn().eventsOfType(ACCOUNT, execution, type);
    }

    private Response request(String action, Map<String, ?> input) {
        return sfn().request(ACCOUNT, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }

    /** Polls for {@code what} as long and as often as the task tests allow a background start. */
    private static ConditionFactory awaiting(String what) {
        return await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50));
    }

}
