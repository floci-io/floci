package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.InlineZipPackager;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFileSystemConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Provisions {@code AWS::Lambda::Function} and {@code AWS::Lambda::LayerVersion}, moved verbatim out
 * of the {@code CloudFormationResourceProvisioner} monolith. Function provisioning covers create,
 * in-place configuration/code update, and replacement on a name or package-type change; layer
 * version provisioning publishes a new version and exposes its ARN for {@code Ref}/{@code Fn::GetAtt}.
 */
@ApplicationScoped
public class LambdaCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(LambdaCfnProvisioner.class);

    private static final String FUNCTION = "AWS::Lambda::Function";
    private static final String LAYER_VERSION = "AWS::Lambda::LayerVersion";

    private static final String LAMBDA_CODE_IDENTITY_ATTR = "FlociLambdaCodeIdentity";
    private static final String HOT_RELOAD_BUCKET = "hot-reload";
    private static final String LAMBDA_NAME_MODE_ATTR = "FlociLambdaFunctionNameMode";
    private static final String LAMBDA_PACKAGE_TYPE_ATTR = "FlociLambdaPackageType";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";

    private static final int LAMBDA_DEFAULT_TIMEOUT_SECONDS = 3;
    private static final int LAMBDA_DEFAULT_MEMORY_MB = 128;
    private static final int LAMBDA_DEFAULT_EPHEMERAL_STORAGE_MB = 512;
    private static final String LAMBDA_DEFAULT_TRACING_MODE = "PassThrough";

    private final LambdaService lambdaService;
    private final LambdaLayerService lambdaLayerService;
    private final S3Service s3Service;
    private final EmulatorConfig config;

    @Inject
    public LambdaCfnProvisioner(LambdaService lambdaService, LambdaLayerService lambdaLayerService,
                                S3Service s3Service, EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.lambdaLayerService = lambdaLayerService;
        this.s3Service = s3Service;
        this.config = config;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FUNCTION, LAYER_VERSION);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case FUNCTION -> provisionLambda(r, props, ctx);
            case LAYER_VERSION -> provisionLambdaLayerVersion(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaCfnProvisioner does not handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case FUNCTION -> deleteLambdaFunctionSafe(physicalId, region);
            case LAYER_VERSION -> deleteLambdaLayerVersion(physicalId, region);
            default -> {
                // no other Lambda type has a backing delete here
            }
        }
    }

    private void provisionLambda(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        LambdaDesiredState desired = buildLambdaDesiredState(r, props, ctx);
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
                desired.explicitFunctionName() ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        r.getAttributes().put(LAMBDA_PACKAGE_TYPE_ATTR, desired.packageType());
    }

    private LambdaDesiredState buildLambdaDesiredState(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String stackName = ctx.stackName();
        String explicitName = ctx.resolveOptional(props, "FunctionName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String packageType = ctx.resolveOrDefault(props, "PackageType", "Zip");
        String previousNameMode = r.getAttributes().get(LAMBDA_NAME_MODE_ATTR);
        if (previousNameMode == null && r.getPhysicalId() != null) {
            // Functions persisted before LAMBDA_NAME_MODE_ATTR existed have no recorded mode, but an
            // auto-generated name always has the deterministic shape generatePhysicalName produces,
            // so anything else must have been explicit (see #1965/#2152 for the LogGroup precedent
            // this mirrors, and #2163 for this gap).
            previousNameMode = isGeneratedName(r.getPhysicalId(), stackName, r.getLogicalId(), 64)
                    ? NAME_MODE_GENERATED
                    : NAME_MODE_EXPLICIT;
            if (NAME_MODE_GENERATED.equals(previousNameMode) && !hasExplicitName) {
                // This inference is what decides explicitRemoved below, and it's the one direction
                // that can be wrong with no way for Floci to tell: a legacy FunctionName that was
                // actually pinned explicitly, but happens to exactly match generatePhysicalName's
                // shape (e.g. a user who deliberately reused a name Floci had previously generated),
                // is indistinguishable from a name that really was auto-generated all along. The raw
                // property value from that far back was never persisted to check against. Logged so
                // an operator relying on this FunctionName removal to trigger a replacement has a
                // chance to notice it silently didn't, rather than this being an invisible guess.
                LOG.warnv("Lambda {0} in stack {1}: inferring legacy FunctionName ''{2}'' as "
                                + "auto-generated because it matches the generated-name shape; if it "
                                + "was actually set explicitly, removing FunctionName here will not "
                                + "trigger the replacement AWS would perform",
                        r.getLogicalId(), stackName, r.getPhysicalId());
            }
        }
        String oldPackageType = r.getAttributes().get(LAMBDA_PACKAGE_TYPE_ATTR);
        boolean packageTypeReplacement = r.getPhysicalId() != null
                && oldPackageType != null
                && !Objects.equals(oldPackageType, packageType);
        boolean explicitRemoved = r.getPhysicalId() != null
                && !hasExplicitName
                && NAME_MODE_EXPLICIT.equals(previousNameMode);

        String functionName;
        if (hasExplicitName) {
            functionName = explicitName;
        } else if (r.getPhysicalId() != null && !explicitRemoved && !packageTypeReplacement) {
            functionName = r.getPhysicalId();
        } else {
            functionName = ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }

        Map<String, Object> createRequest = new HashMap<>();
        Map<String, Object> configRequest = new HashMap<>();
        createRequest.put("FunctionName", functionName);
        createRequest.put("PackageType", packageType);

        String role = ctx.resolveOrDefault(props, "Role",
                AwsArnUtils.Arn.of("iam", "", ctx.accountId(), "role/default").toString());
        createRequest.put("Role", role);
        configRequest.put("Role", role);

        String runtime;
        String handler;
        if ("Zip".equals(packageType)) {
            runtime = ctx.resolveOrDefault(props, "Runtime", "nodejs18.x");
            handler = ctx.resolveOrDefault(props, "Handler", "index.handler");
            createRequest.put("Runtime", runtime);
            createRequest.put("Handler", handler);
            configRequest.put("Runtime", runtime);
            configRequest.put("Handler", handler);
        } else {
            runtime = ctx.resolveOptional(props, "Runtime");
            handler = ctx.resolveOptional(props, "Handler");
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

        configRequest.put("Timeout", intOrDefault(ctx.resolveOptional(props, "Timeout"),
                LAMBDA_DEFAULT_TIMEOUT_SECONDS));
        configRequest.put("MemorySize", intOrDefault(ctx.resolveOptional(props, "MemorySize"),
                LAMBDA_DEFAULT_MEMORY_MB));
        configRequest.put("Description", ctx.resolveOptional(props, "Description"));
        configRequest.put("KMSKeyArn", ctx.resolveOptional(props, "KMSKeyArn"));
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
        configRequest.put("FileSystemConfigs",
                resolveObjectListOrEmpty(props, "FileSystemConfigs", engine));
        putResolvedMapIfPresent(configRequest, props, "ImageConfig", "ImageConfig", engine);

        createRequest.putAll(configRequest);
        Integer reservedConcurrentExecutions = null;
        String reserved = ctx.resolveOptional(props, "ReservedConcurrentExecutions");
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

    /**
     * Whether an unreadable explicit {@code Code} reference may fall back to the stub handler.
     * The provisioners hand-built in unit tests carry no config; absent configuration means the
     * documented default, which is the strict behaviour.
     */
    private boolean stubLambdaCodeAllowed() {
        return config != null && config.services().cloudformation().allowStubLambdaCode();
    }

    private LambdaCodeSpec resolveLambdaCode(JsonNode props, CloudFormationTemplateEngine engine,
                                             String handler, String runtime) {
        if (props != null && props.has("Code")) {
            JsonNode codeNode = engine.resolveNode(props.get("Code"));

            String s3Bucket = codeNode.path("S3Bucket").asText(null);
            String s3Key = codeNode.path("S3Key").asText(null);
            if (HOT_RELOAD_BUCKET.equals(s3Bucket) && s3Key != null) {
                // Lambda's bind-mount hot-reload convention: the bucket is a marker and the key
                // is a directory on the Docker host, so there is no S3 object to probe. Hand the
                // pair to LambdaService unchanged; it enforces the hot-reload enablement and
                // path allow-list and reports its own errors, which fail the resource.
                return new LambdaCodeSpec(Map.of("S3Bucket", s3Bucket, "S3Key", s3Key),
                        "hot-reload:" + canonicalHotReloadPath(s3Key));
            }
            if (s3Bucket != null && s3Key != null) {
                // A template that names its code explicitly must fail if that code cannot be
                // read, the way real CloudFormation does. Substituting the stub handler here
                // let a stack reach CREATE_COMPLETE running code the template never referenced,
                // or, when the handler was not "index.handler", fail with a handler error
                // that pointed away from the real problem (issue #2648). The stub below is for
                // a template that supplies no Code at all, which is a different case.
                //
                // allow-stub-lambda-code opts back in to the old fallback, for a stack that
                // deliberately leaves its Lambda packages unbuilt and only cares about the
                // other resources. Off by default: silently serving a placeholder is the more
                // dangerous of the two behaviours.
                //
                // headObject, not getObject: this only needs to know whether the code is
                // readable. getObject additionally reads the whole body, which is then thrown
                // away, and LambdaService reads it again for real during CreateFunction. That
                // is a second full copy of the package per Lambda per stack operation, for a
                // question a metadata lookup answers (issue #2675). Both resolve the object
                // through the same getObjectMetadata call, so a missing key or bucket still
                // fails here exactly as before.
                try {
                    s3Service.headObject(s3Bucket, s3Key);
                    return new LambdaCodeSpec(Map.of("S3Bucket", s3Bucket, "S3Key", s3Key),
                            "s3:" + s3Bucket + "\n" + s3Key);
                } catch (Exception e) {
                    if (!stubLambdaCodeAllowed()) {
                        throw new AwsException("ValidationError",
                                "Error occurred while GetObject. S3 Error Message: " + e.getMessage()
                                        + " (bucket: " + s3Bucket + ", key: " + s3Key + ")", 400);
                    }
                    LOG.warnv("S3 code not found for Lambda ({0}/{1}), using default handler because "
                                    + "floci.services.cloudformation.allow-stub-lambda-code is enabled: {2}",
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

    /**
     * The form LambdaService stores a hot-reload host path in: normalized, so {@code /a/../b/}
     * and {@code /b} are the same mount. Identities and change detection use it so a redeploy
     * that only rewrites the path spelling, or a function adopted from a direct CreateFunction,
     * is not mistaken for a code change. The raw key still goes to Lambda, which validates it and
     * reports its own error for a path it cannot use.
     */
    private static String canonicalHotReloadPath(String s3Key) {
        try {
            return Path.of(s3Key).normalize().toString();
        } catch (InvalidPathException e) {
            return s3Key;
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
        if (HOT_RELOAD_BUCKET.equals(request.get("S3Bucket"))) {
            return !Objects.equals(fn.getHotReloadHostPath(),
                    canonicalHotReloadPath((String) request.get("S3Key")));
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
        for (Map.Entry<String, Object> entry : request.entrySet()) {
            String key = entry.getKey();
            Object desired = entry.getValue();
            switch (key) {
                case "Description" -> {
                    if (!Objects.equals(fn.getDescription(), desired)) {
                        return true;
                    }
                }
                case "Handler" -> {
                    if (!Objects.equals(fn.getHandler(), desired)) {
                        return true;
                    }
                }
                case "MemorySize" -> {
                    if (fn.getMemorySize() != toIntValue(desired, fn.getMemorySize())) {
                        return true;
                    }
                }
                case "Role" -> {
                    if (!Objects.equals(fn.getRole(), desired)) {
                        return true;
                    }
                }
                case "Runtime" -> {
                    if (!Objects.equals(fn.getRuntime(), desired)) {
                        return true;
                    }
                }
                case "Timeout" -> {
                    if (fn.getTimeout() != toIntValue(desired, fn.getTimeout())) {
                        return true;
                    }
                }
                case "Environment" -> {
                    if (!Objects.equals(fn.getEnvironment(), environmentVariables(desired))) {
                        return true;
                    }
                }
                case "Architectures" -> {
                    if (!Objects.equals(fn.getArchitectures(), desired)) {
                        return true;
                    }
                }
                case "EphemeralStorage" -> {
                    if (fn.getEphemeralStorageSize() != mapInt(desired, "Size", fn.getEphemeralStorageSize())) {
                        return true;
                    }
                }
                case "TracingConfig" -> {
                    if (!Objects.equals(fn.getTracingMode(), mapString(desired, "Mode"))) {
                        return true;
                    }
                }
                case "DeadLetterConfig" -> {
                    if (!Objects.equals(fn.getDeadLetterTargetArn(), mapString(desired, "TargetArn"))) {
                        return true;
                    }
                }
                case "Layers" -> {
                    if (!Objects.equals(fn.getLayers(), desired)) {
                        return true;
                    }
                }
                case "KMSKeyArn" -> {
                    if (!Objects.equals(fn.getKmsKeyArn(), desired)) {
                        return true;
                    }
                }
                case "VpcConfig" -> {
                    if (!Objects.equals(normalizeForCompare(fn.getVpcConfig()), normalizeForCompare(desired))) {
                        return true;
                    }
                }
                case "FileSystemConfigs" -> {
                    if (!Objects.equals(normalizeForCompare(fileSystemConfigs(fn)),
                            normalizeForCompare(desired))) {
                        return true;
                    }
                }
                case "ImageConfig" -> {
                    if (imageConfigurationChanged(fn, desired)) {
                        return true;
                    }
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

    private static List<Map<String, String>> fileSystemConfigs(LambdaFunction fn) {
        if (fn.getFileSystemConfigs() == null) {
            return List.of();
        }
        return fn.getFileSystemConfigs().stream()
                .map(LambdaCfnProvisioner::fileSystemConfig)
                .toList();
    }

    private static Map<String, String> fileSystemConfig(LambdaFileSystemConfig config) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("Arn", config.getArn());
        value.put("LocalMountPath", config.getLocalMountPath());
        return value;
    }

    private static String sha256Base64(String zipFileBase64) {
        byte[] zipBytes = Base64.getDecoder().decode(zipFileBase64);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(zipBytes);
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
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
            return list.stream().map(LambdaCfnProvisioner::normalizeForCompare).toList();
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

    private List<Object> resolveObjectListOrEmpty(JsonNode props, String source,
                                                  CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return List.of();
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved == null) {
            return List.of();
        }
        if (!resolved.isArray()) {
            throw new AwsException("ValidationError", source + " must be a list", 400);
        }
        List<Object> values = new ArrayList<>();
        resolved.forEach(value -> values.add(jsonNodeToValue(value)));
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

    private void provisionLambdaLayerVersion(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        if (props == null || !props.has("Content")) {
            throw new AwsException("ValidationError",
                    "Lambda LayerVersion " + r.getLogicalId() + " is missing Content", 400);
        }
        String layerName = ctx.resolveOptional(props, "LayerName");
        if (layerName == null || layerName.isBlank()) {
            layerName = ctx.generatePhysicalName(r.getLogicalId(), 140, false);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("Content", jsonObjectToMap(engine.resolveNode(props.get("Content"))));
        String description = ctx.resolveOptional(props, "Description");
        if (description != null) {
            request.put("Description", description);
        }
        String licenseInfo = ctx.resolveOptional(props, "LicenseInfo");
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

    private void deleteLambdaFunctionSafe(String functionName, String region) {
        try {
            lambdaService.deleteFunction(region, functionName);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Lambda function already gone, treating as deleted: {0}", functionName);
        }
    }

    /**
     * True when {@code physicalId} has the deterministic shape {@code generatePhysicalName} produces:
     * the expected {stackName}-{logicalId} prefix (truncated the same way) followed by exactly 12
     * lowercase hex characters. Used to infer a legacy function's name mode (explicit vs. generated)
     * when it predates the attribute that would otherwise record it.
     *
     * <p>Assumes the {@code generatePhysicalName} call this mirrors used {@code lowercase=false} and a
     * {@code maxLength} large enough that the truncated prefix is never empty ({@code maxLength > 13}),
     * both true of the Lambda caller (64).
     */
    private boolean isGeneratedName(String physicalId, String stackName, String logicalId, int maxLength) {
        if (physicalId == null || physicalId.length() < 13) {
            return false;
        }
        String suffix = physicalId.substring(physicalId.length() - 12);
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        if (physicalId.charAt(physicalId.length() - 13) != '-') {
            return false;
        }
        String actualPrefix = physicalId.substring(0, physicalId.length() - 13);
        return actualPrefix.equals(expectedGeneratedNamePrefix(stackName, logicalId, maxLength));
    }

    /** Mirrors {@code generatePhysicalName}'s base-and-truncation logic, without the random suffix. */
    private String expectedGeneratedNamePrefix(String stackName, String logicalId, int maxLength) {
        String base = stackName + "-" + logicalId;
        if (maxLength <= 0 || base.length() + 1 + 12 <= maxLength) {
            return base;
        }
        int keep = Math.max(0, maxLength - 12 - 1);
        String prefix = base.length() > keep ? base.substring(0, keep) : base;
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static String sourceToZipBase64(String source, String handler, String runtime) {
        return InlineZipPackager.sourceToZipBase64(source, handler, runtime);
    }

    private static String defaultHandlerZipBase64() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write("exports.handler=async(e)=>({statusCode:200})".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create default handler zip", e);
        }
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
}
