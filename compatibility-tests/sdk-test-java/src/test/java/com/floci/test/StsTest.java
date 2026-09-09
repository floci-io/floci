package com.floci.test;

import org.junit.jupiter.api.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.CreateSamlProviderResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;
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

import static org.assertj.core.api.Assertions.*;

@DisplayName("STS Security Token Service")
class StsTest {

    private static StsClient sts;
    private static IamClient iam;
    private static KeyPair signingKeys;
    private static String providerArn;
    private static String allowedRoleArn;
    private static String allowedRoleName;

    @BeforeAll
    static void setup() throws Exception {
        sts = TestFixtures.stsClient();
        iam = TestFixtures.iamClient();
        signingKeys = createSigningKeys();

        String issuer = "https://sdk-test.example.test/saml";
        String providerName = TestFixtures.uniqueName("sdk-saml");
        String certificate = createCertificate(signingKeys);
        String metadata = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + issuer + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\"><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data><ds:X509Certificate>"
                + certificate + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor></md:IDPSSODescriptor></md:EntityDescriptor>";
        CreateSamlProviderResponse provider = iam.createSAMLProvider(CreateSamlProviderRequest.builder()
                .name(providerName)
                .samlMetadataDocument(metadata)
                .build());
        providerArn = provider.samlProviderArn();

        allowedRoleName = TestFixtures.uniqueName("sdk-saml-role");
        String trustPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                + providerArn + "\"},\"Action\":\"sts:AssumeRoleWithSAML\"}]}";
        allowedRoleArn = iam.createRole(CreateRoleRequest.builder()
                .roleName(allowedRoleName)
                .assumeRolePolicyDocument(trustPolicy)
                .build()).role().arn();
    }

    @AfterAll
    static void cleanup() {
        if (iam != null) {
            iam.close();
        }
        if (sts != null) {
            sts.close();
        }
    }

    @Test
    void getCallerIdentity() {
        GetCallerIdentityResponse response = sts.getCallerIdentity(
                GetCallerIdentityRequest.builder().build());

        assertThat(response.account()).isNotNull();
        assertThat(response.arn()).isNotNull();
        assertThat(response.userId()).isNotNull();
    }

    @Test
    void getCallerIdentityAccountId() {
        GetCallerIdentityResponse response = sts.getCallerIdentity(
                GetCallerIdentityRequest.builder().build());

        assertThat(response.account()).isEqualTo("000000000000");
    }

