package io.github.hectorvent.floci.core.common.docker;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier for the DockerClient dedicated to long-lived streaming operations —
 * container log-follow ({@link ContainerLogStreamer}) and {@code execStartCmd} output
 * streams held open for a whole CodeBuild phase. These calls occupy a connection pool
 * slot for a container's (or build's) entire lifetime, so they must not share a pool
 * with short-lived control-plane calls (create/start/stop/remove/copyArchive): a fan-out
 * of many long-lived streams would otherwise starve the control-plane calls out of a
 * lease until httpclient5's connection-request timeout fires. See
 * {@link DockerClientProducer} for the two beans this qualifier distinguishes.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface StreamingDocker {
}
