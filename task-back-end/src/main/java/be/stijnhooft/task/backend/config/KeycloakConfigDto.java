package be.stijnhooft.task.backend.config;

/// Where the auth server is, which realm, and which client to present as.
///
/// `url` and `realm` are split rather than handed over as one issuer string because that is the
/// shape `keycloak-js` takes. No secret is in here and none exists: every client in the realm is
/// public ([#31](https://github.com/stainii/task/issues/31)), and the back-end is a resource server
/// that validates tokens rather than obtaining them.
public record KeycloakConfigDto(String url, String realm, String clientId) {
}
