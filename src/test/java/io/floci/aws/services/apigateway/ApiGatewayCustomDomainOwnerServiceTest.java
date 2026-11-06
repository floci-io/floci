package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.BasePathMapping;
import io.floci.aws.services.apigateway.model.CustomDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayCustomDomainOwnerServiceTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OWNER = "111122223333";
    private static final String OTHER_ACCOUNT = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String DOMAIN = "owned.example.test";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT)));
        service = new ApiGatewayService(storageFactory,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(TlsCertificateManager.class),
                new RegionResolver(REGION, DEFAULT_ACCOUNT));
    }

    @Test
    void resolvesNonDefaultOwnerWithoutGrantingManagementVisibility() {
        putDomain(OWNER, REGION);

        ApiGatewayService.CustomDomainRoute route = service.findDomainByName(DOMAIN);

        assertEquals(new ApiGatewayService.CustomDomainRoute(OWNER, REGION, DOMAIN), route);
        assertEquals(route, service.findDomainByRegionalHostname(DOMAIN + ".regional.local"));
        AwsException hidden = assertThrows(AwsException.class, () -> service.getDomainName(REGION, DOMAIN));
        assertEquals("NotFoundException", hidden.getErrorCode());
    }

    @Test
    void usesOnlyTheOwnersExactRegionAndDomainThenKeepsLongestPrefixMatching() {
        putDomain(OWNER, REGION);
        ApiGatewayService.CustomDomainRoute route = service.findDomainByName(DOMAIN);
        BasePathMapping foreign = new BasePathMapping("orders/special", "foreign-api", "foreign");
        mappings().putForAccount(OTHER_ACCOUNT, REGION + "::" + DOMAIN + "::orders/special", foreign);
        mappings().putForAccount(OWNER, OTHER_REGION + "::" + DOMAIN + "::orders/special", foreign);
        mappings().putForAccount(OWNER, REGION + "::prefix." + DOMAIN + "::orders/special", foreign);
        assertNull(service.resolveBasePathMapping(route, "/orders/special/42"));

        BasePathMapping root = new BasePathMapping("(none)", "owned-api", "root");
        BasePathMapping orders = new BasePathMapping("orders", "owned-api", "orders");
        mappings().putForAccount(OWNER, REGION + "::" + DOMAIN + "::(none)", root);
        mappings().putForAccount(OWNER, REGION + "::" + DOMAIN + "::orders", orders);

        assertSame(orders, service.resolveBasePathMapping(route, "/orders/special/42"));
        assertSame(orders, service.resolveBasePathMapping(route, "/orders"));
        assertSame(root, service.resolveBasePathMapping(route, "/orders-other"));
        assertSame(root, service.resolveBasePathMapping(route, "/"));
    }

    @Test
    void refusesAmbiguousDomainAcrossAccounts() {
        putDomain(OWNER, REGION);
        putDomain(OTHER_ACCOUNT, REGION);

        AwsException ambiguous = assertThrows(AwsException.class, () -> service.findDomainByName(DOMAIN));

        assertEquals("ConflictException", ambiguous.getErrorCode());
        assertEquals(409, ambiguous.getHttpStatus());
    }

    @Test
    void refusesAmbiguousDomainAcrossRegions() {
        putDomain(OWNER, REGION);
        putDomain(OWNER, OTHER_REGION);

        AwsException ambiguous = assertThrows(AwsException.class,
                () -> service.findDomainByRegionalHostname(DOMAIN + ".regional.local"));

        assertEquals("ConflictException", ambiguous.getErrorCode());
        assertEquals(409, ambiguous.getHttpStatus());
    }

    @Test
    void leavesUnknownHostsUnclaimed() {
        putDomain(OWNER, REGION);

        assertNull(service.findDomainByName("other.example.test"));
        assertNull(service.findDomainByRegionalHostname("other.example.test.regional.local"));
        assertNull(service.findDomainByRegionalHostname(DOMAIN));
    }

    @SuppressWarnings("unchecked")
    private void putDomain(String account, String region) {
        AccountAwareStorageBackend<CustomDomain> domains =
                (AccountAwareStorageBackend<CustomDomain>) stores.get("apigateway-domains.json");
        CustomDomain domain = new CustomDomain();
        domain.setDomainName(DOMAIN);
        domains.putForAccount(account, region + "::" + DOMAIN, domain);
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<BasePathMapping> mappings() {
        return (AccountAwareStorageBackend<BasePathMapping>) stores.get("apigateway-mappings.json");
    }
}
