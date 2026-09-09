package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.iot.IotService.RegisteredDevice;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What lets a device onto the MQTT TLS listener: its certificate is registered, ACTIVE and within
 * its validity, and the policies attached to it allow {@code iot:Connect} for the client id, with
 * the AWS IoT policy variables and condition keys a connection carries.
 */
class IotServiceDeviceAuthTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = IotServiceTestSupport.ACCOUNT;
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static final String CONNECT_ANY_CLIENT = statement("Allow", "arn:aws:iot:*:*:client/*", null);
    private static final String CONNECT_AS_THING =
            statement("Allow", "arn:aws:iot:*:*:client/${iot:Connection.Thing.ThingName}", null);
    private static final String CONNECT_ATTACHED_THINGS_ONLY = statement("Allow", "arn:aws:iot:*:*:client/*",
            "{\"Bool\":{\"iot:Connection.Thing.IsAttached\":\"true\"}}");

    private static FlociCertificateAuthority ca;

    private IotServiceTestSupport support;
    private IotService service;

    @BeforeAll
    static void certificateAuthority(@TempDir Path tlsDir) {
        ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
    }

    @BeforeEach
    void setUp() {
        support = new IotServiceTestSupport(REGION, ca);
        service = support.service;
    }

    @Test
    void registeredActiveCertificateIsFoundByItsDerFingerprint() {
        IotCertificate created = service.createKeysAndCertificate(true, REGION);

        RegisteredDevice device = service.findRegisteredCertificate(parse(created)).orElseThrow();

        assertEquals(created.getCertificateArn(), device.certificate().getCertificateArn());
        assertEquals(ACCOUNT, device.accountId());
        assertEquals(REGION, device.region());
    }

    @Test
    void unknownCertificateIsNotFoundEvenWhenTheFlociCaSignedIt() {
        service.createKeysAndCertificate(true, REGION);
        X509Certificate stranger = GENERATOR.parseCertificate(ca.issueClientCertificate("stranger").certificatePem());

        assertTrue(service.findRegisteredCertificate(stranger).isEmpty());
    }

    @Test
    void aCertificateRegisteredInAnotherAccountIsFoundWithItsAccount() {
        IotCertificate created = service.createKeysAndCertificate(true, "eu-west-1");
        IotCertificate stored = service.describeCertificate(created.getCertificateId(), "eu-west-1");
        support.certificates.delete("cert:eu-west-1:" + created.getCertificateId());
        support.certificates.putForAccount(OTHER_ACCOUNT, "cert:eu-west-1:" + created.getCertificateId(), stored);

        RegisteredDevice device = service.findRegisteredCertificate(parse(created)).orElseThrow();

        assertEquals(OTHER_ACCOUNT, device.accountId());
        assertEquals("eu-west-1", device.region());
        assertEquals(created.getCertificateId(), device.certificate().getCertificateId());
    }

    @Test
    void plainStoresWithoutAccountPartitionsWorkTheSameWay() {
        IotService plain = new IotServiceTestSupport(REGION, ca, false).service;
        IotCertificate created = plain.createKeysAndCertificate(true, REGION);
        plain.createPolicy("p", CONNECT_ANY_CLIENT, REGION);
        plain.attachPolicy("p", created.getCertificateArn(), REGION);

        RegisteredDevice device = plain.findRegisteredCertificate(parse(created)).orElseThrow();

        assertEquals(ACCOUNT, device.accountId(), "the resolver's default account stands in for the partition");
        assertEquals(REGION, device.region());
        assertTrue(plain.isConnectAllowed(device, "sensor-1", "127.0.0.1", null));
        assertTrue(plain.findRegisteredCertificate(
                GENERATOR.parseCertificate(ca.issueClientCertificate("stranger").certificatePem())).isEmpty());
    }

    /**
     * Policy attachments, policy versions and thing attributes are replaced under a connecting
     * device: every evaluation completes with a decision, never an exception, and the state the
     * writers leave behind is the one the next evaluation sees.
     */
    @Test
    void evaluationsRacingPolicyAndThingChangesAlwaysComplete() throws Exception {
        RegisteredDevice device = registeredDevice(true);
        service.createThing("sensor-1", Map.of("envType", "prod"), REGION);
        service.attachThingPrincipal("sensor-1", device.certificate().getCertificateArn(), REGION);
        createAndAttach("p", statement("Allow", "arn:aws:iot:*:*:client/${iot:Connection.Thing.ThingName}",
                "{\"StringEquals\":{\"iot:Connection.Thing.Attributes[envType]\":\"prod\"}}"), device);
        int rounds = 200;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        service.detachPolicy("p", device.certificate().getCertificateArn(), REGION);
                        service.updateThing("sensor-1", Map.of("envType", round % 2 == 0 ? "test" : "prod"), null, REGION);
                        service.attachPolicy("p", device.certificate().getCertificateArn(), REGION);
                    }
                    return null;
                }));
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        connect(device, "sensor-1");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        service.updateThing("sensor-1", Map.of("envType", "prod"), null, REGION);
        assertTrue(connect(device, "sensor-1"));
        service.detachPolicy("p", device.certificate().getCertificateArn(), REGION);
        assertFalse(connect(device, "sensor-1"));
    }

    /** The location a certificate was found at is remembered, and forgotten again the moment it stops resolving. */
    @Test
    void aCertificateFoundOnceIsFoundAgainAfterItMovesAndReflectsItsCurrentStatus() {
        IotCertificate created = service.createKeysAndCertificate(true, REGION);
        X509Certificate presented = parse(created);
        String key = "cert:" + REGION + ":" + created.getCertificateId();
        assertEquals(ACCOUNT, service.findRegisteredCertificate(presented).orElseThrow().accountId());

        service.updateCertificate(created.getCertificateId(), "INACTIVE", REGION);
        assertEquals("INACTIVE", service.findRegisteredCertificate(presented).orElseThrow().certificate().getStatus());

        IotCertificate stored = service.describeCertificate(created.getCertificateId(), REGION);
        support.certificates.delete(key);
        assertTrue(service.findRegisteredCertificate(presented).isEmpty(), "deleted: no longer found");

        support.certificates.putForAccount(OTHER_ACCOUNT, key, stored);
        assertEquals(OTHER_ACCOUNT, service.findRegisteredCertificate(presented).orElseThrow().accountId());
        assertEquals(OTHER_ACCOUNT, service.findRegisteredCertificate(presented).orElseThrow().accountId());
    }

    @Test
    void activeCertificateWithConnectPolicyIsAllowed() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);

        assertTrue(connect(device, "sensor-1"));
    }

    @Test
    void activeCertificateWithoutPolicyIsDenied() {
        assertFalse(connect(registeredDevice(true), "sensor-1"));
    }

    @Test
    void inactiveCertificateIsDenied() {
        RegisteredDevice device = registeredDevice(false);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);

        assertFalse(connect(device, "sensor-1"));
    }

    @Test
    void revokedCertificateIsDenied() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);
        service.updateCertificate(device.certificate().getCertificateId(), "REVOKED", REGION);

        assertFalse(connect(service.findRegisteredCertificate(parse(device.certificate())).orElseThrow(), "sensor-1"));
    }

    @Test
    void expiredCertificateIsDenied() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);
        device.certificate().setNotAfter(Instant.now().minusSeconds(1));

        assertFalse(connect(device, "sensor-1"));
    }

    @Test
    void notYetValidCertificateIsDenied() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);
        device.certificate().setNotBefore(Instant.now().plusSeconds(3600));

        assertFalse(connect(device, "sensor-1"));
    }

    @Test
    void aBlankClientIdIsDenied() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);

        assertFalse(connect(device, ""));
        assertFalse(connect(device, null));
    }

    @Test
    void detachedPolicyNoLongerAllows() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", CONNECT_ANY_CLIENT, device);
        service.detachPolicy("p", device.certificate().getCertificateArn(), REGION);

        assertFalse(connect(device, "sensor-1"));
    }

    @Test
    void theDefaultPolicyVersionIsTheOneEvaluated() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", statement("Allow", "arn:aws:iot:*:*:client/only-this-one", null), device);
        assertFalse(connect(device, "sensor-1"));

        service.createPolicyVersion("p", CONNECT_ANY_CLIENT, true, REGION);

        assertTrue(connect(device, "sensor-1"));
    }

    @Test
    void aPolicyAttachedInAnotherRegionDoesNotCount() {
        RegisteredDevice device = registeredDevice(true);
        service.createPolicy("p", CONNECT_ANY_CLIENT, "eu-west-1");
        service.attachPolicy("p", device.certificate().getCertificateArn(), "eu-west-1");

        assertFalse(connect(device, "sensor-1"));
    }

    @Test
    void policiesAreReadFromTheCertificatesOwnAccount() {
        IotCertificate created = service.createKeysAndCertificate(true, REGION);
        IotCertificate stored = service.describeCertificate(created.getCertificateId(), REGION);
        support.certificates.delete("cert:" + REGION + ":" + created.getCertificateId());
        support.certificates.putForAccount(OTHER_ACCOUNT, "cert:" + REGION + ":" + created.getCertificateId(), stored);
        RegisteredDevice device = service.findRegisteredCertificate(parse(created)).orElseThrow();
        createAndAttach("p", CONNECT_ANY_CLIENT, device);
        assertFalse(connect(device, "sensor-1"), "a policy attached in the default account is not the device's");

        IotPolicy policy = service.getPolicy("p", REGION);
        support.policies.putForAccount(OTHER_ACCOUNT, "policy:" + REGION + ":p", policy);
        support.policyAttachments.putForAccount(OTHER_ACCOUNT, "policy-attachment:" + REGION + ":p",
                Set.of(created.getCertificateArn()));

        assertTrue(connect(device, "sensor-1"));
    }

    @Test
    void explicitDenyWins() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("allow", CONNECT_ANY_CLIENT, device);
        createAndAttach("deny", statement("Deny", "arn:aws:iot:*:*:client/blocked", null), device);

        assertTrue(connect(device, "sensor-1"));
        assertFalse(connect(device, "blocked"));
    }

    @Test
    void clientIdVariableResolvesToTheConnectingClientId() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", statement("Allow", "arn:aws:iot:" + REGION + ":" + ACCOUNT + ":client/${iot:ClientId}", null),
                device);

        assertTrue(connect(device, "sensor-1"));
    }

    @Test
    void thingNameVariableResolvesOnlyForAThingAttachedToTheCertificate() {
        RegisteredDevice device = registeredDevice(true);
        service.createThing("sensor-1", Map.of(), REGION);
        service.createThing("sensor-2", Map.of(), REGION);
        service.attachThingPrincipal("sensor-1", device.certificate().getCertificateArn(), REGION);
        createAndAttach("p", CONNECT_AS_THING, device);

        assertTrue(connect(device, "sensor-1"));
        assertFalse(connect(device, "sensor-2"), "a thing that exists but is not attached to this certificate");
        assertFalse(connect(device, "sensor-3"), "a client id that names no thing");
    }

    @Test
    void isAttachedConditionAdmitsOnlyAClientIdNamingAnAttachedThing() {
        RegisteredDevice device = registeredDevice(true);
        service.createThing("sensor-1", Map.of(), REGION);
        service.attachThingPrincipal("sensor-1", device.certificate().getCertificateArn(), REGION);
        createAndAttach("p", CONNECT_ATTACHED_THINGS_ONLY, device);

        assertTrue(connect(device, "sensor-1"));
        assertFalse(connect(device, "sensor-9"));
    }

    @Test
    void thingTypeAndAttributesResolveForTheAttachedThing() {
        RegisteredDevice device = registeredDevice(true);
        service.createThingType("gateway", new ObjectMapper().createObjectNode(), REGION);
        service.createThing("gw-prod", Map.of("envType", "prod"), "gateway", REGION);
        service.createThing("gw-test", Map.of("envType", "prod"), "gateway", REGION);
        service.attachThingPrincipal("gw-prod", device.certificate().getCertificateArn(), REGION);
        service.attachThingPrincipal("gw-test", device.certificate().getCertificateArn(), REGION);
        createAndAttach("p", statement("Allow", "arn:aws:iot:*:*:client/*",
                "{\"StringEquals\":{\"iot:Connection.Thing.ThingTypeName\":\"gateway\"},"
                        + "\"StringLike\":{\"iot:ClientId\":\"*${iot:Connection.Thing.Attributes[envType]}\"}}"), device);

        assertTrue(connect(device, "gw-prod"), "the client id ends with the thing's envType attribute");
        assertFalse(connect(device, "gw-test"), "the client id does not end with the attribute value");
    }

    @Test
    void sourceIpConditionIsEvaluatedAgainstTheConnectionAddress() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", statement("Allow", "arn:aws:iot:*:*:client/*",
                "{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}"), device);

        assertTrue(service.isConnectAllowed(device, "sensor-1", "10.1.2.3", null));
        assertFalse(service.isConnectAllowed(device, "sensor-1", "127.0.0.1", null));
        assertFalse(service.isConnectAllowed(device, "sensor-1", null, null), "no address, so the condition cannot hold");
    }

    @Test
    void domainNameConditionIsEvaluatedAgainstTheNameTheClientConnectedTo() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("p", statement("Allow", "arn:aws:iot:*:*:client/*",
                "{\"StringEquals\":{\"iot:DomainName\":\"iot.dev.localhost.floci.io\"}}"), device);

        assertTrue(service.isConnectAllowed(device, "sensor-1", null, "iot.dev.localhost.floci.io"));
        assertFalse(service.isConnectAllowed(device, "sensor-1", null, "other.localhost.floci.io"));
        assertFalse(service.isConnectAllowed(device, "sensor-1", null, null));
    }

    @Test
    void aStatementWithAVariableThatCannotBeResolvedNeverMatches() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("thing", CONNECT_AS_THING, device);
        createAndAttach("cert", statement("Allow", "arn:aws:iot:*:*:client/${iot:Certificate.Subject.CommonName}", null),
                device);

        assertFalse(connect(device, "${iot:Connection.Thing.ThingName}"),
                "an unattached client cannot match the unresolved variable by spelling it out");
        assertFalse(connect(device, "AWS IoT Certificate"), "certificate policy variables are not resolved");
        assertFalse(connect(device, "${iot:Certificate.Subject.CommonName}"));
    }

    @Test
    void aClientIdCarryingJsonSyntaxIsDataNotPolicy() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("allow", CONNECT_ANY_CLIENT, device);
        createAndAttach("deny", statement("Deny", "arn:aws:iot:*:*:client/${iot:ClientId}", null), device);
        assertFalse(connect(device, "sensor-1"), "the deny names every client id");

        assertFalse(connect(device, "x\",\"Effect\":\"Allow\",\"Z\":\""),
                "a client id that would rewrite the statement as text is still just a client id");
    }

    @Test
    void anUnparseablePolicyDocumentDenies() {
        RegisteredDevice device = registeredDevice(true);
        createAndAttach("broken", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"", device);

        assertFalse(connect(device, "sensor-1"));
    }

    private RegisteredDevice registeredDevice(boolean active) {
        IotCertificate created = service.createKeysAndCertificate(active, REGION);
        return service.findRegisteredCertificate(parse(created)).orElseThrow();
    }

    private void createAndAttach(String policyName, String document, RegisteredDevice device) {
        service.createPolicy(policyName, document, REGION);
        service.attachPolicy(policyName, device.certificate().getCertificateArn(), REGION);
    }

    private boolean connect(RegisteredDevice device, String clientId) {
        return service.isConnectAllowed(device, clientId, "127.0.0.1", null);
    }

    private static X509Certificate parse(IotCertificate certificate) {
        return GENERATOR.parseCertificate(certificate.getCertificatePem());
    }

    private static String statement(String effect, String resource, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Action\":\"iot:Connect\","
                + "\"Resource\":\"" + resource + "\"" + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
    }
}
