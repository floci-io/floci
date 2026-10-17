package io.github.hectorvent.floci.services.lambda;

import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

class LambdaInitFailureMessageTest {

    @Test
    void unreachableDockerIsNamed() {
        Exception e = new RuntimeException("java.net.SocketException: No such file or directory",
                new SocketException("No such file or directory"));

        String message = LambdaExecutorService.initFailureMessage(e, true);

        assertThat(message, containsString("Docker is not reachable (No such file or directory)"));
        assertThat(message, containsString("Lambda functions run in Docker containers"));
    }

    @Test
    void otherFailuresKeepTheirMessage() {
        assertThat(LambdaExecutorService.initFailureMessage(new IllegalStateException("image pull failed"), true),
                equalTo("Failed to start Lambda container: image pull failed"));
    }

    @Test
    void anUnresolvableDockerHostIsNamed() {
        Exception e = new RuntimeException("docker.internal", new UnknownHostException("docker.internal"));

        assertThat(LambdaExecutorService.initFailureMessage(e, true),
                containsString("Docker is not reachable (docker.internal)"));
    }

    @Test
    void kubernetesSocketErrorsDoNotBlameDocker() {
        Exception e = new RuntimeException("connect failed", new SocketException("Connection refused"));

        assertThat(LambdaExecutorService.initFailureMessage(e, false),
                equalTo("Failed to start Lambda container: connect failed"));
    }
}
