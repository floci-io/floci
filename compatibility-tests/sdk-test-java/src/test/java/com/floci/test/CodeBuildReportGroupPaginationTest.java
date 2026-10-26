package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.model.ListReportGroupsResponse;
import software.amazon.awssdk.services.codebuild.model.ReportExportConfigType;
import software.amazon.awssdk.services.codebuild.model.ReportGroupSortByType;
import software.amazon.awssdk.services.codebuild.model.ReportType;
import software.amazon.awssdk.services.codebuild.model.SortOrderType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeBuildReportGroupPaginationTest {

    @Test
    void paginatorReturnsEveryReportGroupOnce() {
        String prefix = "sdk-page-" + UUID.randomUUID() + "-";
        List<String> created = new ArrayList<>();
        try (CodeBuildClient client = TestFixtures.codeBuildClient()) {
            try {
                for (String suffix : List.of("a", "b", "c")) {
                    created.add(client.createReportGroup(request -> request.name(prefix + suffix)
                            .type(ReportType.TEST)
                            .exportConfig(config -> config.exportConfigType(ReportExportConfigType.NO_EXPORT)))
                            .reportGroup().arn());
                }
                List<String> seen = new ArrayList<>();
                int pages = 0;
                for (ListReportGroupsResponse page : client.listReportGroupsPaginator(request -> request
                        .maxResults(1).sortBy(ReportGroupSortByType.NAME).sortOrder(SortOrderType.ASCENDING))) {
                    assertThat(page.reportGroups()).hasSizeLessThanOrEqualTo(1);
                    seen.addAll(page.reportGroups());
                    assertThat(++pages).as("pagination must end").isLessThan(1000);
                }
                assertThat(seen).doesNotHaveDuplicates();
                assertThat(seen.stream().filter(arn -> arn.contains(prefix)).toList())
                        .containsExactlyElementsOf(created);
            } finally {
                for (String arn : created) {
                    client.deleteReportGroup(request -> request.arn(arn));
                }
            }
        }
    }
}
