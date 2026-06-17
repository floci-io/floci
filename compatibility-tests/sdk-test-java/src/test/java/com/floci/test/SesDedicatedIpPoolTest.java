package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.GetDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.GetDedicatedIpPoolResponse;
import software.amazon.awssdk.services.sesv2.model.ListDedicatedIpPoolsResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 dedicated IP pool APIs. Verifies the
 * AWS Java SDK v2 marshalling of CreateDedicatedIpPool / GetDedicatedIpPool /
 * ListDedicatedIpPools / DeleteDedicatedIpPool and the
 * BadRequestException / AlreadyExistsException / NotFoundException errors,
 * against a live Floci instance.
 */
@DisplayName("SES v2 Dedicated IP Pools")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesDedicatedIpPoolTest {

    private static final String POOL = "compat-pool-alpha";
    private static final String POOL_MANAGED = "compat-pool-managed";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        deletePoolQuietly(POOL);
        deletePoolQuietly(POOL_MANAGED);
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            deletePoolQuietly(POOL);
            deletePoolQuietly(POOL_MANAGED);
            sesV2.close();
        }
    }

    private static void deletePoolQuietly(String name) {
        try {
            sesV2.deleteDedicatedIpPool(DeleteDedicatedIpPoolRequest.builder().poolName(name).build());
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void createDedicatedIpPool_defaultsToStandardScaling() {
        sesV2.createDedicatedIpPool(CreateDedicatedIpPoolRequest.builder().poolName(POOL).build());

        GetDedicatedIpPoolResponse response = sesV2.getDedicatedIpPool(
                GetDedicatedIpPoolRequest.builder().poolName(POOL).build());
        assertThat(response.dedicatedIpPool()).isNotNull();
        assertThat(response.dedicatedIpPool().poolName()).isEqualTo(POOL);
        assertThat(response.dedicatedIpPool().scalingModeAsString()).isEqualTo("STANDARD");
    }

    @Test
    @Order(2)
    void createDedicatedIpPool_managedScaling() {
        sesV2.createDedicatedIpPool(CreateDedicatedIpPoolRequest.builder()
                .poolName(POOL_MANAGED).scalingMode("MANAGED").build());

        GetDedicatedIpPoolResponse response = sesV2.getDedicatedIpPool(
                GetDedicatedIpPoolRequest.builder().poolName(POOL_MANAGED).build());
        assertThat(response.dedicatedIpPool().scalingModeAsString()).isEqualTo("MANAGED");
    }

    @Test
    @Order(3)
    void createDedicatedIpPool_invalidScalingMode_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createDedicatedIpPool(CreateDedicatedIpPoolRequest.builder()
                .poolName("compat-pool-bad").scalingMode("NONSENSE").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @Order(4)
    void createDedicatedIpPool_duplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> sesV2.createDedicatedIpPool(
                CreateDedicatedIpPoolRequest.builder().poolName(POOL).build()))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @Order(5)
    void getDedicatedIpPool_unknown_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.getDedicatedIpPool(
                GetDedicatedIpPoolRequest.builder().poolName("compat-pool-ghost").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(6)
    void listDedicatedIpPools_includesCreatedPools() {
        ListDedicatedIpPoolsResponse response = sesV2.listDedicatedIpPools(r -> {});
        assertThat(response.dedicatedIpPools()).contains(POOL, POOL_MANAGED);
    }

    @Test
    @Order(7)
    void deleteDedicatedIpPool_removesIt() {
        sesV2.deleteDedicatedIpPool(DeleteDedicatedIpPoolRequest.builder().poolName(POOL).build());

        assertThatThrownBy(() -> sesV2.getDedicatedIpPool(
                GetDedicatedIpPoolRequest.builder().poolName(POOL).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(8)
    void deleteDedicatedIpPool_unknown_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.deleteDedicatedIpPool(
                DeleteDedicatedIpPoolRequest.builder().poolName("compat-pool-ghost").build()))
                .isInstanceOf(NotFoundException.class);
    }
}
