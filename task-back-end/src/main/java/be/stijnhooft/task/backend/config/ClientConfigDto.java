package be.stijnhooft.task.backend.config;

/// The runtime configuration a browser fetches before it authenticates. See [ClientConfigController].
public record ClientConfigDto(KeycloakConfigDto keycloak) {
}
