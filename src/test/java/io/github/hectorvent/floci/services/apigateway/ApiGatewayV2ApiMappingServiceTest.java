package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService.StoredMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The v2 custom domain and API mapping rules, over real services and in-memory stores: the id a
 * mapping is given and keeps, the APIs it can point at, and the restrictions AWS documents for API
 * mappings (a regional domain, the TLS 1.2 security policy for an HTTP API or a key with more than
 * one level, no WebSocket API beside an HTTP or REST API, the characters and length of a key).
 */
class ApiGatewayV2ApiMappingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "api.example.com";
    private static final String OTHER_DOMAIN = "other.example.com";
    private static final String CERTIFICATE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";
    private static final String EDGE_MESSAGE = "Only REGIONAL domain names can be managed through the API Gateway V2 "
            + "API. For EDGE domain names, please use the API Gateway V1 API. Also note that only REST APIs can be "
            + "attached to EDGE domain names.";

    private static final String V2_EDGE_ENDPOINT_MESSAGE = "EDGE endpoint type is not supported for APIGatewayV2 "
            + "domainName";

    private ApiGatewayService service;
    private ApiGatewayV2Service v2Service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().domainSuffix()).thenReturn(Optional.empty());
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        v2Service = new ApiGatewayV2Service(storageFactory, config, regionResolver);
        service = new ApiGatewayService(storageFactory, config, mock(TlsCertificateManager.class), regionResolver,
                v2Service);
    }

    private void v2Domain(String name) {
        service.createV2DomainName(REGION, Map.of("domainName", name,
                "domainNameConfigurations", List.of(Map.of("certificateArn", CERTIFICATE_ARN, "endpointType", "REGIONAL"))));
    }

    /** A domain as the v1 API creates it, which can carry what the v2 API cannot. */
    private void v1Domain(String name, String endpointType, String securityPolicy) {
        Map<String, Object> request = new HashMap<>();
        request.put("domainName", name);
        request.put("regionalCertificateArn", CERTIFICATE_ARN);
        request.put("certificateArn", CERTIFICATE_ARN);
        request.put("endpointConfiguration", Map.of("types", List.of(endpointType)));
        request.put("securityPolicy", securityPolicy);
        service.createDomainName(REGION, request);
    }

    private String api(String protocolType, String stage) {
        Map<String, Object> request = new HashMap<>();
        request.put("name", protocolType.toLowerCase() + "-api");
        request.put("protocolType", protocolType);
        if ("WEBSOCKET".equals(protocolType)) {
            request.put("routeSelectionExpression", "$request.body.action");
        }
        String apiId = v2Service.createApi(REGION, request).getApiId();
        v2Service.createStage(REGION, apiId, Map.of("stageName", stage));
        return apiId;
    }

    private String restApi(String stage) {
        String apiId = service.createRestApi(REGION, Map.of("name", "rest-api")).getId();
        service.createDeployment(REGION, apiId, Map.of("stageName", stage));
        return apiId;
    }

    private static void assertBadRequest(String message, Runnable call) {
        AwsException failure = assertThrows(AwsException.class, call::run);
        assertEquals("BadRequestException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        if (message != null) {
            assertEquals(message, failure.getMessage());
        }
    }

    @Test
    void aMappingKeepsItsIdWhenItsKeyChanges() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");
        StoredMapping created = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default");

        StoredMapping updated = service.updateApiMapping(REGION, DOMAIN, created.apiMappingId(), "v2", apiId, "$default");

        assertEquals(created.apiMappingId(), updated.apiMappingId());
        assertEquals("v2", updated.storedPath());
        assertEquals("v2", service.getApiMapping(REGION, DOMAIN, created.apiMappingId()).storedPath());
        assertEquals(1, service.getApiMappings(REGION, DOMAIN).size());
    }

    @Test
    void aKeyAnotherMappingMovedOffGetsAnIdOfItsOwn() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");
        String first = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default").apiMappingId();
        service.updateApiMapping(REGION, DOMAIN, first, "v2", apiId, "$default");

        String second = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default").apiMappingId();

        assertNotEquals(first, second);
        assertEquals("v2", service.getApiMapping(REGION, DOMAIN, first).storedPath());
        assertEquals("v1", service.getApiMapping(REGION, DOMAIN, second).storedPath());
    }

    @Test
    void anExcludedIdIsNotHandedOut() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");
        String derived = ApiGatewayService.apiMappingId("v1");

        String id = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default", Set.of(derived)).apiMappingId();

        assertNotEquals(derived, id);
    }

    @Test
    void aKeyChangeOntoATakenKeyConflicts() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");
        String first = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default").apiMappingId();
        service.createApiMapping(REGION, DOMAIN, "v2", apiId, "$default");

        AwsException failure = assertThrows(AwsException.class,
                () -> service.updateApiMapping(REGION, DOMAIN, first, "v2", apiId, "$default"));

        assertEquals("ConflictException", failure.getErrorCode());
        assertEquals("v1", service.getApiMapping(REGION, DOMAIN, first).storedPath());
    }

    @Test
    void aRestApiCanBeMappedUnderAKeyWithSeveralLevels() {
        v2Domain(DOMAIN);
        String apiId = restApi("prod");

        StoredMapping created = service.createApiMapping(REGION, DOMAIN, "orders/v1/items", apiId, "prod");

        assertEquals("REST", created.mapping().getApiType());
        assertEquals(apiId, service.resolveBasePathMapping(DOMAIN, "/orders/v1/items/123").getRestApiId());
    }

    @Test
    void anApiOrStageThatDoesNotExistIsRefused() {
        v2Domain(DOMAIN);
        String httpApi = api("HTTP", "$default");
        String restApi = restApi("prod");

        assertBadRequest("Invalid API identifier specified: nope",
                () -> service.createApiMapping(REGION, DOMAIN, "a", "nope", "$default"));
        assertBadRequest("Invalid stage identifier specified",
                () -> service.createApiMapping(REGION, DOMAIN, "b", httpApi, "missing"));
        assertBadRequest("Invalid stage identifier specified",
                () -> service.createApiMapping(REGION, DOMAIN, "c", restApi, "missing"));
    }

    @Test
    void anEdgeDomainCannotBeManagedThroughTheV2Api() {
        v1Domain(DOMAIN, "EDGE", "TLS_1_2");
        String restApi = restApi("prod");

        assertBadRequest(EDGE_MESSAGE, () -> service.createApiMapping(REGION, DOMAIN, "v1", restApi, "prod"));
        assertBadRequest(EDGE_MESSAGE, () -> service.getApiMappings(REGION, DOMAIN));
        assertBadRequest(V2_EDGE_ENDPOINT_MESSAGE, () -> service.createV2DomainName(REGION, Map.of("domainName",
                OTHER_DOMAIN, "domainNameConfigurations", List.of(Map.of("endpointType", "EDGE")))));
    }

    /**
     * Measured against API Gateway (eu-west-1, 2026-10-04): an HTTP API custom domain is regional.
     * A create or update with an endpoint type of PRIVATE or one API Gateway does not know gives a
     * REGIONAL domain; EDGE is refused.
     */
    @Test
    void aV2DomainIsRegionalWhateverOtherEndpointTypeItIsGiven() {
        service.createV2DomainName(REGION, Map.of("domainName", DOMAIN, "domainNameConfigurations",
                List.of(Map.of("certificateArn", CERTIFICATE_ARN, "endpointType", "PRIVATE"))));
        assertEquals("REGIONAL", service.getDomainName(REGION, DOMAIN).getEndpointConfigurationType());

        service.replaceV2DomainConfiguration(REGION, DOMAIN, Map.of("domainNameConfigurations",
                List.of(Map.of("certificateArn", CERTIFICATE_ARN, "endpointType", "FOO"))));
        assertEquals("REGIONAL", service.getDomainName(REGION, DOMAIN).getEndpointConfigurationType());

        assertBadRequest(V2_EDGE_ENDPOINT_MESSAGE, () -> service.replaceV2DomainConfiguration(REGION, DOMAIN,
                Map.of("domainNameConfigurations",
                        List.of(Map.of("certificateArn", CERTIFICATE_ARN, "endpointType", "EDGE")))));
        assertEquals("REGIONAL", service.getDomainName(REGION, DOMAIN).getEndpointConfigurationType());
    }

    /**
     * UpdateDomainName through the v2 API is a v2 call like the others: refused on an
     * edge-optimized domain, and refused for what Floci does not emulate even without a
     * configuration to replace.
     */
    @Test
    void aV2UpdateRefusesAnEdgeDomainAndWhatIsNotEmulated() {
        v1Domain(DOMAIN, "EDGE", "TLS_1_2");
        assertBadRequest(EDGE_MESSAGE, () -> service.updateV2DomainName(REGION, DOMAIN, Map.of(
                "domainNameConfigurations",
                List.of(Map.of("certificateArn", CERTIFICATE_ARN, "endpointType", "REGIONAL")))));
        assertEquals("EDGE", service.getDomainName(REGION, DOMAIN).getEndpointConfigurationType());

        v2Domain(OTHER_DOMAIN);
        assertBadRequest("Mutual TLS authentication is not supported", () -> service.updateV2DomainName(REGION,
                OTHER_DOMAIN, Map.of("mutualTlsAuthentication", Map.of("truststoreUri", "s3://bucket/key"))));
        assertEquals(OTHER_DOMAIN, service.updateV2DomainName(REGION, OTHER_DOMAIN, Map.of()).getDomainName());
    }

    /** Measured against API Gateway (eu-west-1, 2026-10-04), its messages included. */
    @Test
    void anApiMappingKeyIsCheckedAsApiGatewayChecksIt() {
        v2Domain(DOMAIN);
        String httpApi = api("HTTP", "prod");
        String endSlash = "API mapping key should not end with a '/'.";
        String slashes = "API mapping key should not start with a '/' or have consecutive '/'s.";
        String characters = "An API mapping key may contain only letters, numbers and one of $-_.+!*'(), characters.";

        assertBadRequest(endSlash, () -> service.createApiMapping(REGION, DOMAIN, "/", httpApi, "prod"));
        assertBadRequest(endSlash, () -> service.createApiMapping(REGION, DOMAIN, "a/", httpApi, "prod"));
        assertBadRequest(slashes, () -> service.createApiMapping(REGION, DOMAIN, "/a", httpApi, "prod"));
        assertBadRequest(slashes, () -> service.createApiMapping(REGION, DOMAIN, "a//b", httpApi, "prod"));
        assertBadRequest(characters, () -> service.createApiMapping(REGION, DOMAIN, " a", httpApi, "prod"));
        assertBadRequest(characters, () -> service.createApiMapping(REGION, DOMAIN, "a~b", httpApi, "prod"));
        assertBadRequest("ApiMapping key length must be under 300 characters.",
                () -> service.createApiMapping(REGION, DOMAIN, "a".repeat(301), httpApi, "prod"));

        assertEquals("a,b$", service.createApiMapping(REGION, DOMAIN, "a,b$", httpApi, "prod").storedPath());
        assertEquals("a".repeat(300),
                service.createApiMapping(REGION, DOMAIN, "a".repeat(300), httpApi, "prod").storedPath());
        // A key of whitespace only is the root on a create, and refused on an update.
        assertEquals("(none)", ApiGatewayService.canonicalBasePath(
                service.createApiMapping(REGION, DOMAIN, "   ", httpApi, "prod").storedPath()));
        String id = service.createApiMapping(REGION, DOMAIN, "v1", httpApi, "prod").apiMappingId();
        assertBadRequest(slashes, () -> service.updateApiMapping(REGION, DOMAIN, id, "  ", httpApi, "prod"));
        assertBadRequest(endSlash, () -> service.updateApiMapping(REGION, DOMAIN, id, "/", httpApi, "prod"));
    }

    @Test
    void anHttpApiNeedsTheTls12SecurityPolicy() {
        v1Domain(DOMAIN, "REGIONAL", "TLS_1_0");
        String httpApi = api("HTTP", "$default");
        String restApi = restApi("prod");

        assertBadRequest(null, () -> service.createApiMapping(REGION, DOMAIN, "http", httpApi, "$default"));
        assertBadRequest(null, () -> service.createApiMapping(REGION, DOMAIN, "orders/v1", restApi, "prod"));
        assertEquals("REST", service.createApiMapping(REGION, DOMAIN, "orders", restApi, "prod").mapping().getApiType());
    }

    @Test
    void aDomainWithAnHttpApiMappingStaysOnTls12() {
        v2Domain(DOMAIN);
        String httpApi = api("HTTP", "$default");
        service.createApiMapping(REGION, DOMAIN, null, httpApi, "$default");

        assertBadRequest(null, () -> service.replaceV2DomainConfiguration(REGION, DOMAIN, Map.of("domainName", DOMAIN,
                "domainNameConfigurations", List.of(Map.of("certificateArn", CERTIFICATE_ARN,
                        "endpointType", "REGIONAL", "securityPolicy", "TLS_1_0")))));
        assertBadRequest(null, () -> service.updateDomainName(REGION, DOMAIN,
                List.of(Map.of("op", "replace", "path", "/securityPolicy", "value", "TLS_1_0"))));
        assertEquals("TLS_1_2", service.getDomainName(REGION, DOMAIN).getSecurityPolicy());
    }

    @Test
    void aWebSocketApiDoesNotShareADomainWithAnHttpOrRestApi() {
        v2Domain(DOMAIN);
        v2Domain(OTHER_DOMAIN);
        String webSocketApi = api("WEBSOCKET", "prod");
        String httpApi = api("HTTP", "$default");
        String restApi = restApi("prod");
        service.createApiMapping(REGION, DOMAIN, "ws", webSocketApi, "prod");
        service.createApiMapping(REGION, OTHER_DOMAIN, "http", httpApi, "$default");

        assertBadRequest(null, () -> service.createApiMapping(REGION, DOMAIN, "http", httpApi, "$default"));
        assertBadRequest(null, () -> service.createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "rest", "restApiId", restApi, "stage", "prod")));
        assertBadRequest(null, () -> service.createApiMapping(REGION, OTHER_DOMAIN, "ws", webSocketApi, "prod"));
        service.createApiMapping(REGION, DOMAIN, "ws2", webSocketApi, "prod");
    }

    @Test
    void aKeyHoldsOnlyTheCharactersAwsAllowsUpTo300OfThem() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");

        assertBadRequest(null, () -> service.createApiMapping(REGION, DOMAIN, "v1?x", apiId, "$default"));
        assertBadRequest(null, () -> service.createApiMapping(REGION, DOMAIN, "a".repeat(301), apiId, "$default"));
        service.createApiMapping(REGION, DOMAIN, "a".repeat(300), apiId, "$default");
        service.createApiMapping(REGION, DOMAIN, "$-_.+!*'()/x", apiId, "$default");
    }

    @Test
    void updatingAMappingOntoAnotherKindOfApiRoutesItAsThatKind() {
        v2Domain(DOMAIN);
        String httpApi = api("HTTP", "$default");
        String restApi = restApi("prod");
        String id = service.createApiMapping(REGION, DOMAIN, "v1", httpApi, "$default").apiMappingId();

        StoredMapping updated = service.updateApiMapping(REGION, DOMAIN, id, "v1", restApi, "prod");

        assertEquals("REST", updated.mapping().getApiType());
        assertEquals(restApi, service.getApiMapping(REGION, DOMAIN, id).mapping().getRestApiId());
    }

    @Test
    void replacingADomainConfigurationReplacesItAsAWhole() {
        service.createV2DomainName(REGION, Map.of("domainName", DOMAIN, "domainNameConfigurations",
                List.of(Map.of("certificateArn", CERTIFICATE_ARN, "certificateName", "named",
                        "endpointType", "REGIONAL", "securityPolicy", "TLS_1_0"))));

        CustomDomain replaced = service.replaceV2DomainConfiguration(REGION, DOMAIN, Map.of("domainName", DOMAIN,
                "domainNameConfigurations", List.of(Map.of("certificateArn", CERTIFICATE_ARN))));

        assertEquals(CERTIFICATE_ARN, replaced.getRegionalCertificateArn());
        assertNull(replaced.getRegionalCertificateName(), "a field the configuration leaves out is cleared");
        assertEquals("TLS_1_2", replaced.getSecurityPolicy(), "an omitted security policy takes the default");
        assertEquals("REGIONAL", replaced.getEndpointConfigurationType());
    }

    @Test
    void aV2DomainKeepsItsCertificateAsTheRegionalOne() {
        service.createV2DomainName(REGION, Map.of("domainName", DOMAIN, "domainNameConfigurations",
                List.of(Map.of("certificateArn", CERTIFICATE_ARN, "certificateName", "named"))));

        CustomDomain domain = service.getDomainName(REGION, DOMAIN);

        assertEquals(CERTIFICATE_ARN, domain.getRegionalCertificateArn());
        assertEquals("named", domain.getRegionalCertificateName());
        assertNull(domain.getCertificateArn(), "the edge-optimized certificate is not the regional domain's");
        assertNull(domain.getCertificateName());
        assertEquals(CERTIFICATE_ARN, ApiGatewayService.v2CertificateArn(domain));
        assertEquals("named", ApiGatewayService.v2CertificateName(domain));
    }

    @Test
    void theV2ApiReportsTheCertificateOfTheDomainsEndpointType() {
        Map<String, Object> regional = new HashMap<>();
        regional.put("domainName", DOMAIN);
        regional.put("regionalCertificateArn", CERTIFICATE_ARN);
        regional.put("endpointConfiguration", Map.of("types", List.of("REGIONAL")));
        service.createDomainName(REGION, regional);
        Map<String, Object> edge = new HashMap<>();
        edge.put("domainName", OTHER_DOMAIN);
        edge.put("certificateArn", "arn:aws:acm:us-east-1:000000000000:certificate/edge");
        edge.put("regionalCertificateArn", CERTIFICATE_ARN);
        edge.put("endpointConfiguration", Map.of("types", List.of("EDGE")));
        service.createDomainName(REGION, edge);

        assertEquals(CERTIFICATE_ARN, ApiGatewayService.v2CertificateArn(service.getDomainName(REGION, DOMAIN)));
        assertEquals("arn:aws:acm:us-east-1:000000000000:certificate/edge",
                ApiGatewayService.v2CertificateArn(service.getDomainName(REGION, OTHER_DOMAIN)));
    }

    @Test
    void aDomainTheV2ApiWroteToTheEdgeFieldsKeepsItsCertificateUntilItIsReplaced() {
        // How a v2 create stored a regional domain's certificate before it went to the regional fields.
        Map<String, Object> earlier = new HashMap<>();
        earlier.put("domainName", DOMAIN);
        earlier.put("certificateArn", CERTIFICATE_ARN);
        earlier.put("certificateName", "earlier");
        earlier.put("endpointConfiguration", Map.of("types", List.of("REGIONAL")));
        service.createDomainName(REGION, earlier);
        assertEquals(CERTIFICATE_ARN, ApiGatewayService.v2CertificateArn(service.getDomainName(REGION, DOMAIN)));
        assertEquals("earlier", ApiGatewayService.v2CertificateName(service.getDomainName(REGION, DOMAIN)));

        CustomDomain replaced = service.replaceV2DomainConfiguration(REGION, DOMAIN, Map.of("domainName", DOMAIN,
                "domainNameConfigurations", List.of(Map.of("securityPolicy", "TLS_1_2"))));

        assertNull(replaced.getCertificateArn(), "the configuration is replaced as a whole");
        assertNull(replaced.getCertificateName());
        assertNull(ApiGatewayService.v2CertificateArn(replaced));
    }

    @Test
    void anUpdateBodyKeepsWhatItLeavesOut() {
        v2Domain(DOMAIN);
        String apiId = api("HTTP", "$default");
        v2Service.createStage(REGION, apiId, Map.of("stageName", "beta"));
        String id = service.createApiMapping(REGION, DOMAIN, "v1", apiId, "$default").apiMappingId();

        StoredMapping rekeyed = service.updateApiMapping(REGION, DOMAIN, id,
                Map.of("apiId", apiId, "apiMappingKey", "v2"));
        assertEquals("v2", rekeyed.storedPath());
        assertEquals("$default", rekeyed.mapping().getStage(), "a stage the body leaves out is kept");

        StoredMapping restaged = service.updateApiMapping(REGION, DOMAIN, id, Map.of("apiId", apiId, "stage", "beta"));
        assertEquals("v2", restaged.storedPath(), "a key the body leaves out is kept");
        assertEquals("beta", restaged.mapping().getStage());

        Map<String, Object> toRoot = new HashMap<>();
        toRoot.put("apiId", apiId);
        toRoot.put("apiMappingKey", null);
        assertEquals("(none)", service.updateApiMapping(REGION, DOMAIN, id, toRoot).storedPath(),
                "a key the body names as null is the root");
        assertEquals(id, service.getApiMapping(REGION, DOMAIN, id).apiMappingId());
    }
}
