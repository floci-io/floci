package io.floci.aws.services.dsql.dataplane;

/** Maps the role names clients see to the ones the cluster's database has, and back. */
public interface RoleNames {

    /** Names are the same on both sides, as with a database of the cluster's own. */
    RoleNames IDENTITY = new RoleNames() {
        @Override
        public String toBackend(String logical) {
            return logical;
        }

        @Override
        public String toLogical(String backend) {
            return backend;
        }

        @Override
        public boolean isIdentity() {
            return true;
        }
    };

    /** The backend name of the role called {@code logical}: a role's exact name, never a specifier. */
    String toBackend(String logical);

    /**
     * The backend name of a role named in SQL. Only an unquoted keyword is a role specifier
     * ({@code PUBLIC}, {@code CURRENT_USER} and the like); a {@code quoted} identifier always names
     * a role, as PostgreSQL reads it.
     */
    default String toBackend(String logical, boolean quoted) {
        return toBackend(logical);
    }

    String toLogical(String backend);

    boolean isIdentity();
}
