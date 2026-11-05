package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.withSettings;

@QuarkusTest
class CloudFormationSlowStackResponseIntegrationTest {

    private static final String PREFIX = "slow-stack-response-";
    private static final String SLOW_RESOURCE = "AWS::Test::Resource";
    private static final String SLOW_TEMPLATE = """
            {"Resources":{"Slow":{"Type":"AWS::Test::Resource","Properties":{}}}}
            """;

    @Inject
    CfnResourceDispatcher provisioner;

    private final CountDownLatch provisioningStarted = new CountDownLatch(1);
    private final CountDownLatch releaseProvisioning = new CountDownLatch(1);

    @BeforeEach
    void blockSlowResource() {
        Answer<Object> realProvisioner = AdditionalAnswers.delegatesTo(ClientProxy.unwrap(provisioner));
        CfnResourceDispatcher blockingProvisioner = Mockito.mock(CfnResourceDispatcher.class,
                withSettings().defaultAnswer(invocation -> {
                    if (!"provision".equals(invocation.getMethod().getName())
                            || !SLOW_RESOURCE.equals(invocation.getArgument(1))
                            || Arrays.stream(invocation.getArguments()).noneMatch(
                                    argument -> argument instanceof String name && name.startsWith(PREFIX))) {
                        return realProvisioner.answer(invocation);
                    }
                    provisioningStarted.countDown();
                    assertTrue(releaseProvisioning.await(15, TimeUnit.SECONDS),
                            "blocked provisioning was not released");
                    StackResource resource = new StackResource();
                    resource.setLogicalId(invocation.getArgument(0));
                    resource.setResourceType(SLOW_RESOURCE);
                    resource.setPhysicalId("slow-resource");
                    resource.setStatus("CREATE_COMPLETE");
                    return resource;
                }));
        QuarkusMock.installMockForType(blockingProvisioner, CfnResourceDispatcher.class);
    }

    @Test
    void createStackReturnsStackIdWhileResourceIsStillProvisioning() throws Exception {
        String stackName = uniqueStackName();
        try {
            assertRespondsBeforeProvisioningCompletes("CreateStack", stackName, SLOW_TEMPLATE,
                    "CREATE_IN_PROGRESS");
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        } finally {
            releaseProvisioning.countDown();
            deleteStack(stackName);
        }
    }

    @Test
    void updateStackReturnsStackIdWhileResourceIsStillProvisioning() throws Exception {
        String stackName = uniqueStackName();
        String baseTemplate = """
                {"Resources":{"Base":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}}}}
                """.formatted(stackName);
        String updatedTemplate = """
                {"Resources":{"Base":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}},
                              "Slow":{"Type":"AWS::Test::Resource","Properties":{}}}}
                """.formatted(stackName);
        try {
            String createdStackId = cfn("CreateStack", stackName).formParam("TemplateBody", baseTemplate)
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("**.find { it.name() == 'StackId' }");
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            assertEquals(createdStackId, assertRespondsBeforeProvisioningCompletes("UpdateStack", stackName,
                    updatedTemplate, "UPDATE_IN_PROGRESS"));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        } finally {
            releaseProvisioning.countDown();
            deleteStack(stackName);
        }
    }

    @Test
    void protectedCreateStackRejectsDeleteWhileResourceIsStillProvisioning() throws Exception {
        String stackName = uniqueStackName();
        try {
            assertRespondsBeforeProvisioningCompletes("CreateStack", stackName, SLOW_TEMPLATE,
                    "CREATE_IN_PROGRESS", true);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        } finally {
            releaseProvisioning.countDown();
            cfn("UpdateTerminationProtection", stackName)
                    .formParam("EnableTerminationProtection", "false")
                    .when().post("/").then().statusCode(200);
            deleteStack(stackName);
        }
    }

    private String assertRespondsBeforeProvisioningCompletes(String action, String stackName,
                                                               String template, String inProgressStatus) throws Exception {
        return assertRespondsBeforeProvisioningCompletes(action, stackName, template, inProgressStatus, false);
    }

    private String assertRespondsBeforeProvisioningCompletes(String action, String stackName,
                                                               String template, String inProgressStatus,
                                                               boolean terminationProtection) throws Exception {
        try (ExecutorService requests = Executors.newSingleThreadExecutor()) {
            Future<Response> request = requests.submit(() -> {
                RequestSpecification form = cfn(action, stackName).formParam("TemplateBody", template);
                if (terminationProtection) {
                    form.formParam("EnableTerminationProtection", "true");
                }
                return form.when().post("/");
            });
            try {
                assertTrue(provisioningStarted.await(10, TimeUnit.SECONDS),
                        "stack never reached resource provisioning");
                Response response = request.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.asString());
                assertThat(response.asString(), containsString("<StackId>"));
                String responseStackId = response.xmlPath().getString("**.find { it.name() == 'StackId' }");
                Response described = cfn("DescribeStacks", stackName).when().post("/");
                assertEquals(200, described.statusCode(), described.asString());
                assertEquals(responseStackId,
                        described.xmlPath().getString("**.find { it.name() == 'StackId' }"));
                assertThat(described.asString(),
                        containsString("<StackStatus>" + inProgressStatus + "</StackStatus>"));
                if (terminationProtection) {
                    assertThat(described.asString(),
                            containsString("<EnableTerminationProtection>true</EnableTerminationProtection>"));
                    cfn("DeleteStack", stackName).when().post("/").then().statusCode(400)
                            .body(containsString("cannot be deleted while TerminationProtection is enabled"));
                }
                return responseStackId;
            } finally {
                releaseProvisioning.countDown();
            }
        }
    }

    private static void deleteStack(String stackName) {
        cfn("DeleteStack", stackName).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static String uniqueStackName() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }
}
