package com.floci.test;

import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.ArrayList;
import java.util.List;

/** A VPC and its subnets whose lifetime belongs to one test, after its tasks and services stop. */
final class Ec2TestNetwork implements AutoCloseable {
    private final Ec2Client ec2;
    private final String vpcId;
    private final List<String> subnetIds;

    private Ec2TestNetwork(Ec2Client ec2, String vpcId, List<String> subnetIds) {
        this.ec2 = ec2;
        this.vpcId = vpcId;
        this.subnetIds = List.copyOf(subnetIds);
    }

    static Ec2TestNetwork create(Ec2Client ec2) {
        return create(ec2, List.of(TestFixtures.availabilityZone()));
    }

    static Ec2TestNetwork create(Ec2Client ec2, List<String> availabilityZones) {
        String vpcId = ec2.createVpc(request -> request.cidrBlock("10.254.0.0/16")).vpc().vpcId();
        List<String> subnetIds = new ArrayList<>();
        try {
            for (int i = 0; i < availabilityZones.size(); i++) {
                String zone = availabilityZones.get(i);
                String cidr = "10.254." + (i + 1) + ".0/24";
                subnetIds.add(ec2.createSubnet(request -> request.vpcId(vpcId)
                        .cidrBlock(cidr).availabilityZone(zone)).subnet().subnetId());
            }
            return new Ec2TestNetwork(ec2, vpcId, subnetIds);
        } catch (RuntimeException failure) {
            try {
                new Ec2TestNetwork(ec2, vpcId, subnetIds).close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    String subnetId() {
        return subnetIds.get(0);
    }

    List<String> subnetIds() {
        return subnetIds;
    }

    String vpcId() {
        return vpcId;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int i = subnetIds.size() - 1; i >= 0; i--) {
            String subnetId = subnetIds.get(i);
            try {
                ec2.deleteSubnet(request -> request.subnetId(subnetId));
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        try {
            ec2.deleteVpc(request -> request.vpcId(vpcId));
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
