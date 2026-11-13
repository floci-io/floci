package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the OIDC discovery document and JWKS for an EKS cluster's IRSA issuer.
 *
 * <p>Floci's STS resolves signing keys in-process, so nothing in the IRSA flow dereferences these
 * routes — they exist for fidelity and debuggability (inspecting the key a token was signed with,
 * or pointing an external verifier at Floci).
 *
 * <p>Hosted under {@code _floci/eks/...} rather than at the AWS-shaped issuer path. The issuer
 * ({@code https://oidc.eks.<region>.amazonaws.com/id/<id>}) is not resolvable by Floci's embedded
 * DNS, and serving {@code /id/<id>/.well-known/...} at the root would collide with S3's path-style
 * catch-all — the same hazard that forced the Cognito well-known routes to discriminate on an
 * underscore. Keying by cluster name keeps the route unambiguous.
 */
@ApplicationScoped
@Path("_floci/eks/clusters/{name}/oidc")
@Produces(MediaType.APPLICATION_JSON)
public class EksOidcWellKnownController {

    private final EksService eksService;
    private final EksOidcService oidcService;
    private final EmulatorConfig config;

    @Inject
    public EksOidcWellKnownController(EksService eksService, EksOidcService oidcService,
                                      EmulatorConfig config) {
        this.eksService = eksService;
        this.oidcService = oidcService;
        this.config = config;
    }

    @GET
    @Path(".well-known/openid-configuration")
    public Response openIdConfiguration(@PathParam("name") String clusterName,
                                        @QueryParam("region") String region) {
        Cluster cluster = eksService.findClusterForFlociRoute(clusterName, region);
        String issuer = requireIssuer(cluster, clusterName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("jwks_uri", jwksUri(clusterName, eksService.clusterRegion(cluster)));
        body.put("authorization_endpoint", "urn:kubernetes:programmatic_authorization");
        body.put("response_types_supported", List.of("id_token"));
        body.put("subject_types_supported", List.of("public"));
        body.put("id_token_signing_alg_values_supported", List.of("RS256"));
        body.put("claims_supported", List.of("sub", "iss", "aud", "exp", "iat"));
        return Response.ok(body).build();
    }

    @GET
    @Path("keys")
    public Response keys(@PathParam("name") String clusterName, @QueryParam("region") String region) {
        Cluster cluster = eksService.findClusterForFlociRoute(clusterName, region);
        requireIssuer(cluster, clusterName);
        ClusterOidcKey key = oidcService.findKeyByCluster(eksService.clusterKey(cluster))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No OIDC signing key for cluster: " + clusterName, 404));

        RSAPublicKey publicKey = oidcService.toPublicKey(key.getPublicKey());
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("alg", "RS256");
        jwk.put("use", "sig");
        jwk.put("kid", key.getKeyId());
        jwk.put("n", base64UrlEncodeUnsigned(publicKey.getModulus()));
        jwk.put("e", base64UrlEncodeUnsigned(publicKey.getPublicExponent()));

        return Response.ok(Map.of("keys", List.of(jwk))).build();
    }

    private String requireIssuer(Cluster cluster, String clusterName) {
        String issuer = cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                ? cluster.getIdentity().getOidc().getIssuer()
                : null;
        if (issuer == null || issuer.isBlank()) {
            throw new AwsException("ResourceNotFoundException",
                    "Cluster " + clusterName + " has no OIDC issuer", 404);
        }
        return issuer;
    }

    /**
     * The key route of the cluster discovery resolved. It names the cluster's Region, since a verifier
     * follows it without the discovery request's context, and cluster names are unique only within a
     * Region of an account.
     */
    private String jwksUri(String clusterName, String region) {
        return config.effectiveBaseUrl() + "/_floci/eks/clusters/" + clusterName + "/oidc/keys?region=" + region;
    }

    /**
     * Encodes an RSA parameter as an unsigned big-endian base64url value, dropping the leading zero
     * byte {@link BigInteger#toByteArray()} adds for sign — JWK requires the unsigned form.
     */
    private String base64UrlEncodeUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64Url.encode(bytes);
    }
}
