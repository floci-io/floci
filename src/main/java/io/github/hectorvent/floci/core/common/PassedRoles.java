package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The IAM roles a request passes to a service, which the caller also needs {@code iam:PassRole} on.
 * The IAM user guide: "To pass a role (and its permissions) to an AWS service, a user must have
 * permissions to pass the role to the service." AWS's Service Reference names, for each operation
 * that passes one, the service the role goes to, which the check offers as {@code iam:PassedToService};
 * where the request carries the role differs per operation.
 *
 * <p>Most operations name their roles in plain request members, which {@link PassedRoleMembers} reads
 * from a table generated from the Service Reference and botocore's models. A member that takes a role
 * by name is read as the role IAM holds under that name, or else the one the name gives in the
 * request's account, and a role the Service Reference passes to no named service is checked with no
 * {@code iam:PassedToService}. The operations below are read here instead, since their roles are not
 * such a member.
 *
 * <p>An EC2 request names an instance profile rather than a role, and passes the roles the profile
 * holds. Launching from a launch template passes nothing here: the EC2 user guide warns that a user
 * who can create launch template versions "can potentially update a launch template to pass an IAM
 * role to an instance even if they don't have the iam:PassRole permission", so the role is checked
 * where the template is created.
 *
 * <p>IAM's {@code AddRoleToInstanceProfile} passes the role it adds to {@code ec2.amazonaws.com}, since
 * every instance running with the profile then gets that role's credentials: "The caller of this
 * operation must be granted the PassRole permission on the IAM role by a permissions policy." The
 * request names the role without its path, so it is checked on the ARN IAM holds the role under.
 *
 * <p>An Auto Scaling group is different: the EC2 Auto Scaling user guide has the permission "needed on
 * the IAM identity (user or role) that creates or updates an Auto Scaling group using a launch template
 * that specifies an instance profile", and an instance refresh to a desired configuration needs it
 * the same way. A group request passes the roles of the instance profile in each launch template it
 * names, at the version the group launches from, to {@code ec2.amazonaws.com}; a refresh's desired
 * configuration is read merged with the group, as Auto Scaling applies it. A launch configuration
 * passes its profile's roles where it is created, the one it names or else the one of the instance it
 * is made from. A service-linked role the request names and a lifecycle hook's role go
 * to {@code autoscaling.amazonaws.com}, except the default service-linked role: the permission is
 * "Needed when a nondefault service-linked role, an IAM role for a lifecycle hook, or a launch
 * template that specifies an instance profile ... is provided".
 *
 * <p>An ECS request that starts tasks from a task definition passes the definition's task role and
 * task execution role: the ECS developer guide has the role EventBridge runs scheduled tasks with
 * hold {@code iam:PassRole} "for each task execution role, task role, or task role override". A role
 * override is checked as well, not instead, since Floci runs a task with its definition's task role.
 * ECS infrastructure roles go to {@code ecs.amazonaws.com} ("To use an ECS infrastructure IAM role,
 * you must grant your user permission to pass the role to Amazon ECS"), task roles to
 * {@code ecs-tasks.amazonaws.com}.
 *
 * <p>An EKS request passes the cluster's {@code roleArn}, a node group's {@code nodeRole}, a Fargate
 * profile's {@code podExecutionRoleArn}, and an add-on's service account role and the role of each Pod
 * Identity association it makes, to {@code eks.amazonaws.com}. A Pod Identity association passes its
 * {@code roleArn} to {@code pods.eks.amazonaws.com}: the EKS user guide has "The IAM principal that is
 * creating the association must have iam:PassRole." Its target role is not passed but assumed with the
 * association's role: "Pod Identity then automatically performs two role assumptions in sequence: first
 * assuming the EKS Pod Identity role, then using those credentials to assume the Target IAM Role." An
 * access entry passes its principal to {@code ec2.amazonaws.com} for the node types alone: "If you create
 * an access entry with type EC2_LINUX or EC2_WINDOWS, the IAM principal creating the access entry must
 * have the iam:PassRole permission."
 *
 * <p>A CloudFormation stack's service role is checked where a request names it, and not on later
 * operations that reuse it: CloudFormation "uses this role even if the users don't have permission
 * to pass it". A self-managed StackSet that names no administration role passes the default one:
 * "Even when you don't specify the AdministrationRoleARN parameter, the IAM principal calling
 * CreateStackSet or UpdateStackSet must have iam:PassRole permission for the
 * AWSCloudFormationStackSetAdministrationRole role."
 *
 * <p>A Redshift request passes the roles it associates with a cluster, to {@code redshift.amazonaws.com}:
 * the Redshift management guide has "To associate an IAM role with a cluster, a user must have
 * iam:PassRole permission for that IAM role." A role a request names only as the cluster's default
 * passes nothing of its own, since the default is one of the roles the cluster holds ("To set an
 * associated IAM role as the default for the cluster"). A Redshift Serverless namespace's roles and a
 * scheduled action's role go to {@code redshift-serverless.amazonaws.com}, and so does a namespace's
 * default, which Floci keeps whether or not the namespace's roles hold it. Redshift takes a role only
 * under the ARN IAM holds it under, path included, so the ARN as the request names it is the role it
 * passes.
 *
 * <p>An RDS request passes to {@code rds.amazonaws.com} every role it names for the instance or cluster:
 * the role it adds or removes, a proxy's role, an export task's role, the Enhanced Monitoring role (the
 * RDS user guide: "The user that enables Enhanced Monitoring must be granted the PassRole permission."),
 * the directory role it names by name, a cluster's associated roles, and the role an option takes in its
 * {@code IAM_ROLE_ARN} setting, as SQL Server's backup and audit options do. A DMS endpoint passes to
 * {@code dms.amazonaws.com} its service access role and the roles its settings name for each engine, and
 * a replication instance the role its Kerberos settings name.
 *
 * <p>An SNS topic's delivery status roles are attributes, which "are used to give Amazon SNS write access
 * to use CloudWatch Logs on your behalf": {@code <ENDPOINT>SuccessFeedbackRoleArn} and
 * {@code <ENDPOINT>FailureFeedbackRoleArn} for each kind of endpoint. So are a platform application's
 * {@code SuccessFeedbackRoleArn} and {@code FailureFeedbackRoleArn}, a Firehose subscription's
 * {@code SubscriptionRoleArn} and the SMS {@code DeliveryStatusIAMRole}; all go to {@code sns.amazonaws.com}.
 * A CloudWatch Logs subscription filter policy names its role in its document: "To perform a
 * PutAccountPolicy subscription filter operation for any destination except a Lambda function, you must
 * also have the iam:PassRole permission."
 *
 * <p>A Service Catalog launch constraint names its launch role in its parameters document, by
 * {@code RoleArn} or by {@code LocalRoleName}, which names the role without its path, so it is checked on
 * the ARN IAM holds the role under. An SWF decision that starts a workflow execution names its Lambda role
 * in the attributes its {@code decisionType} names.
 */
final class PassedRoles {

    private static final Logger LOG = Logger.getLogger(PassedRoles.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Reads the CBOR bodies the RPC v2 path carries, which name the role in the same member JSON does. */
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    /** An IAM role ARN, the only resource {@code iam:PassRole} acts on. */
    private static final Pattern ROLE_ARN = Pattern.compile("^arn:[a-z0-9-]+:iam::\\d{12}:role/.+");

    private static final String LAMBDA = ServicePrincipals.of("lambda");
    private static final String EC2 = ServicePrincipals.of("ec2");
    private static final String VPC_FLOW_LOGS = ServicePrincipals.of("vpc-flow-logs");
    private static final String ECS = ServicePrincipals.of("ecs");
    private static final String ECS_TASKS = ServicePrincipals.of("ecs-tasks");
    private static final String STATES = ServicePrincipals.of("states");
    private static final String EVENTS = ServicePrincipals.of("events");
    private static final String SCHEDULER = ServicePrincipals.of("scheduler");
    private static final String PIPES = ServicePrincipals.of("pipes");
    private static final String EKS = ServicePrincipals.of("eks");
    private static final String EKS_PODS = ServicePrincipals.of("pods.eks");
    private static final String CLOUDFORMATION = ServicePrincipals.of("cloudformation");
    private static final String AUTOSCALING = ServicePrincipals.of("autoscaling");
    private static final String APPLICATION_AUTOSCALING = ServicePrincipals.of("application-autoscaling");
    private static final String REDSHIFT = ServicePrincipals.of("redshift");
    private static final String REDSHIFT_SERVERLESS = ServicePrincipals.of("redshift-serverless");
    private static final String RDS = ServicePrincipals.of("rds");
    private static final String DMS = ServicePrincipals.of("dms");
    private static final String SNS = ServicePrincipals.of("sns");
    private static final String LOGS = ServicePrincipals.of("logs");
    private static final String SERVICE_CATALOG = ServicePrincipals.of("servicecatalog");
    private static final String SWF = ServicePrincipals.of("swf");

    /** The topic attributes that name a delivery status role, a success and a failure one per kind of endpoint. */
    private static final Set<String> SNS_TOPIC_ROLES = Set.of(
            "ApplicationSuccessFeedbackRoleArn", "ApplicationFailureFeedbackRoleArn",
            "FirehoseSuccessFeedbackRoleArn", "FirehoseFailureFeedbackRoleArn",
            "HTTPSuccessFeedbackRoleArn", "HTTPFailureFeedbackRoleArn",
            "LambdaSuccessFeedbackRoleArn", "LambdaFailureFeedbackRoleArn",
            "SQSSuccessFeedbackRoleArn", "SQSFailureFeedbackRoleArn");
    private static final Set<String> SNS_SUBSCRIPTION_ROLES = Set.of("SubscriptionRoleArn");
    private static final Set<String> SNS_PLATFORM_ROLES = Set.of("SuccessFeedbackRoleArn", "FailureFeedbackRoleArn");
    private static final Set<String> SNS_SMS_ROLES = Set.of("DeliveryStatusIAMRole");
    private static final String SUBSCRIPTION_FILTER_POLICY = "SUBSCRIPTION_FILTER_POLICY";
    private static final String LAUNCH_CONSTRAINT = "LAUNCH";
    /** The SWF decisions that start a workflow execution, and the member each carries its attributes in. */
    private static final Map<String, String> SWF_EXECUTION_DECISIONS = Map.of(
            "ContinueAsNewWorkflowExecution", "continueAsNewWorkflowExecutionDecisionAttributes",
            "StartChildWorkflowExecution", "startChildWorkflowExecutionDecisionAttributes");

    /** The RDS operations whose request takes a {@code MonitoringRoleArn}. */
    private static final Set<String> RDS_MONITORING_ROLE = Set.of("rds:CreateDBCluster", "rds:CreateDBInstance",
            "rds:CreateDBInstanceReadReplica", "rds:ModifyDBCluster", "rds:ModifyDBInstance",
            "rds:RestoreDBClusterFromSnapshot", "rds:RestoreDBClusterToPointInTime");
    /** The RDS operations whose request takes a cluster's {@code AssociatedRoles}. */
    private static final Set<String> RDS_ASSOCIATED_ROLES = Set.of("rds:CreateDBCluster",
            "rds:RestoreDBClusterFromSnapshot", "rds:RestoreDBClusterToPointInTime");
    /** An associated role's ARN, in either list form the RDS handler reads, at any index. */
    private static final Pattern ASSOCIATED_ROLE =
            Pattern.compile("AssociatedRoles\\.(DBClusterAssociatedRole|member)\\.\\d+\\.RoleArn");
    /** The option setting SQL Server's backup and audit options take their role in. */
    private static final String OPTION_ROLE_SETTING = "IAM_ROLE_ARN";
    private static final String DMS_SERVICE_ACCESS_ROLE = "ServiceAccessRoleArn";
    private static final String DMS_SECRETS_ROLE = "SecretsManagerAccessRoleArn";
    /**
     * The role members of each DMS endpoint settings structure, as the DMS model gives them. Every structure
     * here is one the DMS handler stores; the others ({@code KafkaSettings}, {@code RedisSettings},
     * {@code TimestreamSettings}) name no role.
     */
    private static final Map<String, List<String>> DMS_SETTINGS_ROLES = Map.ofEntries(
            Map.entry("DynamoDbSettings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("S3Settings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("DmsTransferSettings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("KinesisSettings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("ElasticsearchSettings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("NeptuneSettings", List.of(DMS_SERVICE_ACCESS_ROLE)),
            Map.entry("RedshiftSettings", List.of(DMS_SERVICE_ACCESS_ROLE, DMS_SECRETS_ROLE)),
            Map.entry("PostgreSQLSettings", List.of(DMS_SERVICE_ACCESS_ROLE, DMS_SECRETS_ROLE)),
            Map.entry("MySQLSettings", List.of(DMS_SERVICE_ACCESS_ROLE, DMS_SECRETS_ROLE)),
            Map.entry("MongoDbSettings", List.of(DMS_SECRETS_ROLE)),
            Map.entry("OracleSettings", List.of(DMS_SECRETS_ROLE, "SecretsManagerOracleAsmAccessRoleArn")),
            Map.entry("SybaseSettings", List.of(DMS_SECRETS_ROLE)),
            Map.entry("MicrosoftSQLServerSettings", List.of(DMS_SECRETS_ROLE)),
            Map.entry("IBMDb2Settings", List.of(DMS_SECRETS_ROLE)),
            Map.entry("DocDbSettings", List.of(DMS_SECRETS_ROLE)),
            Map.entry("GcpMySQLSettings", List.of(DMS_SECRETS_ROLE)));

    private static final String STACK_SET_ADMINISTRATION_ROLE = "AWSCloudFormationStackSetAdministrationRole";
    private static final String SERVICE_MANAGED = "SERVICE_MANAGED";
    /** The end of the default Auto Scaling service-linked role's ARN; passing that role needs no permission. */
    private static final String DEFAULT_AUTOSCALING_ROLE =
            ":role/aws-service-role/" + AUTOSCALING + "/AWSServiceRoleForAutoScaling";

    /** A role a request passes, and the service principal it is passed to; null when AWS names none. */
    record PassedRole(String roleArn, String passedToService) {
    }

    /** What a request names that holds the roles it passes, looked up in the request's account and Region. */
    interface Lookups {

        /** The ARNs of the roles an EC2 instance profile holds, looked up by its name or its ARN. */
        List<String> instanceProfileRoleArns(String profileName, String profileArn);

        /** The task role and task execution role of the ECS task definition {@code reference} names. */
        List<String> taskDefinitionRoles(String reference);

        /** The permission model of the StackSet named {@code stackSetName}; empty when there is none. */
        Optional<String> stackSetPermissionModel(String stackSetName);

        /**
         * The instance profile, by its ARN or else its name, of the launch template version an Auto Scaling
         * group launches from when it names this template and version; empty when there is none.
         */
        Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version);

        /** The instance profile, by its ARN, a launch configuration made from this EC2 instance takes from it. */
        Optional<String> launchConfigurationInstanceProfile(String instanceId);

        /**
         * The instance profile of the launch template version the group launches from once an instance
         * refresh applies this desired configuration, each part as the request gives it or null.
         */
        Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId, String templateName,
                                                        String version);

        /** The ARN of the role {@code roleName}, a name with any path, names in the request's account. */
        String roleArn(String roleName);

        /** The ARN, path included, of the role IAM holds under {@code roleName}; empty when it holds none. */
        Optional<String> heldRoleArn(String roleName);

        /** The type of the Service Catalog constraint {@code constraintId}; empty when there is none. */
        Optional<String> serviceCatalogConstraintType(String constraintId);
    }

    private PassedRoles() {
    }

    /** The roles a request for {@code action} passes; empty when the operation passes none or the request names none. */
    static List<PassedRole> of(String action, ContainerRequestContext ctx, Lookups lookups) {
        List<PassedRole> byHand = byHand(action, ctx, lookups);
        return byHand != null ? byHand : fromTable(PassedRoleMembers.of(action), ctx, lookups);
    }

    /**
     * The roles a request for {@code action} passes, for an operation read here rather than from the table;
     * null for any other.
     */
    static List<PassedRole> byHand(String action, ContainerRequestContext ctx, Lookups lookups) {
        return switch (action) {
            case "lambda:CreateFunction", "lambda:UpdateFunctionConfiguration" -> fromJson(ctx, LAMBDA, "Role");
            case "lambda:CreateWebFunction" ->
                    fromJson(ctx, LAMBDA, "revisionConfig", "serviceConfig", "executionRoleArn");
            case "lambda:CreateWebFunctionRevision" -> fromJson(ctx, LAMBDA, "serviceConfig", "executionRoleArn");
            case "ec2:RunInstances", "ec2:AssociateIamInstanceProfile", "ec2:ReplaceIamInstanceProfileAssociation" ->
                    fromInstanceProfile(ctx, lookups, "IamInstanceProfile");
            case "ec2:RequestSpotInstances" ->
                    fromInstanceProfile(ctx, lookups, "LaunchSpecification.IamInstanceProfile");
            case "ec2:CreateLaunchTemplate", "ec2:CreateLaunchTemplateVersion" ->
                    fromInstanceProfile(ctx, lookups, "LaunchTemplateData.IamInstanceProfile");
            case "ec2:CreateFlowLogs" -> fromForm(ctx, VPC_FLOW_LOGS, "DeliverLogsPermissionArn");
            case "iam:AddRoleToInstanceProfile" -> instanceProfileRole(ctx, lookups);
            case "ecs:RegisterTaskDefinition" -> ecsTaskDefinition(jsonBody(ctx), lookups);
            case "ecs:RunTask", "ecs:StartTask" -> ecsTask(jsonBody(ctx), lookups);
            case "ecs:CreateService", "ecs:UpdateService" -> ecsService(jsonBody(ctx), lookups);
            case "ecs:CreateTaskSet" -> ecsTaskDefinitionRoles(jsonBody(ctx), lookups);
            case "ecs:CreateCapacityProvider", "ecs:UpdateCapacityProvider" ->
                    ecsRoles(values(jsonBody(ctx), "managedInstancesProvider", "infrastructureRoleArn"), ECS, lookups);
            case "states:CreateStateMachine", "states:UpdateStateMachine" -> fromJson(ctx, STATES, "roleArn");
            case "events:PutRule" -> fromJson(ctx, EVENTS, "RoleArn");
            case "events:PutTargets" -> fromJson(ctx, EVENTS, "Targets", "[]", "RoleArn");
            case "scheduler:CreateSchedule", "scheduler:UpdateSchedule" -> fromJson(ctx, SCHEDULER, "Target", "RoleArn");
            case "pipes:CreatePipe", "pipes:UpdatePipe" -> fromJson(ctx, PIPES, "RoleArn");
            case "eks:CreateCluster" -> fromJson(ctx, EKS, "roleArn");
            case "eks:CreateNodegroup" -> fromJson(ctx, EKS, "nodeRole");
            case "eks:CreateFargateProfile" -> fromJson(ctx, EKS, "podExecutionRoleArn");
            case "eks:CreateAddon", "eks:UpdateAddon" -> eksAddonRoles(jsonBody(ctx));
            case "eks:CreatePodIdentityAssociation", "eks:UpdatePodIdentityAssociation" ->
                    fromJson(ctx, EKS_PODS, "roleArn");
            case "eks:CreateAccessEntry" -> eksNodeAccessEntry(jsonBody(ctx));
            case "lambda:CreateMicrovmImage", "lambda:UpdateMicrovmImage" -> fromJson(ctx, LAMBDA, "buildRoleArn");
            case "lambda:RunMicrovm" -> fromJson(ctx, LAMBDA, "executionRoleArn");
            case "lambda:CreateNetworkConnector", "lambda:UpdateNetworkConnector" -> fromJson(ctx, LAMBDA, "OperatorRole");
            case "cloudformation:CreateStack", "cloudformation:UpdateStack", "cloudformation:DeleteStack",
                 "cloudformation:CreateChangeSet", "cloudformation:ContinueUpdateRollback" ->
                    fromForm(ctx, CLOUDFORMATION, "RoleARN");
            case "cloudformation:CreateStackSet", "cloudformation:UpdateStackSet" ->
                    stackSetAdministrationRole(ctx, lookups, "cloudformation:UpdateStackSet".equals(action));
            case "autoscaling:CreateLaunchConfiguration" -> launchConfiguration(ctx, lookups);
            case "autoscaling:CreateAutoScalingGroup", "autoscaling:UpdateAutoScalingGroup" ->
                    autoScalingGroup(ctx, lookups);
            case "autoscaling:StartInstanceRefresh" -> instanceRefresh(ctx, lookups);
            case "autoscaling:PutLifecycleHook" -> fromForm(ctx, AUTOSCALING, "RoleARN");
            case "application-autoscaling:RegisterScalableTarget" -> fromJson(ctx, APPLICATION_AUTOSCALING, "RoleARN");
            case "redshift:CreateCluster", "redshift:RestoreFromClusterSnapshot" -> redshiftRoles(ctx, "IamRoles");
            case "redshift:ModifyClusterIamRoles" -> redshiftRoles(ctx, "AddIamRoles");
            case "redshift-serverless:CreateNamespace", "redshift-serverless:UpdateNamespace" -> namespaceRoles(ctx);
            case "redshift-serverless:CreateScheduledAction", "redshift-serverless:UpdateScheduledAction" ->
                    fromJson(ctx, REDSHIFT_SERVERLESS, "roleArn");
            case "rds:AddRoleToDBCluster", "rds:AddRoleToDBInstance", "rds:RemoveRoleFromDBCluster",
                 "rds:RemoveRoleFromDBInstance", "rds:CreateDBProxy", "rds:ModifyDBProxy" ->
                    fromForm(ctx, RDS, "RoleArn");
            case "rds:StartExportTask" -> fromForm(ctx, RDS, "IamRoleArn");
            case "rds:CreateDBInstance", "rds:CreateDBInstanceReadReplica", "rds:ModifyDBInstance",
                 "rds:RestoreDBInstanceFromDBSnapshot", "rds:RestoreDBInstanceToPointInTime", "rds:CreateDBCluster",
                 "rds:ModifyDBCluster", "rds:RestoreDBClusterFromSnapshot", "rds:RestoreDBClusterToPointInTime" ->
                    rdsDatabaseRoles(action, ctx, lookups);
            case "rds:ModifyOptionGroup" -> optionGroupRoles(ctx);
            case "dms:CreateEndpoint", "dms:ModifyEndpoint" -> dmsEndpointRoles(jsonBody(ctx));
            case "dms:CreateReplicationInstance", "dms:ModifyReplicationInstance" ->
                    fromJson(ctx, DMS, "KerberosAuthenticationSettings", "KeyCacheSecretIamArn");
            case "sns:CreateTopic" -> snsAttributeRoles(ctx, "Attributes", SNS_TOPIC_ROLES);
            case "sns:SetTopicAttributes" -> snsAttributeRole(ctx, SNS_TOPIC_ROLES);
            case "sns:Subscribe" -> snsAttributeRoles(ctx, "Attributes", SNS_SUBSCRIPTION_ROLES);
            case "sns:SetSubscriptionAttributes" -> snsAttributeRole(ctx, SNS_SUBSCRIPTION_ROLES);
            case "sns:CreatePlatformApplication", "sns:SetPlatformApplicationAttributes" ->
                    snsAttributeRoles(ctx, "Attributes", SNS_PLATFORM_ROLES);
            case "sns:SetSMSAttributes" -> snsAttributeRoles(ctx, "attributes", SNS_SMS_ROLES);
            case "logs:PutAccountPolicy" -> subscriptionFilterPolicyRole(jsonBody(ctx));
            case "servicecatalog:CreateConstraint" -> launchConstraintRoles(jsonBody(ctx), lookups, false);
            case "servicecatalog:UpdateConstraint" -> launchConstraintRoles(jsonBody(ctx), lookups, true);
            case "swf:RespondDecisionTaskCompleted" -> swfDecisionRoles(jsonBody(ctx));
            default -> null;
        };
    }

    /**
     * The roles a request names in the members the table gives for its operation: from its form parameters
     * when it comes in the Query protocol, and from its JSON or CBOR body otherwise.
     */
    private static List<PassedRole> fromTable(List<PassedRoleMembers.Member> members, ContainerRequestContext ctx,
                                              Lookups lookups) {
        if (members == null) {
            return List.of();
        }
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = form.isEmpty() ? jsonBody(ctx) : null;
        List<PassedRole> passed = new ArrayList<>();
        for (PassedRoleMembers.Member member : members) {
            List<String> roles = new ArrayList<>();
            if (!form.isEmpty()) {
                if (member.queryKeys() != null) {
                    form.forEach((key, value) -> {
                        if (member.queryKeys().matcher(key).matches()) {
                            roles.add(value);
                        }
                    });
                }
            } else {
                collect(body, member.steps(), 0, roles);
            }
            passed.addAll(passed(member.byName() ? heldOrNamedRoles(roles, lookups) : roles,
                    member.passedTo() == null ? null : ServicePrincipals.of(member.passedTo())));
        }
        return passed;
    }

    static void collect(JsonNode node, List<PassedRoleMembers.Step> steps, int index, List<String> found) {
        if (node == null) {
            return;
        }
        if (index == steps.size()) {
            if (node.isTextual()) {
                found.add(node.asText());
            }
            return;
        }
        PassedRoleMembers.Step step = steps.get(index);
        JsonNode value = node.get(step.name());
        switch (step.into()) {
            case NONE -> collect(value, steps, index + 1, found);
            case KEY -> collect(value == null ? null : value.get(step.key()), steps, index + 1, found);
            case LIST, MAP -> {
                if (value != null && (step.into() == PassedRoleMembers.Into.LIST ? value.isArray() : value.isObject())) {
                    for (JsonNode element : value) {
                        collect(element, steps, index + 1, found);
                    }
                }
            }
        }
    }

    /** Roles given by ARN or by name: a name is the role IAM holds under it, or else the one it gives. */
    private static List<String> heldOrNamedRoles(List<String> roles, Lookups lookups) {
        List<String> arns = new ArrayList<>();
        for (String role : roles) {
            if (!role.isBlank()) {
                arns.add(role.startsWith("arn:") ? role
                        : lookups.heldRoleArn(role).orElseGet(() -> lookups.roleArn(role)));
            }
        }
        return arns;
    }

    /**
     * The roles among an SNS request's attributes {@code map}, read as the SNS handlers read the map: from a
     * form, each {@code map.entry.N} from 1 up to the first with no key, the last value of a key named twice;
     * from a JSON body, the {@code map} object.
     */
    private static List<PassedRole> snsAttributeRoles(ContainerRequestContext ctx, String map, Set<String> roleAttributes) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!form.isEmpty()) {
            for (int i = 1; form.get(map + ".entry." + i + ".key") != null; i++) {
                attributes.put(form.get(map + ".entry." + i + ".key"), form.get(map + ".entry." + i + ".value"));
            }
        } else {
            JsonNode body = jsonBody(ctx);
            JsonNode values = body == null ? null : body.get(map);
            if (values != null && values.isObject()) {
                values.properties().forEach(entry -> attributes.put(entry.getKey(), entry.getValue().asText(null)));
            }
        }
        List<String> roles = new ArrayList<>();
        attributes.forEach((name, value) -> {
            if (roleAttributes.contains(name)) {
                roles.add(value);
            }
        });
        return passed(roles, SNS);
    }

    /**
     * The role an SNS {@code AttributeValue} names, when its {@code AttributeName} is one that names a role; a
     * request with no value names none, and is left to SNS to answer.
     */
    private static List<PassedRole> snsAttributeRole(ContainerRequestContext ctx, Set<String> roleAttributes) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String name;
        String value;
        if (!form.isEmpty()) {
            name = form.get("AttributeName");
            value = form.get("AttributeValue");
        } else {
            JsonNode body = jsonBody(ctx);
            name = body == null ? null : body.path("AttributeName").asText(null);
            value = body == null ? null : body.path("AttributeValue").asText(null);
        }
        if (name == null || value == null || !roleAttributes.contains(name)) {
            return List.of();
        }
        return passed(List.of(value), SNS);
    }

    /** The role a CloudWatch Logs subscription filter policy's document names; another policy names none. */
    private static List<PassedRole> subscriptionFilterPolicyRole(JsonNode body) {
        if (body == null || !SUBSCRIPTION_FILTER_POLICY.equals(body.path("policyType").asText(null))) {
            return List.of();
        }
        return passed(values(embeddedJson(body.get("policyDocument")), "RoleArn"), LOGS);
    }

    /**
     * The launch role a Service Catalog launch constraint names in its {@code Parameters}, a JSON document,
     * by {@code RoleArn} or by {@code LocalRoleName}, which names the role of that name in the caller's
     * account without its path. A constraint of another type names no launch role. An update is read by
     * the type Floci keeps for the constraint, since its request names none. The type is matched in any
     * case, since Floci keeps it as given.
     */
    private static List<PassedRole> launchConstraintRoles(JsonNode body, Lookups lookups, boolean update) {
        JsonNode parameters = body == null ? null : embeddedJson(body.get("Parameters"));
        if (parameters == null) {
            return List.of();
        }
        String type;
        if (update) {
            String id = blankToNull(body.path("Id").asText(null));
            type = id == null ? null : lookups.serviceCatalogConstraintType(id).orElse(null);
        } else {
            type = body.path("Type").asText(null);
        }
        if (!LAUNCH_CONSTRAINT.equalsIgnoreCase(type)) {
            return List.of();
        }
        List<String> roles = new ArrayList<>(values(parameters, "RoleArn"));
        for (String name : values(parameters, "LocalRoleName")) {
            if (!name.isBlank()) {
                roles.add(lookups.heldRoleArn(name).orElseGet(() -> lookups.roleArn(name)));
            }
        }
        return passed(roles, SERVICE_CATALOG);
    }

    /**
     * The roles of the decisions that start a workflow execution, each read from the attributes its
     * {@code decisionType} names, as the SWF handler reads a decision.
     */
    private static List<PassedRole> swfDecisionRoles(JsonNode body) {
        JsonNode decisions = body == null ? null : body.get("decisions");
        if (decisions == null || !decisions.isArray()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (JsonNode decision : decisions) {
            String attributes = SWF_EXECUTION_DECISIONS.get(decision.path("decisionType").asText(""));
            if (attributes != null) {
                roles.addAll(values(decision, attributes, "lambdaRole"));
            }
        }
        return passed(roles, SWF);
    }

    /** The JSON document a string member holds; null when the member holds no JSON. */
    private static JsonNode embeddedJson(JsonNode member) {
        if (member == null || !member.isTextual()) {
            return null;
        }
        try {
            return MAPPER.readTree(member.asText());
        } catch (JsonProcessingException e) {
            // A document that is not JSON has no member to name a role in.
            LOG.debugv(e, "Embedded document is not JSON, so it passes no role");
            return null;
        }
    }

    /**
     * The role AddRoleToInstanceProfile adds, which {@code RoleName} names without its path: the ARN IAM
     * holds it under, or else the one the name gives, so a role IAM does not hold is checked as named.
     */
    private static List<PassedRole> instanceProfileRole(ContainerRequestContext ctx, Lookups lookups) {
        String name = blankToNull(IamConditionContextResolver.formParameters(ctx).get("RoleName"));
        return name == null
                ? List.of() : passed(List.of(lookups.heldRoleArn(name).orElseGet(() -> lookups.roleArn(name))), EC2);
    }

    private static List<PassedRole> ecsTaskDefinition(JsonNode body, Lookups lookups) {
        List<String> roles = new ArrayList<>(values(body, "taskRoleArn"));
        roles.addAll(values(body, "executionRoleArn"));
        return ecsRoles(roles, ECS_TASKS, lookups);
    }

    /** RunTask and StartTask: the task definition's roles and its overrides to the task, and the volumes' infrastructure role. */
    private static List<PassedRole> ecsTask(JsonNode body, Lookups lookups) {
        List<String> taskRoles = new ArrayList<>(taskDefinitionRoles(body, lookups));
        taskRoles.addAll(values(body, "overrides", "taskRoleArn"));
        taskRoles.addAll(values(body, "overrides", "executionRoleArn"));
        List<PassedRole> passed = new ArrayList<>(ecsRoles(taskRoles, ECS_TASKS, lookups));
        passed.addAll(ecsRoles(values(body, "volumeConfigurations", "[]", "managedEBSVolume", "roleArn"), ECS, lookups));
        return passed;
    }

    /** CreateService and UpdateService: the task definition's roles, and the infrastructure roles the service uses. */
    private static List<PassedRole> ecsService(JsonNode body, Lookups lookups) {
        List<String> infrastructure = new ArrayList<>(values(body, "role"));
        infrastructure.addAll(values(body, "loadBalancers", "[]", "advancedConfiguration", "roleArn"));
        infrastructure.addAll(values(body, "deploymentConfiguration", "lifecycleHooks", "[]", "roleArn"));
        infrastructure.addAll(values(body, "serviceConnectConfiguration", "services", "[]", "tls", "roleArn"));
        infrastructure.addAll(values(body, "volumeConfigurations", "[]", "managedEBSVolume", "roleArn"));
        infrastructure.addAll(values(body, "vpcLatticeConfigurations", "[]", "roleArn"));
        List<PassedRole> passed = new ArrayList<>(ecsTaskDefinitionRoles(body, lookups));
        passed.addAll(ecsRoles(infrastructure, ECS, lookups));
        return passed;
    }

    private static List<PassedRole> ecsTaskDefinitionRoles(JsonNode body, Lookups lookups) {
        return ecsRoles(taskDefinitionRoles(body, lookups), ECS_TASKS, lookups);
    }

    /** The roles of the task definition the request names; none when it names none. */
    private static List<String> taskDefinitionRoles(JsonNode body, Lookups lookups) {
        List<String> reference = values(body, "taskDefinition");
        return reference.isEmpty() || reference.get(0).isBlank()
                ? List.of() : lookups.taskDefinitionRoles(reference.get(0));
    }

    /**
     * ECS takes a role by its name as well as by its ARN ({@code taskRoleArn} is "The short name or
     * full Amazon Resource Name (ARN) of the IAM role"), and a name is the role in the request's account.
     */
    private static List<PassedRole> ecsRoles(List<String> roles, String service, Lookups lookups) {
        List<String> arns = new ArrayList<>();
        for (String role : roles) {
            if (!role.isBlank()) {
                arns.add(role.startsWith("arn:") ? role : lookups.roleArn(role));
            }
        }
        return passed(arns, service);
    }

    /** An EKS add-on's service account role and the role of each Pod Identity association it makes. */
    private static List<PassedRole> eksAddonRoles(JsonNode body) {
        List<String> roles = new ArrayList<>(values(body, "serviceAccountRoleArn"));
        roles.addAll(values(body, "podIdentityAssociations", "[]", "roleArn"));
        return passed(roles, EKS);
    }

    /** An EKS access entry's principal, when its type is one whose nodes run as that role. */
    private static List<PassedRole> eksNodeAccessEntry(JsonNode body) {
        List<String> type = values(body, "type");
        return type.contains("EC2_LINUX") || type.contains("EC2_WINDOWS")
                ? passed(values(body, "principalArn"), EC2) : List.of();
    }

    /**
     * The administration role a StackSet operation passes: the one it names, or else, unless the
     * StackSet uses service-managed permissions, the default one. An update goes by the StackSet's
     * own permission model, which Floci keeps whatever the request names, and one of a StackSet that
     * does not exist passes nothing.
     */
    private static List<PassedRole> stackSetAdministrationRole(ContainerRequestContext ctx, Lookups lookups,
                                                               boolean update) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String named = blankToNull(form.get("AdministrationRoleARN"));
        if (named != null) {
            return passed(List.of(named), CLOUDFORMATION);
        }
        String model = blankToNull(form.get("PermissionModel"));
        if (update) {
            String name = blankToNull(form.get("StackSetName"));
            Optional<String> stored = name == null ? Optional.empty() : lookups.stackSetPermissionModel(name);
            if (stored.isEmpty()) {
                return List.of();
            }
            model = stored.get();
        }
        return SERVICE_MANAGED.equals(model)
                ? List.of() : passed(List.of(lookups.roleArn(STACK_SET_ADMINISTRATION_ROLE)), CLOUDFORMATION);
    }

    /** The roles of the profile a launch configuration names, or else takes from the instance it is made from. */
    private static List<PassedRole> launchConfiguration(ContainerRequestContext ctx, Lookups lookups) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String profile = blankToNull(form.get("IamInstanceProfile"));
        String instanceId = blankToNull(form.get("InstanceId"));
        if (profile == null && instanceId != null) {
            profile = lookups.launchConfigurationInstanceProfile(instanceId).orElse(null);
        }
        return fromInstanceProfile(lookups, profile);
    }

    /**
     * StartInstanceRefresh: the roles of the launch template the group launches from once the desired
     * configuration is applied to it, which may name only a version of the group's template or keep the
     * group's version for a template it names, and those of its mixed instances policy's templates.
     */
    private static List<PassedRole> instanceRefresh(ContainerRequestContext ctx, Lookups lookups) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String desired = "DesiredConfiguration.LaunchTemplate.";
        List<PassedRole> passed = new ArrayList<>();
        String group = blankToNull(form.get("AutoScalingGroupName"));
        if (group != null) {
            lookups.instanceRefreshInstanceProfile(group, form.get(desired + "LaunchTemplateId"),
                            form.get(desired + "LaunchTemplateName"), form.get(desired + "Version"))
                    .ifPresent(profile -> passed.addAll(fromInstanceProfile(lookups, profile)));
        }
        passed.addAll(mixedInstancesPolicyRoles(form, "DesiredConfiguration.", lookups));
        return passed;
    }

    /**
     * CreateAutoScalingGroup and UpdateAutoScalingGroup: the roles of the launch templates' instance
     * profiles, and a service-linked role and the lifecycle hooks' roles.
     */
    private static List<PassedRole> autoScalingGroup(ContainerRequestContext ctx, Lookups lookups) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        List<String> roles = new ArrayList<>();
        String serviceLinkedRole = blankToNull(form.get("ServiceLinkedRoleARN"));
        if (serviceLinkedRole != null && !serviceLinkedRole.endsWith(DEFAULT_AUTOSCALING_ROLE)) {
            roles.add(serviceLinkedRole);
        }
        for (String hook : members(form, "LifecycleHookSpecificationList")) {
            roles.add(form.get(hook + "RoleARN"));
        }
        List<PassedRole> passed = new ArrayList<>(launchTemplateRoles(form, "LaunchTemplate.", lookups));
        passed.addAll(mixedInstancesPolicyRoles(form, "", lookups));
        passed.addAll(passed(roles, AUTOSCALING));
        return passed;
    }

    /** The roles of the mixed instances policy's launch template under {@code prefix}, and of each override's. */
    private static List<PassedRole> mixedInstancesPolicyRoles(Map<String, String> form, String prefix,
                                                              Lookups lookups) {
        String mixed = prefix + "MixedInstancesPolicy.LaunchTemplate.";
        List<PassedRole> passed = new ArrayList<>(launchTemplateRoles(form, mixed + "LaunchTemplateSpecification.",
                lookups));
        for (String override : members(form, mixed + "Overrides")) {
            passed.addAll(launchTemplateRoles(form, override + "LaunchTemplateSpecification.", lookups));
        }
        return passed;
    }

    /** The roles of the instance profile in the launch template a request names under {@code template}. */
    private static List<PassedRole> launchTemplateRoles(Map<String, String> form, String template, Lookups lookups) {
        String id = blankToNull(form.get(template + "LaunchTemplateId"));
        String name = blankToNull(form.get(template + "LaunchTemplateName"));
        if (id == null && name == null) {
            return List.of();
        }
        return lookups.launchTemplateInstanceProfile(id, name, blankToNull(form.get(template + "Version")))
                .map(profile -> fromInstanceProfile(lookups, profile))
                .orElse(List.of());
    }

    /**
     * The prefix of each {@code list}.member.N, from 1 up to the first N the form holds nothing under,
     * as the handlers read numbered members.
     */
    private static List<String> members(Map<String, String> form, String list) {
        List<String> members = new ArrayList<>();
        for (int i = 1; ; i++) {
            String member = list + ".member." + i + ".";
            if (form.keySet().stream().noneMatch(key -> key.startsWith(member))) {
                return members;
            }
            members.add(member);
        }
    }

    /**
     * The roles a Redshift request lists under {@code list}, every member the Redshift handler reads: the
     * list's shape names them {@code IamRoleArn}.
     */
    private static List<PassedRole> redshiftRoles(ContainerRequestContext ctx, String list) {
        Pattern member = AwsQueryLists.memberKeys(list, "IamRoleArn");
        List<String> roles = new ArrayList<>();
        IamConditionContextResolver.formParameters(ctx).forEach((key, value) -> {
            if (member.matcher(key).matches()) {
                roles.add(value);
            }
        });
        return passed(roles, REDSHIFT);
    }

    /** A Redshift Serverless namespace's roles, and its default when they do not hold it. */
    private static List<PassedRole> namespaceRoles(ContainerRequestContext ctx) {
        JsonNode body = jsonBody(ctx);
        List<String> roles = new ArrayList<>(values(body, "iamRoles", "[]"));
        for (String defaultRole : values(body, "defaultIamRoleArn")) {
            if (!roles.contains(defaultRole)) {
                roles.add(defaultRole);
            }
        }
        return passed(roles, REDSHIFT_SERVERLESS);
    }

    /**
     * The roles an RDS instance or cluster request names: the directory role {@code DomainIAMRoleName}
     * names without its path, which is checked on the ARN IAM holds it under as
     * {@link #instanceProfileRole} checks a role name; and the {@code MonitoringRoleArn} and the
     * {@code AssociatedRoles} where the operation takes them, every associated role at any index, as the RDS
     * handler reads them.
     */
    private static List<PassedRole> rdsDatabaseRoles(String action, ContainerRequestContext ctx, Lookups lookups) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        List<String> roles = new ArrayList<>();
        String directoryRole = blankToNull(form.get("DomainIAMRoleName"));
        if (directoryRole != null) {
            roles.add(lookups.heldRoleArn(directoryRole).orElseGet(() -> lookups.roleArn(directoryRole)));
        }
        if (RDS_MONITORING_ROLE.contains(action)) {
            roles.add(form.get("MonitoringRoleArn"));
        }
        if (RDS_ASSOCIATED_ROLES.contains(action)) {
            form.forEach((key, value) -> {
                if (ASSOCIATED_ROLE.matcher(key).matches()) {
                    roles.add(value);
                }
            });
        }
        return passed(roles, RDS);
    }

    /**
     * The role each option of a ModifyOptionGroup request takes in its {@code IAM_ROLE_ARN} setting, read
     * as the RDS handler reads {@code OptionsToInclude}: in either list form, each option from 1 up to the
     * first with no {@code OptionName}, each setting up to the first with no {@code Name}, and of an option
     * naming the setting twice the last.
     */
    private static List<PassedRole> optionGroupRoles(ContainerRequestContext ctx) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        List<String> roles = new ArrayList<>();
        for (String options : List.of("OptionsToInclude.OptionConfiguration.", "OptionsToInclude.member.")) {
            for (int option = 1; form.get(options + option + ".OptionName") != null; option++) {
                String role = null;
                for (String settings : List.of(".OptionSettings.OptionSetting.", ".OptionSettings.member.")) {
                    String setting = options + option + settings;
                    for (int index = 1; form.get(setting + index + ".Name") != null; index++) {
                        if (OPTION_ROLE_SETTING.equals(form.get(setting + index + ".Name"))) {
                            role = form.get(setting + index + ".Value");
                        }
                    }
                }
                roles.add(role);
            }
        }
        return passed(roles, RDS);
    }

    /** A DMS endpoint's service access role, and the role members of each engine's settings structure it sets. */
    private static List<PassedRole> dmsEndpointRoles(JsonNode body) {
        List<String> roles = new ArrayList<>(values(body, DMS_SERVICE_ACCESS_ROLE));
        if (body != null) {
            for (Map.Entry<String, JsonNode> member : body.properties()) {
                for (String role : DMS_SETTINGS_ROLES.getOrDefault(member.getKey(), List.of())) {
                    roles.addAll(values(member.getValue(), role));
                }
            }
        }
        return passed(roles, DMS);
    }

    private static List<PassedRole> fromJson(ContainerRequestContext ctx, String service, String... path) {
        return passed(values(jsonBody(ctx), path), service);
    }

    /** The strings at {@code path} in {@code node}, where a {@code []} step takes each element of an array. */
    private static List<String> values(JsonNode node, String... path) {
        List<String> found = new ArrayList<>();
        collect(node, path, 0, found);
        return found;
    }

    private static void collect(JsonNode node, String[] path, int step, List<String> found) {
        if (node == null) {
            return;
        }
        if (step == path.length) {
            if (node.isTextual()) {
                found.add(node.asText());
            }
            return;
        }
        if ("[]".equals(path[step])) {
            if (node.isArray()) {
                for (JsonNode element : node) {
                    collect(element, path, step + 1, found);
                }
            }
            return;
        }
        collect(node.get(path[step]), path, step + 1, found);
    }

    private static List<PassedRole> fromForm(ContainerRequestContext ctx, String service, String parameter) {
        String roleArn = IamConditionContextResolver.formParameters(ctx).get(parameter);
        return roleArn == null ? List.of() : passed(List.of(roleArn), service);
    }

    /** The roles of the instance profile named by {@code prefix}.Name or {@code prefix}.Arn. */
    private static List<PassedRole> fromInstanceProfile(ContainerRequestContext ctx, Lookups lookups, String prefix) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String name = blankToNull(form.get(prefix + ".Name"));
        String arn = blankToNull(form.get(prefix + ".Arn"));
        if (name == null && arn == null) {
            return List.of();
        }
        return passed(lookups.instanceProfileRoleArns(name, arn), EC2);
    }

    /** The roles of the instance profile {@code profile}, given by its name or its ARN. */
    private static List<PassedRole> fromInstanceProfile(Lookups lookups, String profile) {
        if (profile == null || profile.isBlank()) {
            return List.of();
        }
        boolean arn = AwsArnUtils.isArn(profile);
        return passed(lookups.instanceProfileRoleArns(arn ? null : profile, arn ? profile : null), EC2);
    }

    /** The roles that are IAM role ARNs; anything else is left for the operation to refuse. */
    private static List<PassedRole> passed(List<String> roleArns, String service) {
        List<PassedRole> passed = new ArrayList<>();
        for (String roleArn : roleArns) {
            if (roleArn != null && ROLE_ARN.matcher(roleArn).matches()) {
                passed.add(new PassedRole(roleArn, service));
            }
        }
        return passed;
    }

    /**
     * The request's JSON or CBOR body, read once and kept where the other request readers look for it.
     * A gzip-encoded body is decompressed first, as {@code AwsJsonCborController} decompresses it before
     * running the request.
     */
    static JsonNode jsonBody(ContainerRequestContext ctx) {
        if (ctx.getProperty(IamConditionContextResolver.BUFFERED_JSON_BODY) instanceof JsonNode cached) {
            return cached;
        }
        byte[] body = IamConditionContextResolver.bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            String encoding = ctx.getHeaderString(HttpHeaders.CONTENT_ENCODING);
            if (encoding != null && AwsJsonCborController.isGZipped(List.of(encoding))) {
                body = AwsJsonCborController.decodeBody(body);
            }
            JsonNode node = (isCbor(ctx.getMediaType()) ? CBOR_MAPPER : MAPPER).readTree(body);
            ctx.setProperty(IamConditionContextResolver.BUFFERED_JSON_BODY, node);
            return node;
        } catch (IOException | AwsException e) {
            // The operation refuses a body it cannot decompress or parse itself; nothing here names a role.
            LOG.debugv(e, "Request body cannot be read, so it passes no role");
            return null;
        }
    }

    /** Both CBOR content types arrive as {@code application/cbor}, as {@code ResourceArnBuilder} reads them. */
    private static boolean isCbor(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && ("cbor".equalsIgnoreCase(mediaType.getSubtype())
                        || "x-amz-cbor-1.1".equalsIgnoreCase(mediaType.getSubtype()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
