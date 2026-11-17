package io.floci.aws.services.iam;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.WebIdentityTokenIssuer.Algorithm;
import io.floci.aws.services.iam.WebIdentityTokenIssuer.PublishedKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the OIDC discovery document and the key set for the issuer {@code GetWebIdentityToken}
 * names in its tokens, so a relying party under test can verify a token Floci signed.
 *
 * <p>AWS hosts both at the issuer itself, {@code https://<uuid>.tokens.sts.global.api.aws}, under
 * {@code /.well-known/openid-configuration} and {@code /.well-known/jwks.json}. That host does not
 * reach Floci, and serving {@code /.well-known/...} at the root would answer for no issuer in
 * particular and collide with S3's path-style catch-all, so the routes live under
 * {@code _floci/sts/web-identity/<uuid>}, as {@code EksOidcWellKnownController} does for a
 * cluster's issuer. The token's {@code iss} stays AWS's form; the document names it as
 * {@code issuer}, which is what a verifier compares, and points {@code jwks_uri} at Floci.
 *
 * <p>The UUID is the route's key because it is what AWS keys on too: its one discovery endpoint
 * "serves discovery requests for any issuer UUID in the partition", and a relying party fetching
 * it carries no AWS credential, so the account comes from the issuer rather than the request.
 */
@ApplicationScoped
@Path("_floci/sts/web-identity/{issuerId}/.well-known")
@Produces(MediaType.APPLICATION_JSON)
public class StsWebIdentityWellKnownController {

    /** The byte length of a P-384 coordinate, which a JWK carries at full length. */
    private static final int P384_COORDINATE_BYTES = 48;

    private final IamService iamService;
    private final WebIdentityTokenIssuer tokenIssuer;
    private final EmulatorConfig config;

    @Inject
    public StsWebIdentityWellKnownController(IamService iamService,
                                             WebIdentityTokenIssuer tokenIssuer,
                                             EmulatorConfig config) {
        this.iamService = iamService;
        this.tokenIssuer = tokenIssuer;
        this.config = config;
    }

    /**
     * The discovery document. AWS does not publish the one its issuer serves, so this carries the
     * members OpenID Connect Discovery requires of a provider that issues signed tokens and no
     * more: the issuer, where its keys are, and the two algorithms {@code GetWebIdentityToken}
     * signs with.
     */
    @GET
    @Path("openid-configuration")
    public Response openIdConfiguration(@PathParam("issuerId") String issuerId) {
        String issuer = requireIssuer(issuerId).value();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("jwks_uri", config.effectiveBaseUrl() + "/_floci/sts/web-identity/" + issuerId
                + "/.well-known/jwks.json");
        body.put("response_types_supported", List.of("id_token"));
        body.put("subject_types_supported", List.of("public"));
        body.put("id_token_signing_alg_values_supported", Algorithm.names());
        return Response.ok(body).build();
    }

    /**
     * The account's verification keys, one per algorithm, in the shape of the sample key set the
     * IAM User Guide gives: the EC key names its {@code alg} and curve, and the RSA key carries
     * neither {@code alg} nor anything beyond its modulus and exponent.
     */
    @GET
    @Path("jwks.json")
    public Response keys(@PathParam("issuerId") String issuerId) {
        String accountId = requireIssuer(issuerId).accountId();
        List<Map<String, Object>> keys = new ArrayList<>();
        for (PublishedKey key : tokenIssuer.publishedKeys(accountId)) {
            keys.add(jwk(key));
        }
        return Response.ok(Map.of("keys", keys)).build();
    }

    private AccountAwareStorageBackend.AccountEntry<String> requireIssuer(String issuerId) {
        return iamService.findOutboundWebIdentityIssuer(issuerId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No web identity token issuer: " + issuerId, 404));
    }

    private static Map<String, Object> jwk(PublishedKey key) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        switch (key.algorithm()) {
            case ES384 -> {
                ECPublicKey ec = (ECPublicKey) key.publicKey();
                jwk.put("kty", "EC");
                jwk.put("use", "sig");
                jwk.put("kid", key.keyId());
                jwk.put("alg", key.algorithm().name());
                jwk.put("crv", "P-384");
                jwk.put("x", Base64Url.encodeUnsigned(ec.getW().getAffineX(), P384_COORDINATE_BYTES));
                jwk.put("y", Base64Url.encodeUnsigned(ec.getW().getAffineY(), P384_COORDINATE_BYTES));
            }
            case RS256 -> {
                RSAPublicKey rsa = (RSAPublicKey) key.publicKey();
                jwk.put("kty", "RSA");
                jwk.put("use", "sig");
                jwk.put("kid", key.keyId());
                jwk.put("n", Base64Url.encodeUnsigned(rsa.getModulus()));
                jwk.put("e", Base64Url.encodeUnsigned(rsa.getPublicExponent()));
            }
        }
        return jwk;
    }
}
