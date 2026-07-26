package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A service may accept more than one signing scope (S3 also answers requests signed for
 * {@code s3express}), but IAM action rules, ARN building and condition keys are keyed by the
 * canonical name. An unnormalised alias resolves to no action, which the enforcement filter
 * treats as ALLOW — so the alias must map back to the canonical scope before enforcement runs.
 */
@QuarkusTest
class CredentialScopeAliasTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Inject
    IamActionRegistry actionRegistry;

    @Test
    void s3ExpressAliasNormalisesToS3() {
        assertEquals("s3", catalog.canonicalCredentialScope("s3express"));
    }

    @Test
    void canonicalScopeIsUnchanged() {
        assertEquals("s3", catalog.canonicalCredentialScope("s3"));
        assertEquals("dynamodb", catalog.canonicalCredentialScope("dynamodb"));
    }

    @Test
    void unknownScopeIsLeftAlone() {
        assertEquals("securityhub", catalog.canonicalCredentialScope("securityhub"));
    }

    @Test
    void scopeWhoseExternalKeyIsNotASigningNameIsLeftAlone() {
        // CloudWatch Logs signs as "logs" while its external key is "cloudwatchlogs";
        // rewriting to the external key there would invent an action prefix AWS never uses.
        assertEquals("logs", catalog.canonicalCredentialScope("logs"));
    }

    @Test
    void normalisedAliasResolvesTheSameS3ActionAsTheCanonicalScope() {
        ContainerRequestContext ctx = getObjectRequest();
        String viaAlias = actionRegistry.resolve(catalog.canonicalCredentialScope("s3express"), ctx);
        String viaCanonical = actionRegistry.resolve("s3", ctx);

        assertEquals(viaCanonical, viaAlias);
        assertEquals("s3:GetObject", viaAlias);
    }

    @Test
    void rawAliasResolvesNoActionWithoutNormalisation() {
        assertEquals(null, actionRegistry.resolve("s3express", getObjectRequest()));
    }

    private static ContainerRequestContext getObjectRequest() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/my-bucket/my-key");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getHeaderString("X-Amz-Target")).thenReturn(null);
        return ctx;
    }
}
