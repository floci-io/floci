package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.databasemigration.DatabaseMigrationClient;
import software.amazon.awssdk.services.databasemigration.model.DatabaseMigrationException;
import software.amazon.awssdk.services.databasemigration.model.DescribeReplicationSubnetGroupsResponse;
import software.amazon.awssdk.services.databasemigration.model.ReplicationSubnetGroup;
import software.amazon.awssdk.services.databasemigration.model.Tag;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmsTest {

    private static final String GROUP_ID = "sdk-compat-subnet-group";
    private static final String PAGED_GROUP_PREFIX = "sdk-compat-page-";
    private static final String CONTROL_CHAR_GROUP_ID = "sdk-compat-control-char";

    @Test
    void replicationSubnetGroupLifecycleUsesAwsSdkWireContract() {
        List<Subnet> subnets = twoSubnetsInOneVpcAcrossZones();
        List<String> subnetIds = subnets.stream().map(Subnet::subnetId).toList();

        try (DatabaseMigrationClient dms = TestFixtures.databaseMigrationClient()) {
            dms.createReplicationSubnetGroup(request -> request
                    .replicationSubnetGroupIdentifier(GROUP_ID)
                    .replicationSubnetGroupDescription("sdk compatibility suite")
                    .subnetIds(subnetIds)
                    .tags(tag -> tag.key("env").value("test")));
            try {
                List<ReplicationSubnetGroup> described = dms.describeReplicationSubnetGroups(request -> request
                                .filters(filter -> filter.name("replication-subnet-group-id").values(GROUP_ID)))
                        .replicationSubnetGroups();

                assertEquals(1, described.size());
                ReplicationSubnetGroup group = described.get(0);
                assertEquals(GROUP_ID, group.replicationSubnetGroupIdentifier());
                assertEquals("Complete", group.subnetGroupStatus());
                assertEquals(subnets.get(0).vpcId(), group.vpcId());
                assertFalse(group.subnets().isEmpty());
                assertTrue(group.subnets().stream()
                        .allMatch(subnet -> "Active".equals(subnet.subnetStatus())));
                String arn = "arn:aws:dms:us-east-1:" + callerAccountId() + ":subgrp:" + GROUP_ID;
                assertEquals(Map.of("env", "test"), tagsOf(dms, arn));

                dms.addTagsToResource(request -> request.resourceArn(arn)
                        .tags(tag -> tag.key("owner").value("data")));
                assertEquals(Map.of("env", "test", "owner", "data"), tagsOf(dms, arn));

                dms.removeTagsFromResource(request -> request.resourceArn(arn).tagKeys("env"));
                assertEquals(Map.of("owner", "data"), tagsOf(dms, arn));
            } finally {
                dms.deleteReplicationSubnetGroup(request -> request.replicationSubnetGroupIdentifier(GROUP_ID));
            }

            DatabaseMigrationException deleted = assertThrows(DatabaseMigrationException.class,
                    () -> dms.describeReplicationSubnetGroups(request -> request
                            .filters(filter -> filter.name("replication-subnet-group-id").values(GROUP_ID))));
            assertEquals("ResourceNotFoundFault", deleted.awsErrorDetails().errorCode());
        }
    }

    /**
     * DMS pages this describe at a minimum MaxRecords of 20, so a second page needs more than 20
     * groups to exist. Walk the pages by Marker and check the walk is a partition of what was
     * created: every identifier once, none missing, non-final pages full, and no Marker handed back
     * on the last page (an SDK paginator treats any Marker as "ask again" and would loop).
     */
    @Test
    void describeReplicationSubnetGroupsPagesByMarker() {
        List<String> subnetIds = twoSubnetsInOneVpcAcrossZones().stream().map(Subnet::subnetId).toList();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(PAGED_GROUP_PREFIX + String.format("%02d", i));
        }

        try (DatabaseMigrationClient dms = TestFixtures.databaseMigrationClient()) {
            for (String identifier : created) {
                dms.createReplicationSubnetGroup(request -> request
                        .replicationSubnetGroupIdentifier(identifier)
                        .replicationSubnetGroupDescription("sdk pagination suite")
                        .subnetIds(subnetIds));
            }
            try {
                List<String> walked = new ArrayList<>();
                String marker = null;
                int pages = 0;
                do {
                    String resume = marker;
                    DescribeReplicationSubnetGroupsResponse page = dms.describeReplicationSubnetGroups(
                            request -> request.maxRecords(20).marker(resume));
                    pages++;
                    marker = page.marker();
                    if (marker != null) {
                        assertEquals(20, page.replicationSubnetGroups().size(),
                                "a non-final page must be full");
                    }
                    page.replicationSubnetGroups()
                            .forEach(g -> walked.add(g.replicationSubnetGroupIdentifier()));
                } while (marker != null);

                assertTrue(pages > 1, "25 groups at MaxRecords=20 must span more than one page");
                assertEquals(walked.size(), walked.stream().distinct().count(), "a group was returned twice");
                assertEquals(created, walked.stream()
                        .filter(id -> id.startsWith(PAGED_GROUP_PREFIX))
                        .sorted()
                        .toList());
            } finally {
                for (String identifier : created) {
                    dms.deleteReplicationSubnetGroup(request -> request
                            .replicationSubnetGroupIdentifier(identifier));
                }
            }
        }
    }

    @Test
    void describeReplicationSubnetGroupsRejectsMaxRecordsOutsideTheDocumentedRange() {
        try (DatabaseMigrationClient dms = TestFixtures.databaseMigrationClient()) {
            for (int outOfRange : new int[] {19, 101}) {
                DatabaseMigrationException rejected = assertThrows(DatabaseMigrationException.class,
                        () -> dms.describeReplicationSubnetGroups(request -> request.maxRecords(outOfRange)));
                assertEquals("InvalidParameterValueException", rejected.awsErrorDetails().errorCode());
            }
        }
    }

    /**
     * The DMS request model allows only printable characters in the description, so a control
     * character is rejected rather than persisted.
     */
    @Test
    void createReplicationSubnetGroupRejectsANonPrintableDescription() {
        List<String> subnetIds = twoSubnetsInOneVpcAcrossZones().stream().map(Subnet::subnetId).toList();

        try (DatabaseMigrationClient dms = TestFixtures.databaseMigrationClient()) {
            DatabaseMigrationException rejected = assertThrows(DatabaseMigrationException.class,
                    () -> dms.createReplicationSubnetGroup(request -> request
                            .replicationSubnetGroupIdentifier(CONTROL_CHAR_GROUP_ID)
                            .replicationSubnetGroupDescription("sdk\u0001suite")
                            .subnetIds(subnetIds)));
            assertEquals("InvalidParameterValueException", rejected.awsErrorDetails().errorCode());

            DatabaseMigrationException absent = assertThrows(DatabaseMigrationException.class,
                    () -> dms.describeReplicationSubnetGroups(request -> request
                            .filters(filter -> filter.name("replication-subnet-group-id")
                                    .values(CONTROL_CHAR_GROUP_ID))));
            assertEquals("ResourceNotFoundFault", absent.awsErrorDetails().errorCode());
        }
    }

    /**
     * DMS does not return the subnet group's ARN, so the tagging calls need it built the way the
     * Terraform provider builds it. Read the account from STS rather than hardcoding the default.
     */
    private static String callerAccountId() {
        try (StsClient sts = TestFixtures.stsClient()) {
            return sts.getCallerIdentity().account();
        }
    }

    private static Map<String, String> tagsOf(DatabaseMigrationClient dms, String arn) {
        return dms.listTagsForResource(request -> request.resourceArn(arn)).tagList().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    /**
     * DMS requires the group's subnets to share a VPC and to cover at least two Availability
     * Zones, so pick a VPC that satisfies both rather than the first two subnets returned.
     */
    private static List<Subnet> twoSubnetsInOneVpcAcrossZones() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Map<String, Map<String, Subnet>> byVpcAndZone = new LinkedHashMap<>();
            for (Subnet subnet : ec2.describeSubnets().subnets()) {
                byVpcAndZone
                        .computeIfAbsent(subnet.vpcId(), vpc -> new LinkedHashMap<>())
                        .putIfAbsent(subnet.availabilityZone(), subnet);
            }
            return byVpcAndZone.values().stream()
                    .filter(byZone -> byZone.size() >= 2)
                    .map(byZone -> byZone.values().stream().limit(2).toList())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no VPC has subnets in two Availability Zones"));
        }
    }
}
