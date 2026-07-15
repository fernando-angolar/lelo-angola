package ao.com.angotech.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste E2E do fluxo de autenticação como um cliente externo faria, sobre HTTP real.
 *
 * Fluxo (plano 1.0):
 *   registar -> login -> usar API (/auth/me) -> logout -> tentar usar o token invalidado
 *
 * Infra real via Testcontainers (PostgreSQL + Redis para a blacklist de tokens).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AuthFlowE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lelo_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = json();
        headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    @Test
    @DisplayName("fluxo completo: registar, login, usar API, logout e tentar reutilizar o token invalidado")
    void fluxoCompleto_registar_login_usarApi_logout_tentarUsarTokenInvalidado() throws Exception {
        String email = "e2e-" + System.nanoTime() + "@lelo.ao";
        String password = "Senha@1234";

        // 1. REGISTAR -> 201 com UserResponse
        Map<String, String> registerBody = Map.of(
                "email", email,
                "password", password,
                "fullName", "Utilizador E2E",
                "phone", "+244923000123",
                "role", "BUYER"
        );
        ResponseEntity<String> registerResponse = rest.postForEntity(
                url("/auth/register"),
                new HttpEntity<>(objectMapper.writeValueAsString(registerBody), json()),
                String.class
        );
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode registerData = body(registerResponse).get("data");
        assertThat(body(registerResponse).get("success").asBoolean()).isTrue();
        assertThat(registerData.get("email").asText()).isEqualTo(email);
        assertThat(registerData.get("id").asText()).isNotBlank();

        // 2. LOGIN -> 200 com par de tokens
        Map<String, String> loginBody = Map.of("email", email, "password", password);
        ResponseEntity<String> loginResponse = rest.postForEntity(
                url("/auth/login"),
                new HttpEntity<>(objectMapper.writeValueAsString(loginBody), json()),
                String.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loginData = body(loginResponse).get("data");
        String accessToken = loginData.get("accessToken").asText();
        String refreshToken = loginData.get("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(loginData.get("tokenType").asText()).isEqualTo("Bearer");

        // 3. USAR A API: GET /auth/me com o accessToken -> 200 com o próprio perfil
        ResponseEntity<String> meResponse = rest.exchange(
                url("/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                String.class
        );
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode meData = body(meResponse).get("data");
        assertThat(meData.get("email").asText()).isEqualTo(email);
        assertThat(meData.get("roles").isArray()).isTrue();

        // 4. LOGOUT -> 204 (invalida access + refresh na blacklist Redis)
        Map<String, String> logoutBody = Map.of("refreshToken", refreshToken);
        ResponseEntity<String> logoutResponse = rest.exchange(
                url("/auth/logout"),
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(logoutBody), bearer(accessToken)),
                String.class
        );
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 5. REUTILIZAR o token invalidado em /auth/me -> 401 (SEC-07: blacklist)
        ResponseEntity<String> afterLogout = rest.exchange(
                url("/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                String.class
        );
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 6. O refreshToken invalidado também não deve permitir renovar -> 401
        Map<String, String> refreshBody = Map.of("refreshToken", refreshToken);
        ResponseEntity<String> refreshAfterLogout = rest.postForEntity(
                url("/auth/refresh"),
                new HttpEntity<>(objectMapper.writeValueAsString(refreshBody), json()),
                String.class
        );
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
