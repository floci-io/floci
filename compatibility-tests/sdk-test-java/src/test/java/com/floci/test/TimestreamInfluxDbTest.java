package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.timestreaminfluxdb.TimestreamInfluxDbClient;
import software.amazon.awssdk.services.timestreaminfluxdb.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.timestreaminfluxdb.model.CreateDbParameterGroupRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.CreateDbParameterGroupResponse;
import software.amazon.awssdk.services.timestreaminfluxdb.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.GetDbInstanceRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.GetDbInstanceResponse;
import software.amazon.awssdk.services.timestreaminfluxdb.model.GetDbParameterGroupRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.InfluxDBv2Parameters;
import software.amazon.awssdk.services.timestreaminfluxdb.model.ListDbInstancesRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.LogLevel;
import software.amazon.awssdk.services.timestreaminfluxdb.model.Parameters;
import software.amazon.awssdk.services.timestreaminfluxdb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.timestreaminfluxdb.model.TagResourceRequest;
import software.amazon.awssdk.services.timestreaminfluxdb.model.ValidationException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestreamInfluxDbTest {

    @Test
    void parameterGroupAndInstanceLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (TimestreamInfluxDbClient client = TestFixtures.timestreamInfluxDbClient()) {
            CreateDbParameterGroupResponse group = client.createDbParameterGroup(CreateDbParameterGroupRequest.builder()
                    .name("sdk-group-" + suffix)
                    .parameters(Parameters.fromInfluxDBv2(InfluxDBv2Parameters.builder().logLevel(LogLevel.DEBUG).build()))
                    .build());
            assertEquals(LogLevel.DEBUG, client.getDbParameterGroup(GetDbParameterGroupRequest.builder()
                    .identifier(group.id()).build()).parameters().influxDBv2().logLevel());

            CreateDbInstanceResponse created = client.createDbInstance(CreateDbInstanceRequest.builder()
                    .name("sdk-db-" + suffix)
                    .password("password123")
                    .dbInstanceType("db.influx.medium")
                    .allocatedStorage(20)
                    .dbParameterGroupIdentifier(group.id())
                    .vpcSubnetIds("subnet-abc123")
                    .vpcSecurityGroupIds("sg-abc123")
                    .tags(Map.of("env", "compat"))
                    .build());
            assertNotNull(created.id());
            assertEquals("CREATING", created.statusAsString());

            try {
                GetDbInstanceResponse instance = client.getDbInstance(GetDbInstanceRequest.builder()
                        .identifier(created.id()).build());
                assertEquals(created.arn(), instance.arn());
                assertEquals(group.id(), instance.dbParameterGroupIdentifier());
                assertTrue(client.listDbInstances(ListDbInstancesRequest.builder().build()).items().stream()
                        .anyMatch(summary -> summary.id().equals(created.id())));

                client.tagResource(TagResourceRequest.builder()
                        .resourceArn(created.arn()).tags(Map.of("team", "metrics")).build());
                Map<String, String> tags = client.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceArn(created.arn()).build()).tags();
                assertEquals("compat", tags.get("env"));
                assertEquals("metrics", tags.get("team"));
            } finally {
                client.deleteDbInstance(DeleteDbInstanceRequest.builder().identifier(created.id()).build());
            }

            assertThrows(ResourceNotFoundException.class, () -> client.getDbInstance(GetDbInstanceRequest.builder()
                    .identifier(created.id()).build()));
            assertThrows(ValidationException.class, () -> client.createDbInstance(CreateDbInstanceRequest.builder()
                    .name("sdk-bad-" + suffix)
                    .password("password123")
                    .dbInstanceType("db.influx.medium")
                    .allocatedStorage(19)
                    .vpcSubnetIds("subnet-abc123")
                    .vpcSecurityGroupIds("sg-abc123")
                    .build()));
        }
    }
}
