package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateRouteRequest;
import software.amazon.awssdk.services.ec2.model.CreateRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteRouteRequest;
import software.amazon.awssdk.services.ec2.model.DeleteRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ReplaceRouteRequest;
import software.amazon.awssdk.services.ec2.model.Route;
import software.amazon.awssdk.services.ec2.model.RouteTable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2RouteTargetTest {

    private static final String CIDR_INST = "10.150.1.0/24";
    private static final String CIDR_ENI = "10.150.2.0/24";
    private static final String CIDR_REPLACE = "10.150.3.0/24";
    private static final String INSTANCE_ID = "i-0123456789abcdef0";
    private static final String ENI_ID = "eni-0123456789abcdef0";
    private static final String REPLACEMENT_ENI_ID = "eni-0987654321fedcba0";

    @Test
    @DisplayName("CreateRoute, ReplaceRoute and DescribeRouteTables with instance and network-interface targets")
    void routeTargetsWithSdk() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(CreateVpcRequest.builder()
                            .cidrBlock("10.150.0.0/16")
                            .build())
                    .vpc().vpcId();

            String routeTableId = ec2.createRouteTable(CreateRouteTableRequest.builder()
                            .vpcId(vpcId)
                            .build())
                    .routeTable().routeTableId();

            try {
                // 1. Create route targeting instanceId
                ec2.createRoute(CreateRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(CIDR_INST)
                        .instanceId(INSTANCE_ID)
                        .build());

                // 2. Create route targeting networkInterfaceId
                ec2.createRoute(CreateRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(CIDR_ENI)
                        .networkInterfaceId(ENI_ID)
                        .build());

                // 3. Describe route table and verify targets are present on Route objects
                List<RouteTable> tables = ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
                                .routeTableIds(routeTableId)
                                .build())
                        .routeTables();
                assertThat(tables).hasSize(1);
                RouteTable table = tables.get(0);

                Route instanceRoute = table.routes().stream()
                        .filter(r -> CIDR_INST.equals(r.destinationCidrBlock()))
                        .findFirst()
                        .orElseThrow();
                assertThat(instanceRoute.instanceId()).isEqualTo(INSTANCE_ID);
                assertThat(instanceRoute.instanceOwnerId()).isNotNull();
                assertThat(instanceRoute.instanceOwnerId()).isEqualTo(table.ownerId());
                assertThat(instanceRoute.networkInterfaceId()).isNull();

                Route eniRoute = table.routes().stream()
                        .filter(r -> CIDR_ENI.equals(r.destinationCidrBlock()))
                        .findFirst()
                        .orElseThrow();
                assertThat(eniRoute.networkInterfaceId()).isEqualTo(ENI_ID);
                assertThat(eniRoute.instanceId()).isNull();
                assertThat(eniRoute.instanceOwnerId()).isNull();

                // 4. DescribeRouteTables filters
                List<RouteTable> filteredByInstance = ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
                                .filters(Filter.builder().name("route.instance-id").values(INSTANCE_ID).build())
                                .build())
                        .routeTables();
                assertThat(filteredByInstance).anyMatch(rt -> routeTableId.equals(rt.routeTableId()));

                List<RouteTable> filteredByEni = ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
                                .filters(Filter.builder().name("route.network-interface-id").values(ENI_ID).build())
                                .build())
                        .routeTables();
                assertThat(filteredByEni).anyMatch(rt -> routeTableId.equals(rt.routeTableId()));

                // 5. ReplaceRoute: replace instance target with network interface target
                ec2.createRoute(CreateRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(CIDR_REPLACE)
                        .instanceId(INSTANCE_ID)
                        .build());

                ec2.replaceRoute(ReplaceRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(CIDR_REPLACE)
                        .networkInterfaceId(REPLACEMENT_ENI_ID)
                        .build());

                RouteTable tableAfterReplace = ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
                                .routeTableIds(routeTableId)
                                .build())
                        .routeTables().get(0);
                Route replacedRoute = tableAfterReplace.routes().stream()
                        .filter(r -> CIDR_REPLACE.equals(r.destinationCidrBlock()))
                        .findFirst()
                        .orElseThrow();
                assertThat(replacedRoute.networkInterfaceId()).isEqualTo(REPLACEMENT_ENI_ID);
                assertThat(replacedRoute.instanceId()).isNull();
                assertThat(replacedRoute.instanceOwnerId()).isNull();

                // 6. Error validation: combining targets must fail
                assertThatThrownBy(() -> ec2.createRoute(CreateRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock("10.150.99.0/24")
                        .instanceId(INSTANCE_ID)
                        .networkInterfaceId(ENI_ID)
                        .build()))
                        .isInstanceOfSatisfying(Ec2Exception.class, ex -> {
                            assertThat(ex.statusCode()).isEqualTo(400);
                            assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                        });

                // 7. Delete route
                ec2.deleteRoute(DeleteRouteRequest.builder()
                        .routeTableId(routeTableId)
                        .destinationCidrBlock(CIDR_INST)
                        .build());

                RouteTable tableAfterDelete = ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
                                .routeTableIds(routeTableId)
                                .build())
                        .routeTables().get(0);
                assertThat(tableAfterDelete.routes().stream()
                        .noneMatch(r -> CIDR_INST.equals(r.destinationCidrBlock()))).isTrue();

            } finally {
                ec2.deleteRouteTable(DeleteRouteTableRequest.builder()
                        .routeTableId(routeTableId)
                        .build());
                ec2.deleteVpc(DeleteVpcRequest.builder()
                        .vpcId(vpcId)
                        .build());
            }
        }
    }
}
