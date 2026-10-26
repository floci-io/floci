package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.core.common.PassedRoles.PassedRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where each operation carries the roles it passes, and the service each goes to. The enforcement
 * of the check is covered by {@code IamPassRoleEnforcementIntegrationTest}; this covers the table.
 */
class PassedRolesTest {

    private static final String ROLES = "arn:aws:iam::111122223333:role/";
    private static final String ECS = "ecs.amazonaws.com";
    private static final String ECS_TASKS = "ecs-tasks.amazonaws.com";
    private static final String CLOUDFORMATION = "cloudformation.amazonaws.com";
    private static final String DEFAULT_ADMINISTRATION = ROLES + "AWSCloudFormationStackSetAdministrationRole";
    private static final String EC2 = "ec2.amazonaws.com";
    private static final String AUTOSCALING = "autoscaling.amazonaws.com";
    private static final String RDS = "rds.amazonaws.com";
    private static final String DMS = "dms.amazonaws.com";
    private static final String PROFILE_ARN = "arn:aws:iam::111122223333:instance-profile/arn-profile";

    /**
     * A task definition {@code web:3} holding a task role by ARN and an execution role by name, two
     * StackSets, two instance profiles, and the launch templates {@code lt-web}, whose every version
     * names one profile by name, and {@code arn-template}, whose version 2 names the other by ARN. The
     * instance {@code i-arn} runs with that other profile, and the group {@code g} launches from
     * {@code lt-web} once a refresh names only a version, or from {@code arn-template} at the group's
     * version 2 once a refresh names that template alone. IAM holds the role {@code app-role} under the
     * path {@code /app/}. Service Catalog holds a launch constraint {@code cons-launch} and a notification
     * constraint {@code cons-notify}.
     */
    private static final PassedRoles.Lookups LOOKUPS = new PassedRoles.Lookups() {
        @Override
        public List<String> instanceProfileRoleArns(String profileName, String profileArn) {
            if ("web-profile".equals(profileName)) {
                return List.of(ROLES + "web-instance");
            }
            return PROFILE_ARN.equals(profileArn) ? List.of(ROLES + "arn-instance") : List.of();
        }

        @Override
        public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version) {
            if ("lt-web".equals(templateId)) {
                return Optional.of("web-profile");
            }
            return "arn-template".equals(templateName) && "2".equals(version)
                    ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public Optional<String> launchConfigurationInstanceProfile(String instanceId) {
            return "i-arn".equals(instanceId) ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId,
                                                               String templateName, String version) {
            if (!"g".equals(groupName)) {
                return Optional.empty();
            }
            if (templateId == null && templateName == null && "2".equals(version)) {
                return Optional.of("web-profile");
            }
            return "arn-template".equals(templateName) && version == null ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public List<String> taskDefinitionRoles(String reference) {
            return "web:3".equals(reference) ? List.of(ROLES + "web-task", "web-execution") : List.of();
        }

        @Override
        public Optional<String> stackSetPermissionModel(String stackSetName) {
            return switch (stackSetName) {
                case "own-set" -> Optional.of("SELF_MANAGED");
                case "org-set" -> Optional.of("SERVICE_MANAGED");
                default -> Optional.empty();
            };
        }

        @Override
        public String roleArn(String roleName) {
            return ROLES + roleName;
        }

        @Override
        public Optional<String> heldRoleArn(String roleName) {
            return "app-role".equals(roleName) ? Optional.of(ROLES + "app/app-role") : Optional.empty();
        }

        @Override
        public Optional<String> serviceCatalogConstraintType(String constraintId) {
            return switch (constraintId) {
                case "cons-launch" -> Optional.of("LAUNCH");
                case "cons-lowercase" -> Optional.of("launch");
                case "cons-notify" -> Optional.of("NOTIFICATION");
                default -> Optional.empty();
            };
        }
    };

    @Test
    void aTaskDefinitionPassesItsTaskAndExecutionRolesToEcsTasksByNameOrArn() {
        assertEquals(List.of(passed("app", ECS_TASKS), passed("exec", ECS_TASKS)),
                PassedRoles.of("ecs:RegisterTaskDefinition",
                        json("{\"taskRoleArn\":\"app\",\"executionRoleArn\":\"" + ROLES + "exec\"}"), LOOKUPS));
    }

    @Test
    void aTaskPassesItsDefinitionsRolesItsOverridesAndItsVolumesInfrastructureRole() {
        String body = """
                {"taskDefinition":"web:3","overrides":{"taskRoleArn":"%1$sover-task","executionRoleArn":"%1$sover-exec"},
                 "volumeConfigurations":[{"name":"a","managedEBSVolume":{"roleArn":"%1$sebs"}}]}"""
                .formatted(ROLES);
        List<PassedRole> expected = List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS),
                passed("over-task", ECS_TASKS), passed("over-exec", ECS_TASKS), passed("ebs", ECS));

