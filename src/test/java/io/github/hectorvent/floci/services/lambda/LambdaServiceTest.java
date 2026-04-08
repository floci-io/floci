package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        service = new LambdaService(store, warmPool, codeStore, zipExtractor, regionResolver);
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

    @Test
    void updateFunctionCodeUpdatesRevision() {
        service.createFunction(REGION, baseRequest("update-fn"));
        LambdaFunction original = service.getFunction(REGION, "update-fn");
        String originalRevision = original.getRevisionId();

        // Updating with no-op (no zip or image uri) still bumps revision
        LambdaFunction updated = service.updateFunctionCode(REGION, "update-fn", Map.of());
        assertNotEquals(originalRevision, updated.getRevisionId());
    }
}
