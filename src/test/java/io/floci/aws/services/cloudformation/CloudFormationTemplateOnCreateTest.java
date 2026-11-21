package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * A created stack has its template once CreateStack answers, as on AWS, though its creation waits for a free operation
 * thread: GetTemplate and GetTemplateSummary read it while every thread runs another stack's creation.
 */
class CloudFormationTemplateOnCreateTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String HELD = "{\"Resources\":{\"Held\":{\"Type\":\"AWS::SNS::Topic\",\"Properties\":{}}}}";
    private static final String QUEUED =
            "{\"Resources\":{\"Queued\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{}}}}";

    @Test
    void aStackWaitingForAnOperationThreadHasTheTemplateItWasCreatedWith() throws Exception {
        Semaphore provisioning = new Semaphore(0);
        CountDownLatch release = new CountDownLatch(1);
        CfnResourceDispatcher dispatcher = mock(CfnResourceDispatcher.class,
                withSettings().defaultAnswer(invocation -> "provision".equals(invocation.getMethod().getName())
                        ? heldResource(invocation.getArgument(0), invocation.getArgument(1), provisioning, release)
                        : RETURNS_DEFAULTS.answer(invocation)));
        CloudFormationService service = CloudFormationServiceFixture.create(dispatcher);
        ThreadPoolExecutor pool = CloudFormationService.newOperationExecutor();
        int threads = pool.getMaximumPoolSize();
        pool.shutdown();
        List<Future<?>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                runs.add(create(service, "held-" + i, HELD));
            }
            assertTrue(provisioning.tryAcquire(threads, 30, TimeUnit.SECONDS), "not every operation thread is held");
            runs.add(create(service, "queued", QUEUED));

            assertEquals(QUEUED, service.getTemplate("queued", null, REGION));
            assertEquals(List.of("AWS::SQS::Queue"),
                    service.getTemplateSummary("queued", null, null, REGION).resourceTypes());
        } finally {
            release.countDown();
        }
        for (Future<?> run : runs) {
            run.get(30, TimeUnit.SECONDS);
        }
    }

    /** A resource created once {@code release} lets it, counted on {@code provisioning} when its creation starts. */
    private static StackResource heldResource(String logicalId, String type, Semaphore provisioning,
                                              CountDownLatch release) throws InterruptedException {
        provisioning.release();
        assertTrue(release.await(30, TimeUnit.SECONDS), "the held creations were not released");
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType(type);
        resource.setPhysicalId(logicalId + "-id");
        resource.setStatus("CREATE_COMPLETE");
        return resource;
    }

    private static Future<?> create(CloudFormationService service, String stackName, String template) {
        service.createChangeSet(stackName, "create", "CREATE", template, null, Map.of(), List.of(), Map.of(), REGION,
                ACCOUNT);
        return service.executeChangeSet(stackName, "create", REGION, ACCOUNT);
    }
}
