package io.github.hectorvent.floci.lifecycle;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerAvailabilityReportTest {

    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerClient docker = mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
    private final ServiceRegistry registry = mock(ServiceRegistry.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
    private final DockerAvailabilityReport report = new DockerAvailabilityReport(lifecycle, registry, config);

    @Test
    void unreachableDaemonReportsTheReason() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.pingCmd().exec()).thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));

        assertThat(report.unreachableReason(), containsString("No such file or directory"));
    }

    @Test
    void reachableDaemonHasNoReason() {
        when(lifecycle.getDockerClient()).thenReturn(docker);

        assertThat(report.unreachableReason(), is(nullValue()));
    }

    @Test
    void noAffectedServiceEnabledSkipsTheProbe() {
        when(registry.getEnabledServices()).thenReturn(List.of("sqs", "s3", "dynamodb"));

        report.report();

        verify(lifecycle, never()).getDockerClient();
    }

    @Test
    void affectedServicesAreProbed() {
        when(registry.getEnabledServices()).thenReturn(List.of("sqs", "lambda", "rds"));
        when(config.services().lambda().executor()).thenReturn("docker");
        when(lifecycle.getDockerClient()).thenReturn(docker);

        report.report();

        verify(lifecycle).getDockerClient();
    }

    @Test
    void listsDoNotOverlap() {
        assertThat(DockerAvailabilityReport.METADATA_ONLY.stream()
                .filter(DockerAvailabilityReport.FAIL_ON_USE::contains).toList(), is(empty()));
        assertThat(DockerAvailabilityReport.FAIL_ON_USE, not(empty()));
    }

    @Test
    void servicesBackedByADockerRuntimeFailOnUse() {
        when(config.services().lambda().executor()).thenReturn("docker");
        when(config.services().duck().url()).thenReturn(Optional.empty());

        assertThat(report.failOnUse(Set.of("redshift-serverless", "rds-data", "athena", "lambda", "sqs")),
                contains("lambda", "redshift-serverless", "rds-data", "athena"));
    }

    @Test
    void servicesPointedElsewhereAreNotReported() {
        when(config.services().lambda().executor()).thenReturn(" Kubernetes ");
        when(config.services().duck().url()).thenReturn(Optional.of("http://duck:8080"));
        when(config.services().verifiedpermissions().cedarUrl()).thenReturn(Optional.of("http://cedar:8080"));

        assertThat(report.failOnUse(Set.of("lambda", "athena", "verifiedpermissions", "ecs")), contains("ecs"));
    }

    @Test
    void pipesIsQualifiedToItsKafkaSources() {
        assertThat(report.failOnUse(Set.of("pipes")), contains("pipes (Kafka sources)"));
    }
}
