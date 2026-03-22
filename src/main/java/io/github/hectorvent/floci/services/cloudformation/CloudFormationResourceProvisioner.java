package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Provisions individual CloudFormation resource types using Floci's existing service implementations.
 */
@ApplicationScoped
public class CloudFormationResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CloudFormationResourceProvisioner.class);

    private final S3Service s3Service;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final DynamoDbService dynamoDbService;
    private final LambdaService lambdaService;
    private final IamService iamService;
    private final SsmService ssmService;
    private final KmsService kmsService;
    private final SecretsManagerService secretsManagerService;

    @Inject
    public CloudFormationResourceProvisioner(S3Service s3Service, SqsService sqsService,
                                             SnsService snsService, DynamoDbService dynamoDbService,
                                             LambdaService lambdaService, IamService iamService,
                                             SsmService ssmService, KmsService kmsService,
                                             SecretsManagerService secretsManagerService) {
        this.s3Service = s3Service;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.dynamoDbService = dynamoDbService;
        this.lambdaService = lambdaService;
        this.iamService = iamService;
        this.ssmService = ssmService;
        this.kmsService = kmsService;
        this.secretsManagerService = secretsManagerService;
    }

    /**
     * Provisions a single resource. Returns the populated StackResource (physicalId + attributes set).
     * Returns null and logs a warning for unsupported types.
     */
    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType(resourceType);

        try {
            switch (resourceType) {
                case "AWS::S3::Bucket" -> provisionS3Bucket(resource, properties, engine, region, accountId);
                case "AWS::SQS::Queue" -> provisionSqsQueue(resource, properties, engine, region, accountId);
                case "AWS::SNS::Topic" -> provisionSnsTopic(resource, properties, engine, region, accountId);
                case "AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable" ->
                        provisionDynamoTable(resource, properties, engine, region, accountId);
                case "AWS::Lambda::Function" -> provisionLambda(resource, properties, engine, region, accountId);
                case "AWS::IAM::Role" -> provisionIamRole(resource, properties, engine, accountId);
                case "AWS::IAM::User" -> provisionIamUser(resource, properties, engine);
                case "AWS::IAM::AccessKey" -> provisionIamAccessKey(resource, properties, engine);
                case "AWS::IAM::Policy", "AWS::IAM::ManagedPolicy" ->
                        provisionIamPolicy(resource, properties, engine, accountId);
                case "AWS::IAM::InstanceProfile" -> provisionInstanceProfile(resource, properties, engine, accountId);
                case "AWS::SSM::Parameter" -> provisionSsmParameter(resource, properties, engine, region);
                case "AWS::KMS::Key" -> provisionKmsKey(resource, properties, engine, region, accountId);
                case "AWS::KMS::Alias" -> provisionKmsAlias(resource, properties, engine, region);
                case "AWS::SecretsManager::Secret" -> provisionSecret(resource, properties, engine, region, accountId);
                case "AWS::CloudFormation::Stack" -> provisionNestedStack(resource, properties, engine, region);
                case "AWS::CDK::Metadata" -> provisionCdkMetadata(resource);
                case "AWS::S3::BucketPolicy" -> provisionS3BucketPolicy(resource, properties, engine);
                case "AWS::SQS::QueuePolicy" -> provisionSqsQueuePolicy(resource, properties, engine);
                case "AWS::ECR::Repository" -> provisionEcrRepository(resource, properties, engine);
                case "AWS::Route53::HostedZone" -> provisionRoute53HostedZone(resource, properties, engine);
                case "AWS::Route53::RecordSet" -> provisionRoute53RecordSet(resource, properties, engine);
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
                default -> LOG.debugv("Skipping delete of unsupported resource type: {0}", resourceType);
            }
        } catch (Exception e) {
            LOG.debugv("Error deleting {0} ({1}): {2}", resourceType, physicalId, e.getMessage());
        }
    }

    // ── S3 ────────────────────────────────────────────────────────────────────

    private void provisionS3Bucket(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId) {
        String bucketName = resolveOptional(props, "BucketName", engine);
        if (bucketName == null || bucketName.isBlank()) {
            bucketName = "cfn-" + UUID.randomUUID().toString().substring(0, 12).toLowerCase();
        }
        s3Service.createBucket(bucketName);
        r.setPhysicalId(bucketName);
        r.getAttributes().put("Arn", "arn:aws:s3:::" + bucketName);
        r.getAttributes().put("DomainName", bucketName + ".s3.amazonaws.com");
        r.getAttributes().put("RegionalDomainName", bucketName + ".s3." + region + ".amazonaws.com");
        r.getAttributes().put("WebsiteURL", "http://" + bucketName + ".s3-website." + region + ".amazonaws.com");
        r.getAttributes().put("BucketName", bucketName);
    }

    // ── SQS ───────────────────────────────────────────────────────────────────

    private void provisionSqsQueue(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId) {
        String queueName = resolveOptional(props, "QueueName", engine);
        if (queueName == null || queueName.isBlank()) {
            queueName = "cfn-" + UUID.randomUUID().toString().substring(0, 12);
        }
        Map<String, String> attrs = new HashMap<>();
        if (props != null && props.has("VisibilityTimeout")) {
            attrs.put("VisibilityTimeout", engine.resolve(props.get("VisibilityTimeout")));
        }
        var queue = sqsService.createQueue(queueName, attrs, region);
        String queueArn = queue.getAttributes().getOrDefault("QueueArn", "");
        r.setPhysicalId(queue.getQueueUrl());
        r.getAttributes().put("Arn", queueArn);
        r.getAttributes().put("QueueName", queueName);
        r.getAttributes().put("QueueUrl", queue.getQueueUrl());
    }

    // ── SNS ───────────────────────────────────────────────────────────────────

    private void provisionSnsTopic(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                   String region, String accountId) {
        String topicName = resolveOptional(props, "TopicName", engine);
        if (topicName == null || topicName.isBlank()) {
            topicName = "cfn-" + UUID.randomUUID().toString().substring(0, 12);
        }
        var topic = snsService.createTopic(topicName, Map.of(), Map.of(), region);
        r.setPhysicalId(topic.getTopicArn());
        r.getAttributes().put("Arn", topic.getTopicArn());
        r.getAttributes().put("TopicName", topicName);
    }

    // ── DynamoDB ──────────────────────────────────────────────────────────────

    private void provisionDynamoTable(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                      String region, String accountId) {
        String tableName = resolveOptional(props, "TableName", engine);
        if (tableName == null || tableName.isBlank()) {
            tableName = "cfn-" + UUID.randomUUID().toString().substring(0, 12);
        }

        List<KeySchemaElement> keySchema = new ArrayList<>();
        List<AttributeDefinition> attrDefs = new ArrayList<>();

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

        if (keySchema.isEmpty()) {
            keySchema.add(new KeySchemaElement("id", "HASH"));
            attrDefs.add(new AttributeDefinition("id", "S"));
        }

        var table = dynamoDbService.createTable(tableName, keySchema, attrDefs, null, null, region);
        r.setPhysicalId(tableName);
        r.getAttributes().put("Arn", table.getTableArn());
        r.getAttributes().put("StreamArn", table.getTableArn() + "/stream/2024-01-01T00:00:00.000");
    }

    // ── Lambda ────────────────────────────────────────────────────────────────

    private void provisionLambda(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                 String region, String accountId) {
        String funcName = resolveOptional(props, "FunctionName", engine);
        if (funcName == null || funcName.isBlank()) {
            funcName = "cfn-func-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("FunctionName", funcName);
        req.put("Runtime", props != null && props.has("Runtime") ? engine.resolve(props.get("Runtime")) : "nodejs18.x");
        req.put("Handler", props != null && props.has("Handler") ? engine.resolve(props.get("Handler")) : "index.handler");
        req.put("Role", props != null && props.has("Role") ? engine.resolve(props.get("Role")) : "arn:aws:iam::" + accountId + ":role/default");
        if (props != null && props.has("Code")) {
            JsonNode codeNode = props.get("Code");
            String s3Bucket = codeNode.has("S3Bucket") ? engine.resolve(codeNode.get("S3Bucket")) : null;
            String s3Key = codeNode.has("S3Key") ? engine.resolve(codeNode.get("S3Key")) : null;
            if ("hot-reload".equals(s3Bucket) && s3Key != null) {
                // Hot-reload: pass S3Bucket/S3Key through so LambdaService sets up bind mounts
                req.put("Code", Map.of("S3Bucket", s3Bucket, "S3Key", s3Key));
            } else {
                // Stub handler for non-hot-reload CF-provisioned functions
                req.put("Code", Map.of("ZipFile", "exports.handler=async(e)=>({statusCode:200})"));
            }
        } else {
            req.put("Code", Map.of("ZipFile", "exports.handler=async(e)=>({statusCode:200})"));
        }
        var func = lambdaService.createFunction(region, req);
        r.setPhysicalId(funcName);
        r.getAttributes().put("Arn", func.getFunctionArn());
    }

    // ── IAM Role ──────────────────────────────────────────────────────────────

    private void provisionIamRole(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                  String accountId) {
        String roleName = resolveOptional(props, "RoleName", engine);
        if (roleName == null || roleName.isBlank()) {
            roleName = "cfn-role-" + UUID.randomUUID().toString().substring(0, 8);
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
                                    String accountId) {
        String policyName = resolveOptional(props, "PolicyName", engine);
        if (policyName == null || policyName.isBlank()) {
            policyName = "cfn-policy-" + UUID.randomUUID().toString().substring(0, 8);
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
                                           String accountId) {
        provisionIamPolicy(r, props, engine, accountId);
    }

    // ── IAM Instance Profile ──────────────────────────────────────────────────

    private void provisionInstanceProfile(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                          String accountId) {
        String name = resolveOptional(props, "InstanceProfileName", engine);
        if (name == null || name.isBlank()) {
            name = "cfn-profile-" + UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            var profile = iamService.createInstanceProfile(name, "/");
            r.setPhysicalId(name);
            r.getAttributes().put("Arn", profile.getArn());
        } catch (Exception e) {
            r.setPhysicalId(name);
            r.getAttributes().put("Arn", "arn:aws:iam::" + accountId + ":instance-profile/" + name);
        }
    }

    // ── SSM Parameter ─────────────────────────────────────────────────────────

    private void provisionSsmParameter(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                       String region) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = "/cfn/" + UUID.randomUUID().toString().substring(0, 12);
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
        var key = kmsService.createKey(description, region);
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
                                 String region, String accountId) {
        String name = resolveOptional(props, "Name", engine);
        if (name == null || name.isBlank()) {
            name = "cfn-secret-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String value = resolveOptional(props, "SecretString", engine);
        if (value == null) {
            value = "{}";
        }
        var secret = secretsManagerService.createSecret(name, value, null, null, null, List.of(), region);
        r.setPhysicalId(secret.getArn());
        r.getAttributes().put("Arn", secret.getArn());
        r.getAttributes().put("Name", name);
    }

    // ── Nested Stack ──────────────────────────────────────────────────────────

    private void provisionNestedStack(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                      String region) {
        // Nested stacks are stubbed — return a synthetic stack ID
        String nestedId = "arn:aws:cloudformation:" + region + "::stack/nested-" +
                UUID.randomUUID().toString().substring(0, 8) + "/";
        r.setPhysicalId(nestedId);
        r.getAttributes().put("Arn", nestedId);
        r.getAttributes().put("Outputs.BootstrapVersion", "21");
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

    private void provisionIamUser(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String userName = resolveOptional(props, "UserName", engine);
        if (userName == null || userName.isBlank()) {
            userName = "cfn-user-" + UUID.randomUUID().toString().substring(0, 8);
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

    private void provisionEcrRepository(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String repoName = resolveOptional(props, "RepositoryName", engine);
        if (repoName == null || repoName.isBlank()) {
            repoName = "cfn-repo-" + UUID.randomUUID().toString().substring(0, 8);
        }
        r.setPhysicalId(repoName);
        r.getAttributes().put("Arn", "arn:aws:ecr:us-east-1:000000000000:repository/" + repoName);
    }

    private void provisionRoute53HostedZone(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String zoneId = "Z" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        r.setPhysicalId(zoneId);
    }

    private void provisionRoute53RecordSet(StackResource r, JsonNode props, CloudFormationTemplateEngine engine) {
        String name = resolveOptional(props, "Name", engine);
        r.setPhysicalId(name != null ? name : "record-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String resolveOptional(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
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
}
