package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two accounts owning a same-named function in the same region must not share
 * on-disk extracted code or a PublishVersion counter. Cross-account invoke
 * resolves a function by the ARN's own account ({@code LambdaService.resolveInvokeTarget}),
 * so a collision here silently serves one account's code under the other's ARN.
 */
class LambdaAccountScopedCodeTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";

    @Test
    void sameFunctionNameInTwoAccountsKeepsItsOwnCodeOnDisk(@TempDir Path baseDir) throws Exception {
        CodeStore sharedCodeStore = new CodeStore(baseDir);
        LambdaService svcA = serviceFor(ACCOUNT_A, sharedCodeStore);
        LambdaService svcB = serviceFor(ACCOUNT_B, sharedCodeStore);

        LambdaFunction a = svcA.createFunction(REGION, zipRequest("shared-fn", "module.exports.handler = 'A';"));
        LambdaFunction b = svcB.createFunction(REGION, zipRequest("shared-fn", "module.exports.handler = 'B';"));

        assertNotEquals(a.getCodeLocalPath(), b.getCodeLocalPath(),
                "each account's function must extract to its own directory");
        assertEquals("module.exports.handler = 'A';",
                Files.readString(Path.of(a.getCodeLocalPath()).resolve("index.js")),
                "account A's code must survive account B's create");
        assertEquals("module.exports.handler = 'B';",
                Files.readString(Path.of(b.getCodeLocalPath()).resolve("index.js")));
    }

    @Test
    void deletingOneAccountsFunctionLeavesTheOthersCodeIntact(@TempDir Path baseDir) throws Exception {
        CodeStore sharedCodeStore = new CodeStore(baseDir);
        LambdaService svcA = serviceFor(ACCOUNT_A, sharedCodeStore);
        LambdaService svcB = serviceFor(ACCOUNT_B, sharedCodeStore);

        LambdaFunction a = svcA.createFunction(REGION, zipRequest("shared-fn", "A"));
        svcB.createFunction(REGION, zipRequest("shared-fn", "B"));

        svcB.deleteFunction(REGION, "shared-fn");

        assertTrue(sharedCodeStore.exists(ACCOUNT_A, "shared-fn"),
                "deleting B's function must not delete A's code");
        assertEquals("A", Files.readString(Path.of(a.getCodeLocalPath()).resolve("index.js")));
    }

    @Test
    void publishVersionCounterKeyCarriesTheOwningAccount() {
        LambdaFunction a = functionOwnedBy(ACCOUNT_A);
        LambdaFunction b = functionOwnedBy(ACCOUNT_B);

        assertNotEquals(LambdaService.versionCounterKey(REGION, a), LambdaService.versionCounterKey(REGION, b),
                "same-named functions in different accounts must number versions independently");
        assertTrue(LambdaService.versionCounterKey(REGION, a).contains(ACCOUNT_A));
    }

    private LambdaFunction functionOwnedBy(String accountId) {
        LambdaFunction fn = new LambdaFunction();
        fn.setAccountId(accountId);
        fn.setFunctionName("shared-fn");
        return fn;
    }

    private LambdaService serviceFor(String accountId, CodeStore codeStore) {
        return new LambdaService(
                new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(),
                codeStore,
                new ZipExtractor(),
                new RegionResolver(REGION, accountId));
    }

    private Map<String, Object> zipRequest(String name, String handlerSource) throws Exception {
        return new java.util.HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "index.handler",
                "Code", Map.of("ZipFile", zipBase64("index.js", handlerSource))
        ));
    }

    private String zipBase64(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes());
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
