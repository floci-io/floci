package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.accessanalyzer.AccessAnalyzerClient;
import software.amazon.awssdk.services.accessanalyzer.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.accessanalyzer.model.Type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("IAM Access Analyzer lifecycle")
class AccessAnalyzerTest {

    private static final Logger LOG = Logger.getLogger(AccessAnalyzerTest.class);

    @Test
    void analyzerLifecycleAndTypeSpecificQuotaUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only analyzer names and quota assertions");

        try (AccessAnalyzerClient client = TestFixtures.accessAnalyzerClient()) {
            String accountName = "floci-account-analyzer";
            String unusedName = "floci-unused-analyzer";
            String secondAccountName = "floci-account-analyzer-2";
            try {
                client.createAnalyzer(request -> request.analyzerName(accountName).type(Type.ACCOUNT));
                client.createAnalyzer(request -> request.analyzerName(unusedName).type(Type.ACCOUNT_UNUSED_ACCESS));

                var listed = client.listAnalyzers(request -> {});
                assertThat(listed.analyzers())
                        .extracting(analyzer -> analyzer.name())
                        .contains(accountName, unusedName);

                assertThatThrownBy(() -> client.createAnalyzer(request -> request
                                .analyzerName(secondAccountName)
                                .type(Type.ACCOUNT)))
                        .isInstanceOf(ServiceQuotaExceededException.class);
            } finally {
                deleteBestEffort(client, unusedName);
                deleteBestEffort(client, accountName);
            }
        }
    }

    private static void deleteBestEffort(AccessAnalyzerClient client, String analyzerName) {
        try {
            client.deleteAnalyzer(request -> request.analyzerName(analyzerName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for analyzerName=%s", analyzerName);
        }
    }
}
