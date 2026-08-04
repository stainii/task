package be.stijnhooft.task.backend;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Collections;

@Import(TestcontainersConfiguration.class)
public class AbstractIntegrationTestCases {

    static KeycloakContainer keycloakContainer;

    /**
     * Pinned explicitly rather than inheriting testcontainers-keycloak's default, so that tests and
     * {@code compose.yaml} provably run the same Keycloak and a library bump cannot move it silently.
     */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.0";

    static {
        keycloakContainer = new KeycloakContainer(KEYCLOAK_IMAGE)
                .withRealmImportFile("keycloak/realm-export.json")
                .withReuse(true);
        keycloakContainer.start();
    }

    @DynamicPropertySource
    static void registerResourceServerIssuerProperty(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> keycloakContainer.getAuthServerUrl() + "/realms/test-realm");
    }

    protected String getAuthorizationHeaderForUser() {
        var authorizationURI = keycloakContainer.getAuthServerUrl() + "/realms/test-realm/protocol/openid-connect/token";
        RestClient restClient = RestClient.builder().build();
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.put("grant_type", Collections.singletonList("password"));
        formData.put("client_id", Collections.singletonList("test-client"));
        formData.put("username", Collections.singletonList("testuser"));
        formData.put("password", Collections.singletonList("secret123"));

        String result = restClient.post()
                .uri(authorizationURI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(String.class);

        JacksonJsonParser jsonParser = new JacksonJsonParser();
        return "Bearer " + jsonParser.parseMap(result)
                .get("access_token")
                .toString();
    }

}
