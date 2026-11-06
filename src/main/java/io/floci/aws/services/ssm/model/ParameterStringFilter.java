package io.floci.aws.services.ssm.model;

import java.util.List;

/**
 * One {@code ParameterFilters} entry for a parameter listing (or a translated legacy
 * {@code DescribeParameters.Filters} entry). A null {@code option} means the caller omitted it.
 */
public record ParameterStringFilter(String key, String option, List<String> values) {
}
