package io.github.hectorvent.floci.services.cloudformation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfnCreateOnlyPropertiesTest {

    @Test
    void isCreateOnly_matchesSchemaDefinedProperties() {
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "DBName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "ClusterIdentifier"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::EC2::Subnet", "CidrBlock"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::ApiGateway::DomainName", "DomainName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "QueueName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "FifoQueue"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::S3::Bucket", "BucketName"));
    }

    @Test
    void isCreateOnly_returnsFalseForMutableProperties() {
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::SQS::Queue", "VisibilityTimeout"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Redshift::Cluster", "NumberOfNodes"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::S3::Bucket", "VersioningConfiguration"));
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
