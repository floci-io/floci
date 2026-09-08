package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
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

import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AssumeRoleWithSamlValidationIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ISSUER = "https://idp.example.test/saml";
    private static final String PROVIDER_NAME = "greptile-saml";
    private static final String PROVIDER = "arn:aws:iam::" + ACCOUNT + ":saml-provider/" + PROVIDER_NAME;
    private static final String AUDIENCE = "urn:amazon:webservices";
    private static final String SAML_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
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

    @Test
    void duplicateProviderCreationIsRejected() {
        String metadata = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + ISSUER + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\"><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data><ds:X509Certificate>"
                + certificateBase64 + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor></md:IDPSSODescriptor></md:EntityDescriptor>";
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
    void validSignedAssertionReturnsCredentialsAndSamlFields() {
        String role = createRole(true);
        assume(role, assertion(role, PROVIDER, Instant.now().plusSeconds(300), AUDIENCE, ISSUER, true), PROVIDER)
                .statusCode(200)
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Issuer", containsString(ISSUER))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Audience", containsString(AUDIENCE))
                .body("AssumeRoleWithSAMLResponse.AssumeRoleWithSAMLResult.Subject", containsString("saml-subject"));
    }

    private static io.restassured.response.ValidatableResponse assume(String role, String assertion, String provider) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRoleWithSAML")
                .formParam("RoleArn", role)
                .formParam("PrincipalArn", provider)
                .formParam("SAMLAssertion", assertion)
                .header("Authorization", auth("sts"))
                .when().post("/").then();
    }

    private static String createRole(boolean allow) {
        String role = "saml-role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String trust = allow
                ? "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + PROVIDER + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"}]}"
                : "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"Federated\":\"" + PROVIDER + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"}]}";
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
        String metadata = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\"" + ISSUER
                + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\"><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data><ds:X509Certificate>"
                + certificateBase64 + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor></md:IDPSSODescriptor></md:EntityDescriptor>";
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
        String id = "_" + UUID.randomUUID();
        String extraConfirmation = unrelatedConfirmation
                ? "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:sender-vouches\"><saml:SubjectConfirmationData Recipient=\"https://unrelated.example.test\" NotOnOrAfter=\"" + Instant.now().minusSeconds(1) + "\"/></saml:SubjectConfirmation>"
                : "";
        String xml = "<saml:Assertion xmlns:saml=\"" + SAML_NS + "\" ID=\"" + id + "\" Version=\"2.0\" IssueInstant=\""
                + Instant.now() + "\"><saml:Issuer>" + issuer + "</saml:Issuer><saml:Subject><saml:NameID Format=\"persistent\">saml-subject</saml:NameID>"
                + extraConfirmation + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData Recipient=\"https://signin.aws.amazon.com/saml\" NotOnOrAfter=\"" + expiry + "\"/></saml:SubjectConfirmation></saml:Subject>"
                + "<saml:Conditions NotBefore=\"" + Instant.now().minusSeconds(30) + "\" NotOnOrAfter=\"" + expiry + "\"><saml:AudienceRestriction><saml:Audience>" + audience + "</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
                + "<saml:AttributeStatement><saml:Attribute Name=\"https://aws.amazon.com/SAML/Attributes/Role\"><saml:AttributeValue>" + role + "," + provider + "</saml:AttributeValue></saml:Attribute></saml:AttributeStatement></saml:Assertion>";
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
                    List.of(factory.newTransform(Transform.ENVELOPED, (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)), null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod("http://www.w3.org/2001/10/xml-exc-c14n#", (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(reference));
            factory.newXMLSignature(signedInfo, null)
                    .sign(new DOMSignContext(signingKeys.getPrivate(), assertion));
            return encoded(document);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encoded(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return Base64.getEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
