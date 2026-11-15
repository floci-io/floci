package io.floci.aws.services.wafv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.wafv2.model.IpSet;
import io.floci.aws.services.wafv2.model.WebAcl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resources stored before WAF v2 was keyed by Region, under {@code Scope:Id}, move at startup onto the key of the
 * Region they live in: a {@code REGIONAL} one to the Region its ARN names, a {@code CLOUDFRONT} one to its partition's
 * CloudFront Region, even when its ARN named another Region, and one without an ARN to the default Region. Each is
 * then listed and reached there only.
 */
class WafV2LegacyKeysTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    void resourcesStoredUnderTheirScopeAndIdMoveToTheRegionTheirArnNames() {
        AccountAwareStorageBackend<WebAcl> webAcls = AccountAwareStorageBackend.inMemory(ACCOUNT);
        AccountAwareStorageBackend<IpSet> ipSets = AccountAwareStorageBackend.inMemory(ACCOUNT);
        webAcls.put("REGIONAL:acl-west", webAcl("acl-west", "REGIONAL",
                "arn:aws:wafv2:eu-west-1:" + ACCOUNT + ":regional/webacl/west/acl-west"));
        webAcls.put("CLOUDFRONT:acl-edge", webAcl("acl-edge", "CLOUDFRONT",
                "arn:aws:wafv2:us-east-1:" + ACCOUNT + ":global/webacl/edge/acl-edge"));
        webAcls.put("CLOUDFRONT:acl-edge-old", webAcl("acl-edge-old", "CLOUDFRONT",
                "arn:aws:wafv2:eu-west-1:" + ACCOUNT + ":global/webacl/edge-old/acl-edge-old"));
        IpSet ipSet = new IpSet();
        ipSet.setId("ip-central");
        ipSet.setName("central");
        ipSet.setScope("REGIONAL");
        ipSet.setArn("arn:aws:wafv2:eu-central-1:" + ACCOUNT + ":regional/ipset/central/ip-central");
        ipSets.put("REGIONAL:ip-central", ipSet);
        IpSet withoutArn = new IpSet();
        withoutArn.setId("ip-bare");
        withoutArn.setName("bare");
        withoutArn.setScope("REGIONAL");
        ipSets.put("REGIONAL:ip-bare", withoutArn);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                switch (invocation.getArgument(1, String.class)) {
                    case "wafv2-webacls.json" -> webAcls;
                    case "wafv2-ipsets.json" -> ipSets;
                    default -> AccountAwareStorageBackend.inMemory(ACCOUNT);
                });
        WafV2Service service = new WafV2Service(storageFactory, new RegionResolver("eu-west-1", ACCOUNT),
                new ObjectMapper());

        service.migrateLegacyKeys();

        assertEquals(Set.of("eu-west-1::REGIONAL:acl-west", "us-east-1::CLOUDFRONT:acl-edge",
                "us-east-1::CLOUDFRONT:acl-edge-old"), webAcls.keysForAccount(ACCOUNT));
        assertEquals(Set.of("eu-central-1::REGIONAL:ip-central", "eu-west-1::REGIONAL:ip-bare"),
                ipSets.keysForAccount(ACCOUNT));
        assertEquals(List.of("west"), service.listWebAcls("REGIONAL").stream().map(WebAcl::getName).toList());
        assertEquals(Set.of("edge", "edge-old"),
                service.listWebAcls("CLOUDFRONT").stream().map(WebAcl::getName).collect(Collectors.toSet()));
        assertEquals(List.of("bare"), service.listIpSets("REGIONAL").stream().map(IpSet::getName).toList());
    }

    private static WebAcl webAcl(String id, String scope, String arn) {
        WebAcl acl = new WebAcl();
        acl.setId(id);
        acl.setName(arn.split("/")[2]);
        acl.setScope(scope);
        acl.setArn(arn);
        return acl;
    }
}
