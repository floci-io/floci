package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Account-level Block Public Access is a security control that AWS LZA sets once per governed
 * account during the LoggingStack deploy; a resumed pipeline does not re-run
 * {@code Custom::PutPublicAccessBlock}. It must therefore survive a restart rather than living
 * only in process memory.
 */
class S3AccountPublicAccessBlockPersistenceTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String GOVERNED_ACCOUNT = "452743914166";

    private static final String CONFIG_XML =
            "<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>"
                    + "<RestrictPublicBuckets>true</RestrictPublicBuckets>"
                    + "</PublicAccessBlockConfiguration>";

    @Test
    void accountConfigurationSurvivesRestart(@TempDir Path dir) {
        S3Service first = newService(dir);
        first.putAccountPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML);
        first.putAccountPublicAccessBlock(DEFAULT_ACCOUNT, "<PublicAccessBlockConfiguration/>");

        S3Service restarted = newService(dir);

        assertEquals(CONFIG_XML, restarted.getAccountPublicAccessBlock(GOVERNED_ACCOUNT));
        assertEquals("<PublicAccessBlockConfiguration/>",
                restarted.getAccountPublicAccessBlock(DEFAULT_ACCOUNT));
    }

    @Test
    void deletedAccountConfigurationStaysDeletedAcrossRestart(@TempDir Path dir) {
        S3Service first = newService(dir);
        first.putAccountPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML);
        first.deleteAccountPublicAccessBlock(GOVERNED_ACCOUNT);

        S3Service restarted = newService(dir);

        AwsException ex = assertThrows(AwsException.class,
                () -> restarted.getAccountPublicAccessBlock(GOVERNED_ACCOUNT));
        assertEquals("NoSuchPublicAccessBlockConfiguration", ex.getErrorCode());
    }

    private S3Service newService(Path dir) {
        PersistentStorage<String, String> backend = new PersistentStorage<>(
                dir.resolve("s3-account-public-access-block.json"),
                new TypeReference<Map<String, String>>() {});
        backend.load();
        return new S3Service(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(backend, null, DEFAULT_ACCOUNT),
                dir.resolve("s3"),
                false);
    }
}
