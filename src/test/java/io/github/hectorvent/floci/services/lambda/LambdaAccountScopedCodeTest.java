package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
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

    @Test
    void publishVersionAdoptsACounterPersistedUnderThePreAccountKey(@TempDir Path baseDir) throws Exception {
        // The counter map is persisted, and its whole point is that a restart must not re-issue
        // an already-used version number. Re-keying it must therefore carry the old value
        // forward rather than restart numbering from 1 over existing snapshots.
        LambdaService svc = serviceFor(ACCOUNT_A, new CodeStore(baseDir));
        svc.createFunction(REGION, zipRequest("legacy-fn", "A"));
        svc.versionCounters().put(REGION + "::legacy-fn", 3);

        LambdaFunction published = svc.publishVersion(REGION, "legacy-fn", null);

        assertEquals("4", published.getVersion());
        assertNull(svc.versionCounters().get(REGION + "::legacy-fn"),
                "the migrated legacy entry must not linger and be adopted twice");
    }

    @Test
    void updateFunctionCodeRemovesAPreAccountScopedLegacyDirectory(@TempDir Path baseDir) throws Exception {
        // A function created before account-scoping left its code at baseDir/<functionName>.
        // Updating its code after the upgrade re-extracts to the new account-scoped path but
        // must also reclaim the old directory, or it lingers on disk forever.
        CodeStore codeStore = new CodeStore(baseDir);
        LambdaService svc = serviceFor(ACCOUNT_A, codeStore);
        svc.createFunction(REGION, zipRequest("legacy-fn", "A"));
        Path legacyPath = codeStore.getLegacyCodePath("legacy-fn");
        Files.createDirectories(legacyPath);
        Files.writeString(legacyPath.resolve("index.js"), "stale");

        svc.updateFunctionCode(REGION, "legacy-fn",
                Map.of("ZipFile", zipBase64("index.js", "B")));

        assertFalse(Files.exists(legacyPath), "the pre-account-scoped directory must be reclaimed on update");
    }

    @Test
    void updateFunctionCodeDoesNotDeleteALegacyDirectoryStillReferencedByAPublishedVersion(
            @TempDir Path baseDir) throws Exception {
        // publishVersion snapshots codeLocalPath verbatim (it must, or a version-qualified
        // invoke launches a container with no code - see #1987). If $LATEST was still on the
        // legacy path at publish time, that version's snapshot is now the ONLY thing keeping
        // the legacy directory alive once $LATEST itself migrates. The unused-check only scanned
        // $LATEST records, so it missed this and reclaimed the directory out from under the
        // published version's own future invokes.
        CodeStore codeStore = new CodeStore(baseDir);
        LambdaService svc = serviceFor(ACCOUNT_A, codeStore);
        svc.createFunction(REGION, zipRequest("legacy-version-fn", "v1"));

        Path legacyPath = codeStore.getLegacyCodePath("legacy-version-fn");
        Files.createDirectories(legacyPath);
        Files.writeString(legacyPath.resolve("index.js"), "v1");
        LambdaFunction latest = svc.getFunction(REGION, "legacy-version-fn");
        latest.setCodeLocalPath(legacyPath.toAbsolutePath().normalize().toString());

        LambdaFunction version = svc.publishVersion(REGION, "legacy-version-fn", null);
        assertEquals(legacyPath.toAbsolutePath().normalize().toString(), version.getCodeLocalPath(),
                "the published version must have snapshotted the legacy path");

        svc.updateFunctionCode(REGION, "legacy-version-fn", Map.of("ZipFile", zipBase64("index.js", "v2")));

        assertTrue(Files.exists(legacyPath),
                "a legacy directory a published version still references must survive this update");
    }

    @Test
    void updateFunctionCodeDoesNotDeleteALegacyDirectoryStillLiveForAnotherAccount(@TempDir Path baseDir) throws Exception {
        // Before account-scoping, two accounts' same-named functions shared the exact same
        // on-disk directory. If account B's function was never updated since the migration, its
        // $LATEST still points at that shared legacy directory. Account A updating its own code
        // must not delete it out from under B.
        CodeStore codeStore = new CodeStore(baseDir);
        LambdaFunctionStore sharedStore = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT_A));
        LambdaService svcA = new LambdaService(sharedStore, new WarmPool(), codeStore, new ZipExtractor(),
                new RegionResolver(REGION, ACCOUNT_A));
        svcA.createFunction(REGION, zipRequest("shared-fn", "A"));

        Path legacyPath = codeStore.getLegacyCodePath("shared-fn");
        Files.createDirectories(legacyPath);
        Files.writeString(legacyPath.resolve("index.js"), "B-legacy");
        LambdaFunction bFn = new LambdaFunction();
        bFn.setFunctionName("shared-fn");
        bFn.setAccountId(ACCOUNT_B);
        bFn.setVersion("$LATEST");
        bFn.setCodeLocalPath(legacyPath.toAbsolutePath().normalize().toString());
        sharedStore.saveForAccount(ACCOUNT_B, REGION, bFn);

        svcA.updateFunctionCode(REGION, "shared-fn", Map.of("ZipFile", zipBase64("index.js", "A-v2")));

        assertTrue(Files.exists(legacyPath),
                "a legacy directory another account's $LATEST still points at must survive this update");
    }

    @Test
    void deleteFunctionDoesNotDeleteALegacyDirectoryStillLiveForAnotherAccount(@TempDir Path baseDir) throws Exception {
        CodeStore codeStore = new CodeStore(baseDir);
        LambdaFunctionStore sharedStore = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT_A));
        LambdaService svcA = new LambdaService(sharedStore, new WarmPool(), codeStore, new ZipExtractor(),
                new RegionResolver(REGION, ACCOUNT_A));
        svcA.createFunction(REGION, zipRequest("shared-fn", "A"));

        Path legacyPath = codeStore.getLegacyCodePath("shared-fn");
        Files.createDirectories(legacyPath);
        Files.writeString(legacyPath.resolve("index.js"), "B-legacy");
        LambdaFunction bFn = new LambdaFunction();
        bFn.setFunctionName("shared-fn");
        bFn.setAccountId(ACCOUNT_B);
        bFn.setVersion("$LATEST");
        bFn.setCodeLocalPath(legacyPath.toAbsolutePath().normalize().toString());
        sharedStore.saveForAccount(ACCOUNT_B, REGION, bFn);

        svcA.deleteFunction(REGION, "shared-fn");

        assertTrue(Files.exists(legacyPath),
                "a legacy directory another account's $LATEST still points at must survive this delete");
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
