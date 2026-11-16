package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestChecks;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser;
import io.floci.aws.services.dynamodb.DynamoDbTableNames;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamAuthorizedActions;
import io.floci.aws.services.iam.ResourceArnBuilder;
import io.floci.aws.services.redshiftdata.RedshiftDataTargetSubmitter;
import io.floci.aws.services.s3.S3ConditionKeys;
import io.floci.aws.services.s3.S3ResourceArns;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a task's call to its integrated service needs from IAM. A request that names no resource
 * needs no permission here: the integrated service refuses it with its own validation error.
 *
 * @param deniedError         the error a refused permission fails the task with: the integration's prefix
 *                            and the exception the integrated service's SDK raises for an access denial
 * @param permissions         the actions the call performs
 * @param checksExecutionRole whether the task also needs its execution role to exist and to trust Step Functions,
 *                            which a nested execution does not, on the maintainer's decision to leave nested and
 *                            Lambda tasks as they are
 */
record TaskPermissions(String deniedError, List<Permission> permissions, boolean checksExecutionRole) {

    private static final Logger LOG = Logger.getLogger(TaskPermissions.class);
    private static final String ANY_RESOURCE = "*";
    static final String RESULT_WRITER_SUCCEEDED = "SUCCEEDED_0.json";
    static final String RESULT_WRITER_FAILED = "FAILED_0.json";
    static final String RESULT_WRITER_MANIFEST = "manifest.json";
    private static final String DYNAMODB_OPTIMIZED_PREFIX = "DynamoDB.";
    private static final String DYNAMODB_SDK_PREFIX = "DynamoDb.";
    private static final String REDSHIFT_DATA_PREFIX = "RedshiftData.";
    private static final String RDS_DATA_PREFIX = "RdsData.";
    private static final String ECS_OPTIMIZED_PREFIX = "ECS.";
    private static final String ECS_SDK_PREFIX = "Ecs.";
    private static final String EMR_PREFIX = "EMR.";
    private static final String EMR_SERVERLESS_PREFIX = "EmrServerless.";
    private static final String LAMBDA_PREFIX = "Lambda.";
    private static final String SECRETS_MANAGER_PREFIX = "SecretsManager.";
    private static final Set<String> DYNAMODB_OPTIMIZED =
            Set.of("putItem", "getItem", "deleteItem", "updateItem", "scan");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * The EMR calls each API makes, in order: a ByName API first lists the cluster's members to find the id, as the
     * CDK's policy for it grants.
     */
    private static final Map<String, List<String>> EMR_CALLS = Map.of(
            "createCluster", List.of("RunJobFlow"),
            "setClusterTerminationProtection", List.of("SetTerminationProtection"),
            "terminateCluster", List.of("TerminateJobFlows"),
            "addStep", List.of("AddJobFlowSteps"),
            "cancelStep", List.of("CancelSteps"),
            "modifyInstanceFleetByName", List.of("ListInstanceFleets", "ModifyInstanceFleet"),
            "modifyInstanceGroupByName", List.of("ListInstanceGroups", "ModifyInstanceGroups"));
    private static final String SCHEDULER_PREFIX = "Scheduler.";
    private static final String CLOUDFORMATION_PREFIX = "CloudFormation.";
    private static final String HTTP_PREFIX = "States.Http.";
    private static final String SFN_SDK_PREFIX = "Sfn.";
    private static final String NESTED_PREFIX = "StepFunctions.";
    private static final Set<String> SCHEDULER = Set.of("createSchedule", "updateSchedule", "deleteSchedule");
    private static final Set<String> SFN_SDK = Set.of("startExecution", "startSyncExecution", "sendTaskSuccess",
            "sendTaskFailure", "describeMapRun");

    TaskPermissions(String deniedError, List<Permission> permissions) {
        this(deniedError, permissions, true);
    }

    /** The permissions of an integration whose refusal is its prefix and {@code AccessDeniedException}. */
    static TaskPermissions accessDenied(String errorPrefix, List<Permission> permissions) {
        return new TaskPermissions(errorPrefix + "AccessDeniedException", permissions);
    }

