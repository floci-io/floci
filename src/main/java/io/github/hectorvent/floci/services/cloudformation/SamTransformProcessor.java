package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Expands AWS SAM (Serverless Application Model) resource types into standard CloudFormation
 * resources. This implements the {@code AWS::Serverless-2016-10-31} transform macro.
 *
 * <p>Supported SAM resource types:
 * <ul>
 *   <li>{@code AWS::Serverless::Function} → {@code AWS::Lambda::Function} + {@code AWS::IAM::Role}</li>
 *   <li>{@code AWS::Serverless::SimpleTable} → {@code AWS::DynamoDB::Table}</li>
 *   <li>{@code AWS::Serverless::Api} → {@code AWS::ApiGateway::RestApi} + deployment + stage</li>
 * </ul>
 */
class SamTransformProcessor {

    private static final Logger LOG = Logger.getLogger(SamTransformProcessor.class);
    private static final String SAM_TRANSFORM = "AWS::Serverless-2016-10-31";

    private final ObjectMapper objectMapper;

    SamTransformProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns true if the template declares the SAM transform.
     */
    boolean hasSamTransform(JsonNode template) {
        JsonNode transform = template.path("Transform");
        if (transform.isTextual()) {
            return SAM_TRANSFORM.equals(transform.asText());
        }
        if (transform.isArray()) {
            for (JsonNode t : transform) {
                if (SAM_TRANSFORM.equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Expands SAM resource types in the template into standard CloudFormation resources.
     * Returns a new template JsonNode with the expanded resources. The original template
     * is not modified.
     */
    JsonNode expandSamTemplate(JsonNode template) {
        if (!hasSamTransform(template)) {
            return template;
        }

        ObjectNode expanded = template.deepCopy();
        // Remove the Transform declaration — it has been processed
        expanded.remove("Transform");

        JsonNode resources = expanded.path("Resources");
        if (!resources.isObject()) {
            return expanded;
        }

        ObjectNode expandedResources = (ObjectNode) resources;
        // Collect SAM resources to expand (avoid ConcurrentModification)
        List<String> samLogicalIds = new ArrayList<>();
        resources.fieldNames().forEachRemaining(logicalId -> {
            String type = resources.path(logicalId).path("Type").asText("");
            if (type.startsWith("AWS::Serverless::")) {
                samLogicalIds.add(logicalId);
            }
        });

        for (String logicalId : samLogicalIds) {
            JsonNode resDef = resources.get(logicalId);
            String type = resDef.path("Type").asText();
            JsonNode properties = resDef.path("Properties");

            switch (type) {
                case "AWS::Serverless::Function" ->
                        expandServerlessFunction(logicalId, properties, expandedResources);
                case "AWS::Serverless::SimpleTable" ->
                        expandServerlessSimpleTable(logicalId, properties, expandedResources);
                case "AWS::Serverless::Api" ->
                        expandServerlessApi(logicalId, properties, expandedResources);
                default -> LOG.debugv("Unsupported SAM resource type: {0} ({1})", type, logicalId);
            }
        }

        return expanded;
    }

    /**
     * Expands AWS::Serverless::Function into:
     * - {LogicalId}Role → AWS::IAM::Role (execution role)
     * - {LogicalId} → AWS::Lambda::Function
     * - {LogicalId}{EventName}Permission → AWS::Lambda::Permission (for each event)
     */
    private void expandServerlessFunction(String logicalId, JsonNode properties, ObjectNode resources) {
        // Remove the SAM resource
        resources.remove(logicalId);

        String roleLogicalId = logicalId + "Role";

        // 1. Create the IAM execution role
        ObjectNode roleResource = createExecutionRole(logicalId, properties);
        resources.set(roleLogicalId, roleResource);

        // 2. Create the Lambda function
        ObjectNode lambdaResource = createLambdaFunction(logicalId, roleLogicalId, properties);
        resources.set(logicalId, lambdaResource);

        // 3. Process Events (create event source mappings, permissions, etc.)
        JsonNode events = properties.path("Events");
        if (events.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> eventFields = events.fields();
            while (eventFields.hasNext()) {
                Map.Entry<String, JsonNode> entry = eventFields.next();
                String eventName = entry.getKey();
                JsonNode eventDef = entry.getValue();
                expandFunctionEvent(logicalId, eventName, eventDef, resources);
            }
        }
    }

    private ObjectNode createExecutionRole(String functionLogicalId, JsonNode properties) {
        ObjectNode roleDef = objectMapper.createObjectNode();
        roleDef.put("Type", "AWS::IAM::Role");

        ObjectNode roleProps = objectMapper.createObjectNode();

        // AssumeRolePolicyDocument — Lambda service principal
        ObjectNode assumePolicy = objectMapper.createObjectNode();
        assumePolicy.put("Version", "2012-10-17");
        ArrayNode statements = objectMapper.createArrayNode();
        ObjectNode stmt = objectMapper.createObjectNode();
        stmt.put("Effect", "Allow");
        ObjectNode principal = objectMapper.createObjectNode();
        principal.put("Service", "lambda.amazonaws.com");
        stmt.set("Principal", principal);
        stmt.put("Action", "sts:AssumeRole");
        statements.add(stmt);
        assumePolicy.set("Statement", statements);
        roleProps.set("AssumeRolePolicyDocument", assumePolicy);

        // ManagedPolicyArns — basic execution role + any user-specified policies
        ArrayNode managedPolicies = objectMapper.createArrayNode();
        managedPolicies.add("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole");

        JsonNode userPolicies = properties.path("Policies");
        if (userPolicies.isArray()) {
            for (JsonNode policy : userPolicies) {
                if (policy.isTextual()) {
                    // ARN reference
                    managedPolicies.add(policy.asText());
                }
                // Inline policy documents are not expanded here for simplicity
            }
        } else if (userPolicies.isTextual()) {
            managedPolicies.add(userPolicies.asText());
        }
        roleProps.set("ManagedPolicyArns", managedPolicies);

        roleDef.set("Properties", roleProps);
        return roleDef;
    }

    private ObjectNode createLambdaFunction(String logicalId, String roleLogicalId, JsonNode properties) {
        ObjectNode lambdaDef = objectMapper.createObjectNode();
        lambdaDef.put("Type", "AWS::Lambda::Function");

        ObjectNode lambdaProps = objectMapper.createObjectNode();

        // FunctionName — SAM uses the logical ID convention if not specified
        JsonNode functionName = properties.path("FunctionName");
        if (!functionName.isMissingNode() && !functionName.isNull()) {
            lambdaProps.set("FunctionName", functionName.deepCopy());
        }

        // Handler
        JsonNode handler = properties.path("Handler");
        if (!handler.isMissingNode()) {
            lambdaProps.set("Handler", handler.deepCopy());
        }

        // Runtime
        JsonNode runtime = properties.path("Runtime");
        if (!runtime.isMissingNode()) {
            lambdaProps.set("Runtime", runtime.deepCopy());
        }

        // Code — SAM uses CodeUri or InlineCode
        ObjectNode code = buildLambdaCode(properties);
        lambdaProps.set("Code", code);

        // Role — reference the generated execution role
        JsonNode explicitRole = properties.path("Role");
        if (!explicitRole.isMissingNode() && !explicitRole.isNull()) {
            lambdaProps.set("Role", explicitRole.deepCopy());
        } else {
            // Reference the generated role's ARN via Fn::GetAtt
            ObjectNode roleRef = objectMapper.createObjectNode();
            ArrayNode getAtt = objectMapper.createArrayNode();
            getAtt.add(roleLogicalId);
            getAtt.add("Arn");
            roleRef.set("Fn::GetAtt", getAtt);
            lambdaProps.set("Role", roleRef);
        }

        // Timeout
        JsonNode timeout = properties.path("Timeout");
        if (!timeout.isMissingNode()) {
            lambdaProps.set("Timeout", timeout.deepCopy());
        }

        // MemorySize
        JsonNode memorySize = properties.path("MemorySize");
        if (!memorySize.isMissingNode()) {
            lambdaProps.set("MemorySize", memorySize.deepCopy());
        }

        // Environment
        JsonNode environment = properties.path("Environment");
        if (!environment.isMissingNode()) {
            lambdaProps.set("Environment", environment.deepCopy());
        }

        // Layers
        JsonNode layers = properties.path("Layers");
        if (!layers.isMissingNode()) {
            lambdaProps.set("Layers", layers.deepCopy());
        }

        // Tags
        JsonNode tags = properties.path("Tags");
        if (!tags.isMissingNode()) {
            lambdaProps.set("Tags", tags.deepCopy());
        }

        // Architectures
        JsonNode architectures = properties.path("Architectures");
        if (!architectures.isMissingNode()) {
            lambdaProps.set("Architectures", architectures.deepCopy());
        }

        // TracingConfig from Tracing property
        JsonNode tracing = properties.path("Tracing");
        if (!tracing.isMissingNode()) {
            ObjectNode tracingConfig = objectMapper.createObjectNode();
            tracingConfig.set("Mode", tracing.deepCopy());
            lambdaProps.set("TracingConfig", tracingConfig);
        }

        // ReservedConcurrentExecutions
        JsonNode reserved = properties.path("ReservedConcurrentExecutions");
        if (!reserved.isMissingNode()) {
            lambdaProps.set("ReservedConcurrentExecutions", reserved.deepCopy());
        }

        // EphemeralStorage
        JsonNode ephemeral = properties.path("EphemeralStorage");
        if (!ephemeral.isMissingNode()) {
            lambdaProps.set("EphemeralStorage", ephemeral.deepCopy());
        }

        // DependsOn the role
        lambdaDef.set("Properties", lambdaProps);
        ArrayNode dependsOn = objectMapper.createArrayNode();
        dependsOn.add(roleLogicalId);
        lambdaDef.set("DependsOn", dependsOn);

        return lambdaDef;
    }

    private ObjectNode buildLambdaCode(JsonNode properties) {
        ObjectNode code = objectMapper.createObjectNode();

        // InlineCode → ZipFile
        JsonNode inlineCode = properties.path("InlineCode");
        if (!inlineCode.isMissingNode()) {
            code.set("ZipFile", inlineCode.deepCopy());
            return code;
        }

        // CodeUri as string (S3 URI: s3://bucket/key)
        JsonNode codeUri = properties.path("CodeUri");
        if (codeUri.isTextual()) {
            String uri = codeUri.asText();
            if (uri.startsWith("s3://")) {
                String withoutScheme = uri.substring(5);
                int slash = withoutScheme.indexOf('/');
                if (slash > 0) {
                    code.put("S3Bucket", withoutScheme.substring(0, slash));
                    code.put("S3Key", withoutScheme.substring(slash + 1));
                }
            } else {
                // Local path — treat as ZipFile placeholder for local dev
                code.put("ZipFile", "// SAM local code: " + uri);
            }
            return code;
        }

        // CodeUri as object with Bucket/Key/Version
        if (codeUri.isObject()) {
            JsonNode bucket = codeUri.path("Bucket");
            if (!bucket.isMissingNode()) {
                code.set("S3Bucket", bucket.deepCopy());
            }
            JsonNode key = codeUri.path("Key");
            if (!key.isMissingNode()) {
                code.set("S3Key", key.deepCopy());
            }
            JsonNode version = codeUri.path("Version");
            if (!version.isMissingNode()) {
                code.set("S3ObjectVersion", version.deepCopy());
            }
            return code;
        }

        // ImageUri
        JsonNode imageUri = properties.path("ImageUri");
        if (!imageUri.isMissingNode()) {
            code.set("ImageUri", imageUri.deepCopy());
            return code;
        }

        // No code specified — empty placeholder
        code.put("ZipFile", "// No code specified");
        return code;
    }

    /**
     * Expands function events into supporting resources (permissions, event source mappings).
     */
    private void expandFunctionEvent(String functionLogicalId, String eventName,
                                     JsonNode eventDef, ObjectNode resources) {
        String eventType = eventDef.path("Type").asText("");
        JsonNode eventProps = eventDef.path("Properties");

        switch (eventType) {
            case "SQS", "Kinesis", "DynamoDB" ->
                    expandEventSourceMapping(functionLogicalId, eventName, eventType, eventProps, resources);
            case "Api" ->
                    LOG.debugv("SAM Api event for {0}.{1} — API Gateway integration handled by Api resource",
                            functionLogicalId, eventName);
            case "Schedule" ->
                    LOG.debugv("SAM Schedule event for {0}.{1} — EventBridge rule not yet expanded",
                            functionLogicalId, eventName);
            default ->
                    LOG.debugv("SAM event type {0} for {1}.{2} not expanded", eventType, functionLogicalId, eventName);
        }
    }

    private void expandEventSourceMapping(String functionLogicalId, String eventName,
                                          String eventType, JsonNode eventProps, ObjectNode resources) {
        String esmLogicalId = functionLogicalId + eventName;

        ObjectNode esmDef = objectMapper.createObjectNode();
        esmDef.put("Type", "AWS::Lambda::EventSourceMapping");

        ObjectNode esmProps = objectMapper.createObjectNode();

        // FunctionName — Ref to the Lambda function
        ObjectNode funcRef = objectMapper.createObjectNode();
        funcRef.put("Ref", functionLogicalId);
        esmProps.set("FunctionName", funcRef);

        // EventSourceArn
        JsonNode sourceArn = eventProps.path("Queue");
        if (sourceArn.isMissingNode()) {
            sourceArn = eventProps.path("Stream");
        }
        if (!sourceArn.isMissingNode()) {
            esmProps.set("EventSourceArn", sourceArn.deepCopy());
        }

        // BatchSize
        JsonNode batchSize = eventProps.path("BatchSize");
        if (!batchSize.isMissingNode()) {
            esmProps.set("BatchSize", batchSize.deepCopy());
        }

        // Enabled
        JsonNode enabled = eventProps.path("Enabled");
        if (!enabled.isMissingNode()) {
            esmProps.set("Enabled", enabled.deepCopy());
        }

        esmDef.set("Properties", esmProps);

        // DependsOn the function
        ArrayNode dependsOn = objectMapper.createArrayNode();
        dependsOn.add(functionLogicalId);
        esmDef.set("DependsOn", dependsOn);

        resources.set(esmLogicalId, esmDef);
    }

    /**
     * Expands AWS::Serverless::SimpleTable into AWS::DynamoDB::Table.
     */
    private void expandServerlessSimpleTable(String logicalId, JsonNode properties, ObjectNode resources) {
        resources.remove(logicalId);

        ObjectNode tableDef = objectMapper.createObjectNode();
        tableDef.put("Type", "AWS::DynamoDB::Table");

        ObjectNode tableProps = objectMapper.createObjectNode();

        // TableName
        JsonNode tableName = properties.path("TableName");
        if (!tableName.isMissingNode()) {
            tableProps.set("TableName", tableName.deepCopy());
        }

        // PrimaryKey → KeySchema + AttributeDefinitions
        JsonNode primaryKey = properties.path("PrimaryKey");
        ArrayNode keySchema = objectMapper.createArrayNode();
        ArrayNode attrDefs = objectMapper.createArrayNode();

        if (primaryKey.isObject()) {
            String pkName = primaryKey.path("Name").asText("id");
            String pkType = mapSamAttributeType(primaryKey.path("Type").asText("String"));

            ObjectNode hashKey = objectMapper.createObjectNode();
            hashKey.put("AttributeName", pkName);
            hashKey.put("KeyType", "HASH");
            keySchema.add(hashKey);

            ObjectNode hashAttr = objectMapper.createObjectNode();
            hashAttr.put("AttributeName", pkName);
            hashAttr.put("AttributeType", pkType);
            attrDefs.add(hashAttr);
        } else {
            // Default: id (String) as HASH key
            ObjectNode hashKey = objectMapper.createObjectNode();
            hashKey.put("AttributeName", "id");
            hashKey.put("KeyType", "HASH");
            keySchema.add(hashKey);

            ObjectNode hashAttr = objectMapper.createObjectNode();
            hashAttr.put("AttributeName", "id");
            hashAttr.put("AttributeType", "S");
            attrDefs.add(hashAttr);
        }

        tableProps.set("KeySchema", keySchema);
        tableProps.set("AttributeDefinitions", attrDefs);

        // Tags
        JsonNode tags = properties.path("Tags");
        if (!tags.isMissingNode()) {
            tableProps.set("Tags", tags.deepCopy());
        }

        tableDef.set("Properties", tableProps);
        resources.set(logicalId, tableDef);
    }

    /**
     * Expands AWS::Serverless::Api into:
     * - {LogicalId} → AWS::ApiGateway::RestApi
     * - {LogicalId}Deployment → AWS::ApiGateway::Deployment
     * - {LogicalId}Stage → AWS::ApiGateway::Stage
     */
    private void expandServerlessApi(String logicalId, JsonNode properties, ObjectNode resources) {
        resources.remove(logicalId);

        // 1. RestApi
        ObjectNode apiDef = objectMapper.createObjectNode();
        apiDef.put("Type", "AWS::ApiGateway::RestApi");
        ObjectNode apiProps = objectMapper.createObjectNode();

        JsonNode name = properties.path("Name");
        if (!name.isMissingNode()) {
            apiProps.set("Name", name.deepCopy());
        } else {
            apiProps.put("Name", logicalId);
        }

        JsonNode description = properties.path("Description");
        if (!description.isMissingNode()) {
            apiProps.set("Description", description.deepCopy());
        }

        apiDef.set("Properties", apiProps);
        resources.set(logicalId, apiDef);

        // 2. Deployment
        String deploymentLogicalId = logicalId + "Deployment";
        ObjectNode deployDef = objectMapper.createObjectNode();
        deployDef.put("Type", "AWS::ApiGateway::Deployment");
        ObjectNode deployProps = objectMapper.createObjectNode();
        ObjectNode restApiRef = objectMapper.createObjectNode();
        restApiRef.put("Ref", logicalId);
        deployProps.set("RestApiId", restApiRef);
        deployDef.set("Properties", deployProps);
        ArrayNode deployDeps = objectMapper.createArrayNode();
        deployDeps.add(logicalId);
        deployDef.set("DependsOn", deployDeps);
        resources.set(deploymentLogicalId, deployDef);

        // 3. Stage
        String stageLogicalId = logicalId + "Stage";
        ObjectNode stageDef = objectMapper.createObjectNode();
        stageDef.put("Type", "AWS::ApiGateway::Stage");
        ObjectNode stageProps = objectMapper.createObjectNode();
        stageProps.set("RestApiId", restApiRef.deepCopy());
        ObjectNode deployRef = objectMapper.createObjectNode();
        deployRef.put("Ref", deploymentLogicalId);
        stageProps.set("DeploymentId", deployRef);

        JsonNode stageName = properties.path("StageName");
        if (!stageName.isMissingNode()) {
            stageProps.set("StageName", stageName.deepCopy());
        } else {
            stageProps.put("StageName", "Prod");
        }

        stageDef.set("Properties", stageProps);
        ArrayNode stageDeps = objectMapper.createArrayNode();
        stageDeps.add(deploymentLogicalId);
        stageDef.set("DependsOn", stageDeps);
        resources.set(stageLogicalId, stageDef);
    }

    private String mapSamAttributeType(String samType) {
        return switch (samType) {
            case "String" -> "S";
            case "Number" -> "N";
            case "Binary" -> "B";
            default -> "S";
        };
    }
}
