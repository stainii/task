package be.stijnhooft.task.backend.config;

/// The runtime configuration a browser fetches before it authenticates, plus the one fact
/// [ADR-0009](../../../../../../../docs/adr/0009-the-app-is-its-own-monitor.md) monitors this whole
/// application with. See [ClientConfigController].
///
/// `buildTime` is an ISO-8601 instant, and it is **the back end saying when the back end was
/// built** — the only party that can. A date compiled into the front-end bundle reports when *that
/// device's cached bundle* was built, which after a failed deploy is indistinguishable from a
/// successful one.
public record ClientConfigDto(KeycloakConfigDto keycloak, String buildTime) {
}
