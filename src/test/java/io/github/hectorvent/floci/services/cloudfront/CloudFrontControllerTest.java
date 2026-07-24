package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontControllerTest {

    private static final String ACCOUNT = "000000000000";
    private static final String DOMAIN_SUFFIX = "cloudfront.net";
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

    private void assertIncludesTerraformRequiredDistributionConfig(String xml) {
        assertTrue(xml.contains(EMPTY_ORIGIN_GROUPS_XML), xml);
        assertTrue(xml.contains(EMPTY_RESTRICTIONS_XML), xml);
    }

    private CloudFrontService serviceWithDomainSuffix() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(DOMAIN_SUFFIX);

        return new CloudFrontService(storageFactory, config);
    }
}
