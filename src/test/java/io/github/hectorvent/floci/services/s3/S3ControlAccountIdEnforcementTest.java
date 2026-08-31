package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The account-level Block Public Access operations are keyed by the {@code x-amz-account-id}
 * header. Block Public Access is a security control, so the header must not be usable to read,
 * overwrite or delete another account's configuration: AWS s3control requires the header to
 * name the caller's own account and answers {@code AccessDenied} otherwise.
 *
 * <p>Every controller here shares one {@link S3Service}, as the CDI beans do — only the resolved
 * caller account differs between them.
 */
class S3ControlAccountIdEnforcementTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String GOVERNED_ACCOUNT = "452743914166";
    private static final String OTHER_ACCOUNT = "999988887777";

    private static final String CONFIG_XML =
            "<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>"
                    + "</PublicAccessBlockConfiguration>";

    @TempDir
    Path tempDir;

    private S3Service service;

    @BeforeEach
    void setUp() {
        service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve("s3"), false);
    }

    @Test
    void callerMayManageItsOwnAccountConfiguration() {
        S3ControlController owner = controllerFor(GOVERNED_ACCOUNT);

        assertEquals(200, owner.putPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML).getStatus());

        Response read = owner.getPublicAccessBlock(GOVERNED_ACCOUNT);
        assertEquals(200, read.getStatus());
        assertTrue(read.getEntity().toString().contains("<BlockPublicAcls>true</BlockPublicAcls>"));

        assertEquals(204, owner.deletePublicAccessBlock(GOVERNED_ACCOUNT).getStatus());
    }

    @Test
    void foreignCallerCannotWriteAnotherAccountConfiguration() {
        controllerFor(GOVERNED_ACCOUNT).putPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML);

        Response response = controllerFor(OTHER_ACCOUNT)
                .putPublicAccessBlock(GOVERNED_ACCOUNT, "<PublicAccessBlockConfiguration/>");

        assertEquals(403, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>AccessDenied</Code>"));

        // The victim's configuration is untouched.
        Response read = controllerFor(GOVERNED_ACCOUNT).getPublicAccessBlock(GOVERNED_ACCOUNT);
        assertTrue(read.getEntity().toString().contains("<BlockPublicAcls>true</BlockPublicAcls>"));
    }

    @Test
    void foreignCallerCannotReadOrDeleteAnotherAccountConfiguration() {
        controllerFor(GOVERNED_ACCOUNT).putPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML);
        S3ControlController attacker = controllerFor(OTHER_ACCOUNT);

        Response read = attacker.getPublicAccessBlock(GOVERNED_ACCOUNT);
        assertEquals(403, read.getStatus());
        assertTrue(read.getEntity().toString().contains("<Code>AccessDenied</Code>"));

        Response deleted = attacker.deletePublicAccessBlock(GOVERNED_ACCOUNT);
        assertEquals(403, deleted.getStatus());
        assertTrue(deleted.getEntity().toString().contains("<Code>AccessDenied</Code>"));

        assertEquals(200, controllerFor(GOVERNED_ACCOUNT)
                .getPublicAccessBlock(GOVERNED_ACCOUNT).getStatus());
    }

    @Test
    void managementAccountCallerMayManageAGovernedAccountConfiguration() {
        // LZA's Custom::PutPublicAccessBlock Lambda runs under the management account's
        // placeholder credentials while naming each governed account in the header.
        S3ControlController management = controllerFor(DEFAULT_ACCOUNT);

        assertEquals(200, management.putPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML).getStatus());
        assertEquals(200, management.getPublicAccessBlock(GOVERNED_ACCOUNT).getStatus());
        // The config landed in the governed account's partition, not the management account's.
        assertEquals(404, management.getPublicAccessBlock(DEFAULT_ACCOUNT).getStatus());
    }

    @Test
    void missingAccountIdStillFailsAsInvalidRequest() {
        Response response = controllerFor(GOVERNED_ACCOUNT).getPublicAccessBlock("  ");

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>InvalidRequest</Code>"));
    }

    /**
     * {@code PublicAccessBlockConfiguration} is a required member of {@code PutPublicAccessBlockRequest}.
     * Block Public Access is a security control, so an empty, unparseable, or wrong-root body must
     * be rejected as {@code MalformedXML} rather than silently normalized into a configuration with
     * every flag defaulted to false — which would look like the caller deliberately disabled every
     * protection.
     */
    @Test
    void putRejectsAMalformedOrWrongRootBody() {
        S3ControlController owner = controllerFor(GOVERNED_ACCOUNT);
        owner.putPublicAccessBlock(GOVERNED_ACCOUNT, CONFIG_XML);

        for (String body : new String[] {null, "", "not xml",
                "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>"}) {
            Response response = owner.putPublicAccessBlock(GOVERNED_ACCOUNT, body);
            assertEquals(400, response.getStatus(), "expected rejection for body: " + body);
            assertTrue(response.getEntity().toString().contains("<Code>MalformedXML</Code>"),
                    "expected MalformedXML for body: " + body);
        }

        // The existing configuration must survive a rejected write.
        Response read = owner.getPublicAccessBlock(GOVERNED_ACCOUNT);
        assertTrue(read.getEntity().toString().contains("<BlockPublicAcls>true</BlockPublicAcls>"));
    }

    private S3ControlController controllerFor(String callerAccountId) {
        RequestContext context = new RequestContext();
        context.setAccountId(callerAccountId);

        @SuppressWarnings("unchecked")
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(context);

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);

        return new S3ControlController(service, contextInstance, config);
    }
}
