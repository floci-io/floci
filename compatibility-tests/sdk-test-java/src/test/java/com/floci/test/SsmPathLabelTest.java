package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterStringFilter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SsmPathLabelTest {

    @Test
    void pathLabelsSelectHistoricalValuesAndFollowLabelChanges() {
        String path = "/sdk-test/path-label-" + UUID.randomUUID();
        String name = path + "/config";
        String other = path + "/unlabeled";
        try (SsmClient ssm = TestFixtures.ssmClient()) {
            try {
                ssm.putParameter(r -> r.name(name).type(ParameterType.STRING).value("released"));
                ssm.labelParameterVersion(r -> r.name(name).parameterVersion(1L).labels("release"));
                ssm.putParameter(r -> r.name(name).type(ParameterType.STRING).value("next").overwrite(true));
                ssm.putParameter(r -> r.name(other).type(ParameterType.STRING).value("other"));

                List<Parameter> released = parameters(ssm, path, "release");
                assertThat(released).extracting(Parameter::name).containsExactly(name);
                assertThat(released).extracting(Parameter::version).containsExactly(1L);
                assertThat(released).extracting(Parameter::value).containsExactly("released");
                assertThat(parameters(ssm, path, "missing")).isEmpty();

                ssm.labelParameterVersion(r -> r.name(name).parameterVersion(2L).labels("release"));
                assertThat(parameters(ssm, path, "release")).extracting(Parameter::value).containsExactly("next");
                ssm.unlabelParameterVersion(r -> r.name(name).parameterVersion(2L).labels("release"));
                assertThat(parameters(ssm, path, "release")).isEmpty();
            } finally {
                ssm.deleteParameters(r -> r.names(name, other));
            }
        }
    }

    @Test
    void paginatorDoesNotSkipVersionsThatShareAParameterName() {
        String path = "/sdk-test/path-label-" + UUID.randomUUID();
        String name = path + "/config";
        try (SsmClient ssm = TestFixtures.ssmClient()) {
            try {
                ssm.putParameter(r -> r.name(name).type(ParameterType.STRING).value("first"));
                ssm.labelParameterVersion(r -> r.name(name).parameterVersion(1L).labels("release", "stable"));
                ssm.putParameter(r -> r.name(name).type(ParameterType.STRING).value("second").overwrite(true));
                ssm.labelParameterVersion(r -> r.name(name).parameterVersion(2L).labels("preview"));

                List<Parameter> versions = parameters(ssm, path, "release", "stable", "preview");
                assertThat(versions).extracting(Parameter::name).containsExactly(name, name);
                assertThat(versions).extracting(Parameter::version).containsExactly(1L, 2L);
                assertThat(versions).extracting(Parameter::value).containsExactly("first", "second");
            } finally {
                ssm.deleteParameter(r -> r.name(name));
            }
        }
    }

    private List<Parameter> parameters(SsmClient ssm, String path, String... labels) {
        return ssm.getParametersByPathPaginator(GetParametersByPathRequest.builder().path(path).maxResults(1)
                .parameterFilters(ParameterStringFilter.builder().key("Label").option("Equals").values(labels).build())
                .build()).stream().flatMap(page -> page.parameters().stream()).toList();
    }
}
