package com.floci.test;

import software.amazon.awssdk.services.ec2.Ec2Client;

/** A VPC and subnet whose lifetime belongs to one test, after its tasks and services stop. */
final class Ec2TestNetwork implements AutoCloseable {
    private final Ec2Client ec2;
    private final String vpcId;
    private final String subnetId;

    private Ec2TestNetwork(Ec2Client ec2, String vpcId, String subnetId) {
        this.ec2 = ec2;
        this.vpcId = vpcId;
        this.subnetId = subnetId;
    }

    static Ec2TestNetwork create(Ec2Client ec2) {
        String vpcId = ec2.createVpc(request -> request.cidrBlock("10.254.0.0/16")).vpc().vpcId();
        try {
            String subnetId = ec2.createSubnet(request -> request.vpcId(vpcId)
                    .cidrBlock("10.254.1.0/24").availabilityZone(TestFixtures.availabilityZone()))
                    .subnet().subnetId();
            return new Ec2TestNetwork(ec2, vpcId, subnetId);
        } catch (RuntimeException failure) {
            try {
                ec2.deleteVpc(request -> request.vpcId(vpcId));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    String subnetId() {
        return subnetId;
    }

    String vpcId() {
        return vpcId;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            ec2.deleteSubnet(request -> request.subnetId(subnetId));
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
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
