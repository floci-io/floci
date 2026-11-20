package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CfnApiShapeConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ecsAliasesAndOpaqueVolumeOptionsKeepTheirApiShape() throws Exception {
        JsonNode properties = mapper.readTree("""
                {"EFSVolumeConfiguration":{"FilesystemId":"fs-1","TransitEncryptionPort":"2049",
                    "AuthorizationConfig":{"IAM":"ENABLED"}},
                 "DockerVolumeConfiguration":{"DriverOpts":{"MountType":"bind"},
                    "Labels":{"TeamName":"infra"}},
                 "FSxWindowsFileServerVolumeConfiguration":{"FileSystemId":"fsx-1"},
                 "S3FilesVolumeConfiguration":{"BucketName":"bucket"}}
                """);
        CfnApiShapeConverter.Rules rules = new CfnApiShapeConverter.Rules(
                Map.of("EFSVolumeConfiguration", "efsVolumeConfiguration",
                        "FSxWindowsFileServerVolumeConfiguration", "fsxWindowsFileServerVolumeConfiguration",
                        "S3FilesVolumeConfiguration", "s3filesVolumeConfiguration",
                        "FilesystemId", "fileSystemId", "IAM", "iam"),
                Set.of("DriverOpts", "Labels"), Set.of("TransitEncryptionPort"), false);

        assertEquals(mapper.readTree("""
                {"efsVolumeConfiguration":{"fileSystemId":"fs-1","transitEncryptionPort":2049,
                    "authorizationConfig":{"iam":"ENABLED"}},
                 "dockerVolumeConfiguration":{"driverOpts":{"MountType":"bind"},
                    "labels":{"TeamName":"infra"}},
                 "fsxWindowsFileServerVolumeConfiguration":{"fileSystemId":"fsx-1"},
                 "s3filesVolumeConfiguration":{"bucketName":"bucket"}}
                """), CfnApiShapeConverter.convert(properties, rules));
        assertEquals("2049", properties.path("EFSVolumeConfiguration")
                .path("TransitEncryptionPort").asText());
    }

    @Test
    void buildSpecAndPipelineConfigurationUseDifferentExceptions() throws Exception {
        JsonNode properties = mapper.readTree("""
                {"Source":{"BuildSpec":"version: 0.2"},
                 "Stages":[{"Actions":[{"Configuration":{"Owner":"awslabs",
                    "Nested":{"ProjectName":"build"}}}]}]}
                """);
        CfnApiShapeConverter.Rules rules = new CfnApiShapeConverter.Rules(
                Map.of("BuildSpec", "buildspec"), Set.of("Configuration"), Set.of(), false);

        assertEquals(mapper.readTree("""
                {"source":{"buildspec":"version: 0.2"},
                 "stages":[{"actions":[{"configuration":{"Owner":"awslabs",
                    "Nested":{"ProjectName":"build"}}}]}]}
                """), CfnApiShapeConverter.convert(properties, rules));
    }

    @Test
    void nullPropertiesAreOmittedWithoutRemovingNullArrayEntries() throws Exception {
        JsonNode properties = mapper.readTree("""
                {"NetworkConfiguration":{"NetworkMode":"PUBLIC","Unused":null,
                    "Subnets":[null,{"SubnetId":"subnet-1","Other":null}]}}
                """);

        assertEquals(mapper.readTree("""
                {"networkConfiguration":{"networkMode":"PUBLIC",
                    "subnets":[null,{"subnetId":"subnet-1"}]}}
                """), CfnApiShapeConverter.convert(properties, CfnApiShapeConverter.OMIT_NULL_PROPERTIES));
    }

    @Test
    void capacityProviderUsesJavaShapeAndDropsNullAndEmptyValues() throws Exception {
        JsonNode properties = mapper.readTree("""
                {"ManagedScaling":{"Status":"ENABLED","TargetCapacity":80,
                    "ScalingStep":1.5,"Unused":null,"Empty":"",
                    "Settings":[null,"",true,2,{"Name":"x","Absent":null}]}}
                """);

        Object converted = CfnApiShapeConverter.convertToJava(properties);

        assertInstanceOf(LinkedHashMap.class, converted);
        assertEquals(Map.of("managedScaling", Map.of(
                "status", "ENABLED", "targetCapacity", 80, "scalingStep", 1.5,
                "settings", List.of(true, 2, Map.of("name", "x")))), converted);
        assertEquals("", properties.path("ManagedScaling").path("Empty").asText());
    }
}
