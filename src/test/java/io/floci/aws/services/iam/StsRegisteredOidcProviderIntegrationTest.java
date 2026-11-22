package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.services.apigatewayv2.JwtSignatureVerifier;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@code AssumeRoleWithWebIdentity} with a token from an issuer registered through
 * {@code CreateOpenIDConnectProvider}: "The JSON Web Key Set (JWKS) endpoint where IAM gets your
 * public keys" verifies it (IAM User Guide, Create an OpenID Connect (OIDC) identity provider in IAM).
 *
 * <p>The issuer's published keys are the network boundary, so the verifier that fetches them is
 * mocked; {@code JwtSignatureVerifierTest} covers the fetch itself.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StsRegisteredOidcProviderIntegrationTest {

    private static final String KEY_ID = "registered-key";
    private static final String SUBJECT = "repro";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> sessions = new ArrayList<>();

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iamService;

    @InjectMock
    JwtSignatureVerifier issuerKeys;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String issuer;
    private String providerArn;
    private SignedIamUsers.User admin;
    private IamClient iam;
    private StsClient sts;
    private Role role;

    @BeforeEach
    void registerTheIssuer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
        String host = "issuer-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        issuer = "https://" + host;

        admin = SignedIamUsers.createUser(endpoint, "web-registered-", "admin",
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}");
        iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        admin.accessKeyId(), admin.secretAccessKey()))).build();
        sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create()).build();
        providerArn = iam.createOpenIDConnectProvider(b -> b.url(issuer).clientIDList("sts.amazonaws.com")
                .thumbprintList("0000000000000000000000000000000000000000")).openIDConnectProviderArn();
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                + providerArn + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\",\"Condition\":{\"StringEquals\":{\""
                + host + ":sub\":\"" + SUBJECT + "\"}}}]}";
        role = iam.createRole(b -> b.roleName("web-registered-" + UUID.randomUUID().toString().substring(0, 8))
                .assumeRolePolicyDocument(trust)).role();
        when(issuerKeys.signingKey(issuer, KEY_ID)).thenReturn(Optional.of(publicKey));
    }

    @AfterEach
    void removeTheIssuer() throws Exception {
        try {
            for (String session : sessions) {
                iamService.unregisterSession("000000000000", session);
            }
            iam.deleteRole(b -> b.roleName(role.roleName()));
            iam.deleteOpenIDConnectProvider(b -> b.openIDConnectProviderArn(providerArn));
        } finally {
            try {
                sts.close();
                iam.close();
            } finally {
                admin.close();
            }
        }
    }

    @Test
    void tokenFromTheRegisteredIssuerAssumesTheRole() throws Exception {
        AssumeRoleWithWebIdentityResponse response = assume(token(issuer, "sts.amazonaws.com", privateKey));

        assertEquals(SUBJECT, response.subjectFromWebIdentityToken());
        assertEquals(issuer, response.provider());
        assertEquals("sts.amazonaws.com", response.audience());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRefused() throws Exception {
        RSAPrivateKey forged = (RSAPrivateKey) KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();

        StsException error = assertThrows(StsException.class,
                () -> assume(token(issuer, "sts.amazonaws.com", forged)));

        assertEquals("InvalidIdentityToken", error.awsErrorDetails().errorCode());
        assertEquals(400, error.statusCode());
    }

    @Test
    void tokenForAnAudienceTheProviderDoesNotListIsRefused() throws Exception {
        StsException error = assertThrows(StsException.class,
                () -> assume(token(issuer, "another-client", privateKey)));

        assertEquals("InvalidIdentityToken", error.awsErrorDetails().errorCode());
    }

    @Test
    void tokenFromAnIssuerWithoutAProviderIsRefused() throws Exception {
        String unregistered = "https://unregistered-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        when(issuerKeys.signingKey(unregistered, KEY_ID)).thenReturn(Optional.of(publicKey));

        StsException error = assertThrows(StsException.class,
                () -> assume(token(unregistered, "sts.amazonaws.com", privateKey)));

        assertEquals("InvalidIdentityToken", error.awsErrorDetails().errorCode());
        assertEquals("The web identity token issuer is not trusted.", error.awsErrorDetails().errorMessage());
    }

    @Test
    void issuerWhoseKeysCannotBeFetchedAnswersIdpCommunicationError() throws Exception {
        when(issuerKeys.signingKey(issuer, KEY_ID))
                .thenThrow(new JwtSignatureVerifier.JwtVerificationException("connection refused"));

        StsException error = assertThrows(StsException.class,
                () -> assume(token(issuer, "sts.amazonaws.com", privateKey)));

        assertEquals("IDPCommunicationError", error.awsErrorDetails().errorCode());
        assertEquals(400, error.statusCode());
        assertEquals("Couldn't retrieve verification key from your identity provider,  please reference "
                + "AssumeRoleWithWebIdentity documentation for requirements", error.awsErrorDetails().errorMessage());
    }

    private AssumeRoleWithWebIdentityResponse assume(String token) {
        AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(b -> b.roleArn(role.arn())
                .roleSessionName("registered").webIdentityToken(token));
        sessions.add(response.credentials().accessKeyId());
        return response;
    }

    private String token(String tokenIssuer, String audience, RSAPrivateKey signingKey)
            throws GeneralSecurityException {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("iss", tokenIssuer);
        payload.put("sub", SUBJECT);
        payload.put("aud", audience);
        payload.put("exp", System.currentTimeMillis() / 1000 + 3600);
        String signingInput = Base64Url.encode(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + Base64Url.encode(payload.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64Url.encode(signature.sign());
    }
}
