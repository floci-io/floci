package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String DOMAIN_SUFFIX = "cloudfront.net";
    private static final String CONFIGURED_DOMAIN_SUFFIX = "cloudfront.local";
    private static final String DOMAIN_SEPARATOR = ".";
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

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix(DOMAIN_SUFFIX);

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(DOMAIN_SEPARATOR + DOMAIN_SUFFIX),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix(CONFIGURED_DOMAIN_SUFFIX);

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(DOMAIN_SEPARATOR + CONFIGURED_DOMAIN_SUFFIX),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix(CONFIGURED_DOMAIN_SUFFIX);

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(DOMAIN_SEPARATOR + CONFIGURED_DOMAIN_SUFFIX),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    @Test
    void createDistributionIncludesEmptyOriginGroupsAndRestrictions() {
        CloudFrontController controller = new CloudFrontController(serviceWithDomainSuffix());

        Response response = controller.createDistribution(null, DISTRIBUTION_CONFIG_XML);
        String xml = response.getEntity().toString();
        String distributionId = XmlParser.extractFirst(xml, "Id", null);

        assertIncludesTerraformRequiredDistributionConfig(xml);

        Response getResponse = controller.getDistribution(distributionId);
        assertIncludesTerraformRequiredDistributionConfig(getResponse.getEntity().toString());

        Response listResponse = controller.listDistributions(null, 100);
        assertIncludesTerraformRequiredDistributionConfig(listResponse.getEntity().toString());
    }

    @Test
    void createDistributionPreservesGeoRestrictionLocations() {
        CloudFrontController controller = new CloudFrontController(serviceWithDomainSuffix());

        Response response = controller.createDistribution(null, GEO_RESTRICTED_DISTRIBUTION_CONFIG_XML);
        String xml = response.getEntity().toString();
        String distributionId = XmlParser.extractFirst(xml, "Id", null);

        assertIncludesGeoRestrictionLocations(xml);

        Response getResponse = controller.getDistribution(distributionId);
        assertIncludesGeoRestrictionLocations(getResponse.getEntity().toString());

        Response listResponse = controller.listDistributions(null, 100);
        assertIncludesGeoRestrictionLocations(listResponse.getEntity().toString());
    }

    private void assertIncludesTerraformRequiredDistributionConfig(String xml) {
        assertTrue(xml.contains(EMPTY_ORIGIN_GROUPS_XML), xml);
        assertTrue(xml.contains(EMPTY_RESTRICTIONS_XML), xml);
    }

    private void assertIncludesGeoRestrictionLocations(String xml) {
        assertTrue(xml.contains(WHITELIST_RESTRICTIONS_XML), xml);
    }

    private CloudFrontService serviceWithDomainSuffix() {
        return serviceWithDomainSuffix(DOMAIN_SUFFIX);
    }

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(domainSuffix);

        return new CloudFrontService(storageFactory, config);
    }
}
