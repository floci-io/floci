package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.PropertyChanges;
import io.floci.aws.services.cloudformation.provisioners.PropertyChanges.Kind;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.cloudformation.provisioners.SqsCfnProvisioner;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.floci.aws.testing.CfnRequests.cfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
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

    /** What one provision call saw, read before the real provisioner runs. */
    private record Seen(String stack, boolean isUpdate, List<String> contextDropped,
                        PropertyChanges changes) {
    }

    /**
     * Creates then updates a queue stack, recording what each provision call saw. When
     * {@code overwriteId} is true, the spy overwrites the
     * resource's physical id after the real call, so the next update meets a resource kept with
     * a null or blank physical id.
     */
    private List<Seen> createThenUpdateDroppingDelay(String stack, boolean overwriteId,
                                                     String physicalIdAfterProvision) {
        List<Seen> seen = new ArrayList<>();
        doAnswer(invocation -> {
            StackResource resource = invocation.getArgument(0);
            JsonNode props = invocation.getArgument(1);
            ProvisionContext ctx = invocation.getArgument(2);
            seen.add(new Seen(ctx.stackName(), ctx.isUpdate(),
                    new ArrayList<>(ctx.droppedProperties(props)), ctx.changes()));
            Object result = invocation.callRealMethod();
            if (overwriteId) {
                resource.setPhysicalId(physicalIdAfterProvision);
            }
            return result;
        }).when(sqsProvisioner).provision(any(), any(), any());

        String queue = stack + "-q";
        cfn("CreateStack", stack).header("Authorization", CFN_AUTH)
                .formParam("TemplateBody", template(queue, """
                        "VisibilityTimeout": "30", "DelaySeconds": "5"
                        """))
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", CFN_AUTH);

        cfn("UpdateStack", stack).header("Authorization", CFN_AUTH)
                .formParam("TemplateBody", template(queue, """
                        "VisibilityTimeout": "30"
                        """))
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", CFN_AUTH);

        return seen.stream().filter(s -> s.stack().equals(stack)).toList();
    }

    private void assertEngineSaysNothingIsDropped(List<Seen> forStack) {
        assertEquals(2, forStack.size(), "one provision call for the create, one for the update");
        Seen update = forStack.get(1);
        assertFalse(update.isUpdate(), "a resource without a physical id is not an update");
        assertEquals(List.of(), update.contextDropped());
        PropertyChanges changes = update.changes();
        assertNotNull(changes, "the engine supplies the object on every call");
        assertEquals(List.of(), List.copyOf(changes.dropped()),
                "the engine agrees with the context that nothing is dropped");
        assertEquals(Set.of(), changes.changed());
        assertEquals(Set.of(), changes.unchanged());
        assertEquals(Set.of("QueueName", "VisibilityTimeout"), changes.added(),
                "every property of the current template is ADDED when the context is not an update");
    }

    @Test
    void engineAndContextAgreeOnDroppedForANormalUpdate() {
        String stack = "agree-" + Long.toString(System.nanoTime(), 36);
        List<Seen> forStack = createThenUpdateDroppingDelay(stack, false, null);

        assertEquals(2, forStack.size());
        Seen update = forStack.get(1);
        assertTrue(update.isUpdate());
        assertEquals(List.of("DelaySeconds"), update.contextDropped());
        assertNotNull(update.changes());
        assertEquals(update.contextDropped(), List.copyOf(update.changes().dropped()));
    }

    @Test
    void updateOfAResourceWithANullPhysicalIdIsNotAnUpdateForTheEngineEither() {
        String stack = "nullid-" + Long.toString(System.nanoTime(), 36);
        assertEngineSaysNothingIsDropped(createThenUpdateDroppingDelay(stack, true, null));
    }

    @Test
    void updateOfAResourceWithABlankPhysicalIdIsNotAnUpdateForTheEngineEither() {
        String stack = "blankid-" + Long.toString(System.nanoTime(), 36);
        assertEngineSaysNothingIsDropped(createThenUpdateDroppingDelay(stack, true, "  "));
    }
}
