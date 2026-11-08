package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbProxyNotFoundException;
import software.amazon.awssdk.services.rds.model.InvalidSubnetException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdsSubnetOwnershipTest {
    @Test
    void mixedVpcSubnetsRemainRejectedWithoutPublishingAProxy() {
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             RdsClient rds = TestFixtures.rdsClient();
             Ec2TestNetwork first = Ec2TestNetwork.create(ec2);
             Ec2TestNetwork second = Ec2TestNetwork.create(ec2,
                     List.of(TestFixtures.region().id() + "b"))) {
            String name = TestFixtures.uniqueName("rds-mixed-subnets");
            try {
                assertThatThrownBy(() -> rds.createDBProxy(request -> request.dbProxyName(name)
                        .engineFamily("POSTGRESQL")
                        .roleArn("arn:aws:iam::000000000000:role/rds-proxy")
                        .defaultAuthScheme("IAM_AUTH")
                        .vpcSubnetIds(first.subnetId(), second.subnetId())))
                        .isInstanceOf(InvalidSubnetException.class)
                        .hasMessageContaining("VpcSubnetIds must belong to one VPC.");
                assertThatThrownBy(() -> rds.describeDBProxies(request -> request.dbProxyName(name)))
                        .isInstanceOf(DbProxyNotFoundException.class);
            } finally {
                try {
                    rds.deleteDBProxy(request -> request.dbProxyName(name));
                } catch (DbProxyNotFoundException expected) {
                    // Rejected creation leaves no proxy; still clean up if the assertion fails.
                }
            }
        }
    }
}
