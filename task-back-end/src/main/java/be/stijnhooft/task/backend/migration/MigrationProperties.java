package be.stijnhooft.task.backend.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/// Where the restored archive is, for this run.
///
/// **No value here is committed with anything real in it.** The archive lives outside the repo,
/// at `~/portal-archive/2026-08-04/`, and is restored into throwaway containers whose ports and
/// passwords are the operator's business — so the defaults point at localhost and the
/// [#31](https://github.com/stainii/task/issues/31) rule holds: committed config carries
/// deliberately worthless values.
///
/// @param mongoUri     connection string for the restored `todo` Mongo database
/// @param mongoDatabase the database name inside that dump. `todo` — **not** `portal-todo`, which is
///                     the correction [#35](https://github.com/stainii/task/issues/35) made to
///                     DB-001's inferred name
/// @param deployments  the four restored `portal-recurring-tasks` databases. The `name` of each must
///                     be the **deployment name** as it appears in `subscription.origin`, not the
///                     database name — they differ in case, and for social they differ altogether
@ConfigurationProperties(prefix = "task.migration")
public record MigrationProperties(
        String mongoUri,
        String mongoDatabase,
        List<Deployment> deployments) {

    public MigrationProperties {
        deployments = List.copyOf(deployments);
    }

    /// @param name the deployment name, matching `subscription.origin`
    /// @param url  JDBC url of the restored database
    public record Deployment(String name, String url, String username, String password) {
    }
}
