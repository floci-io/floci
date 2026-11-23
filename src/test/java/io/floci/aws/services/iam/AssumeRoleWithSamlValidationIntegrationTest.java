package io.floci.aws.services.iam;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SamlMetadata;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithSamlResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.crypto.dsig.spec.XPathFilter2ParameterSpec;
import javax.xml.crypto.dsig.spec.XPathFilterParameterSpec;
import javax.xml.crypto.dsig.spec.XPathType;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AssumeRoleWithSamlValidationIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ISSUER = "https://idp.example.test/saml";
    private static final String PROVIDER_NAME = "greptile-saml";
    private static final String PROVIDER = "arn:aws:iam::" + ACCOUNT + ":saml-provider/" + PROVIDER_NAME;
    private static final String AUDIENCE = "urn:amazon:webservices";
    private static final String SAML_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    @TestHTTPResource
    URI endpoint;

    @Inject
    IamService iamService;

    @Inject
    SAMLProviderService samlProviders;

    private static KeyPair signingKeys;
    private static String certificateBase64;
    private static boolean providerRegistered;

    @BeforeAll
    static void createSigningCertificate() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKeys = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=Floci SAML test provider");
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)),
                name, signingKeys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signingKeys.getPrivate()));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        certificate.verify(signingKeys.getPublic());
        certificateBase64 = Base64.getEncoder().encodeToString(certificate.getEncoded());
    }

    @Test
    void malformedAssertionIsRejected() {
        String role = createRole(true);
        assume(role, "not-an-assertion", PROVIDER).statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void unsignedAssertionIsRejected() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, false), PROVIDER)
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void expiredAssertionIsRejected() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().minusSeconds(1), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void wrongAudienceIsRejected() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), "wrong-audience", ISSUER, true), PROVIDER)
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void wrongIssuerIsRejected() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, "https://wrong.example.test", true), PROVIDER)
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void alteredAssertionIsRejected() {
        String role = createRole(true);
        String altered = tamper(assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true),
                "saml-subject", "altered-subject");
        assume(role, altered, PROVIDER).statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void xpathTransformIsRejectedAsInvalidSignature() {
        assertSignatureInvalid(factory -> List.of(enveloped(factory),
                transform(factory, Transform.XPATH, new XPathFilterParameterSpec("true()")),
                transform(factory, CanonicalizationMethod.EXCLUSIVE, null)));
    }

    @Test
    void xpathFilter2TransformIsRejectedAsInvalidSignature() {
        assertSignatureInvalid(factory -> List.of(enveloped(factory),
                transform(factory, Transform.XPATH2,
                        new XPathFilter2ParameterSpec(List.of(new XPathType("/", XPathType.Filter.UNION)))),
                transform(factory, CanonicalizationMethod.EXCLUSIVE, null)));
    }

    @Test
    void inclusiveCanonicalizationTransformIsRejectedAsInvalidSignature() {
        assertSignatureInvalid(factory -> List.of(enveloped(factory),
                transform(factory, CanonicalizationMethod.INCLUSIVE, null)));
    }

    @Test
    void envelopedAndExclusiveCanonicalizationTransformsAreAccepted() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, false,
                factory -> List.of(enveloped(factory), transform(factory, CanonicalizationMethod.EXCLUSIVE, null))),
                PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void envelopedAndExclusiveCanonicalizationWithCommentsTransformsAreAccepted() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, false,
                factory -> List.of(enveloped(factory),
                        transform(factory, CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS, null))),
                PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void wrongRoleAndPrincipalPairIsRejected() {
        String role = createRole(true);
        String otherRole = "arn:aws:iam::" + ACCOUNT + ":role/not-the-requested-role";
        assume(role, assertion(otherRole, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void trustPolicyDenialIsRejected() {
        String role = createRole(false);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void unregisteredProviderIsRejected() {
        String role = "arn:aws:iam::" + ACCOUNT + ":role/unknown-provider-role";
        assume(role, Base64.getEncoder().encodeToString("<Assertion/>".getBytes(StandardCharsets.UTF_8)),
                "arn:aws:iam::" + ACCOUNT + ":saml-provider/not-registered")
                .statusCode(400).body(containsString("InvalidIdentityToken"));
    }

    @Test
    void conditionalTrustPolicyDenyIsNotBypassed() {
        String role = "saml-conditional-deny-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + PROVIDER
                + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"},"
                + "{\"Effect\":\"Deny\",\"Principal\":{\"Federated\":\"" + PROVIDER
                + "\"},\"Action\":\"sts:AssumeRoleWithSAML\",\"Condition\":{\"StringNotEquals\":{\"saml:iss\":\"https://other.example.test\"}}}]}";
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", trust)
                .header("Authorization", auth("iam")).when().post("/").then().statusCode(200);
        ensureProviderRegistered();
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + role;
        assume(roleArn, assertion(roleArn, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * "You can use an Attribute element with the Name attribute set to
     * https://aws.amazon.com/SAML/Attributes/RoleSessionName. This element contains one
     * AttributeValue element that provides an identifier for the temporary credentials that are
     * issued when the role is assumed." It names the session, and it is the {@code sts:RoleSessionName}
     * a trust policy reads, which the Service Authorization Reference lists for
     * {@code sts:AssumeRoleWithSAML}.
     */
    @Test
    void trustPolicyRequiringTheSessionNameAllowsTheAssertionThatCarriesIt() {
        String role = createRoleWithCondition("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice@example.test\"}}");
        assume(role, assertionNamingSession(role, "alice@example.test"), PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.AssumedRoleUser.Arn",
                        equalTo("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + role.substring(role.lastIndexOf('/') + 1)
                                + "/alice@example.test"))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.AssumedRoleUser.AssumedRoleId",
                        endsWith(":alice@example.test"));
    }

    @Test
    void trustPolicyRequiringTheSessionNameRefusesAnotherName() {
        String role = createRoleWithCondition("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice@example.test\"}}");
        assume(role, assertionNamingSession(role, "mallory"), PROVIDER)
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void trustPolicyRequiringTheSessionNameRefusesAnAssertionWithoutOne() {
        String role = createRoleWithCondition("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice@example.test\"}}");
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void theSessionNameAttributeNamesTheSessionWithoutACondition() {
        String role = createRole(true);
        assume(role, assertionNamingSession(role, "bob"), PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.AssumedRoleUser.Arn", endsWith("/bob"));
    }

    /**
     * "Error: RoleSessionName in AuthnResponse must match [a-zA-Z_0-9+=,.@-]{2,64} (service:
     * AWSSecurityTokenService; status code: 400; error code: InvalidIdentityToken)".
     */
    @Test
    void aSessionNameAttributeOutsideItsPatternIsRejected() {
        String role = createRole(true);
        assume(role, assertionNamingSession(role, "has space"), PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidIdentityToken"))
                .body("ErrorResponse.Error.Message",
                        equalTo("RoleSessionName in AuthnResponse must match [a-zA-Z_0-9+=,.@-]{2,64}"));
        assume(role, assertionNamingSession(role, "a".repeat(65)), PROVIDER)
                .statusCode(400).body("ErrorResponse.Error.Code", equalTo("InvalidIdentityToken"));
    }

    @Test
    void duplicateProviderCreationIsRejected() {
        String metadata = SamlMetadata.forEntity(ISSUER, certificateBase64);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateSAMLProvider").formParam("Name", PROVIDER_NAME)
                .formParam("SAMLMetadataDocument", metadata)
                .header("Authorization", auth("iam")).when().post("/").then()
                .statusCode(409).body(containsString("EntityAlreadyExists"));
    }

    @Test
    void validBearerConfirmationIsAcceptedWithUnrelatedConfirmation() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, true), PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void validSignedAssertionPreservesCallerIdentity() {
        String role = createRole(true);
        ValidatableResponse assumed = assume(role,
                assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Issuer", containsString(ISSUER))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Audience", containsString(AUDIENCE))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Subject", containsString("saml-subject"));

        String prefix = "AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.";
        String accessKeyId = assumed.extract().path(prefix + "Credentials.AccessKeyId");
        String sessionToken = assumed.extract().path(prefix + "Credentials.SessionToken");
        String assumedRoleArn = assumed.extract().path(prefix + "AssumedRoleUser.Arn");
        String assumedRoleId = assumed.extract().path(prefix + "AssumedRoleUser.AssumedRoleId");
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20260926/us-east-1/sts/aws4_request")
                .header("X-Amz-Security-Token", sessionToken)
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId", equalTo(assumedRoleId));
    }

    @Test
    void samlDurationAboveRoleMaximumIsRejected() {
        try (DurationFixture fixture = new DurationFixture(3600)) {
            StsException error = assertThrows(StsException.class,
                    () -> fixture.assume(3601, Instant.now().plusSeconds(7200), true));
            assertEquals("ValidationError", error.awsErrorDetails().errorCode());
            assertEquals(400, error.statusCode());
        }
    }

    @Test
    void samlShortAssertionDoesNotBypassRoleMaximum() {
        try (DurationFixture fixture = new DurationFixture(3600)) {
            StsException error = assertThrows(StsException.class,
                    () -> fixture.assume(7200, Instant.now().plusSeconds(300), true));
            assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void samlDurationAtRoleMaximumRetainsRequestedExpiration() {
        try (DurationFixture fixture = new DurationFixture(7200)) {
            Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            AssumeRoleWithSamlResponse result = fixture.assume(7200, before.plusSeconds(10000), true);
            assertTrue(!result.credentials().expiration().isBefore(before.plusSeconds(7200)));
            assertTrue(!result.credentials().expiration().isAfter(Instant.now().plusSeconds(7200)));
        }
    }

    @Test
    void samlValidDurationStillUsesShorterAssertionExpiration() {
        try (DurationFixture fixture = new DurationFixture(7200)) {
            Instant expiry = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
            AssumeRoleWithSamlResponse result = fixture.assume(7200, expiry, true);
            assertEquals(expiry, result.credentials().expiration());
        }
    }

    @Test
    void samlInvalidSignaturePrecedesRoleDurationValidation() {
        try (DurationFixture fixture = new DurationFixture(3600)) {
            StsException error = assertThrows(StsException.class,
                    () -> fixture.assume(7200, Instant.now().plusSeconds(300), false));
            assertEquals("InvalidIdentityToken", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void samlDurationFixtureCleansProviderAfterConstructionFailure() {
        List<String> providers = new ArrayList<>();
        IllegalStateException original = new IllegalStateException("before role creation");
        IllegalStateException caught = assertThrows(IllegalStateException.class,
                () -> new DurationFixture(3600, provider -> {
                    providers.add(provider);
                    throw original;
                }));
        assertSame(original, caught);
        assertEquals(0, caught.getSuppressed().length);
        assertEquals(1, providers.size());
        assertTrue(samlProviders.find(providers.getFirst()).isEmpty());
    }

    @Test
    void samlDurationFixturePreservesConstructionFailureWhenCleanupFails() {
        List<String> providers = new ArrayList<>();
        IllegalStateException original = new IllegalStateException("before role creation");
        IllegalStateException caught = assertThrows(IllegalStateException.class,
                () -> new DurationFixture(3600, provider -> {
                    providers.add(provider);
                    samlProviders.delete(ACCOUNT, provider);
                    throw original;
                }));
        assertSame(original, caught);
        assertEquals(1, caught.getSuppressed().length);
        IamException cleanup = assertInstanceOf(IamException.class, caught.getSuppressed()[0]);
        assertEquals("NoSuchEntity", cleanup.awsErrorDetails().errorCode());
        assertEquals(1, providers.size());
        assertTrue(samlProviders.find(providers.getFirst()).isEmpty());
    }

    private final class DurationFixture implements AutoCloseable {
        private final IamClient client;
        private final StsClient sts;
        private final String name = "saml-duration-" + UUID.randomUUID();
        private final List<String> sessions = new ArrayList<>();
        private String providerArn;
        private String roleArn;

        private DurationFixture(int maximum) {
            this(maximum, provider -> assertTrue(samlProviders.find(provider).isPresent()));
        }

        private DurationFixture(int maximum, Consumer<String> providerCreated) {
            client = AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1, endpoint);
            try {
                sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(AnonymousCredentialsProvider.create()).build();
                String metadata = SamlMetadata.forEntity(ISSUER, certificateBase64);
                providerArn = client.createSAMLProvider(b -> b.name(name).samlMetadataDocument(metadata))
                        .samlProviderArn();
                providerCreated.accept(providerArn);
                String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"Federated\":\"" + providerArn
                        + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"}]}";
                roleArn = client.createRole(b -> b.roleName(name).path("/teams/saml/").assumeRolePolicyDocument(trust)
                        .maxSessionDuration(maximum).tags(tag -> tag.key("team").value("federated"))).role().arn();
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        private AssumeRoleWithSamlResponse assume(int duration, Instant expiry, boolean sign) {
            AssumeRoleWithSamlResponse response = sts.assumeRoleWithSAML(b -> b.roleArn(roleArn)
                    .principalArn(providerArn).durationSeconds(duration)
                    .samlAssertion(assertion(roleArn, providerArn, expiry, AUDIENCE, ISSUER, sign)));
            sessions.add(response.credentials().accessKeyId());
            Role role = client.getRole(b -> b.roleName(name)).role();
            FederatedSessionAssertions.assertIdentity(endpoint, iamService, role, "saml-subject", response);
            return response;
        }

        @Override
        public void close() {
            try {
                for (String key : sessions.reversed()) {
                    iamService.unregisterSession(ACCOUNT, key);
                    assertTrue(iamService.resolveCallerArns(key).isEmpty());
                }
            } finally {
                try {
                    if (roleArn != null) {
                        client.deleteRole(b -> b.roleName(name));
                        assertTrue(iamService.findRole(ACCOUNT, name).isEmpty());
                    }
                } finally {
                    try {
                        if (providerArn != null) {
                            client.deleteSAMLProvider(b -> b.samlProviderArn(providerArn));
                            assertTrue(samlProviders.find(providerArn).isEmpty());
                        }
                    } finally {
                        try {
                            if (sts != null) {
                                sts.close();
                            }
                        } finally {
                            client.close();
                        }
                    }
                }
            }
        }
    }

    /**
     * The session token is as long as the request's {@code MinimumSessionTokenSize} asks for, which
     * this operation models and now answers with the five others that do. It is pinned here rather
     * than beside them because a token is minted only once the assertion verifies, which wants the
     * signing fixture this class already has.
     */
    @Test
    void theSessionTokenIsAsLongAsTheRequestAsksFor() {
        String role = createRole(true);
        String assertion = assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true);

        String token = assumeRequest(role, assertion, PROVIDER)
                .formParam("MinimumSessionTokenSize", "1024")
                .when().post("/").then().statusCode(200)
                .extract().path("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.SessionToken");

        assertEquals(1024, token.length());
    }

    /**
     * And it publishes what that token cost, the three members its response declares: the size in
     * bytes, the percentage of the 4,096-byte limit it consumes, and the deprecated member
     * carrying the same percentage. A tag is not among the state counted here, this operation
     * declaring none: a session tag reaches it inside the assertion.
     */
    @Test
    void theSessionPublishesWhatItsTokenCost() {
        String role = createRole(true);
        String assertion = assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true);

        String result = "AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.";
        assumeRequest(role, assertion, PROVIDER)
                .formParam("MinimumSessionTokenSize", "1024")
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "payments")
                .when().post("/").then().statusCode(200)
                .body(result + "SessionTokenSize", equalTo("1024"))
                .body(result + "SessionTokenUtilization", equalTo("25"))
                .body(result + "PackedPolicySize", equalTo("25"));
    }

    /**
     * The session tags the assertion passes are the session's principal tags: "to pass SAML
     * attributes as session tags, include the Attribute element with the Name attribute set to
     * https://aws.amazon.com/SAML/Attributes/PrincipalTag:{TagKey}", which is the only way this
     * operation takes them, its request declaring no Tags member at all.
     */
    @Test
    void theAssertionsSessionTagsBecomeTheSessionsPrincipalTags() {
        String role = createRoleAllowingTags();
        Map<String, List<String>> tags = Map.of("Project", List.of("Automation"),
                "CostCenter", List.of("12345"));

        String accessKeyId = assumeRequest(role, assertionCarryingTags(role, tags), PROVIDER)
                .when().post("/").then().statusCode(200)
                .extract().path("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId");

        assertEquals(Map.of("Project", "Automation", "CostCenter", "12345"),
                iamService.resolveCallerArns(accessKeyId).orElseThrow().principalTags());
    }

    /**
     * The value is the one the assertion signed, spaces and all: a tag value may carry them, and
     * trimming it would hand a policy a value the provider never asserted.
     */
    @Test
    void aSpacedTagValueIsStoredAsTheAssertionSignedIt() {
        String role = createRoleAllowingTags();

        String accessKeyId = assumeRequest(role, assertionCarryingTags(role, tag("Team", " admin ")), PROVIDER)
                .when().post("/").then().statusCode(200)
                .extract().path("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId");

        assertEquals(Map.of("Team", " admin "),
                iamService.resolveCallerArns(accessKeyId).orElseThrow().principalTags());
    }

    /**
     * A tag attribute carrying several values is a multi-valued session tag, which AWS STS does not
     * support, and one carrying none names a tag with no value: both are refused rather than read
     * as the first value or dropped, so a tag a policy would be evaluated with cannot go missing.
     */
    @Test
    void aTagAttributeWithoutExactlyOneValueIsRefused() {
        String role = createRoleAllowingTags();

        assume(role, assertionCarryingTags(role, Map.of("Teams", List.of("one", "two"))), PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidIdentityToken"))
                .body("ErrorResponse.Error.Message",
                        equalTo(SAMLAssertionVerifier.singleValueRequired("Teams")));
        assume(role, assertionCarryingTags(role, Map.of("Teams", List.of())), PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Message",
                        equalTo(SAMLAssertionVerifier.singleValueRequired("Teams")));
    }

    /** And an attribute whose name ends at the colon names no tag key at all. */
    @Test
    void aTagAttributeNamingNoKeyIsRefused() {
        String role = createRoleAllowingTags();

        assume(role, assertionCarryingTags(role, tag("", "Automation")), PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidIdentityToken"))
                .body("ErrorResponse.Error.Message", equalTo(SAMLAssertionVerifier.TAG_KEY_MISSING));
    }

    /**
     * The assertion's tags are state the session token carries, so they count against the one
     * limit it is held to and a set that will not fit is refused before anything is minted. The
     * request declares no Tags member, so this is measured once the assertion has been verified.
     */
    @Test
    void anAssertionCarryingTooManyTagsIsRefused() {
        String role = createRoleAllowingTags();
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (int i = 1; i <= 20; i++) {
            tags.put("key-" + i, List.of("v".repeat(250)));
        }

        assume(role, assertionCarryingTags(role, tags), PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("PackedPolicyTooLarge"));
    }

    /** A set that does fit is reported as what it cost, the tags counted with the token. */
    @Test
    void theAssertionsTagsCountTowardWhatTheSessionReports() {
        String role = createRoleAllowingTags();
        String value = "v".repeat(250);

        String result = "AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.";
        assumeRequest(role, assertionCarryingTags(role, tag("Project", value)), PROVIDER)
                .when().post("/").then().statusCode(200)
                .body(result + "SessionTokenSize",
                        equalTo(Integer.toString(200 + "Project".length() + value.length())));
    }

    private static void assertSignatureInvalid(Function<XMLSignatureFactory, List<Transform>> transforms) {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, false, transforms),
                PROVIDER)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidIdentityToken"))
                .body("ErrorResponse.Error.Message", equalTo("Response signature invalid"));
    }

    private static Transform enveloped(XMLSignatureFactory factory) {
        return transform(factory, Transform.ENVELOPED, null);
    }

    private static Transform transform(XMLSignatureFactory factory, String algorithm, TransformParameterSpec spec) {
        try {
            return factory.newTransform(algorithm, spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValidatableResponse assume(String role, String assertion, String provider) {
        return assumeRequest(role, assertion, provider).when().post("/").then();
    }

    /** The request every case here posts, for a case that carries a member of its own on top. */
    private static RequestSpecification assumeRequest(String role, String assertion, String provider) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRoleWithSAML")
                .formParam("RoleArn", role)
                .formParam("PrincipalArn", provider)
                .formParam("SAMLAssertion", assertion)
                .header("Authorization", auth("sts"));
    }

    private static String createRole(boolean allow) {
        return createRole("saml-role-", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\""
                + (allow ? "Allow" : "Deny") + "\",\"Principal\":{\"Federated\":\"" + PROVIDER
                + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"}]}");
    }

    /**
     * A role whose trust policy permits tagging the session as well as assuming it, which a tagged
     * assertion needs: "trust policies for all roles connected to the identity provider passing
     * tags must have the sts:TagSession permission."
     */
    private static String createRoleAllowingTags() {
        return createRole("saml-tagged-", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Federated\":\"" + PROVIDER + "\"},"
                + "\"Action\":[\"sts:AssumeRoleWithSAML\",\"sts:TagSession\"]}]}");
    }

    private static String createRoleWithCondition(String condition) {
        return createRole("saml-session-", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Federated\":\"" + PROVIDER + "\"},"
                + "\"Action\":\"sts:AssumeRoleWithSAML\",\"Condition\":" + condition + "}]}");
    }

    /** A role of this class's own, named for what its trust policy says, with the provider registered. */
    private static String createRole(String namePrefix, String trust) {
        String role = namePrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", trust)
                .header("Authorization", auth("iam")).when().post("/").then().statusCode(200);
        ensureProviderRegistered();
        return "arn:aws:iam::" + ACCOUNT + ":role/" + role;
    }

    private static void ensureProviderRegistered() {
        if (!providerRegistered) {
            registerProvider();
            providerRegistered = true;
        }
    }

    private static void registerProvider() {
        String metadata = SamlMetadata.forEntity(ISSUER, certificateBase64);
        given().contentType("application/x-www-form-urlencoded").formParam("Action", "CreateSAMLProvider")
                .formParam("Name", PROVIDER_NAME).formParam("SAMLMetadataDocument", metadata)
                .header("Authorization", auth("iam")).when().post("/").then().statusCode(200);
    }

    private static String tamper(String encoded, String original, String replacement) {
        String xml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(xml.replace(original, replacement).getBytes(StandardCharsets.UTF_8));
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260907/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=test";
    }

    private static String assertion(String role, String provider, Instant expiry, String audience,
                                    String issuer, boolean sign) {
        return assertion(role, provider, expiry, audience, issuer, sign, false);
    }

    private static String assertion(String role, String provider, Instant expiry, String audience,
                                    String issuer, boolean sign, boolean unrelatedConfirmation) {
        return assertion(role, provider, expiry, audience, issuer, sign, unrelatedConfirmation,
                factory -> List.of(enveloped(factory)));
    }

    private static String assertion(String role, String provider, Instant expiry, String audience,
                                    String issuer, boolean sign, boolean unrelatedConfirmation,
                                    Function<XMLSignatureFactory, List<Transform>> transforms) {
        return assertion(role, provider, expiry, audience, issuer, sign, unrelatedConfirmation, transforms, null);
    }

    /** A valid signed assertion for {@code role} that names its session {@code roleSessionName}. */
    private static String assertionNamingSession(String role, String roleSessionName) {
        return assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, false,
                factory -> List.of(enveloped(factory)), roleSessionName);
    }

    /**
     * A valid signed assertion for {@code role} passing {@code tags} as its session tags, each key
     * written as its own attribute and each value in its own AttributeValue element, so a case can
     * pass a tag with no value or with several.
     */
    private static String assertionCarryingTags(String role, Map<String, List<String>> tags) {
        return assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true, false,
                factory -> List.of(enveloped(factory)), null, tags);
    }

    /** One session tag, the shape every case that passes a well-formed one uses. */
    private static Map<String, List<String>> tag(String key, String value) {
        return Map.of(key, List.of(value));
    }

    private static String assertion(String role, String provider, Instant expiry, String audience,
                                    String issuer, boolean sign, boolean unrelatedConfirmation,
                                    Function<XMLSignatureFactory, List<Transform>> transforms,
                                    String roleSessionName) {
        return assertion(role, provider, expiry, audience, issuer, sign, unrelatedConfirmation, transforms,
                roleSessionName, Map.of());
    }

    private static String assertion(String role, String provider, Instant expiry, String audience,
                                    String issuer, boolean sign, boolean unrelatedConfirmation,
                                    Function<XMLSignatureFactory, List<Transform>> transforms,
                                    String roleSessionName, Map<String, List<String>> principalTags) {
        String sessionNameAttribute = roleSessionName == null ? ""
                : "<saml:Attribute Name=\"https://aws.amazon.com/SAML/Attributes/RoleSessionName\"><saml:AttributeValue>"
                        + roleSessionName + "</saml:AttributeValue></saml:Attribute>";
        StringBuilder tagAttributes = new StringBuilder();
        principalTags.forEach((key, values) -> {
            tagAttributes.append("<saml:Attribute Name=\"https://aws.amazon.com/SAML/Attributes/PrincipalTag:")
                    .append(key).append("\">");
            values.forEach(value -> tagAttributes.append("<saml:AttributeValue>").append(value)
                    .append("</saml:AttributeValue>"));
            tagAttributes.append("</saml:Attribute>");
        });
        String id = "_" + UUID.randomUUID();
        String extraConfirmation = unrelatedConfirmation
                ? "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:sender-vouches\"><saml:SubjectConfirmationData Recipient=\"https://unrelated.example.test\" NotOnOrAfter=\"" + Instant.now().minusSeconds(1) + "\"/></saml:SubjectConfirmation>"
                : "";
        String xml = "<saml:Assertion xmlns:saml=\"" + SAML_NS + "\" ID=\"" + id + "\" Version=\"2.0\" IssueInstant=\""
                + Instant.now() + "\"><saml:Issuer>" + issuer + "</saml:Issuer><saml:Subject><saml:NameID Format=\"persistent\">saml-subject</saml:NameID>"
                + extraConfirmation + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData Recipient=\"https://signin.aws.amazon.com/saml\" NotOnOrAfter=\"" + expiry + "\"/></saml:SubjectConfirmation></saml:Subject>"
                + "<saml:Conditions NotBefore=\"" + Instant.now().minusSeconds(30) + "\" NotOnOrAfter=\"" + expiry + "\"><saml:AudienceRestriction><saml:Audience>" + audience + "</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
                + "<saml:AttributeStatement><saml:Attribute Name=\"https://aws.amazon.com/SAML/Attributes/Role\"><saml:AttributeValue>" + role + "," + provider + "</saml:AttributeValue></saml:Attribute>" + sessionNameAttribute
                + tagAttributes + "</saml:AttributeStatement></saml:Assertion>";
        try {
            Document document = XmlParser.parseDocument(xml);
            if (!sign) {
                return encoded(document);
            }
            Element assertion = document.getDocumentElement();
            assertion.setIdAttribute("ID", true);
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference("#" + id,
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    transforms.apply(factory), null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod("http://www.w3.org/2001/10/xml-exc-c14n#", (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(reference));
            factory.newXMLSignature(signedInfo, null)
                    .sign(new DOMSignContext(signingKeys.getPrivate(), assertion));
            return encoded(document);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encoded(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return Base64.getEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
