package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Account-level (S3 Control) Block Public Access.
 *
 * <p>AWS LZA's {@code Custom::PutPublicAccessBlock} custom resource sets account-wide
 * S3 Block Public Access via the s3control API ({@code PutPublicAccessBlock} keyed by
 * {@code AccountId}, distinct from the bucket-level operation). This is what the
 * LoggingStack deploy exercises for every governed account.
 */
class S3AccountPublicAccessBlockTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        Path dataRoot = tempDir.resolve("s3");
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
    }

    @Test
    void putThenGetAccountPublicAccessBlockRoundTrips() {
        String xml = "<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>"
                + "</PublicAccessBlockConfiguration>";
        s3Service.putAccountPublicAccessBlock("452743914166", xml);
        assertEquals(xml, s3Service.getAccountPublicAccessBlock("452743914166"));
    }

    @Test
    void accountConfigsAreIsolatedPerAccount() {
        s3Service.putAccountPublicAccessBlock("452743914166", "<a/>");
        s3Service.putAccountPublicAccessBlock("000000000000", "<b/>");
        assertEquals("<a/>", s3Service.getAccountPublicAccessBlock("452743914166"));
        assertEquals("<b/>", s3Service.getAccountPublicAccessBlock("000000000000"));
    }

    @Test
    void getAccountPublicAccessBlockThrowsNoSuchWhenUnset() {
        AwsException ex = assertThrows(AwsException.class,
                () -> s3Service.getAccountPublicAccessBlock("000000000000"));
        assertEquals("NoSuchPublicAccessBlockConfiguration", ex.getErrorCode());
    }

    @Test
    void deleteAccountPublicAccessBlockRemovesConfig() {
        s3Service.putAccountPublicAccessBlock("000000000000", "<x/>");
        s3Service.deleteAccountPublicAccessBlock("000000000000");
        assertThrows(AwsException.class,
                () -> s3Service.getAccountPublicAccessBlock("000000000000"));
    }

    @Test
    void deleteAccountPublicAccessBlockIsIdempotent() {
        // Deleting an absent config must not throw (mirrors AWS 204 on repeat delete).
        s3Service.deleteAccountPublicAccessBlock("000000000000");
    }

    @Test
    void accountPublicAccessBlockRequiresAccountId() {
        AwsException ex = assertThrows(AwsException.class,
                () -> s3Service.putAccountPublicAccessBlock("  ", "<x/>"));
        assertEquals("InvalidRequest", ex.getErrorCode());
    }

    @Test
    void clearRemovesAccountPublicAccessBlock() {
        s3Service.putAccountPublicAccessBlock("000000000000", "<x/>");
        s3Service.clear();
        assertThrows(AwsException.class,
                () -> s3Service.getAccountPublicAccessBlock("000000000000"));
    }
}
