package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.mq.MqClient;
import software.amazon.awssdk.services.mq.model.BrokerState;
import software.amazon.awssdk.services.mq.model.CreateBrokerRequest;
import software.amazon.awssdk.services.mq.model.CreateBrokerResponse;
import software.amazon.awssdk.services.mq.model.CreateUserRequest;
import software.amazon.awssdk.services.mq.model.DeleteBrokerRequest;
import software.amazon.awssdk.services.mq.model.DeploymentMode;
import software.amazon.awssdk.services.mq.model.DescribeBrokerRequest;
import software.amazon.awssdk.services.mq.model.DescribeBrokerResponse;
import software.amazon.awssdk.services.mq.model.EngineType;
import software.amazon.awssdk.services.mq.model.ListBrokersRequest;
import software.amazon.awssdk.services.mq.model.MqException;
import software.amazon.awssdk.services.mq.model.User;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real RabbitMQ container path: CreateBroker provisions a
 * rabbitmq:3-management container, the broker transitions to RUNNING once its
 * management API answers, and the AMQP/console endpoints are published. Also
 * confirms the AWS-faithful rejection of the ActiveMQ-only User API on RabbitMQ.
 *
 * <p>Requires a running Floci with {@code amazonmq.mock=false} and a working
 * Docker daemon. When the broker never reaches RUNNING (Docker unavailable in
 * CI), the dependent assertions skip rather than fail.
 */
@DisplayName("Amazon MQ (RabbitMQ)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmazonMqTest {

    private static MqClient mq;
    private static String brokerId;
    private static boolean reachedRunning;
    private static final String BROKER_NAME = TestFixtures.uniqueName("mq-broker");

    @BeforeAll
    static void setup() {
        mq = TestFixtures.mqClient();
    }

    @AfterAll
    static void cleanup() {
        if (mq != null) {
            if (brokerId != null) {
                try {
                    mq.deleteBroker(DeleteBrokerRequest.builder().brokerId(brokerId).build());
                } catch (Exception ignored) {}
            }
            mq.close();
        }
    }

    @Test
    @Order(1)
    void createBroker() {
        CreateBrokerResponse response = mq.createBroker(CreateBrokerRequest.builder()
                .brokerName(BROKER_NAME)
                .engineType(EngineType.RABBITMQ)
                .engineVersion("3.13")
                .deploymentMode(DeploymentMode.SINGLE_INSTANCE)
                .hostInstanceType("mq.t3.micro")
                .publiclyAccessible(false)
                .autoMinorVersionUpgrade(true)
                .users(User.builder()
                        .username("admin")
                        .password("AdminPass123")
                        .consoleAccess(true)
                        .build())
                .build());

        assertThat(response.brokerId()).isNotNull();
        assertThat(response.brokerArn()).contains(":mq:");
        brokerId = response.brokerId();
    }

    @Test
    @Order(2)
    void brokerReachesRunningWithEndpoints() {
        requireBroker();

        DescribeBrokerResponse describe = waitForState(BrokerState.RUNNING, Duration.ofSeconds(120));
        Assumptions.assumeTrue(describe != null,
                "Broker did not reach RUNNING (Docker likely unavailable); skipping endpoint checks");

        reachedRunning = true;
        assertThat(describe.brokerName()).isEqualTo(BROKER_NAME);
        assertThat(describe.engineType()).isEqualTo(EngineType.RABBITMQ);
        assertThat(describe.brokerInstances()).isNotEmpty();
        assertThat(describe.brokerInstances().get(0).endpoints().get(0)).startsWith("amqp://");
        assertThat(describe.brokerInstances().get(0).consoleURL()).startsWith("http://");
    }

    @Test
    @Order(3)
    void listBrokersIncludesCreated() {
        requireBroker();

        var response = mq.listBrokers(ListBrokersRequest.builder().build());
        assertThat(response.brokerSummaries()).anyMatch(b -> b.brokerId().equals(brokerId));
    }

    @Test
    @Order(4)
    void userApiRejectedForRabbitMq() {
        requireBroker();

        // The standalone User API is ActiveMQ-only; AWS rejects it for RabbitMQ.
        assertThatThrownBy(() -> mq.createUser(CreateUserRequest.builder()
                .brokerId(brokerId)
                .username("alice")
                .password("AnotherPass99")
                .build()))
                .isInstanceOf(MqException.class);
    }

    @Test
    @Order(5)
    void deleteBroker() {
        requireBroker();

        var response = mq.deleteBroker(DeleteBrokerRequest.builder().brokerId(brokerId).build());
        assertThat(response.brokerId()).isEqualTo(brokerId);
        brokerId = null;
    }

    private DescribeBrokerResponse waitForState(BrokerState target, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            DescribeBrokerResponse describe = mq.describeBroker(
                    DescribeBrokerRequest.builder().brokerId(brokerId).build());
            if (describe.brokerState() == target) {
                return describe;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static void requireBroker() {
        Assumptions.assumeTrue(brokerId != null, "Broker must exist from earlier ordered test");
    }
}
