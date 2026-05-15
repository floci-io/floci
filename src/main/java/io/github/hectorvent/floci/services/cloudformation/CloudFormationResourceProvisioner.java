package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.SqsParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.*;
import io.github.hectorvent.floci.core.common.AwsException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Provisions individual CloudFormation resource types using Floci's existing service implementations.
 */
@ApplicationScoped
public class CloudFormationResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CloudFormationResourceProvisioner.class);
    private static final String LAMBDA_CODE_IDENTITY_ATTR = "FlociLambdaCodeIdentity";
    private static final String LAMBDA_NAME_MODE_ATTR = "FlociLambdaFunctionNameMode";
    private static final String LAMBDA_PACKAGE_TYPE_ATTR = "FlociLambdaPackageType";
    private static final String LAMBDA_NAME_MODE_EXPLICIT = "explicit";
    private static final String LAMBDA_NAME_MODE_GENERATED = "generated";
    private static final int LAMBDA_DEFAULT_TIMEOUT_SECONDS = 3;
    private static final int LAMBDA_DEFAULT_MEMORY_MB = 128;
    private static final int LAMBDA_DEFAULT_EPHEMERAL_STORAGE_MB = 512;
    private static final String LAMBDA_DEFAULT_TRACING_MODE = "PassThrough";

    private final S3Service s3Service;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final DynamoDbService dynamoDbService;
    private final LambdaService lambdaService;
    private final IamService iamService;
    private final SsmService ssmService;
    private final KmsService kmsService;
    private final SecretsManagerService secretsManagerService;
    private final EventBridgeService eventBridgeService;
    private final ApiGatewayService apiGatewayService;
    private final ApiGatewayV2Service apiGatewayV2Service;
    private final EcrService ecrService;
    private final PipesService pipesService;

    @Inject
    public CloudFormationResourceProvisioner(S3Service s3Service, SqsService sqsService,
                                             SnsService snsService, DynamoDbService dynamoDbService,
                                             LambdaService lambdaService, IamService iamService,
                                             SsmService ssmService, KmsService kmsService,
                                             SecretsManagerService secretsManagerService,
                                             EventBridgeService eventBridgeService,
                                             ApiGatewayService apiGatewayService,
                                             ApiGatewayV2Service apiGatewayV2Service,
                                             EcrService ecrService,
                                             PipesService pipesService) {
        this.s3Service = s3Service;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.dynamoDbService = dynamoDbService;
        this.lambdaService = lambdaService;
        this.iamService = iamService;
        this.ssmService = ssmService;
        this.kmsService = kmsService;
        this.secretsManagerService = secretsManagerService;
        this.eventBridgeService = eventBridgeService;
        this.apiGatewayService = apiGatewayService;
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.ecrService = ecrService;
        this.pipesService = pipesService;
    }

    /**
     * Provisions a single resource. Returns the populated StackResource (physicalId + attributes set).
     * Returns null and logs a warning for unsupported types.
     */
    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName) {
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName, null);
    }

    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName, String existingPhysicalId) {
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName,
                existingPhysicalId, Map.of());
    }

    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName, String existingPhysicalId,
                                   Map<String, String> existingAttributes) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType(resourceType);
        resource.setPhysicalId(existingPhysicalId);
        resource.setAttributes(new HashMap<>(existingAttributes != null ? existingAttributes : Map.of()));

        try {
            switch (resourceType) {
                case "AWS::S3::Bucket" -> provisionS3Bucket(resource, properties, engine, region, accountId, stackName);
                case "AWS::SQS::Queue" -> provisionSqsQueue(resource, properties, engine, region, accountId, stackName);
                case "AWS::SNS::Topic" -> provisionSnsTopic(resource, properties, engine, region, accountId, stackName);
                case "AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable" ->
                        provisionDynamoTable(resource, properties, engine, region, accountId, stackName);
                case "AWS::Lambda::Function" -> provisionLambda(resource, properties, engine, region, accountId, stackName);
                case "AWS::IAM::Role" -> provisionIamRole(resource, properties, engine, accountId, stackName);
                case "AWS::IAM::User" -> provisionIamUser(resource, properties, engine, stackName);
                case "AWS::IAM::AccessKey" -> provisionIamAccessKey(resource, properties, engine);
                case "AWS::IAM::Policy", "AWS::IAM::ManagedPolicy" ->
                        provisionIamPolicy(resource, properties, engine, accountId, stackName);
                case "AWS::IAM::InstanceProfile" -> provisionInstanceProfile(resource, properties, engine, accountId, stackName);
                case "AWS::SSM::Parameter" -> provisionSsmParameter(resource, properties, engine, region, stackName);
                case "AWS::KMS::Key" -> provisionKmsKey(resource, properties, engine, region, accountId);
                case "AWS::KMS::Alias" -> provisionKmsAlias(resource, properties, engine, region);
                case "AWS::SecretsManager::Secret" -> provisionSecret(resource, properties, engine, region, accountId, stackName);
                case "AWS::CloudFormation::Stack" -> provisionNestedStack(resource, properties, engine, region);
                case "AWS::CDK::Metadata" -> provisionCdkMetadata(resource);
                case "AWS::S3::BucketPolicy" -> provisionS3BucketPolicy(resource, properties, engine);
                case "AWS::SQS::QueuePolicy" -> provisionSqsQueuePolicy(resource, properties, engine);
                case "AWS::ECR::Repository" -> provisionEcrRepository(resource, properties, engine, stackName, region);
                case "AWS::Route53::HostedZone" -> provisionRoute53HostedZone(resource, properties, engine);
                case "AWS::Route53::RecordSet" -> provisionRoute53RecordSet(resource, properties, engine);
                case "AWS::Events::Rule" -> provisionEventBridgeRule(resource, properties, engine, region, stackName);
                case "AWS::ApiGateway::RestApi" -> provisionApiGatewayRestApi(resource, properties, engine, region, accountId, stackName);
                case "AWS::ApiGateway::Resource" -> provisionApiGatewayResource(resource, properties, engine, region);
                case "AWS::ApiGateway::Authorizer" -> provisionApiGatewayAuthorizer(resource, properties, engine, region);
                case "AWS::ApiGateway::Method" -> provisionApiGatewayMethod(resource, properties, engine, region);
                case "AWS::ApiGateway::Deployment" -> provisionApiGatewayDeployment(resource, properties, engine, region);
                case "AWS::ApiGateway::Stage" -> provisionApiGatewayStage(resource, properties, engine, region);
                case "AWS::ApiGatewayV2::Api" -> provisionApiGatewayV2Api(resource, properties, engine, region, accountId, stackName);
                case "AWS::ApiGatewayV2::Route" -> provisionApiGatewayV2Route(resource, properties, engine, region);
                case "AWS::ApiGatewayV2::Integration" -> provisionApiGatewayV2Integration(resource, properties, engine, region);
                case "AWS::ApiGatewayV2::Stage" -> provisionApiGatewayV2Stage(resource, properties, engine, region);
                case "AWS::ApiGatewayV2::Deployment" -> provisionApiGatewayV2Deployment(resource, properties, engine, region);
                case "AWS::Pipes::Pipe" -> provisionPipe(resource, properties, engine, region, stackName);
                case "AWS::Lambda::EventSourceMapping" ->
                        provisionLambdaEventSourceMapping(resource, properties, engine, region);
                default -> {
                    LOG.debugv("Stubbing unsupported resource type: {0} ({1})", resourceType, logicalId);
                    resource.setPhysicalId(logicalId + "-" + UUID.randomUUID().toString().substring(0, 8));
                    resource.getAttributes().put("Arn", "arn:aws:stub:::" + logicalId);
                }
            }
            resource.setStatus("CREATE_COMPLETE");
        } catch (Exception e) {
            LOG.warnv("Failed to provision {0} ({1}): {2}", resourceType, logicalId, e.getMessage());
            resource.setStatus("CREATE_FAILED");
            resource.setStatusReason(e.getMessage());
        }
        return resource;
    }

    public void delete(String resourceType, String physicalId, String region) {
        try {
            switch (resourceType) {
                case "AWS::S3::Bucket" -> s3Service.deleteBucket(physicalId);
                case "AWS::SQS::Queue" -> sqsService.deleteQueue(physicalId, region);
                case "AWS::SNS::Topic" -> snsService.deleteTopic(physicalId, region);
                case "AWS::DynamoDB::Table" -> dynamoDbService.deleteTable(physicalId, region);
                case "AWS::Lambda::Function" -> lambdaService.deleteFunction(region, physicalId);
                case "AWS::IAM::Role" -> deleteRoleSafe(physicalId);
                case "AWS::IAM::Policy", "AWS::IAM::ManagedPolicy" -> deletePolicySafe(physicalId);
                case "AWS::IAM::InstanceProfile" -> iamService.deleteInstanceProfile(physicalId);
                case "AWS::SSM::Parameter" -> ssmService.deleteParameter(physicalId, region);
                case "AWS::KMS::Key" -> {
                } // KMS keys can't be immediately deleted; skip
                case "AWS::KMS::Alias" -> kmsService.deleteAlias(physicalId, region);
                case "AWS::SecretsManager::Secret" ->
                        secretsManagerService.deleteSecret(physicalId, null, true, region);
                case "AWS::Events::Rule" -> deleteEventBridgeRuleSafe(physicalId, region);
                case "AWS::ApiGateway::RestApi" -> apiGatewayService.deleteRestApi(region, physicalId);
                case "AWS::ApiGatewayV2::Api" -> apiGatewayV2Service.deleteApi(region, physicalId);
                case "AWS::ECR::Repository" ->
                        ecrService.deleteRepository(physicalId, null, true, region);
                case "AWS::Pipes::Pipe" -> pipesService.deletePipe(physicalId, region);
                case "AWS::Lambda::EventSourceMapping" -> lambdaService.deleteEventSourceMapping(physicalId);
                default -> LOG.debugv("Skipping delete of unsupported resource type: {0}", resourceType);
            }
        } catch (Exception e) {
            LOG.debugv("Error deleting {0} ({1}): {2}", resourceType, physicalId, e.getMessage());
        }
    }

    // ── S3 ────────────────────────────────────────────────────────────────────

    private void provisionS3Bucket(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId, String stackName) {
        String bucketName = resolveOptional(props, "BucketName", engine);
        if (bucketName == null || bucketName.isBlank()) {
            bucketName = generatePhysicalName(stackName, r.getLogicalId(), 63, true);
        }
        s3Service.createBucket(bucketName, region);
        r.setPhysicalId(bucketName);
        r.getAttributes().put("Arn", AwsArnUtils.Arn.of("s3", "", "", bucketName).toString());
        r.getAttributes().put("DomainName", bucketName + ".s3.amazonaws.com");
        r.getAttributes().put("RegionalDomainName", bucketName + ".s3." + region + ".amazonaws.com");
        r.getAttributes().put("WebsiteURL", "http://" + bucketName + ".s3-website." + region + ".amazonaws.com");
        r.getAttributes().put("BucketName", bucketName);
    }

    // ── SQS ───────────────────────────────────────────────────────────────────

    private void provisionSqsQueue(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId, String stackName) {
        String queueName = resolveOptional(props, "QueueName", engine);
        if (queueName == null || queueName.isBlank()) {
            queueName = generatePhysicalName(stackName, r.getLogicalId(), 80, false);
        }
        Map<String, String> attrs = new HashMap<>();
        if (props != null && props.has("VisibilityTimeout")) {
            attrs.put("VisibilityTimeout", engine.resolve(props.get("VisibilityTimeout")));
        }
        var queue = sqsService.createQueue(queueName, attrs, region);
        // QueueArn is computed on demand in SqsService#getQueueAttributes and is not
        // stored on the Queue object, so build it here from region + accountId + queueName.
        // Without this, Fn::GetAtt [Queue, Arn] references resolve to an empty string.
        String queueArn = AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        r.setPhysicalId(queue.getQueueUrl());
        r.getAttributes().put("Arn", queueArn);
        r.getAttributes().put("QueueName", queueName);
        r.getAttributes().put("QueueUrl", queue.getQueueUrl());
    }

    // ── SNS ───────────────────────────────────────────────────────────────────

    private void provisionSnsTopic(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId, String stackName) {
        String topicName = resolveOptional(props, "TopicName", engine);
        if (topicName == null || topicName.isBlank()) {
            topicName = generatePhysicalName(stackName, r.getLogicalId(), 256, false);
        }
        var topic = snsService.createTopic(topicName, Map.of(), Map.of(), region);
        r.setPhysicalId(topic.getTopicArn());
        r.getAttributes().put("Arn", topic.getTopicArn());
        r.getAttributes().put("TopicName", topicName);
    }

    // ── DynamoDB ──────────────────────────────────────────────────────────────

    private void provisionDynamoTable(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                      String region, String accountId, String stackName) {
        String tableName = resolveOptional(props, "TableName", engine);
        if (tableName == null || tableName.isBlank()) {
            tableName = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }

        List<KeySchemaElement> keySchema = new ArrayList<>();
        List<AttributeDefinition> attrDefs = new ArrayList<>();
        List<GlobalSecondaryIndex> gsis = new ArrayList<>();
        List<LocalSecondaryIndex> lsis = new ArrayList<>();

        if (props != null && props.has("KeySchema")) {
            for (JsonNode ks : props.get("KeySchema")) {
                String attrName = engine.resolve(ks.get("AttributeName"));
                String keyType = engine.resolve(ks.get("KeyType"));
                keySchema.add(new KeySchemaElement(attrName, keyType));
            }
        }
        if (props != null && props.has("AttributeDefinitions")) {
            for (JsonNode ad : props.get("AttributeDefinitions")) {
                String attrName = engine.resolve(ad.get("AttributeName"));
                String attrType = engine.resolve(ad.get("AttributeType"));
                attrDefs.add(new AttributeDefinition(attrName, attrType));
            }
        }

        if (props != null && props.has("GlobalSecondaryIndexes")) {
            for (JsonNode gsiNode : props.get("GlobalSecondaryIndexes")) {
                String indexName = engine.resolve(gsiNode.get("IndexName"));
                List<KeySchemaElement> gsiKeySchema = new ArrayList<>();
                if (gsiNode.has("KeySchema")) {
                    for (JsonNode ks : gsiNode.get("KeySchema")) {
                        String attrName = engine.resolve(ks.get("AttributeName"));
                        String keyType = engine.resolve(ks.get("KeyType"));
                        gsiKeySchema.add(new KeySchemaElement(attrName, keyType));
                    }
                }
                String projectionType = "ALL";
                JsonNode projection = gsiNode.get("Projection");
                List<String> nonKeyAttributes = new ArrayList<>();
                if (projection != null && projection.has("ProjectionType")) {
                    projectionType = engine.resolve(projection.get("ProjectionType"));
                    JsonNode nonKeyAttrArray = projection.path("NonKeyAttributes");
                    if (!nonKeyAttrArray.isMissingNode() && nonKeyAttrArray.isArray()){
                        for (JsonNode nonKeyAttr : nonKeyAttrArray){
                            nonKeyAttributes.add(nonKeyAttr.asText());
                        }
                    }
                }
                gsis.add(new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes));
            }
        }

        if (props != null && props.has("LocalSecondaryIndexes")) {
            for (JsonNode lsiNode : props.get("LocalSecondaryIndexes")) {
                String indexName = engine.resolve(lsiNode.get("IndexName"));
                List<KeySchemaElement> lsiKeySchema = new ArrayList<>();
                if (lsiNode.has("KeySchema")) {
                    for (JsonNode ks : lsiNode.get("KeySchema")) {
                        String attrName = engine.resolve(ks.get("AttributeName"));
                        String keyType = engine.resolve(ks.get("KeyType"));
                        lsiKeySchema.add(new KeySchemaElement(attrName, keyType));
                    }
                }
                String projectionType = "ALL";
                JsonNode projection = lsiNode.get("Projection");
                if (projection != null && projection.has("ProjectionType")) {
                    projectionType = engine.resolve(projection.get("ProjectionType"));
                }
                lsis.add(new LocalSecondaryIndex(indexName, lsiKeySchema, null, projectionType));
            }
        }

        if (keySchema.isEmpty()) {
            keySchema.add(new KeySchemaElement("id", "HASH"));
            attrDefs.add(new AttributeDefinition("id", "S"));
        }

        TableDefinition table;
        try {
            table = dynamoDbService.createTable(tableName, keySchema, attrDefs, null, null, gsis, lsis, region);
        } catch (AwsException e) {
            if (!"ResourceInUseException".equals(e.getErrorCode())) {
                throw e;
            }
            table = dynamoDbService.describeTable(tableName, region);
        }
        r.setPhysicalId(tableName);
        r.getAttributes().put("Arn", table.getTableArn());
        r.getAttributes().put("StreamArn", table.getTableArn() + "/stream/2024-01-01T00:00:00.000");
    }

    // ── Lambda ────────────────────────────────────────────────────────────────

    private void provisionLambda(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                 String region, String accountId, String stackName) {
        LambdaDesiredState desired = buildLambdaDesiredState(r, props, engine, region, accountId, stackName);
        LambdaFunction existing = getExistingLambda(region, r.getPhysicalId());
        boolean replacement = lambdaRequiresReplacement(r, desired, existing);

        LambdaFunction func;
        if (existing == null || replacement) {
            if (replacement && desired.functionName().equals(r.getPhysicalId())) {
                throw new AwsException("ValidationError",
                        "Cannot replace Lambda function " + r.getPhysicalId()
                                + " without a new FunctionName", 400);
            }
            func = createLambdaFunction(region, desired, !replacement);
            if (replacement && r.getPhysicalId() != null) {
                deleteReplacedLambda(region, r.getPhysicalId());
            }
        } else {
            func = updateLambdaFunction(region, existing, desired, r);
        }

        applyLambdaReservedConcurrency(region, func, desired);

        r.setPhysicalId(desired.functionName());
        r.getAttributes().put("Arn", func.getFunctionArn());
        r.getAttributes().put(LAMBDA_CODE_IDENTITY_ATTR, desired.code().identity());
        r.getAttributes().put(LAMBDA_NAME_MODE_ATTR,
                desired.explicitFunctionName() ? LAMBDA_NAME_MODE_EXPLICIT : LAMBDA_NAME_MODE_GENERATED);
        r.getAttributes().put(LAMBDA_PACKAGE_TYPE_ATTR, desired.packageType());
    }

    private LambdaDesiredState buildLambdaDesiredState(StackResource r, JsonNode props,
                                                       CloudFormationTemplateEngine engine,
                                                       String region, String accountId,
                                                       String stackName) {
        String explicitName = resolveOptional(props, "FunctionName", engine);
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String packageType = resolveOrDefault(props, "PackageType", engine, "Zip");
        String previousNameMode = r.getAttributes().get(LAMBDA_NAME_MODE_ATTR);
        String oldPackageType = r.getAttributes().get(LAMBDA_PACKAGE_TYPE_ATTR);
        boolean packageTypeReplacement = r.getPhysicalId() != null
                && oldPackageType != null
                && !Objects.equals(oldPackageType, packageType);
        boolean explicitRemoved = r.getPhysicalId() != null
                && !hasExplicitName
                && LAMBDA_NAME_MODE_EXPLICIT.equals(previousNameMode);

        String functionName;
        if (hasExplicitName) {
            functionName = explicitName;
        } else if (r.getPhysicalId() != null && !explicitRemoved && !packageTypeReplacement) {
            functionName = r.getPhysicalId();
        } else {
            functionName = generatePhysicalName(stackName, r.getLogicalId(), 64, false);
        }

        Map<String, Object> createRequest = new HashMap<>();
        Map<String, Object> configRequest = new HashMap<>();
        createRequest.put("FunctionName", functionName);
        createRequest.put("PackageType", packageType);

        String role = resolveOrDefault(props, "Role", engine,
                AwsArnUtils.Arn.of("iam", "", accountId, "role/default").toString());
        createRequest.put("Role", role);
        configRequest.put("Role", role);

        String runtime = null;
        String handler = null;
        if ("Zip".equals(packageType)) {
            runtime = resolveOrDefault(props, "Runtime", engine, "nodejs18.x");
            handler = resolveOrDefault(props, "Handler", engine, "index.handler");
            createRequest.put("Runtime", runtime);
            createRequest.put("Handler", handler);
            configRequest.put("Runtime", runtime);
            configRequest.put("Handler", handler);
        } else {
            runtime = resolveOptional(props, "Runtime", engine);
            handler = resolveOptional(props, "Handler", engine);
            if (runtime != null) {
                createRequest.put("Runtime", runtime);
                configRequest.put("Runtime", runtime);
            }
            if (handler != null) {
                createRequest.put("Handler", handler);
                configRequest.put("Handler", handler);
            }
        }

        LambdaCodeSpec code = resolveLambdaCode(props, engine, handler, runtime);
        createRequest.put("Code", code.request());

        configRequest.put("Timeout", intOrDefault(resolveOptional(props, "Timeout", engine),
                LAMBDA_DEFAULT_TIMEOUT_SECONDS));
        configRequest.put("MemorySize", intOrDefault(resolveOptional(props, "MemorySize", engine),
                LAMBDA_DEFAULT_MEMORY_MB));
        configRequest.put("Description", resolveOptional(props, "Description", engine));
        configRequest.put("KMSKeyArn", resolveOptional(props, "KMSKeyArn", engine));
        configRequest.put("Environment", Map.of("Variables", resolveLambdaEnvironment(props, engine)));
        putStringListIfPresent(configRequest, props, "Architectures", "Architectures", engine);
        configRequest.put("Layers", resolveStringListOrEmpty(props, "Layers", engine));
        configRequest.put("EphemeralStorage", resolveMapOrDefault(props, "EphemeralStorage", engine,
                Map.of("Size", LAMBDA_DEFAULT_EPHEMERAL_STORAGE_MB)));
        configRequest.put("TracingConfig", resolveMapOrDefault(props, "TracingConfig", engine,
                Map.of("Mode", LAMBDA_DEFAULT_TRACING_MODE)));
        configRequest.put("DeadLetterConfig", resolveMapOrDefault(props, "DeadLetterConfig", engine,
                mapWithNullValue("TargetArn")));
        configRequest.put("VpcConfig", resolveMapOrDefault(props, "VpcConfig", engine, Map.of()));
        putResolvedMapIfPresent(configRequest, props, "ImageConfig", "ImageConfig", engine);

        createRequest.putAll(configRequest);
        Integer reservedConcurrentExecutions = null;
        String reserved = resolveOptional(props, "ReservedConcurrentExecutions", engine);
        if (reserved != null) {
            try {
                reservedConcurrentExecutions = Integer.parseInt(reserved);
            } catch (NumberFormatException ignored) {
                throw new AwsException("InvalidParameterValueException",
                        "ReservedConcurrentExecutions must be an integer", 400);
            }
        }

        return new LambdaDesiredState(functionName, hasExplicitName, packageType,
                createRequest, code, configRequest, props != null && props.has("ReservedConcurrentExecutions"),
                reservedConcurrentExecutions);
    }

    private LambdaCodeSpec resolveLambdaCode(JsonNode props, CloudFormationTemplateEngine engine,
                                             String handler, String runtime) {
        if (props != null && props.has("Code")) {
            JsonNode codeNode = engine.resolveNode(props.get("Code"));

            String s3Bucket = codeNode.path("S3Bucket").asText(null);
            String s3Key = codeNode.path("S3Key").asText(null);
            if (s3Bucket != null && s3Key != null) {
                try {
                    s3Service.getObject(s3Bucket, s3Key);
                    return new LambdaCodeSpec(Map.of("S3Bucket", s3Bucket, "S3Key", s3Key),
                            "s3:" + s3Bucket + "\n" + s3Key);
                } catch (Exception e) {
                    LOG.warnv("S3 code not found for Lambda ({0}/{1}), using default handler: {2}",
                              s3Bucket, s3Key, e.getMessage());
                }
            }

            String zipFile = codeNode.path("ZipFile").asText(null);
            if (zipFile != null) {
                String effectiveHandler = handler != null ? handler : "index.handler";
                String effectiveRuntime = runtime != null ? runtime : "nodejs18.x";
                return new LambdaCodeSpec(Map.of("ZipFile", sourceToZipBase64(zipFile, effectiveHandler, effectiveRuntime)),
                        "inline:" + effectiveRuntime + "\n" + effectiveHandler + "\n" + zipFile);
            }

            String imageUri = codeNode.path("ImageUri").asText(null);
            if (imageUri != null) {
                return new LambdaCodeSpec(Map.of("ImageUri", imageUri), "image:" + imageUri);
            }
        }
        return new LambdaCodeSpec(Map.of("ZipFile", defaultHandlerZipBase64()), "default-handler");
    }

    private LambdaFunction getExistingLambda(String region, String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return null;
        }
        try {
            return lambdaService.getFunction(region, functionName);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode()) || e.getHttpStatus() == 404) {
                return null;
            }
            throw e;
        }
    }

    private boolean lambdaRequiresReplacement(StackResource r, LambdaDesiredState desired,
                                              LambdaFunction existing) {
        if (existing == null || r.getPhysicalId() == null) {
            return false;
        }
        if (!Objects.equals(r.getPhysicalId(), desired.functionName())) {
            return true;
        }
        String existingPackageType = existing.getPackageType() != null ? existing.getPackageType() : "Zip";
        return !Objects.equals(existingPackageType, desired.packageType());
    }

    private LambdaFunction createLambdaFunction(String region, LambdaDesiredState desired, boolean allowAdopt) {
        try {
            return lambdaService.createFunction(region, desired.createRequest());
        } catch (AwsException e) {
            if (allowAdopt && ("ResourceConflictException".equals(e.getErrorCode())
                    || (e.getMessage() != null && e.getMessage().contains("Function already exist")))) {
                return lambdaService.getFunction(region, desired.functionName());
            }
            throw e;
        }
    }

    private LambdaFunction updateLambdaFunction(String region,
                                                LambdaFunction existing,
                                                LambdaDesiredState desired,
                                                StackResource r) {
        LambdaFunction current = existing;
        if (lambdaConfigurationChanged(current, desired.configRequest())) {
            current = lambdaService.updateFunctionConfiguration(region, current.getFunctionName(),
                    desired.configRequest());
        }
        if (lambdaCodeChanged(current, desired.code(), r.getAttributes().get(LAMBDA_CODE_IDENTITY_ATTR))) {
            current = lambdaService.updateFunctionCode(region, current.getFunctionName(), desired.code().request());
        }
        return current;
    }

    private void deleteReplacedLambda(String region, String functionName) {
        try {
            lambdaService.deleteFunction(region, functionName);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode()) && e.getHttpStatus() != 404) {
                throw e;
            }
        }
    }

    private void applyLambdaReservedConcurrency(
            String region,
            LambdaFunction fn,
            LambdaDesiredState desired) {
        if (desired.reservedConcurrentExecutionsPresent()) {
            if (!Objects.equals(fn.getReservedConcurrentExecutions(), desired.reservedConcurrentExecutions())) {
                lambdaService.putFunctionConcurrency(region, fn.getFunctionName(),
                        desired.reservedConcurrentExecutions());
            }
        } else if (fn.getReservedConcurrentExecutions() != null) {
            lambdaService.deleteFunctionConcurrency(region, fn.getFunctionName());
        }
    }

    private boolean lambdaCodeChanged(LambdaFunction fn,
                                      LambdaCodeSpec code, String previousIdentity) {
        if (previousIdentity != null) {
            return !previousIdentity.equals(code.identity());
        }
        Map<String, Object> request = code.request();
        if (request.containsKey("ImageUri")) {
            return !Objects.equals(fn.getImageUri(), request.get("ImageUri"));
        }
        if (request.containsKey("S3Bucket") && request.containsKey("S3Key")) {
            return !Objects.equals(fn.getS3Bucket(), request.get("S3Bucket"))
                    || !Objects.equals(fn.getS3Key(), request.get("S3Key"));
        }
        if (request.containsKey("ZipFile")) {
            String desiredSha256 = sha256Base64((String) request.get("ZipFile"));
            return !Objects.equals(fn.getCodeSha256(), desiredSha256);
        }
        return false;
    }

    private boolean lambdaConfigurationChanged(
            LambdaFunction fn,
            Map<String, Object> request) {
        for (var entry : request.entrySet()) {
            String key = entry.getKey();
            Object desired = entry.getValue();
            switch (key) {
                case "Description" -> {
                    if (!Objects.equals(fn.getDescription(), desired)) return true;
                }
                case "Handler" -> {
                    if (!Objects.equals(fn.getHandler(), desired)) return true;
                }
                case "MemorySize" -> {
                    if (fn.getMemorySize() != toIntValue(desired, fn.getMemorySize())) return true;
                }
                case "Role" -> {
                    if (!Objects.equals(fn.getRole(), desired)) return true;
                }
                case "Runtime" -> {
                    if (!Objects.equals(fn.getRuntime(), desired)) return true;
                }
                case "Timeout" -> {
                    if (fn.getTimeout() != toIntValue(desired, fn.getTimeout())) return true;
                }
                case "Environment" -> {
                    if (!Objects.equals(fn.getEnvironment(), environmentVariables(desired))) return true;
                }
                case "Architectures" -> {
                    if (!Objects.equals(fn.getArchitectures(), desired)) return true;
                }
                case "EphemeralStorage" -> {
                    if (fn.getEphemeralStorageSize() != mapInt(desired, "Size", fn.getEphemeralStorageSize())) {
                        return true;
                    }
                }
                case "TracingConfig" -> {
                    if (!Objects.equals(fn.getTracingMode(), mapString(desired, "Mode"))) return true;
                }
                case "DeadLetterConfig" -> {
                    if (!Objects.equals(fn.getDeadLetterTargetArn(), mapString(desired, "TargetArn"))) return true;
                }
                case "Layers" -> {
                    if (!Objects.equals(fn.getLayers(), desired)) return true;
                }
                case "KMSKeyArn" -> {
                    if (!Objects.equals(fn.getKmsKeyArn(), desired)) return true;
                }
                case "VpcConfig" -> {
                    if (!Objects.equals(normalizeForCompare(fn.getVpcConfig()), normalizeForCompare(desired))) {
                        return true;
                    }
                }
                case "ImageConfig" -> {
                    if (imageConfigurationChanged(fn, desired)) return true;
                }
                default -> {
                    // Properties outside UpdateFunctionConfiguration are ignored here.
                }
            }
        }
        return false;
    }

    private boolean imageConfigurationChanged(
            LambdaFunction fn,
            Object desired) {
        if (!(desired instanceof Map<?, ?> map)) {
            return false;
        }
        if (map.containsKey("Command")
                && !Objects.equals(fn.getImageConfigCommand(), stringList(map.get("Command")))) {
            return true;
        }
        if (map.containsKey("EntryPoint")
                && !Objects.equals(fn.getImageConfigEntryPoint(), stringList(map.get("EntryPoint")))) {
            return true;
        }
        return map.containsKey("WorkingDirectory")
                && !Objects.equals(fn.getImageConfigWorkingDirectory(), mapString(map, "WorkingDirectory"));
    }

    private static String sha256Base64(String zipFileBase64) {
        byte[] zipBytes = Base64.getDecoder().decode(zipFileBase64);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(zipBytes);
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environmentVariables(Object value) {
        if (!(value instanceof Map<?, ?> envBlock)) {
            return Map.of();
        }
        Object variables = envBlock.get("Variables");
        if (!(variables instanceof Map<?, ?> vars)) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        vars.forEach((k, v) -> out.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
        return out;
    }

    private static String mapString(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object found = map.get(key);
        return found != null ? found.toString() : null;
    }

    private static int mapInt(Object value, String key, int defaultValue) {
        if (!(value instanceof Map<?, ?> map)) {
            return defaultValue;
        }
        return toIntValue(map.get(key), defaultValue);
    }

    private static int toIntValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s);
        }
        return defaultValue;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(Object::toString).toList();
    }

    private static Object normalizeForCompare(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), normalizeForCompare(v)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CloudFormationResourceProvisioner::normalizeForCompare).toList();
        }
        return value;
    }

    private static int intOrDefault(String value, int defaultValue) {
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    private Map<String, String> resolveLambdaEnvironment(JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("Environment") || props.get("Environment").isNull()) {
            return Map.of();
        }
        JsonNode envNode = engine.resolveNode(props.get("Environment"));
        if (envNode == null || !envNode.has("Variables") || !envNode.get("Variables").isObject()) {
            return Map.of();
        }
        Map<String, String> vars = new HashMap<>();
        envNode.get("Variables").fields()
                .forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
        return vars;
    }

    private List<String> resolveStringListOrEmpty(JsonNode props, String source,
                                                  CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return List.of();
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved == null || !resolved.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        resolved.forEach(v -> values.add(v.asText()));
        return values;
    }

    private Map<String, Object> resolveMapOrDefault(JsonNode props, String source,
                                                    CloudFormationTemplateEngine engine,
                                                    Map<String, Object> defaultValue) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return defaultValue;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        return resolved != null && resolved.isObject() ? jsonObjectToMap(resolved) : defaultValue;
    }

    private static Map<String, Object> mapWithNullValue(String key) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, null);
        return map;
    }

    private void putStringListIfPresent(Map<String, Object> request, JsonNode props, String source,
                                        String target, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved != null && resolved.isArray()) {
            List<String> values = new ArrayList<>();
            resolved.forEach(v -> values.add(v.asText()));
            request.put(target, values);
        }
    }

    private void putResolvedMapIfPresent(Map<String, Object> request, JsonNode props, String source,
                                         String target, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved != null && resolved.isObject()) {
            request.put(target, jsonObjectToMap(resolved));
        }
    }

    private Map<String, Object> jsonObjectToMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), jsonNodeToValue(e.getValue())));
        return out;
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonObjectToMap(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(v -> values.add(jsonNodeToValue(v)));
            return values;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }

    private record LambdaDesiredState(String functionName,
                                      boolean explicitFunctionName,
                                      String packageType,
                                      Map<String, Object> createRequest,
                                      LambdaCodeSpec code,
                                      Map<String, Object> configRequest,
                                      boolean reservedConcurrentExecutionsPresent,
                                      Integer reservedConcurrentExecutions) {}

    private record LambdaCodeSpec(Map<String, Object> request, String identity) {}

    private static String sourceToZipBase64(String source, String handler, String runtime) {
        String module = handler.contains(".") ? handler.substring(0, handler.lastIndexOf('.')) : "index";
        String ext = runtime.startsWith("python") ? ".py" : ".js";
        try {
            var baos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry(module + ext));
                zos.write(source.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create zip from ZipFile source", e);
        }
    }

    private static String defaultHandlerZipBase64() {
        try {
            var baos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write("exports.handler=async(e)=>({statusCode:200})".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create default handler zip", e);
        }
    }

    // ── IAM Role ──────────────────────────────────────────────────────────────

    private void provisionIamRole(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                  String accountId, String stackName) {
        String roleName = resolveOptional(props, "RoleName", engine);
        if (roleName == null || roleName.isBlank()) {
            roleName = generatePhysicalName(stackName, r.getLogicalId(), 64, false);
        }
        String assumeDoc = props != null && props.has("AssumeRolePolicyDocument")
                ? props.get("AssumeRolePolicyDocument").toString()
                : "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        String path = resolveOptional(props, "Path", engine);
        if (path == null) {
            path = "/";
        }
        String description = resolveOptional(props, "Description", engine);

        try {
            var role = iamService.createRole(roleName, path, assumeDoc, description, 3600, Map.of());
            r.setPhysicalId(roleName);
            r.getAttributes().put("Arn", role.getArn());
            r.getAttributes().put("RoleId", role.getRoleId());
        } catch (Exception e) {
            // Role might already exist (e.g., re-deploy) — look it up
            var role = iamService.getRole(roleName);
            r.setPhysicalId(roleName);
            r.getAttributes().put("Arn", role.getArn());
            r.getAttributes().put("RoleId", role.getRoleId());
        }

        // Attach managed policies if specified
        if (props != null && props.has("ManagedPolicyArns")) {
            for (JsonNode policyArn : props.get("ManagedPolicyArns")) {
                try {
                    iamService.attachRolePolicy(roleName, engine.resolve(policyArn));
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ── IAM Policy ────────────────────────────────────────────────────────────

    private void provisionIamPolicy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                    String accountId, String stackName) {
        String policyName = resolveOptional(props, "PolicyName", engine);
        if (policyName == null || policyName.isBlank()) {
            policyName = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }
        String document = props != null && props.has("PolicyDocument")
                ? props.get("PolicyDocument").toString()
                : "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        var policy = iamService.createPolicy(policyName, "/", null, document, Map.of());
        r.setPhysicalId(policy.getArn());
        r.getAttributes().put("Arn", policy.getArn());

        // Attach to roles if specified
        if (props != null && props.has("Roles")) {
            for (JsonNode role : props.get("Roles")) {
                try {
                    iamService.attachRolePolicy(engine.resolve(role), policy.getArn());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void provisionIamManagedPolicy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                           String accountId, String stackName) {
        provisionIamPolicy(r, props, engine, accountId, stackName);
    }

    // ── IAM Instance Profile ──────────────────────────────────────────────────

    private void provisionInstanceProfile(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String accountId, String stackName) {
        String name = resolveOptional(props, "InstanceProfileName", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }
        try {
            var profile = iamService.createInstanceProfile(name, "/");
            r.setPhysicalId(name);
            r.getAttributes().put("Arn", profile.getArn());
        } catch (Exception e) {
            r.setPhysicalId(name);
            r.getAttributes().put("Arn", AwsArnUtils.Arn.of("iam", "", accountId, "instance-profile/" + name).toString());
        }
    }

    // ── SSM Parameter ─────────────────────────────────────────────────────────

    private void provisionSsmParameter(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                       String region, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 2048, false);
        }
        String value = resolveOptional(props, "Value", engine);
        if (value == null) {
            value = "";
        }
        String type = resolveOptional(props, "Type", engine);
        if (type == null) {
            type = "String";
        }
        ssmService.putParameter(name, value, type, null, true, region);
        r.setPhysicalId(name);
    }

    // ── KMS ───────────────────────────────────────────────────────────────────

    private void provisionKmsKey(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                 String region, String accountId) {
        String description = resolveOptional(props, "Description", engine);
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        var key = kmsService.createKey(description, null, tags, region);
        r.setPhysicalId(key.getKeyId());
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", key.getKeyId());
    }

    private void provisionKmsAlias(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region) {
        String aliasName = resolveOptional(props, "AliasName", engine);
        String targetKeyId = resolveOptional(props, "TargetKeyId", engine);
        if (aliasName != null && targetKeyId != null) {
            kmsService.createAlias(aliasName, targetKeyId, region);
        }
        r.setPhysicalId(aliasName != null ? aliasName : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // ── Secrets Manager ───────────────────────────────────────────────────────

    private void provisionSecret(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                 String region, String accountId, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 512, false);
        }
        String description = resolveOptional(props, "Description", engine);
        String value = resolveSecretValue(props, engine);
        var secret = secretsManagerService.createSecret(name, value, null, description, null, List.of(), region);
        r.setPhysicalId(secret.getArn());
        r.getAttributes().put("Arn", secret.getArn());
        r.getAttributes().put("Name", name);
    }

    /**
     * Resolves the secret value from CloudFormation properties.
     * SecretString and GenerateSecretString are mutually exclusive per AWS spec.
     * If GenerateSecretString is present, a random password is generated.
     * If SecretStringTemplate and GenerateStringKey are specified inside
     * GenerateSecretString, the generated password is embedded in the template JSON.
     */
    private String resolveSecretValue(JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null) {
            return "{}";
        }

        // SecretString takes precedence when explicitly set
        String secretString = resolveOptional(props, "SecretString", engine);
        JsonNode genNode = props.get("GenerateSecretString");

        if (secretString != null && genNode != null && !genNode.isNull()) {
            throw new AwsException("ValidationError",
                    "You can't specify both SecretString and GenerateSecretString", 400);
        }

        if (secretString != null) {
            return secretString;
        }

        if (genNode != null && !genNode.isNull()) {
            return generateSecretString(genNode);
        }

        return "{}";
    }

    private String generateSecretString(JsonNode genNode) {
        String password = io.github.hectorvent.floci.services.secretsmanager
                .RandomPasswordGenerator.generate(genNode);

        String template = null;
        String key = null;
        JsonNode templateNode = genNode.get("SecretStringTemplate");
        JsonNode keyNode = genNode.get("GenerateStringKey");

        if (templateNode != null && !templateNode.isNull()) {
            template = templateNode.asText();
        }
        if (keyNode != null && !keyNode.isNull()) {
            key = keyNode.asText();
        }

        if (template != null && key != null) {
            // Insert the generated password into the template JSON
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(template);
                tree.put(key, password);
                return mapper.writeValueAsString(tree);
            } catch (Exception e) {
                // If the template is not valid JSON, fall back to raw password
                LOG.warnv("Failed to parse SecretStringTemplate: {0}", e.getMessage());
                return password;
            }
        }

        return password;
    }

    // ── Nested Stack ──────────────────────────────────────────────────────────

    private void provisionNestedStack(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                      String region) {
        // Nested stacks are stubbed — return a synthetic stack ID
        String nestedId = AwsArnUtils.Arn.of("cloudformation", region, "", "stack/nested-" + UUID.randomUUID().toString().substring(0, 8) + "/").toString();
        r.setPhysicalId(nestedId);
        r.getAttributes().put("Arn", nestedId);
        r.getAttributes().put("Outputs.BootstrapVersion", "21");
    }

    // ── EventBridge ─────────────────────────────────────────────────────────

    private void provisionEventBridgeRule(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String region, String stackName) {
        String ruleName = resolveOptional(props, "Name", engine);
        if (ruleName == null || ruleName.isBlank()) {
            ruleName = generatePhysicalName(stackName, r.getLogicalId(), 64, false);
        }

        String busName = resolveOptional(props, "EventBusName", engine);
        String description = resolveOptional(props, "Description", engine);
        String roleArn = resolveOptional(props, "RoleArn", engine);
        String scheduleExpression = resolveOptional(props, "ScheduleExpression", engine);

        String eventPattern = null;
        if (props != null && props.has("EventPattern") && !props.get("EventPattern").isNull()) {
            JsonNode patternNode = engine.resolveNode(props.get("EventPattern"));
            eventPattern = patternNode.toString();
        }

        String stateStr = resolveOptional(props, "State", engine);
        RuleState state = "DISABLED".equals(stateStr) ? RuleState.DISABLED : RuleState.ENABLED;

        var rule = eventBridgeService.putRule(ruleName, busName, eventPattern, scheduleExpression,
                state, description, roleArn, Map.of(), region);
        r.setPhysicalId(ruleName);
        r.getAttributes().put("Arn", rule.getArn());

        // Provision inline targets
        if (props != null && props.has("Targets")) {
            List<Target> targets = new ArrayList<>();
            for (JsonNode targetNode : props.get("Targets")) {
                JsonNode resolved = engine.resolveNode(targetNode);
                String targetId = resolved.path("Id").asText(null);
                String targetArn = resolved.path("Arn").asText(null);
                String input = resolved.path("Input").asText(null);
                String inputPath = resolved.path("InputPath").asText(null);
                if (targetId != null && targetArn != null) {
                    Target target = new Target(targetId, targetArn, input, inputPath);
                    JsonNode sqsParamsNode = resolved.path("SqsParameters");
                    if (!sqsParamsNode.isMissingNode() && sqsParamsNode.isObject()) {
                        String messageGroupId = sqsParamsNode.path("MessageGroupId").asText(null);
                        if (messageGroupId != null) {
                            SqsParameters sqsParameters = new SqsParameters();
                            sqsParameters.setMessageGroupId(messageGroupId);
                            target.setSqsParameters(sqsParameters);
                        }
                    }
                    targets.add(target);
                }
            }
            if (!targets.isEmpty()) {
                eventBridgeService.putTargets(ruleName, busName, targets, region);
            }
        }
    }

    private void deleteEventBridgeRuleSafe(String ruleName, String region) {
        try {
            // Remove all targets before deleting the rule
            var targets = eventBridgeService.listTargetsByRule(ruleName, null, region);
            if (!targets.isEmpty()) {
                List<String> targetIds = targets.stream().map(Target::getId).toList();
                eventBridgeService.removeTargets(ruleName, null, targetIds, region);
            }
            eventBridgeService.deleteRule(ruleName, null, region);
        } catch (Exception e) {
            LOG.debugv("Could not delete EventBridge rule {0}: {1}", ruleName, e.getMessage());
        }
    }

    // ── Lambda EventSourceMapping ─────────────────────────────────────────────

    private void provisionLambdaEventSourceMapping(StackResource r, JsonNode props,
                                                   CloudFormationTemplateEngine engine, String region) {
        Map<String, Object> req = new HashMap<>();
        req.put("FunctionName", resolveOptional(props, "FunctionName", engine));
        req.put("EventSourceArn", resolveOptional(props, "EventSourceArn", engine));

        String enabledStr = resolveOptional(props, "Enabled", engine);
        if (enabledStr != null) {
            req.put("Enabled", Boolean.parseBoolean(enabledStr));
        }

        String batchSize = resolveOptional(props, "BatchSize", engine);
        if (batchSize != null) {
            try { req.put("BatchSize", Integer.parseInt(batchSize)); } catch (NumberFormatException ignored) {}
        }

        var esm = lambdaService.createEventSourceMapping(region, req);
        r.setPhysicalId(esm.getUuid());
        r.getAttributes().put("Id", esm.getUuid());
    }

    // ── Pipes ──────────────────────────────────────────────────────────────────

    private void provisionPipe(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                               String region, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 64, false);
        }

        String source = resolveOptional(props, "Source", engine);
        String target = resolveOptional(props, "Target", engine);
        String roleArn = resolveOptional(props, "RoleArn", engine);
        String description = resolveOptional(props, "Description", engine);
        String enrichment = resolveOptional(props, "Enrichment", engine);

        String stateStr = resolveOptional(props, "DesiredState", engine);
        DesiredState desiredState = "STOPPED".equals(stateStr) ? DesiredState.STOPPED : DesiredState.RUNNING;

        JsonNode sourceParameters = null;
        if (props != null && props.has("SourceParameters") && !props.get("SourceParameters").isNull()) {
            sourceParameters = engine.resolveNode(props.get("SourceParameters"));
        }

        JsonNode targetParameters = null;
        if (props != null && props.has("TargetParameters") && !props.get("TargetParameters").isNull()) {
            targetParameters = engine.resolveNode(props.get("TargetParameters"));
        }

        JsonNode enrichmentParameters = null;
        if (props != null && props.has("EnrichmentParameters") && !props.get("EnrichmentParameters").isNull()) {
            enrichmentParameters = engine.resolveNode(props.get("EnrichmentParameters"));
        }

        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);

        var pipe = pipesService.createPipe(name, source, target, roleArn, description, desiredState,
                enrichment, sourceParameters, targetParameters, enrichmentParameters, tags, region);

        r.setPhysicalId(name);
        r.getAttributes().put("Arn", pipe.getArn());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void provisionCdkMetadata(StackResource r) {
        r.setPhysicalId("cdk-metadata-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void provisionS3BucketPolicy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        r.setPhysicalId("bucket-policy-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void provisionSqsQueuePolicy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        r.setPhysicalId("queue-policy-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void provisionIamUser(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                  String stackName) {
        String userName = resolveOptional(props, "UserName", engine);
        if (userName == null || userName.isBlank()) {
            userName = generatePhysicalName(stackName, r.getLogicalId(), 64, false);
        }
        var user = iamService.createUser(userName, "/");
        r.setPhysicalId(userName);
        r.getAttributes().put("Arn", user.getArn());
    }

    private void provisionIamAccessKey(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String userName = resolveOptional(props, "UserName", engine);
        if (userName != null) {
            var key = iamService.createAccessKey(userName);
            r.setPhysicalId(key.getAccessKeyId());
            r.getAttributes().put("SecretAccessKey", key.getSecretAccessKey());
        }
    }

    private void provisionEcrRepository(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                        String stackName, String region) {
        String repoName = resolveOptional(props, "RepositoryName", engine);
        if (repoName == null || repoName.isBlank()) {
            repoName = generatePhysicalName(stackName, r.getLogicalId(), 256, true);
        }
        // CDK bootstrap requires lower-case repository names; CFN-generated suffixes can include
        // upper-case characters. Normalize to satisfy the AWS ECR repository name pattern.
        repoName = repoName.toLowerCase();

        String mutability = resolveOptional(props, "ImageTagMutability", engine);
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);

        Repository repo;
        try {
            repo = ecrService.createRepository(repoName, null, mutability, null, null, null, tags, region);
        } catch (AwsException e) {
            if ("RepositoryAlreadyExistsException".equals(e.getErrorCode())) {
                repo = ecrService.describeRepositories(List.of(repoName), null, region).get(0);
            } else {
                throw e;
            }
        }

        // Lifecycle policy can be inlined as `LifecyclePolicy.LifecyclePolicyText`
        if (props != null && props.has("LifecyclePolicy")) {
            JsonNode lp = engine.resolveNode(props.get("LifecyclePolicy"));
            String policyText = lp.path("LifecyclePolicyText").asText(null);
            if (policyText != null && !policyText.isEmpty()) {
                ecrService.putLifecyclePolicy(repoName, null, policyText, region);
            }
        }
        if (props != null && props.has("RepositoryPolicyText")) {
            JsonNode pol = engine.resolveNode(props.get("RepositoryPolicyText"));
            String policyText = pol.isTextual() ? pol.asText() : pol.toString();
            if (policyText != null && !policyText.isEmpty()) {
                ecrService.setRepositoryPolicy(repoName, null, policyText, region);
            }
        }

        r.setPhysicalId(repoName);
        r.getAttributes().put("Arn", repo.getRepositoryArn());
        r.getAttributes().put("RepositoryUri", repo.getRepositoryUri());
    }

    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private void provisionRoute53HostedZone(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String zoneId = "Z" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        r.setPhysicalId(zoneId);
    }

    private void provisionRoute53RecordSet(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String name = resolveOptional(props, "Name", engine);
        r.setPhysicalId(name != null ? name : "record-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // ── ApiGateway (V1) ──────────────────────────────────────────────────────

    private void provisionApiGatewayRestApi(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region, String accountId, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("description", resolveOptional(props, "Description", engine));

        var api = apiGatewayService.createRestApi(region, req);
        r.setPhysicalId(api.getId());
        r.getAttributes().put("RootResourceId", apiGatewayService.getResources(region, api.getId()).get(0).getId());
    }

    private void provisionApiGatewayResource(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                             String region) {
        String apiId = resolveOptional(props, "RestApiId", engine);
        String parentId = resolveOptional(props, "ParentId", engine);
        String pathPart = resolveOptional(props, "PathPart", engine);

        Map<String, Object> req = new HashMap<>();
        req.put("pathPart", pathPart);

        var res = apiGatewayService.createResource(region, apiId, parentId, req);
        r.setPhysicalId(res.getId());
    }

    private void provisionApiGatewayAuthorizer(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                               String region) {
        String apiId = resolveOptional(props, "RestApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("name", resolveOptional(props, "Name", engine));
        req.put("type", resolveOptional(props, "Type", engine));
        req.put("authorizerUri", resolveOptional(props, "AuthorizerUri", engine));
        req.put("identitySource", resolveOptional(props, "IdentitySource", engine));
        String ttl = resolveOptional(props, "AuthorizerResultTtlInSeconds", engine);
        if (ttl != null) {
            req.put("authorizerResultTtlInSeconds", ttl);
        }
        var authorizer = apiGatewayService.createAuthorizer(region, apiId, req);
        r.setPhysicalId(authorizer.getId());
    }

    private void provisionApiGatewayMethod(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                           String region) {
        String apiId = resolveOptional(props, "RestApiId", engine);
        String resourceId = resolveOptional(props, "ResourceId", engine);
        String httpMethod = resolveOptional(props, "HttpMethod", engine);

        Map<String, Object> req = new HashMap<>();
        req.put("authorizationType", resolveOrDefault(props, "AuthorizationType", engine, "NONE"));
        String authorizerId = resolveOptional(props, "AuthorizerId", engine);
        if (authorizerId != null) {
            req.put("authorizerId", authorizerId);
        }

        apiGatewayService.putMethod(region, apiId, resourceId, httpMethod, req);
        r.setPhysicalId(apiId + "-" + resourceId + "-" + httpMethod);

        // Provision integration if present
        if (props != null && props.has("Integration")) {
            JsonNode integNode = engine.resolveNode(props.get("Integration"));
            Map<String, Object> integReq = new HashMap<>();
            integReq.put("type", resolveOptional(integNode, "Type", engine));
            integReq.put("httpMethod", resolveOptional(integNode, "IntegrationHttpMethod", engine));
            integReq.put("uri", resolveOptional(integNode, "Uri", engine));

            apiGatewayService.putIntegration(region, apiId, resourceId, httpMethod, integReq);
        }
    }

    private void provisionApiGatewayDeployment(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                               String region) {
        String apiId = resolveOptional(props, "RestApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("description", resolveOptional(props, "Description", engine));

        var deployment = apiGatewayService.createDeployment(region, apiId, req);
        r.setPhysicalId(deployment.id());
    }

    private void provisionApiGatewayStage(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String region) {
        String apiId = resolveOptional(props, "RestApiId", engine);
        String stageName = resolveOptional(props, "StageName", engine);
        String deploymentId = resolveOptional(props, "DeploymentId", engine);

        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("deploymentId", deploymentId);
        req.put("description", resolveOptional(props, "Description", engine));

        var stage = apiGatewayService.createStage(region, apiId, req);
        r.setPhysicalId(stageName);
    }

    // ── ApiGatewayV2 (HTTP/WebSocket) ────────────────────────────────────────

    private void provisionApiGatewayV2Api(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String region, String accountId, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("protocolType", resolveOrDefault(props, "ProtocolType", engine, "HTTP"));

        Api api = apiGatewayV2Service.createApi(region, req);
        r.setPhysicalId(api.getApiId());
        r.getAttributes().put("ApiEndpoint", api.getApiEndpoint());
    }

    private void provisionApiGatewayV2Route(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("routeKey", resolveOptional(props, "RouteKey", engine));
        req.put("authorizationType", resolveOrDefault(props, "AuthorizationType", engine, "NONE"));
        req.put("target", resolveOptional(props, "Target", engine));

        Route route = apiGatewayV2Service.createRoute(region, apiId, req);
        r.setPhysicalId(route.getRouteId());
    }

    private void provisionApiGatewayV2Integration(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                                  String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("integrationType", resolveOptional(props, "IntegrationType", engine));
        req.put("integrationUri", resolveOptional(props, "IntegrationUri", engine));
        req.put("payloadFormatVersion", resolveOrDefault(props, "PayloadFormatVersion", engine, "2.0"));

        Integration integration = apiGatewayV2Service.createIntegration(region, apiId, req);
        r.setPhysicalId(integration.getIntegrationId());
    }

    private void provisionApiGatewayV2Stage(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        String stageName = resolveOptional(props, "StageName", engine);

        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("autoDeploy", resolveOrDefault(props, "AutoDeploy", engine, "false"));

        Stage stage = apiGatewayV2Service.createStage(region, apiId, req);
        r.setPhysicalId(stageName);
    }

    private void provisionApiGatewayV2Deployment(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                                 String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("description", resolveOptional(props, "Description", engine));

        Deployment deployment = apiGatewayV2Service.createDeployment(region, apiId, req);
        r.setPhysicalId(deployment.getDeploymentId());
    }

    private String resolveOptional(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    private String resolveOrDefault(JsonNode props, String name,
                                    CloudFormationTemplateEngine engine, String defaultValue) {
        String value = resolveOptional(props, name, engine);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private void deleteRoleSafe(String roleName) {
        try {
            var role = iamService.getRole(roleName);
            for (String policyArn : new ArrayList<>(role.getAttachedPolicyArns())) {
                iamService.detachRolePolicy(roleName, policyArn);
            }
            for (String policyName : new ArrayList<>(role.getInlinePolicies().keySet())) {
                iamService.deleteRolePolicy(roleName, policyName);
            }
            iamService.deleteRole(roleName);
        } catch (Exception e) {
            LOG.debugv("Could not delete role {0}: {1}", roleName, e.getMessage());
        }
    }

    private void deletePolicySafe(String policyArn) {
        try {
            iamService.deletePolicy(policyArn);
        } catch (Exception e) {
            LOG.debugv("Could not delete policy {0}: {1}", policyArn, e.getMessage());
        }
    }

    /**
     * Generate an AWS-like physical name: {stackName}-{logicalId}-{randomSuffix}.
     * Mirrors the naming pattern AWS CloudFormation uses when no explicit name is provided.
     */
    private String generatePhysicalName(String stackName, String logicalId, int maxLength, boolean lowercase) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String name = stackName + "-" + logicalId + "-" + suffix;
        if (lowercase) {
            name = name.toLowerCase();
        }
        if (maxLength > 0 && name.length() > maxLength) {
            name = name.substring(0, maxLength);
        }
        return name;
    }
}
