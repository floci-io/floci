package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";

    private static final String DEFAULT_DOMAIN_SUFFIX = "cloudfront.net";
    private static final int LIST_MAX_ITEMS = 100;
    private static final String ID_ELEMENT = "Id";
    private static final String DISTRIBUTION_CONFIG_XML = """
            <DistributionConfig>
              <CallerReference>terraform-shape-test</CallerReference>
              <Comment>shape test</Comment>
              <Enabled>true</Enabled>
              <Origins>
                <Quantity>1</Quantity>
                <Items>
                  <Origin>
                    <Id>origin1</Id>
                    <DomainName>example-bucket.s3.us-east-1.amazonaws.com</DomainName>
                    <S3OriginConfig>
                      <OriginAccessIdentity></OriginAccessIdentity>
                    </S3OriginConfig>
                  </Origin>
                </Items>
              </Origins>
              <DefaultCacheBehavior>
                <TargetOriginId>origin1</TargetOriginId>
                <ViewerProtocolPolicy>redirect-to-https</ViewerProtocolPolicy>
              </DefaultCacheBehavior>
              <ViewerCertificate>
                <CloudFrontDefaultCertificate>true</CloudFrontDefaultCertificate>
              </ViewerCertificate>
            </DistributionConfig>
            """;
    private static final String EMPTY_ORIGIN_GROUPS_XML =
            "<OriginGroups><Quantity>0</Quantity></OriginGroups>";
    private static final String EMPTY_RESTRICTIONS_XML =
            "<Restrictions><GeoRestriction><RestrictionType>none</RestrictionType><Quantity>0</Quantity>"
                    + "</GeoRestriction></Restrictions>";
    private static final String GEO_RESTRICTED_DISTRIBUTION_CONFIG_XML = """
            <DistributionConfig>
              <CallerReference>geo-restriction-test</CallerReference>
              <Comment>geo restriction test</Comment>
              <Enabled>true</Enabled>
              <Origins>
                <Quantity>1</Quantity>
                <Items>
                  <Origin>
                    <Id>origin1</Id>
                    <DomainName>example-bucket.s3.us-east-1.amazonaws.com</DomainName>
                    <S3OriginConfig>
                      <OriginAccessIdentity></OriginAccessIdentity>
                    </S3OriginConfig>
                  </Origin>
                </Items>
              </Origins>
              <DefaultCacheBehavior>
                <TargetOriginId>origin1</TargetOriginId>
                <ViewerProtocolPolicy>redirect-to-https</ViewerProtocolPolicy>
              </DefaultCacheBehavior>
              <ViewerCertificate>
                <CloudFrontDefaultCertificate>true</CloudFrontDefaultCertificate>
              </ViewerCertificate>
              <Restrictions>
                <GeoRestriction>
                  <RestrictionType>whitelist</RestrictionType>
                  <Quantity>2</Quantity>
                  <Items>
                    <Location>US</Location>
                    <Location>CA</Location>
                  </Items>
                </GeoRestriction>
              </Restrictions>
            </DistributionConfig>
            """;
    private static final String WHITELIST_RESTRICTIONS_XML =
            "<Restrictions><GeoRestriction><RestrictionType>whitelist</RestrictionType><Quantity>2</Quantity>"
                    + "<Items><Location>US</Location><Location>CA</Location></Items>"
                    + "</GeoRestriction></Restrictions>";

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        return serviceWithDomainSuffix(domainSuffix, "us-east-1");
    }

    /** A null {@code domainSuffix} is {@code floci.services.cloudfront.domain-suffix} left unset. */
    private CloudFrontService serviceWithDomainSuffix(String domainSuffix, String region) {
        return serviceWithDomainSuffix(domainSuffix, region, "http://localhost:4566");
    }

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix, String region, String baseUrl) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.DnsConfig dnsConfig = Mockito.mock(EmulatorConfig.DnsConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.CloudFrontServiceConfig cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.effectiveBaseUrl()).thenReturn(baseUrl);
        when(config.dns()).thenReturn(dnsConfig);
        when(dnsConfig.extraSuffixes()).thenReturn(Optional.empty());
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(Optional.ofNullable(domainSuffix));

        return new CloudFrontService(storageFactory, config, new RegionResolver(region, ACCOUNT));
    }

    @Test
    void publishFunctionCopiesDevelopmentToLive() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");
        function.setFunctionCode("function handler(event) { return event.request; }");
        function.setRuntime("cloudfront-js-2.0");
        function.setComment("routing function");

        CloudFrontFunction development = service.createFunction(function);
        CloudFrontFunction live = service.publishFunction(function.getName(), development.getEtag());

        CloudFrontFunction describedDevelopment = service.describeFunction(function.getName(), "DEVELOPMENT");
        CloudFrontFunction describedLive = service.describeFunction(function.getName(), "LIVE");
        assertEquals("DEVELOPMENT", describedDevelopment.getStage());
        assertEquals("LIVE", describedLive.getStage());
        assertEquals(development.getFunctionCode(), describedLive.getFunctionCode());
        assertEquals(development.getEtag(), describedDevelopment.getEtag());
        assertNotEquals(development.getEtag(), live.getEtag());
    }

    @Test
    void createFunction_existingName_isRejectedAndKeepsTheFunction() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction first = new CloudFrontFunction();
        first.setName("routing");
        first.setFunctionCode("first");
        service.createFunction(first);
        CloudFrontFunction second = new CloudFrontFunction();
        second.setName("routing");
        second.setFunctionCode("second");

        AwsException error = assertThrows(AwsException.class, () -> service.createFunction(second));

        assertEquals("FunctionAlreadyExists", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("first", service.describeFunction("routing", null).getFunctionCode());
    }

    @Test
    void createFunction_nameOfADeletedFunction_isAccepted() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");
        CloudFrontFunction created = service.createFunction(function);
        service.publishFunction("routing", created.getEtag());
        service.deleteFunction("routing", created.getEtag());
        CloudFrontFunction again = new CloudFrontFunction();
        again.setName("routing");

        assertEquals("DEVELOPMENT", service.createFunction(again).getStage());
    }

    @Test
    void createFunction_unknownRuntime_isRejected() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");
        function.setRuntime("python3");

        AwsException error = assertThrows(AwsException.class, () -> service.createFunction(function));

        assertEquals("InvalidArgument", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertThrows(AwsException.class, () -> service.describeFunction("routing", null));
    }

    @Test
    void createFunction_supportedRuntimes_areAccepted() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        for (String runtime : List.of("cloudfront-js-1.0", "cloudfront-js-2.0")) {
            CloudFrontFunction function = new CloudFrontFunction();
            function.setName("routing-" + runtime);
            function.setRuntime(runtime);

            assertEquals(runtime, service.createFunction(function).getRuntime());
        }
    }

    @Test
    void updateFunction_unknownRuntime_isRejectedAndKeepsTheFunction() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");
        function.setFunctionCode("first");
        function.setRuntime("cloudfront-js-2.0");
        String etag = service.createFunction(function).getEtag();
        CloudFrontFunction update = new CloudFrontFunction();
        update.setFunctionCode("second");
        update.setRuntime("python3");

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateFunction("routing", etag, update));

        assertEquals("InvalidArgument", error.getErrorCode());
        CloudFrontFunction kept = service.describeFunction("routing", null);
        assertEquals("first", kept.getFunctionCode());
        assertEquals("cloudfront-js-2.0", kept.getRuntime());
        assertEquals(etag, kept.getEtag());
    }

    @Test
    void listFunctionsWithoutStageReturnsDevelopmentAndLive() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");

        CloudFrontFunction development = service.createFunction(function);
        service.publishFunction(function.getName(), development.getEtag());

        assertEquals(List.of("DEVELOPMENT", "LIVE"), service.listFunctions(null, null, LIST_MAX_ITEMS)
                .stream()
                .map(CloudFrontFunction::getStage)
                .toList());
    }

    @Test
    void functionStageLookupRejectsUnsupportedValues() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("routing");
        service.createFunction(function);

        AwsException error = assertThrows(AwsException.class,
                () -> service.describeFunction(function.getName(), "TESTING"));
        assertEquals("InvalidArgument", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void legacyLiveFunctionIsNeverReturnedAsDevelopment() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("legacy-routing");
        CloudFrontFunction legacyLive = service.createFunction(function);
        legacyLive.setStage("LIVE");
        legacyLive.setStatus("DEPLOYED");

        AwsException developmentError = assertThrows(AwsException.class,
                () -> service.describeFunction(function.getName(), "DEVELOPMENT"));
        assertEquals("NoSuchFunctionExists", developmentError.getErrorCode());
        assertEquals(legacyLive, service.describeFunction(function.getName(), "LIVE"));

        AwsException publishError = assertThrows(AwsException.class,
                () -> service.publishFunction(function.getName(), legacyLive.getEtag()));
        assertEquals("NoSuchFunctionExists", publishError.getErrorCode());
    }

    @Test
    void legacyLiveFunctionCanBeDeleted() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName("legacy-routing");
        CloudFrontFunction legacyLive = service.createFunction(function);
        legacyLive.setStage("LIVE");
        legacyLive.setStatus("DEPLOYED");

        service.deleteFunction(function.getName(), legacyLive.getEtag());

        AwsException error = assertThrows(AwsException.class,
                () -> service.describeFunction(function.getName(), "LIVE"));
        assertEquals("NoSuchFunctionExists", error.getErrorCode());
    }

    @Test
    void distributionArnTakesTheRequestsPartition() {
        CloudFrontService china = serviceWithDomainSuffix("cloudfront.net", "cn-north-1");

        Distribution dist = china.createDistribution(new Distribution(), Map.of());

        assertEquals("arn:aws-cn:cloudfront::" + ACCOUNT + ":distribution/" + dist.getId(), dist.getArn());
    }

    @Test
    void distributionTagsAreReadAndUpdatedByArn() {
        CloudFrontService service = serviceWithDomainSuffix(DEFAULT_DOMAIN_SUFFIX);
        Distribution dist = service.createDistribution(new Distribution(), Map.of("env", "dev"));

        assertEquals(Map.of("env", "dev"), service.listTagsForResource(dist.getArn()));

        service.tagResource(dist.getArn(), Map.of("team", "infra"));
        assertEquals(Map.of("env", "dev", "team", "infra"), service.listTagsForResource(dist.getArn()));

        service.untagResource(dist.getArn(), List.of("env"));
        assertEquals(Map.of("team", "infra"), service.listTagsForResource(dist.getArn()));
    }

    @Test
    void deletingDistributionRemovesTagsStoredUnderItsArn() {
        CloudFrontService service = serviceWithDomainSuffix(DEFAULT_DOMAIN_SUFFIX);
        Distribution dist = service.createDistribution(new Distribution(), Map.of());
        service.tagResource(dist.getArn(), Map.of("env", "dev"));

        service.deleteDistribution(dist.getId(), dist.getEtag());

        assertEquals(Map.of(), service.listTagsForResource(dist.getArn()));
    }

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix(null);

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.net"),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createPublicKeyIssuesAnAwsShapedId() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix(DEFAULT_DOMAIN_SUFFIX);

        PublicKey created = service.createPublicKey(validPublicKey("signer"));

        // Verified on AWS us-east-1 2026-09-18: CreatePublicKey answers K + 13 characters
        // (K2VKB3XV74876Q), the value a signed URL carries as Key-Pair-Id.
        assertTrue(created.getId().matches("K[A-Z0-9]{13}"),
                "Expected an AWS-shaped public key id, got: " + created.getId());
    }

    @Test
    void createDistributionLowerCasesTheDomainNameId() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        // A browser lower-cases the host it sends, so an upper-case id here would break every
        // signed URL built from the domain name.
        assertEquals(dist.getId().toLowerCase(Locale.ROOT) + ".cloudfront.net", dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    /**
     * China's distributions are named under {@code cloudfront.cn}; GovCloud has no CloudFront and
     * so no suffix of its own, and keeps the commercial one.
     */
    @Test
    void anUnconfiguredDomainSuffixFollowsTheRequestsPartition() {
        CloudFrontService china = serviceWithDomainSuffix(null, "cn-north-1");
        CloudFrontService govCloud = serviceWithDomainSuffix(null, "us-gov-west-1");

        Distribution dist = china.createDistribution(new Distribution(), Map.of());
        StreamingDistribution streaming = china.createStreamingDistribution(new StreamingDistribution());

        assertEquals(dist.getId().toLowerCase(Locale.ROOT) + ".cloudfront.cn", dist.getDomainName());
        assertEquals(streaming.getId().toLowerCase(Locale.ROOT) + ".cloudfront.cn", streaming.getDomainName());
        assertTrue(govCloud.createDistribution(new Distribution(), Map.of()).getDomainName().endsWith(".cloudfront.net"));
    }

    @Test
    void aConfiguredDomainSuffixWinsOverThePartitions() {
        CloudFrontService china = serviceWithDomainSuffix("cloudfront.local", "cn-north-1");

        Distribution dist = china.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    @Test
    void findByHostMatchesDomainNameAndAliasIgnoringPort() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setAliases(List.of("viewer.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        // Matches the assigned CloudFront domain name, with or without a port.
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName() + ":4566").getId());
        // Matches a configured alias, case-insensitively and ignoring the port.
        assertEquals(dist.getId(), service.findByHost("viewer.example.test").getId());
        assertEquals(dist.getId(), service.findByHost("VIEWER.EXAMPLE.TEST:8443").getId());
        // No match for an unrelated host.
        assertNull(service.findByHost("unrelated.example.test"));
        assertNull(service.findByHost(null));
    }

    @Test
    void findByHostMatchesLocalDeliveryHostnames() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(distribution(true, List.of()), Map.of());
        String id = dist.getId();

        assertEquals(id, service.findByHost(id + ".cloudfront.localhost.floci.io").getId());
        assertEquals(id, service.findByHost(id + ".cloudfront.localhost:4566").getId());
        // Clients are free to lower-case the hostname they send.
        assertEquals(id, service.findByHost(
                (id + ".cloudfront.localhost.floci.io").toLowerCase(Locale.ROOT)).getId());

        // The id stands for one label, and an unknown id belongs to no distribution.
        assertNull(service.findByHost("a." + id + ".cloudfront.localhost.floci.io"));
        assertNull(service.findByHost("EABCDEFGHIJKLM.cloudfront.localhost.floci.io"));
        assertNull(service.findByHost(".cloudfront.localhost"));
    }

    @Test
    void findByHostPrefersExactAliasOverLocalDeliveryHostname() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution generated = service.createDistribution(distribution(true, List.of()), Map.of());
        String aliasOfAnother = generated.getId() + ".cloudfront.localhost";
        Distribution aliasOwner = service.createDistribution(
                distribution(true, List.of(aliasOfAnother)), Map.of());

        assertEquals(aliasOwner.getId(), service.findByHost(aliasOfAnother).getId());
    }

    @Test
    void findByHostRetainsOwnershipForDisabledDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(false);
        cfg.setAliases(List.of("disabled.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost("disabled.example.test").getId());
    }

    @Test
    void rejectsDuplicateAliasOnCreateAndUpdate() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution owner = service.createDistribution(
                distribution(true, List.of("shared.example.test")), Map.of());

        AwsException createError = assertThrows(AwsException.class, () -> service.createDistribution(
                distribution(true, List.of("SHARED.EXAMPLE.TEST")), Map.of()));
        assertEquals("CNAMEAlreadyExists", createError.getErrorCode());

        Distribution other = service.createDistribution(
                distribution(true, List.of("other.example.test")), Map.of());
        AwsException updateError = assertThrows(AwsException.class, () -> service.updateDistribution(
                other.getId(), other.getEtag(), distribution(true, List.of("shared.example.test"))));
        assertEquals("CNAMEAlreadyExists", updateError.getErrorCode());
        assertEquals(owner.getId(), service.findByHost("shared.example.test").getId());
        assertEquals(other.getId(), service.findByHost("other.example.test").getId());
    }

    @Test
    void associateAliasTransfersOwnershipAtomically() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution owner = service.createDistribution(
                distribution(false, List.of("move.example.test")), Map.of());
        Distribution target = service.createDistribution(distribution(true, List.of()), Map.of());

        service.associateAlias(target.getId(), "MOVE.EXAMPLE.TEST");

        assertTrue(service.getDistribution(owner.getId()).getConfig().getAliases().isEmpty());
        assertEquals(target.getId(), service.findByHost("move.example.test").getId());
    }

    @Test
    void wildcardAliasesMatchSubdomainsAndPreferMoreSpecificNames() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution wildcard = service.createDistribution(
                distribution(true, List.of("*.example.test")), Map.of());
        Distribution specificWildcard = service.createDistribution(
                distribution(true, List.of("*.shop.example.test")), Map.of());
        Distribution exact = service.createDistribution(
                distribution(true, List.of("marketing.example.test")), Map.of());

        assertEquals(wildcard.getId(), service.findByHost("www.example.test").getId());
        assertEquals(specificWildcard.getId(), service.findByHost("item.shop.example.test").getId());
        assertEquals(exact.getId(), service.findByHost("marketing.example.test").getId());
        // A wildcard replaces exactly one label, so it covers neither a higher nor a lower level:
        // "*.example.test" matches www.example.test but not a.b.example.test, and not the apex.
        // Per AWS: "you can't add alternate domain names that are at levels higher or lower than
        // the wildcard. For example, you can't add ... example.com or marketing.product.example.com."
        assertNull(service.findByHost("a.b.example.test"));
        assertNull(service.findByHost("example.test"));
        // ...and the deeper wildcard is likewise single-level.
        assertNull(service.findByHost("a.b.shop.example.test"));
    }

    @Test
    void validatesRequiredOriginAccessControlConfiguration() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        OriginAccessControl missingName = originAccessControl();
        missingName.setName(null);
        assertInvalidOriginAccessControl(service, missingName, "MalformedXML", "1 validation error detected: Value null"
                + " at 'originAccessControlConfig.name' failed to satisfy constraint: Member must not be null");

        OriginAccessControl emptyName = originAccessControl();
        emptyName.setName("");
        assertInvalidOriginAccessControl(service, emptyName, "InvalidArgument", "The parameter Name is too small or empty.");

        OriginAccessControl longName = originAccessControl();
        longName.setName("x".repeat(65));
        assertInvalidOriginAccessControl(service, longName, "InvalidArgument", "The parameter Name is too big.");

        OriginAccessControl invalidProtocol = originAccessControl();
        invalidProtocol.setSigningProtocol("sigv2");
        assertInvalidOriginAccessControl(service, invalidProtocol, "MalformedXML", "1 validation error detected: Value"
                + " 'sigv2' at 'originAccessControlConfig.signingProtocol' failed to satisfy constraint: Member must"
                + " satisfy enum value set: [sigv4, sigv4a]");

        OriginAccessControl invalidBehavior = originAccessControl();
        invalidBehavior.setSigningBehavior("sometimes");
        assertInvalidOriginAccessControl(service, invalidBehavior, "MalformedXML", "1 validation error detected: Value"
                + " 'sometimes' at 'originAccessControlConfig.signingBehavior' failed to satisfy constraint: Member"
                + " must satisfy enum value set: [always-amz-auth, never, always, no-override]");

        OriginAccessControl invalidOriginType = originAccessControl();
        invalidOriginType.setOriginAccessControlOriginType("custom");
        assertInvalidOriginAccessControl(service, invalidOriginType, "MalformedXML", "1 validation error detected:"
                + " Value 'custom' at 'originAccessControlConfig.originAccessControlOriginType' failed to satisfy"
                + " constraint: Member must satisfy enum value set: [s3, execute-api, lambda, mediastore,"
                + " mediapackagev2]");
    }

    @Test
    void originAccessControlEnumsAndNameAreReportedTogetherBeforeTheOtherRules() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        assertInvalidOriginAccessControl(service, new OriginAccessControl(), "MalformedXML", "4 validation errors"
                + " detected: Value null at 'originAccessControlConfig.originAccessControlOriginType' failed to satisfy"
                + " constraint: Member must not be null; Value null at 'originAccessControlConfig.signingProtocol'"
                + " failed to satisfy constraint: Member must not be null; Value null at"
                + " 'originAccessControlConfig.signingBehavior' failed to satisfy constraint: Member must not be null;"
                + " Value null at 'originAccessControlConfig.name' failed to satisfy constraint: Member must not be null");

        OriginAccessControl longNameAndBehavior = originAccessControl();
        longNameAndBehavior.setName("x".repeat(65));
        longNameAndBehavior.setSigningBehavior("always-amz-auth");
        assertInvalidOriginAccessControl(service, longNameAndBehavior, "InvalidArgument", "The parameter Name is too big.");
    }

    @Test
    void alwaysAmzAuthIsForLambdaAndSigV4aForS3OrLambda() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        assertInvalidOriginAccessControl(service, originAccessControl("sigv4", "always-amz-auth", "s3"),
                "InvalidArgument", "The parameter SigningBehavior always-amz-auth is only supported with lambda origin type.");
        assertInvalidOriginAccessControl(service, originAccessControl("sigv4a", "always-amz-auth", "mediastore"),
                "InvalidArgument", "The parameter SigningBehavior always-amz-auth is only supported with lambda origin type.");
        assertInvalidOriginAccessControl(service, originAccessControl("sigv4a", "always", "mediapackagev2"),
                "InvalidArgument", "The parameter OriginType sigv4a is only supported with s3 or lambda origin types.");

        assertDoesNotThrow(() -> service.createOriginAccessControl(originAccessControl("sigv4a", "always-amz-auth", "lambda")));
        assertDoesNotThrow(() -> service.createOriginAccessControl(originAccessControl("sigv4a", "never", "lambda")));
        assertDoesNotThrow(() -> service.createOriginAccessControl(originAccessControl("sigv4a", "always", "s3")));
    }

    @Test
    void theExecuteApiOriginTypeIsRefused() {
        AwsException error = assertAws("AccessDenied", () -> serviceWithDomainSuffix("cloudfront.net")
                .createOriginAccessControl(originAccessControl("sigv4", "always", "execute-api")));

        assertEquals("Access Denied.", error.getMessage());
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void aLambdaOriginNeedsTheOriginAccessControlItsHostnameCallsFor() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String regional = "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.on.aws";
        String global = "abcdefghijklmnopqrstuvwxyz.lambda-web.global.on.aws";
        String functionUrl = "abcdefghijklmnopqrstuvwxyz0123.lambda-url.us-east-1.on.aws";
        String differ = "The origin type and OAC origin type differ";

        assertOriginAccessControlRefused(service, regional, "sigv4", "never", "lambda",
                "Lambda-Web origins require always-amz-auth signing behavior.");
        assertOriginAccessControlRefused(service, "abcdefghijklmnop.001.lambda-web.us-east-1.localhost:4566", "sigv4",
                "always", "lambda", "Lambda-Web origins require always-amz-auth signing behavior.");
        assertOriginAccessControlRefused(service, regional, "sigv4", "always", "s3", differ);
        assertOriginAccessControlRefused(service, global, "sigv4", "always", "s3", differ);
        assertOriginAccessControlRefused(service, "example.com", "sigv4", "always-amz-auth", "lambda", differ);
        assertOriginAccessControlRefused(service, "bucket.s3.us-east-1.amazonaws.com", "sigv4", "always", "lambda", differ);
        assertOriginAccessControlRefused(service, global, "sigv4", "always-amz-auth", "lambda",
                "This origin domain requires SigV4a signing protocol.");
        assertOriginAccessControlRefused(service, global, "sigv4", "never", "lambda",
                "This origin domain requires SigV4a signing protocol.");
        assertOriginAccessControlRefused(service, regional, "sigv4a", "always", "lambda",
                "SigV4a signing protocol is not compatible with this origin domain.");
        assertOriginAccessControlRefused(service, "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-west-2.on.aws", "sigv4a",
                "always-amz-auth", "lambda", "SigV4a signing protocol is not compatible with this origin domain.");
        assertOriginAccessControlRefused(service, functionUrl, "sigv4a", "always-amz-auth", "lambda",
                "SigV4a signing protocol is not compatible with this origin domain.");
        assertOriginAccessControlRefused(service, functionUrl, "sigv4", "always-amz-auth", "lambda",
                "always-amz-auth signing behavior requires a Lambda-Web origin.");

        assertDoesNotThrow(() -> service.createDistribution(
                distributionWithOrigin(regional, oac(service, "sigv4", "always-amz-auth", "lambda"), null), Map.of()));
        assertDoesNotThrow(() -> service.createDistribution(
                distributionWithOrigin(global, oac(service, "sigv4a", "always-amz-auth", "lambda"), null), Map.of()));
        assertDoesNotThrow(() -> service.createDistribution(
                distributionWithOrigin(functionUrl, oac(service, "sigv4", "never", "lambda"), null), Map.of()));
    }

    @Test
    void onlyAHostnameUnderOnAwsOrThisFlociIsALambdaOrigin() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String differ = "The origin type and OAC origin type differ";

        for (String notLambda : List.of("api.lambda-web.us-east-1.example.com",
                "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.on.aws.example.com",
                "abcdefghijklmnopqrstuvwxyz0123.lambda-url.us-east-1.example.com",
                "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.amazonaws.com",
                "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.floci.example.com",
                "abcdefghijklmnopqrstuvwxyz.001.LAMBDA-WEB.US-EAST-1.ON.AWS",
                "abc.lambda-web.Global.on.aws",
                "lambda-web.us-east-1.on.aws",
                "abc.lambda-web.foo.on.aws")) {
            assertOriginAccessControlRefused(service, notLambda, "sigv4a", "always-amz-auth", "lambda", differ);
        }
        for (String webFunction : List.of("ABC.lambda-web.us-east-1.on.aws", "a.b.c.lambda-web.us-east-1.on.aws",
                "abc.lambda-web.xx-fake-9.on.aws", "abcdefghijklmnop.001.lambda-web.us-east-1.localhost")) {
            assertOriginAccessControlRefused(service, webFunction, "sigv4a", "always-amz-auth", "lambda",
                    "SigV4a signing protocol is not compatible with this origin domain.");
        }
    }

    @Test
    void aFlociHostWithAnUnderscoreNamesLambdaOriginsToo() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net", "us-east-1", "http://floci_host:4566");

        for (String webFunction : List.of("abcdefghijklmnop.001.lambda-web.us-east-1.floci_host:4566",
                "abcdefghijklmnop.001.lambda-web.us-east-1.floci_host")) {
            assertOriginAccessControlRefused(service, webFunction, "sigv4a", "always-amz-auth", "lambda",
                    "SigV4a signing protocol is not compatible with this origin domain.");
        }
    }

    @Test
    void anUpdateChecksTheOriginAccessControlOfALambdaOrigin() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String never = oac(service, "sigv4", "never", "lambda");
        Distribution created = service.createDistribution(
                distributionWithOrigin("abcdefghijklmnopqrstuvwxyz0123.lambda-url.us-east-1.on.aws", never, null), Map.of());

        AwsException error = assertAws("IllegalOriginAccessConfiguration", () -> service.updateDistribution(
                created.getId(), created.getEtag(), distributionWithOrigin(
                        "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.on.aws", never, null)));

        assertEquals("Illegal configuration: Lambda-Web origins require always-amz-auth signing behavior.",
                error.getMessage());
    }

    @Test
    void aDistributionNamingAnUnknownOriginAccessControlIsRefused() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        AwsException created = assertAws("InvalidOriginAccessControl", () -> service.createDistribution(
                distributionWithOrigin("example.com", "E0000000000000", null), Map.of()));
        assertEquals("The specified origin access control does not exist or is not valid.", created.getMessage());
        assertEquals(400, created.getHttpStatus());

        Distribution existing = service.createDistribution(distributionWithOrigin("example.com", null, null), Map.of());
        assertAws("InvalidOriginAccessControl", () -> service.updateDistribution(existing.getId(),
                existing.getEtag(), distributionWithOrigin("example.com", "E0000000000000", null)));
        assertNull(service.getDistribution(existing.getId()).getConfig().getOrigins().getFirst()
                .getOriginAccessControlId());
    }

    @Test
    void createDistributionRejectsConnectionAttemptsOutsideTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException above = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.setConnectionAttempts(4);
                }), Map.of()));
        assertEquals("The parameter ConnectionAttempts must be between 1 and 3.", above.getMessage());
        assertEquals(400, above.getHttpStatus());

        assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.setConnectionAttempts(0);
                }), Map.of()));
    }

    @Test
    void createDistributionRejectsConnectionTimeoutOutsideTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException above = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.setConnectionTimeout(11);
                }), Map.of()));
        assertEquals("The parameter ConnectionTimeout must be between 1 and 10 seconds.", above.getMessage());
        assertEquals(400, above.getHttpStatus());

        AwsException below = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.setConnectionTimeout(0);
                }), Map.of()));
        assertEquals("The parameter ConnectionTimeout must be between 1 and 10 seconds.", below.getMessage());
    }

    @Test
    void createDistributionRejectsOriginReadTimeoutOutsideTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException error = assertAws("InvalidOriginReadTimeout",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginReadTimeout", 121);
                }), Map.of()));
        assertEquals("The parameter OriginReadTimeout must be between 1 and 120 seconds.", error.getMessage());
        assertEquals(400, error.getHttpStatus());

        assertAws("InvalidOriginReadTimeout",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginReadTimeout", 0);
                }), Map.of()));
    }

    @Test
    void createDistributionRejectsOriginKeepaliveTimeoutOutsideTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException below = assertAws("InvalidOriginKeepaliveTimeout",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginKeepaliveTimeout", 0);
                }), Map.of()));
        assertEquals("The parameter OriginKeepaliveTimeout must be between 1 and 300 seconds.", below.getMessage());
        assertEquals(400, below.getHttpStatus());

        assertAws("InvalidOriginKeepaliveTimeout",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginKeepaliveTimeout", 301);
                }), Map.of()));
    }

    @Test
    void createDistributionRejectsAResponseCompletionTimeoutShorterThanTheReadTimeout() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException below = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginReadTimeout", 30);
                    origin.setResponseCompletionTimeout(29);
                }), Map.of()));
        assertEquals("The parameter ResponseCompletionTimeout must be greater than or equal to"
                + " OriginReadTimeout.", below.getMessage());
        assertEquals(400, below.getHttpStatus());

        AwsException negative = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin ->
                        origin.setResponseCompletionTimeout(-1)), Map.of()));
        assertEquals("The parameter ResponseCompletionTimeout must be a non-negative integer.",
                negative.getMessage());
        assertEquals(400, negative.getHttpStatus());

        // A completion timeout as long as the read timeout, and none at all, are both accepted.
        assertDoesNotThrow(() -> service.createDistribution(distributionWithCustomOrigin(origin -> {
            origin.getCustomOriginConfig().put("OriginReadTimeout", 30);
            origin.setResponseCompletionTimeout(30);
        }), Map.of()));
        assertDoesNotThrow(() -> service.createDistribution(distributionWithCustomOrigin(origin ->
                origin.setResponseCompletionTimeout(0)), Map.of()));

        // No OriginReadTimeout means the 30 s default, which a shorter completion timeout breaks.
        AwsException belowDefault = assertAws("InvalidArgument",
                () -> service.createDistribution(distributionWithCustomOrigin(origin ->
                        origin.setResponseCompletionTimeout(10)), Map.of()));
        assertEquals("The parameter ResponseCompletionTimeout must be greater than or equal to"
                + " OriginReadTimeout.", belowDefault.getMessage());
    }

    @Test
    void updateDistributionRejectsOriginSettingsOutsideTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution existing = service.createDistribution(
                distributionWithCustomOrigin(origin -> { }), Map.of());

        AwsException error = assertAws("InvalidArgument", () -> service.updateDistribution(
                existing.getId(), existing.getEtag(),
                distributionWithCustomOrigin(origin -> origin.setConnectionAttempts(4))));
        assertEquals("The parameter ConnectionAttempts must be between 1 and 3.", error.getMessage());

        assertAws("InvalidOriginReadTimeout", () -> service.updateDistribution(
                existing.getId(), existing.getEtag(),
                distributionWithCustomOrigin(origin ->
                        origin.getCustomOriginConfig().put("OriginReadTimeout", 121))));
    }

    @Test
    void updateDistributionRejectsAResponseCompletionTimeoutShorterThanTheReadTimeout() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution existing = service.createDistribution(
                distributionWithCustomOrigin(origin -> { }), Map.of());

        AwsException below = assertAws("InvalidArgument", () -> service.updateDistribution(
                existing.getId(), existing.getEtag(),
                distributionWithCustomOrigin(origin -> {
                    origin.getCustomOriginConfig().put("OriginReadTimeout", 30);
                    origin.setResponseCompletionTimeout(29);
                })));
        assertEquals("The parameter ResponseCompletionTimeout must be greater than or equal to"
                + " OriginReadTimeout.", below.getMessage());
        assertEquals(400, below.getHttpStatus());

        AwsException negative = assertAws("InvalidArgument", () -> service.updateDistribution(
                existing.getId(), existing.getEtag(),
                distributionWithCustomOrigin(origin -> origin.setResponseCompletionTimeout(-1))));
        assertEquals("The parameter ResponseCompletionTimeout must be a non-negative integer.",
                negative.getMessage());
        assertEquals(400, negative.getHttpStatus());

        // The rejected updates left the distribution as it was.
        Origin origin = service.getDistribution(existing.getId()).getConfig().getOrigins().getFirst();
        assertNull(origin.getResponseCompletionTimeout());
    }

    @Test
    void createDistributionAcceptsOriginSettingsWithinTheAwsRange() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution created = assertDoesNotThrow(() -> service.createDistribution(
                distributionWithCustomOrigin(origin -> {
                    origin.setConnectionAttempts(2);
                    origin.setConnectionTimeout(5);
                    origin.getCustomOriginConfig().put("OriginReadTimeout", 45);
                    origin.getCustomOriginConfig().put("OriginKeepaliveTimeout", 300);
                }), Map.of()));

        Origin origin = created.getConfig().getOrigins().getFirst();
        assertEquals(2, origin.getConnectionAttempts());
        assertEquals(5, origin.getConnectionTimeout());
        assertEquals(45, origin.getCustomOriginConfig().get("OriginReadTimeout"));
        assertEquals(300, origin.getCustomOriginConfig().get("OriginKeepaliveTimeout"));
    }

    @Test
    void anOriginAccessControlInUseKeepsHowItSigns() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String s3 = oac(service, "sigv4", "always", "s3");
        service.createDistribution(distributionWithOrigin("bucket.s3.us-east-1.amazonaws.com", s3, null), Map.of());
        String functionUrl = oac(service, "sigv4", "always", "lambda");
        service.createDistribution(distributionWithOrigin(
                "abcdefghijklmnopqrstuvwxyz0123.lambda-url.us-east-1.on.aws", functionUrl, null), Map.of());

        assertDoesNotThrow(() -> updateOriginAccessControl(service, s3, "sigv4", "never", "s3"));
        assertDoesNotThrow(() -> updateOriginAccessControl(service, s3, "sigv4", "no-override", "s3"));
        assertDoesNotThrow(() -> updateOriginAccessControl(service, functionUrl, "sigv4", "never", "lambda"));
        assertIllegalUpdate(service, s3, "sigv4a", "no-override", "s3");
        assertIllegalUpdate(service, s3, "sigv4", "no-override", "mediastore");
        assertIllegalUpdate(service, functionUrl, "sigv4", "always-amz-auth", "lambda");
        assertIllegalUpdate(service, functionUrl, "sigv4a", "never", "lambda");

        String unused = oac(service, "sigv4", "always", "s3");
        assertDoesNotThrow(() -> updateOriginAccessControl(service, unused, "sigv4a", "always", "lambda"));
    }

    private static void updateOriginAccessControl(CloudFrontService service, String id, String protocol,
                                                  String behavior, String originType) {
        service.updateOriginAccessControl(id, service.getOriginAccessControl(id).getEtag(),
                originAccessControl(protocol, behavior, originType));
    }

    private static void assertIllegalUpdate(CloudFrontService service, String id, String protocol, String behavior,
                                            String originType) {
        AwsException error = assertAws("IllegalUpdate",
                () -> updateOriginAccessControl(service, id, protocol, behavior, originType));
        assertEquals("Cannot update origin type when associated to origins.", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void lambdaEdgeOriginTriggersAreRefusedInFrontOfAnAlwaysAmzAuthOrSigV4aOrigin() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String regional = "abcdefghijklmnopqrstuvwxyz.001.lambda-web.us-east-1.on.aws";
        String amzAuth = oac(service, "sigv4", "always-amz-auth", "lambda");
        String message = "Lambda@Edge origin triggers are incompatible with SigV4a and always-amz-auth origins."
                + " Use a different origin or disassociate the function.";

        for (String eventType : List.of("origin-request", "origin-response")) {
            AwsException error = assertAws("InvalidLambdaFunctionAssociation", () -> service.createDistribution(
                    distributionWithOrigin(regional, amzAuth, eventType), Map.of()));
            assertEquals(message, error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
        Distribution onABehavior = distributionWithOrigin(regional, amzAuth, null);
        CacheBehavior behavior = new CacheBehavior();
        behavior.setPathPattern("/api/*");
        behavior.setTargetOriginId("o");
        behavior.setLambdaFunctionAssociations(List.of(Map.<String, Object>of("LambdaFunctionARN",
                "arn:aws:lambda:us-east-1:000000000000:function:edge:1", "EventType", "origin-request")));
        onABehavior.getConfig().setCacheBehaviors(List.of(behavior));
        assertEquals(message, assertAws("InvalidLambdaFunctionAssociation",
                () -> service.createDistribution(onABehavior, Map.of())).getMessage());
        Distribution sigv4aS3 = distributionWithOrigin(
                "bucket.s3.us-east-1.amazonaws.com", oac(service, "sigv4a", "always", "s3"), "origin-request");
        assertEquals(message, assertAws("InvalidLambdaFunctionAssociation",
                () -> service.createDistribution(sigv4aS3, Map.of())).getMessage());

        assertDoesNotThrow(() -> service.createDistribution(
                distributionWithOrigin(regional, amzAuth, "viewer-request"), Map.of()));
        assertDoesNotThrow(() -> service.createDistribution(distributionWithOrigin(
                "bucket.s3.us-east-1.amazonaws.com", oac(service, "sigv4", "always", "s3"), "origin-request"), Map.of()));
    }

    @Test
    void validatesOriginAccessControlConfigurationOnUpdate() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        OriginAccessControl existing =
                service.createOriginAccessControl(originAccessControl());
        OriginAccessControl invalid = originAccessControl();
        invalid.setSigningBehavior(null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOriginAccessControl(
                        existing.getId(), existing.getEtag(), invalid));

        assertEquals("MalformedXML", error.getErrorCode());
        assertEquals("test-oac", service.getOriginAccessControl(existing.getId()).getName());
    }

    @Test
    void rejectsDeletingOriginAccessControlUsedByDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        OriginAccessControl oac = service.createOriginAccessControl(originAccessControl());
        Origin origin = new Origin();
        origin.setOriginAccessControlId(oac.getId());
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        service.createDistribution(distribution, Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteOriginAccessControl(oac.getId(), oac.getEtag()));

        assertEquals("OriginAccessControlInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(oac.getId(), service.getOriginAccessControl(oac.getId()).getId());
    }

    @Test
    void rejectsDeletingOriginAccessIdentityUsedByDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontOriginAccessIdentity oai = originAccessIdentity(service);
        Origin origin = new Origin();
        origin.setS3OriginConfig(Map.of(
                "OriginAccessIdentity",
                "origin-access-identity/cloudfront/" + oai.getId()));
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        service.createDistribution(distribution, Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteCloudFrontOriginAccessIdentity(
                        oai.getId(), oai.getEtag()));

        assertEquals("CloudFrontOriginAccessIdentityInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(oai.getId(),
                service.getCloudFrontOriginAccessIdentity(oai.getId()).getId());
    }

    @Test
    void rejectsDeletingOriginAccessIdentityUsedByStreamingDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontOriginAccessIdentity oai = originAccessIdentity(service);
        StreamingDistribution distribution = new StreamingDistribution();
        distribution.setS3OriginAccessIdentity(
                "/origin-access-identity/cloudfront/" + oai.getId());
        service.createStreamingDistribution(distribution);

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteCloudFrontOriginAccessIdentity(
                        oai.getId(), oai.getEtag()));

        assertEquals("CloudFrontOriginAccessIdentityInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
    }

    @Test
    void validatesCloudFrontSignerKeyTypes() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        PublicKey rsaKey = publicKey(
                "rsa-key", pem(rsa.generateKeyPair().getPublic()));
        assertEquals(
                rsaKey.getName(),
                service.createPublicKey(rsaKey).getName());

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        PublicKey ecKey = publicKey(
                "ec-key", pem(ec.generateKeyPair().getPublic()));
        assertEquals(
                ecKey.getName(),
                service.createPublicKey(ecKey).getName());

        KeyPairGenerator shortRsa = KeyPairGenerator.getInstance("RSA");
        shortRsa.initialize(1024);
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createPublicKey(publicKey(
                        "short-rsa",
                        pem(shortRsa.generateKeyPair().getPublic()))));
        assertEquals("InvalidArgument", error.getErrorCode());
    }

    @Test
    void rejectsDuplicatePublicKeyCallerReferences() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        PublicKey existing =
                service.createPublicKey(validPublicKey("existing"));
        PublicKey duplicate = validPublicKey("duplicate");
        duplicate.setCallerReference(existing.getCallerReference());

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createPublicKey(duplicate));

        assertEquals("PublicKeyAlreadyExists", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(1, service.listPublicKeys(null, 100).size());
    }

    @Test
    void validatesKeyGroupMembership() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        KeyGroup empty = new KeyGroup();
        empty.setName("empty");
        empty.setItems(List.of());

        AwsException emptyError = assertThrows(
                AwsException.class, () -> service.createKeyGroup(empty));
        assertEquals("InvalidArgument", emptyError.getErrorCode());

        KeyGroup missing = new KeyGroup();
        missing.setName("missing");
        missing.setItems(List.of("missing-public-key"));
        AwsException missingError = assertThrows(
                AwsException.class, () -> service.createKeyGroup(missing));
        assertEquals("InvalidArgument", missingError.getErrorCode());

        PublicKey key = service.createPublicKey(validPublicKey("member"));
        KeyGroup valid = keyGroup("valid", key.getId());
        assertEquals(
                List.of(key.getId()),
                service.createKeyGroup(valid).getItems());
    }

    @Test
    void rejectsKeyGroupsWithMoreThanFivePublicKeys() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        KeyGroup overQuota = new KeyGroup();
        overQuota.setName("over-quota");
        overQuota.setItems(List.of(
                "key-1", "key-2", "key-3", "key-4", "key-5", "key-6"));

        AwsException createError = assertThrows(
                AwsException.class,
                () -> service.createKeyGroup(overQuota));
        assertEquals(
                "TooManyPublicKeysInKeyGroup",
                createError.getErrorCode());
        assertEquals(400, createError.getHttpStatus());

        PublicKey key = service.createPublicKey(validPublicKey("member"));
        KeyGroup existing =
                service.createKeyGroup(keyGroup("existing", key.getId()));
        AwsException updateError = assertThrows(
                AwsException.class,
                () -> service.updateKeyGroup(
                        existing.getId(), existing.getEtag(), overQuota));
        assertEquals(
                "TooManyPublicKeysInKeyGroup",
                updateError.getErrorCode());
        assertEquals(400, updateError.getHttpStatus());
    }

    @Test
    void rejectsDuplicateKeyGroupNamesOnCreateAndUpdate()
            throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        PublicKey key = service.createPublicKey(validPublicKey("member"));
        KeyGroup first =
                service.createKeyGroup(keyGroup("existing", key.getId()));

        AwsException createError = assertThrows(
                AwsException.class,
                () -> service.createKeyGroup(
                        keyGroup("existing", key.getId())));
        assertEquals("KeyGroupAlreadyExists", createError.getErrorCode());
        assertEquals(409, createError.getHttpStatus());

        KeyGroup second =
                service.createKeyGroup(keyGroup("other", key.getId()));
        KeyGroup renamed = keyGroup("existing", key.getId());
        AwsException updateError = assertThrows(
                AwsException.class,
                () -> service.updateKeyGroup(
                        second.getId(), second.getEtag(), renamed));
        assertEquals("KeyGroupAlreadyExists", updateError.getErrorCode());
        assertEquals(409, updateError.getHttpStatus());

        assertEquals(
                "existing",
                service.updateKeyGroup(
                                first.getId(),
                                first.getEtag(),
                                keyGroup("existing", key.getId()))
                        .getName());
    }

    @Test
    void returnsPreconditionFailedForStaleSignerResourceEtags()
            throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        PublicKey key = service.createPublicKey(validPublicKey("stale"));
        KeyGroup group =
                service.createKeyGroup(keyGroup("stale", key.getId()));

        AwsException updatePublicKeyError = assertThrows(
                AwsException.class,
                () -> service.updatePublicKey(
                        key.getId(), "stale-etag", key));
        assertEquals(
                "PreconditionFailed",
                updatePublicKeyError.getErrorCode());
        assertEquals(412, updatePublicKeyError.getHttpStatus());

        AwsException deletePublicKeyError = assertThrows(
                AwsException.class,
                () -> service.deletePublicKey(
                        key.getId(), "stale-etag"));
        assertEquals(
                "PreconditionFailed",
                deletePublicKeyError.getErrorCode());
        assertEquals(412, deletePublicKeyError.getHttpStatus());

        AwsException updateKeyGroupError = assertThrows(
                AwsException.class,
                () -> service.updateKeyGroup(
                        group.getId(), "stale-etag", group));
        assertEquals(
                "PreconditionFailed",
                updateKeyGroupError.getErrorCode());
        assertEquals(412, updateKeyGroupError.getHttpStatus());

        AwsException deleteKeyGroupError = assertThrows(
                AwsException.class,
                () -> service.deleteKeyGroup(
                        group.getId(), "stale-etag"));
        assertEquals(
                "PreconditionFailed",
                deleteKeyGroupError.getErrorCode());
        assertEquals(412, deleteKeyGroupError.getHttpStatus());
    }

    @Test
    void validatesTrustedKeyGroupsOnDistributionCreateAndUpdate()
            throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        PublicKey key = service.createPublicKey(validPublicKey("trusted"));
        KeyGroup group =
                service.createKeyGroup(keyGroup("trusted", key.getId()));

        Distribution valid = distributionWithTrustedGroup(group.getId());
        assertEquals(
                group.getId(),
                service.createDistribution(valid, Map.of())
                        .getConfig()
                        .getDefaultCacheBehavior()
                        .getTrustedKeyGroups()
                        .getFirst());

        AwsException missingError = assertThrows(
                AwsException.class,
                () -> service.createDistribution(
                        distributionWithTrustedGroup("missing-group"),
                        Map.of()));
        assertEquals(
                "TrustedKeyGroupDoesNotExist",
                missingError.getErrorCode());

        DistributionConfig emptyConfig = new DistributionConfig();
        DefaultCacheBehavior emptyBehavior = new DefaultCacheBehavior();
        emptyBehavior.setTrustedKeyGroupsEnabled(true);
        emptyConfig.setDefaultCacheBehavior(emptyBehavior);
        Distribution empty = new Distribution();
        empty.setConfig(emptyConfig);
        AwsException emptyError = assertThrows(
                AwsException.class,
                () -> service.createDistribution(empty, Map.of()));
        assertEquals("InvalidArgument", emptyError.getErrorCode());

        Distribution existing =
                service.createDistribution(distribution(false, List.of()), Map.of());
        AwsException updateError = assertThrows(
                AwsException.class,
                () -> service.updateDistribution(
                        existing.getId(),
                        existing.getEtag(),
                        distributionWithTrustedGroup("missing-group")));
        assertEquals(
                "TrustedKeyGroupDoesNotExist",
                updateError.getErrorCode());
        assertEquals(
                existing.getEtag(),
                service.getDistribution(existing.getId()).getEtag());
    }

    @Test
    void rejectsDeletingSignerResourcesThatAreInUse() throws Exception {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        PublicKey key = service.createPublicKey(validPublicKey("in-use"));
        KeyGroup group =
                service.createKeyGroup(keyGroup("in-use", key.getId()));
        service.createDistribution(
                distributionWithTrustedGroup(group.getId()), Map.of());

        AwsException publicKeyError = assertThrows(
                AwsException.class,
                () -> service.deletePublicKey(key.getId(), key.getEtag()));
        assertEquals("PublicKeyInUse", publicKeyError.getErrorCode());
        assertEquals(409, publicKeyError.getHttpStatus());

        AwsException keyGroupError = assertThrows(
                AwsException.class,
                () -> service.deleteKeyGroup(group.getId(), group.getEtag()));
        assertEquals("ResourceInUse", keyGroupError.getErrorCode());
        assertEquals(409, keyGroupError.getHttpStatus());
    }

    @Test
    void rejectsInvalidOriginCustomHeaderNamesAndValues() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        List<List<Map<String, String>>> invalidHeaders = List.of(
                List.of(header("X-Duplicate", "one"), header("x-duplicate", "two")),
                List.of(header("X-Amz-Reserved", "value")),
                List.of(header("X-Edge-Reserved", "value")),
                List.of(header("Bad Header", "value")),
                List.of(header("", "value")),
                List.of(header("X-Missing-Value", null)),
                List.of(header("X-Injected", "value\r\nInjected: true")),
                List.of(header("X".repeat(257), "value")),
                List.of(header("X-Too-Long", "v".repeat(1_784))),
                List.of(
                        header("X-Quota-1", "v".repeat(1_783)),
                        header("X-Quota-2", "v".repeat(1_783)),
                        header("X-Quota-3", "v".repeat(1_783)),
                        header("X-Quota-4", "v".repeat(1_783)),
                        header("X-Quota-5", "v".repeat(1_783)),
                        header("X-Quota-6", "v".repeat(1_783))));

        for (int i = 0; i < invalidHeaders.size(); i++) {
            Distribution candidate = distribution(false, List.of());
            Origin origin = new Origin();
            origin.setCustomHeaders(invalidHeaders.get(i));
            candidate.getConfig().setOrigins(List.of(origin));

            AwsException error = assertThrows(
                    AwsException.class,
                    () -> service.createDistribution(candidate, Map.of()),
                    "validation case " + i);
            assertEquals("InvalidArgument", error.getErrorCode(), "validation case " + i);
            assertEquals(400, error.getHttpStatus(), "validation case " + i);
        }
    }

    private static Map<String, String> header(String name, String value) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("HeaderName", name);
        header.put("HeaderValue", value);
        return header;
    }

    @Test
    void exposesCanonicalManagedResponseHeadersPolicies() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        ResponseHeadersPolicy simple = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        assertEquals("Managed-SimpleCORS", simple.getName());
        assertEquals("Allows all origins for simple CORS requests", simple.getComment());
        assertEquals(Instant.EPOCH, simple.getLastModifiedTime());
        assertEquals("E23ZP02F085DFQ", simple.getEtag());
        Map<String, String> simpleHeaders = policyHeaders(simple, false);
        assertEquals(Map.of("Access-Control-Allow-Origin", "*"), simpleHeaders);

        ResponseHeadersPolicy preflight = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_CORS_PREFLIGHT_POLICY_ID);
        Map<String, String> preflightHeaders = policyHeaders(preflight, true);
        assertEquals("GET, HEAD, PUT, POST, PATCH, DELETE, OPTIONS",
                preflightHeaders.get("Access-Control-Allow-Methods"));
        assertEquals("*", preflightHeaders.get("Access-Control-Expose-Headers"));
        assertFalse(preflightHeaders.containsKey("Access-Control-Allow-Headers"));
        assertFalse(preflightHeaders.containsKey("Access-Control-Max-Age"));

        assertEquals(5, service.listResponseHeadersPolicies(null, 100, "managed").size());
        assertTrue(service.listResponseHeadersPolicies(null, 100, "custom").isEmpty());
        assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies(null, 100, "unsupported"));
        assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies(null, 100, "MANAGED"));
        assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies("missing-marker", 100, "managed"));
    }

    @Test
    void validatesResponseHeadersPolicyReferencesBeforeDistributionMutation() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution invalidDefault = distributionWithPolicy("missing-default-policy");

        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.createDistribution(invalidDefault, Map.of()));
        assertNull(invalidDefault.getId());
        assertTrue(service.listDistributions(null, 100).isEmpty());

        Distribution valid = distributionWithPolicy(CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        valid = service.createDistribution(valid, Map.of());
        Distribution invalidUpdate = distributionWithPolicy(null);
        CacheBehavior ordered = new CacheBehavior();
        ordered.setResponseHeadersPolicyId("missing-ordered-policy");
        invalidUpdate.getConfig().setCacheBehaviors(List.of(ordered));
        Distribution stored = valid;

        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.updateDistribution(stored.getId(), stored.getEtag(), invalidUpdate));
        assertEquals(CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID,
                service.getDistribution(stored.getId()).getConfig()
                        .getDefaultCacheBehavior().getResponseHeadersPolicyId());
    }

    @Test
    void enforcesPolicyNameUniquenessAndManagedPolicyImmutability() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy first = service.createResponseHeadersPolicy(customPolicy("unique-name"));

        assertAws("ResponseHeadersPolicyAlreadyExists",
                () -> service.createResponseHeadersPolicy(customPolicy("unique-name")));
        assertAws("ResponseHeadersPolicyAlreadyExists",
                () -> service.createResponseHeadersPolicy(customPolicy("Managed-SimpleCORS")));

        ResponseHeadersPolicy managed = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        assertAws("IllegalUpdate", () -> service.updateResponseHeadersPolicy(
                managed.getId(), managed.getEtag(), customPolicy("replacement")));
        assertAws("IllegalDelete", () -> service.deleteResponseHeadersPolicy(
                managed.getId(), managed.getEtag()));

        ResponseHeadersPolicy second = service.createResponseHeadersPolicy(customPolicy("second-name"));
        assertAws("ResponseHeadersPolicyAlreadyExists", () -> service.updateResponseHeadersPolicy(
                second.getId(), second.getEtag(), customPolicy(first.getName())));
    }

    @Test
    void preventsDeletingAReferencedResponseHeadersPolicy() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy policy = service.createResponseHeadersPolicy(customPolicy("in-use-policy"));
        Distribution distribution = service.createDistribution(
                distributionWithPolicy(policy.getId()), Map.of());

        assertAws("ResponseHeadersPolicyInUse",
                () -> service.deleteResponseHeadersPolicy(policy.getId(), policy.getEtag()));

        Distribution detached = distributionWithPolicy(null);
        service.updateDistribution(distribution.getId(), distribution.getEtag(), detached);
        service.deleteResponseHeadersPolicy(policy.getId(), policy.getEtag());
        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.getResponseHeadersPolicy(policy.getId()));
    }

    @Test
    void enforcesCustomPolicyQuotaAndStaleEtagPreconditions() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        for (int index = 0; index < 20; index++) {
            service.createResponseHeadersPolicy(customPolicy("quota-policy-" + index));
        }
        assertAws("TooManyResponseHeadersPolicies",
                () -> service.createResponseHeadersPolicy(customPolicy("quota-policy-overflow")));

        CloudFrontService etagService = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy policy = etagService.createResponseHeadersPolicy(
                customPolicy("etag-policy"));
        AwsException update = assertAws("PreconditionFailed",
                () -> etagService.updateResponseHeadersPolicy(
                        policy.getId(), "stale-etag", customPolicy("etag-policy")));
        assertEquals(412, update.getHttpStatus());
        AwsException delete = assertAws("PreconditionFailed",
                () -> etagService.deleteResponseHeadersPolicy(policy.getId(), "stale-etag"));
        assertEquals(412, delete.getHttpStatus());
    }

    @Test
    void limitsResponseHeadersPolicyToOneHundredDistributions() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy policy = service.createResponseHeadersPolicy(
                customPolicy("association-quota"));
        for (int index = 0; index < 100; index++) {
            service.createDistribution(
                    distributionWithPolicy(policy.getId()), Map.of());
        }

        AwsException overflow = assertAws(
                "TooManyDistributionsAssociatedToResponseHeadersPolicy",
                () -> service.createDistribution(
                        distributionWithPolicy(policy.getId()), Map.of()));
        assertEquals(400, overflow.getHttpStatus());

        Distribution existing = service.listDistributions(null, 100).get(0);
        assertDoesNotThrow(() -> service.updateDistribution(
                existing.getId(), existing.getEtag(),
                distributionWithPolicy(policy.getId())));
    }

    @Test
    void preventsDeletingACachePolicyAttachedToTheDefaultCacheBehavior() {
        // DeleteCachePolicy declares CachePolicyInUse and documents "You cannot delete a cache
        // policy if it's attached to a cache behavior"; without the check the distribution was
        // left pointing at a policy that no longer existed.
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CachePolicy policy = service.createCachePolicy(namedCachePolicy("in-use-cache-policy"));
        Distribution distribution = service.createDistribution(
                distributionWithCachePolicy(policy.getId(), null), Map.of());

        assertAws("CachePolicyInUse", () -> service.deleteCachePolicy(policy.getId(), policy.getEtag()));

        Distribution detached = distributionWithCachePolicy(null, null);
        service.updateDistribution(distribution.getId(), distribution.getEtag(), detached);
        service.deleteCachePolicy(policy.getId(), policy.getEtag());
        assertAws("NoSuchCachePolicy", () -> service.getCachePolicy(policy.getId()));
    }

    @Test
    void preventsDeletingACachePolicyAttachedToAnOrderedCacheBehavior() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CachePolicy policy = service.createCachePolicy(namedCachePolicy("ordered-cache-policy"));
        service.createDistribution(distributionWithCachePolicy(null, policy.getId()), Map.of());

        assertAws("CachePolicyInUse", () -> service.deleteCachePolicy(policy.getId(), policy.getEtag()));
    }

    @Test
    void preventsDeletingAnOriginRequestPolicyInUse() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        OriginRequestPolicy policy =
                service.createOriginRequestPolicy(namedOriginRequestPolicy("in-use-orp"));
        Distribution distribution = service.createDistribution(
                distributionWithOriginRequestPolicy(policy.getId()), Map.of());

        assertAws("OriginRequestPolicyInUse",
                () -> service.deleteOriginRequestPolicy(policy.getId(), policy.getEtag()));

        Distribution detached = distributionWithOriginRequestPolicy(null);
        service.updateDistribution(distribution.getId(), distribution.getEtag(), detached);
        service.deleteOriginRequestPolicy(policy.getId(), policy.getEtag());
        assertAws("NoSuchOriginRequestPolicy", () -> service.getOriginRequestPolicy(policy.getId()));
    }

    @Test
    void exposesTheAwsManagedOriginRequestPoliciesAsImmutable() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        OriginRequestPolicy exceptHost = service.getOriginRequestPolicy(
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);
        assertEquals("Managed-AllViewerExceptHostHeader", exceptHost.getName());
        assertEquals(Map.of("HeaderBehavior", "allExcept", "Headers", List.of("Host")),
                exceptHost.getConfig().get("HeadersConfig"));
        assertEquals(Map.of("CookieBehavior", "all"), exceptHost.getConfig().get("CookiesConfig"));
        assertEquals(Map.of("QueryStringBehavior", "all"),
                exceptHost.getConfig().get("QueryStringsConfig"));
        assertEquals(Map.of("HeaderBehavior", "allViewer"), service.getOriginRequestPolicy(
                CloudFrontService.MANAGED_ALL_VIEWER_ORIGIN_REQUEST_POLICY_ID)
                .getConfig().get("HeadersConfig"));
        assertEquals(Map.of("HeaderBehavior", "whitelist", "Headers", List.of("Host")),
                service.getOriginRequestPolicy(
                        CloudFrontService.MANAGED_HOST_HEADER_ONLY_ORIGIN_REQUEST_POLICY_ID)
                        .getConfig().get("HeadersConfig"));

        OriginRequestPolicy custom =
                service.createOriginRequestPolicy(namedOriginRequestPolicy("custom-orp"));
        assertEquals(8, service.listOriginRequestPolicies(null, 100, "managed").size());
        assertEquals(List.of(custom.getId()), service.listOriginRequestPolicies(null, 100, "custom")
                .stream().map(OriginRequestPolicy::getId).toList());
        assertEquals(9, service.listOriginRequestPolicies(null, 100, null).size());
        assertAws("InvalidArgument", () -> service.listOriginRequestPolicies(null, 100, "MANAGED"));

        assertAws("IllegalUpdate", () -> service.updateOriginRequestPolicy(exceptHost.getId(),
                exceptHost.getEtag(), namedOriginRequestPolicy("replacement")));
        assertAws("IllegalDelete",
                () -> service.deleteOriginRequestPolicy(exceptHost.getId(), exceptHost.getEtag()));
    }

    @Test
    void exposesTheSevenAwsManagedCachePolicies() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        List<String> originOverrideHeaders = List.of(
                "Host", "Origin", "X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override");
        Map<String, Object> noHeaders = Map.of("HeaderBehavior", "none");
        Map<String, Object> noCookies = Map.of("CookieBehavior", "none");
        Map<String, Object> allCookies = Map.of("CookieBehavior", "all");
        Map<String, Object> noQueryStrings = Map.of("QueryStringBehavior", "none");
        Map<String, Object> allQueryStrings = Map.of("QueryStringBehavior", "all");
        Map<String, Object> originOverrideHeaderSelection =
                Map.of("HeaderBehavior", "whitelist", "Headers", originOverrideHeaders);

        assertManagedCachePolicy(service, "2e54312d-136d-493c-8eb9-b001f22f67d2", "Managed-Amplify",
                "2", "2", "600", "true", "true",
                Map.of("HeaderBehavior", "whitelist", "Headers",
                        List.of("Authorization", "CloudFront-Viewer-Country", "Host")),
                allCookies, allQueryStrings);
        assertManagedCachePolicy(service, "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
                "Managed-CachingDisabled", "0", "0", "0", "false", "false",
                noHeaders, noCookies, noQueryStrings);
        assertManagedCachePolicy(service, "658327ea-f89d-4fab-a63d-7e88639e58f6",
                "Managed-CachingOptimized", "1", "86400", "31536000", "true", "true",
                noHeaders, noCookies, noQueryStrings);
        assertManagedCachePolicy(service, "b2884449-e4de-46a7-ac36-70bc7f1ddd6d",
                "Managed-CachingOptimizedForUncompressedObjects", "1", "86400", "31536000",
                "false", "false", noHeaders, noCookies, noQueryStrings);
        assertManagedCachePolicy(service, "08627262-05a9-4f76-9ded-b50ca2e3a84f",
                "Managed-Elemental-MediaPackage", "0", "86400", "31536000", "true", "false",
                Map.of("HeaderBehavior", "whitelist", "Headers", List.of("Origin")), noCookies,
                Map.of("QueryStringBehavior", "whitelist", "QueryStrings",
                        List.of("aws.manifestfilter", "start", "end", "m")));
        assertManagedCachePolicy(service, "83da9c7e-98b4-4e11-a168-04f0df8e2c65",
                "Managed-UseOriginCacheControlHeaders", "0", "0", "31536000", "true", "true",
                originOverrideHeaderSelection, allCookies, noQueryStrings);
        assertManagedCachePolicy(service, "4cc15a8a-d715-48a4-82b8-cc0b614638fe",
                "Managed-UseOriginCacheControlHeaders-QueryStrings", "0", "0", "31536000",
                "true", "true", originOverrideHeaderSelection, allCookies, allQueryStrings);

        assertAws("NoSuchCachePolicy",
                () -> service.getCachePolicy("4d1d2f1d-3a71-49ad-9e08-7ea5d843a556"));
    }

    @Test
    void listsManagedAndCustomCachePoliciesByType() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CachePolicy custom = service.createCachePolicy(namedCachePolicy("custom-cache-policy"));

        assertEquals(7, service.listCachePolicies(null, 100, "managed").size());
        assertTrue(service.listCachePolicies(null, 100, "managed").stream()
                .allMatch(policy -> CloudFrontService.isManagedCachePolicy(policy.getId())));
        assertEquals(List.of(custom.getId()), service.listCachePolicies(null, 100, "custom")
                .stream().map(CachePolicy::getId).toList());
        assertEquals(8, service.listCachePolicies(null, 100, null).size());
        assertEquals(8, service.listCachePolicies(null, 100).size());
        assertAws("InvalidArgument", () -> service.listCachePolicies(null, 100, "MANAGED"));
    }

    @Test
    void listCachePolicies_badType_rejectsWithCachePolicyMessage() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException error = assertAws("InvalidArgument",
                () -> service.listCachePolicies(null, 100, "unsupported"));

        assertEquals("Cache policy Type must be managed or custom.", error.getMessage());
    }

    @Test
    void listOriginRequestPolicies_badType_rejectsWithOriginRequestPolicyMessage() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException error = assertAws("InvalidArgument",
                () -> service.listOriginRequestPolicies(null, 100, "unsupported"));

        assertEquals("Origin request policy Type must be managed or custom.", error.getMessage());
    }

    @Test
    void listResponseHeadersPolicies_badTypeAndUnknownMarker_rejectWithOwnMessages() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException badType = assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies(null, 100, "unsupported"));
        AwsException badMarker = assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies("missing-marker", 100, null));

        assertEquals("Response headers policy Type must be managed or custom.", badType.getMessage());
        assertEquals("The specified marker is invalid.", badMarker.getMessage());
    }

    @Test
    void refusesToUpdateOrDeleteAManagedCachePolicy() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CachePolicy managed = service.getCachePolicy("658327ea-f89d-4fab-a63d-7e88639e58f6");

        assertAws("IllegalUpdate", () -> service.updateCachePolicy(managed.getId(),
                managed.getEtag(), namedCachePolicy("replacement")));
        assertAws("IllegalDelete",
                () -> service.deleteCachePolicy(managed.getId(), managed.getEtag()));

        CachePolicy after = service.getCachePolicy(managed.getId());
        assertEquals("Managed-CachingOptimized", after.getName());
        assertEquals(managed.getEtag(), after.getEtag());
    }

    private static void assertManagedCachePolicy(
            CloudFrontService service, String id, String name, String minTtl, String defaultTtl,
            String maxTtl, String gzip, String brotli, Map<String, Object> headers,
            Map<String, Object> cookies, Map<String, Object> queryStrings) {
        CachePolicy policy = service.getCachePolicy(id);
        assertEquals(id, policy.getId());
        assertEquals(name, policy.getName());
        Map<String, Object> config = policy.getConfig();
        assertEquals(minTtl, config.get("MinTTL"), name + " MinTTL");
        assertEquals(defaultTtl, config.get("DefaultTTL"), name + " DefaultTTL");
        assertEquals(maxTtl, config.get("MaxTTL"), name + " MaxTTL");
        Map<?, ?> parameters = (Map<?, ?>) config.get("ParametersInCacheKeyAndForwardedToOrigin");
        assertEquals(gzip, parameters.get("EnableAcceptEncodingGzip"), name + " Gzip");
        assertEquals(brotli, parameters.get("EnableAcceptEncodingBrotli"), name + " Brotli");
        assertEquals(headers, parameters.get("HeadersConfig"), name + " headers");
        assertEquals(cookies, parameters.get("CookiesConfig"), name + " cookies");
        assertEquals(queryStrings, parameters.get("QueryStringsConfig"), name + " query strings");
    }

    @Test
    void deletesAnUnattachedCachePolicy() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CachePolicy policy = service.createCachePolicy(namedCachePolicy("free-cache-policy"));

        service.deleteCachePolicy(policy.getId(), policy.getEtag());

        assertAws("NoSuchCachePolicy", () -> service.getCachePolicy(policy.getId()));
    }

    private static CachePolicy namedCachePolicy(String name) {
        CachePolicy policy = new CachePolicy();
        policy.setName(name);
        policy.setConfig(Map.of());
        return policy;
    }

    private static OriginRequestPolicy namedOriginRequestPolicy(String name) {
        OriginRequestPolicy policy = new OriginRequestPolicy();
        policy.setName(name);
        policy.setConfig(Map.of());
        return policy;
    }

    private static Distribution distributionWithCachePolicy(String defaultPolicyId, String orderedPolicyId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setCachePolicyId(defaultPolicyId);
        DistributionConfig config = new DistributionConfig();
        config.setDefaultCacheBehavior(behavior);
        if (orderedPolicyId != null) {
            CacheBehavior ordered = new CacheBehavior();
            ordered.setCachePolicyId(orderedPolicyId);
            config.setCacheBehaviors(List.of(ordered));
        }
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static Distribution distributionWithOriginRequestPolicy(String policyId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setOriginRequestPolicyId(policyId);
        DistributionConfig config = new DistributionConfig();
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static Distribution distributionWithPolicy(String policyId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setResponseHeadersPolicyId(policyId);
        DistributionConfig config = new DistributionConfig();
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static ResponseHeadersPolicy customPolicy(String name) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(name);
        policy.setConfig(Map.of());
        return policy;
    }

    private static Map<String, String> policyHeaders(
            ResponseHeadersPolicy policy, boolean preflight) {
        return ResponseHeadersPolicyConfigCodec.directives(
                        policy.getConfig(), "https://viewer.example", preflight).add().stream()
                .collect(Collectors.toMap(
                        ResponseHeadersPolicyConfigCodec.PolicyHeader::name,
                        ResponseHeadersPolicyConfigCodec.PolicyHeader::value,
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private static AwsException assertAws(
            String code, Executable action) {
        AwsException error = assertThrows(AwsException.class, action);
        assertEquals(code, error.getErrorCode());
        return error;
    }

    private static Distribution distribution(boolean enabled, List<String> aliases) {
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(enabled);
        config.setAliases(aliases);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static OriginAccessControl originAccessControl() {
        return originAccessControl("sigv4", "always", "s3");
    }

    private static OriginAccessControl originAccessControl(String protocol, String behavior, String originType) {
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("test-oac");
        oac.setSigningProtocol(protocol);
        oac.setSigningBehavior(behavior);
        oac.setOriginAccessControlOriginType(originType);
        return oac;
    }

    private static String oac(CloudFrontService service, String protocol, String behavior, String originType) {
        return service.createOriginAccessControl(originAccessControl(protocol, behavior, originType)).getId();
    }

    /** A distribution whose only origin {@code o} is {@code domainName}, with an optional Lambda@Edge trigger. */
    private static Distribution distributionWithOrigin(String domainName, String oacId, String edgeEventType) {
        Origin origin = new Origin();
        origin.setId("o");
        origin.setDomainName(domainName);
        origin.setOriginAccessControlId(oacId);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("o");
        if (edgeEventType != null) {
            behavior.setLambdaFunctionAssociations(List.of(Map.<String, Object>of("LambdaFunctionARN",
                    "arn:aws:lambda:us-east-1:000000000000:function:edge:1", "EventType", edgeEventType)));
        }
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        distribution.getConfig().setDefaultCacheBehavior(behavior);
        return distribution;
    }

    /** A distribution with one custom (HTTP/HTTPS) origin, customized through {@code originCustomizer}. */
    private static Distribution distributionWithCustomOrigin(Consumer<Origin> originCustomizer) {
        Origin origin = new Origin();
        origin.setId("o");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "https-only");
        origin.setCustomOriginConfig(customOriginConfig);
        originCustomizer.accept(origin);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("o");
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        distribution.getConfig().setDefaultCacheBehavior(behavior);
        return distribution;
    }

    private static void assertOriginAccessControlRefused(CloudFrontService service, String domainName, String protocol,
                                                         String behavior, String originType, String reason) {
        Distribution distribution = distributionWithOrigin(domainName, oac(service, protocol, behavior, originType), null);
        AwsException error = assertAws("IllegalOriginAccessConfiguration",
                () -> service.createDistribution(distribution, Map.of()));
        assertEquals("Illegal configuration: " + reason, error.getMessage(), domainName);
        assertEquals(400, error.getHttpStatus());
    }

    private static PublicKey validPublicKey(String name) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return publicKey(name, pem(generator.generateKeyPair().getPublic()));
    }

    private static PublicKey publicKey(String name, String encodedKey) {
        PublicKey key = new PublicKey();
        key.setCallerReference("reference-" + name);
        key.setName(name);
        key.setEncodedKey(encodedKey);
        return key;
    }

    private static String pem(java.security.PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static KeyGroup keyGroup(String name, String publicKeyId) {
        KeyGroup group = new KeyGroup();
        group.setName(name);
        group.setItems(List.of(publicKeyId));
        return group;
    }

    private static Distribution distributionWithTrustedGroup(
            String keyGroupId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTrustedKeyGroupsEnabled(true);
        behavior.setTrustedKeyGroups(List.of(keyGroupId));
        DistributionConfig config = new DistributionConfig();
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static CloudFrontOriginAccessIdentity originAccessIdentity(
            CloudFrontService service) {
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference("test-reference");
        return service.createCloudFrontOriginAccessIdentity(oai);
    }

    private static void assertInvalidOriginAccessControl(
            CloudFrontService service, OriginAccessControl oac, String errorCode, String message) {
        AwsException error = assertAws(errorCode, () -> service.createOriginAccessControl(oac));
        assertEquals(message, error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createDistributionIncludesEmptyOriginGroupsAndRestrictions() {
        CloudFrontController controller =
                new CloudFrontController(serviceWithDomainSuffix(DEFAULT_DOMAIN_SUFFIX));

        Response response = controller.createDistribution(null, DISTRIBUTION_CONFIG_XML);
        String xml = response.getEntity().toString();
        String distributionId = XmlParser.extractFirst(xml, ID_ELEMENT, null);

        assertIncludesTerraformRequiredDistributionConfig(xml);

        Response getResponse = controller.getDistribution(distributionId);
        assertIncludesTerraformRequiredDistributionConfig(getResponse.getEntity().toString());

        Response listResponse = controller.listDistributions(null, LIST_MAX_ITEMS);
        assertIncludesTerraformRequiredDistributionConfig(listResponse.getEntity().toString());
    }

    @Test
    void createDistributionPreservesGeoRestrictionLocations() {
        CloudFrontController controller =
                new CloudFrontController(serviceWithDomainSuffix(DEFAULT_DOMAIN_SUFFIX));

        Response response =
                controller.createDistribution(null, GEO_RESTRICTED_DISTRIBUTION_CONFIG_XML);
        String xml = response.getEntity().toString();
        String distributionId = XmlParser.extractFirst(xml, ID_ELEMENT, null);

        assertIncludesGeoRestrictionLocations(xml);

        Response getResponse = controller.getDistribution(distributionId);
        assertIncludesGeoRestrictionLocations(getResponse.getEntity().toString());

        Response listResponse = controller.listDistributions(null, LIST_MAX_ITEMS);
        assertIncludesGeoRestrictionLocations(listResponse.getEntity().toString());
    }

    private void assertIncludesTerraformRequiredDistributionConfig(String xml) {
        assertTrue(xml.contains(EMPTY_ORIGIN_GROUPS_XML), xml);
        assertTrue(xml.contains(EMPTY_RESTRICTIONS_XML), xml);
    }

    private void assertIncludesGeoRestrictionLocations(String xml) {
        assertTrue(xml.contains(WHITELIST_RESTRICTIONS_XML), xml);
    }
}
