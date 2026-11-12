package io.floci.aws.services.iam;

import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The data plane's routes are checked as the actions AWS checks them for, however the key is
 * named.
 *
 * <p>The key of a data-plane request may be named by its identifier or by its ARN, and an ARN
 * carries a slash that a client sends encoded. The router rewrites the request onto the
 * internal prefix, and the vendored routes, at the model's paths, are matched on the rewritten
 * path without it, which is decoded. So the ARN arrives as a path that holds a real slash, and
 * a route whose label stops at the first slash would not match it: the request would resolve to
 * no action, and a policy that denies the action would not be enforced.
 *
 * <p>The rest of the repository holds a request to its standard: a request is refused however
 * it is sent. This is that standard for the data plane.
 */
class PaymentCryptographyDataPlaneIamRouteTest {

    private final IamActionRegistry registry = new IamActionRegistry();

    private static final String KEY_ID = "1234abcd-12ab-34cd-56ef-1234567890ab";

    private static final String KEY_ARN =
            "arn:aws:payment-cryptography:us-east-1:000000000000:key/" + KEY_ID;

    @Test
    void theKeyIdentifierResolvesToItsAction() {
        assertEquals("payment-cryptography:EncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ID + "/encrypt"));
        assertEquals("payment-cryptography:DecryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ID + "/decrypt"));
        assertEquals("payment-cryptography:ReEncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ID + "/reencrypt"));
    }

    @Test
    void theKeyArnResolvesToTheSameAction() {
        assertEquals("payment-cryptography:EncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ARN + "/encrypt"));
        assertEquals("payment-cryptography:DecryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ARN + "/decrypt"));
        assertEquals("payment-cryptography:ReEncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ARN + "/reencrypt"));
    }

    @Test
    void theEncodedKeyArnResolvesToTheSameAction() {
        // The router matches the rewritten path decoded, so the encoded slash of the ARN is a
        // real slash by the time the rule is matched.
        String encoded = KEY_ARN.replace(":", "%3A").replace("/", "%2F");
        assertEquals("payment-cryptography:EncryptData",
                resolve("/_paymentcryptography/keys/" + encoded + "/encrypt"));
        assertEquals("payment-cryptography:DecryptData",
                resolve("/_paymentcryptography/keys/" + encoded + "/decrypt"));
        assertEquals("payment-cryptography:ReEncryptData",
                resolve("/_paymentcryptography/keys/" + encoded + "/reencrypt"));
    }

    @Test
    void theOtherOperationsResolveToTheirActions() {
        assertEquals("payment-cryptography:GenerateMac", resolve("/_paymentcryptography/mac/generate"));
        assertEquals("payment-cryptography:VerifyMac", resolve("/_paymentcryptography/mac/verify"));
    }

    @Test
    void thePinOperationsResolveToTheirActions() {
        assertEquals("payment-cryptography:GeneratePinData",
                resolve("/_paymentcryptography/pindata/generate"));
        assertEquals("payment-cryptography:VerifyPinData",
                resolve("/_paymentcryptography/pindata/verify"));
        assertEquals("payment-cryptography:TranslatePinData",
                resolve("/_paymentcryptography/pindata/translate"));
        assertEquals("payment-cryptography:GenerateMacEmvPinChange",
                resolve("/_paymentcryptography/macemvpinchange/generate"));
    }

    @Test
    void theRoutesAreReadUnderTheServicesScope() {
        // A client of the data plane signs for the service, which is one scope for both of
        // its planes, and a client that signs for the data plane's own scope is served under
        // that scope too (see CredentialScopeAliasTest), so the routes are read under the one
        // scope the actions live in.
        assertEquals("payment-cryptography:EncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ID + "/encrypt"));
        assertEquals("payment-cryptography:EncryptData",
                resolve("/_paymentcryptography/keys/" + KEY_ARN + "/encrypt"));
    }

    @Test
    void theCardOperationsResolveToTheirActions() {
        assertEquals("payment-cryptography:GenerateCardValidationData",
                resolve("/_paymentcryptography/cardvalidationdata/generate"));
        assertEquals("payment-cryptography:VerifyCardValidationData",
                resolve("/_paymentcryptography/cardvalidationdata/verify"));
        assertEquals("payment-cryptography:GenerateAuthRequestCryptogram",
                resolve("/_paymentcryptography/cryptogram/generate"));
        assertEquals("payment-cryptography:VerifyAuthRequestCryptogram",
                resolve("/_paymentcryptography/cryptogram/verify"));
    }

    /** The action the registry resolves for a POST to the path, for the service's scope. */
    private String resolve(String path) {
        return resolve("payment-cryptography", path);
    }

    /** The action the registry resolves for a POST to the path. */
    private String resolve(String scope, String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + path));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getMethod()).thenReturn("POST");
        // The data plane's route filter records the prefix it moves the request under.
        when(ctx.getProperty(SignedServiceRoute.INTERNAL_PREFIX_PROPERTY)).thenReturn("/_paymentcryptography");
        return registry.resolveRoute(scope, ctx);
    }
}
