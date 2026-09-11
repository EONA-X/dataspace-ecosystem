package org.eclipse.dse.migration.spi;

/**
 * A set of schema migration scripts contributed by one module, to be applied to one datasource.
 *
 * @param moduleName       name of the contributing module, used for logging only.
 * @param dataSourceName   name of the datasource, as registered in the EDC {@code DataSourceRegistry}.
 * @param location         where the scripts live, in the form the migration engine expects, e.g.
 *                         {@code classpath:db/migration/control-plane}.
 * @param shared           true for the one set that reshapes objects owned by no single module
 *                         (see {@code SharedMigrationExtension}) -- every runtime sharing a database
 *                         carries it, so it is the set that must never run ahead of every other
 *                         runtime's own baseline. false for every module-owned set.
 */
public record MigrationSet(String moduleName, String dataSourceName, String location, boolean shared) {

    public MigrationSet {
        if (moduleName == null || moduleName.isBlank()) {
            throw new IllegalArgumentException("moduleName cannot be null or blank");
        }
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new IllegalArgumentException("dataSourceName cannot be null or blank");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location cannot be null or blank");
        }
    }
}