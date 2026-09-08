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

    @Test
    void aVersionDirectoryCannotCollideWithAnotherFunctionsOwnDirectory(@TempDir Path baseDir)
            throws IOException {
        // Floci does not restrict the character set of FunctionName today, only that it is
        // non-blank, so "foo.v1" is a function a user can genuinely create. A "<name>.v<n>" sibling
        // naming scheme handed it the exact directory version 1 of "foo" would claim, so deleting
        // either function silently corrupted the other. The suffix used instead is outside the
        // character set sanitizeName can emit, which makes the two namespaces disjoint by
        // construction rather than by a prefix match that has to guess where the name ends.
        CodeStore store = new CodeStore(baseDir);
        writeHandler(store.getVersionCodePath(ACCOUNT_A, "foo", "1"), "foo-v1");
        writeHandler(store.getCodePath(ACCOUNT_A, "foo.v1"), "other-function");
        writeHandler(store.getVersionCodePath(ACCOUNT_A, "foo.v1", "1"), "other-function-v1");

        store.delete(ACCOUNT_A, "foo");

        assertTrue(store.exists(ACCOUNT_A, "foo.v1"),
                "deleting foo must not remove a function literally named foo.v1");
        assertEquals("other-function",
                Files.readString(store.getCodePath(ACCOUNT_A, "foo.v1").resolve("index.js")));
        assertEquals("other-function-v1",
                Files.readString(store.getVersionCodePath(ACCOUNT_A, "foo.v1", "1").resolve("index.js")),
                "another function's published version code must survive too");
        assertFalse(Files.exists(store.getVersionCodePath(ACCOUNT_A, "foo", "1")),
                "foo's own version code must still be reclaimed");
    }

    @Test
    void deleteVersionRemovesOneVersionAndLeavesTheRestInPlace(@TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        writeHandler(store.getCodePath(ACCOUNT_A, "fn"), "latest");
        writeHandler(store.getVersionCodePath(ACCOUNT_A, "fn", "1"), "one");
        writeHandler(store.getVersionCodePath(ACCOUNT_A, "fn", "2"), "two");

        store.deleteVersion(ACCOUNT_A, "fn", "1");

        assertFalse(Files.exists(store.getVersionCodePath(ACCOUNT_A, "fn", "1")));
        assertEquals("two",
                Files.readString(store.getVersionCodePath(ACCOUNT_A, "fn", "2").resolve("index.js")));
        assertTrue(store.exists(ACCOUNT_A, "fn"), "$LATEST's code must be untouched");
    }

    @Test
    void versionCodeLivesOutsideTheDirectoryExtractionReplaces(@TempDir Path baseDir) {
        CodeStore store = new CodeStore(baseDir);

        Path latest = store.getCodePath(ACCOUNT_A, "fn");
        Path version = store.getVersionCodePath(ACCOUNT_A, "fn", "1");

        assertFalse(version.normalize().startsWith(latest.normalize()),
                "extraction replaces $LATEST's directory wholesale, taking any nested version with it");
        assertTrue(version.normalize().startsWith(baseDir.normalize()));
    }

    private void writeHandler(Path codePath, String content) throws IOException {
        Files.createDirectories(codePath);
        Files.writeString(codePath.resolve("index.js"), content);
    }
}
