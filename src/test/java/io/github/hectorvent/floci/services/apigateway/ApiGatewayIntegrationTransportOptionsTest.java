package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the two REST integration transport settings AWS documents that Floci previously accepted
 * and ignored: {@code timeoutInMillis} and {@code tlsConfig.insecureSkipVerification}.
 *
 * <p>The TLS case matters because {@code HttpProxyInvoker} uses a default {@code HttpClient}: an
 * HTTPS backend presenting a self-signed certificate was unreachable with no way to opt out, which
 * is the common shape for an internal service behind a private CA.
 */
@QuarkusTest
class ApiGatewayIntegrationTransportOptionsTest {

    private static HttpServer slowServer;
    private static HttpsServer tlsServer;
    private static HttpsServer wrongHostTlsServer;
    private static HttpsServer expiredTlsServer;
    private static HttpsServer privateCaTlsServer;
    private static HttpsServer nonSigningRootTlsServer;
    private static HttpsServer keyUsagelessRootTlsServer;
    private static HttpsServer nameConstrainedRootTlsServer;
    private static HttpsServer nameConstrainedIntermediateTlsServer;
    private static HttpsServer permissiveIntermediateTlsServer;
    private static int slowPort;
    private static int tlsPort;
    private static int wrongHostTlsPort;
    private static int expiredTlsPort;
    private static int privateCaTlsPort;
    private static int nonSigningRootTlsPort;
    private static int keyUsagelessRootTlsPort;
    private static int nameConstrainedRootTlsPort;
    private static int nameConstrainedIntermediateTlsPort;
    private static int permissiveIntermediateTlsPort;

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackends() throws Exception {
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/", ApiGatewayIntegrationTransportOptionsTest::slowHandler);
        slowServer.start();
        slowPort = slowServer.getAddress().getPort();

        tlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tlsServer.setHttpsConfigurator(new HttpsConfigurator(selfSignedContext()));
        tlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        tlsServer.start();
        tlsPort = tlsServer.getAddress().getPort();

        // Same self-signed shape, but the certificate names a host this server is not reachable at,
        // so only hostname verification can reject it.
        wrongHostTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wrongHostTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                selfSignedContextFor("wrong.example", List.of("wrong.example"))));
        wrongHostTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        wrongHostTlsServer.start();
        wrongHostTlsPort = wrongHostTlsServer.getAddress().getPort();

        // Correct hostname, self-signed, but expired two days ago.
        expiredTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        expiredTlsServer.setHttpsConfigurator(new HttpsConfigurator(expiredSelfSignedContext()));
        expiredTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        expiredTlsServer.start();
        expiredTlsPort = expiredTlsServer.getAddress().getPort();

        // A leaf issued by a private root, the shape insecureSkipVerification exists for beyond the
        // single self-signed certificate. Serves the root alongside the leaf, as a real backend does.
        privateCaTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        privateCaTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                privateCaContext(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign, null)));
        privateCaTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        privateCaTlsServer.start();
        privateCaTlsPort = privateCaTlsServer.getAddress().getPort();

        // Same chain, except the root declares cA=true without keyCertSign. AWS requires both
        // together, so this root cannot issue the leaf it just issued.
        nonSigningRootTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nonSigningRootTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                privateCaContext(KeyUsage.digitalSignature | KeyUsage.cRLSign, null)));
        nonSigningRootTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        nonSigningRootTlsServer.start();
        nonSigningRootTlsPort = nonSigningRootTlsServer.getAddress().getPort();

        // Same chain again, except the root carries no keyUsage extension at all. AWS states the
        // extension itself as a requirement on a private root, not just the bit inside it.
        keyUsagelessRootTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keyUsagelessRootTlsServer.setHttpsConfigurator(new HttpsConfigurator(privateCaContext(null, null)));
        keyUsagelessRootTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        keyUsagelessRootTlsServer.start();
        keyUsagelessRootTlsPort = keyUsagelessRootTlsServer.getAddress().getPort();

        // A fully-formed root that additionally carries Name Constraints of its own.
        NameConstraints internalOnly = permittedDnsSubtree("internal.example");
        nameConstrainedRootTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nameConstrainedRootTlsServer.setHttpsConfigurator(new HttpsConfigurator(privateCaContext(
                KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign, internalOnly)));
        nameConstrainedRootTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        nameConstrainedRootTlsServer.start();
        nameConstrainedRootTlsPort = nameConstrainedRootTlsServer.getAddress().getPort();

        // Three certificates, with the constraints on the intermediate where the validator actually
        // reads them: the intermediate permits itself internal.example and issued for localhost.
        nameConstrainedIntermediateTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nameConstrainedIntermediateTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                intermediateCaContext(permittedDnsSubtree("internal.example"))));
        nameConstrainedIntermediateTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        nameConstrainedIntermediateTlsServer.start();
        nameConstrainedIntermediateTlsPort = nameConstrainedIntermediateTlsServer.getAddress().getPort();

        // The same three-certificate shape whose intermediate permits localhost. Without this, the
        // test above would pass for any three-certificate chain rather than because of the
        // constraint it names.
        permissiveIntermediateTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        permissiveIntermediateTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                intermediateCaContext(permittedDnsSubtree("localhost"))));
        permissiveIntermediateTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        permissiveIntermediateTlsServer.start();
        permissiveIntermediateTlsPort = permissiveIntermediateTlsServer.getAddress().getPort();
    }

    @AfterAll
    static void stopBackends() {
        if (slowServer != null) slowServer.stop(0);
        if (tlsServer != null) tlsServer.stop(0);
        if (wrongHostTlsServer != null) wrongHostTlsServer.stop(0);
        if (expiredTlsServer != null) expiredTlsServer.stop(0);
        if (privateCaTlsServer != null) privateCaTlsServer.stop(0);
        if (nonSigningRootTlsServer != null) nonSigningRootTlsServer.stop(0);
        if (keyUsagelessRootTlsServer != null) keyUsagelessRootTlsServer.stop(0);
        if (nameConstrainedRootTlsServer != null) nameConstrainedRootTlsServer.stop(0);
        if (nameConstrainedIntermediateTlsServer != null) nameConstrainedIntermediateTlsServer.stop(0);
        if (permissiveIntermediateTlsServer != null) permissiveIntermediateTlsServer.stop(0);
    }

    /** Sleeps when asked to, so a configured timeout can be observed firing. */
    private static void slowHandler(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("slow=true")) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, 200, "{\"from\":\"backend\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** An SSLContext serving a genuinely self-signed cert no default trust store will accept. */
    private static SSLContext selfSignedContext() throws Exception {
        return selfSignedContextFor("localhost", List.of("localhost", "127.0.0.1"));
    }

    /**
     * A self-signed certificate for localhost whose validity window closed two days ago. Trusting
     * everything accepts it; AWS does not, because expiration is part of the basic validation that
     * survives insecureSkipVerification.
     */
    private static SSLContext expiredSelfSignedContext() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X500Name dn = new X500Name("CN=localhost");
        Instant now = Instant.now();
        X509Certificate certificate = generator.signCertificate(
                dn, keyPair.getPublic(), dn, keyPair.getPrivate(),
                List.of("localhost", "127.0.0.1"), false, CertificateGenerator.LeafUsage.SERVER,
                now.minus(10, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS));
        return contextFor(certificate, keyPair.getPrivate());
    }

    private static SSLContext selfSignedContextFor(String commonName, List<String> sans) throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                commonName, sans, KeyAlgorithm.RSA_2048);

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        generated.certificatePem().getBytes(StandardCharsets.UTF_8)));

        // RSA keys are emitted as PKCS#1 ("RSA PRIVATE KEY"), not PKCS#8, so use the generator's
        // own parser rather than PKCS8EncodedKeySpec.
        PrivateKey privateKey = generator.parsePrivateKey(generated.privateKeyPem());

        return contextFor(certificate, privateKey);
    }

    /**
     * A two-certificate chain: a {@code localhost} leaf issued by a self-signed root built with
     * exactly {@code rootKeyUsageBits} and, optionally, {@code nameConstraints}. Both are what a
     * private certificate authority looks like on the wire, and both are the knobs AWS documents as
     * still mattering under {@code insecureSkipVerification}.
     */
    /** Permitted subtrees naming a single DNS name, the usual private-CA scoping shape. */
    private static NameConstraints permittedDnsSubtree(String dnsName) {
        return new NameConstraints(
                new GeneralSubtree[]{new GeneralSubtree(new GeneralName(GeneralName.dNSName, dnsName))},
                null);
    }

    /**
     * A three-certificate chain, root to intermediate to {@code localhost} leaf, where the
     * intermediate carries {@code nameConstraints}. Constraints belong on the intermediate to be
     * enforced: it sits inside the certification path, where the validator reads extensions, rather
     * than being the trust anchor, whose extensions are never looked at.
     */
    private static SSLContext intermediateCaContext(NameConstraints nameConstraints) throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair rootKeyPair = keys.generateKeyPair();
        KeyPair intermediateKeyPair = keys.generateKeyPair();
        KeyPair leafKeyPair = keys.generateKeyPair();

        Instant now = Instant.now();
        int caKeyUsage = KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign;

        X500Name rootDn = new X500Name("CN=Floci Test Root");
        X509Certificate root = buildCa(rootDn, rootKeyPair.getPublic(), rootDn, rootKeyPair.getPrivate(),
                caKeyUsage, null, now);

        X500Name intermediateDn = new X500Name("CN=Floci Test Intermediate");
        X509Certificate intermediate = buildCa(intermediateDn, intermediateKeyPair.getPublic(),
                rootDn, rootKeyPair.getPrivate(), caKeyUsage, nameConstraints, now);

        X509Certificate leaf = new CertificateGenerator().signCertificate(
                new X500Name("CN=localhost"), leafKeyPair.getPublic(), intermediateDn,
                intermediateKeyPair.getPrivate(), List.of("localhost", "127.0.0.1"), false,
                CertificateGenerator.LeafUsage.SERVER, now.minus(1, ChronoUnit.DAYS),
                now.plus(30, ChronoUnit.DAYS));

        return contextFor(leafKeyPair.getPrivate(), leaf, intermediate, root);
    }

    /** A CA certificate, self-signed when {@code issuerDn} is its own subject. */
    private static X509Certificate buildCa(X500Name subjectDn, java.security.PublicKey subjectKey,
                                           X500Name issuerDn, PrivateKey issuerKey, Integer keyUsageBits,
                                           NameConstraints nameConstraints, Instant now) throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerDn, new BigInteger(128, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(365, ChronoUnit.DAYS)),
                subjectDn, subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        // A null bit set means the extension is left off entirely, which is its own case: AWS
        // requires the extension to be there, not merely to have the right bit when present.
        if (keyUsageBits != null) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));
        }
        if (nameConstraints != null) {
            builder.addExtension(Extension.nameConstraints, true, nameConstraints);
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static SSLContext privateCaContext(Integer rootKeyUsageBits, NameConstraints nameConstraints)
            throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair rootKeyPair = keys.generateKeyPair();
        KeyPair leafKeyPair = keys.generateKeyPair();

        Instant now = Instant.now();
        X500Name rootDn = new X500Name("CN=Floci Test Root");
        X509Certificate root = buildCa(rootDn, rootKeyPair.getPublic(), rootDn,
                rootKeyPair.getPrivate(), rootKeyUsageBits, nameConstraints, now);

        X509Certificate leaf = new CertificateGenerator().signCertificate(
                new X500Name("CN=localhost"), leafKeyPair.getPublic(), rootDn, rootKeyPair.getPrivate(),
                List.of("localhost", "127.0.0.1"), false, CertificateGenerator.LeafUsage.SERVER,
                now.minus(1, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS));

        return contextFor(leafKeyPair.getPrivate(), leaf, root);
    }

    private static SSLContext contextFor(X509Certificate certificate, PrivateKey privateKey) throws Exception {
        return contextFor(privateKey, certificate);
    }

    private static SSLContext contextFor(PrivateKey privateKey, X509Certificate... chain) throws Exception {
        char[] password = "floci-test".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("backend", privateKey, password, (Certificate[]) chain);

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    /** Builds a deployed REST API whose /{proxy+} ANY method is an HTTP_PROXY to {@code targetUri}. */
    private String createApi(String name, String targetUri, String integrationExtras) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"ANY\",\"uri\":\"" + targetUri + "\""
                        + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void configuredTimeoutCutsOffASlowBackend() {
        String apiId = createApi("timeout-short", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":300");

        // Backend sleeps 2s; the 300ms integration timeout must fire and surface as 502.
        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(502);
    }

    @Test
    void aGenerousTimeoutLetsTheSameSlowBackendThrough() {
        String apiId = createApi("timeout-long", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":10000");

        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(200);
    }

    @Test
    void rejectsATimeoutBelowTheAwsMinimum() {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"timeout-invalid\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"GET\","
                        + "\"uri\":\"http://example.internal\",\"timeoutInMillis\":10}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration")
                .then().statusCode(400);
    }

    @Test
    void selfSignedHttpsBackendIsRejectedWithoutTlsConfig() {
        String apiId = createApi("tls-verified", "https://localhost:" + tlsPort + "/{proxy}", "");

        // Default trust store cannot verify the backend's self-signed cert → 502 Bad Gateway.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationReachesTheSelfSignedHttpsBackend() {
        String apiId = createApi("tls-insecure", "https://localhost:" + tlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(200)
                .body("tls", org.hamcrest.Matchers.equalTo("ok"));
    }

    @Test
    void insecureSkipVerificationStillVerifiesTheHostname() {
        String apiId = createApi("tls-insecure-wrong-host",
                "https://localhost:" + wrongHostTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // AWS documents insecureSkipVerification as skipping only the check that the certificate
        // was issued by a supported CA: expiration, hostname and the presence of a root CA are
        // still verified. A certificate issued for wrong.example must therefore fail against a
        // backend addressed as localhost.
        // See https://docs.aws.amazon.com/apigateway/latest/api/API_TlsConfig.html
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationStillRejectsAnExpiredCertificate() {
        String apiId = createApi("tls-insecure-expired",
                "https://localhost:" + expiredTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // Expiration is the other half of the basic validation AWS keeps. A backend whose
        // certificate has lapsed has to fail here too, or it works locally and breaks in AWS.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationReachesABackendBehindAPrivateCa() {
        String apiId = createApi("tls-insecure-private-ca",
                "https://localhost:" + privateCaTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // The case the setting exists for beyond a lone self-signed certificate: a well-formed
        // private root that no public trust store knows. Everything else about the chain is
        // correct, so it has to be accepted.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(200)
                .body("tls", org.hamcrest.Matchers.equalTo("ok"));
    }

    @Test
    void insecureSkipVerificationStillRequiresKeyCertSignOnAPrivateRoot() {
        String apiId = createApi("tls-insecure-root-without-key-cert-sign",
                "https://localhost:" + nonSigningRootTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // AWS states the two requirements on a private root together: the keyUsage extension has
        // to include keyCertSign and basicConstraints has to say CA:TRUE. Checking only the second
        // accepts a root that real API Gateway rejects, which is the direction that hides a broken
        // certificate authority until deployment.
        // See https://docs.aws.amazon.com/apigateway/latest/api/API_TlsConfig.html
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationRejectsAPrivateRootWithNoKeyUsageExtension() {
        String apiId = createApi("tls-insecure-root-without-key-usage",
                "https://localhost:" + keyUsagelessRootTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // AWS states the constraint as "x509 extension keyUsage must have keyCertSign", which a
        // root carrying no keyUsage extension does not satisfy. Generic PKIX would read the absent
        // extension as leaving the key unrestricted, and that looser reading accepts a certificate
        // authority real API Gateway turns away.
        // See https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-extensions-integration-tls-config.html
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationEnforcesNameConstraintsOnAnIntermediate() {
        String apiId = createApi("tls-insecure-name-constrained-intermediate",
                "https://localhost:" + nameConstrainedIntermediateTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // The intermediate permits itself only internal.example and issued for localhost anyway.
        // AWS documents certificate chain validation as including X509v3 Name Constraints
        // enforcement, so an issuer overreaching its own declared scope must not be honoured here.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationReachesABackendWhoseIntermediatePermitsIt() {
        String apiId = createApi("tls-insecure-permissive-intermediate",
                "https://localhost:" + permissiveIntermediateTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // Same three-certificate shape, same code path, with the constraint satisfied. Without this
        // the test above would be satisfied by any rejection of a three-certificate chain rather
        // than by the constraint it names.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(200)
                .body("tls", org.hamcrest.Matchers.equalTo("ok"));
    }

    @Test
    void insecureSkipVerificationRejectsARootCarryingNameConstraints() {
        String apiId = createApi("tls-insecure-name-constrained-root",
                "https://localhost:" + nameConstrainedRootTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // Constraints on the trust anchor itself are a separate case from the one above, and they
        // cannot be evaluated: the platform validator refuses to process constraints supplied with
        // an anchor rather than read from a certificate in the path. Rejecting is the safe
        // direction and the documented one. AWS tells operators hitting validation errors under
        // insecureSkipVerification to check that their CA certificates carry no Name Constraints
        // extension and to reissue them without it, so such a certificate does not work in AWS
        // either.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }
}
