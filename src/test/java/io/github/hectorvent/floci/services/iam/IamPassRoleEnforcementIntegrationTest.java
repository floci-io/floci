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
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that passes a role to a service also needs {@code iam:PassRole} on that role, with
 * {@code iam:PassedToService} naming the service, as the IAM user guide requires: "To pass a role
 * (and its permissions) to an AWS service, a user must have permissions to pass the role to the
 * service." Without it a user allowed only to create functions, launch instances or run tasks could
 * hand them any role in the account and act with its permissions.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamPassRoleEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    private static final String IMAGE = "ami-0abcdef1234567890";
    private static final String ECS_TASKS = "ecs-tasks.amazonaws.com";
    private static final String ECS = "ecs.amazonaws.com";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:" + ACCOUNT + ":passrole-queue";
    private static final String LAMBDA = "lambda.amazonaws.com";
    private static final String SCHEDULER = "scheduler.amazonaws.com";
    private static final String PIPES = "pipes.amazonaws.com";
    private static final String AUTOSCALING = "autoscaling.amazonaws.com";
    private static final String REDSHIFT = "redshift.amazonaws.com";
    private static final String REDSHIFT_SERVERLESS = "redshift-serverless.amazonaws.com";
    private static final String RDS = "rds.amazonaws.com";
    private static final String DMS = "dms.amazonaws.com";
    private static final String SSM = "ssm.amazonaws.com";
    private static final String SERVICE_CATALOG = "servicecatalog.amazonaws.com";
    private static final String SERVICE_LINKED_ROLES =
            "arn:aws:iam::" + ACCOUNT + ":role/aws-service-role/autoscaling.amazonaws.com/";
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
    void creatingAFunctionNeedsPassRoleOnItsExecutionRoleForLambda() {
        String role = role("fn-exec");
        User user = user("passrole-create-fn");
        String name = "passrole-fn-" + suffix();
        String body = """
                {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s"}"""
                .formatted(name, role);

        user.policy(allow("lambda:CreateFunction", "*"));
        denied(user.lambda().body(body).post("/2015-03-31/functions").then());

        // The permission names the service the role is passed to: one for EC2 does not cover Lambda.
        user.policy(allow("lambda:CreateFunction", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        denied(user.lambda().body(body).post("/2015-03-31/functions").then());

        user.policy(allow("lambda:CreateFunction", "*") + "," + passRole(role, "lambda.amazonaws.com"));
        user.lambda().body(body).post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
    }

    @Test
    void updatingAFunctionNeedsPassRoleOnlyWhenItSetsTheRole() {
        String first = role("fn-first");
        String second = role("fn-second");
        String name = "passrole-update-" + suffix();
        admin("lambda").contentType("application/json")
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s"}"""
                        .formatted(name, first))
                .post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
        User user = user("passrole-update-fn");
        String configuration = "/2015-03-31/functions/" + name + "/configuration";

        user.policy(allow("lambda:UpdateFunctionConfiguration", "*"));
        user.lambda().body("{\"Timeout\":10}").put(configuration).then().statusCode(200);
        denied(user.lambda().body("{\"Role\":\"" + second + "\"}").put(configuration).then());

        user.policy(allow("lambda:UpdateFunctionConfiguration", "*") + ","
                + passRole(second, "lambda.amazonaws.com"));
        user.lambda().body("{\"Role\":\"" + second + "\"}").put(configuration).then().statusCode(200);
    }

    @Test
    void aWebFunctionNeedsPassRoleOnItsExecutionRole() {
        String role = role("web-exec");
        User user = user("passrole-web-fn");
        String name = "passrole-web-" + suffix();
        String function = """
                {"functionName":"%s","revisionConfig":{"serviceConfig":{"executionRoleArn":"%s"}}}"""
                .formatted(name, role);
        String revision = "{\"serviceConfig\":{\"executionRoleArn\":\"" + role + "\"}}";

        user.policy(allow("lambda:CreateWebFunction", "*") + "," + allow("lambda:CreateWebFunctionRevision", "*"));
        denied(user.lambda().body(function).put("/2025-03-07/web-functions").then());
        denied(user.lambda().body(revision).post("/2025-03-07/web-functions/" + name + "/revisions").then());

        // With the permission the request reaches Lambda, which answers it on its own terms.
        user.policy(allow("lambda:CreateWebFunction", "*") + "," + allow("lambda:CreateWebFunctionRevision", "*")
                + "," + passRole(role, "lambda.amazonaws.com"));
        user.lambda().body(function).put("/2025-03-07/web-functions").then().statusCode(not(403));
        user.lambda().body(revision).post("/2025-03-07/web-functions/" + name + "/revisions").then()
                .statusCode(not(403));
        cleanup.register(() -> admin("lambda").delete("/2025-03-07/web-functions/{name}", name));
    }

    @Test
    void anInstanceProfileNeedsPassRoleOnItsRoleWhenAnInstanceIsLaunchedOrAssociated() {
        String role = role("ec2-instance");
        String roleName = role.substring(role.lastIndexOf('/') + 1);
        String profile = instanceProfile(roleName);
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profile;
        User user = user("passrole-ec2");

        user.policy(allow("ec2:RunInstances", "*") + "," + allow("ec2:AssociateIamInstanceProfile", "*"));
        denied(user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", profile).post("/").then());
        String instanceId = user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1").post("/").then().statusCode(200)
                .extract().xmlPath().getString("RunInstancesResponse.instancesSet.item.instanceId");
        terminateLater(instanceId);
        denied(user.ec2("AssociateIamInstanceProfile").formParam("InstanceId", instanceId)
                .formParam("IamInstanceProfile.Arn", profileArn).post("/").then());
        // A profile that does not exist passes no role; EC2 refuses it as it would without enforcement.
        user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", "no-such-profile").post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"));

        user.policy(allow("ec2:RunInstances", "*") + "," + allow("ec2:AssociateIamInstanceProfile", "*") + ","
                + passRole(role, "ec2.amazonaws.com"));
        terminateLater(user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", profile).post("/").then().statusCode(200)
                .extract().xmlPath().getString("RunInstancesResponse.instancesSet.item.instanceId"));
        String associationId = user.ec2("AssociateIamInstanceProfile").formParam("InstanceId", instanceId)
                .formParam("IamInstanceProfile.Arn", profileArn).post("/").then().statusCode(200)
                .extract().xmlPath().getString(
                        "AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.associationId");

        // Replacing the association passes the new profile's role.
        String other = role("ec2-replacement");
        String otherProfile = instanceProfile(other.substring(other.lastIndexOf('/') + 1));
        user.policy(allow("ec2:ReplaceIamInstanceProfileAssociation", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        denied(user.ec2("ReplaceIamInstanceProfileAssociation").formParam("AssociationId", associationId)
                .formParam("IamInstanceProfile.Name", otherProfile).post("/").then());
        user.policy(allow("ec2:ReplaceIamInstanceProfileAssociation", "*") + "," + passRole(other, "ec2.amazonaws.com"));
        user.ec2("ReplaceIamInstanceProfileAssociation").formParam("AssociationId", associationId)
                .formParam("IamInstanceProfile.Name", otherProfile).post("/").then().statusCode(200);
    }

    @Test
    void addingARoleToAnInstanceProfileNeedsPassRoleOnItForEc2() {
        String role = role("/app/", "profile-role");
        String roleName = role.substring(role.lastIndexOf('/') + 1);
        String profile = emptyInstanceProfile();
        User user = user("passrole-profile-role");
        String add = allow("iam:AddRoleToInstanceProfile", "*");

        // A grant to pass the role to Lambda does not cover EC2.
        user.policy(add + "," + passRole(role, LAMBDA));
        denied(addRoleToInstanceProfile(user, profile, roleName));
        // The request names the role without its path; a grant on the ARN that name gives misses it.
        user.policy(add + "," + passRole("arn:aws:iam::" + ACCOUNT + ":role/" + roleName, "ec2.amazonaws.com"));
        denied(addRoleToInstanceProfile(user, profile, roleName));
        // A role IAM does not hold is checked as the one its name gives.
        user.policy(add + "," + passRole(role, "ec2.amazonaws.com"));
        denied(addRoleToInstanceProfile(user, profile, "no-such-role"));

        addRoleToInstanceProfile(user, profile, roleName).statusCode(200);
    }

    @Test
    void aSpotInstanceRequestNeedsPassRoleOnItsInstanceProfilesRole() {
        String role = role("ec2-spot");
        String profile = instanceProfile(role.substring(role.lastIndexOf('/') + 1));
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profile;
        User user = user("passrole-spot");

        user.policy(allow("ec2:RequestSpotInstances", "*"));
        denied(spotRequest(user, "Arn", profileArn).then());
        denied(spotRequest(user, "Name", profile).then());

        user.policy(allow("ec2:RequestSpotInstances", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        assertEquals(profileArn, spotProfileArn(user, "Arn", profileArn));
        // A profile named rather than given by ARN is the same profile, and the instance gets it.
        assertEquals(profileArn, spotProfileArn(user, "Name", profile));
    }

    @Test
    void aLaunchTemplateWithAnInstanceProfileNeedsPassRoleOnItsRole() {
        String role = role("ec2-template");
        String profile = instanceProfile(role.substring(role.lastIndexOf('/') + 1));
        User user = user("passrole-template");
        String name = "passrole-template-" + suffix();

        user.policy(allow("ec2:CreateLaunchTemplate", "*"));
        denied(user.ec2("CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then());

        user.policy(allow("ec2:CreateLaunchTemplate", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.ec2("CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then().statusCode(200);
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteLaunchTemplate")
                .formParam("LaunchTemplateName", name).post("/"));

        // A new version of the template passes its profile's role too.
        user.policy(allow("ec2:CreateLaunchTemplateVersion", "*"));
        denied(user.ec2("CreateLaunchTemplateVersion").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then());
        user.policy(allow("ec2:CreateLaunchTemplateVersion", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.ec2("CreateLaunchTemplateVersion").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then().statusCode(200);
    }

    @Test
    void aFlowLogNeedsPassRoleOnItsDeliveryRoleForFlowLogs() {
        String role = role("flow-logs");
        String vpcId = admin("ec2").formParam("Action", "DescribeVpcs").post("/").then().statusCode(200)
                .extract().xmlPath().getString("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        User user = user("passrole-flow-logs");

        user.policy(allow("ec2:CreateFlowLogs", "*"));
        denied(flowLog(user, vpcId, role).then());

        user.policy(allow("ec2:CreateFlowLogs", "*") + "," + passRole(role, "vpc-flow-logs.amazonaws.com"));
        String flowLogId = flowLog(user, vpcId, role).then().statusCode(200)
                .extract().xmlPath().getString("CreateFlowLogsResponse.flowLogIdSet.item");
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteFlowLogs")
                .formParam("FlowLogId.1", flowLogId).post("/"));
    }

    @Test
    void aTaskDefinitionNeedsPassRoleOnItsTaskAndExecutionRolesForEcsTasks() {
        String taskRole = role("ecs-task");
        String executionRole = role("ecs-execution");
        User user = user("passrole-ecs-register");
        String family = "passrole-family-" + suffix();
        String body = taskDefinition(family, taskRole, executionRole);

        user.policy(allow("ecs:RegisterTaskDefinition", "*") + "," + passRole(taskRole, ECS_TASKS));
        deniedJson(user.ecs("RegisterTaskDefinition").body(body).post("/").then());

        // Task roles go to ecs-tasks.amazonaws.com: a grant for ecs.amazonaws.com does not cover them.
        user.policy(allow("ecs:RegisterTaskDefinition", "*") + "," + passRole(taskRole, ECS_TASKS) + ","
                + passRole(executionRole, ECS));
        deniedJson(user.ecs("RegisterTaskDefinition").body(body).post("/").then());

        user.policy(allow("ecs:RegisterTaskDefinition", "*") + "," + passRole(taskRole, ECS_TASKS) + ","
                + passRole(executionRole, ECS_TASKS));
        user.ecs("RegisterTaskDefinition").body(body).post("/").then().statusCode(200);
        deregisterLater(family + ":1");

        // A task role given by its name is the role of that name in the caller's account.
        String named = taskDefinition(family, roleName(taskRole), null);
        user.policy(allow("ecs:RegisterTaskDefinition", "*"));
        deniedJson(user.ecs("RegisterTaskDefinition").body(named).post("/").then());
        user.policy(allow("ecs:RegisterTaskDefinition", "*") + "," + passRole(taskRole, ECS_TASKS));
        user.ecs("RegisterTaskDefinition").body(named).post("/").then().statusCode(200);
        deregisterLater(family + ":2");
    }

    @Test
    void runningATaskNeedsPassRoleOnItsTaskDefinitionsRolesAndOnAnyOverride() {
        String taskRole = role("ecs-run");
        String override = role("ecs-override");
        String family = registerTaskDefinition(taskRole);
        String cluster = cluster();
        User user = user("passrole-ecs-run");

        // The caller names no role: the one it passes is the task definition's.
        user.policy(allow("ecs:RunTask", "*"));
        deniedJson(user.ecs("RunTask").body(runTask(cluster, family, null)).post("/").then());

        user.policy(allow("ecs:RunTask", "*") + "," + passRole(taskRole, ECS_TASKS));
        stopLater(cluster, user.ecs("RunTask").body(runTask(cluster, family, null)).post("/").then()
                .statusCode(200).extract().path("tasks[0].taskArn"));

        // An override is passed as well.
        deniedJson(user.ecs("RunTask").body(runTask(cluster, family, override)).post("/").then());
        user.policy(allow("ecs:RunTask", "*") + "," + passRole(taskRole, ECS_TASKS) + ","
                + passRole(override, ECS_TASKS));
        stopLater(cluster, user.ecs("RunTask").body(runTask(cluster, family, override)).post("/").then()
                .statusCode(200).extract().path("tasks[0].taskArn"));
    }

    @Test
    void aServiceNeedsPassRoleOnItsTaskDefinitionsRolesAndOnItsInfrastructureRoles() {
        String taskRole = role("ecs-service");
        String nextRole = role("ecs-service-next");
        String infrastructure = role("ecs-infrastructure");
        String family = registerTaskDefinition(taskRole);
        String next = registerTaskDefinition(nextRole);
        String cluster = cluster();
        String name = "passrole-svc-" + suffix();
        User user = user("passrole-ecs-service");

        user.policy(allow("ecs:CreateService", "*"));
        deniedJson(user.ecs("CreateService").body(service(cluster, name, family)).post("/").then());
        user.policy(allow("ecs:CreateService", "*") + "," + passRole(taskRole, ECS_TASKS));
        user.ecs("CreateService").body(service(cluster, name, family)).post("/").then().statusCode(200);
        cleanup.register(() -> admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DeleteService")
                .body("{\"cluster\":\"" + cluster + "\",\"service\":\"" + name + "\",\"force\":true}").post("/"));

        // Moving the service to another task definition passes that definition's roles.
        String update = "{\"cluster\":\"" + cluster + "\",\"service\":\"" + name + "\",\"taskDefinition\":\""
                + next + "\"}";
        user.policy(allow("ecs:UpdateService", "*") + "," + passRole(taskRole, ECS_TASKS));
        deniedJson(user.ecs("UpdateService").body(update).post("/").then());
        user.policy(allow("ecs:UpdateService", "*") + "," + passRole(nextRole, ECS_TASKS));
        user.ecs("UpdateService").body(update).post("/").then().statusCode(200);

        // An infrastructure role, here an EBS volume's, is passed to ECS itself.
        String volumes = "{\"cluster\":\"" + cluster + "\",\"service\":\"" + name + "\",\"volumeConfigurations\":"
                + "[{\"name\":\"data\",\"managedEBSVolume\":{\"roleArn\":\"" + infrastructure + "\"}}]}";
        user.policy(allow("ecs:UpdateService", "*") + "," + passRole(infrastructure, ECS_TASKS));
        deniedJson(user.ecs("UpdateService").body(volumes).post("/").then());
        user.policy(allow("ecs:UpdateService", "*") + "," + passRole(infrastructure, ECS));
        // ECS answers the volume on its own terms; a JSON-protocol denial would be a 400 naming the action.
        user.ecs("UpdateService").body(volumes).post("/").then().body(not(containsString("iam:PassRole")));
    }

    @Test
    void aStateMachineNeedsPassRoleOnItsRoleForStepFunctions() throws IOException {
        String role = role("states");
        String other = role("states-other");
        User user = user("passrole-states");
        String body = """
                {"name":"passrole-sm-%s","roleArn":"%s",
                 "definition":"{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"End\\":true}}}"}"""
                .formatted(suffix(), role);

        user.policy(allow("states:CreateStateMachine", "*"));
        deniedJson(user.states("CreateStateMachine").body(body).post("/").then());
        // A Smithy RPC v2 CBOR call names the role in the same member.
        byte[] cbor = CBOR_MAPPER.writeValueAsBytes(new ObjectMapper().readTree(body));
        assertPassRoleDenied(user.cbor("states").body(cbor)
                .post("/service/AWSStepFunctions/operation/CreateStateMachine"));
        // A gzip-encoded body, which the CBOR endpoint decompresses before it runs the request.
        assertPassRoleDenied(user.cbor("states").header("Content-Encoding", "gzip").body(gzip(cbor))
                .post("/service/AWSStepFunctions/operation/CreateStateMachine"));

        user.policy(allow("states:CreateStateMachine", "*") + "," + passRole(role, "states.amazonaws.com"));
        String arn = user.states("CreateStateMachine").body(body).post("/").then()
                .statusCode(200).extract().path("stateMachineArn");
        cleanup.register(() -> admin("states").contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                .body("{\"stateMachineArn\":\"" + arn + "\"}").post("/"));

        String update = "{\"stateMachineArn\":\"" + arn + "\",\"roleArn\":\"" + other + "\"}";
        user.policy(allow("states:UpdateStateMachine", "*") + "," + passRole(role, "states.amazonaws.com"));
        deniedJson(user.states("UpdateStateMachine").body(update).post("/").then());
        user.policy(allow("states:UpdateStateMachine", "*") + "," + passRole(other, "states.amazonaws.com"));
        user.states("UpdateStateMachine").body(update).post("/").then().statusCode(200);
    }

    @Test
    void anEventBridgeRuleAndEachOfItsTargetsNeedPassRoleOnTheirRoles() {
        String role = role("events");
        String targetRole = role("events-target");
        User user = user("passrole-events");
        String rule = "passrole-rule-" + suffix();
        String putRule = "{\"Name\":\"" + rule + "\",\"ScheduleExpression\":\"rate(1 hour)\",\"RoleArn\":\""
                + role + "\"}";
        String rules = allow("events:PutRule", "*") + "," + allow("events:PutTargets", "*");

        user.policy(rules);
        deniedJson(user.json("events", "AWSEvents.PutRule").body(putRule).post("/").then());
        user.policy(rules + "," + passRole(role, "events.amazonaws.com"));
        user.json("events", "AWSEvents.PutRule").body(putRule).post("/").then().statusCode(200);
        cleanup.register(() -> admin("events").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.DeleteRule").body("{\"Name\":\"" + rule + "\"}").post("/"));
        cleanup.register(() -> admin("events").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.RemoveTargets")
                .body("{\"Rule\":\"" + rule + "\",\"Ids\":[\"1\",\"2\"]}").post("/"));

        // The second target names a role, the first does not.
        String putTargets = "{\"Rule\":\"" + rule + "\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\"" + QUEUE_ARN + "\"},"
                + "{\"Id\":\"2\",\"Arn\":\"" + QUEUE_ARN + "\",\"RoleArn\":\"" + targetRole + "\"}]}";
        deniedJson(user.json("events", "AWSEvents.PutTargets").body(putTargets).post("/").then());
        user.policy(rules + "," + passRole(targetRole, "events.amazonaws.com"));
        user.json("events", "AWSEvents.PutTargets").body(putTargets).post("/").then().statusCode(200);
    }

    @Test
    void aScheduleNeedsTheSchedulerActionOnItsArnAndPassRoleOnItsTargetsRole() {
        String role = role("scheduler");
        String other = role("scheduler-other");
        User user = user("passrole-scheduler");
        String name = "passrole-schedule-" + suffix();
        String path = "/schedules/" + name;
        String arn = "arn:aws:scheduler:us-east-1:" + ACCOUNT + ":schedule/default/" + name;

        // The route is checked as scheduler:CreateSchedule, on the schedule's ARN.
        user.policy(allow("scheduler:CreateSchedule", arn + "-other") + "," + passRole(role, SCHEDULER));
        refused(user.rest("scheduler").body(schedule(role)).post(path).then(), "scheduler:CreateSchedule");
        user.policy(allow("scheduler:CreateSchedule", arn));
        denied(user.rest("scheduler").body(schedule(role)).post(path).then());
        user.policy(allow("scheduler:CreateSchedule", arn) + "," + passRole(role, SCHEDULER));
        user.rest("scheduler").body(schedule(role)).post(path).then().statusCode(200);
        cleanup.register(() -> admin("scheduler").delete(path));

        user.policy(allow("scheduler:UpdateSchedule", arn) + "," + passRole(role, SCHEDULER));
        denied(user.rest("scheduler").body(schedule(other)).put(path).then());
        user.policy(allow("scheduler:UpdateSchedule", arn) + "," + passRole(other, SCHEDULER));
        user.rest("scheduler").body(schedule(other)).put(path).then().statusCode(200);
    }

    @Test
    void aPipeNeedsThePipesActionOnItsArnAndPassRoleOnItsRole() {
        String role = role("pipes");
        String other = role("pipes-other");
        User user = user("passrole-pipes");
        String name = "passrole-pipe-" + suffix();
        String path = "/v1/pipes/" + name;
        String arn = "arn:aws:pipes:us-east-1:" + ACCOUNT + ":pipe/" + name;
        String create = "{\"Source\":\"" + QUEUE_ARN + "\",\"Target\":\"" + QUEUE_ARN + "\",\"RoleArn\":\"" + role
                + "\",\"DesiredState\":\"STOPPED\"}";

        user.policy(allow("pipes:CreatePipe", arn + "-other") + "," + passRole(role, PIPES));
        refused(user.rest("pipes").body(create).post(path).then(), "pipes:CreatePipe");
        user.policy(allow("pipes:CreatePipe", arn));
        denied(user.rest("pipes").body(create).post(path).then());
        user.policy(allow("pipes:CreatePipe", arn) + "," + passRole(role, PIPES));
        user.rest("pipes").body(create).post(path).then().statusCode(200);
        cleanup.register(() -> admin("pipes").delete(path));

        String update = "{\"RoleArn\":\"" + other + "\",\"DesiredState\":\"STOPPED\"}";
        user.policy(allow("pipes:UpdatePipe", arn) + "," + passRole(role, PIPES));
        denied(user.rest("pipes").body(update).put(path).then());
        user.policy(allow("pipes:UpdatePipe", arn) + "," + passRole(other, PIPES));
        user.rest("pipes").body(update).put(path).then().statusCode(200);
    }

    @Test
    void anEksPodIdentityAssociationNeedsPassRoleOnItsRoleForPodIdentity() {
        // Pod Identity hands the service account's pods the association role's credentials, so naming a role
        // needs iam:PassRole on it, passed to Pod Identity rather than to EKS.
        String role = role("eks-pod");
        String other = role("eks-pod-other");
        User user = user("passrole-eks-pod");
        String path = "/clusters/" + eksCluster() + "/pod-identity-associations";
        String create = "{\"namespace\":\"default\",\"serviceAccount\":\"app\",\"roleArn\":\"" + role + "\"}";
        String pods = "pods.eks.amazonaws.com";

        user.policy(allow("eks:CreatePodIdentityAssociation", "*") + "," + passRole(role, "eks.amazonaws.com"));
        denied(user.rest("eks").body(create).post(path).then());
        user.policy(allow("eks:CreatePodIdentityAssociation", "*") + "," + passRole(role, pods));
        String id = user.rest("eks").body(create).post(path).then().statusCode(200)
                .extract().path("association.associationId");

        String update = "{\"roleArn\":\"" + other + "\"}";
        user.policy(allow("eks:UpdatePodIdentityAssociation", "*") + "," + passRole(role, pods));
        denied(user.rest("eks").body(update).post(path + "/" + id).then());
        user.policy(allow("eks:UpdatePodIdentityAssociation", "*") + "," + passRole(other, pods));
        user.rest("eks").body(update).post(path + "/" + id).then().statusCode(200);
    }

    @Test
    void anEksClusterNodeGroupFargateProfileAndNodeAccessEntryNeedPassRoleOnTheirRoles() {
        String clusterRole = role("eks-cluster");
        String nodeRole = role("eks-node");
        String podRole = role("eks-fargate");
        User user = user("passrole-eks");
        String eks = "eks.amazonaws.com";
        String name = "passrole-eks-" + suffix();
        String path = "/clusters/" + name;
        String cluster = "{\"name\":\"" + name + "\",\"roleArn\":\"" + clusterRole
                + "\",\"accessConfig\":{\"authenticationMode\":\"API\"}}";

        user.policy(allow("eks:CreateCluster", "*"));
        denied(user.rest("eks").body(cluster).post("/clusters").then());
        user.policy(allow("eks:CreateCluster", "*") + "," + passRole(clusterRole, eks));
        user.rest("eks").body(cluster).post("/clusters").then().statusCode(200);
        cleanup.register(() -> admin("eks").delete(path));

        String nodegroup = "{\"nodegroupName\":\"ng\",\"subnets\":[\"subnet-abc\"],\"nodeRole\":\"" + nodeRole + "\"}";
        user.policy(allow("eks:CreateNodegroup", "*"));
        denied(user.rest("eks").body(nodegroup).post(path + "/node-groups").then());
        user.policy(allow("eks:CreateNodegroup", "*") + "," + passRole(nodeRole, eks));
        user.rest("eks").body(nodegroup).post(path + "/node-groups").then().statusCode(200);
        cleanup.register(() -> admin("eks").delete(path + "/node-groups/ng"));

        String profile = "{\"fargateProfileName\":\"fp\",\"podExecutionRoleArn\":\"" + podRole + "\"}";
        user.policy(allow("eks:CreateFargateProfile", "*"));
        denied(user.rest("eks").body(profile).post(path + "/fargate-profiles").then());
        user.policy(allow("eks:CreateFargateProfile", "*") + "," + passRole(podRole, eks));
        user.rest("eks").body(profile).post(path + "/fargate-profiles").then().statusCode(200);
        cleanup.register(() -> admin("eks").delete(path + "/fargate-profiles/fp"));

        // A node access entry passes its role to EC2, whose instances join as it; a standard one passes nothing.
        String node = "{\"principalArn\":\"" + nodeRole + "\",\"type\":\"EC2_LINUX\"}";
        user.policy(allow("eks:CreateAccessEntry", "*") + "," + passRole(nodeRole, eks));
        denied(user.rest("eks").body(node).post(path + "/access-entries").then());
        user.policy(allow("eks:CreateAccessEntry", "*") + "," + passRole(nodeRole, "ec2.amazonaws.com"));
        user.rest("eks").body(node).post(path + "/access-entries").then().statusCode(200);
        user.policy(allow("eks:CreateAccessEntry", "*"));
        user.rest("eks").body("{\"principalArn\":\"" + podRole + "\"}").post(path + "/access-entries").then()
                .statusCode(200);
    }

    /** Creates an EKS cluster as the account's admin, removed afterwards, and returns its name. */
    private String eksCluster() {
        String name = "passrole-cluster-" + suffix();
        admin("eks").contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/passrole-cluster\"}")
                .post("/clusters").then().statusCode(200);
        cleanup.register(() -> admin("eks").delete("/clusters/" + name));
        return name;
    }

    @Test
    void aMicrovmImageAndANetworkConnectorNeedPassRoleOnTheRolesTheyName() {
        String build = role("microvm-build");
        String operator = role("connector-operator");
        User user = user("passrole-microvm");
        String image = "passrole-image-" + suffix();
        String createImage = """
                {"name":"%s","baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"%s","codeArtifact":{"uri":"s3://bucket/code.zip"}}""".formatted(image, build);

        user.policy(allow("lambda:CreateMicrovmImage", "*"));
        denied(user.rest("lambda").body(createImage).post("/2025-09-09/microvm-images").then());
        // It also passes the managed egress connector an image gets when it names none.
        user.policy(allow("lambda:CreateMicrovmImage", "*") + "," + passRole(build, LAMBDA) + ","
                + allow("lambda:PassNetworkConnector", "*"));
        user.rest("lambda").body(createImage).post("/2025-09-09/microvm-images").then().statusCode(201);
        cleanup.register(() -> admin("lambda").delete("/2025-09-09/microvm-images/" + image));
        // Reading it is checked against the image's ARN.
        String imageArn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":microvm-image:" + image;
        user.policy(allow("lambda:GetMicrovmImage", imageArn + "-other"));
        refused(user.rest("lambda").get("/2025-09-09/microvm-images/" + image).then(), "lambda:GetMicrovmImage");
        user.policy(allow("lambda:GetMicrovmImage", imageArn));
        user.rest("lambda").get("/2025-09-09/microvm-images/" + image).then().statusCode(200);

        String createConnector = """
                {"Name":"passrole-connector-%1$s","ClientToken":"passrole-token-%1$s","OperatorRole":"%2$s",
                 "Configuration":{"VpcEgressConfiguration":{"AssociatedComputeResourceTypes":["MicroVm"],
                 "NetworkProtocol":"IPv4","SubnetIds":["subnet-0000000000000it01"],
                 "SecurityGroupIds":["sg-0000000000000it01"]}}}"""
                .formatted(suffix(), operator);
        user.policy(allow("lambda:CreateNetworkConnector", "*"));
        denied(user.rest("lambda").body(createConnector).post("/2026-04-04/network-connectors").then());
        user.policy(allow("lambda:CreateNetworkConnector", "*") + "," + passRole(operator, LAMBDA));
        String connector = user.rest("lambda").body(createConnector).post("/2026-04-04/network-connectors").then()
                .statusCode(202).extract().path("Id");
        cleanup.register(() -> admin("lambda").delete("/2026-04-04/network-connectors/" + connector));
    }

    @Test
    void aStackNeedsPassRoleOnAServiceRoleTheRequestNames() {
        String role = role("cloudformation");
        User user = user("passrole-stack");
        String stack = "passrole-stack-" + suffix();
        String stacks = allow("cloudformation:CreateStack", "*") + "," + allow("cloudformation:DeleteStack", "*");

        user.policy(stacks);
        denied(createStack(user, stack).formParam("RoleARN", role).post("/").then());
        user.policy(stacks + "," + passRole(role, "cloudformation.amazonaws.com"));
        createStack(user, stack).formParam("RoleARN", role).post("/").then().statusCode(200);
        cleanup.register(() -> admin("cloudformation").formParam("Action", "DeleteStack")
                .formParam("StackName", stack).post("/"));

        // A later operation that names no role reuses the stack's own without passing it.
        user.policy(stacks);
        denied(user.cfn("DeleteStack").formParam("StackName", stack).formParam("RoleARN", role).post("/").then());
        user.cfn("DeleteStack").formParam("StackName", stack).post("/").then().statusCode(200);
    }

    @Test
    void aSelfManagedStackSetPassesTheAdministrationRoleItNamesOrTheDefaultOne() {
        String defaultRole = "arn:aws:iam::" + ACCOUNT + ":role/AWSCloudFormationStackSetAdministrationRole";
        String custom = role("stackset-admin");
        User user = user("passrole-stackset");
        String name = "passrole-stackset-" + suffix();
        String stackSets = allow("cloudformation:CreateStackSet", "*") + ","
                + allow("cloudformation:UpdateStackSet", "*");

        user.policy(stackSets);
        denied(stackSet(user, "CreateStackSet", name).post("/").then());
        user.policy(stackSets + "," + passRole(defaultRole, "cloudformation.amazonaws.com"));
        stackSet(user, "CreateStackSet", name).post("/").then().statusCode(200);
        cleanup.register(() -> admin("cloudformation").formParam("Action", "DeleteStackSet")
                .formParam("StackSetName", name).post("/"));

        // An update naming no administration role passes the default one too, and one naming a role passes it.
        user.policy(stackSets);
        denied(stackSet(user, "UpdateStackSet", name).post("/").then());
        user.policy(stackSets + "," + passRole(defaultRole, "cloudformation.amazonaws.com"));
        denied(stackSet(user, "UpdateStackSet", name).formParam("AdministrationRoleARN", custom).post("/").then());
        user.policy(stackSets + "," + passRole(custom, "cloudformation.amazonaws.com"));
        stackSet(user, "UpdateStackSet", name).formParam("AdministrationRoleARN", custom).post("/").then()
                .statusCode(200);

        // A service-managed StackSet uses no administration role, so none is passed.
        user.policy(stackSets);
        cleanup.register(() -> admin("cloudformation").formParam("Action", "DeleteStackSet")
                .formParam("StackSetName", name + "-org").post("/"));
        stackSet(user, "CreateStackSet", name + "-org").formParam("PermissionModel", "SERVICE_MANAGED").post("/")
                .then().statusCode(not(403)).body(not(containsString("iam:PassRole")));
    }

    /** Requests a Spot Instance, removes it afterwards, and returns the profile ARN it was launched with. */
    @Test
    void aLaunchConfigurationNeedsPassRoleOnItsInstanceProfilesRoleForEc2() {
        String role = role("asg-config");
        String profile = instanceProfile(roleName(role));
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profile;
        User user = user("passrole-launch-config");
        String name = "passrole-config-" + suffix();

        // The role goes to the instances, so a grant to pass it to Auto Scaling does not cover it.
        user.policy(allow("autoscaling:CreateLaunchConfiguration", "*") + "," + passRole(role, AUTOSCALING));
        denied(launchConfiguration(user, name, profile).then());
        denied(launchConfiguration(user, name, profileArn).then());

        user.policy(allow("autoscaling:CreateLaunchConfiguration", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        launchConfiguration(user, name, profileArn).then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).post("/"));
    }

    @Test
    void aLaunchConfigurationMadeFromAnInstanceNeedsPassRoleOnTheRoleOfTheProfileItTakes() {
        String role = role("asg-from-instance");
        String profile = instanceProfile(roleName(role));
        String instanceId = admin("ec2").formParam("Action", "RunInstances").formParam("ImageId", IMAGE)
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", profile).post("/").then().statusCode(200)
                .extract().xmlPath().getString("RunInstancesResponse.instancesSet.item.instanceId");
        terminateLater(instanceId);
        User user = user("passrole-config-instance");
        String name = "passrole-instance-config-" + suffix();

        user.policy(allow("autoscaling:CreateLaunchConfiguration", "*"));
        denied(user.autoscaling("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("InstanceId", instanceId).post("/").then());

        user.policy(allow("autoscaling:CreateLaunchConfiguration", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.autoscaling("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("InstanceId", instanceId).post("/").then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).post("/"));
    }

    @Test
    void aGroupNeedsPassRoleOnTheRoleOfItsLaunchTemplatesInstanceProfileAtTheVersionItLaunches() {
        String role = role("asg-template");
        String templateId = launchTemplate(null, instanceProfile(roleName(role)));
        User user = user("passrole-asg");
        String group = "passrole-asg-" + suffix();
        String actions = allow("autoscaling:CreateAutoScalingGroup", "*") + ","
                + allow("autoscaling:UpdateAutoScalingGroup", "*") + ","
                + allow("autoscaling:StartInstanceRefresh", "*");

        user.policy(actions);
        denied(group(user, "CreateAutoScalingGroup", group).formParam("LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("LaunchTemplate.Version", "$Latest").post("/").then());
        String mixed = "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.";
        denied(group(user, "CreateAutoScalingGroup", group).formParam(mixed + "LaunchTemplateId", templateId)
                .formParam(mixed + "Version", "2").post("/").then());
        // Version 1 names no instance profile, so a group launching from it passes no role.
        group(user, "CreateAutoScalingGroup", group).formParam("LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("LaunchTemplate.Version", "1").post("/").then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", group).formParam("ForceDelete", "true").post("/"));

        denied(group(user, "UpdateAutoScalingGroup", group).formParam("LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("LaunchTemplate.Version", "2").post("/").then());
        denied(user.autoscaling("StartInstanceRefresh").formParam("AutoScalingGroupName", group)
                .formParam("DesiredConfiguration.LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("DesiredConfiguration.LaunchTemplate.Version", "2").post("/").then());

        user.policy(actions + "," + passRole(role, "ec2.amazonaws.com"));
        group(user, "UpdateAutoScalingGroup", group).formParam("LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("LaunchTemplate.Version", "2").post("/").then().statusCode(200);
    }

    @Test
    void anInstanceRefreshNeedsPassRoleOnTheTemplateVersionItsGroupLaunchesFromOnceItApplies() {
        String role = role("asg-refresh");
        String profile = instanceProfile(roleName(role));
        String plain = launchTemplate(null, null);
        // Version 2 names the profile; the latest, version 3, names none.
        String privileged = launchTemplate(null, profile, null);
        String group = "passrole-refresh-" + suffix();
        admin("autoscaling").formParam("Action", "CreateAutoScalingGroup").formParam("AutoScalingGroupName", group)
                .formParam("LaunchTemplate.LaunchTemplateId", plain).formParam("LaunchTemplate.Version", "2")
                .formParam("MinSize", "0").formParam("MaxSize", "0").formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", group).formParam("ForceDelete", "true").post("/"));
        User user = user("passrole-refresh");

        // A template named without a version keeps the group's version 2, not the template's latest.
        user.policy(allow("autoscaling:StartInstanceRefresh", "*"));
        denied(user.autoscaling("StartInstanceRefresh").formParam("AutoScalingGroupName", group)
                .formParam("DesiredConfiguration.LaunchTemplate.LaunchTemplateId", privileged).post("/").then());

        // A version named alone moves the group's own template to it.
        admin("autoscaling").formParam("Action", "UpdateAutoScalingGroup").formParam("AutoScalingGroupName", group)
                .formParam("LaunchTemplate.LaunchTemplateId", privileged).formParam("LaunchTemplate.Version", "1")
                .post("/").then().statusCode(200);
        denied(user.autoscaling("StartInstanceRefresh").formParam("AutoScalingGroupName", group)
                .formParam("DesiredConfiguration.LaunchTemplate.Version", "2").post("/").then());

        user.policy(allow("autoscaling:StartInstanceRefresh", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.autoscaling("StartInstanceRefresh").formParam("AutoScalingGroupName", group)
                .formParam("DesiredConfiguration.LaunchTemplate.Version", "2").post("/").then().statusCode(200);
    }

    @Test
    void aServiceLinkedRoleOtherThanTheDefaultAndALifecycleHooksRoleNeedPassRoleForAutoScaling() {
        String hookRole = role("asg-hook");
        String configuration = "passrole-plain-config-" + suffix();
        admin("autoscaling").formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", configuration).formParam("ImageId", IMAGE)
                .formParam("InstanceType", "t3.micro").post("/").then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteLaunchConfiguration")
                .formParam("LaunchConfigurationName", configuration).post("/"));
        User user = user("passrole-asg-roles");
        String group = "passrole-asg-roles-" + suffix();
        String hooks = "LifecycleHookSpecificationList.member.1.";

        user.policy(allow("autoscaling:CreateAutoScalingGroup", "*") + ","
                + allow("autoscaling:PutLifecycleHook", "*"));
        denied(group(user, "CreateAutoScalingGroup", group).formParam("LaunchConfigurationName", configuration)
                .formParam("ServiceLinkedRoleARN", SERVICE_LINKED_ROLES + "AWSServiceRoleForAutoScaling_passrole")
                .post("/").then());
        denied(group(user, "CreateAutoScalingGroup", group).formParam("LaunchConfigurationName", configuration)
                .formParam(hooks + "LifecycleHookName", "launch")
                .formParam(hooks + "LifecycleTransition", "autoscaling:EC2_INSTANCE_LAUNCHING")
                .formParam(hooks + "NotificationTargetARN", QUEUE_ARN).formParam(hooks + "RoleARN", hookRole)
                .post("/").then());
        // The default service-linked role needs no permission to pass, nor does a launch configuration's group.
        group(user, "CreateAutoScalingGroup", group).formParam("LaunchConfigurationName", configuration)
                .formParam("ServiceLinkedRoleARN", SERVICE_LINKED_ROLES + "AWSServiceRoleForAutoScaling")
                .post("/").then().statusCode(200);
        cleanup.register(() -> admin("autoscaling").formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", group).formParam("ForceDelete", "true").post("/"));

        denied(lifecycleHook(user, group, hookRole).then());
        user.policy(allow("autoscaling:PutLifecycleHook", "*") + "," + passRole(hookRole, AUTOSCALING));
        lifecycleHook(user, group, hookRole).then().statusCode(200);
    }

    @Test
    void aScalableTargetNeedsPassRoleOnTheRoleItNamesForApplicationAutoScaling() {
        String role = role("scalable-target");
        String table = "table/passrole-" + suffix();
        String target = "AnyScaleFrontendService.RegisterScalableTarget";
        String body = """
                {"ServiceNamespace":"dynamodb","ResourceId":"%s","ScalableDimension":"dynamodb:table:ReadCapacityUnits",
                 "MinCapacity":1,"MaxCapacity":5,"RoleARN":"%s"}""".formatted(table, role);
        User user = user("passrole-scaling");

        user.policy(allow("application-autoscaling:RegisterScalableTarget", "*") + "," + passRole(role, AUTOSCALING));
        deniedJson(user.json("application-autoscaling", target).body(body).post("/").then());

        user.policy(allow("application-autoscaling:RegisterScalableTarget", "*") + ","
                + passRole(role, "application-autoscaling.amazonaws.com"));
        user.json("application-autoscaling", target).body(body).post("/").then().statusCode(200);
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "application-autoscaling"))
                .header("X-Amz-Target", "AnyScaleFrontendService.DeregisterScalableTarget")
                .contentType("application/x-amz-json-1.1")
                .body("{\"ServiceNamespace\":\"dynamodb\",\"ResourceId\":\"" + table
                        + "\",\"ScalableDimension\":\"dynamodb:table:ReadCapacityUnits\"}").post("/"));
    }

    @Test
    void aRedshiftClusterNeedsPassRoleOnEachRoleItAssociatesForRedshift() {
        String role = role("redshift-copy");
        User user = user("passrole-redshift");
        String cluster = "passrole-cluster-" + suffix();
        String actions = allow("redshift:CreateCluster", "*") + "," + allow("redshift:RestoreFromClusterSnapshot", "*")
                + "," + allow("redshift:ModifyClusterIamRoles", "*");

        // A grant to pass the role to Redshift Serverless does not cover Redshift.
        user.policy(actions + "," + passRole(role, REDSHIFT_SERVERLESS));
        denied(restore(user, cluster, role).then());
        denied(user.redshift("ModifyClusterIamRoles").formParam("ClusterIdentifier", cluster)
                .formParam("AddIamRoles.member.1", role).post("/").then());
        // Refused before Redshift starts the cluster's container.
        denied(user.redshift("CreateCluster").formParam("ClusterIdentifier", cluster)
                .formParam("NodeType", "dc2.large").formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "Passw0rd1").formParam("IamRoles.IamRoleArn.1", role)
                .post("/").then());

        // With it, each reaches Redshift, which has no such snapshot or cluster.
        user.policy(actions + "," + passRole(role, REDSHIFT));
        restore(user, cluster, role).then().statusCode(404).body(containsString("ClusterSnapshotNotFound"));
        user.redshift("ModifyClusterIamRoles").formParam("ClusterIdentifier", cluster)
                .formParam("AddIamRoles.member.1", role).post("/").then()
                .statusCode(404).body(containsString("ClusterNotFound"));

        // Removing a role, or naming one only as the default, passes nothing.
        user.policy(actions);
        user.redshift("ModifyClusterIamRoles").formParam("ClusterIdentifier", cluster)
                .formParam("RemoveIamRoles.IamRoleArn.1", role).formParam("DefaultIamRoleArn", role).post("/").then()
                .statusCode(404).body(containsString("ClusterNotFound"));
    }

    @Test
    void aRedshiftServerlessNamespaceAndScheduledActionNeedPassRoleOnTheirRolesForRedshiftServerless() {
        String role = role("serverless-copy");
        User user = user("passrole-serverless");
        String namespace = "passrole-ns-" + suffix();
        String action = "passrole-action-" + suffix();
        String createNamespace = "{\"namespaceName\":\"%s\",\"adminUsername\":\"admin\",\"iamRoles\":[\"%s\"]}"
                .formatted(namespace, role);
        String updateNamespace = "{\"namespaceName\":\"%s\",\"iamRoles\":[\"%s\"],\"defaultIamRoleArn\":\"%s\"}"
                .formatted(namespace, role, role);
        String createAction = ("{\"scheduledActionName\":\"%s\",\"namespaceName\":\"%s\",\"roleArn\":\"%s\","
                + "\"schedule\":{\"cron\":\"cron(0 3 * * ? *)\"},\"targetAction\":{\"createSnapshot\":"
                + "{\"namespaceName\":\"%s\",\"snapshotNamePrefix\":\"nightly\"}}}")
                .formatted(action, namespace, role, namespace);
        String updateAction = "{\"scheduledActionName\":\"%s\",\"roleArn\":\"%s\"}".formatted(action, role);
        String actions = allow("redshift-serverless:CreateNamespace", "*") + ","
                + allow("redshift-serverless:UpdateNamespace", "*") + ","
                + allow("redshift-serverless:CreateScheduledAction", "*") + ","
                + allow("redshift-serverless:UpdateScheduledAction", "*");

        // A grant to pass the role to Redshift does not cover Redshift Serverless.
        user.policy(actions + "," + passRole(role, REDSHIFT));
        deniedJson(serverless(user, "CreateNamespace").body(createNamespace).post("/").then());
        user.policy(actions + "," + passRole(role, REDSHIFT_SERVERLESS));
        serverless(user, "CreateNamespace").body(createNamespace).post("/").then().statusCode(200);
        serverless(user, "CreateScheduledAction").body(createAction).post("/").then().statusCode(200);
        cleanup.register(() -> {
            admin("redshift-serverless").header("X-Amz-Target", "RedshiftServerless.DeleteScheduledAction")
                    .contentType("application/x-amz-json-1.1")
                    .body("{\"scheduledActionName\":\"" + action + "\"}").post("/");
            admin("redshift-serverless").header("X-Amz-Target", "RedshiftServerless.DeleteNamespace")
                    .contentType("application/x-amz-json-1.1")
                    .body("{\"namespaceName\":\"" + namespace + "\"}").post("/");
        });

        user.policy(actions);
        deniedJson(serverless(user, "UpdateNamespace").body(updateNamespace).post("/").then());
        deniedJson(serverless(user, "CreateScheduledAction").body(createAction).post("/").then());
        deniedJson(serverless(user, "UpdateScheduledAction").body(updateAction).post("/").then());
        user.policy(actions + "," + passRole(role, REDSHIFT_SERVERLESS));
        serverless(user, "UpdateNamespace").body(updateNamespace).post("/").then().statusCode(200);
        serverless(user, "UpdateScheduledAction").body(updateAction).post("/").then().statusCode(200);

        // Floci keeps a default the namespace's roles do not hold, so naming one needs PassRole on it too.
        String other = role("serverless-default");
        String defaultOnly = "{\"namespaceName\":\"%s\",\"defaultIamRoleArn\":\"%s\"}".formatted(namespace, other);
        deniedJson(serverless(user, "UpdateNamespace").body(defaultOnly).post("/").then());
        user.policy(actions + "," + passRole(role, REDSHIFT_SERVERLESS) + "," + passRole(other, REDSHIFT_SERVERLESS));
        serverless(user, "UpdateNamespace").body(defaultOnly).post("/").then().statusCode(200);
    }

    @Test
    void anRdsRequestNeedsPassRoleOnEachRoleItNamesForRds() {
        String role = role("rds-feature");
        User user = user("passrole-rds");
        String missing = "passrole-missing-" + suffix();
        String option = "OptionsToInclude.OptionConfiguration.1.";
        Map<String, Map<String, String>> requests = new LinkedHashMap<>();
        requests.put("AddRoleToDBInstance", Map.of("DBInstanceIdentifier", missing, "RoleArn", role,
                "FeatureName", "s3Import"));
        requests.put("RemoveRoleFromDBCluster", Map.of("DBClusterIdentifier", missing, "RoleArn", role,
                "FeatureName", "s3Import"));
        requests.put("ModifyDBProxy", Map.of("DBProxyName", missing, "RoleArn", role));
        requests.put("ModifyDBInstance", Map.of("DBInstanceIdentifier", missing, "MonitoringInterval", "60",
                "MonitoringRoleArn", role));
        requests.put("RestoreDBClusterFromSnapshot", Map.of("DBClusterIdentifier", missing, "SnapshotIdentifier",
                missing, "Engine", "aurora-postgresql", "AssociatedRoles.DBClusterAssociatedRole.1.RoleArn", role));
        requests.put("ModifyOptionGroup", Map.of("OptionGroupName", missing, option + "OptionName",
                "SQLSERVER_BACKUP_RESTORE", option + "OptionSettings.OptionSetting.1.Name", "IAM_ROLE_ARN",
                option + "OptionSettings.OptionSetting.1.Value", role));
        requests.put("StartExportTask", Map.of("ExportTaskIdentifier", missing, "SourceArn",
                "arn:aws:rds:us-east-1:" + ACCOUNT + ":snapshot:" + missing, "S3BucketName", missing,
                "IamRoleArn", role, "KmsKeyId", "alias/" + missing));

        // A grant to pass the role to Redshift does not cover RDS; with one for RDS, each reaches RDS, which
        // has no such resource.
        user.policy(allow("rds:*", "*") + "," + passRole(role, REDSHIFT));
        requests.forEach((action, params) -> denied(user.rds(action).formParams(params).post("/").then()));
        user.policy(allow("rds:*", "*") + "," + passRole(role, RDS));
        requests.forEach((action, params) -> user.rds(action).formParams(params).post("/").then()
                .statusCode(not(403)).body(not(containsString("iam:PassRole"))));
    }

    @Test
    void anRdsDirectoryRoleNamedByNameNeedsPassRoleOnTheArnIamHoldsItUnder() {
        String role = role("/directory/", "rds-directory");
        User user = user("passrole-rds-directory");
        String pathless = "arn:aws:iam::" + ACCOUNT + ":role/" + roleName(role);
        String cluster = "passrole-missing-" + suffix();

        user.policy(allow("rds:ModifyDBCluster", "*") + "," + passRole(pathless, RDS));
        denied(modifyClusterDirectoryRole(user, cluster, roleName(role)).then());
        user.policy(allow("rds:ModifyDBCluster", "*") + "," + passRole(role, RDS));
        modifyClusterDirectoryRole(user, cluster, roleName(role)).then()
                .statusCode(404).body(containsString("DBClusterNotFound"));
    }

    private static Response modifyClusterDirectoryRole(User user, String cluster, String roleName) {
        return user.rds("ModifyDBCluster").formParam("DBClusterIdentifier", cluster)
                .formParam("DomainIAMRoleName", roleName).post("/");
    }

    @Test
    void aDmsEndpointAndReplicationInstanceNeedPassRoleOnTheirRolesForDms() {
        String role = role("dms-access");
        User user = user("passrole-dms");
        String endpoint = ("{\"EndpointIdentifier\":\"passrole-%s\",\"EndpointType\":\"target\",\"EngineName\":\"s3\","
                + "\"S3Settings\":{\"ServiceAccessRoleArn\":\"%s\",\"BucketName\":\"passrole-bucket\"}}")
                .formatted(suffix(), role);
        String modifyEndpoint = ("{\"EndpointArn\":\"arn:aws:dms:us-east-1:%s:endpoint:PASSROLEMISSING\","
                + "\"PostgreSQLSettings\":{\"SecretsManagerAccessRoleArn\":\"%s\"}}").formatted(ACCOUNT, role);
        String modifyInstance = ("{\"ReplicationInstanceArn\":\"arn:aws:dms:us-east-1:%s:rep:PASSROLEMISSING\","
                + "\"KerberosAuthenticationSettings\":{\"KeyCacheSecretIamArn\":\"%s\"}}").formatted(ACCOUNT, role);

        // A grant to pass the role to RDS does not cover DMS.
        user.policy(allow("dms:*", "*") + "," + passRole(role, RDS));
        deniedJson(dms(user, "CreateEndpoint").body(endpoint).post("/").then());
        deniedJson(dms(user, "ModifyEndpoint").body(modifyEndpoint).post("/").then());
        deniedJson(dms(user, "ModifyReplicationInstance").body(modifyInstance).post("/").then());

        user.policy(allow("dms:*", "*") + "," + passRole(role, DMS));
        String endpointArn = dms(user, "CreateEndpoint").body(endpoint).post("/").then().statusCode(200)
                .extract().path("Endpoint.EndpointArn");
        cleanup.register(() -> admin("dms").header("X-Amz-Target", "AmazonDMSv20160101.DeleteEndpoint")
                .contentType("application/x-amz-json-1.1").body("{\"EndpointArn\":\"" + endpointArn + "\"}").post("/"));
        dms(user, "ModifyEndpoint").body(modifyEndpoint).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundFault"));
        dms(user, "ModifyReplicationInstance").body(modifyInstance).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundFault"));
    }

    private static RequestSpecification dms(User user, String operation) {
        return user.json("dms", "AmazonDMSv20160101." + operation);
    }

    private static Response restore(User user, String cluster, String role) {
        return user.redshift("RestoreFromClusterSnapshot").formParam("ClusterIdentifier", cluster)
                .formParam("SnapshotIdentifier", "passrole-no-snapshot").formParam("IamRoles.IamRoleArn.1", role)
                .post("/");
    }

    private static RequestSpecification serverless(User user, String operation) {
        return user.json("redshift-serverless", "RedshiftServerless." + operation);
    }

    private static Response launchConfiguration(User user, String name, String profile) {
        return user.autoscaling("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("IamInstanceProfile", profile).post("/");
    }

    /**
     * A launch template, removed afterwards, with a version for each of {@code profiles} in order, each
     * naming that instance profile or, for null, none; returns its ID.
     */
    private String launchTemplate(String... profiles) {
        String name = "passrole-asg-template-" + suffix();
        String templateId = withProfile(admin("ec2").formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name), profiles[0]).post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteLaunchTemplate")
                .formParam("LaunchTemplateId", templateId).post("/"));
        for (int i = 1; i < profiles.length; i++) {
            withProfile(admin("ec2").formParam("Action", "CreateLaunchTemplateVersion")
                    .formParam("LaunchTemplateId", templateId), profiles[i]).post("/").then().statusCode(200);
        }
        return templateId;
    }

    private static RequestSpecification withProfile(RequestSpecification request, String profile) {
        request.formParam("LaunchTemplateData.ImageId", IMAGE);
        return profile == null ? request : request.formParam("LaunchTemplateData.IamInstanceProfile.Name", profile);
    }

    /** An empty group's request, which launches nothing. */
    private static RequestSpecification group(User user, String action, String name) {
        return user.autoscaling(action).formParam("AutoScalingGroupName", name).formParam("MinSize", "0")
                .formParam("MaxSize", "0").formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a");
    }

    private static Response lifecycleHook(User user, String group, String role) {
        return user.autoscaling("PutLifecycleHook").formParam("AutoScalingGroupName", group)
                .formParam("LifecycleHookName", "launch")
                .formParam("LifecycleTransition", "autoscaling:EC2_INSTANCE_LAUNCHING")
                .formParam("NotificationTargetARN", QUEUE_ARN).formParam("RoleARN", role).post("/");
    }

    private String spotProfileArn(User user, String selector, String profile) {
        String item = "RequestSpotInstancesResponse.spotInstanceRequestSet.item.";
        XmlPath request = spotRequest(user, selector, profile).then().statusCode(200).extract().xmlPath();
        String requestId = request.getString(item + "spotInstanceRequestId");
        cleanup.register(() -> admin("ec2").formParam("Action", "CancelSpotInstanceRequests")
                .formParam("SpotInstanceRequestId.1", requestId).post("/"));
        terminateLater(request.getString(item + "instanceId"));
        return request.getString(item + "launchSpecification.iamInstanceProfile.arn");
    }

    /** A Spot Instance request naming its instance profile by {@code selector}, {@code Arn} or {@code Name}. */
    private static Response spotRequest(User user, String selector, String profile) {
        return user.ec2("RequestSpotInstances").formParam("SpotPrice", "0.05").formParam("InstanceCount", "1")
                .formParam("LaunchSpecification.ImageId", IMAGE).formParam("LaunchSpecification.InstanceType", "t3.micro")
                .formParam("LaunchSpecification.IamInstanceProfile." + selector, profile).post("/");
    }

    private static Response flowLog(User user, String vpcId, String role) {
        return user.ec2("CreateFlowLogs").formParam("ResourceId.1", vpcId).formParam("ResourceType", "VPC")
                .formParam("TrafficType", "ALL").formParam("LogGroupName", "passrole-flow-logs")
                .formParam("DeliverLogsPermissionArn", role).post("/");
    }

    private static String taskDefinition(String family, String taskRole, String executionRole) {
        return """
                {"family":"%s","requiresCompatibilities":["FARGATE"],"networkMode":"awsvpc","cpu":"256","memory":"512",
                 "containerDefinitions":[{"name":"app","image":"nginx:latest","essential":true}]%s%s}"""
                .formatted(family, taskRole == null ? "" : ",\"taskRoleArn\":\"" + taskRole + "\"",
                        executionRole == null ? "" : ",\"executionRoleArn\":\"" + executionRole + "\"");
    }

    /** Registers a task definition with {@code taskRole}, removed afterwards, and returns its family. */
    private String registerTaskDefinition(String taskRole) {
        String family = "passrole-family-" + suffix();
        admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.RegisterTaskDefinition")
                .body(taskDefinition(family, taskRole, null)).post("/").then().statusCode(200);
        deregisterLater(family + ":1");
        return family;
    }

    private void deregisterLater(String taskDefinition) {
        cleanup.register(() -> admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DeregisterTaskDefinition")
                .body("{\"taskDefinition\":\"" + taskDefinition + "\"}").post("/"));
    }

    /** Creates a cluster, removed afterwards, and returns its name. */
    private String cluster() {
        String name = "passrole-cluster-" + suffix();
        admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.CreateCluster")
                .body("{\"clusterName\":\"" + name + "\"}").post("/").then().statusCode(200);
        cleanup.register(() -> admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DeleteCluster")
                .body("{\"cluster\":\"" + name + "\"}").post("/"));
        return name;
    }

    private void stopLater(String cluster, String taskArn) {
        cleanup.register(() -> admin("ecs").contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.StopTask")
                .body("{\"cluster\":\"" + cluster + "\",\"task\":\"" + taskArn + "\"}").post("/"));
    }

    private static String runTask(String cluster, String family, String taskRoleOverride) {
        return """
                {"cluster":"%s","taskDefinition":"%s","launchType":"FARGATE","count":1,
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-default-us-east-1-a"]}}%s}"""
                .formatted(cluster, family, taskRoleOverride == null ? ""
                        : ",\"overrides\":{\"taskRoleArn\":\"" + taskRoleOverride + "\"}");
    }

    private static String service(String cluster, String name, String family) {
        return """
                {"cluster":"%s","serviceName":"%s","taskDefinition":"%s","desiredCount":0,"launchType":"FARGATE",
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-default-us-east-1-a"]}}}"""
                .formatted(cluster, name, family);
    }

    private static RequestSpecification createStack(User user, String stack) {
        return user.cfn("CreateStack").formParam("StackName", stack).formParam("TemplateBody",
                "{\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":\"" + stack + "\"}}}}");
    }

    private static RequestSpecification stackSet(User user, String action, String name) {
        return user.cfn(action).formParam("StackSetName", name).formParam("TemplateBody",
                "{\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\"}}}");
    }

    @Test
    void anSsmAssociationAndACommandNeedPassRoleOnTheRolesTheyNameForSsm() {
        String role = role("ssm-dispatch");
        User user = user("passrole-ssm");
        String association = "{\"Name\":\"passrole-missing-document\",\"InstanceId\":\"i-0123456789abcdef0\","
                + "\"AssociationDispatchAssumeRole\":\"" + role + "\"}";
        String update = "{\"AssociationId\":\"" + UUID.randomUUID() + "\",\"AssociationDispatchAssumeRole\":\""
                + role + "\"}";
        // The command names no instance, so SSM refuses it once it is authorized.
        String command = "{\"DocumentName\":\"AWS-RunShellScript\",\"ServiceRoleArn\":\"" + role + "\"}";
        String actions = allow("ssm:CreateAssociation", "*") + "," + allow("ssm:UpdateAssociation", "*") + ","
                + allow("ssm:SendCommand", "*");

        // A grant to pass the role to Lambda does not cover SSM.
        user.policy(actions + "," + passRole(role, LAMBDA));
        deniedJson(ssm(user, "CreateAssociation").body(association).post("/").then());
        deniedJson(ssm(user, "UpdateAssociation").body(update).post("/").then());
        deniedJson(ssm(user, "SendCommand").body(command).post("/").then());

        user.policy(actions + "," + passRole(role, SSM));
        ssm(user, "CreateAssociation").body(association).post("/").then()
                .statusCode(400).body(containsString("InvalidDocument"));
        ssm(user, "UpdateAssociation").body(update).post("/").then()
                .statusCode(400).body(containsString("AssociationDoesNotExist"));
        ssm(user, "SendCommand").body(command).post("/").then()
                .statusCode(400).body(containsString("InvalidInstanceId"));
    }

    @Test
    void aServiceCatalogLaunchRoleNeedsPassRoleForServiceCatalog() {
        String role = role("catalog-launch");
        User user = user("passrole-catalog");
        String portfolio = catalogAdmin("CreatePortfolio", "{\"DisplayName\":\"passrole-" + suffix()
                + "\",\"ProviderName\":\"floci\"}").extract().path("PortfolioDetail.Id");
        cleanup.register(() -> deleteFromCatalog("DeletePortfolio", portfolio));
        String product = catalogAdmin("CreateProduct", "{\"Name\":\"passrole-" + suffix() + "\",\"Owner\":\"floci\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
        cleanup.register(() -> deleteFromCatalog("DeleteProduct", product));
        String launch = catalogAdmin("CreateConstraint", constraint(portfolio, product, "LAUNCH", "{}"))
                .extract().path("ConstraintDetail.ConstraintId");
        String notification = catalogAdmin("CreateConstraint", constraint(portfolio, product, "NOTIFICATION",
                "{\\\"NotificationArns\\\":[]}")).extract().path("ConstraintDetail.ConstraintId");
        // Floci keeps a constraint's type as given, so one typed in lowercase is a launch constraint too.
        String lowercase = catalogAdmin("CreateConstraint", constraint(portfolio, product, "launch", "{}"))
                .extract().path("ConstraintDetail.ConstraintId");
        cleanup.register(() -> deleteFromCatalog("DeleteConstraint", launch));
        cleanup.register(() -> deleteFromCatalog("DeleteConstraint", notification));
        cleanup.register(() -> deleteFromCatalog("DeleteConstraint", lowercase));
        String launchRole = "{\\\"RoleArn\\\":\\\"" + role + "\\\"}";
        String create = constraint(portfolio, product, "LAUNCH", launchRole);
        String updateLaunch = "{\"Id\":\"" + launch + "\",\"Parameters\":\"" + launchRole + "\"}";
        String updateNotification = "{\"Id\":\"" + notification + "\",\"Parameters\":\"" + launchRole + "\"}";
        String createLowercase = constraint(portfolio, product, "launch", launchRole);
        String updateLowercase = "{\"Id\":\"" + lowercase + "\",\"Parameters\":\"" + launchRole + "\"}";
        String serviceAction = "{\"Name\":\"passrole-" + suffix() + "\",\"DefinitionType\":\"SSM_AUTOMATION\","
                + "\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\",\"Version\":\"1\",\"AssumeRole\":\""
                + role + "\"},\"IdempotencyToken\":\"" + suffix() + "\"}";
        String properties = "{\"ProvisionedProductId\":\"pp-passrolemissing\",\"IdempotencyToken\":\"" + suffix()
                + "\",\"ProvisionedProductProperties\":{\"LAUNCH_ROLE\":\"" + role + "\"}}";
        String actions = allow("servicecatalog:*", "*");

        // A grant to pass the role to SSM does not cover Service Catalog.
        user.policy(actions + "," + passRole(role, SSM));
        deniedJson(catalog(user, "CreateConstraint").body(create).post("/").then());
        deniedJson(catalog(user, "UpdateConstraint").body(updateLaunch).post("/").then());
        deniedJson(catalog(user, "CreateConstraint").body(createLowercase).post("/").then());
        deniedJson(catalog(user, "UpdateConstraint").body(updateLowercase).post("/").then());
        deniedJson(catalog(user, "CreateServiceAction").body(serviceAction).post("/").then());
        deniedJson(catalog(user, "UpdateProvisionedProductProperties").body(properties).post("/").then());
        // The update goes by the constraint's own type: a notification constraint takes no launch role.
        catalog(user, "UpdateConstraint").body(updateNotification).post("/").then().statusCode(200);

        user.policy(actions + "," + passRole(role, SERVICE_CATALOG));
        String created = catalog(user, "CreateConstraint").body(create).post("/").then().statusCode(200)
                .extract().path("ConstraintDetail.ConstraintId");
        cleanup.register(() -> deleteFromCatalog("DeleteConstraint", created));
        catalog(user, "UpdateConstraint").body(updateLaunch).post("/").then().statusCode(200);
        String action = catalog(user, "CreateServiceAction").body(serviceAction).post("/").then().statusCode(200)
                .extract().path("ServiceActionDetail.ServiceActionSummary.Id");
        cleanup.register(() -> deleteFromCatalog("DeleteServiceAction", action));
        catalog(user, "UpdateProvisionedProductProperties").body(properties).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundException"));
    }

    @Test
    void aServiceCatalogLocalRoleNameNeedsPassRoleOnTheArnIamHoldsItUnder() {
        String held = role("/catalog/", "catalog-local");
        String pathless = "arn:aws:iam::" + ACCOUNT + ":role/" + roleName(held);
        User user = user("passrole-catalog-local");
        String update = "{\"Id\":\"cons-passrolemissing\",\"Parameters\":\"{\\\"LocalRoleName\\\":\\\""
                + roleName(held) + "\\\"}\"}";
        String create = constraint("port-passrolemissing", "prod-passrolemissing", "LAUNCH",
                "{\\\"LocalRoleName\\\":\\\"" + roleName(held) + "\\\"}");

        // The name carries no path, but the role it names is the one IAM holds under /catalog/.
        user.policy(allow("servicecatalog:CreateConstraint", "*") + "," + passRole(pathless, SERVICE_CATALOG));
        deniedJson(catalog(user, "CreateConstraint").body(create).post("/").then());
        user.policy(allow("servicecatalog:CreateConstraint", "*") + "," + passRole(held, SERVICE_CATALOG));
        catalog(user, "CreateConstraint").body(create).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundException"));

        // A constraint Floci does not hold has no type to read a launch role by, and Service Catalog reports it.
        user.policy(allow("servicecatalog:UpdateConstraint", "*"));
        catalog(user, "UpdateConstraint").body(update).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundException"));
    }

    @Test
    void aRotationRoleABudgetActionsRoleAndAWorkflowsLambdaRoleNeedPassRoleForTheirService() {
        String role = role("service-role");
        User user = user("passrole-mixed");
        String rotate = "{\"SecretId\":\"passrole-missing-secret\",\"ExternalSecretRotationRoleArn\":\"" + role + "\"}";
        String budgetAction = ("{\"AccountId\":\"%s\",\"BudgetName\":\"passrole-missing\",\"NotificationType\":\"ACTUAL\","
                + "\"ActionType\":\"APPLY_IAM_POLICY\",\"ActionThreshold\":{\"ActionThresholdValue\":80,"
                + "\"ActionThresholdType\":\"PERCENTAGE\"},\"Definition\":{\"IamActionDefinition\":{\"PolicyArn\":"
                + "\"arn:aws:iam::%s:policy/passrole-deny\",\"Roles\":[\"target\"]}},\"ExecutionRoleArn\":\"%s\","
                + "\"ApprovalModel\":\"AUTOMATIC\",\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\","
                + "\"Address\":\"ops@example.com\"}]}").formatted(ACCOUNT, ACCOUNT, role);
        String workflowType = "{\"domain\":\"passrole-missing\",\"name\":\"w\",\"version\":\"1\","
                + "\"defaultLambdaRole\":\"" + role + "\"}";
        String execution = "{\"domain\":\"passrole-missing\",\"workflowId\":\"w\",\"workflowType\":"
                + "{\"name\":\"w\",\"version\":\"1\"},\"lambdaRole\":\"" + role + "\"}";
        String decisions = "{\"taskToken\":\"passrole-missing\",\"decisions\":[{\"decisionType\":"
                + "\"StartChildWorkflowExecution\",\"startChildWorkflowExecutionDecisionAttributes\":"
                + "{\"workflowType\":{\"name\":\"w\",\"version\":\"1\"},\"workflowId\":\"child\","
                + "\"lambdaRole\":\"" + role + "\"}}]}";
        String actions = allow("secretsmanager:RotateSecret", "*") + "," + allow("budgets:CreateBudgetAction", "*")
                + "," + allow("swf:*", "*");

        // Each grant is for another service's principal.
        user.policy(actions + "," + passRole(role, "budgets.amazonaws.com") + "," + passRole(role, SSM));
        deniedJson(user.json("secretsmanager", "secretsmanager.RotateSecret").body(rotate).post("/").then());
        user.policy(actions + "," + passRole(role, "secretsmanager.amazonaws.com") + "," + passRole(role, SSM));
        deniedJson(user.json("budgets", "AWSBudgetServiceGateway.CreateBudgetAction").body(budgetAction).post("/")
                .then());
        user.policy(actions + "," + passRole(role, "secretsmanager.amazonaws.com") + ","
                + passRole(role, "budgets.amazonaws.com"));
        deniedJson(swf(user, "RegisterWorkflowType").body(workflowType).post("/").then());
        deniedJson(swf(user, "StartWorkflowExecution").body(execution).post("/").then());
        deniedJson(swf(user, "RespondDecisionTaskCompleted").body(decisions).post("/").then());

        // With all three, each request reaches its service, which has nothing of that name.
        user.policy(actions + "," + passRole(role, "secretsmanager.amazonaws.com") + ","
                + passRole(role, "budgets.amazonaws.com") + "," + passRole(role, "swf.amazonaws.com"));
        user.json("secretsmanager", "secretsmanager.RotateSecret").body(rotate).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundException"));
        user.json("budgets", "AWSBudgetServiceGateway.CreateBudgetAction").body(budgetAction).post("/").then()
                .statusCode(400).body(containsString("NotFoundException"));
        swf(user, "RegisterWorkflowType").body(workflowType).post("/").then()
                .statusCode(400).body(containsString("UnknownResourceFault"));
        swf(user, "StartWorkflowExecution").body(execution).post("/").then()
                .statusCode(400).body(containsString("UnknownResourceFault"));
        swf(user, "RespondDecisionTaskCompleted").body(decisions).post("/").then()
                .statusCode(400).body(containsString("Unknown or expired task token"));
    }

    @Test
    void anSnsTopicsDeliveryStatusRoleNeedsPassRoleForSns() {
        String role = role("sns-feedback");
        User user = user("passrole-sns");
        String topic = "passrole-topic-" + suffix();
        String topicArn = "arn:aws:sns:us-east-1:" + ACCOUNT + ":" + topic;
        String actions = allow("sns:CreateTopic", "*") + "," + allow("sns:SetTopicAttributes", "*");

        user.policy(actions + "," + passRole(role, LAMBDA));
        denied(user.query("sns", "CreateTopic").formParam("Name", topic)
                .formParam("Attributes.entry.1.key", "LambdaFailureFeedbackRoleArn")
                .formParam("Attributes.entry.1.value", role).post("/").then());
        denied(user.query("sns", "SetTopicAttributes").formParam("TopicArn", topicArn)
                .formParam("AttributeName", "SQSSuccessFeedbackRoleArn").formParam("AttributeValue", role)
                .post("/").then());

        user.policy(actions + "," + passRole(role, "sns.amazonaws.com"));
        user.query("sns", "CreateTopic").formParam("Name", topic)
                .formParam("Attributes.entry.1.key", "LambdaFailureFeedbackRoleArn")
                .formParam("Attributes.entry.1.value", role).post("/").then().statusCode(200);
        cleanup.register(() -> admin("sns").formParam("Action", "DeleteTopic").formParam("TopicArn", topicArn).post("/"));
        user.query("sns", "SetTopicAttributes").formParam("TopicArn", topicArn)
                .formParam("AttributeName", "SQSSuccessFeedbackRoleArn").formParam("AttributeValue", role)
                .post("/").then().statusCode(200);

        // A role attribute with no value names no role, so SNS answers it: a topic takes it, and a Firehose
        // subscription refuses it.
        user.policy(actions + "," + allow("sns:SetSubscriptionAttributes", "*"));
        user.query("sns", "SetTopicAttributes").formParam("TopicArn", topicArn)
                .formParam("AttributeName", "SQSSuccessFeedbackRoleArn").post("/").then().statusCode(200);
        String subscription = admin("sns").formParam("Action", "Subscribe").formParam("TopicArn", topicArn)
                .formParam("Protocol", "firehose")
                .formParam("Endpoint", "arn:aws:firehose:us-east-1:" + ACCOUNT + ":deliverystream/passrole")
                .formParam("Attributes.entry.1.key", "SubscriptionRoleArn")
                .formParam("Attributes.entry.1.value", role).post("/").then().statusCode(200)
                .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");
        user.query("sns", "SetSubscriptionAttributes").formParam("SubscriptionArn", subscription)
                .formParam("AttributeName", "SubscriptionRoleArn").post("/").then().statusCode(400)
                .body(containsString("SubscriptionRoleArn must be a valid IAM role ARN"));
    }

    @Test
    void aReceiptRulesRoleAndAMetricStreamsRoleInAQueryRequestNeedPassRoleForTheirService() {
        String role = role("query-roles");
        User user = user("passrole-query");
        String stream = "passrole-stream-" + suffix();
        String actions = allow("ses:CreateReceiptRule", "*") + "," + allow("cloudwatch:PutMetricStream", "*");

        user.policy(actions + "," + passRole(role, LAMBDA));
        denied(receiptRule(user, role).post("/").then());
        denied(metricStream(user, stream, role).post("/").then());

        user.policy(actions + "," + passRole(role, "ses.amazonaws.com") + ","
                + passRole(role, "streams.metrics.cloudwatch.amazonaws.com"));
        receiptRule(user, role).post("/").then().statusCode(400).body(containsString("RuleSetDoesNotExist"));
        metricStream(user, stream, role).post("/").then().statusCode(200);
        cleanup.register(() -> admin("monitoring").formParam("Action", "DeleteMetricStream").formParam("Name", stream)
                .post("/"));
    }

    @Test
    void aBuildProjectsServiceRoleAndADeliveryStreamsRoleNeedPassRoleForTheirService() {
        String role = role("json-roles");
        User user = user("passrole-json");
        String project = "passrole-project-" + suffix();
        String deliveryStream = "passrole-delivery-" + suffix();
        String createProject = ("{\"name\":\"%s\",\"source\":{\"type\":\"NO_SOURCE\",\"buildspec\":\"version: 0.2\"},"
                + "\"artifacts\":{\"type\":\"NO_ARTIFACTS\"},\"environment\":{\"type\":\"LINUX_CONTAINER\","
                + "\"image\":\"aws/codebuild/standard:7.0\",\"computeType\":\"BUILD_GENERAL1_SMALL\"},"
                + "\"serviceRole\":\"%s\"}").formatted(project, role);
        String createStream = ("{\"DeliveryStreamName\":\"%s\",\"ExtendedS3DestinationConfiguration\":"
                + "{\"RoleARN\":\"%s\",\"BucketARN\":\"arn:aws:s3:::passrole-bucket\"}}").formatted(deliveryStream, role);
        String actions = allow("codebuild:CreateProject", "*") + "," + allow("firehose:CreateDeliveryStream", "*");

        user.policy(actions + "," + passRole(role, LAMBDA));
        deniedJson(user.json("codebuild", "CodeBuild_20161006.CreateProject").body(createProject).post("/").then());
        deniedJson(user.json("firehose", "Firehose_20150804.CreateDeliveryStream").body(createStream).post("/").then());

        user.policy(actions + "," + passRole(role, "codebuild.amazonaws.com") + ","
                + passRole(role, "firehose.amazonaws.com"));
        user.json("codebuild", "CodeBuild_20161006.CreateProject").body(createProject).post("/").then().statusCode(200);
        cleanup.register(() -> admin("codebuild").header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
                .contentType("application/x-amz-json-1.1").body("{\"name\":\"" + project + "\"}").post("/"));
        user.json("firehose", "Firehose_20150804.CreateDeliveryStream").body(createStream).post("/").then()
                .statusCode(200);
        cleanup.register(() -> admin("firehose").header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
                .contentType("application/x-amz-json-1.1").body("{\"DeliveryStreamName\":\"" + deliveryStream + "\"}")
                .post("/"));
    }

    @Test
    void aGlueJobsRoleNamedByNameNeedsPassRoleOnTheArnIamHoldsItUnder() {
        String role = role("/glue/", "glue-job");
        String name = roleName(role);
        User user = user("passrole-glue");
        String job = "passrole-job-" + suffix();
        String createJob = ("{\"Name\":\"%s\",\"Role\":\"%s\",\"Command\":{\"Name\":\"glueetl\","
                + "\"ScriptLocation\":\"s3://passrole-bucket/job.py\"}}").formatted(job, name);

        // The name carries no path; the role it names is the one IAM holds under /glue/.
        user.policy(allow("glue:CreateJob", "*") + ","
                + passRole("arn:aws:iam::" + ACCOUNT + ":role/" + name, "glue.amazonaws.com"));
        deniedJson(user.json("glue", "AWSGlue.CreateJob").body(createJob).post("/").then());

        user.policy(allow("glue:CreateJob", "*") + "," + passRole(role, "glue.amazonaws.com"));
        user.json("glue", "AWSGlue.CreateJob").body(createJob).post("/").then().statusCode(200);
        cleanup.register(() -> admin("glue").header("X-Amz-Target", "AWSGlue.DeleteJob")
                .contentType("application/x-amz-json-1.1").body("{\"JobName\":\"" + job + "\"}").post("/"));
    }

    @Test
    void aCognitoGroupsRoleIsPassedToNoNamedService() {
        String role = role("cognito-group");
        User user = user("passrole-cognito");
        String group = "{\"UserPoolId\":\"us-east-1_passrolemissing\",\"GroupName\":\"g\",\"RoleArn\":\"" + role + "\"}";

        // The Service Reference names no service for a group's role, so a grant naming one does not match.
        user.policy(allow("cognito-idp:CreateGroup", "*") + "," + passRole(role, "cognito-idp.amazonaws.com"));
        deniedJson(user.json("cognito-idp", "AWSCognitoIdentityProviderService.CreateGroup").body(group).post("/")
                .then());

        user.policy(allow("cognito-idp:CreateGroup", "*") + "," + allow("iam:PassRole", role));
        user.json("cognito-idp", "AWSCognitoIdentityProviderService.CreateGroup").body(group).post("/").then()
                .statusCode(400).body(containsString("ResourceNotFoundException"));
    }

    @Test
    void aSubscriptionFilterPolicysRoleNeedsPassRoleForLogs() {
        String role = role("logs-delivery");
        User user = user("passrole-logs");
        String policy = "passrole-policy-" + suffix();
        String body = ("{\"policyName\":\"%s\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"policyDocument\":"
                + "\"{\\\"DestinationArn\\\":\\\"arn:aws:kinesis:us-east-1:%s:stream/passrole\\\","
                + "\\\"RoleArn\\\":\\\"%s\\\"}\"}").formatted(policy, ACCOUNT, role);

        // A subscription filter policy also needs logs:PutSubscriptionFilter, which Floci checks too.
        String actions = allow("logs:PutAccountPolicy", "*") + "," + allow("logs:PutSubscriptionFilter", "*");
        user.policy(actions + "," + passRole(role, LAMBDA));
        deniedJson(user.json("logs", "Logs_20140328.PutAccountPolicy").body(body).post("/").then());

        user.policy(actions + "," + passRole(role, "logs.amazonaws.com"));
        user.json("logs", "Logs_20140328.PutAccountPolicy").body(body).post("/").then().statusCode(200);
        cleanup.register(() -> admin("logs").header("X-Amz-Target", "Logs_20140328.DeleteAccountPolicy")
                .contentType("application/x-amz-json-1.1")
                .body("{\"policyName\":\"" + policy + "\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\"}").post("/"));
    }

    private static RequestSpecification receiptRule(User user, String role) {
        return user.query("ses", "CreateReceiptRule").formParam("RuleSetName", "passrole-missing")
                .formParam("Rule.Name", "r").formParam("Rule.Enabled", "true")
                .formParam("Rule.Actions.member.1.S3Action.BucketName", "passrole-bucket")
                .formParam("Rule.Actions.member.1.S3Action.IamRoleArn", role);
    }

    private static RequestSpecification metricStream(User user, String name, String role) {
        return user.query("monitoring", "PutMetricStream").formParam("Name", name)
                .formParam("FirehoseArn", "arn:aws:firehose:us-east-1:" + ACCOUNT + ":deliverystream/passrole")
                .formParam("RoleArn", role).formParam("OutputFormat", "json");
    }

    private static RequestSpecification ssm(User user, String operation) {
        return user.json("ssm", "AmazonSSM." + operation);
    }

    private static RequestSpecification catalog(User user, String operation) {
        return user.json("servicecatalog", "AWS242ServiceCatalogService." + operation);
    }

    private static ValidatableResponse catalogAdmin(String operation, String body) {
        return admin("servicecatalog").header("X-Amz-Target", "AWS242ServiceCatalogService." + operation)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    private static void deleteFromCatalog(String operation, String id) {
        admin("servicecatalog").header("X-Amz-Target", "AWS242ServiceCatalogService." + operation)
                .contentType("application/x-amz-json-1.1").body("{\"Id\":\"" + id + "\"}").post("/");
    }

    /** A CreateConstraint body; {@code parameters} is the document, its quotes escaped for the JSON string it sits in. */
    private static String constraint(String portfolio, String product, String type, String parameters) {
        return "{\"PortfolioId\":\"" + portfolio + "\",\"ProductId\":\"" + product + "\",\"Type\":\"" + type
                + "\",\"Parameters\":\"" + parameters + "\",\"IdempotencyToken\":\"" + suffix() + "\"}";
    }

    /** SWF speaks JSON 1.0. */
    private static RequestSpecification swf(User user, String operation) {
        return given().header("Authorization", auth(user.accessKeyId(), "swf"))
                .header("X-Amz-Target", "SimpleWorkflowService." + operation).contentType("application/x-amz-json-1.0");
    }

    private static String roleName(String roleArn) {
        return roleArn.substring(roleArn.lastIndexOf('/') + 1);
    }

    private static void assertPassRoleDenied(Response response) throws IOException {
        JsonNode error = CBOR_MAPPER.readTree(response.then().statusCode(403).extract().asByteArray());
        assertTrue(error.path("message").asText().contains("iam:PassRole"), error.toString());
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
            out.write(body);
        }
        return compressed.toByteArray();
    }

    /** A REST request refused for the operation's own action, which the message names. */
    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString(action)).body(not(containsString("iam:PassRole")));
    }

    private static String schedule(String role) {
        return """
                {"ScheduleExpression":"rate(1 hour)","FlexibleTimeWindow":{"Mode":"OFF"},
                 "Target":{"Arn":"%s","RoleArn":"%s"}}""".formatted(QUEUE_ARN, role);
    }

    private static void denied(ValidatableResponse response) {
        response.statusCode(403).body(containsString("iam:PassRole"));
    }

    /** A JSON-protocol service refuses with {@code AccessDeniedException}, which those protocols send as a 400. */
    private static void deniedJson(ValidatableResponse response) {
        response.statusCode(400).body(containsString("AccessDeniedException")).body(containsString("iam:PassRole"));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String passRole(String role, String service) {
        return "{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"" + role + "\","
                + "\"Condition\":{\"StringEquals\":{\"iam:PassedToService\":\"" + service + "\"}}}";
    }

    private String role(String name) {
        return role("/", name);
    }

    private String role(String path, String name) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, path, name + "-" + suffix(),
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\","
                        + "\"Resource\":\"*\"}]}");
    }

    private String instanceProfile(String roleName) {
        String name = emptyInstanceProfile();
        admin("iam").formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("RoleName", roleName).post("/").then().statusCode(200);
        return name;
    }

    private String emptyInstanceProfile() {
        String name = "passrole-profile-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeInstanceProfile(baseUri.getPort(), auth(ADMIN, "iam"), name));
        admin("iam").formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200);
        return name;
    }

    private static ValidatableResponse addRoleToInstanceProfile(User user, String profile, String roleName) {
        return user.iam("AddRoleToInstanceProfile").formParam("InstanceProfileName", profile)
                .formParam("RoleName", roleName).post("/").then();
    }

    private void terminateLater(String instanceId) {
        cleanup.register(() -> admin("ec2").formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/"));
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

        RequestSpecification lambda() {
            return given().header("Authorization", auth(accessKeyId, "lambda")).contentType("application/json");
        }

        RequestSpecification ec2(String action) {
            return given().header("Authorization", auth(accessKeyId, "ec2")).formParam("Action", action);
        }

        RequestSpecification iam(String action) {
            return given().header("Authorization", auth(accessKeyId, "iam")).formParam("Action", action);
        }

        RequestSpecification autoscaling(String action) {
            return given().header("Authorization", auth(accessKeyId, "autoscaling")).formParam("Action", action);
        }

        RequestSpecification redshift(String action) {
            return given().header("Authorization", auth(accessKeyId, "redshift")).formParam("Action", action);
        }

        RequestSpecification rds(String action) {
            return given().header("Authorization", auth(accessKeyId, "rds")).formParam("Action", action);
        }

        RequestSpecification ecs(String action) {
            return json("ecs", "AmazonEC2ContainerServiceV20141113." + action);
        }

        RequestSpecification rest(String service) {
            return given().header("Authorization", auth(accessKeyId, service)).contentType("application/json");
        }

        RequestSpecification json(String service, String target) {
            return given().header("Authorization", auth(accessKeyId, service)).header("X-Amz-Target", target)
                    .contentType("application/x-amz-json-1.1");
        }

        /** Step Functions speaks JSON 1.0. */
        RequestSpecification states(String operation) {
            return given().header("Authorization", auth(accessKeyId, "states"))
                    .header("X-Amz-Target", "AWSStepFunctions." + operation).contentType("application/x-amz-json-1.0");
        }

        RequestSpecification cbor(String service) {
            return given().header("Authorization", auth(accessKeyId, service)).header("smithy-protocol", "rpc-v2-cbor")
                    .contentType("application/cbor").accept("application/cbor");
        }

        RequestSpecification query(String service, String action) {
            return given().header("Authorization", auth(accessKeyId, service)).formParam("Action", action);
        }

        RequestSpecification cfn(String action) {
            return given().header("Authorization", auth(accessKeyId, "cloudformation")).formParam("Action", action);
        }
    }
}
