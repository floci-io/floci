package io.github.hectorvent.floci.services.ssm.model;

import java.util.List;

/**
 * One entry of {@code DescribeParameters}' {@code ParameterFilters} (or a translated legacy
 * {@code Filters} entry). A null {@code option} means the caller omitted it.
 */
public record ParameterStringFilter(String key, String option, List<String> values) {
}
