package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.BatchParameters;
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
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.*;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
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

    /** Reserved attribute keys used to carry custom-resource state to the later Delete invocation. */
    private static final String CR_SERVICE_TOKEN_ATTR = "__FlociServiceToken";
    private static final String CR_PROPERTIES_ATTR = "__FlociResourceProperties";
    /**
     * How long to wait for the Lambda's ResponseURL callback after the synchronous invoke returns.
     * The invoke already blocks until the handler finishes, so this only covers a PUT that lands
     * fractionally after the container returns control.
     */
    private static final Duration CR_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

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
    private final CognitoService cognitoService;
    private final LambdaLayerService lambdaLayerService;
    private final ObjectMapper objectMapper;
    private final CustomResourceResponseStore customResourceResponseStore;
    private final ContainerReachableEndpoint reachableEndpoint;
    private final EcsService ecsService;
    private final ElbV2Service elbV2Service;
    private final StepFunctionsService stepFunctionsService;
    private final BatchService batchService;
    private final Ec2Service ec2Service;

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
                                             PipesService pipesService,
                                             CognitoService cognitoService,
                                             LambdaLayerService lambdaLayerService,
                                             ObjectMapper objectMapper,
                                             CustomResourceResponseStore customResourceResponseStore,
                                             ContainerReachableEndpoint reachableEndpoint,
                                             EcsService ecsService,
                                             ElbV2Service elbV2Service,
                                             StepFunctionsService stepFunctionsService,
                                             BatchService batchService,
                                             Ec2Service ec2Service) {
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
        this.cognitoService = cognitoService;
        this.lambdaLayerService = lambdaLayerService;
        this.objectMapper = objectMapper;
        this.customResourceResponseStore = customResourceResponseStore;
        this.reachableEndpoint = reachableEndpoint;
        this.ecsService = ecsService;
        this.elbV2Service = elbV2Service;
        this.stepFunctionsService = stepFunctionsService;
        this.batchService = batchService;
        this.ec2Service = ec2Service;
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
                case "AWS::SNS::Subscription" -> provisionSnsSubscription(resource, properties, engine, region);
                case "AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable" ->
                        provisionDynamoTable(resource, properties, engine, region, accountId, stackName);
                case "AWS::Lambda::Function" -> provisionLambda(resource, properties, engine, region, accountId, stackName);
                case "AWS::Lambda::LayerVersion" ->
                        provisionLambdaLayerVersion(resource, properties, engine, region, stackName);
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
                case "AWS::StepFunctions::StateMachine" ->
                        provisionStepFunctionsStateMachine(resource, properties, engine, region, stackName);
                case "AWS::Lambda::EventSourceMapping" ->
                        provisionLambdaEventSourceMapping(resource, properties, engine, region);
                case "AWS::Cognito::UserPool" ->
                        provisionCognitoUserPool(resource, properties, engine, region, accountId, stackName);
                case "AWS::Cognito::UserPoolClient" ->
                        provisionCognitoUserPoolClient(resource, properties, engine, region, accountId, stackName);
                case "AWS::CloudFormation::CustomResource" ->
                        provisionCustomResource(resource, properties, engine, region, accountId, stackName);
                case "AWS::ECS::Cluster" -> provisionEcsCluster(resource, properties, engine, region, stackName);
                case "AWS::ECS::TaskDefinition" -> provisionEcsTaskDefinition(resource, properties, engine, region, stackName);
                case "AWS::ECS::Service" -> provisionEcsService(resource, properties, engine, region, stackName);
                case "AWS::ElasticLoadBalancingV2::LoadBalancer" ->
                        provisionLoadBalancer(resource, properties, engine, region, stackName);
                case "AWS::ElasticLoadBalancingV2::TargetGroup" ->
                        provisionTargetGroup(resource, properties, engine, region, stackName);
                case "AWS::ElasticLoadBalancingV2::Listener" ->
                        provisionListener(resource, properties, engine, region);
                case "AWS::ElasticLoadBalancingV2::ListenerRule" ->
                        provisionListenerRule(resource, properties, engine, region);
                case "AWS::Batch::ComputeEnvironment" ->
                        provisionBatchComputeEnvironment(resource, properties, engine, region, stackName);
                case "AWS::Batch::JobQueue" ->
                        provisionBatchJobQueue(resource, properties, engine, region, stackName);
                case "AWS::Batch::JobDefinition" ->
                        provisionBatchJobDefinition(resource, properties, engine, region, stackName);
                // EC2 networking. These delegate to Ec2Service so the resources actually exist
                // (describe-subnets, ELBv2, etc. can find them) instead of being stubbed with a
                // fake physical id. Topological ordering guarantees parents are provisioned first.
                case "AWS::EC2::VPC" -> provisionVpc(resource, properties, engine, region);
                case "AWS::EC2::Subnet" -> provisionSubnet(resource, properties, engine, region);
                case "AWS::EC2::SecurityGroup" -> provisionSecurityGroup(resource, properties, engine, region, stackName);
                case "AWS::EC2::InternetGateway" -> provisionInternetGateway(resource, region);
                case "AWS::EC2::RouteTable" -> provisionRouteTable(resource, properties, engine, region);
                case "AWS::EC2::SubnetRouteTableAssociation" ->
                        provisionSubnetRouteTableAssociation(resource, properties, engine, region);
                case "AWS::EC2::Route" -> provisionRoute(resource, properties, engine, region);
                case "AWS::EC2::NatGateway" -> provisionNatGateway(resource, properties, engine, region);
                case "AWS::EC2::EIP" -> provisionEip(resource, region);
                default -> {
                    if (resourceType != null && resourceType.startsWith("Custom::")) {
                        provisionCustomResource(resource, properties, engine, region, accountId, stackName);
                    } else {
                        LOG.debugv("Stubbing unsupported resource type: {0} ({1})", resourceType, logicalId);
                        resource.setPhysicalId(logicalId + "-" + UUID.randomUUID().toString().substring(0, 8));
                        resource.getAttributes().put("Arn", "arn:aws:stub:::" + logicalId);
                    }
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

    /**
     * Deletes a provisioned resource. Custom resources are re-invoked with {@code RequestType=Delete}
     * (using the ServiceToken + properties stashed at create time); everything else delegates to the
     * type-keyed {@link #delete(String, String, String)}.
     */
    public void delete(StackResource resource, String region) {
        String resourceType = resource.getResourceType();
        boolean custom = "AWS::CloudFormation::CustomResource".equals(resourceType)
                || (resourceType != null && resourceType.startsWith("Custom::"));
        if (custom) {
            deleteCustomResource(resource, region);
            return;
        }
        delete(resourceType, resource.getPhysicalId(), region);
    }

    public void delete(String resourceType, String physicalId, String region) {
        try {
            switch (resourceType) {
                case "AWS::S3::Bucket" -> s3Service.deleteBucket(physicalId);
                case "AWS::SQS::Queue" -> sqsService.deleteQueue(physicalId, region);
                case "AWS::SNS::Topic" -> snsService.deleteTopic(physicalId, region);
                case "AWS::SNS::Subscription" -> snsService.unsubscribe(physicalId, region);
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
                case "AWS::StepFunctions::StateMachine" -> stepFunctionsService.deleteStateMachine(physicalId);
                case "AWS::Lambda::EventSourceMapping" -> lambdaService.deleteEventSourceMapping(physicalId);
                case "AWS::Lambda::LayerVersion" -> deleteLambdaLayerVersion(physicalId, region);
                case "AWS::Cognito::UserPool" -> cognitoService.deleteUserPool(physicalId);
                case "AWS::Cognito::UserPoolClient" -> cognitoService.deleteUserPoolClient(physicalId);
                case "AWS::ECS::Cluster" -> ecsService.deleteCluster(physicalId, region);
                case "AWS::ECS::TaskDefinition" -> ecsService.deregisterTaskDefinition(physicalId, region);
                case "AWS::ECS::Service" -> deleteEcsServiceSafe(physicalId, region);
                case "AWS::ElasticLoadBalancingV2::LoadBalancer" -> elbV2Service.deleteLoadBalancer(region, physicalId);
                case "AWS::ElasticLoadBalancingV2::TargetGroup" -> elbV2Service.deleteTargetGroup(region, physicalId);
                case "AWS::ElasticLoadBalancingV2::Listener" -> elbV2Service.deleteListener(region, physicalId);
                case "AWS::ElasticLoadBalancingV2::ListenerRule" -> elbV2Service.deleteRule(region, physicalId);
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
        if (props != null) {
            if(props.has("VisibilityTimeout")) {
                attrs.put("VisibilityTimeout", engine.resolve(props.get("VisibilityTimeout")));
            }
            if(props.has("ContentBasedDeduplication")) {
                attrs.put("ContentBasedDeduplication", engine.resolve(props.get("ContentBasedDeduplication")));
            }
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

    // ── EC2 networking ─────────────────────────────────────────────────────────
    // Each method delegates to Ec2Service so the resource really exists (describe-subnets,
    // ELBv2 create-load-balancer, etc. resolve it). physicalId is set to the real EC2 id so
    // Ref/exports resolve to a real vpc-/subnet-/... id rather than a stub.

    private void provisionVpc(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String cidr = resolveOptional(props, "CidrBlock", engine);
        var vpc = ec2Service.createVpc(region, cidr, false);
        r.setPhysicalId(vpc.getVpcId());
        r.getAttributes().put("VpcId", vpc.getVpcId());
        if (vpc.getCidrBlock() != null) {
            r.getAttributes().put("CidrBlock", vpc.getCidrBlock());
        }
    }

    private void provisionSubnet(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String vpcId = resolveOptional(props, "VpcId", engine);
        String cidr = resolveOptional(props, "CidrBlock", engine);
        String az = resolveOptional(props, "AvailabilityZone", engine);
        var subnet = ec2Service.createSubnet(region, vpcId, cidr, az);
        r.setPhysicalId(subnet.getSubnetId());
        r.getAttributes().put("SubnetId", subnet.getSubnetId());
        r.getAttributes().put("VpcId", subnet.getVpcId());
        r.getAttributes().put("AvailabilityZone", subnet.getAvailabilityZone());
    }

    private void provisionSecurityGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                        String region, String stackName) {
        String groupName = resolveOptional(props, "GroupName", engine);
        if (groupName == null || groupName.isBlank()) {
            groupName = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }
        String description = resolveOptional(props, "GroupDescription", engine);
        if (description == null || description.isBlank()) {
            description = "Managed by CloudFormation";
        }
        String vpcId = resolveOptional(props, "VpcId", engine);
        var sg = ec2Service.createSecurityGroup(region, groupName, description, vpcId);
        // Ref on AWS::EC2::SecurityGroup returns the group id for VPC security groups.
        r.setPhysicalId(sg.getGroupId());
        r.getAttributes().put("GroupId", sg.getGroupId());
        if (sg.getVpcId() != null) {
            r.getAttributes().put("VpcId", sg.getVpcId());
        }
    }

    private void provisionInternetGateway(StackResource r, String region) {
        var igw = ec2Service.createInternetGateway(region);
        r.setPhysicalId(igw.getInternetGatewayId());
        r.getAttributes().put("InternetGatewayId", igw.getInternetGatewayId());
    }

    private void provisionRouteTable(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String vpcId = resolveOptional(props, "VpcId", engine);
        var rt = ec2Service.createRouteTable(region, vpcId);
        r.setPhysicalId(rt.getRouteTableId());
        r.getAttributes().put("RouteTableId", rt.getRouteTableId());
    }

    private void provisionSubnetRouteTableAssociation(StackResource r, JsonNode props,
                                                      CloudFormationTemplateEngine engine, String region) {
        String routeTableId = resolveOptional(props, "RouteTableId", engine);
        String subnetId = resolveOptional(props, "SubnetId", engine);
        var assoc = ec2Service.associateRouteTable(region, routeTableId, subnetId);
        r.setPhysicalId(assoc.getRouteTableAssociationId());
        r.getAttributes().put("Id", assoc.getRouteTableAssociationId());
    }

    private void provisionRoute(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String routeTableId = resolveOptional(props, "RouteTableId", engine);
        String destinationCidr = resolveOptional(props, "DestinationCidrBlock", engine);
        String gatewayId = resolveOptional(props, "GatewayId", engine);
        if (gatewayId == null || gatewayId.isBlank()) {
            gatewayId = resolveOptional(props, "NatGatewayId", engine);
        }
        ec2Service.createRoute(region, routeTableId, destinationCidr, gatewayId);
        r.setPhysicalId(r.getLogicalId() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void provisionNatGateway(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String subnetId = resolveOptional(props, "SubnetId", engine);
        String allocationId = resolveOptional(props, "AllocationId", engine);
        var nat = ec2Service.createNatGateway(region, subnetId, allocationId, "public", List.of());
        r.setPhysicalId(nat.getNatGatewayId());
        r.getAttributes().put("NatGatewayId", nat.getNatGatewayId());
    }

    private void provisionEip(StackResource r, String region) {
        var addr = ec2Service.allocateAddress(region);
        // Ref on AWS::EC2::EIP returns the public IP; AllocationId is exposed via Fn::GetAtt.
        r.setPhysicalId(addr.getPublicIp());
        r.getAttributes().put("AllocationId", addr.getAllocationId());
        r.getAttributes().put("PublicIp", addr.getPublicIp());
    }

    // ── SNS ───────────────────────────────────────────────────────────────────

    private void provisionSnsTopic(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId, String stackName) {
        String topicName = resolveOptional(props, "TopicName", engine);
        String contentBasedDedupFlag = resolveOptional(props, "ContentBasedDeduplication", engine);
        if (topicName == null || topicName.isBlank()) {
            topicName = generatePhysicalName(stackName, r.getLogicalId(), 256, false);
        }

        Map<String, String> attributes = new HashMap<>();

        if (contentBasedDedupFlag != null && !contentBasedDedupFlag.isBlank()) {
            attributes.put("ContentBasedDeduplication", contentBasedDedupFlag);
        }

        var topic = snsService.createTopic(topicName, attributes, Map.of(), region);
        r.setPhysicalId(topic.getTopicArn());
        r.getAttributes().put("Arn", topic.getTopicArn());
        r.getAttributes().put("TopicName", topicName);
    }

    private void provisionSnsSubscription(StackResource r, JsonNode props, CloudFormationTemplateEngine engine, String region) {
        String topicArn = engine.resolve(props.path("TopicArn"));
        String protocol = engine.resolve(props.path("Protocol"));
        String endpoint = engine.resolve(props.path("Endpoint"));

        Map<String, String> attributes = new HashMap<>();
        if (props.has("FilterPolicy") && !props.path("FilterPolicy").isNull()) {
            attributes.put("FilterPolicy", engine.resolveNode(props.path("FilterPolicy")).toString());
        }
        if (props.has("FilterPolicyScope")) {
            attributes.put("FilterPolicyScope", engine.resolve(props.path("FilterPolicyScope")));
        }
        if (props.has("RawMessageDelivery")) {
            attributes.put("RawMessageDelivery", engine.resolve(props.path("RawMessageDelivery")));
        }
        if (props.has("RedrivePolicy") && !props.path("RedrivePolicy").isNull()) {
            attributes.put("RedrivePolicy", engine.resolveNode(props.path("RedrivePolicy")).toString());
        }

        var sub = snsService.subscribe(topicArn, protocol, endpoint, region, attributes);
        r.setPhysicalId(sub.getSubscriptionArn());
        r.getAttributes().put("Arn", sub.getSubscriptionArn());
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
                    JsonNode batchParamsNode = resolved.path("BatchParameters");
                    if (!batchParamsNode.isMissingNode() && batchParamsNode.isObject()) {
                        JsonNode arrayProperties = batchParamsNode.path("ArrayProperties");
                        BatchParameters batchParameters = new BatchParameters();
                        batchParameters.setJobDefinition(batchParamsNode.path("JobDefinition").asText(null));
                        batchParameters.setJobName(batchParamsNode.path("JobName").asText(null));
                        if (arrayProperties.isObject()) {
                            batchParameters.setArrayProperties(jsonObjectToMap(arrayProperties));
                        }
                        if (batchParamsNode.has("RetryStrategy")) {
                            batchParameters.setRetryStrategy(batchParamsNode.get("RetryStrategy"));
                        }
                        target.setBatchParameters(batchParameters);
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

    // ── Batch ────────────────────────────────────────────────────────────────

    private void provisionBatchComputeEnvironment(StackResource r, JsonNode props,
                                                  CloudFormationTemplateEngine engine,
                                                  String region, String stackName) {
        String name = resolveOptional(props, "ComputeEnvironmentName", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("computeEnvironmentName", name);
        putResolvedText(req, "type", props, "Type", engine);
        putResolvedText(req, "state", props, "State", engine);
        putResolvedText(req, "serviceRole", props, "ServiceRole", engine);
        putResolvedObject(req, "computeResources", props, "ComputeResources", engine);
        putTagsObject(req, props, engine);

        ObjectNode response = batchService.createComputeEnvironment(req, region);
        String arn = response.path("computeEnvironmentArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("ComputeEnvironmentArn", arn);
        r.getAttributes().put("ComputeEnvironmentName", name);
    }

    private void provisionBatchJobQueue(StackResource r, JsonNode props,
                                        CloudFormationTemplateEngine engine,
                                        String region, String stackName) {
        String name = resolveOptional(props, "JobQueueName", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobQueueName", name);
        String priority = resolveOptional(props, "Priority", engine);
        req.put("priority", priority != null ? Integer.parseInt(priority) : 1);
        putResolvedText(req, "state", props, "State", engine);
        putResolvedText(req, "jobQueueType", props, "JobQueueType", engine);
        req.set("computeEnvironmentOrder", batchComputeEnvironmentOrder(props, engine));
        putTagsObject(req, props, engine);

        ObjectNode response = batchService.createJobQueue(req, region);
        String arn = response.path("jobQueueArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobQueueArn", arn);
        r.getAttributes().put("JobQueueName", name);
    }

    private void provisionBatchJobDefinition(StackResource r, JsonNode props,
                                             CloudFormationTemplateEngine engine,
                                             String region, String stackName) {
        String name = resolveOptional(props, "JobDefinitionName", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobDefinitionName", name);
        req.put("type", resolveOrDefault(props, "Type", engine, "container"));
        putResolvedArray(req, "platformCapabilities", props, "PlatformCapabilities", engine);
        if (props != null && props.has("ContainerProperties")) {
            req.set("containerProperties", batchContainerProperties(
                    engine.resolveNode(props.get("ContainerProperties")), engine));
        }
        putStringMapFromObject(req, "parameters", props, "Parameters", engine);
        if (props != null && props.has("RetryStrategy")) {
            req.set("retryStrategy", batchRetryStrategy(engine.resolveNode(props.get("RetryStrategy"))));
        }
        if (props != null && props.has("Timeout")) {
            ObjectNode timeout = JsonNodeFactory.instance.objectNode();
            JsonNode resolved = engine.resolveNode(props.get("Timeout"));
            if (resolved.has("AttemptDurationSeconds")) {
                timeout.set("attemptDurationSeconds", resolved.get("AttemptDurationSeconds"));
            }
            req.set("timeout", timeout);
        }
        putTagsObject(req, props, engine);

        ObjectNode response = batchService.registerJobDefinition(req, region);
        String arn = response.path("jobDefinitionArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobDefinitionArn", arn);
        r.getAttributes().put("JobDefinitionName", name);
        r.getAttributes().put("Revision", response.path("revision").asText());
    }

    private ArrayNode batchComputeEnvironmentOrder(JsonNode props, CloudFormationTemplateEngine engine) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (props == null || !props.has("ComputeEnvironmentOrder")) {
            return out;
        }
        JsonNode resolved = engine.resolveNode(props.get("ComputeEnvironmentOrder"));
        if (!resolved.isArray()) {
            return out;
        }
        for (JsonNode item : resolved) {
            ObjectNode order = out.addObject();
            order.put("order", item.path("Order").asInt());
            order.put("computeEnvironment", item.path("ComputeEnvironment").asText(null));
        }
        return out;
    }

    private ObjectNode batchContainerProperties(JsonNode resolved, CloudFormationTemplateEngine engine) {
        ObjectNode container = JsonNodeFactory.instance.objectNode();
        if (resolved == null || !resolved.isObject()) {
            return container;
        }
        copyIfPresent(container, "image", resolved, "Image");
        copyIfPresent(container, "command", resolved, "Command");
        copyIfPresent(container, "jobRoleArn", resolved, "JobRoleArn");
        copyIfPresent(container, "executionRoleArn", resolved, "ExecutionRoleArn");
        copyIfPresent(container, "logConfiguration", resolved, "LogConfiguration");
        copyIfPresent(container, "networkConfiguration", resolved, "NetworkConfiguration");
        copyIfPresent(container, "ephemeralStorage", resolved, "EphemeralStorage");
        if (resolved.has("ResourceRequirements") && resolved.get("ResourceRequirements").isArray()) {
            ArrayNode resources = container.putArray("resourceRequirements");
            for (JsonNode item : resolved.get("ResourceRequirements")) {
                ObjectNode requirement = resources.addObject();
                requirement.put("type", item.path("Type").asText(null));
                requirement.put("value", item.path("Value").asText(null));
            }
        }
        if (resolved.has("Environment") && resolved.get("Environment").isArray()) {
            ArrayNode env = container.putArray("environment");
            for (JsonNode item : resolved.get("Environment")) {
                ObjectNode entry = env.addObject();
                entry.put("name", item.path("Name").asText(null));
                entry.put("value", item.path("Value").asText(null));
            }
        }
        return container;
    }

    private ObjectNode batchRetryStrategy(JsonNode resolved) {
        ObjectNode retry = JsonNodeFactory.instance.objectNode();
        if (resolved == null || !resolved.isObject()) {
            return retry;
        }
        if (resolved.has("Attempts")) {
            retry.set("attempts", resolved.get("Attempts"));
        }
        if (resolved.has("EvaluateOnExit")) {
            retry.set("evaluateOnExit", resolved.get("EvaluateOnExit"));
        }
        return retry;
    }

    private void putResolvedText(ObjectNode req, String target, JsonNode props, String source,
                                 CloudFormationTemplateEngine engine) {
        String value = resolveOptional(props, source, engine);
        if (value != null) {
            req.put(target, value);
        }
    }

    private void putResolvedObject(ObjectNode req, String target, JsonNode props, String source,
                                   CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved != null && resolved.isObject()) {
            req.set(target, resolved);
        }
    }

    private void putResolvedArray(ObjectNode req, String target, JsonNode props, String source,
                                  CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved != null && resolved.isArray()) {
            req.set(target, resolved);
        }
    }

    private void putStringMapFromObject(ObjectNode req, String target, JsonNode props, String source,
                                        CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (!resolved.isObject()) {
            return;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        resolved.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        req.set(target, out);
    }

    private void putTagsObject(ObjectNode req, JsonNode props, CloudFormationTemplateEngine engine) {
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        if (!tags.isEmpty()) {
            ObjectNode tagNode = req.putObject("tags");
            tags.forEach(tagNode::put);
        }
    }

    private void copyIfPresent(ObjectNode target, String targetName, JsonNode source, String sourceName) {
        if (source.has(sourceName) && !source.get(sourceName).isNull()) {
            target.set(targetName, source.get(sourceName));
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

    private void provisionStepFunctionsStateMachine(StackResource r, JsonNode props,
                                                    CloudFormationTemplateEngine engine,
                                                    String region, String stackName) {
        String name = resolveOptional(props, "StateMachineName", engine);
        if (name == null || name.isBlank()) {
            name = generatePhysicalName(stackName, r.getLogicalId(), 80, false);
        }

        String roleArn = resolveOptional(props, "RoleArn", engine);
        String type = resolveOptional(props, "StateMachineType", engine);
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);

        String definition = resolveStateMachineDefinition(props, engine);

        StateMachine sm = stepFunctionsService.createStateMachine(name, definition, roleArn, type, region, tags);

        r.setPhysicalId(sm.getStateMachineArn());
        r.getAttributes().put("Arn", sm.getStateMachineArn());
        r.getAttributes().put("Name", sm.getName());
    }

    private String resolveStateMachineDefinition(JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null) {
            return null;
        }

        String definition = resolveOptional(props, "DefinitionString", engine);
        if (definition == null && props.has("Definition") && !props.get("Definition").isNull()) {
            definition = engine.resolveNode(props.get("Definition")).toString();
        }
        if (definition == null) {
            return null;
        }

        JsonNode subsNode = props.get("DefinitionSubstitutions");
        if (subsNode == null || subsNode.isNull()) {
            return definition;
        }

        JsonNode resolvedSubs = engine.resolveNode(subsNode);
        Iterator<Map.Entry<String, JsonNode>> entries = resolvedSubs.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString();
            definition = definition.replace(placeholder, value);
        }
        return definition;
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

        if (props.has("EndpointConfiguration")) {
            JsonNode epNode = props.get("EndpointConfiguration");
            Map<String, Object> epReq = new HashMap<>();
            epReq.put("types", resolveStringListOrEmpty(epNode, "Types", engine));
            epReq.put("vpcEndpointIds", resolveStringListOrEmpty(epNode, "VpcEndpointIds", engine));
            req.put("endpointConfiguration", epReq);
        }

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
        req.put("routeSelectionExpression", resolveOptional(props, "RouteSelectionExpression", engine));
        req.put("description", resolveOptional(props, "Description", engine));
        req.put("apiKeySelectionExpression", resolveOptional(props, "ApiKeySelectionExpression", engine));

        Map<String, String> tags = parseApiGatewayV2Tags(props != null ? props.get("Tags") : null, engine);
        if (!tags.isEmpty()) {
            req.put("tags", tags);
        }

        Map<String, Object> cors = parseApiGatewayV2Cors(props != null ? props.get("CorsConfiguration") : null, engine);
        if (cors != null) {
            req.put("corsConfiguration", cors);
        }

        Api api;
        if (r.getPhysicalId() == null) {
            api = apiGatewayV2Service.createApi(region, req);
        } else {
            api = apiGatewayV2Service.updateApi(region, r.getPhysicalId(), req);
        }
        r.setPhysicalId(api.getApiId());
        r.getAttributes().put("ApiEndpoint", api.getApiEndpoint());
    }

    private Map<String, String> parseApiGatewayV2Tags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return out;
        }
        JsonNode resolved = engine.resolveNode(tagsNode);
        if (!resolved.isObject()) {
            return out;
        }
        resolved.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
    }

    private Map<String, Object> parseApiGatewayV2Cors(JsonNode corsNode, CloudFormationTemplateEngine engine) {
        if (corsNode == null || corsNode.isNull()) {
            return null;
        }
        JsonNode resolved = engine.resolveNode(corsNode);
        if (!resolved.isObject()) {
            return null;
        }
        Map<String, Object> out = new HashMap<>();
        resolved.properties().forEach(e -> {
            String key = e.getKey();
            String camel = key.isEmpty() || !Character.isUpperCase(key.charAt(0))
                    ? key
                    : Character.toLowerCase(key.charAt(0)) + key.substring(1);
            JsonNode v = e.getValue();
            if (v.isArray()) {
                List<String> list = new ArrayList<>();
                v.forEach(item -> list.add(item.asText()));
                out.put(camel, list);
            } else if (v.isBoolean()) {
                out.put(camel, v.booleanValue());
            } else if (v.isNumber()) {
                out.put(camel, v.numberValue());
            } else if (!v.isNull()) {
                out.put(camel, v.asText());
            }
        });
        return out;
    }

    private void provisionApiGatewayV2Route(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("routeKey", resolveOptional(props, "RouteKey", engine));
        req.put("authorizationType", resolveOrDefault(props, "AuthorizationType", engine, "NONE"));
        req.put("target", resolveOptional(props, "Target", engine));

        Route route;
        if (r.getPhysicalId() == null) {
            route = apiGatewayV2Service.createRoute(region, apiId, req);
        } else {
            route = apiGatewayV2Service.updateRoute(region, apiId, r.getPhysicalId(), req);
        }
        r.setPhysicalId(route.getRouteId());
    }

    private void provisionApiGatewayV2Integration(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                                  String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("integrationType", resolveOptional(props, "IntegrationType", engine));
        req.put("integrationUri", resolveOptional(props, "IntegrationUri", engine));
        req.put("payloadFormatVersion", resolveOrDefault(props, "PayloadFormatVersion", engine, "2.0"));

        Integration integration;
        if (r.getPhysicalId() == null) {
            integration = apiGatewayV2Service.createIntegration(region, apiId, req);
        } else {
            integration = apiGatewayV2Service.updateIntegration(region, apiId, r.getPhysicalId(), req);
        }
        r.setPhysicalId(integration.getIntegrationId());
    }

    private void provisionApiGatewayV2Stage(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region) {
        String apiId = resolveOptional(props, "ApiId", engine);
        String stageName = resolveOptional(props, "StageName", engine);

        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("autoDeploy", resolveOrDefault(props, "AutoDeploy", engine, "false"));
        putResolvedMapIfPresent(req, props, "StageVariables", "stageVariables", engine);

        if (r.getPhysicalId() == null) {
            apiGatewayV2Service.createStage(region, apiId, req);
            r.setPhysicalId(stageName);
        } else {
            apiGatewayV2Service.updateStage(region, apiId, r.getPhysicalId(), req);
        }
    }

    private void provisionApiGatewayV2Deployment(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                                 String region) {
        // Deployments are immutable point-in-time snapshots; on redeploy keep the existing one
        // rather than minting a duplicate (idempotent re-deploy).
        if (r.getPhysicalId() != null) {
            return;
        }
        String apiId = resolveOptional(props, "ApiId", engine);
        Map<String, Object> req = new HashMap<>();
        req.put("description", resolveOptional(props, "Description", engine));

        Deployment deployment = apiGatewayV2Service.createDeployment(region, apiId, req);
        r.setPhysicalId(deployment.getDeploymentId());
    }

    // ── Cognito ──────────────────────────────────────────────────────────────

    private void provisionCognitoUserPool(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String region, String accountId, String stackName) {
        String poolName = resolveOptional(props, "UserPoolName", engine);
        if (poolName == null || poolName.isBlank()) {
            poolName = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }

        Map<String, Object> req = new HashMap<>();
        if (props != null) {
            req.putAll(jsonObjectToMap(engine.resolveNode(props)));
        }
        req.put("PoolName", poolName);

        // Handle Tags
        Map<String, String> tags = parseCfnTags(props != null ? props.get("UserPoolTags") : null, engine);
        if (!tags.isEmpty()) {
            req.put("UserPoolTags", tags);
        }

        UserPool pool;
        if (r.getPhysicalId() == null) {
            pool = cognitoService.createUserPool(req, region);
        } else {
            req.put("UserPoolId", r.getPhysicalId());
            pool = cognitoService.updateUserPool(req, region);
        }

        r.setPhysicalId(pool.getId());
        r.getAttributes().put("Arn", pool.getArn());
        r.getAttributes().put("UserPoolId", pool.getId());
        r.getAttributes().put("ProviderName", pool.getName());
        r.getAttributes().put("ProviderURL", cognitoService.getIssuer(pool.getId()));
    }

    private void provisionCognitoUserPoolClient(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                                String region, String accountId, String stackName) {
        String userPoolId = resolveOptional(props, "UserPoolId", engine);
        String clientName = resolveOptional(props, "ClientName", engine);
        if (clientName == null || clientName.isBlank()) {
            clientName = generatePhysicalName(stackName, r.getLogicalId(), 128, false);
        }
        boolean generateSecret = Boolean.parseBoolean(resolveOrDefault(props, "GenerateSecret", engine, "false"));
        boolean allowedOAuthFlowsUserPoolClient = Boolean.parseBoolean(resolveOrDefault(props, "AllowedOAuthFlowsUserPoolClient", engine, "false"));
        List<String> allowedOAuthFlows = resolveStringListOrEmpty(props, "AllowedOAuthFlows", engine);
        List<String> allowedOAuthScopes = resolveStringListOrEmpty(props, "AllowedOAuthScopes", engine);

        UserPoolClient client;
        if (r.getPhysicalId() == null) {
            client = cognitoService.createUserPoolClient(userPoolId, clientName, generateSecret,
                    allowedOAuthFlowsUserPoolClient, allowedOAuthFlows, allowedOAuthScopes);
        } else {
            client = cognitoService.updateUserPoolClient(userPoolId, r.getPhysicalId(), clientName,
                    allowedOAuthFlowsUserPoolClient, allowedOAuthFlows, allowedOAuthScopes);
        }

        r.setPhysicalId(client.getClientId());
        r.getAttributes().put("ClientId", client.getClientId());
        r.getAttributes().put("ClientName", client.getClientName());
        if (client.getClientSecret() != null) {
            r.getAttributes().put("ClientSecret", client.getClientSecret());
        }
    }

    // ── Lambda LayerVersion ──────────────────────────────────────────────────
    //
    // Without this, layer versions (e.g. CDK's AwsCliLayer) fall through to the stub, so the
    // function's Layers ARN can't be resolved and the layer content is never copied into /opt.

    private void provisionLambdaLayerVersion(StackResource r, JsonNode props,
                                             CloudFormationTemplateEngine engine, String region,
                                             String stackName) {
        if (props == null || !props.has("Content")) {
            throw new AwsException("ValidationError",
                    "Lambda LayerVersion " + r.getLogicalId() + " is missing Content", 400);
        }
        String layerName = resolveOptional(props, "LayerName", engine);
        if (layerName == null || layerName.isBlank()) {
            layerName = generatePhysicalName(stackName, r.getLogicalId(), 140, false);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("Content", jsonObjectToMap(engine.resolveNode(props.get("Content"))));
        String description = resolveOptional(props, "Description", engine);
        if (description != null) {
            request.put("Description", description);
        }
        String licenseInfo = resolveOptional(props, "LicenseInfo", engine);
        if (licenseInfo != null) {
            request.put("LicenseInfo", licenseInfo);
        }
        List<String> runtimes = resolveStringListOrEmpty(props, "CompatibleRuntimes", engine);
        if (!runtimes.isEmpty()) {
            request.put("CompatibleRuntimes", runtimes);
        }
        List<String> architectures = resolveStringListOrEmpty(props, "CompatibleArchitectures", engine);
        if (!architectures.isEmpty()) {
            request.put("CompatibleArchitectures", architectures);
        }

        LambdaLayerVersion layer = lambdaLayerService.publishLayerVersion(region, layerName, request);
        // CloudFormation Ref on a LayerVersion returns the version ARN; the Lambda's Layers list
        // references it, and ContainerLauncher resolves it back to disk via resolveLayerByArn.
        r.setPhysicalId(layer.getLayerVersionArn());
        r.getAttributes().put("Arn", layer.getLayerVersionArn());
        r.getAttributes().put("LayerVersionArn", layer.getLayerVersionArn());
    }

    private void deleteLambdaLayerVersion(String physicalId, String region) {
        LambdaLayerVersion layer = lambdaLayerService.resolveLayerByArn(physicalId);
        if (layer != null) {
            lambdaLayerService.deleteLayerVersion(region, layer.getLayerName(), layer.getVersion());
        }
    }

    // ── CloudFormation Custom Resources ──────────────────────────────────────
    //
    // A Custom::* / AWS::CloudFormation::CustomResource is backed by a Lambda named by its
    // ServiceToken. CloudFormation invokes that Lambda with a request event and the Lambda PUTs its
    // result to the event's ResponseURL (it does NOT return it). Floci points ResponseURL at
    // CfnResponseController and, because the invoke is synchronous, reads the captured response as
    // soon as the handler returns. Pattern 1 only — single-Lambda synchronous handlers (e.g. CDK
    // BucketDeployment). The async Provider framework (onEvent/isComplete polling) is not emulated.

    private void provisionCustomResource(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                         String region, String accountId, String stackName) {
        if (props == null || !props.has("ServiceToken")) {
            throw new AwsException("ValidationError",
                    "Custom resource " + r.getLogicalId() + " is missing ServiceToken", 400);
        }
        String serviceToken = engine.resolve(props.get("ServiceToken"));
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new AwsException("ValidationError",
                    "Custom resource " + r.getLogicalId() + " has an unresolved ServiceToken", 400);
        }

        // Resolve intrinsics to concrete values. CloudFormation keeps ServiceToken inside
        // ResourceProperties (and also surfaces it at the top level of the event), so we leave it
        // in place here. CloudFormation stringifies every scalar in ResourceProperties
        // (true -> "true", 5 -> "5") while preserving list/map structure; handlers (e.g. CDK's)
        // rely on this and call String methods on the values, so we must match it.
        JsonNode resolvedProps = engine.resolveNode(props);
        ObjectNode resolved = resolvedProps.isObject()
                ? ((ObjectNode) resolvedProps).deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resourceProperties = (ObjectNode) stringifyScalars(resolved);

        boolean isUpdate = r.getPhysicalId() != null;
        String requestType = isUpdate ? "Update" : "Create";
        String priorPhysicalId = isUpdate ? r.getPhysicalId() : null;

        // On Update, CloudFormation includes the previous ResourceProperties so the handler can diff.
        // The prior values were stashed at the last create/update; read them before we overwrite below.
        ObjectNode oldResourceProperties = isUpdate ? readStashedProperties(r) : null;

        JsonNode response = invokeCustomResourceHandler(serviceToken, requestType, r.getLogicalId(),
                r.getResourceType(), priorPhysicalId, resourceProperties, oldResourceProperties,
                region, accountId, stackName);

        String status = response.path("Status").asText("FAILED");
        if (!"SUCCESS".equals(status)) {
            throw new AwsException("CustomResourceFailed",
                    "Custom resource handler reported FAILED: "
                            + response.path("Reason").asText("(no reason given)"), 400);
        }

        String returnedPhysicalId = response.path("PhysicalResourceId").asText(null);
        if (returnedPhysicalId != null && !returnedPhysicalId.isBlank()) {
            r.setPhysicalId(returnedPhysicalId);
        } else if (priorPhysicalId != null) {
            r.setPhysicalId(priorPhysicalId);
        } else {
            r.setPhysicalId(r.getLogicalId() + "-" + UUID.randomUUID().toString().substring(0, 12));
        }

        // Data.* become Fn::GetAtt attributes on the custom resource.
        JsonNode data = response.path("Data");
        if (data.isObject()) {
            data.fields().forEachRemaining(e ->
                    r.getAttributes().put(e.getKey(), nodeToAttributeValue(e.getValue())));
        }

        // Stash what a later Delete invocation needs (delete() only gets the StackResource).
        r.getAttributes().put(CR_SERVICE_TOKEN_ATTR, serviceToken);
        r.getAttributes().put(CR_PROPERTIES_ATTR, resourceProperties.toString());
    }

    private void deleteCustomResource(StackResource r, String region) {
        String serviceToken = r.getAttributes().get(CR_SERVICE_TOKEN_ATTR);
        if (serviceToken == null || serviceToken.isBlank()) {
            LOG.debugv("Custom resource {0} has no stored ServiceToken; skipping Delete", r.getLogicalId());
            return;
        }
        ObjectNode stashed = readStashedProperties(r);
        ObjectNode resourceProperties = stashed != null ? stashed : objectMapper.createObjectNode();
        try {
            JsonNode response = invokeCustomResourceHandler(serviceToken, "Delete", r.getLogicalId(),
                    r.getResourceType(), r.getPhysicalId(), resourceProperties, null, region,
                    accountFromArn(serviceToken), "");
            if (!"SUCCESS".equals(response.path("Status").asText("FAILED"))) {
                LOG.warnv("Custom resource {0} Delete reported FAILED: {1}",
                        r.getLogicalId(), response.path("Reason").asText("(no reason given)"));
            }
        } catch (Exception e) {
            // Best-effort, consistent with the rest of delete().
            LOG.debugv("Custom resource {0} Delete invocation failed: {1}", r.getLogicalId(), e.getMessage());
        }
    }

    // Reads the ResourceProperties stashed at the last create/update (CR_PROPERTIES_ATTR).
    // Returns null when nothing is stashed or it cannot be parsed.
    private ObjectNode readStashedProperties(StackResource r) {
        String stored = r.getAttributes().get(CR_PROPERTIES_ATTR);
        if (stored == null) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(stored);
            return parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (Exception e) {
            LOG.debugv("Could not parse stored properties for custom resource {0}: {1}",
                    r.getLogicalId(), e.getMessage());
            return null;
        }
    }

    private JsonNode invokeCustomResourceHandler(String serviceToken, String requestType, String logicalId,
                                                 String resourceType, String physicalId,
                                                 ObjectNode resourceProperties, ObjectNode oldResourceProperties,
                                                 String region, String accountId, String stackName) {
        String token = customResourceResponseStore.register();
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("RequestType", requestType);
            event.put("ResponseURL", reachableEndpoint.baseUrl() + "/cfn-response/" + token);
            event.put("StackId", "arn:aws:cloudformation:" + region + ":" + accountId + ":stack/"
                    + (stackName == null ? "" : stackName) + "/" + UUID.randomUUID());
            event.put("RequestId", UUID.randomUUID().toString());
            event.put("ResourceType", resourceType);
            event.put("LogicalResourceId", logicalId);
            if (physicalId != null) {
                event.put("PhysicalResourceId", physicalId);
            }
            event.put("ServiceToken", serviceToken);
            event.set("ResourceProperties", resourceProperties);
            if (oldResourceProperties != null) {
                event.set("OldResourceProperties", oldResourceProperties);
            }

            byte[] payload = objectMapper.writeValueAsBytes(event);
            InvokeResult result = lambdaService.invoke(region, serviceToken, payload,
                    InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                String body = result.getPayload() != null
                        ? new String(result.getPayload(), StandardCharsets.UTF_8) : "";
                throw new AwsException("CustomResourceFailed",
                        "Custom resource handler errored (" + result.getFunctionError() + "): " + body, 400);
            }

            return customResourceResponseStore.await(token, CR_RESPONSE_TIMEOUT);
        } catch (AwsException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new AwsException("CustomResourceTimeout",
                    "Timed out waiting for custom resource " + logicalId
                            + " to PUT its response to ResponseURL", 504);
        } catch (Exception e) {
            throw new AwsException("CustomResourceFailed",
                    "Failed to invoke custom resource " + logicalId + ": " + e.getMessage(), 500);
        }
    }

    private static String nodeToAttributeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /**
     * Mirrors CloudFormation's stringification of custom-resource ResourceProperties: every scalar
     * (boolean, number, text) becomes a string, while object and array structure is preserved.
     * Null is left as-is.
     */
    private JsonNode stringifyScalars(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), stringifyScalars(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            var out = objectMapper.createArrayNode();
            node.forEach(e -> out.add(stringifyScalars(e)));
            return out;
        }
        return objectMapper.getNodeFactory().textNode(node.asText());
    }

    private static String accountFromArn(String arn) {
        if (arn == null) {
            return "000000000000";
        }
        String[] parts = arn.split(":");
        return parts.length >= 5 && parts[4].matches("\\d{12}") ? parts[4] : "000000000000";
    }

    // ── ECS ──────────────────────────────────────────────────────────────────

    private void provisionEcsCluster(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                     String region, String stackName) {
        String clusterName = resolveOptional(props, "ClusterName", engine);
        if (clusterName == null || clusterName.isBlank()) {
            clusterName = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }
        // createCluster is idempotent, so re-running it on a stack update reuses the existing cluster.
        EcsCluster cluster = ecsService.createCluster(clusterName, region);
        r.setPhysicalId(cluster.getClusterName());
        r.getAttributes().put("Arn", cluster.getClusterArn());
    }

    private void provisionEcsTaskDefinition(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                            String region, String stackName) {
        String family = resolveOptional(props, "Family", engine);
        if (family == null || family.isBlank()) {
            family = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }
        List<ContainerDefinition> containerDefs =
                parseContainerDefinitions(props != null ? props.get("ContainerDefinitions") : null, engine);
        NetworkMode networkMode = parseNetworkMode(resolveOptional(props, "NetworkMode", engine));
        String cpu = resolveOptional(props, "Cpu", engine);
        String memory = resolveOptional(props, "Memory", engine);
        String taskRoleArn = resolveOptional(props, "TaskRoleArn", engine);
        String executionRoleArn = resolveOptional(props, "ExecutionRoleArn", engine);
        List<String> requiresCompatibilities = resolveStringListOrEmpty(props, "RequiresCompatibilities", engine);

        // Task definitions are immutable; each CFN update registers a fresh revision.
        TaskDefinition td = ecsService.registerTaskDefinition(family, containerDefs, networkMode, cpu, memory,
                taskRoleArn, executionRoleArn, requiresCompatibilities, region);

        r.setPhysicalId(td.getTaskDefinitionArn());
        r.getAttributes().put("TaskDefinitionArn", td.getTaskDefinitionArn());
    }

    private void provisionEcsService(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                     String region, String stackName) {
        String clusterRef = resolveOptional(props, "Cluster", engine);
        String taskDefinition = resolveOptional(props, "TaskDefinition", engine);
        int desiredCount = intOrDefault(resolveOptional(props, "DesiredCount", engine), 1);
        LaunchType launchType = parseLaunchType(resolveOptional(props, "LaunchType", engine));
        List<EcsLoadBalancer> loadBalancers =
                parseEcsLoadBalancers(props != null ? props.get("LoadBalancers") : null, engine);
        NetworkConfiguration networkConfiguration =
                parseEcsNetworkConfiguration(props != null ? props.get("NetworkConfiguration") : null, engine);

        String serviceName = resolveOptional(props, "ServiceName", engine);
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = r.getAttributes().get("Name");
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = generatePhysicalName(stackName, r.getLogicalId(), 255, false);
        }

        EcsServiceModel svc;
        if (r.getPhysicalId() == null) {
            svc = ecsService.createService(clusterRef, serviceName, taskDefinition,
                    desiredCount, launchType, loadBalancers, networkConfiguration, region);
        } else {
            svc = ecsService.updateService(clusterRef, serviceName, taskDefinition,
                    desiredCount, networkConfiguration, region);
        }

        r.setPhysicalId(svc.getServiceArn());
        r.getAttributes().put("Name", svc.getServiceName());
        r.getAttributes().put("ServiceArn", svc.getServiceArn());
    }

    private void deleteEcsServiceSafe(String serviceArn, String region) {
        // Floci service ARNs embed the cluster: arn:aws:ecs:<region>:<acct>:service/<cluster>/<service>.
        // Parse both so the right cluster's tasks get stopped during teardown.
        String clusterRef = null;
        String serviceName = serviceArn;
        try {
            String[] segments = AwsArnUtils.parse(serviceArn).resource().split("/");
            if (segments.length == 3) {
                clusterRef = segments[1];
                serviceName = segments[2];
            } else if (segments.length == 2) {
                // Legacy ARN format without an embedded cluster: service/<service>.
                serviceName = segments[1];
            }
        } catch (IllegalArgumentException e) {
            // Not an ARN; treat the value as a bare service name.
        }
        ecsService.deleteService(clusterRef, serviceName, true, region);
    }

    private List<ContainerDefinition> parseContainerDefinitions(JsonNode node, CloudFormationTemplateEngine engine) {
        List<ContainerDefinition> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = engine.resolveNode(node);
        if (resolved == null || !resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            ContainerDefinition def = new ContainerDefinition();
            def.setName(item.path("Name").asText(null));
            def.setImage(item.path("Image").asText(null));
            def.setEssential(item.path("Essential").asBoolean(true));
            if (item.hasNonNull("Cpu")) {
                def.setCpu(item.path("Cpu").asInt());
            }
            if (item.hasNonNull("Memory")) {
                def.setMemory(item.path("Memory").asInt());
            }
            if (item.hasNonNull("MemoryReservation")) {
                def.setMemoryReservation(item.path("MemoryReservation").asInt());
            }
            def.setPortMappings(parseCfnPortMappings(item.path("PortMappings")));
            def.setEnvironment(parseCfnEnvironment(item.path("Environment")));
            if (item.path("Command").isArray()) {
                List<String> cmd = new ArrayList<>();
                item.path("Command").forEach(c -> cmd.add(c.asText()));
                def.setCommand(cmd);
            }
            if (item.path("EntryPoint").isArray()) {
                List<String> ep = new ArrayList<>();
                item.path("EntryPoint").forEach(e -> ep.add(e.asText()));
                def.setEntryPoint(ep);
            }
            result.add(def);
        }
        return result;
    }

    private List<PortMapping> parseCfnPortMappings(JsonNode node) {
        List<PortMapping> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            int containerPort = item.path("ContainerPort").asInt(0);
            int hostPort = item.path("HostPort").asInt(0);
            String protocol = item.path("Protocol").asText("tcp");
            result.add(new PortMapping(containerPort, hostPort, protocol));
        }
        return result;
    }

    private List<KeyValuePair> parseCfnEnvironment(JsonNode node) {
        List<KeyValuePair> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new KeyValuePair(item.path("Name").asText(), item.path("Value").asText()));
        }
        return result;
    }

    private List<EcsLoadBalancer> parseEcsLoadBalancers(JsonNode node, CloudFormationTemplateEngine engine) {
        List<EcsLoadBalancer> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = engine.resolveNode(node);
        if (resolved == null || !resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            EcsLoadBalancer lb = new EcsLoadBalancer();
            if (item.hasNonNull("TargetGroupArn")) {
                lb.setTargetGroupArn(item.path("TargetGroupArn").asText());
            }
            if (item.hasNonNull("LoadBalancerName")) {
                lb.setLoadBalancerName(item.path("LoadBalancerName").asText());
            }
            if (item.hasNonNull("ContainerName")) {
                lb.setContainerName(item.path("ContainerName").asText());
            }
            if (item.hasNonNull("ContainerPort")) {
                lb.setContainerPort(item.path("ContainerPort").asInt());
            }
            result.add(lb);
        }
        return result;
    }

    private NetworkConfiguration parseEcsNetworkConfiguration(JsonNode node, CloudFormationTemplateEngine engine) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode resolved = engine.resolveNode(node);
        if (resolved == null || !resolved.isObject() || !resolved.hasNonNull("AwsvpcConfiguration")) {
            return null;
        }
        JsonNode awsvpc = resolved.path("AwsvpcConfiguration");
        AwsVpcConfiguration awsvpcConfig = new AwsVpcConfiguration();
        awsvpcConfig.setSubnets(jsonArrayToStringList(awsvpc.path("Subnets")));
        awsvpcConfig.setSecurityGroups(jsonArrayToStringList(awsvpc.path("SecurityGroups")));
        if (awsvpc.hasNonNull("AssignPublicIp")) {
            awsvpcConfig.setAssignPublicIp(awsvpc.path("AssignPublicIp").asText());
        }
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpcConfig);
        return networkConfiguration;
    }

    private static List<String> jsonArrayToStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> result.add(v.asText()));
        }
        return result;
    }

    private static NetworkMode parseNetworkMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NetworkMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LaunchType parseLaunchType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LaunchType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── ELBv2 ────────────────────────────────────────────────────────────────

    private void provisionLoadBalancer(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                       String region, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generateElbName(stackName, r.getLogicalId());
        }
        String scheme = resolveOptional(props, "Scheme", engine);
        String type = resolveOptional(props, "Type", engine);
        String ipAddressType = resolveOptional(props, "IpAddressType", engine);
        List<String> subnets = resolveStringListOrEmpty(props, "Subnets", engine);
        List<String> securityGroups = resolveStringListOrEmpty(props, "SecurityGroups", engine);
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);

        LoadBalancer lb;
        try {
            lb = elbV2Service.createLoadBalancer(region, name, scheme, type, ipAddressType,
                    subnets, securityGroups, tags);
        } catch (AwsException e) {
            if ("DuplicateLoadBalancerName".equals(e.getErrorCode())) {
                lb = elbV2Service.describeLoadBalancers(region, null, List.of(name), null, null).get(0);
            } else {
                throw e;
            }
        }

        r.setPhysicalId(lb.getLoadBalancerArn());
        r.getAttributes().put("LoadBalancerArn", lb.getLoadBalancerArn());
        r.getAttributes().put("DNSName", lb.getDnsName());
        r.getAttributes().put("CanonicalHostedZoneID", lb.getCanonicalHostedZoneId());
        r.getAttributes().put("LoadBalancerName", lb.getLoadBalancerName());
        r.getAttributes().put("LoadBalancerFullName", loadBalancerFullName(lb.getLoadBalancerArn()));
    }

    private void provisionTargetGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                      String region, String stackName) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = generateElbName(stackName, r.getLogicalId());
        }
        String protocol = resolveOptional(props, "Protocol", engine);
        String protocolVersion = resolveOptional(props, "ProtocolVersion", engine);
        Integer port = parseIntOrNull(resolveOptional(props, "Port", engine));
        String vpcId = resolveOptional(props, "VpcId", engine);
        String targetType = resolveOptional(props, "TargetType", engine);
        String hcProtocol = resolveOptional(props, "HealthCheckProtocol", engine);
        String hcPort = resolveOptional(props, "HealthCheckPort", engine);
        Boolean hcEnabled = parseBooleanOrNull(resolveOptional(props, "HealthCheckEnabled", engine));
        String hcPath = resolveOptional(props, "HealthCheckPath", engine);
        Integer hcInterval = parseIntOrNull(resolveOptional(props, "HealthCheckIntervalSeconds", engine));
        Integer hcTimeout = parseIntOrNull(resolveOptional(props, "HealthCheckTimeoutSeconds", engine));
        Integer healthyThreshold = parseIntOrNull(resolveOptional(props, "HealthyThresholdCount", engine));
        Integer unhealthyThreshold = parseIntOrNull(resolveOptional(props, "UnhealthyThresholdCount", engine));
        String matcher = parseMatcher(props, engine);
        String ipAddressType = resolveOptional(props, "IpAddressType", engine);
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);

        TargetGroup tg;
        try {
            tg = elbV2Service.createTargetGroup(region, name, protocol, protocolVersion, port, vpcId, targetType,
                    hcProtocol, hcPort, hcEnabled, hcPath, hcInterval, hcTimeout,
                    healthyThreshold, unhealthyThreshold, matcher, ipAddressType, tags);
        } catch (AwsException e) {
            if ("DuplicateTargetGroupName".equals(e.getErrorCode())) {
                tg = elbV2Service.describeTargetGroups(region, null, null, List.of(name)).get(0);
            } else {
                throw e;
            }
        }

        r.setPhysicalId(tg.getTargetGroupArn());
        r.getAttributes().put("TargetGroupArn", tg.getTargetGroupArn());
        r.getAttributes().put("TargetGroupName", tg.getTargetGroupName());
        r.getAttributes().put("TargetGroupFullName", targetGroupFullName(tg.getTargetGroupArn()));
    }

    private void provisionListener(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region) {
        String lbArn = resolveOptional(props, "LoadBalancerArn", engine);
        String protocol = resolveOrDefault(props, "Protocol", engine, "HTTP");
        int port = intOrDefault(resolveOptional(props, "Port", engine), 80);
        String sslPolicy = resolveOptional(props, "SslPolicy", engine);
        List<String> certificates = parseCertificates(props, engine);
        List<Action> defaultActions = parseCfnActions(props != null ? props.get("DefaultActions") : null, engine);

        Listener listener;
        if (r.getPhysicalId() == null) {
            listener = elbV2Service.createListener(region, lbArn, protocol, port, sslPolicy, certificates,
                    defaultActions, null, Map.of());
        } else {
            listener = elbV2Service.modifyListener(region, r.getPhysicalId(), protocol, port, sslPolicy,
                    certificates, defaultActions, null);
        }

        r.setPhysicalId(listener.getListenerArn());
        r.getAttributes().put("ListenerArn", listener.getListenerArn());
    }

    private void provisionListenerRule(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                       String region) {
        String listenerArn = resolveOptional(props, "ListenerArn", engine);
        int priority = intOrDefault(resolveOptional(props, "Priority", engine), 1);
        List<RuleCondition> conditions =
                parseCfnRuleConditions(props != null ? props.get("Conditions") : null, engine);
        List<Action> actions = parseCfnActions(props != null ? props.get("Actions") : null, engine);

        Rule rule;
        if (r.getPhysicalId() == null) {
            rule = elbV2Service.createRule(region, listenerArn, conditions, priority, actions, Map.of());
        } else {
            rule = elbV2Service.modifyRule(region, r.getPhysicalId(), conditions, actions);
        }

        r.setPhysicalId(rule.getRuleArn());
        r.getAttributes().put("RuleArn", rule.getRuleArn());
        r.getAttributes().put("IsDefault", String.valueOf(rule.isDefault()));
    }

    private List<Action> parseCfnActions(JsonNode node, CloudFormationTemplateEngine engine) {
        List<Action> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = engine.resolveNode(node);
        if (!resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            Action action = new Action();
            action.setType(textOrNull(item, "Type"));
            if (item.hasNonNull("Order")) {
                action.setOrder(item.path("Order").asInt());
            }
            if (item.hasNonNull("TargetGroupArn")) {
                action.setTargetGroupArn(item.path("TargetGroupArn").asText());
            }
            JsonNode forward = item.path("ForwardConfig");
            if (forward.isObject()) {
                JsonNode tgs = forward.path("TargetGroups");
                if (tgs.isArray()) {
                    List<Action.TargetGroupTuple> tuples = new ArrayList<>();
                    for (JsonNode t : tgs) {
                        Action.TargetGroupTuple tuple = new Action.TargetGroupTuple();
                        if (t.hasNonNull("TargetGroupArn")) {
                            tuple.setTargetGroupArn(t.path("TargetGroupArn").asText());
                        }
                        if (t.hasNonNull("Weight")) {
                            tuple.setWeight(t.path("Weight").asInt());
                        }
                        tuples.add(tuple);
                    }
                    action.setTargetGroups(tuples);
                }
                JsonNode stickiness = forward.path("TargetGroupStickinessConfig");
                if (stickiness.isObject()) {
                    if (stickiness.hasNonNull("Enabled")) {
                        action.setStickinessEnabled(stickiness.path("Enabled").asBoolean());
                    }
                    if (stickiness.hasNonNull("DurationSeconds")) {
                        action.setStickinessDurationSeconds(stickiness.path("DurationSeconds").asInt());
                    }
                }
            }
            JsonNode redirect = item.path("RedirectConfig");
            if (redirect.isObject()) {
                action.setRedirectProtocol(textOrNull(redirect, "Protocol"));
                action.setRedirectPort(textOrNull(redirect, "Port"));
                action.setRedirectHost(textOrNull(redirect, "Host"));
                action.setRedirectPath(textOrNull(redirect, "Path"));
                action.setRedirectQuery(textOrNull(redirect, "Query"));
                action.setRedirectStatusCode(textOrNull(redirect, "StatusCode"));
            }
            JsonNode fixed = item.path("FixedResponseConfig");
            if (fixed.isObject()) {
                action.setFixedResponseStatusCode(textOrNull(fixed, "StatusCode"));
                action.setFixedResponseContentType(textOrNull(fixed, "ContentType"));
                action.setFixedResponseMessageBody(textOrNull(fixed, "MessageBody"));
            }
            result.add(action);
        }
        return result;
    }

    private List<RuleCondition> parseCfnRuleConditions(JsonNode node, CloudFormationTemplateEngine engine) {
        List<RuleCondition> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = engine.resolveNode(node);
        if (!resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            RuleCondition condition = new RuleCondition();
            condition.setField(textOrNull(item, "Field"));
            if (item.path("Values").isArray()) {
                condition.setValues(jsonArrayToStringList(item.path("Values")));
            }
            JsonNode pathCfg = item.path("PathPatternConfig");
            if (pathCfg.path("Values").isArray()) {
                condition.setPathPatternValues(jsonArrayToStringList(pathCfg.path("Values")));
            }
            JsonNode hostCfg = item.path("HostHeaderConfig");
            if (hostCfg.path("Values").isArray()) {
                condition.setHostHeaderValues(jsonArrayToStringList(hostCfg.path("Values")));
            }
            JsonNode httpHeaderCfg = item.path("HttpHeaderConfig");
            if (httpHeaderCfg.isObject()) {
                condition.setHttpHeaderName(textOrNull(httpHeaderCfg, "HttpHeaderName"));
                if (httpHeaderCfg.path("Values").isArray()) {
                    condition.setHttpHeaderValues(jsonArrayToStringList(httpHeaderCfg.path("Values")));
                }
            }
            JsonNode methodCfg = item.path("HttpRequestMethodConfig");
            if (methodCfg.path("Values").isArray()) {
                condition.setHttpMethodValues(jsonArrayToStringList(methodCfg.path("Values")));
            }
            JsonNode sourceIpCfg = item.path("SourceIpConfig");
            if (sourceIpCfg.path("Values").isArray()) {
                condition.setSourceIpValues(jsonArrayToStringList(sourceIpCfg.path("Values")));
            }
            JsonNode queryCfg = item.path("QueryStringConfig");
            if (queryCfg.path("Values").isArray()) {
                List<RuleCondition.QueryStringPair> pairs = new ArrayList<>();
                for (JsonNode q : queryCfg.path("Values")) {
                    RuleCondition.QueryStringPair pair = new RuleCondition.QueryStringPair();
                    pair.setKey(textOrNull(q, "Key"));
                    pair.setValue(textOrNull(q, "Value"));
                    pairs.add(pair);
                }
                condition.setQueryStringValues(pairs);
            }
            result.add(condition);
        }
        return result;
    }

    private List<String> parseCertificates(JsonNode props, CloudFormationTemplateEngine engine) {
        List<String> result = new ArrayList<>();
        if (props == null || !props.has("Certificates") || props.get("Certificates").isNull()) {
            return result;
        }
        JsonNode resolved = engine.resolveNode(props.get("Certificates"));
        if (resolved.isArray()) {
            for (JsonNode c : resolved) {
                if (c.hasNonNull("CertificateArn")) {
                    result.add(c.path("CertificateArn").asText());
                }
            }
        }
        return result;
    }

    private String parseMatcher(JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("Matcher") || props.get("Matcher").isNull()) {
            return null;
        }
        JsonNode m = engine.resolveNode(props.get("Matcher"));
        if (m.hasNonNull("HttpCode")) {
            return m.path("HttpCode").asText();
        }
        if (m.hasNonNull("GrpcCode")) {
            return m.path("GrpcCode").asText();
        }
        return null;
    }

    private String loadBalancerFullName(String lbArn) {
        // LB ARN resource: loadbalancer/<type>/<name>/<id> → full name drops the "loadbalancer/" prefix.
        String resource = AwsArnUtils.parse(lbArn).resource();
        String prefix = "loadbalancer/";
        return resource.startsWith(prefix) ? resource.substring(prefix.length()) : resource;
    }

    private String targetGroupFullName(String tgArn) {
        // TG full name keeps the "targetgroup/" prefix, e.g. targetgroup/<name>/<id>.
        return AwsArnUtils.parse(tgArn).resource();
    }

    private static String generateElbName(String stackName, String logicalId) {
        // ELBv2 names: ≤32 chars, [A-Za-z0-9-], no leading/trailing hyphen.
        String base = (stackName + "-" + logicalId).replaceAll("[^A-Za-z0-9-]", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        int maxBase = 32 - 1 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        base = base.replaceAll("-+$", "");
        if (base.isEmpty()) {
            base = "elb";
        }
        return base + "-" + suffix;
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBooleanOrNull(String value) {
        return (value == null || value.isBlank()) ? null : Boolean.valueOf(value);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
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