    @Test
    void assumeRole() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/sdk-test-assumed-role")
                .roleSessionName("sdk-test-session")
                .durationSeconds(3600)
                .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().secretAccessKey()).isNotNull();
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isNotNull();
    }

    @Test
    void assumeRoleReturnsAssumedRoleUserArn() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/my-role")
                .roleSessionName("my-session")
                .build());

        assertThat(response.assumedRoleUser()).isNotNull();
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/my-role/my-session");
    }

    @Test
    void assumeRoleWithCustomDuration() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/short-lived-role")
                .roleSessionName("short-session")
                .durationSeconds(900)
                .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().expiration()).isBefore(Instant.now().plusSeconds(901));
    }

    @Test
    void getSessionToken() {
        GetSessionTokenResponse response = sts.getSessionToken(
                GetSessionTokenRequest.builder().durationSeconds(7200).build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now());
    }

    @Test
    void assumeRoleWithWebIdentity() {
        AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(
                AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/web-identity-role")
                        .roleSessionName("web-session")
                        .webIdentityToken("eyJhbGciOiJSUzI1NiJ9.test-token")
                        .durationSeconds(3600)
                        .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/web-identity-role/web-session");
    }

    @Test
    void getFederationToken() {
        GetFederationTokenResponse response = sts.getFederationToken(
                GetFederationTokenRequest.builder()
                        .name("sdk-test-feduser")
                        .durationSeconds(3600)
                        .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.federatedUser().arn()).contains("federated-user/sdk-test-feduser");
    }

    @Test
    void decodeAuthorizationMessage() {
        DecodeAuthorizationMessageResponse response = sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder()
                        .encodedMessage("test-encoded-message")
                        .build());

        assertThat(response.decodedMessage()).isNotEmpty();
    }

    @Test
    void assumeRoleMissingRoleArnThrows400() {
        assertThatThrownBy(() -> sts.assumeRole(AssumeRoleRequest.builder()
                .roleSessionName("s")
                .build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void assumeRoleWithSaml() {
        AssumeRoleWithSamlResponse response = validAssumeRoleWithSaml();

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().secretAccessKey()).isNotNull();
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now());
    }

    @Test
    void assumeRoleWithSamlAssumedRoleUser() {
        AssumeRoleWithSamlResponse response = validAssumeRoleWithSaml();

        assertThat(response.assumedRoleUser()).isNotNull();
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/" + allowedRoleName + "/");
    }

    @Test
    void assumeRoleWithWebIdentityMissingTokenThrows400() {
        assertThatThrownBy(() -> sts.assumeRoleWithWebIdentity(
                AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .roleSessionName("s")
                        .build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void getFederationTokenFederatedUserIdFormat() {
        GetFederationTokenResponse response = sts.getFederationToken(
                GetFederationTokenRequest.builder()
                        .name("myuser")
                        .build());

        assertThat(response.federatedUser()).isNotNull();
        assertThat(response.federatedUser().federatedUserId()).isEqualTo("000000000000:myuser");
    }

    @Test
    void getFederationTokenMissingNameThrows400() {
        assertThatThrownBy(() -> sts.getFederationToken(
                GetFederationTokenRequest.builder().build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void getSessionTokenDefaultDuration() {
        GetSessionTokenResponse response = sts.getSessionToken(
                GetSessionTokenRequest.builder().build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now().plusSeconds(3600));
    }

    @Test
    void decodeAuthorizationMessageEcho() {
        String msg = "exact-message-to-echo-back";
        DecodeAuthorizationMessageResponse response = sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder()
                        .encodedMessage(msg)
                        .build());

        assertThat(response.decodedMessage()).isEqualTo(msg);
    }

    @Test
    void decodeAuthorizationMessageMissingMessageThrows400() {
        assertThatThrownBy(() -> sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder().build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    private static AssumeRoleWithSamlResponse validAssumeRoleWithSaml() {
        String issuer = "https://sdk-test.example.test/saml";
        return sts.assumeRoleWithSAML(AssumeRoleWithSamlRequest.builder()
                .roleArn(allowedRoleArn)
                .principalArn(providerArn)
                .samlAssertion(signedAssertion(allowedRoleArn, providerArn, issuer))
                .durationSeconds(3600)
                .build());
    }

    private static KeyPair createSigningKeys() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String createCertificate(KeyPair keys) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=Floci SDK SAML test provider");
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)),
                name, keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keys.getPrivate()));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        certificate.verify(keys.getPublic());
        return Base64.getEncoder().encodeToString(certificate.getEncoded());
    }


    private static String signedAssertion(String roleArn, String principalArn, String issuer) {
        String id = "_" + UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);
        String xml = "<saml:Assertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + id
                + "\" Version=\"2.0\" IssueInstant=\"" + Instant.now() + "\"><saml:Issuer>" + issuer
                + "</saml:Issuer><saml:Subject><saml:NameID Format=\"persistent\">sdk-test-subject</saml:NameID>"
                + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData Recipient=\"https://signin.aws.amazon.com/saml\" NotOnOrAfter=\""
                + expiry + "\"/></saml:SubjectConfirmation></saml:Subject><saml:Conditions NotBefore=\""
                + Instant.now().minusSeconds(30) + "\" NotOnOrAfter=\"" + expiry
                + "\"><saml:AudienceRestriction><saml:Audience>urn:amazon:webservices</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
                + "<saml:AttributeStatement><saml:Attribute Name=\"https://aws.amazon.com/SAML/Attributes/Role\"><saml:AttributeValue>"
                + roleArn + "," + principalArn + "</saml:AttributeValue></saml:Attribute></saml:AttributeStatement></saml:Assertion>";
        try {
            Document document = XmlParser.parseDocument(xml);
            Element assertion = document.getDocumentElement();
            assertion.setIdAttribute("ID", true);
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference("#" + id,
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(factory.newTransform(Transform.ENVELOPED,
                                                (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)), null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod("http://www.w3.org/2001/10/xml-exc-c14n#",
                                                (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(reference));
            factory.newXMLSignature(signedInfo, null)
                    .sign(new DOMSignContext(signingKeys.getPrivate(), assertion));
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return Base64.getEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create SAML assertion", e);
        }
    }
}
