package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfnCreateOnlyPropertiesTest {

    private static final Path INVENTORY =
            Path.of("src/test/resources/cloudformation/supported-resource-types.tsv");

    /** Inventory types with no registry schema; they are the only ones allowed to have no entry. */
    private static final Set<String> TYPES_WITHOUT_SCHEMA =
            Set.of("AWS::CDK::Metadata", "Custom::DynamoDBReplica");

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
    void signingProfileReplacesForEverythingButTags() {
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Signer::SigningProfile", "ProfileName"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Signer::SigningProfile", "PlatformId"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Signer::SigningProfile", "SignatureValidityPeriod"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Signer::SigningProfile", "Tags"));
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
    void isCreateOnly_appConfigReplacesOnlyOnItsSchemaProperties() {
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::Application", "Name"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::Environment", "ApplicationId"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::Environment", "Name"));
        assertEquals(Set.of("ApplicationId", "LocationUri"),
                CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::AppConfig::ConfigurationProfile"));
        // Type is not create-only: the registry schema lists only ApplicationId and LocationUri, and the
        // AWS resource page gives Type "Update requires: No interruption".
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::ConfigurationProfile", "Type"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::ConfigurationProfile", "Name"));
        assertEquals(Set.of("Name", "ReplicateTo"),
                CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::AppConfig::DeploymentStrategy"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::AppConfig::DeploymentStrategy", "GrowthFactor"));
    }

    @Test
    void eventsV2CreateOnlyPropertiesMatchTheRegistrySchemas() {
        assertEquals(Set.of("Name"), CfnCreateOnlyProperties.getCreateOnlyProperties("AWS::EventsV2::EventBus"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::EventsV2::EventBus", "Description"));
        String subscriber = "AWS::EventsV2::Subscriber";
        assertEquals(Set.of("Name", "EventBusArn", "InvokeConfiguration/TargetArn", "Type", "StartingPosition",
                        "PointInTimeConfiguration"),
                CfnCreateOnlyProperties.getCreateOnlyProperties(subscriber));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(subscriber, "EventBusArn"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(subscriber, "Type"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly(subscriber, "InvokeConfiguration"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(subscriber, "SubscriberFilters"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly(subscriber, "RetryPolicy"));
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

    /**
     * Every type floci provisions has an entry in create-only-properties.json, taken from its
     * registry schema, so the name heuristic in {@code isCreateOnly} is never what answers for a
     * supported type. A type with no schema is listed above with its reason and has no entry.
     */
    @Test
    void everyInventoryTypeHasAnEntryExceptTheSchemalessOnes() throws IOException {
        Map<String, Set<String>> entries;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("cloudformation/create-only-properties.json")) {
            entries = new ObjectMapper().readValue(in, new TypeReference<Map<String, Set<String>>>() {});
        }
        Set<String> missing = new TreeSet<>();
        for (String line : Files.readAllLines(INVENTORY)) {
            if (line.isBlank()) {
                continue;
            }
            String type = line.split("\t", 2)[0];
            if (!entries.containsKey(type) && !TYPES_WITHOUT_SCHEMA.contains(type)) {
                missing.add(type);
            }
        }
        List<String> listedWithEntry = TYPES_WITHOUT_SCHEMA.stream().filter(entries::containsKey).sorted().toList();

        assertEquals(Set.of(), missing,
                "Inventory types with no entry in create-only-properties.json: run make cfn-create-only-sync.");
        assertEquals(List.of(), listedWithEntry,
                "Types listed as schemaless that do have an entry: remove them from the list.");
    }

    /**
     * Registry schemas (CloudformationSchema.zip, us-east-1): aws-events-archive.json lists
     * createOnlyProperties ArchiveName and SourceArn only, so KmsKeyIdentifier is updatable
     * (the name heuristic answered true for any name ending in Identifier), and SourceArn is
     * create-only although no heuristic rule matches it.
     */
    @Test
    void isCreateOnly_eventsArchiveFollowsTheRegistrySchema() {
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Events::Archive", "KmsKeyIdentifier"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Events::Archive", "SourceArn"));
        assertTrue(CfnCreateOnlyProperties.isCreateOnly("AWS::Events::Archive", "ArchiveName"));
        assertFalse(CfnCreateOnlyProperties.isCreateOnly("AWS::Events::Archive", "RetentionDays"));
    }
}
