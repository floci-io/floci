package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.withSettings;

@QuarkusTest
class CloudFormationCreateStackIdentityIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources":{"Q":{"Type":"AWS::SQS::Queue","Properties":{}}}}
            """;

    @Inject
    CloudFormationService service;

    @Test
    void createStackDoesNotExecuteAReplacementWithTheSameName() throws Exception {
        String stackName = "cfn-create-identity-" + UUID.randomUUID().toString().substring(0, 8);
        CloudFormationService original = ClientProxy.unwrap(service);
        Answer<Object> realService = AdditionalAnswers.delegatesTo(original);
        AtomicReference<ChangeSet> replacement = new AtomicReference<>();
        CloudFormationService replacingService = Mockito.mock(CloudFormationService.class,
                withSettings().defaultAnswer(invocation -> {
                    if (!"createChangeSetForCreateStack".equals(invocation.getMethod().getName())) {
                        return realService.answer(invocation);
                    }
                    ChangeSet initial = (ChangeSet) realService.answer(invocation);
                    Future<?> deleted = original.deleteStack(initial.getStackId(), List.of(), "us-east-1",
                            "000000000000");
                    deleted.get(5, TimeUnit.SECONDS);
                    replacement.set(original.createChangeSetForCreateStack(stackName, "initial-create",
                            TEMPLATE, null, null, List.of(), null, "us-east-1", false));
                    assertNotEquals(initial.getStackId(), replacement.get().getStackId());
                    return initial;
                }));
        QuarkusMock.installMockForType(replacingService, CloudFormationService.class);

        try {
            cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                    .when().post("/").then().statusCode(400);
            assertThat(cfn("DescribeStacks", stackName).when().post("/").asString(),
                    containsString("<StackId>" + replacement.get().getStackId() + "</StackId>"));
            assertThat(cfn("DescribeStacks", stackName).when().post("/").asString(),
                    containsString("<StackStatus>REVIEW_IN_PROGRESS</StackStatus>"));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void protectedCreateClearsProtectionWhenItsChangeSetIsDeletedBeforeExecution() {
        String stackName = "cfn-create-deleted-cs-" + UUID.randomUUID().toString().substring(0, 8);
        CloudFormationService original = ClientProxy.unwrap(service);
        Answer<Object> realService = AdditionalAnswers.delegatesTo(original);
        CloudFormationService deletingService = Mockito.mock(CloudFormationService.class,
                withSettings().defaultAnswer(invocation -> {
                    if (!"createChangeSetForCreateStack".equals(invocation.getMethod().getName())) {
                        return realService.answer(invocation);
                    }
                    ChangeSet initial = (ChangeSet) realService.answer(invocation);
                    original.deleteChangeSet(initial.getStackId(), initial.getChangeSetId(), "us-east-1");
                    return initial;
                }));
        QuarkusMock.installMockForType(deletingService, CloudFormationService.class);

        try {
            cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                    .formParam("EnableTerminationProtection", "true")
                    .when().post("/").then().statusCode(400)
                    .body(containsString("ChangeSetNotFoundException"));
            String stackXml = cfn("DescribeStacks", stackName).when().post("/")
                    .then().statusCode(200).extract().asString();
            assertThat(stackXml, containsString("<StackStatus>REVIEW_IN_PROGRESS</StackStatus>"));
            assertThat(stackXml, containsString("<EnableTerminationProtection>false</EnableTerminationProtection>"));
        } finally {
            original.updateTerminationProtection(stackName, false, "us-east-1");
            CfnStackWaits.deleteStack(stackName);
        }
    }
}
