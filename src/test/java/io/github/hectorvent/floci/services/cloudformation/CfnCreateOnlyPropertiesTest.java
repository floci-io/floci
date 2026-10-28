package io.github.hectorvent.floci.services.cloudformation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfnCreateOnlyPropertiesTest {

    @Test
    void s3VectorsCreateOnlyPropertiesMatchTheRegistrySchemas() {
        assertEquals(Set.of("DataType", "Dimension", "DistanceMetric", "EncryptionConfiguration", "IndexName",
                        "MetadataConfiguration", "VectorBucketArn", "VectorBucketName"),
                CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::S3Vectors::Index"));
        assertEquals(Set.of("VectorBucketName", "EncryptionConfiguration"),
                CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::S3Vectors::VectorBucket"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::S3Vectors::Index", "Dimension"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::S3Vectors::VectorBucket", "EncryptionConfiguration"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::S3Vectors::Index", "Tags"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::S3Vectors::VectorBucket", "Tags"));
    }

    @Test
    void isCreateOnly_matchesSchemaDefinedProperties() {
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "DBName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "ClusterIdentifier"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::EC2::Subnet", "CidrBlock"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGateway::DomainName", "DomainName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "QueueName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "FifoQueue"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::S3::Bucket", "BucketName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::IoT::Authorizer", "SigningDisabled"));
    }

    @Test
    void isCreateOnly_returnsFalseForMutableProperties() {
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "VisibilityTimeout"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "NumberOfNodes"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::S3::Bucket", "VersioningConfiguration"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::IoT::Authorizer", "Status"));
    }

    @Test
    void isCreateOnly_vpcLinkReplacesOnlyForSubnetsAndSecurityGroups() {
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGatewayV2::VpcLink", "SubnetIds"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGatewayV2::VpcLink", "SecurityGroupIds"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGatewayV2::VpcLink", "Name"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGatewayV2::VpcLink", "Tags"));
    }

    @Test
    void isCreateOnly_serverlessCacheReplacesOnItsSchemaProperties() {
        String type = "AWS::ElastiCache::ServerlessCache";
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "ServerlessCacheName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "SubnetIds"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "KmsKeyId"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "SnapshotArnsToRestore"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "ConnectionType"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "NetworkType"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "SecurityGroupIds"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Description"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Tags"));
    }

    @Test
    void isCreateOnly_ipSetReplacesOnlyForNameAndScope() {
        String type = "AWS::WAFv2::IPSet";
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "Name"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "Scope"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Addresses"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Description"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "IPAddressVersion"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Tags"));
    }

    @Test
    void isCreateOnly_loggingConfigurationReplacesOnlyForResourceArn() {
        String type = "AWS::WAFv2::LoggingConfiguration";
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "ResourceArn"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "LogDestinationConfigs"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "RedactedFields"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "LoggingFilter"));
    }

    @Test
    void isCreateOnly_recordSetGroupReplacesOnlyForItsHostedZone() {
        String type = "AWS::Route53::RecordSetGroup";
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "HostedZoneId"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(type, "HostedZoneName"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "RecordSets"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(type, "Comment"));
    }

    @Test
    void isCreateOnly_treatsEmptySchemaListAsAuthoritative() {
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGateway::RestApi", "Name"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Cognito::UserPool", "UserPoolName"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGateway::UsagePlan", "UsagePlanName"));
    }

    @Test
    void isCreateOnly_fallsBackToHeuristicForCustomOrUnknownTypes() {
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("Custom::Widget", "WidgetName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("Custom::Widget", "Name"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("Custom::Widget", "ClusterIdentifier"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("Custom::Widget", "Description"));
    }

    @Test
    void getCreateOnlyProperties_returnsExpectedSet() {
        Set<String> redshiftProps = CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::Redshift::Cluster");
        assertTrue(redshiftProps.contains("DBName"));
        assertTrue(redshiftProps.contains("ClusterIdentifier"));

        Set<String> unknownProps = CfnCreateOnlyProperties.getCreateOnlyProperties("Unknown::Type");
        assertTrue(unknownProps.isEmpty());
    }
}
