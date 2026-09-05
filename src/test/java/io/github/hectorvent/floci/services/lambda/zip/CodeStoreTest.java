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
    void contentAddressedPathsForDifferentHashesAreDistinctSiblings(@TempDir Path baseDir) {
        // #2958: without a version/content component every deploy shared one directory, so a
        // published version and a later $LATEST redeploy could not both keep their own code.
        CodeStore store = new CodeStore(baseDir);

        Path v1 = store.getCodePath(ACCOUNT_A, "fn", "aaaa");
        Path v2 = store.getCodePath(ACCOUNT_A, "fn", "bbbb");

        assertNotEquals(v1, v2);
        assertEquals(store.getCodePath(ACCOUNT_A, "fn"), v1.getParent());
        assertEquals(v1.getParent(), v2.getParent());
    }

    @Test
    void sameHashResolvesToTheSameContentAddressedPath(@TempDir Path baseDir) {
        CodeStore store = new CodeStore(baseDir);

        assertEquals(store.getCodePath(ACCOUNT_A, "fn", "aaaa"), store.getCodePath(ACCOUNT_A, "fn", "aaaa"));
    }

    @Test
    void deleteOfTheWholeFunctionRemovesEveryContentAddressedDirectoryUnderIt(
            @TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        writeHandler(store.getCodePath(ACCOUNT_A, "fn", "aaaa"), "v1");
        writeHandler(store.getCodePath(ACCOUNT_A, "fn", "bbbb"), "v2");

        store.delete(ACCOUNT_A, "fn");

        assertFalse(Files.exists(store.getCodePath(ACCOUNT_A, "fn")));
    }

    @Test
    void deleteContentDirectoryRemovesOnlyThatOneBuild(@TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        Path v1 = store.getCodePath(ACCOUNT_A, "fn", "aaaa");
        Path v2 = store.getCodePath(ACCOUNT_A, "fn", "bbbb");
        writeHandler(v1, "v1");
        writeHandler(v2, "v2");

        store.deleteContentDirectory(v1, "fn");

        assertFalse(Files.exists(v1));
        assertTrue(Files.exists(v2));
    }

    @Test
    void deleteContentDirectoryRefusesAPathOutsideTheBaseDirectory(@TempDir Path baseDir) throws IOException {
        CodeStore store = new CodeStore(baseDir);
        Path outside = baseDir.getParent().resolve("outside-" + baseDir.getFileName());
        writeHandler(outside, "not-ours");

        store.deleteContentDirectory(outside, "fn");

        assertTrue(Files.exists(outside), "must refuse to delete anything outside baseDir");
        Files.walk(outside).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.delete(p);
            } catch (IOException ignored) {
                // best-effort cleanup of the temp fixture created outside @TempDir
            }
        });
    }

    private void writeHandler(Path codePath, String content) throws IOException {
        Files.createDirectories(codePath);
        Files.writeString(codePath.resolve("index.js"), content);
    }
}
