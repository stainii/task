package be.stijnhooft.task.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CorsConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class SpringSecurityConfig {

    /// ADR-0010: Keycloak is shared infrastructure (#15), so a valid token from the realm proves only
    /// that someone has an account somewhere in the house. The role is the boundary; without it,
    /// adding a Keycloak user for an unrelated app silently grants that user every task in here.
    private static final String TASK_USER_ROLE = "task-user";

    @Bean
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new NullAuthenticatedSessionStrategy();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        return http.csrf(CsrfConfigurer::disable)
                .cors(CorsConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        /// ADR-0007: this is what tells a cold client where the auth server is,
                        /// so it is the one endpoint that cannot require a token.
                        .requestMatchers("/api/config").permitAll()
                        /// ADR-0009: compose's healthcheck calls this container-to-container.
                        /// Its real protection is ADR-0010's allowlist — nginx never routes /actuator.
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole(TASK_USER_ROLE))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoleConverter())))
                .build();
    }

    /// Spring's default converter reads `scope`/`scp`; Keycloak puts realm roles in `realm_access.roles`.
    /// Without this, {@code hasRole} can never match and every request is a 403.
    private static JwtAuthenticationConverter realmRoleConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SpringSecurityConfig::realmRoles);
        return converter;
    }

    private static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
