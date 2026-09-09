package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.LambdaFileSystemConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LambdaServiceTest {

    private static final String REGION = "us-east-1";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        WarmPool warmPool = new WarmPool();
        CodeStore codeStore = new CodeStore(Path.of("target/test-data/lambda-code"));
        ZipExtractor zipExtractor = new ZipExtractor();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new LambdaService(store, warmPool, codeStore, zipExtractor, null, regionResolver, storageFactory);
    }

    private Map<String, Object> baseRequest(String name) {
        return new java.util.HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "index.handler",
                "Timeout", 10,
                "MemorySize", 256
        ));
    }

    private static Map<String, Object> vpcConfig() {
        return Map.of(
                "SubnetIds", List.of("subnet-0123456789abcdef0"),
                "SecurityGroupIds", List.of("sg-0123456789abcdef0"));
    }

    @Test
    void createFunctionSucceeds() {
        LambdaFunction fn = service.createFunction(REGION, baseRequest("my-function"));

        assertEquals("my-function", fn.getFunctionName());
        assertEquals("nodejs20.x", fn.getRuntime());
        assertEquals("index.handler", fn.getHandler());
        assertEquals(10, fn.getTimeout());
        assertEquals(256, fn.getMemorySize());
        assertEquals("Active", fn.getState());
        assertNotNull(fn.getFunctionArn());
        assertTrue(fn.getFunctionArn().contains("my-function"));
        assertNotNull(fn.getRevisionId());
    }

    @Test
    void createAndUpdateFunctionFileSystemConfig() {
        Map<String, Object> request = baseRequest("efs-function");
        request.put("VpcConfig", vpcConfig());
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "LocalMountPath", "/mnt/shared")));

        LambdaFunction created = service.createFunction(REGION, request);
        assertEquals(1, created.getFileSystemConfigs().size());
        assertEquals("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                created.getFileSystemConfigs().getFirst().getArn());
        assertEquals("/mnt/shared", created.getFileSystemConfigs().getFirst().getLocalMountPath());

        LambdaFunction updated = service.updateFunctionConfiguration(REGION, "efs-function",
                Map.of("FileSystemConfigs", List.of()));
        assertTrue(updated.getFileSystemConfigs().isEmpty());
    }

    @Test
    void publishedVersionKeepsFileSystemConfigSnapshot() {
        Map<String, Object> request = baseRequest("versioned-efs-function");
        request.put("VpcConfig", vpcConfig());
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "LocalMountPath", "/mnt/shared")));

        service.createFunction(REGION, request);
        LambdaFunction version = service.publishVersion(REGION, "versioned-efs-function", null);
        service.updateFunctionConfiguration(REGION, "versioned-efs-function",
                Map.of(
                        "FileSystemConfigs", List.of(),
                        "VpcConfig", Map.of(
                                "SubnetIds", List.of("subnet-updated"),
                                "SecurityGroupIds", List.of("sg-updated"))));

        assertEquals(vpcConfig(), version.getVpcConfig());
        assertEquals(1, version.getFileSystemConfigs().size());
        LambdaFileSystemConfig fileSystem = version.getFileSystemConfigs().getFirst();
        assertEquals("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                fileSystem.getArn());
        assertEquals("/mnt/shared", fileSystem.getLocalMountPath());
    }

    @Test
    void createFunctionRejectsInvalidFileSystemMountPath() {
        Map<String, Object> request = baseRequest("invalid-efs-function");
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "LocalMountPath", "/tmp/shared")));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));
        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void createFunctionRejectsInvalidFileSystemAccessPointArn() {
        Map<String, Object> request = baseRequest("invalid-efs-arn-function");
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/fs-0123456789abcdef0",
                "LocalMountPath", "/mnt/shared")));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));
        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void createFunctionRejectsMoreThanOneFileSystemConfig() {
        Map<String, Object> request = baseRequest("too-many-efs-function");
        request.put("FileSystemConfigs", List.of(
                Map.of(
                        "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                        "LocalMountPath", "/mnt/shared"),
                Map.of(
                        "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef1",
                        "LocalMountPath", "/mnt/other")));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));
        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void createFunctionRejectsUnsupportedS3FilesAccessPointArn() {
        Map<String, Object> request = baseRequest("s3-files-function");
        String arn = "arn:aws:s3files:us-east-1:000000000000:"
                + "file-system/fs-0123456789abcdef0/access-point/fsap-0123456789abcdef0";
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", arn,
                "LocalMountPath", "/mnt/shared")));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));
        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void createFunctionRejectsFileSystemConfigWithoutVpcConfig() {
        Map<String, Object> request = baseRequest("efs-without-vpc-function");
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "LocalMountPath", "/mnt/shared")));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));
        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void updateFunctionAcceptsVpcAndFileSystemConfigTogether() {
        service.createFunction(REGION, baseRequest("update-efs-function"));

        LambdaFunction updated = service.updateFunctionConfiguration(REGION, "update-efs-function",
                Map.of(
                        "VpcConfig", vpcConfig(),
                        "FileSystemConfigs", List.of(Map.of(
                                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:"
                                        + "access-point/fsap-0123456789abcdef0",
                                "LocalMountPath", "/mnt/shared"))));

        assertEquals(1, updated.getFileSystemConfigs().size());
        assertEquals(vpcConfig(), updated.getVpcConfig());
    }

    @Test
    void updateFunctionRejectsRemovingVpcWhileFileSystemConfigRemains() {
        Map<String, Object> request = baseRequest("update-efs-vpc-function");
        request.put("VpcConfig", vpcConfig());
        request.put("FileSystemConfigs", List.of(Map.of(
                "Arn", "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "LocalMountPath", "/mnt/shared")));
        service.createFunction(REGION, request);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "update-efs-vpc-function",
                        Map.of("VpcConfig", Map.of(
                                "SubnetIds", List.of(),
                                "SecurityGroupIds", List.of()))));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(vpcConfig(), service.getFunction(REGION, "update-efs-vpc-function").getVpcConfig());
    }

    @Test
    void updateFunctionConfigurationRejectsMalformedRoleArn() {
        service.createFunction(REGION, baseRequest("update-role-function"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "update-role-function",
                        Map.of("Role", "not-an-arn")));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals("arn:aws:iam::000000000000:role/test-role",
                service.getFunction(REGION, "update-role-function").getRole());
    }

    @Test
    void updateFunctionConfigurationAcceptsValidRoleArn() {
        service.createFunction(REGION, baseRequest("update-role-valid-function"));

        LambdaFunction updated = service.updateFunctionConfiguration(REGION, "update-role-valid-function",
                Map.of("Role", "arn:aws:iam::000000000000:role/new-role"));

        assertEquals("arn:aws:iam::000000000000:role/new-role", updated.getRole());
    }

    @Test
    void updateFunctionConfigurationRejectsHandlerWithWhitespace() {
        service.createFunction(REGION, baseRequest("update-handler-function"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "update-handler-function",
                        Map.of("Handler", "index handler")));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals("index.handler",
                service.getFunction(REGION, "update-handler-function").getHandler());
    }

    @Test
    void updateFunctionConfigurationRejectsHandlerLongerThan128Chars() {
        service.createFunction(REGION, baseRequest("update-handler-length-function"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "update-handler-length-function",
                        Map.of("Handler", "h".repeat(129))));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
    }

    @Test
    void updateFunctionConfigurationRejectsExplicitNullHandler() {
        service.createFunction(REGION, baseRequest("update-handler-null-function"));
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("Handler", null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "update-handler-null-function", request));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals("index.handler",
                service.getFunction(REGION, "update-handler-null-function").getHandler(),
                "an explicit null must not silently clear the handler");
    }

    @Test
    void createFunctionFailsWhenMissingFunctionName() {
        Map<String, Object> req = baseRequest("x");
        req.remove("FunctionName");
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void createFunctionFailsWhenMissingRole() {
        Map<String, Object> req = baseRequest("x");
        req.remove("Role");
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void createFunctionFailsForDuplicate() {
        service.createFunction(REGION, baseRequest("dup"));
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, baseRequest("dup")));
        assertEquals("ResourceConflictException", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void getFunctionReturnsCreatedFunction() {
        service.createFunction(REGION, baseRequest("get-fn"));
        LambdaFunction fn = service.getFunction(REGION, "get-fn");
        assertEquals("get-fn", fn.getFunctionName());
    }

    @Test
    void getFunctionThrows404WhenNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getFunction(REGION, "nonexistent"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void getFunctionAcceptsPartialArn() {
        service.createFunction(REGION, baseRequest("arn-fn"));
        LambdaFunction fn = service.getFunction(REGION, "000000000000:function:arn-fn");
        assertEquals("arn-fn", fn.getFunctionName());
    }

    @Test
    void getFunctionAcceptsFullArn() {
        service.createFunction(REGION, baseRequest("arn-fn"));
        LambdaFunction fn = service.getFunction(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:arn-fn");
        assertEquals("arn-fn", fn.getFunctionName());
    }

    @Test
    void getFunctionAcceptsArnWithQualifier() {
        service.createFunction(REGION, baseRequest("arn-fn"));
        LambdaFunction fn = service.getFunction(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:arn-fn:prod");
        assertEquals("arn-fn", fn.getFunctionName());
    }

    @Test
    void getFunctionRejectsRegionMismatch() {
        service.createFunction(REGION, baseRequest("region-fn"));
        AwsException ex = assertThrows(AwsException.class, () -> service.getFunction(REGION,
                "arn:aws:lambda:eu-west-1:000000000000:function:region-fn"));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void getFunctionRejectsMalformedArn() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getFunction(REGION,
                "arn:aws:lambda:us-east-1:000000000000:layer:my-layer"));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createFunctionDeduplicatesAcrossNameAndArn() {
        service.createFunction(REGION, baseRequest("dedup-fn"));
        Map<String, Object> req = baseRequest("arn:aws:lambda:us-east-1:000000000000:function:dedup-fn");
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("ResourceConflictException", ex.getErrorCode());
    }

    @Test
    void deleteFunctionAcceptsFullArn() {
        service.createFunction(REGION, baseRequest("delete-arn-fn"));
        service.deleteFunction(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:delete-arn-fn");
        assertThrows(AwsException.class, () -> service.getFunction(REGION, "delete-arn-fn"));
    }

    @Test
    void putFunctionConcurrencyAcceptsFullArn() {
        service.createFunction(REGION, baseRequest("concurrency-arn-fn"));
        service.putFunctionConcurrency(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:concurrency-arn-fn", 5);
        assertEquals(5, service.getFunctionConcurrency(REGION, "concurrency-arn-fn"));
    }

    @Test
    void listFunctionsReturnsAllInRegion() {
        service.createFunction(REGION, baseRequest("fn-1"));
        service.createFunction(REGION, baseRequest("fn-2"));
        service.createFunction("eu-west-1", baseRequest("fn-3"));

        List<LambdaFunction> functions = service.listFunctions(REGION);
        assertEquals(2, functions.size());
        assertTrue(functions.stream().anyMatch(f -> f.getFunctionName().equals("fn-1")));
        assertTrue(functions.stream().anyMatch(f -> f.getFunctionName().equals("fn-2")));
    }

    @Test
    void deleteFunctionRemovesIt() {
        service.createFunction(REGION, baseRequest("del-fn"));
        service.deleteFunction(REGION, "del-fn");
        assertThrows(AwsException.class, () -> service.getFunction(REGION, "del-fn"));
    }

    @Test
    void deleteFunctionThrows404WhenNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteFunction(REGION, "ghost"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void createImageFunctionSucceedsWithoutHandler() {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "image-fn",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "PackageType", "Image",
                "ImageUri", "myrepo/myimage:latest"
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("image-fn", fn.getFunctionName());
        assertEquals("Image", fn.getPackageType());
        assertNull(fn.getHandler());
    }

    @Test
    void createImageFunctionSucceedsWithHandler() {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "image-fn-with-handler",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "PackageType", "Image",
                "ImageUri", "myrepo/myimage:latest",
                "Handler", "com.example.Handler::handleRequest"
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("com.example.Handler::handleRequest", fn.getHandler());
    }

    @Test
    void createZipFunctionFailsWithoutHandler() {
        Map<String, Object> req = baseRequest("zip-no-handler");
        req.remove("Handler");
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Handler"));
    }

    private static String createZipBase64(String... entryPaths) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String path : entryPaths) {
                zos.putNextEntry(new ZipEntry(path));
                zos.write("exports.handler = async () => ({});\n".getBytes());
                zos.closeEntry();
            }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    void publishVersionCarriesTheOwningAccount() {
        service.createFunction(REGION, baseRequest("account-versioned-fn"));

        LambdaFunction version = service.publishVersion(REGION, "account-versioned-fn", null);

        // A version snapshot is built field by field, so anything reading accountId off it (the
        // deployment-package key, execution-role session ownership) otherwise has to re-derive the
        // account from the ARN or fall back to the default account.
        assertEquals("000000000000", version.getAccountId());
        assertEquals(
                service.getFunction(REGION, "account-versioned-fn").getAccountId(),
                version.getAccountId());
    }

    @Test
    void createFunctionWithSubdirectoryHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "subdir-handler-fn",
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "src/index.handler",
                "Code", Map.of("ZipFile", createZipBase64("src/index.js"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("src/index.handler", fn.getHandler());
    }

    @Test
    void createFunctionWithRootHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "root-handler-fn",
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "index.handler",
                "Code", Map.of("ZipFile", createZipBase64("index.js"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("index.handler", fn.getHandler());
    }

    @Test
    void createFunctionWithNestedPythonModuleHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "nested-python-handler-fn",
                "Runtime", "python3.11",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "apps.foo.src.lambda_handler.lambda_handler",
                "Code", Map.of("ZipFile", createZipBase64("apps/foo/src/lambda_handler.py"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("apps.foo.src.lambda_handler.lambda_handler", fn.getHandler());
    }

    @Test
    void createFunctionWithNestedPythonPackageHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "nested-python-package-handler-fn",
                "Runtime", "python3.11",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "apps.foo.src.lambda_handler.lambda_handler",
                "Code", Map.of("ZipFile", createZipBase64("apps/foo/src/lambda_handler/__init__.py"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("apps.foo.src.lambda_handler.lambda_handler", fn.getHandler());
    }

    @Test
    void createFunctionWithMissingHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "missing-handler-fn",
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "src/index.handler",
                "Code", Map.of("ZipFile", createZipBase64("other.js"))
        ));
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void createFunctionWithMissingNestedPythonModuleHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "missing-nested-python-handler-fn",
                "Runtime", "python3.11",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "apps.foo.src.lambda_handler.lambda_handler",
                "Code", Map.of("ZipFile", createZipBase64("apps/foo/src/other.py"))
        ));
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("apps/foo/src/lambda_handler"));
    }

    @Test
    void createDotnetFunctionWithAssemblyHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "dotnet-fn",
                "Runtime", "dotnet6",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "blank-net-lambda::blank_net_lambda.Function::FunctionHandler",
                "Code", Map.of("ZipFile", createZipBase64("blank-net-lambda.dll"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("blank-net-lambda::blank_net_lambda.Function::FunctionHandler", fn.getHandler());
    }

    @Test
    void updateFunctionCodeUpdatesRevision() {
        service.createFunction(REGION, baseRequest("update-fn"));
        LambdaFunction original = service.getFunction(REGION, "update-fn");
        String originalRevision = original.getRevisionId();

        // Updating with no-op (no zip or image uri) still bumps revision
        LambdaFunction updated = service.updateFunctionCode(REGION, "update-fn", Map.of());
        assertNotEquals(originalRevision, updated.getRevisionId());
    }

    @Test
    void updateFunctionCodeAppliesArchitectures() {
        Map<String, Object> req = baseRequest("arch-fn");
        req.put("Architectures", List.of("arm64"));
        service.createFunction(REGION, req);

        LambdaFunction updated = service.updateFunctionCode(REGION, "arch-fn",
                Map.of("Architectures", List.of("x86_64")));
        assertEquals(List.of("x86_64"), updated.getArchitectures());
        assertEquals(List.of("x86_64"), service.getFunction(REGION, "arch-fn").getArchitectures());
    }

    @Test
    void createFunctionRejectsUnsupportedArchitectureWithoutPersistingFunction() {
        Map<String, Object> request = baseRequest("unsupported-create-architecture");
        request.put("Architectures", List.of("sparc"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));

        assertUnsupportedArchitecture(error);
        assertTrue(service.listFunctions(REGION).isEmpty());
    }

    @Test
    void createFunctionRejectsMultipleArchitecturesWithoutPersistingFunction() {
        Map<String, Object> request = baseRequest("multiple-create-architectures");
        request.put("Architectures", List.of("arm64", "x86_64"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value '[arm64, x86_64]' at 'architectures' "
                + "failed to satisfy constraint: Member must have length less than or equal to 1",
                error.getMessage());
        assertTrue(service.listFunctions(REGION).isEmpty());
    }

    @Test
    void createFunctionRejectsEmptyArchitecturesWithoutPersistingFunction() {
        Map<String, Object> request = baseRequest("empty-create-architectures");
        request.put("Architectures", List.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value '[]' at 'architectures' "
                + "failed to satisfy constraint: Member must have length greater than or equal to 1",
                error.getMessage());
        assertTrue(service.listFunctions(REGION).isEmpty());
    }

    @Test
    void createFunctionRejectsScalarArchitecturesWithoutPersistingFunction() {
        Map<String, Object> request = baseRequest("scalar-create-architectures");
        request.put("Architectures", "arm64");

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));

        assertMalformedArchitectures(error, "arm64");
        assertTrue(service.listFunctions(REGION).isEmpty());
    }

    @Test
    void updateFunctionCodeRejectsUnsupportedArchitectureBeforeMutation() {
        service.createFunction(REGION, baseRequest("unsupported-code-architecture"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionCode(REGION, "unsupported-code-architecture",
                        Map.of("ImageUri", "example.invalid/changed:latest",
                                "Architectures", List.of("sparc"))));

        assertUnsupportedArchitecture(error);
        LambdaFunction unchanged = service.getFunction(REGION, "unsupported-code-architecture");
        assertNull(unchanged.getImageUri());
        assertNull(unchanged.getArchitectures());
    }

    @Test
    void updateFunctionCodeRejectsObjectArchitecturesBeforeMutation() {
        service.createFunction(REGION, baseRequest("object-code-architectures"));
        String revisionId = service.getFunction(REGION, "object-code-architectures").getRevisionId();
        Map<String, String> malformedArchitectures = Map.of("Architecture", "arm64");

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionCode(REGION, "object-code-architectures",
                        Map.of("ImageUri", "example.invalid/changed:latest",
                                "Architectures", malformedArchitectures)));

        assertMalformedArchitectures(error, malformedArchitectures);
        LambdaFunction unchanged = service.getFunction(REGION, "object-code-architectures");
        assertNull(unchanged.getImageUri());
        assertNull(unchanged.getArchitectures());
        assertEquals(revisionId, unchanged.getRevisionId());
    }

    @Test
    void updateFunctionConfigurationRejectsUnsupportedArchitectureBeforeMutation() {
        service.createFunction(REGION, baseRequest("unsupported-config-architecture"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "unsupported-config-architecture",
                        Map.of("Description", "changed", "Architectures", List.of("sparc"))));

        assertUnsupportedArchitecture(error);
        LambdaFunction unchanged = service.getFunction(REGION, "unsupported-config-architecture");
        assertNull(unchanged.getDescription());
        assertNull(unchanged.getArchitectures());
    }

    @Test
    void updateFunctionConfigurationRejectsScalarArchitecturesBeforeMutation() {
        service.createFunction(REGION, baseRequest("scalar-config-architectures"));
        String revisionId = service.getFunction(REGION, "scalar-config-architectures").getRevisionId();

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunctionConfiguration(REGION, "scalar-config-architectures",
                        Map.of("Description", "changed", "Architectures", 64)));

        assertMalformedArchitectures(error, 64);
        LambdaFunction unchanged = service.getFunction(REGION, "scalar-config-architectures");
        assertNull(unchanged.getDescription());
        assertNull(unchanged.getArchitectures());
        assertEquals(revisionId, unchanged.getRevisionId());
    }

    @Test
    void updateFunctionCodeWithoutArchitecturesKeepsExisting() {
        Map<String, Object> req = baseRequest("arch-keep-fn");
        req.put("Architectures", List.of("arm64"));
        service.createFunction(REGION, req);

        LambdaFunction updated = service.updateFunctionCode(REGION, "arch-keep-fn", Map.of());
        assertEquals(List.of("arm64"), updated.getArchitectures());
    }

    private static void assertUnsupportedArchitecture(AwsException error) {
        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value 'sparc' at 'architectures.1.member' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [x86_64, arm64]",
                error.getMessage());
    }

    private static void assertMalformedArchitectures(AwsException error, Object value) {
        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value '" + value + "' at 'architectures' "
                + "failed to satisfy constraint: Member must be a list",
                error.getMessage());
    }

    @Test
    void rehydrateConcurrency_restoresReservedFromStore() {
        // Simulate a persisted state: functions already live in the store
        // with reserved values before the limiter is populated.
        service.createFunction(REGION, baseRequest("persisted-a"));
        service.createFunction(REGION, baseRequest("persisted-b"));
        service.putFunctionConcurrency(REGION, "persisted-a", 300);
        service.putFunctionConcurrency(REGION, "persisted-b", 200);

        // Build a second service over the same store with a fresh limiter.
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<>());
        // Copy the two persisted functions into the new store to emulate a
        // restart with the same disk state.
        for (LambdaFunction fn : new java.util.ArrayList<>(
                service.listFunctions(REGION))) {
            store.save(REGION, fn);
        }
        LambdaService rebooted = new LambdaService(store, new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(), new RegionResolver(REGION, "000000000000"));

        // Starts empty…
        assertEquals(0, rebooted.concurrencyLimiter().totalReserved(REGION));
        // …until rehydrate walks the store and re-registers the reserved values.
        rebooted.rehydrateConcurrency();
        assertEquals(500, rebooted.concurrencyLimiter().totalReserved(REGION));
    }

    @Test
    void multiArnPutFunctionConcurrency_respectsRegionTotalUnderContention() throws Exception {
        // Two different functions racing a Put near the unreserved floor.
        // Both try to reserve an amount that — summed — would push the
        // region below unreserved-min. reservedLock must serialize so that
        // only one wins.
        service.createFunction(REGION, baseRequest("multi-a"));
        service.createFunction(REGION, baseRequest("multi-b"));

        // Defaults: regionLimit=1000, unreservedMin=100. Each Put asks for
        // 500; together they would leave 0 unreserved.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<Throwable> fA = pool.submit(() -> {
                start.await();
                try {
                    service.putFunctionConcurrency(REGION, "multi-a", 500);
                    return null;
                } catch (Throwable t) { return t; }
            });
            java.util.concurrent.Future<Throwable> fB = pool.submit(() -> {
                start.await();
                try {
                    service.putFunctionConcurrency(REGION, "multi-b", 500);
                    return null;
                } catch (Throwable t) { return t; }
            });
            start.countDown();

            Throwable rA = fA.get();
            Throwable rB = fB.get();

            // Exactly one must have been rejected with LimitExceededException.
            int successes = (rA == null ? 1 : 0) + (rB == null ? 1 : 0);
            assertEquals(1, successes, "exactly one Put must win");
            Throwable rejected = rA != null ? rA : rB;
            assertTrue(rejected instanceof AwsException
                            && "LimitExceededException".equals(((AwsException) rejected).getErrorCode()),
                    "other Put must be rejected, got " + rejected);
            assertEquals(500,
                    service.concurrencyLimiter().totalReserved(REGION),
                    "limiter total must reflect only the winning Put");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void putFunctionConcurrency_rollsBackLimiterIfSaveFails() {
        // If functionStore.save throws after the limiter has been updated,
        // the limiter must be restored so Σreserved stays consistent with
        // what the store actually persisted.
        FailingStore failing = new FailingStore();
        LambdaService svc = new LambdaService(failing, new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(), new RegionResolver(REGION, "000000000000"));
        svc.createFunction(REGION, baseRequest("rb-fn"));
        // Baseline: Put of 300 succeeds
        svc.putFunctionConcurrency(REGION, "rb-fn", 300);
        assertEquals(300, svc.concurrencyLimiter().totalReserved(REGION));

        // Now make the next save() explode and try to Put 500
        failing.shouldFail = true;
        assertThrows(RuntimeException.class,
                () -> svc.putFunctionConcurrency(REGION, "rb-fn", 500));

        // Limiter must have rolled back to the previous 300
        assertEquals(300, svc.concurrencyLimiter().totalReserved(REGION),
                "limiter must unwind on save failure");
    }

    @Test
    void deleteFunction_preservesInflightPermitUntilItCloses() {
        // A deleteFunction that lands while an invocation is still holding a
        // permit must not drop the counter — the permit close() at the end
        // of the running invocation still has to decrement something valid.
        service.createFunction(REGION, baseRequest("del-inflight"));
        service.putFunctionConcurrency(REGION, "del-inflight", 2);

        LambdaFunction fn = service.getFunction(REGION, "del-inflight");
        String arn = fn.getFunctionArn();
        LambdaConcurrencyLimiter.Permit held =
                service.concurrencyLimiter().acquire(fn);
        assertEquals(1, service.concurrencyLimiter().inflightCount(arn));

        service.deleteFunction(REGION, "del-inflight");

        // Reserved is cleared from the limiter, but the inflight counter
        // must still be live for the held permit to decrement into.
        assertEquals(0, service.concurrencyLimiter().totalReserved(REGION));
        assertEquals(1, service.concurrencyLimiter().inflightCount(arn),
                "inflight must survive delete until the permit closes");

        held.close();
        assertEquals(0, service.concurrencyLimiter().inflightCount(arn));
    }

    // ──────────────────────────── Hot-reload ────────────────────────────

    private LambdaService serviceWithHotReload(boolean enabled, List<String> allowedPaths) {
        return serviceWithHotReload(enabled, allowedPaths, ZipExtractor.DEFAULT_MAX_ENTRIES);
    }

    private LambdaService serviceWithHotReload(boolean enabled, List<String> allowedPaths,
                                               int zipMaxEntries) {
        EmulatorConfig cfg = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig svc = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaCfg = mock(EmulatorConfig.LambdaServiceConfig.class);
        EmulatorConfig.LambdaServiceConfig.HotReload hr = mock(EmulatorConfig.LambdaServiceConfig.HotReload.class);

        when(cfg.services()).thenReturn(svc);
        when(svc.lambda()).thenReturn(lambdaCfg);
        when(lambdaCfg.hotReload()).thenReturn(hr);
        when(lambdaCfg.defaultTimeoutSeconds()).thenReturn(3);
        when(lambdaCfg.defaultMemoryMb()).thenReturn(128);
        when(lambdaCfg.zipMaxEntries()).thenReturn(zipMaxEntries);
        when(hr.enabled()).thenReturn(enabled);
        when(hr.allowedPaths()).thenReturn(allowedPaths == null ? Optional.empty() : Optional.of(allowedPaths));

        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        WarmPool warmPool = new WarmPool();
        CodeStore codeStore = new CodeStore(Path.of("target/test-data/lambda-code"));
        ZipExtractor zipExtractor = new ZipExtractor();
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        return new LambdaService(store, warmPool, codeStore, zipExtractor, cfg, regionResolver);
    }

    @Test
    void createFunctionRejectsConfiguredZipEntryLimit() throws Exception {
        LambdaService limited = serviceWithHotReload(true, null, 2);
        Map<String, Object> request = baseRequest("zip-entry-limit");
        request.put("Code", Map.of("ZipFile", createZipBase64("index.js", "one.js", "two.js")));

        AwsException error = assertThrows(AwsException.class,
                () -> limited.createFunction(REGION, request));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertTrue(error.getMessage().contains("more than the configured 2 entries"));
    }

    @Test
    void createFunctionRejectsOversizedDirectZipUploadWithAwsError() {
        Map<String, Object> request = baseRequest("oversized-zip");
        byte[] oversized = new byte[(int) ZipExtractor.DIRECT_UPLOAD_MAX_COMPRESSED_BYTES + 1];
        request.put("Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(oversized)));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunction(REGION, request));

        assertEquals("RequestEntityTooLargeException", error.getErrorCode());
        assertEquals(413, error.getHttpStatus());
    }

    @Test
    void hotReload_disabledByDefault_throwsInvalidParameter() {
        // The package-private test constructor leaves config=null, which means disabled.
        Map<String, Object> req = baseRequest("hr-disabled");
        req.put("Code", Map.of("S3Bucket", "hot-reload", "S3Key", "/tmp/my-fn"));
        AwsException ex = assertThrows(AwsException.class, () -> service.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void hotReload_nonAbsolutePath_throwsInvalidParameter() {
        LambdaService svc = serviceWithHotReload(true, null);
        Map<String, Object> req = baseRequest("hr-relpath");
        req.put("Code", Map.of("S3Bucket", "hot-reload", "S3Key", "relative/path"));
        AwsException ex = assertThrows(AwsException.class, () -> svc.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("absolute"));
    }

    @Test
    void hotReload_allowListRejection_throwsInvalidParameter() {
        LambdaService svc = serviceWithHotReload(true, List.of("/allowed/"));
        Map<String, Object> req = baseRequest("hr-denied");
        req.put("Code", Map.of("S3Bucket", "hot-reload", "S3Key", "/not-allowed/my-fn"));
        AwsException ex = assertThrows(AwsException.class, () -> svc.createFunction(REGION, req));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("allowed"));
    }

    @Test
    void hotReload_happyPath_setsHostPathAndClearsCodeLocalPath() {
        LambdaService svc = serviceWithHotReload(true, null);
        Map<String, Object> req = baseRequest("hr-fn");
        req.put("Code", Map.of("S3Bucket", "hot-reload", "S3Key", "/tmp/my-fn"));
        LambdaFunction fn = svc.createFunction(REGION, req);
        assertEquals("/tmp/my-fn", fn.getHotReloadHostPath());
        assertNull(fn.getCodeLocalPath());
        assertTrue(fn.isHotReload());
        assertEquals(0L, fn.getCodeSizeBytes());
    }

    @Test
    void hotReload_allowListAccepted_setsHostPath() {
        LambdaService svc = serviceWithHotReload(true, List.of("/allowed/"));
        Map<String, Object> req = baseRequest("hr-allowed");
        req.put("Code", Map.of("S3Bucket", "hot-reload", "S3Key", "/allowed/my-fn"));
        LambdaFunction fn = svc.createFunction(REGION, req);
        assertEquals("/allowed/my-fn", fn.getHotReloadHostPath());
    }

    @Test
    void hotReload_updateFunctionCode_setsNewHostPath() {
        LambdaService svc = serviceWithHotReload(true, null);
        svc.createFunction(REGION, baseRequest("hr-update"));

        LambdaFunction updated = svc.updateFunctionCode(REGION, "hr-update",
                Map.of("S3Bucket", "hot-reload", "S3Key", "/tmp/v2"));
        assertEquals("/tmp/v2", updated.getHotReloadHostPath());
        assertTrue(updated.isHotReload());
    }

    @Test
    void hotReload_convertFromS3Backed_clearsBucketAndKey() {
        // A function previously deployed from S3 that is later converted to hot-reload
        // must have s3Bucket/s3Key cleared so the reactive S3 sync observer cannot fire.
        LambdaService svc = serviceWithHotReload(true, null);
        Map<String, Object> req = baseRequest("hr-convert");
        req.put("Code", Map.of("S3Bucket", "my-code-bucket", "S3Key", "fn.zip"));
        // createFunction with a non-existent S3 bucket will fail inside extractZipCodeFromS3
        // because s3Service is null in the test constructor → ServiceUnavailableException.
        // So we create without code and then simulate the S3 bucket/key being set directly.
        LambdaFunction fn = svc.createFunction(REGION, baseRequest("hr-convert"));
        fn.setS3Bucket("my-code-bucket");
        fn.setS3Key("fn.zip");

        LambdaFunction updated = svc.updateFunctionCode(REGION, "hr-convert",
                Map.of("S3Bucket", "hot-reload", "S3Key", "/tmp/converted"));

        assertNull(updated.getS3Bucket(), "s3Bucket must be cleared after hot-reload conversion");
        assertNull(updated.getS3Key(), "s3Key must be cleared after hot-reload conversion");
        assertEquals("/tmp/converted", updated.getHotReloadHostPath());
    }

    /**
     * Test helper: a LambdaFunctionStore whose save() throws on demand so
     * tests can exercise the LambdaService rollback path.
     */
    private static final class FailingStore extends LambdaFunctionStore {
        boolean shouldFail = false;
        FailingStore() {
            super(new InMemoryStorage<String, LambdaFunction>());
        }
        @Override
        public void save(String region, LambdaFunction fn) {
            if (shouldFail) {
                throw new RuntimeException("injected save failure");
            }
            super.save(region, fn);
        }
    }

    @Test
    void concurrentPutFunctionConcurrency_endsInConsistentState() throws Exception {
        // Exercise the per-function serialization in concurrencyOpLocks:
        // two threads racing Put on the same function must leave the
        // limiter and the persisted reserved value in agreement with
        // whichever write landed last.
        service.createFunction(REGION, baseRequest("race-fn"));

        int iterations = 50;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                int a = 100 + i;
                int b = 200 + i;
                java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<Integer> fA = pool.submit(() -> {
                    start.await();
                    return service.putFunctionConcurrency(REGION, "race-fn", a)
                            .getReservedConcurrentExecutions();
                });
                java.util.concurrent.Future<Integer> fB = pool.submit(() -> {
                    start.await();
                    return service.putFunctionConcurrency(REGION, "race-fn", b)
                            .getReservedConcurrentExecutions();
                });
                start.countDown();
                fA.get();
                fB.get();

                LambdaFunction fn = service.getFunction(REGION, "race-fn");
                Integer stored = fn.getReservedConcurrentExecutions();
                assertTrue(stored.equals(a) || stored.equals(b),
                        "store should reflect one of the two writes, got " + stored);
                // The real invariant: the limiter's Σreserved for this
                // region must agree with what was persisted. Comparing
                // getFunctionConcurrency() to stored would be a tautology —
                // both read the same LambdaFunction field — so assert
                // against the limiter's independently-maintained total.
                assertEquals(stored.intValue(),
                        service.concurrencyLimiter().totalReserved(REGION),
                        "limiter totalReserved must match persisted reserved value");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPolicyRestoreAddAndReadPreserveAllStatements() throws Exception {
        LambdaFunction function = service.createFunction(REGION, baseRequest("policy-race-fn"));
        service.addPermission(REGION, function.getFunctionName(), null, Map.of(
                "StatementId", "baseline",
                "Action", "lambda:InvokeFunction",
                "Principal", "events.amazonaws.com"));

        int mutationCount = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new java.util.ArrayList<>();
        Set<String> expectedStatementIds = new HashSet<>();
        expectedStatementIds.add("baseline");

        try {
            for (int i = 0; i < mutationCount; i++) {
                String addedId = "added-" + i;
                String restoredId = "restored-" + i;
                expectedStatementIds.add(addedId);
                expectedStatementIds.add(restoredId);

                futures.add(pool.submit(() -> {
                    start.await();
                    service.addPermission(REGION, function.getFunctionName(), null, Map.of(
                            "StatementId", addedId,
                            "Action", "lambda:InvokeFunction",
                            "Principal", "events.amazonaws.com"));
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    service.restorePermissionStatement(REGION, function.getFunctionName(), Map.of(
                            "Sid", restoredId,
                            "Effect", "Allow",
                            "Principal", Map.of("Service", "events.amazonaws.com"),
                            "Action", "lambda:InvokeFunction",
                            "Resource", function.getFunctionArn()));
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < mutationCount; i++) {
                    service.getPolicy(REGION, function.getFunctionName(), null);
                }
                return null;
            }));

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) service
                .getPolicy(REGION, function.getFunctionName(), null)
                .get("policy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");
        Set<String> actualStatementIds = statements.stream()
                .map(statement -> (String) statement.get("Sid"))
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedStatementIds, actualStatementIds);
    }

    @Test
    void publishVersionWaitsForAConcurrentHolderOfTheFunctionsConcurrencyLock() throws Exception {
        // publishVersion reads codeLocalPath and persists a snapshot of it with no
        // synchronization against extractZipCodeBytes's own reclaim-the-legacy-directory
        // decision (which runs under this same per-function lock, e.g. from deleteFunction).
        // Without publishVersion also taking that lock, a version could be published in the
        // narrow window between that decision and the actual delete, persisting a snapshot
        // that references a directory which is about to be removed as "unused". Proving
        // publishVersion blocks on this lock closes that window regardless of the exact
        // interleaving, rather than relying on timing to catch it in the act.
        LambdaFunction fn = service.createFunction(REGION, baseRequest("lock-race-fn"));
        Object lock = service.lockForConcurrencyOp(fn.getFunctionArn());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                acquired.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            holder.start();
            assertTrue(acquired.await(2, java.util.concurrent.TimeUnit.SECONDS));

            Future<LambdaFunction> publishFuture = pool.submit(
                    () -> service.publishVersion(REGION, "lock-race-fn", null));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> publishFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "publishVersion must block while another operation holds this function's lock");

            release.countDown();
            holder.join();
            assertNotNull(publishFuture.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_success() {
        service.createFunction(REGION, baseRequest("kafka-fn"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn",
                "Topics", List.of("my-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                ),
                "SourceAccessConfigurations", List.of(
                        Map.of("Type", "SASL_SCRAM_256_AUTH", "URI", "arn:aws:secretsmanager:us-east-1:000000000000:secret:my-secret")
                )
        ));

        EventSourceMapping esm = service.createEventSourceMapping(REGION, request);

        assertNotNull(esm);
        assertNotNull(esm.getUuid());
        assertNull(esm.getEventSourceArn());
        assertEquals(List.of("my-topic"), esm.getTopics());
        assertNotNull(esm.getSelfManagedEventSource());
        assertEquals(1, esm.getSourceAccessConfigurations().size());
        assertEquals("Enabled", esm.getState());
        assertTrue(esm.isEnabled());

        // Verify update
        EventSourceMapping updated = service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "Topics", List.of("updated-topic")
        ));
        assertEquals(List.of("updated-topic"), updated.getTopics());

        // Verify delete
        service.deleteEventSourceMapping(esm.getUuid());
        assertThrows(AwsException.class, () -> service.getEventSourceMapping(esm.getUuid()));
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_missingTopics_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-missing-topics"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn-missing-topics",
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Topics"));
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_missingBootstrapServers_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-missing-servers"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn-missing-servers",
                "Topics", List.of("my-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of()
                )
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("KAFKA_BOOTSTRAP_SERVERS"));
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_atTimestamp_succeeds() {
        service.createFunction(REGION, baseRequest("kafka-fn-at-timestamp"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn-at-timestamp",
                "Topics", List.of("my-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                ),
                "StartingPosition", "AT_TIMESTAMP",
                "StartingPositionTimestamp", 123456789
        ));

        EventSourceMapping esm = service.createEventSourceMapping(REGION, request);
        assertNotNull(esm);
        assertEquals("AT_TIMESTAMP", esm.getStartingPosition());
        assertEquals(123456789000L, esm.getStartingPositionTimestamp());
    }

    @Test
    void createEventSourceMapping_dynamoDb_atTimestamp_throws() {
        service.createFunction(REGION, baseRequest("dynamo-fn-at-timestamp"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "dynamo-fn-at-timestamp",
                "EventSourceArn", "arn:aws:dynamodb:us-east-1:000000000000:table/my-table/stream/2026-01-01T00:00:00.000",
                "StartingPosition", "AT_TIMESTAMP",
                "StartingPositionTimestamp", 123456789
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("AT_TIMESTAMP is only supported for Amazon Kinesis"));
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_crossRegionFunctionArn_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-cross-region"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "arn:aws:lambda:us-west-2:000000000000:function:kafka-fn-cross-region",
                "Topics", List.of("my-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Region 'us-west-2' in ARN does not match request region 'us-east-1'"));
    }

    @Test
    void createEventSourceMapping_mixedSources_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-mixed"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn-mixed",
                "EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:my-queue",
                "Topics", List.of("my-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Cannot specify both EventSourceArn and SelfManagedEventSource/Topics"));
    }

    @Test
    void createEventSourceMapping_selfManagedKafka_malformedTopics_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-malformed-topics"));

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "FunctionName", "kafka-fn-malformed-topics",
                "Topics", List.of(123),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createEventSourceMapping(REGION, request));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void updateEventSourceMapping_malformedTopics_throws() {
        service.createFunction(REGION, baseRequest("kafka-fn-update-topics"));

        EventSourceMapping esm = service.createEventSourceMapping(REGION, Map.of(
                "FunctionName", "kafka-fn-update-topics",
                "Topics", List.of("valid-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "Topics", List.of()
        )));
        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "Topics", List.of(456)
        )));
    }

    @Test
    void updateEventSourceMapping_functionTargetValidation() {
        service.createFunction(REGION, baseRequest("fn-esm-target-1"));
        service.createFunction(REGION, baseRequest("fn-esm-target-2"));

        EventSourceMapping esm = service.createEventSourceMapping(REGION, Map.of(
                "FunctionName", "fn-esm-target-1",
                "Topics", List.of("valid-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));

        // Valid function update
        EventSourceMapping updated = service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2"
        ));
        assertEquals("fn-esm-target-2", updated.getFunctionName());
        assertTrue(updated.getFunctionArn().contains("fn-esm-target-2"));

        // Qualified version target update
        service.publishVersion(REGION, "fn-esm-target-2", "v1");
        EventSourceMapping updatedVersion = service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2:1"
        ));
        assertEquals("fn-esm-target-2", updatedVersion.getFunctionName());
        assertTrue(updatedVersion.getFunctionArn().endsWith(":fn-esm-target-2:1"));

        // Qualified alias target update
        service.createAlias(REGION, "fn-esm-target-2", "live", "1", "production alias", null);
        EventSourceMapping updatedAlias = service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2:live"
        ));
        assertEquals("fn-esm-target-2", updatedAlias.getFunctionName());
        assertTrue(updatedAlias.getFunctionArn().endsWith(":fn-esm-target-2:live"));

        // Qualified $LATEST target update
        EventSourceMapping updatedLatest = service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2:$LATEST"
        ));
        assertEquals("fn-esm-target-2", updatedLatest.getFunctionName());
        assertTrue(updatedLatest.getFunctionArn().endsWith(":fn-esm-target-2:$LATEST"));

        // Create ESM with qualified function reference
        EventSourceMapping esmQualified = service.createEventSourceMapping(REGION, Map.of(
                "FunctionName", "fn-esm-target-2:live",
                "Topics", List.of("valid-topic"),
                "SelfManagedEventSource", Map.of(
                        "Endpoints", Map.of(
                                "KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092")
                        )
                )
        ));
        assertEquals("fn-esm-target-2", esmQualified.getFunctionName());
        assertTrue(esmQualified.getFunctionArn().endsWith(":fn-esm-target-2:live"));

        // Non-existent version or alias throws 404
        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2:99"
        )));
        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "fn-esm-target-2:non-existent-alias"
        )));

        // Non-existent function throws
        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "non-existent-fn"
        )));

        // Cross-region function ARN throws
        assertThrows(AwsException.class, () -> service.updateEventSourceMapping(esm.getUuid(), Map.of(
                "FunctionName", "arn:aws:lambda:us-west-2:000000000000:function:other-region-fn"
        )));
    }

    @Test
    void functionExists_resolvesQualifiersAndRegions() {
        service.createFunction(REGION, baseRequest("exists-fn"));
        service.publishVersion(REGION, "exists-fn", null);

        assertTrue(service.functionExists(REGION, "exists-fn"));
        assertTrue(service.functionExists(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:exists-fn"));
        assertTrue(service.functionExists(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:exists-fn:1"));

        assertFalse(service.functionExists(REGION, "ghost-fn"));
        // A qualified reference to a version that was never published must not pass just
        // because the base function exists.
        assertFalse(service.functionExists(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:exists-fn:5"));
        // An ARN whose region disagrees with the request region does not resolve.
        assertFalse(service.functionExists(REGION,
                "arn:aws:lambda:eu-west-1:000000000000:function:exists-fn"));
    }

    /**
     * Issue #2958: a published version stored a reference to {@code $LATEST}'s code directory
     * rather than a copy of the code, and extraction replaces that directory wholesale on every
     * deploy. A later UpdateFunctionCode therefore rewrote what an already-published version would
     * run, leaving the version advertising one CodeSha256 over a different build.
     */
    @Test
    void aPublishedVersionKeepsItsOwnCodeWhenLatestIsRedeployed() throws Exception {
        Map<String, Object> request = baseRequest("version-code-isolation-fn");
        request.put("Code", Map.of("ZipFile", zipWithBody("v1")));
        service.createFunction(REGION, request);

        LambdaFunction v1 = service.publishVersion(REGION, "version-code-isolation-fn", null);
        Path v1Path = Path.of(v1.getCodeLocalPath());
        String v1Sha = v1.getCodeSha256();

        assertTrue(Files.isDirectory(v1Path), "a published version must have its own code directory");
        assertEquals("v1", Files.readString(v1Path.resolve("index.js")).trim());

        // Redeploy $LATEST. Extraction replaces its directory wholesale, which is what used to take
        // the published version's code with it.
        service.updateFunctionCode(REGION, "version-code-isolation-fn",
                Map.of("ZipFile", zipWithBody("v2")));

        LambdaFunction latest = service.getFunction(REGION, "version-code-isolation-fn");
        assertNotEquals(v1Path.toString(), latest.getCodeLocalPath(),
                "a version must not share $LATEST's directory");
        assertEquals("v2",
                Files.readString(Path.of(latest.getCodeLocalPath()).resolve("index.js")).trim());

        // The version still holds the bytes it was published from, and they still match the hash it
        // advertises, which is the guarantee that was broken.
        assertTrue(Files.isDirectory(v1Path), "the version's code must survive a redeploy of $LATEST");
        assertEquals("v1", Files.readString(v1Path.resolve("index.js")).trim());
        assertEquals(v1Sha, v1.getCodeSha256());
    }

    @Test
    void deletingAFunctionRemovesItsPublishedVersionsCode() throws Exception {
        Map<String, Object> request = baseRequest("version-code-delete-fn");
        request.put("Code", Map.of("ZipFile", zipWithBody("v1")));
        service.createFunction(REGION, request);

        LambdaFunction v1 = service.publishVersion(REGION, "version-code-delete-fn", null);
        Path v1Path = Path.of(v1.getCodeLocalPath());
        assertTrue(Files.isDirectory(v1Path));

        service.deleteFunction(REGION, "version-code-delete-fn");

        assertFalse(Files.exists(v1Path),
                "a version's code must not outlive the function it belongs to");
    }

    @Test
    void deletingOnePublishedVersionReclaimsOnlyThatVersionsCode() throws Exception {
        Map<String, Object> request = baseRequest("version-code-reclaim-fn");
        request.put("Code", Map.of("ZipFile", zipWithBody("v1")));
        service.createFunction(REGION, request);

        LambdaFunction v1 = service.publishVersion(REGION, "version-code-reclaim-fn", null);
        service.updateFunctionCode(REGION, "version-code-reclaim-fn",
                Map.of("ZipFile", zipWithBody("v2")));
        LambdaFunction v2 = service.publishVersion(REGION, "version-code-reclaim-fn", null);

        Path v1Path = Path.of(v1.getCodeLocalPath());
        Path v2Path = Path.of(v2.getCodeLocalPath());
        Path latestPath = Path.of(
                service.getFunction(REGION, "version-code-reclaim-fn").getCodeLocalPath());
        assertNotEquals(v1Path, v2Path, "two versions must not share one code directory");

        service.deleteFunction(REGION, "version-code-reclaim-fn", v1.getVersion());

        // Only whole-function delete reclaimed any of this before, so every version ever published
        // stayed on disk for as long as the data directory lived.
        assertFalse(Files.exists(v1Path), "the deleted version's code must be reclaimed");
        assertTrue(Files.isDirectory(v2Path), "a surviving version's code must be left alone");
        assertEquals("v2", Files.readString(v2Path.resolve("index.js")).trim());
        assertTrue(Files.isDirectory(latestPath), "$LATEST's code must be left alone");
    }

    private static String zipWithBody(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write((body + "\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    void updateFunctionCodeWaitsForAConcurrentHolderOfTheFunctionsConcurrencyLock() throws Exception {
        assertUpdaterWaitsForTheLock("update-code-lock-fn",
                (service, name) -> service.updateFunctionCode(REGION, name,
                        new HashMap<>(Map.of("ImageUri", "public.ecr.aws/x/y:1"))));
    }

    @Test
    void updateFunctionConfigurationWaitsForAConcurrentHolderOfTheFunctionsConcurrencyLock() throws Exception {
        assertUpdaterWaitsForTheLock("update-config-lock-fn",
                (service, name) -> service.updateFunctionConfiguration(REGION, name,
                        new HashMap<>(Map.of("Timeout", 42))));
    }

    /**
     * publishVersion copies roughly thirty fields off the live function inside this lock. Neither
     * updater used to take it, so nothing was serialised and a snapshot could be part old code and
     * part new configuration (issue #3007). Proving each updater blocks on the lock closes that
     * window regardless of the exact interleaving, rather than relying on timing to catch it.
     */
    private void assertUpdaterWaitsForTheLock(
            String functionName,
            java.util.function.BiFunction<LambdaService, String, LambdaFunction> updater) throws Exception {
        LambdaFunction fn = service.createFunction(REGION, baseRequest(functionName));
        Object lock = service.lockForConcurrencyOp(fn.getFunctionArn());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                acquired.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            holder.start();
            assertTrue(acquired.await(2, java.util.concurrent.TimeUnit.SECONDS));

            Future<LambdaFunction> updateFuture = pool.submit(() -> updater.apply(service, functionName));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> updateFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "the updater must block while another operation holds this function's lock");

            release.countDown();
            holder.join();
            assertNotNull(updateFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
