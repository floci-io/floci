package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

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
    void iotJobsDataAliasNormalisesToIot() {
        // The IoT Jobs Data Plane signs as iot-jobs-data, but its policy actions live under the
        // iot: namespace (iot:DescribeJobExecution and peers in the Service Authorization
        // Reference), so enforcement has to resolve it there rather than invent iot-jobs-data:.
        assertEquals("iot", catalog.canonicalCredentialScope("iot-jobs-data"));
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
    void scopeWhoseExternalKeyIsNotItsIamNamespaceIsLeftAlone() {
        // These are the traps: the catalog routes SES under "email" and Bedrock Runtime under
        // "bedrock-runtime", but their IAM namespaces are "ses:" and "bedrock:". Deriving the
        // canonical scope from the external key would rewrite valid scopes onto prefixes AWS
        // never issues, so every action would resolve to null and enforcement would be skipped.
        assertEquals("ses", catalog.canonicalCredentialScope("ses"));
        assertEquals("sesv2", catalog.canonicalCredentialScope("sesv2"));
        assertEquals("bedrock", catalog.canonicalCredentialScope("bedrock"));
        assertEquals("logs", catalog.canonicalCredentialScope("logs"));
    }

    @Test
    void onlyExplicitlyAliasedScopesAreRewritten() {
        // Sweep every scope the catalog declares. Anything that changes is a service whose
        // IAM namespace we just moved, so it must be a deliberate alias, not a derivation.
        Map<String, String> rewritten = catalog.all().stream()
                .flatMap(descriptor -> descriptor.credentialScopes().stream())
                .distinct()
                .filter(scope -> !scope.equals(catalog.canonicalCredentialScope(scope)))
                .collect(Collectors.toMap(scope -> scope, catalog::canonicalCredentialScope));

        assertEquals(Map.of("s3express", "s3", "iot-jobs-data", "iot"), rewritten);
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
