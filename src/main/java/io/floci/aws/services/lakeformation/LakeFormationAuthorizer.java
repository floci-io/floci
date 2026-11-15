package io.floci.aws.services.lakeformation;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.lakeformation.model.ColumnWildcard;
import io.floci.aws.services.lakeformation.model.DataLakePrincipal;
import io.floci.aws.services.lakeformation.model.PrincipalResourcePermissions;
import io.floci.aws.services.lakeformation.model.Resource;
import io.floci.aws.services.lakeformation.model.TableResource;
import io.floci.aws.services.lakeformation.model.TableWithColumnsResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Decides whether a principal may read a table, from the grants Lake Formation stores.
 * Only table-level {@code SELECT} is evaluated: a grant on columns, an LF-Tag or a data cells filter
 * does not count, so a table reachable only through those is denied.
 */
@ApplicationScoped
public class LakeFormationAuthorizer {

    /** The group AWS grants to every Glue database that is not under Lake Formation control. */
    static final String IAM_ALLOWED_PRINCIPALS = "IAM_ALLOWED_PRINCIPALS";

    private static final String SELECT = "SELECT";
    private static final String ALL = "ALL";

    private final LakeFormationStorage storage;
    private final RegionResolver regionResolver;

    public LakeFormationAuthorizer(LakeFormationStorage storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    /**
     * Whether {@code principalArn} holds {@code SELECT} on the table, by a grant to the principal itself or
     * to {@code IAMAllowedPrincipals}, the group that leaves the table to IAM alone. Floci's Glue never stores
     * that grant, so a catalog that keeps AWS's initial settings (none stored, or
     * {@code CreateTableDefaultPermissions} naming {@code IAM_ALLOWED_PRINCIPALS}) leaves every table to IAM.
     */
    public boolean canSelect(String accountId, String principalArn, String databaseName, String tableName) {
        return RequestScopes.callAs(accountId, () -> {
            String region = regionResolver.getRegion();
            return grants(region, accountId, principalArn, databaseName, tableName)
                    || grants(region, accountId, IAM_ALLOWED_PRINCIPALS, databaseName, tableName)
                    || newTablesAreLeftToIam(region, accountId);
        });
    }

    private boolean newTablesAreLeftToIam(String region, String catalogId) {
        return storage.getDataLakeSettings(region, catalogId)
                .map(settings -> settings.getCreateTableDefaultPermissions() != null
                        && settings.getCreateTableDefaultPermissions().stream().anyMatch(permissions ->
                        permissions.getPrincipal() != null
                                && IAM_ALLOWED_PRINCIPALS.equals(permissions.getPrincipal().getDataLakePrincipalIdentifier())))
                .orElse(true);
    }

    private boolean grants(String region, String catalogId, String principalId, String databaseName,
                           String tableName) {
        DataLakePrincipal principal = new DataLakePrincipal();
        principal.setDataLakePrincipalIdentifier(principalId);
        List<PrincipalResourcePermissions> held =
                storage.listPermissions(region, catalogId, principal, null, null, false, null, null);
        for (PrincipalResourcePermissions grant : held) {
            if (allowsSelect(grant.getPermissions()) && covers(grant.getResource(), catalogId, databaseName, tableName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsSelect(List<String> permissions) {
        return permissions != null && (permissions.contains(SELECT) || permissions.contains(ALL));
    }

    private static boolean covers(Resource resource, String catalogId, String databaseName, String tableName) {
        if (resource == null) {
            return false;
        }
        TableResource table = resource.getTable();
        if (table != null) {
            return sameCatalog(table.getCatalogId(), catalogId)
                    && sameName(table.getDatabaseName(), databaseName)
                    && (table.getTableWildcard() != null || sameName(table.getName(), tableName));
        }
        TableWithColumnsResource columns = resource.getTableWithColumns();
        if (columns != null) {
            ColumnWildcard wildcard = columns.getColumnWildcard();
            boolean wholeTable = wildcard != null
                    && (wildcard.getExcludedColumnNames() == null || wildcard.getExcludedColumnNames().isEmpty());
            return wholeTable && sameCatalog(columns.getCatalogId(), catalogId)
                    && sameName(columns.getDatabaseName(), databaseName)
                    && sameName(columns.getName(), tableName);
        }
        return false;
    }

    /** Glue resolves database and table names ignoring case, so a grant written in another case still applies. */
    private static boolean sameName(String left, String right) {
        return left != null && left.equalsIgnoreCase(right);
    }

    private static boolean sameCatalog(String grantCatalogId, String catalogId) {
        return grantCatalogId == null || grantCatalogId.equals(catalogId);
    }
}
