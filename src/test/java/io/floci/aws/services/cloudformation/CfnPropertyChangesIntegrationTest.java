package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.provisioners.PropertyChanges;
import io.floci.aws.services.cloudformation.provisioners.PropertyChanges.Kind;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.cloudformation.provisioners.SqsCfnProvisioner;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static io.floci.aws.testing.CfnRequests.cfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

/**
 * UpdateStack hands the provisioner the engine's statement of what happened to each top-level
 * property: the {@link ProvisionContext} it receives carries a {@link PropertyChanges} whose kinds
 * decide CHANGED against UNCHANGED by the forms CloudFormation compares, so a value rewritten
 * without changing what it resolves to is UNCHANGED.
 *
 * <p>Observed by spying on the real SQS provisioner and capturing the context of each call.
 */
@QuarkusTest
class CfnPropertyChangesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/cloudformation/aws4_request";

    @InjectSpy
    SqsCfnProvisioner sqsProvisioner;

    private static String template(String queueName, String properties) {
        return """
                {
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s", %s}
                    }
                  }
                }
                """.formatted(queueName, properties);
    }

    @Test
    void updateGivesTheProvisionerTheKindOfEveryProperty() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "changes-" + suffix;
        String queue = "changes-q-" + suffix;

        cfn("CreateStack", stack).header("Authorization", CFN_AUTH)
                .formParam("TemplateBody", template(queue, """
                        "VisibilityTimeout": "30", "DelaySeconds": "5", "MessageRetentionPeriod": "600"
                        """))
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", CFN_AUTH);

        cfn("UpdateStack", stack).header("Authorization", CFN_AUTH)
                .formParam("TemplateBody", template(queue, """
                        "VisibilityTimeout": {"Fn::Join": ["", ["3", "0"]]},
                        "MaximumMessageSize": "2048", "MessageRetentionPeriod": "1200"
                        """))
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", CFN_AUTH);

        ArgumentCaptor<ProvisionContext> contexts = ArgumentCaptor.forClass(ProvisionContext.class);
        verify(sqsProvisioner, atLeast(2)).provision(any(), any(), contexts.capture());
        List<ProvisionContext> seen = contexts.getAllValues().stream()
                .filter(ctx -> ctx.stackName().equals(stack))
                .toList();
        ProvisionContext created = seen.get(0);
        ProvisionContext updated = seen.get(seen.size() - 1);

        PropertyChanges onCreate = created.changes();
        assertNotNull(onCreate, "the engine supplies the object on a create too");
        assertEquals(Set.of("QueueName", "VisibilityTimeout", "DelaySeconds", "MessageRetentionPeriod"),
                onCreate.added());
        assertTrue(onCreate.dropped().isEmpty());

        PropertyChanges changes = updated.changes();
        assertNotNull(changes, "the engine supplies the object on an update");
        assertEquals(Kind.UNCHANGED, changes.kindOf("QueueName"));
        assertEquals(Kind.UNCHANGED, changes.kindOf("VisibilityTimeout"),
                "written as an Fn::Join but resolving to the same value");
        assertEquals(Kind.CHANGED, changes.kindOf("MessageRetentionPeriod"));
        assertEquals(Kind.DROPPED, changes.kindOf("DelaySeconds"));
        assertEquals(Kind.ADDED, changes.kindOf("MaximumMessageSize"));
        assertEquals(Set.of("DelaySeconds"), changes.dropped());
        assertEquals(Set.of("MaximumMessageSize"), changes.added());
        assertTrue(changes.byName().get("QueueName").createOnly());
    }
}
