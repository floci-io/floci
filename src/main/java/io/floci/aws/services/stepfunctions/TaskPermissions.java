package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser;
import io.floci.aws.services.dynamodb.DynamoDbTableNames;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamAuthorizedActions;
import io.floci.aws.services.iam.ResourceArnBuilder;
import io.floci.aws.services.redshiftdata.RedshiftDataTargetSubmitter;
import io.floci.aws.services.s3.S3ResourceArns;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a task's call to its integrated service needs from IAM. A request that names no resource
 * needs no permission here: the integrated service refuses it with its own validation error.
 *
 * @param deniedError the error a refused permission fails the task with: the integration's prefix and
 *                    the exception the integrated service's SDK raises for an access denial
 * @param permissions the actions the call performs
 */
record TaskPermissions(String deniedError, List<Permission> permissions) {

    /** The permissions of an integration whose refusal is its prefix and {@code AccessDeniedException}. */
    static TaskPermissions accessDenied(String errorPrefix, List<Permission> permissions) {
        return new TaskPermissions(errorPrefix + "AccessDeniedException", permissions);
    }

    private static final Logger LOG = Logger.getLogger(TaskPermissions.class);
    private static final String ANY_RESOURCE = "*";
    private static final String DYNAMODB_OPTIMIZED_PREFIX = "DynamoDB.";
    private static final String DYNAMODB_SDK_PREFIX = "DynamoDb.";
    private static final String REDSHIFT_DATA_PREFIX = "RedshiftData.";
    private static final String RDS_DATA_PREFIX = "RdsData.";
    private static final Set<String> DYNAMODB_OPTIMIZED =
            Set.of("putItem", "getItem", "deleteItem", "updateItem", "scan");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String partition = AwsArnUtils.parse(machine.getStateMachineArn()).partition();
        return accessDenied("S3.",
                List.of(new Permission("s3:PutObject", S3ResourceArns.object(partition, bucket, key))));
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
}