    /**
     * What finds the resources of a control task's request, reading only.
     *
     * @param cloudFormation the stack or stack set a CloudFormation request names by its name, whose id CloudFormation
     *                       minted; without it one is the ARN its format gives the name, its id open
     * @param secrets        the secret a Secrets Manager request names, random suffix included; without it a secret is
     *                       the ARN its format gives the name
     * @param states         the state machine or activity whose task a callback's token names; without it a token
     *                       names nothing
     */
    record ControlServices(StoredResourceArns cloudFormation, StoredResourceArns secrets,
                           StoredResourceArns states) {
    }

    /** The permissions of a mapped integration, or nothing for one that is not checked. */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine) {
        return of(integration, input, machine, null);
    }

    /**
     * The permissions of a mapped integration, or nothing for one that is not checked.
     *
     * @param redshiftData finds the workgroup or the session target a Data API request names; without it a
     *                     workgroup is any workgroup and a session names nothing
     */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine,
                                        StoredResourceArns redshiftData) {
        return of(integration, input, machine, redshiftData, null, false);
    }

    /**
     * The permissions of a mapped integration, or nothing for one that is not checked.
     *
     * @param redshiftData finds the workgroup or the session target a Data API request names; without it a
     *                     workgroup is any workgroup and a session names nothing
     * @param ecs          finds the task definition revision a reference resolves to and the default cluster, reading
     *                     only; without it a family names any revision and a request naming no cluster any cluster
     * @param callback     whether the state's resource ended in {@code .waitForTaskToken}, which {@code integration}
     *                     no longer shows: EMR and EMR Serverless have no such form, so theirs stays unsupported
     */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine,
                                        StoredResourceArns redshiftData, StoredResourceArns ecs, boolean callback) {
        return of(integration, input, machine, redshiftData, ecs, callback, new ControlServices(null, null, null));
    }

    /**
     * The permissions of a mapped integration, or nothing for one that is not checked.
     *
     * @param redshiftData finds the workgroup or the session target a Data API request names; without it a
     *                     workgroup is any workgroup and a session names nothing
     * @param ecs          finds the task definition revision a reference resolves to and the default cluster, reading
     *                     only; without it a family names any revision and a request naming no cluster any cluster
     * @param callback     whether the state's resource ended in {@code .waitForTaskToken}, which {@code integration}
     *                     no longer shows: EMR and EMR Serverless have no such form, so theirs stays unsupported
     * @param control      finds what a control task's request names
     */
    static Optional<TaskPermissions> of(StatesIntegration integration, JsonNode input, StateMachine machine,
                                        StoredResourceArns redshiftData, StoredResourceArns ecs, boolean callback,
                                        ControlServices control) {
        if (integration.isSdkService("scheduler") && SCHEDULER.contains(integration.api())) {
            return Optional.of(scheduler(integration.api(), input, machine));
        }
        if (integration.isSdkService("cloudformation") && servedByCloudFormation(integration.api())) {
            return Optional.of(cloudFormation(AslExecutor.capitalizeFirst(integration.api()), input, machine,
                    control.cloudFormation()));
        }
        if (integration.is("http", "invoke")) {
            return Optional.of(httpInvoke(input, machine));
        }
        if (integration.isSdk("ec2", "describeRegions")) {
            return Optional.of(ec2DescribeRegions(input, machine));
        }
        if (integration.isSdkService("lambda") && SdkManagementTaskInvoker.supports(integration)) {
            return Optional.of(lambdaSdk(integration.api(), input, machine));
        }
        if (integration.isSdkService("secretsmanager") && SdkManagementTaskInvoker.supports(integration)) {
            return Optional.of(accessDenied(SECRETS_MANAGER_PREFIX,
                    requestPermissions("secretsmanager", "DescribeSecret", input, machine, control.secrets())));
        }
        if (integration.isSdkService("sfn") && SFN_SDK.contains(integration.api())) {
            return Optional.of(sfnSdk(AslExecutor.capitalizeFirst(integration.api()), input, machine, control.states()));
        }
        if (integration.isAnySuffix("states", "startExecution")) {
            return Optional.of(nestedStartExecution(input, machine));
        }
        if (integration.isAnySuffix("ecs", "runTask")
                && (integration.suffix().isEmpty() || ".sync".equals(integration.suffix()))) {
            return Optional.of(accessDenied(ECS_OPTIMIZED_PREFIX, ecs("RunTask", input, machine, ecs)));
        }
        if (integration.isSdkService("ecs") && SdkManagementTaskInvoker.supports(integration)) {
            return Optional.of(accessDenied(ECS_SDK_PREFIX,
                    ecs(AslExecutor.capitalizeFirst(integration.api()), input, machine, ecs)));
        }
        if (!callback && integration.isOptimizedService("elasticmapreduce") && EmrTaskInvoker.serves(integration)) {
            return Optional.of(emr(integration.api(), input, machine));
        }
        if (!callback && integration.isOptimizedService("emr-serverless")
                && EmrServerlessTaskInvoker.serves(integration)) {
            return Optional.of(emrServerless(integration.api(), input, machine));
        }
        if (integration.is("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("SQS.", input, machine));
        }
        if (integration.isSdk("sqs", "sendMessage")) {
            return Optional.of(sqsSendMessage("Sqs.", input, machine));
        }
        if (integration.is("sns", "publish")) {
            return Optional.of(snsPublish("SNS.", input));
        }
        if (integration.isSdk("sns", "publish")) {
            return Optional.of(snsPublish("Sns.", input));
        }
        if (integration.is("events", "putEvents")) {
            return Optional.of(eventsPutEvents(input, machine));
        }
        if (integration.isOptimizedService("dynamodb") && DYNAMODB_OPTIMIZED.contains(integration.api())) {
            return Optional.of(dynamoDbOptimized(integration.api(), input, machine));
        }
        if (integration.isSdkService("dynamodb")) {
            return Optional.of(dynamoDbSdk(AslExecutor.capitalizeFirst(integration.api()), input, machine));
        }
        if (integration.is("s3", "putObject") || integration.isSdk("s3", "putObject")) {
            return Optional.of(s3PutObject(input, machine));
        }
        if (integration.isSdkService("redshiftdata") && integration.suffix().isEmpty()) {
            return Optional.of(redshiftData(AslExecutor.capitalizeFirst(integration.api()), input, machine,
                    redshiftData));
        }
        if (integration.isSdkService("rdsdata") && "executeStatement".equals(integration.api())) {
            return Optional.of(rdsDataExecuteStatement(input));
        }
        return Optional.empty();
    }

    private static TaskPermissions sqsSendMessage(String errorPrefix, JsonNode input, StateMachine machine) {
        JsonNode queueUrl = input.path("QueueUrl");
        if (!queueUrl.isTextual() || queueUrl.asText().isBlank()) {
            return new TaskPermissions(errorPrefix + "AccessDeniedException", List.of());
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return new TaskPermissions(errorPrefix + "AccessDeniedException", List.of(new Permission("sqs:SendMessage",
                SqsService.queueArn(queueUrl.asText(), owner.partition(), owner.region(), owner.accountId()))));
    }

    /**
     * What {@code Publish} sends to, read as its handler reads the members and in the order
     * {@code SnsService.publish} takes them: a phone number before a topic, and a topic before a target.
     * Sending an SMS has no resource to check. A value that is not an SNS ARN reaches nothing, as the
     * lookups need the ARN a topic or an endpoint is stored under, so SNS answers it. A refusal is SNS's
     * {@code AuthorizationError}, which its SDKs raise as {@code AuthorizationErrorException}.
     */
    private static TaskPermissions snsPublish(String errorPrefix, JsonNode input) {
        String deniedError = errorPrefix + "AuthorizationErrorException";
        if (input.path("PhoneNumber").asText(null) != null) {
            return new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", "*")));
        }
        String topicArn = input.path("TopicArn").asText(null);
        String resourceArn = topicArn != null ? topicArn : input.path("TargetArn").asText(null);
        if (!AwsArnUtils.isArnFor(resourceArn, "sns")) {
            return new TaskPermissions(deniedError, List.of());
        }
        return new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", resourceArn)));
    }

    /**
     * One permission for each distinct bus the entries reach, read as {@code PutEvents}' handler reads
     * the member: an entry naming none goes to the default bus. An entry that is not an object or whose
     * member is a malformed ARN reaches no bus, so EventBridge answers it.
     */
    private static TaskPermissions eventsPutEvents(JsonNode input, StateMachine machine) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        Set<String> buses = new LinkedHashSet<>();
        JsonNode entries = input.path("Entries");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (entry.isObject()) {
                    EventBridgeService.eventBusArn(entry.path("EventBusName").asText(null), owner.partition(),
                            owner.region(), owner.accountId()).ifPresent(buses::add);
                }
            }
        }
        return new TaskPermissions("EventBridge.AccessDeniedException",
                buses.stream().map(bus -> new Permission("events:PutEvents", bus)).toList());
    }

    /** The action on the table {@code TableName} gives, or nothing without one, which DynamoDB answers. */
    private static TaskPermissions dynamoDbOptimized(String api, JsonNode input, StateMachine machine) {
        String tableName = input.path("TableName").asText("").trim();
        if (tableName.isEmpty()) {
            return accessDenied(DYNAMODB_OPTIMIZED_PREFIX, List.of());
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return accessDenied(DYNAMODB_OPTIMIZED_PREFIX, List.of(new Permission(
                "dynamodb:" + AslExecutor.capitalizeFirst(api),
                ResourceArnBuilder.toDynamoDbTableArn(tableName, owner.region(), owner.accountId()))));
    }

    /**
     * The check a direct request of the operation gets: a transaction by each item's own action on its table,
     * and anything else by its action on the resources it names, every table of a batch. A value that is not an
     * ARN where the request names a resource by one reaches nothing, so DynamoDB answers it.
     */
    private static TaskPermissions dynamoDbSdk(String operation, JsonNode input, StateMachine machine) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        if ("TransactWriteItems".equals(operation) || "TransactGetItems".equals(operation)) {
            return accessDenied(DYNAMODB_SDK_PREFIX, dynamoDbTransaction(operation, input, owner));
        }
        String namedAs = "dynamodb:" + operation;
        List<String> named = RequestResources.reads(namedAs)
                ? RequestResources.ofBody(namedAs, input, owner.partition(), owner.region(), owner.accountId(), null)
                : ResourceArnBuilder.dynamoDbArns(input, owner.region(), owner.accountId());
        String action = dynamoDbAction(operation, input);
        return accessDenied(DYNAMODB_SDK_PREFIX, named.stream()
                .filter(resource -> ANY_RESOURCE.equals(resource) || AwsArnUtils.isArn(resource))
                .map(resource -> new Permission(action, resource))
                .toList());
    }

    /** {@code ExecuteStatement} is authorized by what its statement does, as a direct request is. */
    private static String dynamoDbAction(String operation, JsonNode input) {
        String statementAction = "ExecuteStatement".equals(operation)
                ? DynamoDbPartiQLParser.iamAction(input.path("Statement").asText(null)) : null;
        return statementAction != null ? statementAction : "dynamodb:" + operation;
    }

    /**
     * Each item's own action on the table it names, resolved within the state machine's account as execution
     * resolves it, the same for a table named by its ARN. An item DynamoDB would refuse, whose table name does not
     * resolve or which names no table, needs nothing here: the service answers for the whole transaction.
     */
    private static List<Permission> dynamoDbTransaction(String operation, JsonNode input, AwsArnUtils.Arn owner) {
        boolean read = "TransactGetItems".equals(operation);
        List<String> kinds = read ? List.of("Get") : List.of("Put", "Delete", "Update", "ConditionCheck");
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of(operation));
        List<Permission> permissions = new ArrayList<>();
        JsonNode items = input.path("TransactItems");
        if (!items.isArray()) {
            return permissions;
        }
        for (JsonNode item : items) {
            for (String kind : kinds) {
                String tableName = item.path(kind).path("TableName").asText("").trim();
                if (tableName.isEmpty()) {
                    continue;
                }
                try {
                    String table = DynamoDbTableNames.resolveWithRegion(tableName, owner.region()).name();
                    permissions.add(new Permission("dynamodb:" + kind + "Item",
                            ResourceArnBuilder.toDynamoDbTableArn(table, owner.region(), owner.accountId()), enclosing));
                } catch (AwsException expected) {
                    LOG.debugv("Transaction item names a table DynamoDB will refuse: {0}", tableName);
                }
            }
        }
        return permissions;
    }

    /**
     * {@code s3:PutObject} on the object, in the state machine's partition: the authorizer moves it to the
     * bucket's own. A request naming no bucket or key is answered by the integration.
     */
    private static TaskPermissions s3PutObject(JsonNode input, StateMachine machine) {
        String bucket = input.path("Bucket").asText("");
        String key = input.path("Key").asText(null);
        if (bucket.isBlank() || key == null) {
            return accessDenied("S3.", List.of());
        }
        return accessDenied("S3.", List.of(new Permission("s3:PutObject", s3ObjectArn(machine, bucket, key))));
    }

    /**
     * What a Distributed Map's {@code ItemReader} needs to read one object: {@code s3:GetObject} on it. A request
     * naming no bucket or key needs nothing, and is answered by the reader or by S3.
     */
    static List<Permission> itemReaderGetObject(String bucket, String key, StateMachine machine) {
        if (bucket == null || bucket.isBlank() || key == null) {
            return List.of();
        }
        return List.of(new Permission("s3:GetObject", s3ObjectArn(machine, bucket, key)));
    }

    /**
     * What a Distributed Map's {@code ItemReader} needs to list a bucket: {@code s3:ListBucket} on it, offering
     * {@code s3:prefix} when the request has a prefix, as a direct listing offers it. A request naming no bucket
     * needs nothing, and is answered by the reader or by S3.
     */
    static List<Permission> itemReaderListObjects(String bucket, String prefix, StateMachine machine) {
        if (bucket == null || bucket.isBlank()) {
            return List.of();
        }
        return List.of(new Permission("s3:ListBucket", s3BucketArn(machine, bucket),
                prefix == null ? Map.of() : Map.of(S3ConditionKeys.PREFIX, List.of(prefix))));
    }

    /**
     * What a Distributed Map's {@code ResultWriter} needs to export: {@code s3:PutObject} on each object it writes,
     * all known before the first is. The failed file is written only when a child failed. A location naming no
     * bucket, or a prefix that is not a string, needs nothing, and is answered by the writer.
     *
     * @param location the resolved {@code Parameters} or {@code Arguments} of the writer
     */
    static List<Permission> resultWriterExport(JsonNode location, String mapRunId, boolean failedChildren,
                                               StateMachine machine) {
        String bucket = text(location.path("Bucket"));
        JsonNode prefix = location.path("Prefix");
        if (bucket == null || !(prefix.isMissingNode() || prefix.isTextual())) {
            return List.of();
        }
        String base = resultWriterBase(prefix.asText(""), mapRunId);
        List<String> files = failedChildren
                ? List.of(RESULT_WRITER_SUCCEEDED, RESULT_WRITER_FAILED, RESULT_WRITER_MANIFEST)
                : List.of(RESULT_WRITER_SUCCEEDED, RESULT_WRITER_MANIFEST);
        return files.stream()
                .map(file -> new Permission("s3:PutObject", s3ObjectArn(machine, bucket, base + file)))
                .toList();
    }

    /** The key every object of a Map run's export starts with: the run id keys it under the prefix given. */
    static String resultWriterBase(String prefix, String mapRunId) {
        return prefix.isEmpty() ? mapRunId + "/" : prefix + (prefix.endsWith("/") ? "" : "/") + mapRunId + "/";
    }

    /** A bucket's ARN in the state machine's partition: the authorizer moves it to the bucket's own. */
    private static String s3BucketArn(StateMachine machine, String bucket) {
        return S3ResourceArns.bucket(AwsArnUtils.parse(machine.getStateMachineArn()).partition(), bucket);
    }

    /** An object's ARN in the state machine's partition: the authorizer moves it to the bucket's own. */
    private static String s3ObjectArn(StateMachine machine, String bucket, String key) {
        return S3ResourceArns.object(AwsArnUtils.parse(machine.getStateMachineArn()).partition(), bucket, key);
    }

    /**
     * The check an EventBridge target of the Data API gets, on what the request names: its cluster, its workgroup
     * or its session's target. An operation on no resource, as statement operations are in the service reference,
     * is checked on {@code *}. A request that names nothing it needs, or an operation the Data API does not
     * have, is answered by the service.
     */
    private static TaskPermissions redshiftData(String operation, JsonNode input, StateMachine machine,
                                                StoredResourceArns stored) {
        String namedAs = "redshift-data:" + operation;
        String action = IamAuthorizedActions.actionFor("redshift-data", operation);
        if (!input.isObject() || !RequestResources.reads(namedAs)) {
            return accessDenied(REDSHIFT_DATA_PREFIX, List.of());
        }
        if (RequestResources.actsOnNoResource(namedAs)) {
            return accessDenied(REDSHIFT_DATA_PREFIX, List.of(new Permission(action, ANY_RESOURCE)));
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        List<Permission> permissions = new ArrayList<>();
        for (String target : RequestResources.ofBody(namedAs, input, owner.partition(), owner.region(),
                owner.accountId(), stored)) {
            RedshiftDataTargetSubmitter.permissions(action, target, input, owner.accountId(), owner.region())
                    .forEach(required -> permissions.add(new Permission(required.action(), required.resourceArn())));
        }
        return accessDenied(REDSHIFT_DATA_PREFIX, permissions);
    }

    /**
     * {@code rds-data:ExecuteStatement} on the cluster and, since the Data API reads the database's credentials
     * from it as the caller, {@code secretsmanager:GetSecretValue} on the secret. The members are read in either
     * casing, as the task's request is recased. A request with no ARN for the cluster is answered by the service.
     */
    private static TaskPermissions rdsDataExecuteStatement(JsonNode input) {
        JsonNode request = AslExecutor.recaseKeys(MAPPER, input, false);
        String resourceArn = request.path("resourceArn").asText(null);
        if (!AwsArnUtils.isArn(resourceArn)) {
            return accessDenied(RDS_DATA_PREFIX, List.of());
        }
        String secretArn = request.path("secretArn").asText(null);
        Permission execute = new Permission("rds-data:ExecuteStatement", resourceArn);
        return accessDenied(RDS_DATA_PREFIX, AwsArnUtils.isArn(secretArn)
                ? List.of(execute, new Permission("secretsmanager:GetSecretValue", secretArn)) : List.of(execute));
    }

    /**
     * The ECS call an API makes, checked as the vendored request resources check the same call made directly, on what
     * its request names, its members read in either casing as the integration recases them: {@code RunTask} on the
     * revision ECS resolves its task definition to, a family alone being its latest, and a service on the cluster the
     * request names, or the default one. {@code ListServices} acts on no resource type. A request that is no object is
     * answered by the service.
     */
    private static List<Permission> ecs(String operation, JsonNode input, StateMachine machine,
                                        StoredResourceArns ecs) {
        return requestPermissions("ecs", operation, AslExecutor.recaseKeys(MAPPER, input, false), machine, ecs);
    }

    /**
     * The check the vendored request resources give the same call made directly, in the state machine's partition,
     * Region and account: the operation's action on each resource the request names, {@code stored} finding the ones
     * only its service knows, or on any resource for an operation that acts on no resource type. A request that is no
     * object, or an operation the table does not read, needs nothing here: the service answers it.
     */
    private static List<Permission> requestPermissions(String namespace, String operation, JsonNode request,
                                                       StateMachine machine, StoredResourceArns stored) {
        String namedAs = namespace + ":" + operation;
        String action = IamAuthorizedActions.actionFor(namespace, operation);
        if (!request.isObject() || !RequestResources.reads(namedAs)) {
            return List.of();
        }
        if (RequestResources.actsOnNoResource(namedAs)) {
            return List.of(new Permission(action, ANY_RESOURCE));
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return RequestResources.ofBody(namedAs, request, owner.partition(), owner.region(), owner.accountId(), stored)
                .stream().map(resource -> new Permission(action, resource)).toList();
    }

    /**
     * The EMR calls the API's {@code start} makes, each checked as the vendored request resources check the same
     * call made directly: the integration finds the cluster by {@code ClusterId} alone, and hands it to each call
     * in the member that call names it by. {@code RunJobFlow} acts on no resource type. A request naming no cluster
     * is refused by the integration.
     */
    private static TaskPermissions emr(String api, JsonNode input, StateMachine machine) {
        String clusterId = text(input.path("ClusterId"));
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        List<Permission> permissions = new ArrayList<>();
        for (String operation : EMR_CALLS.getOrDefault(api, List.of())) {
            String namedAs = "elasticmapreduce:" + operation;
            String action = IamAuthorizedActions.actionFor("elasticmapreduce", operation);
            if (RequestResources.actsOnNoResource(namedAs)) {
                permissions.add(new Permission(action, ANY_RESOURCE));
                continue;
            }
            if (clusterId == null) {
                return accessDenied(EMR_PREFIX, List.of());
            }
            for (String resource : RequestResources.ofBody(namedAs, emrRequest(operation, clusterId),
                    owner.partition(), owner.region(), owner.accountId(), null)) {
                permissions.add(new Permission(action, resource));
            }
        }
        return accessDenied(EMR_PREFIX, permissions);
    }

    /**
     * {@code DescribeRegions}, checked as the vendored request resources check the same call made directly: it acts
     * on no resource type, so on any resource.
     */
    private static TaskPermissions ec2DescribeRegions(JsonNode input, StateMachine machine) {
        return accessDenied("Ec2.", requestPermissions("ec2", "DescribeRegions", input, machine, null));
    }

    /** The members of the EMR request the integration sends that name the cluster. */
    private static JsonNode emrRequest(String operation, String clusterId) {
        return switch (operation) {
            case "AddJobFlowSteps" -> MAPPER.createObjectNode().put("JobFlowId", clusterId);
            case "SetTerminationProtection", "TerminateJobFlows" -> {
                ObjectNode request = MAPPER.createObjectNode();
                request.putArray("JobFlowIds").add(clusterId);
                yield request;
            }
            default -> MAPPER.createObjectNode().put("ClusterId", clusterId);
        };
    }

    /**
     * The checks the request the integration sends EMR Serverless needs, as the same request made directly does
     * ({@link RequestChecks}): its action on the application or job run it names, {@code CreateApplication} on no
     * resource; {@code iam:PassRole} on a job run's execution role; and {@code emr-serverless:TagResource} on what a
     * create or a job run tags, with the tags as condition keys of both. A request missing an id the integration
     * requires is refused by it.
     */
    private static TaskPermissions emrServerless(String api, JsonNode input, StateMachine machine) {
        ObjectNode request = EmrServerlessTaskInvoker.apiRequest(api, input);
        if (!"createApplication".equals(api) && !request.has("applicationId")
                || "cancelJobRun".equals(api) && !request.has("jobRunId")) {
            return accessDenied(EMR_SERVERLESS_PREFIX, List.of());
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return accessDenied(EMR_SERVERLESS_PREFIX, RequestChecks.of("emr-serverless:" + AslExecutor.capitalizeFirst(api),
                        request, owner.partition(), owner.region(), owner.accountId(), null).stream()
                .map(check -> new Permission(check.action(), check.resource(), check.context())).toList());
    }

    /**
     * The action on the schedule its name and group give, read as the integration reads them, a group left out being
     * {@code default}. A request naming no schedule is refused by Scheduler.
     */
    private static TaskPermissions scheduler(String api, JsonNode input, StateMachine machine) {
        String name = text(input.path("Name"));
        if (name == null) {
            return accessDenied(SCHEDULER_PREFIX, List.of());
        }
        String group = text(input.path("GroupName"));
        return accessDenied(SCHEDULER_PREFIX, List.of(new Permission(
                "scheduler:" + AslExecutor.capitalizeFirst(api),
                arn(machine, "scheduler", "schedule/" + (group == null ? "default" : group) + "/" + name))));
    }

    /**
     * Every CloudFormation operation the vendored request resources read, which a direct request is checked as too;
     * one the handler does not serve is then its own {@code UnknownAction}.
     */
    private static boolean servedByCloudFormation(String api) {
        return RequestResources.reads("cloudformation:" + AslExecutor.capitalizeFirst(api));
    }

    /**
     * The check the vendored request resources give the same call made directly ({@link #requestPermissions}),
     * {@code stored} finding the stack or the stack set a name names; on any resource when the request names none,
     * whatever its input is, as a direct request naming none is checked.
     */
    private static TaskPermissions cloudFormation(String operation, JsonNode input, StateMachine machine,
                                                  StoredResourceArns stored) {
        List<Permission> permissions = requestPermissions("cloudformation", operation, input, machine, stored);
        return accessDenied(CLOUDFORMATION_PREFIX, permissions.isEmpty()
                ? List.of(new Permission(IamAuthorizedActions.actionFor("cloudformation", operation), ANY_RESOURCE))
                : permissions);
    }

    /**
     * {@code states:InvokeHTTPEndpoint} on the state machine, the resource the Developer Guide's policy scopes it to:
     * a role allowed it on {@code *}, as the CDK grants it, is allowed it there too. The two condition keys the guide
     * and the CDK write are given. The connection's own permissions, {@code events:RetrieveConnectionCredentials}
     * and its secret's, are not evaluated, although the task reads the connection to authorize its request: a role
     * lacking them succeeds here and fails on AWS. A request naming no endpoint is refused by the integration.
     */
    private static TaskPermissions httpInvoke(JsonNode input, StateMachine machine) {
        String endpoint = text(input.path("ApiEndpoint"));
        if (endpoint == null) {
            return accessDenied(HTTP_PREFIX, List.of());
        }
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("states:HTTPEndpoint", List.of(endpoint));
        String method = text(input.path("Method"));
        if (method != null) {
            keys.put("states:HTTPMethod", List.of(method));
        }
        return accessDenied(HTTP_PREFIX, List.of(new Permission("states:InvokeHTTPEndpoint",
                machine.getStateMachineArn(), keys)));
    }

    /**
     * The check the vendored request resources give the same call made directly, on the request the integration
     * sends ({@link SdkManagementTaskInvoker#lambdaRequest}): {@code ListEventSourceMappings} acts on no resource type,
     * and {@code UpdateEventSourceMapping} on the mapping its {@code UUID} names; a request naming none is refused by
     * the integration.
     */
    private static TaskPermissions lambdaSdk(String api, JsonNode input, StateMachine machine) {
        return accessDenied(LAMBDA_PREFIX, requestPermissions("lambda", AslExecutor.capitalizeFirst(api),
                SdkManagementTaskInvoker.lambdaRequest(input), machine, null));
    }

    /**
     * The check a direct request of the operation gets, on what it names: the state machine it starts, the Map Run it
     * describes, or the activity or state machine waiting on the callback's token. A token naming no task, or anything
     * that is not the ARN the request needs, names nothing, so Step Functions answers it.
     */
    private static TaskPermissions sfnSdk(String operation, JsonNode input, StateMachine machine,
                                          StoredResourceArns stored) {
        return accessDenied(SFN_SDK_PREFIX, statesPermissions(operation, input, machine, stored));
    }

    /**
     * {@code states:StartExecution} on the child, without the execution role checks the other integrations make, which
     * a nested execution has never had. A request naming no state machine is refused by the integration.
     */
    private static TaskPermissions nestedStartExecution(JsonNode input, StateMachine machine) {
        return new TaskPermissions(NESTED_PREFIX + "AccessDeniedException",
                statesPermissions("StartExecution", input, machine, null), false);
    }

    private static List<Permission> statesPermissions(String operation, JsonNode input, StateMachine machine,
                                                      StoredResourceArns stored) {
        try {
            return requestPermissions("states", operation, AslExecutor.recaseKeys(MAPPER, input, false), machine,
                    stored);
        } catch (AwsException expected) {
            LOG.debugv("The {0} request names no task: {1}", operation, expected.getMessage());
            return List.of();
        }
    }

    /** The ARN of a resource of the state machine's partition, Region and account. */
    private static String arn(StateMachine machine, String service, String resource) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        return new AwsArnUtils.Arn(owner.partition(), service, owner.region(), owner.accountId(), resource).toString();
    }

    /** The value when it is the non-blank string an id has to be, as the integrations require of one. */
    private static String text(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
