package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.EksPodIdentityCredentialsResponse;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EksPodIdentityCredentialsControllerTest {

    private static final String ISSUER =
            "https://oidc.eks.us-east-1.amazonaws.com/id/A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4";
    private static final String CLUSTER_NAME = "demo-cluster";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String NAMESPACE = "workloads";
    private static final String SERVICE_ACCOUNT = "app-sa";
    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/app-role";

    private EksService eksService;
    private EksOidcService oidcService;
    private EksPodIdentityAssociationService associationService;
    private IamService iamService;
    private WebIdentityTokenVerifier tokenVerifier;
    private EksPodIdentityCredentialsController controller;

    private KeyPair keyPair;
    private Cluster cluster;

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        keyPair = newKeyPair();
        eksService = Mockito.mock(EksService.class);
        oidcService = Mockito.mock(EksOidcService.class);
        associationService = Mockito.mock(EksPodIdentityAssociationService.class);
        iamService = Mockito.mock(IamService.class);
        tokenVerifier = new WebIdentityTokenVerifier(new ObjectMapper());

        controller = new EksPodIdentityCredentialsController(
                eksService, oidcService, associationService, iamService, tokenVerifier
        );

        cluster = new Cluster();
        cluster.setName(CLUSTER_NAME);
        cluster.setAccountId(ACCOUNT_ID);
        cluster.setStatus(ClusterStatus.ACTIVE);

        when(eksService.findClusterByIssuer(ISSUER)).thenReturn(Optional.of(cluster));
        when(oidcService.findVerificationKey(ISSUER)).thenReturn(Optional.of((RSAPublicKey) keyPair.getPublic()));

        PodIdentityAssociation association = new PodIdentityAssociation(
                CLUSTER_NAME,
                NAMESPACE,
                SERVICE_ACCOUNT,
                ROLE_ARN,
                "arn:aws:eks:us-east-1:" + ACCOUNT_ID + ":podidentityassociation/" + CLUSTER_NAME + "/assoc-1",
                "assoc-1",
                null,
                1000.0,
                1000.0,
                null,
                null,
                false,
                null,
                null
        );
        when(associationService.findAssociation(cluster, NAMESPACE, SERVICE_ACCOUNT))
                .thenReturn(Optional.of(association));
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signRs256(PrivateKey key, String claims) throws GeneralSecurityException {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + enc.encodeToString(sig.sign());
    }

    private String buildToken(PrivateKey key, String iss, String aud, String sub, long expOffset) throws GeneralSecurityException {
        long now = Instant.now().getEpochSecond();
        return signRs256(key, String.format(
                "{\"iss\":\"%s\",\"aud\":[\"%s\"],\"sub\":\"%s\",\"iat\":%d,\"nbf\":%d,\"exp\":%d}",
                iss, aud, sub, now - 10, now - 10, now + expOffset));
    }

    private String validToken() throws GeneralSecurityException {
        return buildToken(keyPair.getPrivate(), ISSUER, "pods.eks.amazonaws.com",
                "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT, 3600);
    }

    @Test
    void returnsCredentialsForValidTokenWithBearerPrefix() throws Exception {
        Response response = controller.getCredentials("Bearer " + validToken());
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity() instanceof EksPodIdentityCredentialsResponse);
        EksPodIdentityCredentialsResponse creds = (EksPodIdentityCredentialsResponse) response.getEntity();

        assertNotNull(creds.accessKeyId());
        assertTrue(creds.accessKeyId().startsWith("ASIA"));
        assertNotNull(creds.secretAccessKey());
        assertNotNull(creds.token());
        assertEquals(ACCOUNT_ID, creds.accountId());
        assertNotNull(creds.expiration());

        ArgumentCaptor<String> akidCaptor = ArgumentCaptor.forClass(String.class);
        verify(iamService).registerSession(akidCaptor.capture(), eq(creds.secretAccessKey()),
                eq(creds.token()), eq(ROLE_ARN), any(Instant.class), eq(null), eq(ACCOUNT_ID));
        assertEquals(creds.accessKeyId(), akidCaptor.getValue());
    }

    @Test
    void supportsRawTokenWithoutBearerPrefix() throws Exception {
        Response response = controller.getCredentials(validToken());
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity() instanceof EksPodIdentityCredentialsResponse);
    }

    @Test
    void rejectsMissingOrBlankAuthorizationHeader() {
        for (String auth : new String[]{null, "   ", "Bearer   "}) {
            Response response = controller.getCredentials(auth);
            assertEquals(400, response.getStatus());
            assertEquals("Service account token cannot be empty\n", response.getEntity());
        }
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsMalformedToken() {
        Response response = controller.getCredentials("not.a.valid.jwt");
        assertEquals(400, response.getStatus());
        assertEquals("Service account token cannot be parsed\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsTokenForUnknownCluster() throws Exception {
        String token = buildToken(keyPair.getPrivate(), "https://oidc.eks.us-east-1.amazonaws.com/id/UNKNOWN",
                "pods.eks.amazonaws.com", "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT, 3600);
        Response response = controller.getCredentials(token);
        assertEquals(400, response.getStatus());
        assertEquals("The web identity token issuer is not recognized\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = buildToken(keyPair.getPrivate(), ISSUER, "pods.eks.amazonaws.com",
                "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT, -100);
        Response response = controller.getCredentials(token);
        assertEquals(400, response.getStatus());
        assertEquals("The web identity token that was passed is expired\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        String token = buildToken(newKeyPair().getPrivate(), ISSUER, "pods.eks.amazonaws.com",
                "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT, 3600);
        Response response = controller.getCredentials(token);
        assertEquals(400, response.getStatus());
        assertEquals("InvalidToken: The web identity token signature is invalid\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String token = buildToken(keyPair.getPrivate(), ISSUER, "sts.amazonaws.com",
                "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT, 3600);
        Response response = controller.getCredentials(token);
        assertEquals(400, response.getStatus());
        assertEquals("InvalidToken: The web identity token audience does not include pods.eks.amazonaws.com\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void rejectsInvalidSubject() throws Exception {
        String token = buildToken(keyPair.getPrivate(), ISSUER, "pods.eks.amazonaws.com", "system:node:worker-1", 3600);
        Response response = controller.getCredentials(token);
        assertEquals(400, response.getStatus());
        assertEquals("Service account token subject is invalid\n", response.getEntity());
        verifyNoInteractions(iamService);
    }

    @Test
    void returns404WhenNoAssociationFound() throws Exception {
        String sa = "other-sa";
        String token = buildToken(keyPair.getPrivate(), ISSUER, "pods.eks.amazonaws.com",
                "system:serviceaccount:" + NAMESPACE + ":" + sa, 3600);
        when(associationService.findAssociation(cluster, NAMESPACE, sa)).thenReturn(Optional.empty());
        Response response = controller.getCredentials(token);
        assertEquals(404, response.getStatus());
        assertEquals("ResourceNotFoundException: No association found for service account: " + sa + "\n", response.getEntity());
        verifyNoInteractions(iamService);
    }
}
