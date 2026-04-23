package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void createFunctionWithDirectoryDotInNodeHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "dir-dot-handler-fn",
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "folder.name/index.handler",
                "Code", Map.of("ZipFile", createZipBase64("folder.name/index.js"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("folder.name/index.handler", fn.getHandler());
    }

    @Test
    void createFunctionWithDirectoryDotInGoHandler() throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "FunctionName", "go-handler-fn",
                "Runtime", "provided.al2",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "folder.name/bootstrap",
                "Code", Map.of("ZipFile", createZipBase64("folder.name/bootstrap"))
        ));
        LambdaFunction fn = service.createFunction(REGION, req);
        assertEquals("folder.name/bootstrap", fn.getHandler());
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
}
