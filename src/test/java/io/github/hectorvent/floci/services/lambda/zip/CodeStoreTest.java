package io.github.hectorvent.floci.services.lambda.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CodeStoreTest {

    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";

    @Test
    void sameFunctionNameInTwoAccountsResolvesToDistinctDirectories(@TempDir Path baseDir) {
        CodeStore store = new CodeStore(baseDir);

        Path a = store.getCodePath(ACCOUNT_A, "shared-name");
        Path b = store.getCodePath(ACCOUNT_B, "shared-name");

        assertNotEquals(a, b, "two accounts must not share one on-disk extraction directory");
        assertTrue(a.startsWith(baseDir.resolve(ACCOUNT_A)));
        assertTrue(b.startsWith(baseDir.resolve(ACCOUNT_B)));
    }

    @Test
    void deleteRemovesOnlyTheOwningAccountsCode(@TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        writeHandler(store.getCodePath(ACCOUNT_A, "shared-name"), "a");
        writeHandler(store.getCodePath(ACCOUNT_B, "shared-name"), "b");

        store.delete(ACCOUNT_B, "shared-name");

        assertTrue(store.exists(ACCOUNT_A, "shared-name"), "deleting B's code must not touch A's");
        assertFalse(store.exists(ACCOUNT_B, "shared-name"));
        assertEquals("a", Files.readString(store.getCodePath(ACCOUNT_A, "shared-name").resolve("index.js")));
    }

    @Test
    void accountSegmentIsSanitizedLikeTheFunctionName(@TempDir Path baseDir) {
        CodeStore store = new CodeStore(baseDir);

        Path traversal = store.getCodePath("../../etc", "fn");

        assertTrue(traversal.normalize().startsWith(baseDir.normalize()),
                "a hostile account segment must not escape the base directory");
    }

    @Test
    void bareDotDotAccountSegmentCannotEscapeTheBaseDirectory(@TempDir Path baseDir) {
        // "../../etc" contains "/", which sanitizeName replaces with "_", neutralizing it as a
        // single segment. A segment that is EXACTLY ".." consists entirely of otherwise-allowed
        // characters (dots), so it survives that replacement untouched and still resolves to the
        // parent directory once handed to Path.resolve.
        CodeStore store = new CodeStore(baseDir);

        Path traversal = store.getCodePath("..", "fn");

        assertTrue(traversal.normalize().startsWith(baseDir.normalize()),
                "a bare '..' account segment must not escape the base directory");
    }

    @Test
    void bareDotFunctionSegmentCannotResolveToTheAccountDirectoryItself(@TempDir Path baseDir) {
        CodeStore store = new CodeStore(baseDir);

        Path traversal = store.getCodePath(ACCOUNT_A, ".");

        assertTrue(traversal.normalize().startsWith(baseDir.normalize()));
        assertNotEquals(baseDir.resolve(ACCOUNT_A).normalize(), traversal.normalize(),
                "a bare '.' function segment must not collapse to the account directory itself");
    }

    @Test
    void deleteDoesNotTouchAPreAccountScopedLegacyDirectory(@TempDir Path baseDir) throws IOException {
        // The pre-account-scoped layout gave every account's same-named function the exact same
        // directory, so CodeStore itself cannot safely know whether another account's function
        // still depends on it. That decision belongs to the caller (LambdaService, which can
        // check every account's persisted functions) via the separate deleteLegacy() below -
        // delete() must only ever touch its own account-scoped path.
        CodeStore store = new CodeStore(baseDir);
        Path legacyPath = baseDir.resolve("legacy-fn");
        writeHandler(legacyPath, "legacy");
        writeHandler(store.getCodePath(ACCOUNT_A, "legacy-fn"), "current");

        store.delete(ACCOUNT_A, "legacy-fn");

        assertTrue(Files.exists(legacyPath), "delete() must not unilaterally remove the legacy directory");
        assertFalse(store.exists(ACCOUNT_A, "legacy-fn"));
    }

    @Test
    void deleteLegacyRemovesThePreAccountScopedDirectory(@TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        Path legacyPath = store.getLegacyCodePath("legacy-fn");
        writeHandler(legacyPath, "legacy");

        store.deleteLegacy("legacy-fn");

        assertFalse(Files.exists(legacyPath));
    }

    private void writeHandler(Path codePath, String content) throws IOException {
        Files.createDirectories(codePath);
        Files.writeString(codePath.resolve("index.js"), content);
    }
}
