# Telemetry Agent Migration

Deliberately empty. The telemetry-agent owns no table -- see the module's `README.md` at the project
root of this Gradle module and `TelemetryAgentMigrationExtension`'s javadoc. This location exists only
so Flyway does not have to fall back on `failOnMissingLocations=false`'s silent-skip path for a
`classpath:` location that resolves to nothing at all; a location that resolves to zero `.sql` scripts
is the ordinary, logged case every other module's baseline eventually reaches once it has no pending
delta, not a special one.

Do not add a `.sql` script here for a table this module does not own. If the telemetry-agent's own
store ever needs a schema of its own, give that store its own migration set instead, the same way
`extensions/common/store/sql/telemetry-store-sql` would (see `flyway-core/README.md`'s worked
example) -- this module's only job is the barrier signal key, not schema ownership.