        assertEquals(expected, PassedRoles.of("ecs:RunTask", json(body), LOOKUPS));
        assertEquals(expected, PassedRoles.of("ecs:StartTask", json(body), LOOKUPS));
    }

    @Test
    void aTaskDefinitionThatDoesNotExistPassesNothing() {
        assertEquals(List.of(), PassedRoles.of("ecs:RunTask", json("{\"taskDefinition\":\"missing\"}"), LOOKUPS));
    }

    @Test
    void aServicePassesItsDefinitionsRolesAndEachInfrastructureRole() {
        String body = """
                {"taskDefinition":"web:3","role":"legacy",
                 "loadBalancers":[{"advancedConfiguration":{"roleArn":"%1$slb"}}],
                 "deploymentConfiguration":{"lifecycleHooks":[{"roleArn":"%1$shook"}]},
                 "serviceConnectConfiguration":{"services":[{"tls":{"roleArn":"%1$stls"}}]},
                 "volumeConfigurations":[{"managedEBSVolume":{"roleArn":"%1$sebs"}}],
                 "vpcLatticeConfigurations":[{"roleArn":"%1$slattice"}]}"""
                .formatted(ROLES);
        List<PassedRole> expected = List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS),
                passed("legacy", ECS), passed("lb", ECS), passed("hook", ECS), passed("tls", ECS),
                passed("ebs", ECS), passed("lattice", ECS));

        assertEquals(expected, PassedRoles.of("ecs:CreateService", json(body), LOOKUPS));
        assertEquals(expected, PassedRoles.of("ecs:UpdateService", json(body), LOOKUPS));
        // An update that keeps its task definition passes only the roles it names.
        assertEquals(List.of(passed("lattice", ECS)), PassedRoles.of("ecs:UpdateService",
                json("{\"vpcLatticeConfigurations\":[{\"roleArn\":\"" + ROLES + "lattice\"}]}"), LOOKUPS));
    }

    @Test
    void aTaskSetPassesItsDefinitionsRolesOnly() {
        // The Service Reference gives CreateTaskSet ecs-tasks.amazonaws.com alone.
        String body = "{\"taskDefinition\":\"web:3\",\"loadBalancers\":[{\"advancedConfiguration\":{\"roleArn\":\""
                + ROLES + "lb\"}}]}";
        assertEquals(List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS)),
                PassedRoles.of("ecs:CreateTaskSet", json(body), LOOKUPS));
    }

    @Test
    void aManagedInstancesProviderPassesItsInfrastructureRoleToEcs() {
        String body = "{\"managedInstancesProvider\":{\"infrastructureRoleArn\":\"" + ROLES + "infra\"}}";
        assertEquals(List.of(passed("infra", ECS)), PassedRoles.of("ecs:CreateCapacityProvider", json(body), LOOKUPS));
        assertEquals(List.of(passed("infra", ECS)), PassedRoles.of("ecs:UpdateCapacityProvider", json(body), LOOKUPS));
    }

    @Test
    void stepFunctionsAndEventBridgePassTheRoleTheyName() throws IOException {
        String role = "{\"roleArn\":\"" + ROLES + "sm\"}";
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:CreateStateMachine", json(role), LOOKUPS));
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:UpdateStateMachine", cbor(role), LOOKUPS));
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:CreateStateMachine", gzippedCbor(role), LOOKUPS));
        assertEquals(List.of(passed("rule", "events.amazonaws.com")),
                PassedRoles.of("events:PutRule", json("{\"RoleArn\":\"" + ROLES + "rule\"}"), LOOKUPS));
        assertEquals(List.of(passed("one", "events.amazonaws.com"), passed("two", "events.amazonaws.com")),
                PassedRoles.of("events:PutTargets", json("{\"Targets\":[{\"RoleArn\":\"" + ROLES + "one\"},{},"
                        + "{\"RoleArn\":\"" + ROLES + "two\"}]}"), LOOKUPS));
    }

    @Test
    void schedulerPipesAndLambdaMicrovmsPassTheRoleTheyName() {
        String target = "{\"Target\":{\"RoleArn\":\"" + ROLES + "schedule\"}}";
        assertEquals(List.of(passed("schedule", "scheduler.amazonaws.com")),
                PassedRoles.of("scheduler:CreateSchedule", json(target), LOOKUPS));
        assertEquals(List.of(passed("schedule", "scheduler.amazonaws.com")),
                PassedRoles.of("scheduler:UpdateSchedule", json(target), LOOKUPS));
        String pipe = "{\"RoleArn\":\"" + ROLES + "pipe\"}";
        assertEquals(List.of(passed("pipe", "pipes.amazonaws.com")), PassedRoles.of("pipes:CreatePipe", json(pipe), LOOKUPS));
        assertEquals(List.of(passed("pipe", "pipes.amazonaws.com")), PassedRoles.of("pipes:UpdatePipe", json(pipe), LOOKUPS));
        String build = "{\"buildRoleArn\":\"" + ROLES + "build\"}";
        assertEquals(List.of(passed("build", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:CreateMicrovmImage", json(build), LOOKUPS));
        assertEquals(List.of(passed("build", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:UpdateMicrovmImage", json(build), LOOKUPS));
        assertEquals(List.of(passed("vm", "lambda.amazonaws.com")), PassedRoles.of("lambda:RunMicrovm",
                json("{\"executionRoleArn\":\"" + ROLES + "vm\"}"), LOOKUPS));
        String operator = "{\"OperatorRole\":\"" + ROLES + "operator\"}";
        assertEquals(List.of(passed("operator", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:CreateNetworkConnector", json(operator), LOOKUPS));
        assertEquals(List.of(passed("operator", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:UpdateNetworkConnector", json(operator), LOOKUPS));
    }

    @Test
    void eksPassesItsClusterNodePodAndAddonRolesAndANodeAccessEntrysPrincipal() {
        String eks = "eks.amazonaws.com";
        assertEquals(List.of(passed("cluster", eks)), PassedRoles.of("eks:CreateCluster",
                json("{\"name\":\"c\",\"roleArn\":\"" + ROLES + "cluster\"}"), LOOKUPS));
        assertEquals(List.of(passed("node", eks)), PassedRoles.of("eks:CreateNodegroup",
                json("{\"nodeRole\":\"" + ROLES + "node\"}"), LOOKUPS));
        assertEquals(List.of(passed("fargate", eks)), PassedRoles.of("eks:CreateFargateProfile",
                json("{\"podExecutionRoleArn\":\"" + ROLES + "fargate\"}"), LOOKUPS));
        String addon = """
                {"serviceAccountRoleArn":"%1$saddon","podIdentityAssociations":[
                 {"serviceAccount":"a","roleArn":"%1$spod-a"},{"serviceAccount":"b","roleArn":"%1$spod-b"}]}"""
                .formatted(ROLES);
        for (String action : List.of("eks:CreateAddon", "eks:UpdateAddon")) {
            assertEquals(List.of(passed("addon", eks), passed("pod-a", eks), passed("pod-b", eks)),
                    PassedRoles.of(action, json(addon), LOOKUPS));
        }
        // The association's role goes to Pod Identity; its target role is assumed with it, not passed.
        String association = "{\"roleArn\":\"" + ROLES + "pod\",\"targetRoleArn\":\"" + ROLES + "target\"}";
        for (String action : List.of("eks:CreatePodIdentityAssociation", "eks:UpdatePodIdentityAssociation")) {
            assertEquals(List.of(passed("pod", "pods.eks.amazonaws.com")), PassedRoles.of(action, json(association),
                    LOOKUPS));
        }
        // Only a node access entry passes its principal, to EC2.
        for (String type : List.of("EC2_LINUX", "EC2_WINDOWS")) {
            assertEquals(List.of(passed("worker", EC2)), PassedRoles.of("eks:CreateAccessEntry",
                    json("{\"principalArn\":\"" + ROLES + "worker\",\"type\":\"" + type + "\"}"), LOOKUPS));
        }
        assertEquals(List.of(), PassedRoles.of("eks:CreateAccessEntry",
                json("{\"principalArn\":\"" + ROLES + "admin\",\"type\":\"STANDARD\"}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("eks:CreateAccessEntry",
                json("{\"principalArn\":\"" + ROLES + "admin\"}"), LOOKUPS));
    }

    @Test
    void outsideEcsOnlyARoleArnIsPassed() {
        assertEquals(List.of(), PassedRoles.of("states:CreateStateMachine", json("{\"roleArn\":\"sm\"}"), LOOKUPS));
    }

    @Test
    void aStackOperationPassesTheServiceRoleItNames() {
        for (String action : List.of("CreateStack", "UpdateStack", "DeleteStack", "CreateChangeSet",
                "ContinueUpdateRollback")) {
            assertEquals(List.of(passed("stack", CLOUDFORMATION)),
                    PassedRoles.of("cloudformation:" + action, form("RoleARN=" + ROLES + "stack"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of("cloudformation:" + action, form("StackName=s"), LOOKUPS), action);
        }
    }

    @Test
    void aStackSetPassesTheAdministrationRoleItNamesOrTheDefaultOneWhenSelfManaged() {
        PassedRole defaultRole = new PassedRole(DEFAULT_ADMINISTRATION, CLOUDFORMATION);
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:CreateStackSet", form("StackSetName=new"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:CreateStackSet",
                form("StackSetName=new&PermissionModel=SERVICE_MANAGED"), LOOKUPS));
        assertEquals(List.of(passed("admin", CLOUDFORMATION)), PassedRoles.of("cloudformation:CreateStackSet",
                form("StackSetName=new&AdministrationRoleARN=" + ROLES + "admin"), LOOKUPS));

        // An update goes by the StackSet's own permission model, which a PermissionModel it names doesn't change.
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=own-set"), LOOKUPS));
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=own-set&PermissionModel=SERVICE_MANAGED"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=org-set"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=org-set&PermissionModel=SELF_MANAGED"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=missing"), LOOKUPS));
        assertEquals(List.of(passed("admin", CLOUDFORMATION)), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=org-set&AdministrationRoleARN=" + ROLES + "admin"), LOOKUPS));
    }

    @Test
    void aLaunchConfigurationPassesItsInstanceProfilesRolesToEc2ByNameOrArn() {
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=web-profile"), LOOKUPS));
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=" + PROFILE_ARN), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=missing"), LOOKUPS));
    }

    @Test
    void aLaunchConfigurationMadeFromAnInstancePassesTheInstancesProfileUnlessItNamesOne() {
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-arn"), LOOKUPS));
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-arn&IamInstanceProfile=web-profile"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-none"), LOOKUPS));
    }

    @Test
    void aGroupPassesTheRolesOfEachLaunchTemplatesInstanceProfileToEc2AtTheVersionItNames() {
        for (String action : List.of("CreateAutoScalingGroup", "UpdateAutoScalingGroup")) {
            assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateId=lt-web"), LOOKUPS), action);
            assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateName=arn-template&LaunchTemplate.Version=2"), LOOKUPS), action);
            // A version whose data names no profile passes nothing, nor does a launch configuration.
            assertEquals(List.of(), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateName=arn-template&LaunchTemplate.Version=1"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of("autoscaling:" + action,
                    form("LaunchConfigurationName=c"), LOOKUPS), action);

            // A mixed instances policy names its template, and each override may name another.
            String mixed = "MixedInstancesPolicy.LaunchTemplate.";
            String policy = mixed + "LaunchTemplateSpecification.LaunchTemplateName=arn-template&"
                    + mixed + "LaunchTemplateSpecification.Version=2&"
                    + mixed + "Overrides.member.1.InstanceType=t3.micro&"
                    + mixed + "Overrides.member.2.LaunchTemplateSpecification.LaunchTemplateId=lt-web";
            assertEquals(List.of(passed("arn-instance", EC2), passed("web-instance", EC2)),
                    PassedRoles.of("autoscaling:" + action, form(policy), LOOKUPS), action);
        }
    }

    @Test
    void aGroupPassesAServiceLinkedRoleOtherThanTheDefaultAndItsHooksRolesToAutoScaling() {
        String serviceLinked = "aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling";
        assertEquals(List.of(passed(serviceLinked + "_custom", AUTOSCALING)),
                PassedRoles.of("autoscaling:UpdateAutoScalingGroup",
                        form("ServiceLinkedRoleARN=" + ROLES + serviceLinked + "_custom"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:UpdateAutoScalingGroup",
                form("ServiceLinkedRoleARN=" + ROLES + serviceLinked), LOOKUPS));

        String hooks = "LifecycleHookSpecificationList.member.";
        assertEquals(List.of(passed("web-instance", EC2), passed(serviceLinked + "_custom", AUTOSCALING),
                        passed("hook", AUTOSCALING), passed("other-hook", AUTOSCALING)),
                PassedRoles.of("autoscaling:CreateAutoScalingGroup", form("LaunchTemplate.LaunchTemplateId=lt-web"
                        + "&ServiceLinkedRoleARN=" + ROLES + serviceLinked + "_custom"
                        + "&" + hooks + "1.RoleARN=" + ROLES + "hook&" + hooks + "2.LifecycleHookName=no-role"
                        + "&" + hooks + "3.RoleARN=" + ROLES + "other-hook"
                        + "&" + hooks + "5.RoleARN=" + ROLES + "after-a-gap"), LOOKUPS));
    }

    @Test
    void anInstanceRefreshPassesTheRolesOfTheTemplateItsGroupLaunchesFromOnceTheDesiredConfigurationApplies() {
        // The desired configuration goes to the group's own lookup as given, a version alone or a template alone.
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&DesiredConfiguration.LaunchTemplate.Version=2"), LOOKUPS));
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&DesiredConfiguration.LaunchTemplate.LaunchTemplateName=arn-template"),
                LOOKUPS));
        String desired = "DesiredConfiguration.MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.";
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&" + desired + "LaunchTemplateName=arn-template&" + desired + "Version=2"),
                LOOKUPS));
        // Without a desired configuration the refresh uses the group's own template, checked when the group named it.
        assertEquals(List.of(), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g"), LOOKUPS));
    }

    @Test
    void aLifecycleHookAndAScalableTargetPassTheRoleTheyName() {
        assertEquals(List.of(passed("hook", AUTOSCALING)), PassedRoles.of("autoscaling:PutLifecycleHook",
                form("AutoScalingGroupName=g&LifecycleHookName=h&RoleARN=" + ROLES + "hook"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:PutLifecycleHook",
                form("AutoScalingGroupName=g&LifecycleHookName=h"), LOOKUPS));
        assertEquals(List.of(passed("scaling", "application-autoscaling.amazonaws.com")),
                PassedRoles.of("application-autoscaling:RegisterScalableTarget",
                        json("{\"ServiceNamespace\":\"ecs\",\"RoleARN\":\"" + ROLES + "scaling\"}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("application-autoscaling:RegisterScalableTarget",
                json("{\"ServiceNamespace\":\"ecs\"}"), LOOKUPS));
    }

    @Test
    void aRoleAddedToAnInstanceProfileIsPassedToEc2AsTheArnIamHoldsItUnder() {
        assertEquals(List.of(new PassedRole(ROLES + "app/app-role", EC2)), PassedRoles.of(
                "iam:AddRoleToInstanceProfile", form("InstanceProfileName=p&RoleName=app-role"), LOOKUPS));
        // A role IAM does not hold is checked as the one its name gives.
        assertEquals(List.of(passed("gone", EC2)), PassedRoles.of("iam:AddRoleToInstanceProfile",
                form("InstanceProfileName=p&RoleName=gone"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("iam:AddRoleToInstanceProfile", form("InstanceProfileName=p"), LOOKUPS));
    }

    @Test
    void aRedshiftClusterPassesTheRolesItAssociatesToRedshift() {
        String redshift = "redshift.amazonaws.com";
        // Every list form the Redshift handler reads; the default names a role the cluster holds and passes nothing.
        assertEquals(List.of(passed("copy", redshift), passed("unload", redshift), passed("spectrum", redshift)),
                PassedRoles.of("redshift:CreateCluster", form("ClusterIdentifier=c&IamRoles.IamRoleArn.1=" + ROLES
                        + "copy&IamRoles.member.2=" + ROLES + "unload&IamRoles.5=" + ROLES + "spectrum"
                        + "&DefaultIamRoleArn=" + ROLES + "copy&IamRolesExtra.1=" + ROLES + "other"), LOOKUPS));
        assertEquals(List.of(passed("copy", redshift)), PassedRoles.of("redshift:RestoreFromClusterSnapshot",
                form("ClusterIdentifier=c&SnapshotIdentifier=s&IamRoles.IamRoleArn.1=" + ROLES + "copy"), LOOKUPS));
        // Removing a role, or setting one the cluster holds as the default, passes nothing.
        assertEquals(List.of(passed("added", redshift)), PassedRoles.of("redshift:ModifyClusterIamRoles",
                form("ClusterIdentifier=c&AddIamRoles.IamRoleArn.1=" + ROLES + "added&RemoveIamRoles.IamRoleArn.1="
                        + ROLES + "removed&DefaultIamRoleArn=" + ROLES + "kept"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("redshift:CreateCluster", form("ClusterIdentifier=c"), LOOKUPS));
    }

    @Test
    void aRedshiftServerlessNamespaceAndScheduledActionPassTheirRolesToRedshiftServerless() {
        String serverless = "redshift-serverless.amazonaws.com";
        for (String action : new String[] {"redshift-serverless:CreateNamespace",
                "redshift-serverless:UpdateNamespace"}) {
            assertEquals(List.of(passed("copy", serverless), passed("unload", serverless)), PassedRoles.of(action,
                    json("{\"namespaceName\":\"n\",\"iamRoles\":[\"" + ROLES + "copy\",\"" + ROLES + "unload\"],"
                            + "\"defaultIamRoleArn\":\"" + ROLES + "copy\"}"), LOOKUPS), action);
            // Floci keeps a default the namespace's roles do not hold, so that one is passed too.
            assertEquals(List.of(passed("copy", serverless), passed("other", serverless)), PassedRoles.of(action,
                    json("{\"namespaceName\":\"n\",\"iamRoles\":[\"" + ROLES + "copy\"],"
                            + "\"defaultIamRoleArn\":\"" + ROLES + "other\"}"), LOOKUPS), action);
            assertEquals(List.of(passed("other", serverless)), PassedRoles.of(action,
                    json("{\"namespaceName\":\"n\",\"defaultIamRoleArn\":\"" + ROLES + "other\"}"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of(action, json("{\"namespaceName\":\"n\"}"), LOOKUPS), action);
        }
        for (String action : new String[] {"redshift-serverless:CreateScheduledAction",
                "redshift-serverless:UpdateScheduledAction"}) {
            assertEquals(List.of(passed("snapshots", serverless)), PassedRoles.of(action,
                    json("{\"scheduledActionName\":\"a\",\"roleArn\":\"" + ROLES + "snapshots\"}"), LOOKUPS), action);
        }
    }

    @Test
    void rdsPassesEachRoleARequestNamesForAnInstanceOrClusterToRds() {
        for (String action : new String[] {"rds:AddRoleToDBCluster", "rds:AddRoleToDBInstance",
                "rds:RemoveRoleFromDBCluster", "rds:RemoveRoleFromDBInstance", "rds:CreateDBProxy", "rds:ModifyDBProxy"}) {
            assertEquals(List.of(passed("feature", RDS)), PassedRoles.of(action,
                    form("DBInstanceIdentifier=d&RoleArn=" + ROLES + "feature&FeatureName=s3Import"), LOOKUPS), action);
        }
        assertEquals(List.of(passed("export", RDS)), PassedRoles.of("rds:StartExportTask",
                form("ExportTaskIdentifier=e&IamRoleArn=" + ROLES + "export"), LOOKUPS));
        // The directory role is named without its path, so it is checked on the ARN IAM holds it under.
        assertEquals(List.of(new PassedRole(ROLES + "app/app-role", RDS), passed("monitoring", RDS)),
                PassedRoles.of("rds:CreateDBInstance", form("DBInstanceIdentifier=d&DomainIAMRoleName=app-role"
                        + "&MonitoringRoleArn=" + ROLES + "monitoring"), LOOKUPS));
        assertEquals(List.of(passed("gone", RDS)), PassedRoles.of("rds:ModifyDBCluster",
                form("DBClusterIdentifier=c&DomainIAMRoleName=gone"), LOOKUPS));
        // A cluster's associated roles at any index, in either list form; a restore of an instance takes no
        // monitoring role, and a modify of a cluster no associated roles.
        assertEquals(List.of(passed("monitoring", RDS), passed("s3", RDS), passed("lambda", RDS)),
                PassedRoles.of("rds:RestoreDBClusterToPointInTime", form("DBClusterIdentifier=c&MonitoringRoleArn="
                        + ROLES + "monitoring&AssociatedRoles.DBClusterAssociatedRole.1.RoleArn=" + ROLES
                        + "s3&AssociatedRoles.member.3.RoleArn=" + ROLES + "lambda"), LOOKUPS));
        assertEquals(List.of(passed("gone", RDS)), PassedRoles.of("rds:RestoreDBInstanceFromDBSnapshot",
                form("DBInstanceIdentifier=d&DomainIAMRoleName=gone&MonitoringRoleArn=" + ROLES + "monitoring"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("rds:ModifyDBCluster",
                form("DBClusterIdentifier=c&AssociatedRoles.member.1.RoleArn=" + ROLES + "s3"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("rds:CreateDBCluster", form("DBClusterIdentifier=c"), LOOKUPS));
    }

    @Test
    void anOptionGroupPassesTheRoleEachOptionTakesInItsIamRoleSettingToRds() {
        String backup = "OptionsToInclude.OptionConfiguration.1.";
        String audit = "OptionsToInclude.OptionConfiguration.2.";
        String legacy = "OptionsToInclude.member.1.";
        // Read as the handler reads it: both list forms, up to the first gap, and a setting named twice
        // as its last value.
        assertEquals(List.of(passed("audit", RDS), passed("legacy", RDS)), PassedRoles.of("rds:ModifyOptionGroup",
                form("OptionGroupName=g&" + backup + "OptionName=SQLSERVER_BACKUP_RESTORE&" + backup
                        + "OptionSettings.OptionSetting.2.Name=IAM_ROLE_ARN&" + backup
                        + "OptionSettings.OptionSetting.2.Value=" + ROLES + "after-gap&" + audit
                        + "OptionName=SQLSERVER_AUDIT&" + audit + "OptionSettings.OptionSetting.1.Name=S3_BUCKET_ARN&"
                        + audit + "OptionSettings.OptionSetting.1.Value=" + ROLES + "bucket&" + audit
                        + "OptionSettings.OptionSetting.2.Name=IAM_ROLE_ARN&" + audit
                        + "OptionSettings.OptionSetting.2.Value=" + ROLES + "first&" + audit
                        + "OptionSettings.member.1.Name=IAM_ROLE_ARN&" + audit + "OptionSettings.member.1.Value="
                        + ROLES + "audit&" + legacy + "OptionName=SQLSERVER_BACKUP_RESTORE&" + legacy
                        + "OptionSettings.member.1.Name=IAM_ROLE_ARN&" + legacy + "OptionSettings.member.1.Value="
                        + ROLES + "legacy&OptionsToInclude.OptionConfiguration.4.OptionName=SQLSERVER_AUDIT&"
                        + "OptionsToInclude.OptionConfiguration.4.OptionSettings.OptionSetting.1.Name=IAM_ROLE_ARN&"
                        + "OptionsToInclude.OptionConfiguration.4.OptionSettings.OptionSetting.1.Value=" + ROLES
                        + "option-after-gap"), LOOKUPS));
    }

    @Test
    void dmsPassesItsEndpointsAndReplicationInstancesRolesToDms() {
        String endpoint = """
                {"EndpointIdentifier":"e","ServiceAccessRoleArn":"%1$saccess",
                 "S3Settings":{"ServiceAccessRoleArn":"%1$ss3","BucketName":"b"},
                 "PostgreSQLSettings":{"SecretsManagerAccessRoleArn":"%1$ssecrets"},
                 "OracleSettings":{"SecretsManagerOracleAsmAccessRoleArn":"%1$sasm"},
                 "Tags":[{"Key":"ServiceAccessRoleArn","Value":"%1$stag"}],
                 "FooSettings":{"ServiceAccessRoleArn":"%1$sunknown-structure"},
                 "KafkaSettings":{"ServiceAccessRoleArn":"%1$sno-role-member"},
                 "DynamoDbSettings":{"SecretsManagerAccessRoleArn":"%1$snot-its-member"}}""".formatted(ROLES);
        // Only the role members the DMS model gives each settings structure the handler stores.
        for (String action : new String[] {"dms:CreateEndpoint", "dms:ModifyEndpoint"}) {
            assertEquals(List.of(passed("access", DMS), passed("s3", DMS), passed("secrets", DMS), passed("asm", DMS)),
                    PassedRoles.of(action, json(endpoint), LOOKUPS), action);
        }
        for (String action : new String[] {"dms:CreateReplicationInstance", "dms:ModifyReplicationInstance"}) {
            assertEquals(List.of(passed("key-cache", DMS)), PassedRoles.of(action, json(
                    "{\"KerberosAuthenticationSettings\":{\"KeyCacheSecretIamArn\":\"" + ROLES + "key-cache\"}}"),
                    LOOKUPS), action);
        }
    }

    @Test
    void ssmPassesAnAssociationsDispatchRoleAndACommandsNotificationRoleToSsm() {
        String ssm = "ssm.amazonaws.com";
        // An Automation runbook's assume role is a parameter the runbook names, and a target location's
        // execution role is one in each target account: neither is read.
        String association = "{\"Name\":\"doc\",\"AssociationDispatchAssumeRole\":\"" + ROLES + "dispatch\","
                + "\"Parameters\":{\"AutomationAssumeRole\":[\"" + ROLES + "automation\"]},"
                + "\"TargetLocations\":[{\"ExecutionRoleName\":\"execution\"}]}";
        for (String action : new String[] {"ssm:CreateAssociation", "ssm:UpdateAssociation"}) {
            assertEquals(List.of(passed("dispatch", ssm)), PassedRoles.of(action, json(association), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of(action, json("{\"Name\":\"doc\"}"), LOOKUPS), action);
        }
        assertEquals(List.of(passed("notifications", ssm)), PassedRoles.of("ssm:SendCommand",
                json("{\"DocumentName\":\"AWS-RunShellScript\",\"ServiceRoleArn\":\"" + ROLES + "notifications\","
                        + "\"NotificationConfig\":{\"NotificationArn\":\"arn:aws:sns:us-east-1:111122223333:t\"}}"),
                LOOKUPS));
        // A document's request names no role, and a role is only ever a setting's value.
        assertEquals(List.of(), PassedRoles.of("ssm:CreateDocument",
                json("{\"Name\":\"d\",\"Content\":\"{\\\"assumeRole\\\":\\\"" + ROLES + "runbook\\\"}\"}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("ssm:UpdateServiceSetting",
                json("{\"SettingId\":\"/ssm/managed-instance/default-ec2-instance-management-role\","
                        + "\"SettingValue\":\"service-role/AWSSystemsManagerDefaultEC2InstanceManagementRole\"}"),
                LOOKUPS));
    }

    @Test
    void serviceCatalogPassesTheLaunchRoleAConstraintAnActionOrAProvisionedProductNames() {
        String catalog = "servicecatalog.amazonaws.com";
        assertEquals(List.of(passed("launch", catalog)), PassedRoles.of("servicecatalog:CreateConstraint",
                json(constraint("LAUNCH", "{\\\"RoleArn\\\":\\\"" + ROLES + "launch\\\"}")), LOOKUPS));
        // A local role name carries no path: the ARN IAM holds the role under, or else the one the name gives.
        assertEquals(List.of(new PassedRole(ROLES + "app/app-role", catalog)), PassedRoles.of(
                "servicecatalog:CreateConstraint",
                json(constraint("LAUNCH", "{\\\"LocalRoleName\\\":\\\"app-role\\\"}")), LOOKUPS));
        assertEquals(List.of(passed("gone", catalog)), PassedRoles.of("servicecatalog:CreateConstraint",
                json(constraint("LAUNCH", "{\\\"LocalRoleName\\\":\\\"gone\\\"}")), LOOKUPS));
        // Only a launch constraint names a launch role, and only in a JSON document.
        assertEquals(List.of(), PassedRoles.of("servicecatalog:CreateConstraint",
                json(constraint("NOTIFICATION", "{\\\"RoleArn\\\":\\\"" + ROLES + "launch\\\"}")), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("servicecatalog:CreateConstraint",
                json(constraint("LAUNCH", "RoleArn=" + ROLES + "launch")), LOOKUPS));
        // Floci keeps a constraint's type as given, so a launch constraint is one in any case.
        assertEquals(List.of(passed("launch", catalog)), PassedRoles.of("servicecatalog:CreateConstraint",
                json(constraint("launch", "{\\\"RoleArn\\\":\\\"" + ROLES + "launch\\\"}")), LOOKUPS));

        // An update names no type, so it goes by the constraint's own.
        String parameters = "\"Parameters\":\"{\\\"RoleArn\\\":\\\"" + ROLES + "launch\\\"}\"";
        assertEquals(List.of(passed("launch", catalog)), PassedRoles.of("servicecatalog:UpdateConstraint",
                json("{\"Id\":\"cons-launch\"," + parameters + "}"), LOOKUPS));
        assertEquals(List.of(passed("launch", catalog)), PassedRoles.of("servicecatalog:UpdateConstraint",
                json("{\"Id\":\"cons-lowercase\"," + parameters + "}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("servicecatalog:UpdateConstraint",
                json("{\"Id\":\"cons-notify\"," + parameters + "}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("servicecatalog:UpdateConstraint",
                json("{\"Id\":\"cons-missing\"," + parameters + "}"), LOOKUPS));

        for (String action : new String[] {"servicecatalog:CreateServiceAction", "servicecatalog:UpdateServiceAction"}) {
            assertEquals(List.of(passed("action", catalog)), PassedRoles.of(action,
                    json("{\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\",\"AssumeRole\":\"" + ROLES
                            + "action\"}}"), LOOKUPS), action);
            // LAUNCH_ROLE reuses the provisioned product's launch role, which passes nothing new.
            assertEquals(List.of(), PassedRoles.of(action,
                    json("{\"Definition\":{\"AssumeRole\":\"LAUNCH_ROLE\"}}"), LOOKUPS), action);
        }
        assertEquals(List.of(passed("launch", catalog)), PassedRoles.of(
                "servicecatalog:UpdateProvisionedProductProperties",
                json("{\"ProvisionedProductProperties\":{\"LAUNCH_ROLE\":\"" + ROLES + "launch\","
                        + "\"OWNER\":\"" + ROLES + "owner\"}}"), LOOKUPS));
    }

    @Test
    void secretsManagerBudgetsAndSwfPassTheRolesTheirRequestsName() {
        assertEquals(List.of(passed("rotation", "secretsmanager.amazonaws.com")), PassedRoles.of(
                "secretsmanager:RotateSecret", json("{\"SecretId\":\"s\",\"ExternalSecretRotationRoleArn\":\""
                        + ROLES + "rotation\",\"RotationLambdaARN\":\"arn:aws:lambda:us-east-1:111122223333:"
                        + "function:rotate\"}"), LOOKUPS));
        // The roles an IAM budget action attaches its policy to are what it acts on, not roles it passes.
        String budgetAction = "{\"ExecutionRoleArn\":\"" + ROLES + "execution\",\"Definition\":{\"IamActionDefinition\":"
                + "{\"PolicyArn\":\"arn:aws:iam::111122223333:policy/deny\",\"Roles\":[\"" + ROLES + "target\"]}}}";
        for (String action : new String[] {"budgets:CreateBudgetAction", "budgets:UpdateBudgetAction"}) {
            assertEquals(List.of(passed("execution", "budgets.amazonaws.com")),
                    PassedRoles.of(action, json(budgetAction), LOOKUPS), action);
        }

        String swf = "swf.amazonaws.com";
        assertEquals(List.of(passed("default", swf)), PassedRoles.of("swf:RegisterWorkflowType",
                json("{\"name\":\"w\",\"defaultLambdaRole\":\"" + ROLES + "default\"}"), LOOKUPS));
        assertEquals(List.of(passed("execution", swf)), PassedRoles.of("swf:StartWorkflowExecution",
                json("{\"workflowId\":\"w\",\"lambdaRole\":\"" + ROLES + "execution\"}"), LOOKUPS));
        // Each decision is read from the attributes its type names, as the SWF handler reads it.
        assertEquals(List.of(passed("next", swf), passed("child", swf)), PassedRoles.of(
                "swf:RespondDecisionTaskCompleted", json("{\"taskToken\":\"t\",\"decisions\":["
                        + "{\"decisionType\":\"ContinueAsNewWorkflowExecution\","
                        + "\"continueAsNewWorkflowExecutionDecisionAttributes\":{\"lambdaRole\":\"" + ROLES + "next\"}},"
                        + "{\"decisionType\":\"ScheduleLambdaFunction\","
                        + "\"scheduleLambdaFunctionDecisionAttributes\":{\"id\":\"f\",\"name\":\"f\"}},"
                        + "{\"decisionType\":\"StartChildWorkflowExecution\","
                        + "\"startChildWorkflowExecutionDecisionAttributes\":{\"lambdaRole\":\"" + ROLES + "child\"},"
                        + "\"continueAsNewWorkflowExecutionDecisionAttributes\":{\"lambdaRole\":\"" + ROLES + "unread\"}}]}"),
                LOOKUPS));
    }

    @Test
    void snsPassesTheRolesItsAttributesNameToSns() {
        String sns = "sns.amazonaws.com";
        // Read as the Query handler reads the map: up to the first entry with no key, a key's last value.
        assertEquals(List.of(passed("lambda-success", sns), passed("sqs-failure", sns)), PassedRoles.of("sns:CreateTopic",
                form("Name=t&Attributes.entry.1.key=LambdaSuccessFeedbackRoleArn&Attributes.entry.1.value=" + ROLES
                        + "first&Attributes.entry.2.key=DisplayName&Attributes.entry.2.value=" + ROLES + "display"
                        + "&Attributes.entry.3.key=SQSFailureFeedbackRoleArn&Attributes.entry.3.value=" + ROLES
                        + "sqs-failure&Attributes.entry.4.key=LambdaSuccessFeedbackRoleArn&Attributes.entry.4.value="
                        + ROLES + "lambda-success&Attributes.entry.6.key=HTTPSuccessFeedbackRoleArn"
                        + "&Attributes.entry.6.value=" + ROLES + "after-gap"), LOOKUPS));
        assertEquals(List.of(passed("firehose", sns)), PassedRoles.of("sns:CreateTopic",
                json("{\"Name\":\"t\",\"Attributes\":{\"FirehoseFailureFeedbackRoleArn\":\"" + ROLES
                        + "firehose\",\"Policy\":\"{}\"}}"), LOOKUPS));
        assertEquals(List.of(passed("http", sns)), PassedRoles.of("sns:SetTopicAttributes",
                form("TopicArn=t&AttributeName=HTTPSuccessFeedbackRoleArn&AttributeValue=" + ROLES + "http"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("sns:SetTopicAttributes",
                form("TopicArn=t&AttributeName=DisplayName&AttributeValue=" + ROLES + "http"), LOOKUPS));
        // A role attribute with no value names no role; SNS answers the request.
        assertEquals(List.of(), PassedRoles.of("sns:SetTopicAttributes",
                form("TopicArn=t&AttributeName=HTTPSuccessFeedbackRoleArn"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("sns:SetTopicAttributes",
                json("{\"TopicArn\":\"t\",\"AttributeName\":\"HTTPSuccessFeedbackRoleArn\"}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("sns:SetSubscriptionAttributes",
                form("SubscriptionArn=s&AttributeName=SubscriptionRoleArn"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("sns:SetSubscriptionAttributes",
                json("{\"SubscriptionArn\":\"s\",\"AttributeName\":\"SubscriptionRoleArn\",\"AttributeValue\":null}"),
                LOOKUPS));
        // A subscription's role is its own attribute, not a topic's.
        assertEquals(List.of(passed("subscription", sns)), PassedRoles.of("sns:Subscribe",
                form("TopicArn=t&Protocol=firehose&Attributes.entry.1.key=SubscriptionRoleArn&Attributes.entry.1.value="
                        + ROLES + "subscription"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("sns:Subscribe",
                form("TopicArn=t&Attributes.entry.1.key=LambdaSuccessFeedbackRoleArn&Attributes.entry.1.value="
                        + ROLES + "topic"), LOOKUPS));
        assertEquals(List.of(passed("subscription", sns)), PassedRoles.of("sns:SetSubscriptionAttributes",
                json("{\"SubscriptionArn\":\"s\",\"AttributeName\":\"SubscriptionRoleArn\",\"AttributeValue\":\""
                        + ROLES + "subscription\"}"), LOOKUPS));
        for (String action : new String[] {"sns:CreatePlatformApplication", "sns:SetPlatformApplicationAttributes"}) {
            assertEquals(List.of(passed("platform", sns)), PassedRoles.of(action,
                    form("Name=a&Attributes.entry.1.key=FailureFeedbackRoleArn&Attributes.entry.1.value=" + ROLES
                            + "platform"), LOOKUPS), action);
        }
        assertEquals(List.of(passed("sms", sns)), PassedRoles.of("sns:SetSMSAttributes",
                form("attributes.entry.1.key=DeliveryStatusIAMRole&attributes.entry.1.value=" + ROLES + "sms"), LOOKUPS));
    }

    @Test
    void aLogsSubscriptionFilterPolicyPassesTheRoleItsDocumentNamesToLogs() {
        String document = "\"{\\\"DestinationArn\\\":\\\"arn:aws:kinesis:us-east-1:111122223333:stream/s\\\","
                + "\\\"RoleArn\\\":\\\"" + ROLES + "delivery\\\"}\"";
        assertEquals(List.of(passed("delivery", "logs.amazonaws.com")), PassedRoles.of("logs:PutAccountPolicy",
                json("{\"policyName\":\"p\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"policyDocument\":"
                        + document + "}"), LOOKUPS));
        // Another type of policy names no role, and a document that is not JSON names none either.
        assertEquals(List.of(), PassedRoles.of("logs:PutAccountPolicy",
                json("{\"policyName\":\"p\",\"policyType\":\"FIELD_INDEX_POLICY\",\"policyDocument\":"
                        + document + "}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("logs:PutAccountPolicy",
                json("{\"policyName\":\"p\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"policyDocument\":"
                        + "\"RoleArn=" + ROLES + "delivery\"}"), LOOKUPS));
    }

    @Test
    void theTableReadsARoleInAQueryListAByNameMemberAMapAndOneWithNoNamedService() {
        // SES's receipt rule actions in either list form the Query protocol sends.
        assertEquals(List.of(passed("connect", "ses.amazonaws.com"), passed("s3", "ses.amazonaws.com")),
                PassedRoles.of("ses:CreateReceiptRule", form("RuleSetName=r&Rule.Name=n"
                        + "&Rule.Actions.member.1.S3Action.IamRoleArn=" + ROLES + "s3"
                        + "&Rule.Actions.2.ConnectAction.IAMRoleARN=" + ROLES + "connect"), LOOKUPS));
        // Glue takes a job's role by name, which is the role IAM holds under it, path included.
        assertEquals(List.of(new PassedRole(ROLES + "app/app-role", "glue.amazonaws.com")),
                PassedRoles.of("glue:CreateJob", json("{\"Name\":\"j\",\"Role\":\"app-role\"}"), LOOKUPS));
        assertEquals(List.of(passed("gone", "glue.amazonaws.com")),
                PassedRoles.of("glue:CreateJob", json("{\"Name\":\"j\",\"Role\":\"gone\"}"), LOOKUPS));
        // An identity pool's roles are the values of a map, which the Service Reference passes to no service.
        assertEquals(List.of(new PassedRole(ROLES + "authenticated", null)), PassedRoles.of(
                "cognito-identity:SetIdentityPoolRoles", json("{\"IdentityPoolId\":\"p\",\"Roles\":{\"authenticated\":\""
                        + ROLES + "authenticated\"}}"), LOOKUPS));
        // A member taking only an ARN passes nothing for a name.
        assertEquals(List.of(), PassedRoles.of("codebuild:CreateProject",
                json("{\"name\":\"p\",\"serviceRole\":\"app-role\"}"), LOOKUPS));
    }

    private static String constraint(String type, String parameters) {
        return "{\"PortfolioId\":\"port\",\"ProductId\":\"prod\",\"Type\":\"" + type + "\",\"Parameters\":\""
                + parameters + "\"}";
    }

    private static PassedRole passed(String roleName, String service) {
        return new PassedRole(ROLES + roleName, service);
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext cbor(String body) throws IOException {
        return request("application/cbor",
                new ObjectMapper(new CBORFactory()).writeValueAsBytes(new ObjectMapper().readTree(body)));
    }

    private static ContainerRequestContext gzippedCbor(String body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
            out.write(new ObjectMapper(new CBORFactory()).writeValueAsBytes(new ObjectMapper().readTree(body)));
        }
        ContainerRequestContext request = request("application/cbor", compressed.toByteArray());
        when(request.getHeaderString("Content-Encoding")).thenReturn("gzip");
        return request;
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext request(String mediaType, byte[] body) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(body));
        return request;
    }
}
