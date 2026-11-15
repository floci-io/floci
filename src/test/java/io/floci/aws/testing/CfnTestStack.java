package io.floci.aws.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CloudFormation stack a test creates and updates through the SDK, and the SDK clients the test
 * reads its resources back through, all with the {@code test} credentials in {@code us-east-1}. After
 * each test the stack is deleted, once the test created it, and the clients are closed.
 *
 * <pre>{@code
 * @RegisterExtension
 * final CfnTestStack stack = new CfnTestStack("cfn-rds-db");
 * ...
 * Stack created = stack.create(template);
 * stack.client(RdsClient.builder()).describeDBInstances();
 * }</pre>
 */
public final class CfnTestStack implements AfterEachCallback {

    private final String name;
    private final Map<Class<?>, SdkClient> clients = new LinkedHashMap<>();
    private boolean created;

    /** A stack named {@code namePrefix} plus a suffix unique to the test. */
    public CfnTestStack(String namePrefix) {
        this.name = namePrefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    public String name() {
        return name;
    }

    /** Creates the stack from {@code template} and waits for CREATE_COMPLETE. */
    public Stack create(String template) {
        created = true;
        return CfnStackWaits.createStack(client(CloudFormationClient.builder()), name, template, "CREATE_COMPLETE");
    }

    /** Updates the stack to {@code template} and waits for {@code expectedStatus}. */
    public Stack update(String template, String expectedStatus) {
        return CfnStackWaits.updateStack(client(CloudFormationClient.builder()), name, template, expectedStatus);
    }

    /**
     * The client {@code builder} builds: made on the test's first call for that kind of builder and
     * handed back on every later one, whatever that builder sets, so a test asks for it where it uses
     * it. Closed after the test.
     */
    @SuppressWarnings("unchecked")
    public <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C extends SdkClient> C client(B builder) {
        return (C) clients.computeIfAbsent(builder.getClass(),
                type -> AwsTestClients.create(builder, "test", Region.US_EAST_1));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            if (created) {
                CfnStackWaits.deleteStack(name);
            }
        } finally {
            clients.values().forEach(SdkClient::close);
            clients.clear();
            created = false;
        }
    }
}
