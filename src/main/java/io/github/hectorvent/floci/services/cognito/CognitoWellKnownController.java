package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.cognito.model.UserPool;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.util.Base64;

/**
 * Exposes Cognito well-known endpoints.
 * The JWKS endpoint allows downstream services to verify JWTs issued by Floci Cognito pools.
 * Path mirrors real AWS: /{userPoolId}/.well-known/jwks.json
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CognitoWellKnownController {

    private final CognitoService cognitoService;

    @Inject
    public CognitoWellKnownController(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @GET
    @Path("/{poolId}/.well-known/jwks.json")
    public Response getJwks(@PathParam("poolId") String poolId) {
        UserPool pool = cognitoService.describeUserPool(poolId);
        String kid = cognitoService.getSigningKeyId(pool);
        var publicKey = cognitoService.getSigningPublicKey(pool);
        String modulus = base64UrlEncodeUnsigned(publicKey.getModulus());
        String exponent = base64UrlEncodeUnsigned(publicKey.getPublicExponent());

        String body = """
                {"keys":[{"kty":"RSA","kid":"%s","alg":"RS256","n":"%s","e":"%s","use":"sig"}]}
                """.formatted(kid, modulus, exponent).strip();
        return Response.ok(body).build();
    }

    @GET
    @Path("/{poolId}/.well-known/openid-configuration")
    public Response getOpenIdConfiguration(@PathParam("poolId") String poolId) {
        UserPool pool = cognitoService.describeUserPool(poolId);
        String issuer = cognitoService.getIssuer(pool.getId());
        String jwksUri = cognitoService.getJwksUri(pool.getId());
        String tokenEndpoint = cognitoService.getTokenEndpoint();

        String body = """
                {"issuer":"%s","jwks_uri":"%s","token_endpoint":"%s","subject_types_supported":["public"],"response_types_supported":[],"grant_types_supported":["client_credentials"],"token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"],"id_token_signing_alg_values_supported":["RS256"]}
                """.formatted(issuer, jwksUri, tokenEndpoint).strip();
        return Response.ok(body).build();
    }

    private String base64UrlEncodeUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
