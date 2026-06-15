package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * WAF v2 validated through the real AWS SDK v2 client: IP set + Web ACL lifecycle,
 * LockToken optimistic concurrency, resource association, and scope isolation.
 */
@DisplayName("WAF v2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WafV2Test {

    private static Wafv2Client waf;
    private static String ipSetName;
    private static String aclName;
    private static String ipSetId;
    private static String ipSetArn;
    private static String aclId;
    private static String aclArn;
    private static String aclLockToken;

    private static final String API_RESOURCE_ARN =
            "arn:aws:apigateway:us-east-1::/restapis/floci-wafv2-sdk/stages/prod";

    @BeforeAll
    static void setup() {
        waf = TestFixtures.wafv2Client();
        ipSetName = TestFixtures.uniqueName("waf-ips");
        aclName = TestFixtures.uniqueName("waf-acl");
    }

    @AfterAll
    static void cleanup() {
        try {
            waf.disassociateWebACL(r -> r.resourceArn(API_RESOURCE_ARN));
        } catch (Exception ignored) {}
        try {
            String lock = waf.getWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId)).lockToken();
            waf.deleteWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId).lockToken(lock));
        } catch (Exception ignored) {}
        try {
            String lock = waf.getIPSet(r -> r.name(ipSetName).scope(Scope.REGIONAL).id(ipSetId)).lockToken();
            waf.deleteIPSet(r -> r.name(ipSetName).scope(Scope.REGIONAL).id(ipSetId).lockToken(lock));
        } catch (Exception ignored) {}
        waf.close();
    }

    @Test
    @Order(1)
    void createIpSet() {
        CreateIpSetResponse resp = waf.createIPSet(r -> r
                .name(ipSetName).scope(Scope.REGIONAL)
                .ipAddressVersion(IPAddressVersion.IPV4)
                .addresses("10.0.0.0/24", "192.168.0.0/16"));
        ipSetId = resp.summary().id();
        ipSetArn = resp.summary().arn();
        assertThat(ipSetId).isNotBlank();
        assertThat(resp.summary().lockToken()).isNotBlank();
    }

    @Test
    @Order(2)
    void duplicateIpSetFails() {
        assertThatThrownBy(() -> waf.createIPSet(r -> r
                .name(ipSetName).scope(Scope.REGIONAL)
                .ipAddressVersion(IPAddressVersion.IPV4)
                .addresses("10.0.0.0/24")))
                .isInstanceOf(WafDuplicateItemException.class);
    }

    @Test
    @Order(3)
    void createWebAcl() {
        Rule rule = Rule.builder()
                .name("iprule").priority(1)
                .statement(Statement.builder()
                        .ipSetReferenceStatement(IPSetReferenceStatement.builder().arn(ipSetArn).build())
                        .build())
                .action(RuleAction.builder().block(BlockAction.builder().build()).build())
                .visibilityConfig(VisibilityConfig.builder()
                        .sampledRequestsEnabled(true).cloudWatchMetricsEnabled(true).metricName("iprule").build())
                .build();

        CreateWebAclResponse resp = waf.createWebACL(r -> r
                .name(aclName).scope(Scope.REGIONAL)
                .defaultAction(DefaultAction.builder().allow(AllowAction.builder().build()).build())
                .visibilityConfig(VisibilityConfig.builder()
                        .sampledRequestsEnabled(true).cloudWatchMetricsEnabled(true).metricName("acl").build())
                .rules(rule));
        aclId = resp.summary().id();
        aclArn = resp.summary().arn();
        assertThat(aclId).isNotBlank();
        assertThat(aclArn).contains("regional/webacl");
    }

    @Test
    @Order(4)
    void getWebAcl() {
        GetWebAclResponse resp = waf.getWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId));
        assertThat(resp.webACL().name()).isEqualTo(aclName);
        assertThat(resp.webACL().rules()).hasSize(1);
        assertThat(resp.webACL().rules().get(0).statement().ipSetReferenceStatement().arn()).isEqualTo(ipSetArn);
        aclLockToken = resp.lockToken();
        assertThat(aclLockToken).isNotBlank();
    }

    @Test
    @Order(5)
    void updateWebAclRotatesLockToken() {
        UpdateWebAclResponse resp = waf.updateWebACL(r -> r
                .name(aclName).scope(Scope.REGIONAL).id(aclId).lockToken(aclLockToken)
                .description("updated")
                .defaultAction(DefaultAction.builder().block(BlockAction.builder().build()).build())
                .visibilityConfig(VisibilityConfig.builder()
                        .sampledRequestsEnabled(true).cloudWatchMetricsEnabled(true).metricName("acl").build()));
        assertThat(resp.nextLockToken()).isNotBlank().isNotEqualTo(aclLockToken);
    }

    @Test
    @Order(6)
    void staleLockTokenFails() {
        assertThatThrownBy(() -> waf.updateWebACL(r -> r
                .name(aclName).scope(Scope.REGIONAL).id(aclId).lockToken(aclLockToken)
                .defaultAction(DefaultAction.builder().allow(AllowAction.builder().build()).build())
                .visibilityConfig(VisibilityConfig.builder()
                        .sampledRequestsEnabled(true).cloudWatchMetricsEnabled(true).metricName("acl").build())))
                .isInstanceOf(WafOptimisticLockException.class);
    }

    @Test
    @Order(7)
    void associateAndQuery() {
        waf.associateWebACL(r -> r.webACLArn(aclArn).resourceArn(API_RESOURCE_ARN));

        GetWebAclForResourceResponse forResource =
                waf.getWebACLForResource(r -> r.resourceArn(API_RESOURCE_ARN));
        assertThat(forResource.webACL().id()).isEqualTo(aclId);

        ListResourcesForWebAclResponse resources =
                waf.listResourcesForWebACL(r -> r.webACLArn(aclArn));
        assertThat(resources.resourceArns()).containsExactly(API_RESOURCE_ARN);
    }

    @Test
    @Order(8)
    void deleteWhileAssociatedFails() {
        String lock = waf.getWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId)).lockToken();
        assertThatThrownBy(() -> waf.deleteWebACL(r -> r
                .name(aclName).scope(Scope.REGIONAL).id(aclId).lockToken(lock)))
                .isInstanceOf(WafAssociatedItemException.class);
    }

    @Test
    @Order(9)
    void cloudfrontScopeIsolated() {
        waf.createIPSet(r -> r
                .name(ipSetName).scope(Scope.CLOUDFRONT)
                .ipAddressVersion(IPAddressVersion.IPV4)
                .addresses("172.16.0.0/12"));
        List<IPSetSummary> cf = waf.listIPSets(r -> r.scope(Scope.CLOUDFRONT)).ipSets();
        IPSetSummary found = cf.stream().filter(s -> s.name().equals(ipSetName)).findFirst().orElseThrow();
        assertThat(found.arn()).isNotEqualTo(ipSetArn).contains("global/ipset");

        // cleanup the cloudfront ip set
        String lock = waf.getIPSet(r -> r.name(ipSetName).scope(Scope.CLOUDFRONT).id(found.id())).lockToken();
        waf.deleteIPSet(r -> r.name(ipSetName).scope(Scope.CLOUDFRONT).id(found.id()).lockToken(lock));
    }

    @Test
    @Order(10)
    void teardown() {
        waf.disassociateWebACL(r -> r.resourceArn(API_RESOURCE_ARN));
        String lock = waf.getWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId)).lockToken();
        waf.deleteWebACL(r -> r.name(aclName).scope(Scope.REGIONAL).id(aclId).lockToken(lock));

        String ipLock = waf.getIPSet(r -> r.name(ipSetName).scope(Scope.REGIONAL).id(ipSetId)).lockToken();
        waf.deleteIPSet(r -> r.name(ipSetName).scope(Scope.REGIONAL).id(ipSetId).lockToken(ipLock));
    }

    @Test
    @Order(11)
    void getMissingWebAclThrows() {
        assertThatThrownBy(() -> waf.getWebACL(r -> r
                .name("nope").scope(Scope.REGIONAL).id("00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(WafNonexistentItemException.class);
    }
}