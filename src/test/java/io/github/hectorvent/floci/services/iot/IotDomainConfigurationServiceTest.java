package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotDomainConfigurationServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";
    private static final String OTHER_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/22222222-2222-2222-2222-222222222222";

    private final ObjectMapper mapper = new ObjectMapper();
    private final IotDomainConfigurationService service = new IotDomainConfigurationService(
            new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    private ObjectNode customDomainRequest() {
        ObjectNode request = mapper.createObjectNode().put("domainName", "iot.example.com");
        request.putArray("serverCertificateArns").add(CERTIFICATE_ARN);
        return request;
    }

    private static AwsException assertAwsError(String code, int status, Runnable call) {
        AwsException failure = assertThrows(AwsException.class, call::run);
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
        return failure;
    }

    @Test
    void createReturnsAnEnabledCustomerManagedConfiguration() {
        IotDomainConfiguration created = service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);

        assertEquals("iot-domain", created.getDomainConfigurationName());
        assertTrue(created.getDomainConfigurationArn().matches(
                "arn:aws:iot:us-east-1:000000000000:domainconfiguration/iot-domain/[a-z0-9]{5}"),
                "AWS appends a short id to the ARN: " + created.getDomainConfigurationArn());
        assertEquals("iot.example.com", created.getDomainName());
        assertEquals("DATA", created.getServiceType());
        assertEquals("ENABLED", created.getDomainConfigurationStatus());
        assertEquals("CUSTOMER_MANAGED", created.getDomainType());
        assertEquals(List.of(new IotDomainConfiguration.ServerCertificateSummary(CERTIFICATE_ARN, "VALID", null)),
                created.getServerCertificates());
        assertEquals(new IotDomainConfiguration.TlsConfig("IoTSecurityPolicy_TLS13_1_2_2022_10"), created.getTlsConfig());
        assertEquals(new IotDomainConfiguration.ServerCertificateConfig(false, null, null), created.getServerCertificateConfig());
        assertNotNull(created.getLastStatusChangeDate());
        assertNull(created.getAuthorizerConfig());
        assertNull(created.getAuthenticationType());
        assertNull(created.getApplicationProtocol());
        assertNull(created.getClientCertificateConfig());
        assertTrue(created.getTags().isEmpty());
        assertEquals(created.getDomainConfigurationArn(),
                service.describeDomainConfiguration("iot-domain", REGION).getDomainConfigurationArn());
    }

    @Test
    void createStoresEveryRequestField() {
        ObjectNode request = customDomainRequest()
                .put("serviceType", "JOBS")
                .put("validationCertificateArn", OTHER_CERTIFICATE_ARN)
                .put("authenticationType", "CUSTOM_AUTH")
                .put("applicationProtocol", "MQTT_WSS");
        request.putObject("authorizerConfig").put("defaultAuthorizerName", "my-authorizer").put("allowAuthorizerOverride", true);
        request.putObject("tlsConfig").put("securityPolicy", "IoTSecurityPolicy_TLS12_1_2_2022_10");
        request.putObject("serverCertificateConfig").put("enableOCSPCheck", true)
                .put("ocspLambdaArn", "arn:aws:lambda:us-east-1:000000000000:function:ocsp")
                .put("ocspAuthorizedResponderArn", OTHER_CERTIFICATE_ARN);
        request.putObject("clientCertificateConfig")
                .put("clientCertificateCallbackArn", "arn:aws:lambda:us-east-1:000000000000:function:callback");
        request.putArray("tags").addObject().put("Key", "env").put("Value", "test");

        IotDomainConfiguration created = service.createDomainConfiguration("iot-domain", request, REGION);

        assertEquals("JOBS", created.getServiceType());
        assertEquals(OTHER_CERTIFICATE_ARN, created.getValidationCertificateArn());
        assertEquals(new IotDomainConfiguration.AuthorizerConfig("my-authorizer", true), created.getAuthorizerConfig());
        assertEquals(new IotDomainConfiguration.TlsConfig("IoTSecurityPolicy_TLS12_1_2_2022_10"), created.getTlsConfig());
        assertEquals(new IotDomainConfiguration.ServerCertificateConfig(true,
                "arn:aws:lambda:us-east-1:000000000000:function:ocsp", OTHER_CERTIFICATE_ARN),
                created.getServerCertificateConfig());
        assertEquals("CUSTOM_AUTH", created.getAuthenticationType());
        assertEquals("MQTT_WSS", created.getApplicationProtocol());
        assertEquals(new IotDomainConfiguration.ClientCertificateConfig(
                "arn:aws:lambda:us-east-1:000000000000:function:callback"), created.getClientCertificateConfig());
        assertEquals(Map.of("env", "test"), created.getTags());
    }

    @Test
    void createWithoutADomainNameIsAnEndpointConfiguration() {
        IotDomainConfiguration created = service.createDomainConfiguration("default-endpoint", mapper.createObjectNode(), REGION);

        assertEquals("ENDPOINT", created.getDomainType());
        assertNull(created.getDomainName());
        assertTrue(created.getServerCertificates().isEmpty());
        assertEquals("ENABLED", created.getDomainConfigurationStatus());
    }

    @Test
    void createTreatsAJsonNullAsAbsent() {
        ObjectNode request = customDomainRequest();
        request.putNull("authorizerConfig");
        request.putNull("validationCertificateArn");
        request.putNull("authenticationType");
        request.putNull("tags");

        IotDomainConfiguration created = service.createDomainConfiguration("iot-domain", request, REGION);

        assertNull(created.getAuthorizerConfig());
        assertNull(created.getValidationCertificateArn());
        assertNull(created.getAuthenticationType());
        assertTrue(created.getTags().isEmpty());
    }

    @Test
    void createStoresATagWithoutAValueAsEmpty() {
        ObjectNode request = customDomainRequest();
        request.putArray("tags").addObject().put("Key", "env");

        assertEquals(Map.of("env", ""), service.createDomainConfiguration("iot-domain", request, REGION).getTags());
    }

    @Test
    void createRejectsAStructuredValueWhereAStringIsExpected() {
        ObjectNode request = customDomainRequest();
        request.putObject("domainName");

        assertAwsError("InvalidRequestException", 400,
                () -> service.createDomainConfiguration("iot-domain", request, REGION));
        assertAwsError("ResourceNotFoundException", 404, () -> service.describeDomainConfiguration("iot-domain", REGION));
    }

    @Test
    void updateRejectsAnAuthorizerConfigThatIsNotAnObjectAndKeepsTheStoredOne() {
        ObjectNode create = customDomainRequest();
        create.putObject("authorizerConfig").put("defaultAuthorizerName", "first");
        service.createDomainConfiguration("iot-domain", create, REGION);

        assertAwsError("InvalidRequestException", 400, () -> service.updateDomainConfiguration("iot-domain",
                mapper.createObjectNode().put("authorizerConfig", "not-an-object"), REGION));
        assertEquals(new IotDomainConfiguration.AuthorizerConfig("first", null),
                service.describeDomainConfiguration("iot-domain", REGION).getAuthorizerConfig());
    }

    @Test
    void createRejectsADuplicateName() {
        service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);

        assertAwsError("ResourceAlreadyExistsException", 409,
                () -> service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION));
    }

    @Test
    void concurrentCreatesOfTheSameNameLetExactlyOneThrough() throws Exception {
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<String>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION)
                                .getDomainConfigurationArn();
                    } catch (AwsException e) {
                        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
                        conflicts.incrementAndGet();
                        return null;
                    }
                }));
            }
            start.countDown();
            List<String> arns = new ArrayList<>();
            for (Future<String> result : results) {
                String arn = result.get();
                if (arn != null) {
                    arns.add(arn);
                }
            }
            assertEquals(1, arns.size(), "exactly one create may succeed");
            assertEquals(callers - 1, conflicts.get());
            assertEquals(arns.get(0), service.describeDomainConfiguration("iot-domain", REGION).getDomainConfigurationArn());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createRejectsTagsThatAreNotAList() {
        ObjectNode request = customDomainRequest();
        request.putObject("tags").put("env", "test");

        assertAwsError("InvalidRequestException", 400,
                () -> service.createDomainConfiguration("iot-domain", request, REGION));
    }

    @Test
    void createRejectsNamesOutsideTheAwsPattern() {
        for (String name : List.of("", "has space", "iot:Data-ATS", "x".repeat(129))) {
            assertAwsError("InvalidRequestException", 400,
                    () -> service.createDomainConfiguration(name, customDomainRequest(), REGION));
        }
        assertAwsError("InvalidRequestException", 400,
                () -> service.createDomainConfiguration(null, customDomainRequest(), REGION));
    }

    @Test
    void createRejectsMoreThanOneServerCertificate() {
        ObjectNode request = customDomainRequest();
        request.withArray("serverCertificateArns").add(OTHER_CERTIFICATE_ARN);

        assertAwsError("InvalidRequestException", 400,
                () -> service.createDomainConfiguration("iot-domain", request, REGION));
    }

    @Test
    void createRejectsACustomDomainWithoutAServerCertificate() {
        ObjectNode request = mapper.createObjectNode().put("domainName", "iot.example.com");

        assertAwsError("InvalidRequestException", 400,
                () -> service.createDomainConfiguration("iot-domain", request, REGION));
    }

    @Test
    void createRejectsValuesOutsideTheAwsEnums() {
        assertAwsError("InvalidRequestException", 400, () -> service.createDomainConfiguration(
                "a", customDomainRequest().put("serviceType", "MQTT"), REGION));
        assertAwsError("InvalidRequestException", 400, () -> service.createDomainConfiguration(
                "b", customDomainRequest().put("authenticationType", "NONE"), REGION));
        assertAwsError("InvalidRequestException", 400, () -> service.createDomainConfiguration(
                "c", customDomainRequest().put("applicationProtocol", "AMQP"), REGION));
        assertAwsError("ResourceNotFoundException", 404, () -> service.describeDomainConfiguration("a", REGION));
    }

    @Test
    void describeUnknownConfigurationIsNotFound() {
        assertAwsError("ResourceNotFoundException", 404,
                () -> service.describeDomainConfiguration("missing", REGION));
    }

    @Test
    void configurationsAreScopedToTheirRegion() {
        service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);

        assertAwsError("ResourceNotFoundException", 404,
                () -> service.describeDomainConfiguration("iot-domain", "eu-west-1"));
        IotDomainConfiguration other = service.createDomainConfiguration("iot-domain", customDomainRequest(), "eu-west-1");
        assertTrue(other.getDomainConfigurationArn().startsWith("arn:aws:iot:eu-west-1:"));
        assertFalse(other.getDomainConfigurationArn().equals(
                service.describeDomainConfiguration("iot-domain", REGION).getDomainConfigurationArn()));
    }

    @Test
    void updateFlipsTheStatusAndRecordsWhenItChanged() throws InterruptedException {
        Instant createdAt = service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION)
                .getLastStatusChangeDate();
        Thread.sleep(10);

        IotDomainConfiguration updated = service.updateDomainConfiguration("iot-domain",
                mapper.createObjectNode().put("domainConfigurationStatus", "DISABLED"), REGION);

        assertEquals("DISABLED", updated.getDomainConfigurationStatus());
        assertTrue(updated.getLastStatusChangeDate().isAfter(createdAt));
        assertEquals("DISABLED", service.describeDomainConfiguration("iot-domain", REGION).getDomainConfigurationStatus());
    }

    @Test
    void updateWithTheSameStatusKeepsLastStatusChangeDate() throws InterruptedException {
        Instant createdAt = service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION)
                .getLastStatusChangeDate();
        Thread.sleep(10);

        IotDomainConfiguration updated = service.updateDomainConfiguration("iot-domain",
                mapper.createObjectNode().put("domainConfigurationStatus", "ENABLED"), REGION);

        assertEquals(createdAt, updated.getLastStatusChangeDate());
    }

    @Test
    void updateRejectsAnUnknownStatus() {
        service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);

        assertAwsError("InvalidRequestException", 400, () -> service.updateDomainConfiguration("iot-domain",
                mapper.createObjectNode().put("domainConfigurationStatus", "PAUSED"), REGION));
    }

    @Test
    void updateReplacesTheAuthorizerAndRemoveAuthorizerConfigClearsIt() {
        ObjectNode create = customDomainRequest();
        create.putObject("authorizerConfig").put("defaultAuthorizerName", "first");
        service.createDomainConfiguration("iot-domain", create, REGION);

        ObjectNode replace = mapper.createObjectNode();
        replace.putObject("authorizerConfig").put("defaultAuthorizerName", "second").put("allowAuthorizerOverride", false);
        assertEquals(new IotDomainConfiguration.AuthorizerConfig("second", false),
                service.updateDomainConfiguration("iot-domain", replace, REGION).getAuthorizerConfig());

        ObjectNode remove = mapper.createObjectNode().put("removeAuthorizerConfig", true);
        assertNull(service.updateDomainConfiguration("iot-domain", remove, REGION).getAuthorizerConfig());
        assertNull(service.describeDomainConfiguration("iot-domain", REGION).getAuthorizerConfig());
    }

    @Test
    void updateAppliesTlsCertificateAndProtocolSettingsAndLeavesTheRestAlone() {
        IotDomainConfiguration created = service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);
        ObjectNode request = mapper.createObjectNode()
                .put("authenticationType", "AWS_SIGV4")
                .put("applicationProtocol", "HTTPS");
        request.putObject("tlsConfig").put("securityPolicy", "IoTSecurityPolicy_TLS12_1_2_2022_10");
        request.putObject("serverCertificateConfig").put("enableOCSPCheck", true);
        request.putObject("clientCertificateConfig")
                .put("clientCertificateCallbackArn", "arn:aws:lambda:us-east-1:000000000000:function:callback");

        IotDomainConfiguration updated = service.updateDomainConfiguration("iot-domain", request, REGION);

        assertEquals(new IotDomainConfiguration.TlsConfig("IoTSecurityPolicy_TLS12_1_2_2022_10"), updated.getTlsConfig());
        assertEquals(new IotDomainConfiguration.ServerCertificateConfig(true, null, null), updated.getServerCertificateConfig());
        assertEquals("AWS_SIGV4", updated.getAuthenticationType());
        assertEquals("HTTPS", updated.getApplicationProtocol());
        assertEquals(new IotDomainConfiguration.ClientCertificateConfig(
                "arn:aws:lambda:us-east-1:000000000000:function:callback"), updated.getClientCertificateConfig());
        assertEquals(created.getDomainConfigurationArn(), updated.getDomainConfigurationArn());
        assertEquals("iot.example.com", updated.getDomainName());
        assertEquals("DATA", updated.getServiceType());
        assertEquals("ENABLED", updated.getDomainConfigurationStatus());
        assertEquals(created.getServerCertificates(), updated.getServerCertificates());
    }

    @Test
    void updateUnknownConfigurationIsNotFound() {
        assertAwsError("ResourceNotFoundException", 404, () -> service.updateDomainConfiguration("missing",
                mapper.createObjectNode().put("domainConfigurationStatus", "DISABLED"), REGION));
    }

    @Test
    void deleteRefusesAnEnabledConfiguration() {
        service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);

        assertAwsError("InvalidRequestException", 400, () -> service.deleteDomainConfiguration("iot-domain", REGION));
        assertEquals("ENABLED", service.describeDomainConfiguration("iot-domain", REGION).getDomainConfigurationStatus());
    }

    @Test
    void deleteRemovesADisabledConfiguration() {
        service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION);
        service.updateDomainConfiguration("iot-domain",
                mapper.createObjectNode().put("domainConfigurationStatus", "DISABLED"), REGION);

        service.deleteDomainConfiguration("iot-domain", REGION);

        assertAwsError("ResourceNotFoundException", 404, () -> service.describeDomainConfiguration("iot-domain", REGION));
        assertAwsError("ResourceNotFoundException", 404, () -> service.deleteDomainConfiguration("iot-domain", REGION));
    }

    @Test
    void listReturnsConfigurationsSortedByNameAndFiltersByServiceType() {
        service.createDomainConfiguration("zulu", customDomainRequest(), REGION);
        service.createDomainConfiguration("alpha", customDomainRequest().put("serviceType", "JOBS"), REGION);
        service.createDomainConfiguration("mike", customDomainRequest(), "eu-west-1");

        IotService.Page<IotDomainConfiguration> all = service.listDomainConfigurations(REGION, null, null, null);
        assertEquals(List.of("alpha", "zulu"),
                all.items().stream().map(IotDomainConfiguration::getDomainConfigurationName).toList());
        assertNull(all.nextToken());

        IotService.Page<IotDomainConfiguration> jobs = service.listDomainConfigurations(REGION, "JOBS", null, null);
        assertEquals(List.of("alpha"),
                jobs.items().stream().map(IotDomainConfiguration::getDomainConfigurationName).toList());
    }

    @Test
    void listPagesWithMarkerAndPageSize() {
        for (String name : List.of("a", "b", "c")) {
            service.createDomainConfiguration(name, customDomainRequest(), REGION);
        }

        IotService.Page<IotDomainConfiguration> first = service.listDomainConfigurations(REGION, null, null, 2);
        assertEquals(List.of("a", "b"),
                first.items().stream().map(IotDomainConfiguration::getDomainConfigurationName).toList());
        assertNotNull(first.nextToken());

        IotService.Page<IotDomainConfiguration> second =
                service.listDomainConfigurations(REGION, null, first.nextToken(), 2);
        assertEquals(List.of("c"),
                second.items().stream().map(IotDomainConfiguration::getDomainConfigurationName).toList());
        assertNull(second.nextToken());
    }

    @Test
    void listRejectsInvalidPagingAndFilterValues() {
        assertAwsError("InvalidRequestException", 400, () -> service.listDomainConfigurations(REGION, null, null, 0));
        assertAwsError("InvalidRequestException", 400, () -> service.listDomainConfigurations(REGION, null, null, 251));
        assertAwsError("InvalidRequestException", 400, () -> service.listDomainConfigurations(REGION, null, "not-a-marker", null));
        assertAwsError("InvalidRequestException", 400, () -> service.listDomainConfigurations(REGION, "MQTT", null, null));
    }

    @Test
    void tagsCanBeListedAddedAndRemovedByArn() {
        ObjectNode request = customDomainRequest();
        request.putArray("tags").addObject().put("Key", "env").put("Value", "test");
        String arn = service.createDomainConfiguration("iot-domain", request, REGION).getDomainConfigurationArn();

        assertEquals(Map.of("env", "test"), service.listTagsForResource(arn));

        service.tagResource(arn, Map.of("owner", "iot", "env", "prod"));
        assertEquals(Map.of("env", "prod", "owner", "iot"), service.listTagsForResource(arn));

        service.untagResource(arn, List.of("env", "absent"));
        assertEquals(Map.of("owner", "iot"), service.listTagsForResource(arn));
        assertEquals(Map.of("owner", "iot"), service.describeDomainConfiguration("iot-domain", REGION).getTags());
    }

    @Test
    void tagOperationsRejectArnsThatDoNotNameAStoredConfiguration() {
        String arn = service.createDomainConfiguration("iot-domain", customDomainRequest(), REGION).getDomainConfigurationArn();

        assertAwsError("InvalidRequestException", 400,
                () -> service.listTagsForResource("arn:aws:iot:us-east-1:000000000000:thing/some-thing"));
        assertAwsError("InvalidRequestException", 400, () -> service.listTagsForResource("not-an-arn"));
        assertAwsError("ResourceNotFoundException", 404,
                () -> service.listTagsForResource("arn:aws:iot:us-east-1:000000000000:domainconfiguration/missing/abcde"));
        assertAwsError("ResourceNotFoundException", 404,
                () -> service.tagResource(arn.replaceAll("/[a-z0-9]{5}$", "/zzzzz"), Map.of("k", "v")));
        assertAwsError("ResourceNotFoundException", 404,
                () -> service.untagResource("arn:aws:iot:eu-west-1:000000000000:domainconfiguration/iot-domain/abcde", List.of("k")));
    }
}
