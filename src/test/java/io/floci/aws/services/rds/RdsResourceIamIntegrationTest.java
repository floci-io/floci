package io.floci.aws.services.rds;

import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RdsIamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.awssdk.services.rds.model.Tag;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(RdsIamEnforcementProfile.class)
class RdsResourceIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"pg,us-east-1", "cluster-pg,us-east-1", "subgrp,us-east-1", "db,us-east-1", "cluster,us-east-1",
            "pg,cn-north-1", "cluster-pg,cn-north-1", "subgrp,cn-north-1", "db,cn-north-1", "cluster,cn-north-1",
            "pg,us-gov-west-1", "cluster-pg,us-gov-west-1", "subgrp,us-gov-west-1",
            "db,us-gov-west-1", "cluster,us-gov-west-1"})
    void exactArnGrantsAndStoredTagsControlTheResourceLifecycle(String type, String regionName) {
        Region region = Region.of(regionName);
        String name = "iam-rds-" + UUID.randomUUID().toString().substring(0, 12);
        String arn = "arn:" + AwsRegions.partitionFor(regionName) + ":rds:" + regionName + ":" + ACCOUNT + ":" + type + ":" + name;
        String user = "rds-iam-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        policy(user, """
                [{"Effect":"Allow","Action":["rds:Create*","rds:AddTagsToResource"],"Resource":"%s",
                  "Condition":{"StringEquals":{"aws:RequestTag/Owner":"team-a"}}},
                 {"Effect":"Allow","Action":["rds:Describe*","rds:ListTagsForResource"],"Resource":"%s"},
                 {"Effect":"Allow","Action":["rds:Delete*","rds:Modify*","rds:AddTagsToResource","rds:RemoveTagsFromResource"],
                  "Resource":"%s","Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a"}}}]
                """.formatted(arn, arn, arn));
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, region);
                RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                        credentials.secretAccessKey(), region)) {
            denied(() -> create(limited, type, name + "-other", regionName, "team-a"));
            denied(() -> create(limited, type, name, regionName, "team-b"));
            assertEquals(arn, create(limited, type, name, regionName, "team-a"));
            try {
                assertEquals(List.of(tag("Owner", "team-a")), limited.listTagsForResource(r -> r.resourceName(arn)).tagList());
                modify(limited, type, name, regionName);
                limited.addTagsToResource(r -> r.resourceName(arn).tags(tag("Review", "ok")));
                limited.removeTagsFromResource(r -> r.resourceName(arn).tagKeys("Review"));
                root.addTagsToResource(r -> r.resourceName(arn).tags(tag("Owner", "team-b")));
                denied(() -> modify(limited, type, name, regionName));
                denied(() -> delete(limited, type, name));
                root.addTagsToResource(r -> r.resourceName(arn).tags(tag("Owner", "team-a")));
                delete(limited, type, name);
            } finally {
                try {
                    delete(root, type, name);
                } catch (RdsException expected) {
                    // Deletion above succeeded, or this finally removes the fixture after an assertion failure.
                    if (!expected.awsErrorDetails().errorCode().contains("NotFound")) {
                        throw expected;
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"pg,aws:ResourceTag/", "cluster-pg,aws:ResourceTag/", "subgrp,aws:ResourceTag/",
            "pg,rds:pg-tag/", "cluster-pg,rds:cluster-pg-tag/", "subgrp,rds:subgrp-tag/"})
    void resourceTagGrantsCannotBorrowTagsFromAnotherAccountOrRegion(String type, String prefix) {
        String name = "iam-tags-" + UUID.randomUUID().toString().substring(0, 12);
        String user = "rds-tags-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        policy(user, """
                [{"Effect":"Allow","Action":"rds:ListTagsForResource","Resource":"*",
                  "Condition":{"StringEquals":{"%sOwner":"team-a"}}}]
                """.formatted(prefix));
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1);
                RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                        credentials.secretAccessKey(), Region.US_EAST_1)) {
            String arn = create(root, type, name, "us-east-1", "team-a");
            try {
                assertEquals(List.of(tag("Owner", "team-a")), limited.listTagsForResource(r -> r.resourceName(arn)).tagList());
                for (String foreign : List.of(arn.replace(ACCOUNT, "111111111111"),
                        arn.replace("us-east-1", "us-west-2"), arn.replace("arn:aws:", "arn:aws-cn:"), arn + "-absent")) {
                    denied(() -> limited.listTagsForResource(r -> r.resourceName(foreign)));
                }
                root.removeTagsFromResource(r -> r.resourceName(arn).tagKeys("Owner"));
                denied(() -> limited.listTagsForResource(r -> r.resourceName(arn)));
            } finally {
                delete(root, type, name);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"cluster,cluster-pg,CreateDBCluster", "db,pg,CreateDBInstance"})
    void aCreateNeedsItsExplicitParameterGroupButTagsOnlyTheNewDatabase(String type, String groupType, String action) {
        String name = "iam-secondary-" + UUID.randomUUID().toString().substring(0, 12);
        String arn = "arn:aws:rds:us-east-1:" + ACCOUNT + ":" + type + ":" + name;
        String user = "rds-secondary-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1);
                RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                        credentials.secretAccessKey(), Region.US_EAST_1)) {
            String group = name + "-parameters";
            String groupArn = create(root, groupType, group, "us-east-1", "team-a");
            boolean created = false;
            try {
                Runnable create = () -> {
                    if ("cluster".equals(type)) {
                        limited.createDBCluster(r -> r.dbClusterIdentifier(name).engine("aurora-postgresql")
                                .dbClusterParameterGroupName(group).masterUsername("admin")
                                .masterUserPassword("Password12345").tags(tag("Owner", "team-a")));
                    } else {
                        limited.createDBInstance(r -> r.dbInstanceIdentifier(name).engine("postgres")
                                .dbParameterGroupName(group).dbInstanceClass("db.t3.micro").allocatedStorage(20)
                                .masterUsername("admin").masterUserPassword("Password12345").tags(tag("Owner", "team-a")));
                    }
                };
                String statements = """
                        [{"Effect":"Allow","Action":"rds:%s","Resource":[%s]},
                         {"Effect":"Allow","Action":"rds:AddTagsToResource","Resource":"%s",
                          "Condition":{"StringEquals":{"aws:RequestTag/Owner":"team-a"}}}]
                        """;
                policy(user, statements.formatted(action, "\"" + arn + "\"", arn));
                denied(create);
                policy(user, statements.formatted(action, "\"" + arn + "\",\"" + groupArn + "\"", arn));
                create.run();
                created = true;
                assertEquals(List.of(tag("Owner", "team-a")), root.listTagsForResource(r -> r.resourceName(arn)).tagList());
            } finally {
                if (created) {
                    delete(root, type, name);
                }
                delete(root, groupType, group);
            }
        }
    }

    @Test
    void taggedClusterEndpointHonorsExplicitDeny() {
        String name = "iam-endpoint-" + UUID.randomUUID().toString().substring(0, 8);
        String user = "endpoint-reader-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        policy(user, """
                [{"Effect":"Allow","Action":"rds:ListTagsForResource","Resource":"*"},
                 {"Effect":"Deny","Action":"rds:ListTagsForResource","Resource":"*",
                  "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a"}}}]
                """);
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1)) {
            create(root, "cluster", name, "us-east-1", "team-a");
            try {
                String arn = root.createDBClusterEndpoint(r -> r.dbClusterIdentifier(name)
                        .dbClusterEndpointIdentifier(name + "-reader").endpointType("READER")
                        .tags(tag("Owner", "team-a"))).dbClusterEndpointArn();
                try {
                    denied(() -> limited.listTagsForResource(r -> r.resourceName(arn)));
                    root.removeTagsFromResource(r -> r.resourceName(arn).tagKeys("Owner"));
                    assertEquals(List.of(), limited.listTagsForResource(r -> r.resourceName(arn)).tagList());
                } finally {
                    root.deleteDBClusterEndpoint(r -> r.dbClusterEndpointIdentifier(name + "-reader"));
                }
            } finally {
                delete(root, "cluster", name);
            }
        }
    }

    @Test
    void documentDbTagsUseTheServiceThatOwnsTheSharedRdsArn() {
        String name = "iam-docdb-" + UUID.randomUUID().toString().substring(0, 8);
        String user = "docdb-reader-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        policy(user, """
                [{"Effect":"Allow","Action":["rds:ListTagsForResource","rds:DeleteDBCluster"],"Resource":"*"},
                 {"Effect":"Deny","Action":["rds:ListTagsForResource","rds:DeleteDBCluster"],"Resource":"*",
                  "Condition":{"StringEquals":{"rds:cluster-tag/Owner":"team-a"}}}]
                """);
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1)) {
            String arn = root.createDBCluster(r -> r.dbClusterIdentifier(name).engine("docdb")
                    .masterUsername("admin").masterUserPassword("Password12345").tags(tag("Owner", "team-a")))
                    .dbCluster().dbClusterArn();
            try {
                denied(() -> limited.listTagsForResource(r -> r.resourceName(arn)));
                denied(() -> limited.deleteDBCluster(r -> r.dbClusterIdentifier(name).skipFinalSnapshot(true)));
                root.removeTagsFromResource(r -> r.resourceName(arn).tagKeys("Owner"));
                assertEquals(List.of(), limited.listTagsForResource(r -> r.resourceName(arn)).tagList());
            } finally {
                delete(root, "cluster", name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cluster-pg", "pg"})
    void modifyingAClusterAuthorizesItsExplicitParameterGroups(String type) {
        String name = "iam-modify-" + UUID.randomUUID().toString().substring(0, 8);
        String user = "modify-groups-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                RestAssured.port, auth(ACCOUNT, "iam"), user);
        try (RdsClient root = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient limited = AwsTestClients.create(RdsClient.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1)) {
            String arn = create(root, "cluster", name, "us-east-1", "team-a");
            try {
                String groupArn = create(root, type, name + "-parameters", "us-east-1", "team-a");
                try {
                    policy(user, """
                            [{"Effect":"Allow","Action":"rds:ModifyDBCluster","Resource":"%s"}]
                            """.formatted(arn));
                    Runnable change = () -> limited.modifyDBCluster(r -> {
                        r.dbClusterIdentifier(name);
                        if ("cluster-pg".equals(type)) {
                            r.dbClusterParameterGroupName(name + "-parameters");
                        } else {
                            r.dbInstanceParameterGroupName(name + "-parameters");
                        }
                    });
                    denied(change);
                    policy(user, """
                            [{"Effect":"Allow","Action":"rds:ModifyDBCluster","Resource":"*"},
                             {"Effect":"Deny","Action":"rds:ModifyDBCluster","Resource":"%s"}]
                            """.formatted(groupArn));
                    denied(change);
                } finally {
                    delete(root, type, name + "-parameters");
                }
            } finally {
                delete(root, "cluster", name);
            }
        }
    }

    private static String create(RdsClient client, String type, String name, String region, String owner) {
        Tag tag = tag("Owner", owner);
        return switch (type) {
            case "pg" -> client.createDBParameterGroup(r -> r.dbParameterGroupName(name)
                    .dbParameterGroupFamily("postgres16").description("IAM regression").tags(tag))
                    .dbParameterGroup().dbParameterGroupArn();
            case "cluster-pg" -> client.createDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(name)
                    .dbParameterGroupFamily("aurora-postgresql16").description("IAM regression").tags(tag))
                    .dbClusterParameterGroup().dbClusterParameterGroupArn();
            case "subgrp" -> client.createDBSubnetGroup(r -> r.dbSubnetGroupName(name)
                    .dbSubnetGroupDescription("IAM regression").tags(tag)
                    .subnetIds(Ec2Service.defaultSubnetId(region, "a"), Ec2Service.defaultSubnetId(region, "b")))
                    .dbSubnetGroup().dbSubnetGroupArn();
            case "db" -> client.createDBInstance(r -> r.dbInstanceIdentifier(name).engine("postgres")
                    .dbInstanceClass("db.t3.micro").allocatedStorage(20).masterUsername("admin")
                    .masterUserPassword("Password12345").tags(tag)).dbInstance().dbInstanceArn();
            case "cluster" -> client.createDBCluster(r -> r.dbClusterIdentifier(name).engine("aurora-postgresql")
                    .masterUsername("admin").masterUserPassword("Password12345").tags(tag)).dbCluster().dbClusterArn();
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static void modify(RdsClient client, String type, String name, String region) {
        switch (type) {
            case "pg" -> client.modifyDBParameterGroup(r -> r.dbParameterGroupName(name).parameters(parameter ->
                    parameter.parameterName("log_min_duration_statement").parameterValue("1000").applyMethod("immediate")));
            case "cluster-pg" -> client.modifyDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(name)
                    .parameters(parameter -> parameter.parameterName("log_min_duration_statement")
                            .parameterValue("1000").applyMethod("immediate")));
            case "subgrp" -> client.modifyDBSubnetGroup(r -> r.dbSubnetGroupName(name).dbSubnetGroupDescription("updated")
                    .subnetIds(Ec2Service.defaultSubnetId(region, "a"), Ec2Service.defaultSubnetId(region, "b")));
            case "db" -> client.modifyDBInstance(r -> r.dbInstanceIdentifier(name).backupRetentionPeriod(2));
            case "cluster" -> client.modifyDBCluster(r -> r.dbClusterIdentifier(name).backupRetentionPeriod(2));
            default -> throw new IllegalArgumentException(type);
        }
    }

    private static void delete(RdsClient client, String type, String name) {
        switch (type) {
            case "pg" -> client.deleteDBParameterGroup(r -> r.dbParameterGroupName(name));
            case "cluster-pg" -> client.deleteDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(name));
            case "subgrp" -> client.deleteDBSubnetGroup(r -> r.dbSubnetGroupName(name));
            case "db" -> client.deleteDBInstance(r -> r.dbInstanceIdentifier(name).skipFinalSnapshot(true));
            case "cluster" -> client.deleteDBCluster(r -> r.dbClusterIdentifier(name).skipFinalSnapshot(true));
            default -> throw new IllegalArgumentException(type);
        }
    }

    private static void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), user, "scope",
                "{\"Version\":\"2012-10-17\",\"Statement\":" + statements + "}");
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static void denied(Runnable call) {
        RdsException exception = assertThrows(RdsException.class, call::run);
        assertEquals("AccessDenied", exception.awsErrorDetails().errorCode());
    }
}
