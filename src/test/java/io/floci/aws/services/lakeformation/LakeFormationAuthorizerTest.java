package io.floci.aws.services.lakeformation;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.lakeformation.model.ColumnWildcard;
import io.floci.aws.services.lakeformation.model.DataLakePrincipal;
import io.floci.aws.services.lakeformation.model.DataLakeSettings;
import io.floci.aws.services.lakeformation.model.PrincipalPermissions;
import io.floci.aws.services.lakeformation.model.PrincipalResourcePermissions;
import io.floci.aws.services.lakeformation.model.Resource;
import io.floci.aws.services.lakeformation.model.TableResource;
import io.floci.aws.services.lakeformation.model.TableWildcard;
import io.floci.aws.services.lakeformation.model.TableWithColumnsResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeFormationAuthorizerTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/SpectrumRole";

    private LakeFormationStorage storage;
    private LakeFormationAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        storage = mock(LakeFormationStorage.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", ACCOUNT);
        authorizer = new LakeFormationAuthorizer(storage, regionResolver);
        storeSettings(List.of());
    }

    @Test
    void aCatalogWithoutStoredSettingsLeavesEveryTableToIam() {
        when(storage.getDataLakeSettings(anyString(), anyString())).thenReturn(Optional.empty());
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void defaultTablePermissionsNamingIamAllowedPrincipalsLeaveTablesToIam() {
        storeSettings(List.of(defaultPermissionFor(LakeFormationAuthorizer.IAM_ALLOWED_PRINCIPALS)));
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void defaultTablePermissionsForAnotherPrincipalDoNotLeaveTablesToIam() {
        storeSettings(List.of(defaultPermissionFor("arn:aws:iam::000000000000:role/Other")));
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    private void storeSettings(List<PrincipalPermissions> createTableDefaults) {
        DataLakeSettings settings = new DataLakeSettings();
        settings.setCreateTableDefaultPermissions(createTableDefaults);
        when(storage.getDataLakeSettings(anyString(), anyString())).thenReturn(Optional.of(settings));
    }

    private static PrincipalPermissions defaultPermissionFor(String principalId) {
        DataLakePrincipal principal = new DataLakePrincipal();
        principal.setDataLakePrincipalIdentifier(principalId);
        PrincipalPermissions permissions = new PrincipalPermissions();
        permissions.setPrincipal(principal);
        permissions.setPermissions(List.of("ALL"));
        return permissions;
    }

    @Test
    void noGrantDenies() {
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void selectOnTheTableAllows() {
        grant(ROLE, tableResource("lake", "events"), "SELECT");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void allOnTheTableAllows() {
        grant(ROLE, tableResource("lake", "events"), "ALL");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void namesMatchIgnoringCaseLikeGlue() {
        grant(ROLE, tableResource("salesdb", "events"), "SELECT");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "SalesDB", "EVENTS"));
    }

    @Test
    void anotherTableDenies() {
        grant(ROLE, tableResource("lake", "orders"), "SELECT");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void anotherDatabaseDenies() {
        grant(ROLE, tableResource("other", "events"), "SELECT");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void describeAloneDenies() {
        grant(ROLE, tableResource("lake", "events"), "DESCRIBE");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void tableWildcardCoversEveryTableOfTheDatabase() {
        TableResource table = new TableResource();
        table.setDatabaseName("lake");
        table.setTableWildcard(new TableWildcard());
        Resource resource = new Resource();
        resource.setTable(table);
        grant(ROLE, resource, "SELECT");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "other", "events"));
    }

    @Test
    void columnWildcardCoversTheWholeTable() {
        grant(ROLE, columnsResource(new ColumnWildcard()), "SELECT");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void columnWildcardWithExclusionsDoesNotCoverTheWholeTable() {
        ColumnWildcard wildcard = new ColumnWildcard();
        wildcard.setExcludedColumnNames(List.of("secret"));
        grant(ROLE, columnsResource(wildcard), "SELECT");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void namedColumnsDoNotCoverTheWholeTable() {
        grant(ROLE, columnsResource(null), "SELECT");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void iamAllowedPrincipalsLeavesTheTableToIam() {
        grant(LakeFormationAuthorizer.IAM_ALLOWED_PRINCIPALS, tableResource("lake", "events"), "ALL");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void aGrantToAnotherPrincipalDenies() {
        grant("arn:aws:iam::000000000000:role/Other", tableResource("lake", "events"), "SELECT");
        assertFalse(authorizer.canSelect(ACCOUNT, ROLE, "lake", "events"));
    }

    @Test
    void databaseAndTableNamesMatchIgnoringCase() {
        grant(ROLE, tableResource("salesdb", "orders"), "SELECT");
        assertTrue(authorizer.canSelect(ACCOUNT, ROLE, "SalesDB", "Orders"));
    }

    private void grant(String principalId, Resource resource, String permission) {
        DataLakePrincipal principal = new DataLakePrincipal();
        principal.setDataLakePrincipalIdentifier(principalId);
        PrincipalResourcePermissions grant = new PrincipalResourcePermissions();
        grant.setPrincipal(principal);
        grant.setResource(resource);
        grant.setPermissions(List.of(permission));
        when(storage.listPermissions(anyString(), anyString(), any(DataLakePrincipal.class), isNull(), isNull(),
                eq(false), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    DataLakePrincipal asked = invocation.getArgument(2);
                    return principalId.equals(asked.getDataLakePrincipalIdentifier()) ? List.of(grant) : List.of();
                });
    }

    private static Resource tableResource(String database, String name) {
        TableResource table = new TableResource();
        table.setDatabaseName(database);
        table.setName(name);
        Resource resource = new Resource();
        resource.setTable(table);
        return resource;
    }

    private static Resource columnsResource(ColumnWildcard wildcard) {
        TableWithColumnsResource columns = new TableWithColumnsResource();
        columns.setDatabaseName("lake");
        columns.setName("events");
        columns.setColumnWildcard(wildcard);
        if (wildcard == null) {
            columns.setColumnNames(List.of("id"));
        }
        Resource resource = new Resource();
        resource.setTableWithColumns(columns);
        return resource;
    }
}
