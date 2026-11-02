package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.util.AnnotationLiteral;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DockerClientDisposalIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void destroyingProducedClientStopsOnlyItsEvictor(boolean streaming) throws Exception {
        Annotation qualifier = streaming ? new StreamingLiteral() : Default.Literal.INSTANCE;
        InjectableBean<DockerClient> bean = Arc.container().instance(DockerClient.class, qualifier).getBean();
        CreationalContext<DockerClient> firstContext = Arc.container().beanManager().createCreationalContext(bean);
        CreationalContext<DockerClient> secondContext = Arc.container().beanManager().createCreationalContext(bean);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader marker = new ClassLoader(previous) { };
        List<Thread> firstThreads = List.of();
        List<Thread> secondThreads = List.of();
        Thread.currentThread().setContextClassLoader(marker);
        try (DockerClient first = bean.create(firstContext)) {
            firstThreads = evictors(marker);
            assertEquals(1, firstThreads.size(), "one owned connection eviction thread per client");
            try (DockerClient second = bean.create(secondContext)) {
                List<Thread> alreadyOwned = firstThreads;
                secondThreads = evictors(marker).stream().filter(thread -> !alreadyOwned.contains(thread)).toList();
                assertEquals(1, secondThreads.size());
                Thread.currentThread().setContextClassLoader(previous);

                bean.destroy(first, firstContext);
                assertStopped(firstThreads);
                assertTrue(secondThreads.getFirst().isAlive(), "disposing one client must not close another pool");
                bean.destroy(second, secondContext);
                assertStopped(secondThreads);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            firstContext.release();
            secondContext.release();
            assertStopped(firstThreads);
            assertStopped(secondThreads);
        }
    }

    private static List<Thread> evictors(ClassLoader marker) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getContextClassLoader() == marker)
                .filter(thread -> thread.getName().startsWith("idle-connection-evictor-"))
                .toList();
    }

    private static void assertStopped(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(5));
            assertFalse(thread.isAlive(), "CDI destruction must release the client thread and its classloader");
        }
    }

    private static final class StreamingLiteral extends AnnotationLiteral<StreamingDocker>
            implements StreamingDocker {
        private static final long serialVersionUID = 1L;
    }
}
