package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.BackupVaultListMember;
import software.amazon.awssdk.services.backup.model.InvalidParameterValueException;
import software.amazon.awssdk.services.backup.model.ListBackupVaultsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Backup vault pagination")
class BackupVaultPaginationTest {

    @Test
    @Timeout(30)
    @DisplayName("ListBackupVaults paginator visits every vault and stops on the last page")
    void paginatorVisitsEveryVault() {
        String prefix = "compat-pages-" + UUID.randomUUID();
        List<String> created = new ArrayList<>();
        try (BackupClient backup = TestFixtures.backupClient()) {
            try {
                for (String suffix : List.of("a", "b", "c")) {
                    String name = prefix + suffix;
                    backup.createBackupVault(r -> r.backupVaultName(name));
                    created.add(name);
                }

                List<String> names = new ArrayList<>();
                ListBackupVaultsResponse last = null;
                for (ListBackupVaultsResponse page : backup.listBackupVaultsPaginator(r -> r.maxResults(1))) {
                    assertThat(page.backupVaultList()).hasSize(1);
                    names.addAll(page.backupVaultList().stream().map(BackupVaultListMember::backupVaultName).toList());
                    last = page;
                }
                assertThat(names).containsAll(created).doesNotHaveDuplicates();
                assertThat(last).isNotNull();
                assertThat(last.nextToken()).isNull();

                assertThatThrownBy(() -> backup.listBackupVaults(r -> r.maxResults(0)))
                        .isInstanceOfSatisfying(InvalidParameterValueException.class,
                                error -> assertThat(error.statusCode()).isEqualTo(400));
                assertThatThrownBy(() -> backup.listBackupVaults(r -> r.nextToken("invalid-token")))
                        .isInstanceOfSatisfying(InvalidParameterValueException.class,
                                error -> assertThat(error.statusCode()).isEqualTo(400));
            } finally {
                for (String name : created) {
                    backup.deleteBackupVault(r -> r.backupVaultName(name));
                }
            }
        }
    }
}
