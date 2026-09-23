package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier.ExpiredTokenException;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier.InvalidTokenException;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.EksPodIdentityCredentialsResponse;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Serves the link-local EKS Pod Identity credentials endpoint (169.254.170.23:80).
 * <p>
 * Pods running with projected service account tokens call this endpoint to exchange
 * their token for temporary AWS IAM role credentials associated with the pod's service account.
 */
@ApplicationScoped
@Path("/v1/credentials")
public class EksPodIdentityCredentialsController {

    private static final String POD_IDENTITY_AUDIENCE = "pods.eks.amazonaws.com";
    private static final String SERVICE_ACCOUNT_PREFIX = "system:serviceaccount:";
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** CSPRNG for session secret keys and session tokens; ordinary IDs keep using {@link ThreadLocalRandom}. */
    private final SecureRandom secureRandom = new SecureRandom();

    private final EksService eksService;
    private final EksOidcService oidcService;
    private final EksPodIdentityAssociationService associationService;
    private final IamService iamService;
    private final WebIdentityTokenVerifier tokenVerifier;

    @Inject
    public EksPodIdentityCredentialsController(EksService eksService,
                                               EksOidcService oidcService,
                                               EksPodIdentityAssociationService associationService,
                                               IamService iamService,
                                               WebIdentityTokenVerifier tokenVerifier) {
        this.eksService = eksService;
        this.oidcService = oidcService;
        this.associationService = associationService;
        this.iamService = iamService;
        this.tokenVerifier = tokenVerifier;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCredentials(@HeaderParam("Authorization") String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return error(400, "Service account token cannot be empty\n");
        }

        String token = authHeader.trim();
        if (token.regionMatches(true, 0, "Bearer", 0, 6)) {
            token = token.substring(6).trim();
        }
        if (token.isEmpty()) {
            return error(400, "Service account token cannot be empty\n");
        }

        Optional<String> maybeIssuer = tokenVerifier.peekIssuer(token);
        if (maybeIssuer.isEmpty()) {
            return error(400, "Service account token cannot be parsed\n");
        }
        String issuer = maybeIssuer.get();

        Optional<Cluster> maybeCluster = eksService.findClusterByIssuer(issuer);
        Optional<RSAPublicKey> maybeKey = oidcService.findVerificationKey(issuer);
        if (maybeCluster.isEmpty() || maybeKey.isEmpty()) {
            return error(400, "The web identity token issuer is not recognized\n");
        }

        Cluster cluster = maybeCluster.get();
        RSAPublicKey publicKey = maybeKey.get();

        WebIdentityToken verified;
        try {
            verified = tokenVerifier.verify(token, publicKey, issuer, POD_IDENTITY_AUDIENCE);
        } catch (ExpiredTokenException e) {
            return error(400, "The web identity token that was passed is expired\n");
        } catch (InvalidTokenException e) {
            return error(400, "InvalidToken: " + e.getMessage() + "\n");
        } catch (Exception e) {
            return error(400, "Service account token cannot be parsed: " + e.getMessage() + "\n");
        }

        String sub = verified.subject();
        if (sub == null || !sub.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            return error(400, "Service account token subject is invalid\n");
        }
        String rest = sub.substring(SERVICE_ACCOUNT_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return error(400, "Service account token subject is invalid\n");
        }
        String namespace = rest.substring(0, colon);
        String serviceAccount = rest.substring(colon + 1);

        Optional<PodIdentityAssociation> maybeAssociation = associationService.findAssociation(
                cluster, namespace, serviceAccount);
        if (maybeAssociation.isEmpty()) {
            return error(404, "ResourceNotFoundException: No association found for service account: "
                    + serviceAccount + "\n");
        }

        PodIdentityAssociation association = maybeAssociation.get();
        String roleArn = association.roleArn();
        String callerAccountId = cluster.getAccountId();
        String roleAccountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        String accessKeyId = "ASIA" + randomId(16);
        String secretAccessKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plus(Duration.ofHours(1));

        iamService.registerSession(accessKeyId, secretAccessKey, sessionToken, roleArn,
                expiration, association.policy(), callerAccountId);

        EksPodIdentityCredentialsResponse response = new EksPodIdentityCredentialsResponse(
                accessKeyId,
                secretAccessKey,
                sessionToken,
                roleAccountId,
                DateTimeFormatter.ISO_INSTANT.format(expiration)
        );

        return Response.ok(response, MediaType.APPLICATION_JSON).build();
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(message)
                .type(MediaType.TEXT_PLAIN)
                .build();
    }

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(ThreadLocalRandom.current().nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private String randomSecret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(secureRandom.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
