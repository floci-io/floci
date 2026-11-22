/*
 * Adapted from docker-java com.github.dockerjava.core.DefaultDockerCmdExecFactory (version 3.7.1, Apache License 2.0,
 * https://github.com/docker-java/docker-java). Final there; the copy hands out FlociInvocationBuilder, so stream callbacks run on virtual threads.
 */
package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.core.AbstractDockerCmdExecFactory;
import com.github.dockerjava.core.InvocationBuilder;
import com.github.dockerjava.core.RemoteApiVersion;
import com.github.dockerjava.core.WebTarget;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class FlociDockerCmdExecFactory extends AbstractDockerCmdExecFactory {

    private final DockerHttpClient dockerHttpClient;

    private final ObjectMapper objectMapper;

    FlociDockerCmdExecFactory(
        DockerHttpClient dockerHttpClient,
        ObjectMapper objectMapper
    ) {
        this.dockerHttpClient = dockerHttpClient;
        this.objectMapper = objectMapper;
    }

    public DockerHttpClient getDockerHttpClient() {
        return dockerHttpClient;
    }

    @Override
    protected WebTarget getBaseResource() {
        return new FlociWebTarget();
    }

    @Override
    public void close() throws IOException {
        dockerHttpClient.close();
    }

    private class FlociWebTarget implements WebTarget {

        final ImmutableList<String> path;

        final SetMultimap<String, String> queryParams;

        FlociWebTarget() {
            this(
                ImmutableList.of(),
                MultimapBuilder.hashKeys().hashSetValues().build()
            );
        }

        FlociWebTarget(
            ImmutableList<String> path,
            SetMultimap<String, String> queryParams
        ) {
            this.path = path;
            this.queryParams = queryParams;
        }

        @Override
        public String toString() {
            return String.format("FlociWebTarget{path=%s, queryParams=%s}", path, queryParams);
        }

        @Override
        public InvocationBuilder request() {
            String resource = StringUtils.join(path, "/");

            if (!resource.startsWith("/")) {
                resource = "/" + resource;
            }

            RemoteApiVersion apiVersion = getDockerClientConfig().getApiVersion();
            if (apiVersion != RemoteApiVersion.UNKNOWN_VERSION) {
                resource = "/" + apiVersion.asWebPathPart() + resource;
            }

            if (!queryParams.isEmpty()) {
                Escaper urlFormParameterEscaper = UrlEscapers.urlFormParameterEscaper();
                resource = queryParams.asMap().entrySet().stream()
                    .flatMap(entry -> {
                        return entry.getValue().stream().map(s -> {
                            return entry.getKey() + "=" + urlFormParameterEscaper.escape(s);
                        });
                    })
                    .collect(Collectors.joining("&", resource + "?", ""));
            }

            return new FlociInvocationBuilder(
                dockerHttpClient, objectMapper, resource
            );
        }

        @Override
        public FlociWebTarget path(String... components) {
            ImmutableList<String> newPath = ImmutableList.<String>builder()
                .addAll(path)
                .add(components)
                .build();
            return new FlociWebTarget(newPath, queryParams);
        }

        @Override
        public FlociWebTarget resolveTemplate(String name, Object value) {
            ImmutableList.Builder<String> newPath = ImmutableList.builder();
            for (String component : path) {
                component = component.replaceAll(
                    "\\{" + name + "\\}",
                    UrlEscapers.urlPathSegmentEscaper().escape(value.toString())
                );
                newPath.add(component);
            }

            return new FlociWebTarget(newPath.build(), queryParams);
        }

        @Override
        public FlociWebTarget queryParam(String name, Object value) {
            if (value == null) {
                return this;
            }

            SetMultimap<String, String> newQueryParams = HashMultimap.create(queryParams);
            newQueryParams.put(name, value.toString());

            return new FlociWebTarget(path, newQueryParams);
        }

        @Override
        public FlociWebTarget queryParamsSet(String name, Set<?> values) {
            SetMultimap<String, String> newQueryParams = HashMultimap.create(queryParams);
            newQueryParams.replaceValues(name, values.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toSet()));

            return new FlociWebTarget(path, newQueryParams);
        }

        @Override
        public FlociWebTarget queryParamsJsonMap(String name, Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return this;
            }

            // when param value is JSON string
            try {
                return queryParam(name, objectMapper.writeValueAsString(values));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
