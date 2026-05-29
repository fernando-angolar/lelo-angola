// RED: este teste falha porque:
//   1. POST /auth/refresh recebe Authorization header, não body JSON — contrato errado
//   2. POST /auth/logout não existe
//   3. GET /auth/me não existe
//   4. PUT /auth/change-password não existe
//   5. GET /admin/users não existe
//   6. PUT /admin/users/{id}/toggle-status não existe
//   7. SEC-04: registo com role=ADMIN não retorna 400 (lógica ausente)
//   8. SEC-05: login com utilizador desactivado não retorna 403
//   9. SEC-07: JwtFilter não verifica blacklist Redis
package ao.com.angotech.modules.auth;

import ao.com.angotech.modules.auth.domain.User;
import ao.com.angotech.modules.auth.dto.AuthResponse;
import ao.com.angotech.modules.auth.dto.LoginRequest;
import ao.com.angotech.modules.auth.dto.RegisterRequest;
import ao.com.angotech.modules.auth.repository.UserRepository;
import ao.com.angotech.modules.auth.security.JwtService;
import ao.com.angotech.modules.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes de integração para AuthController via MockMvc.
 * Verifica: rotas, status codes, formato ApiResponse<T>, regras de negócio na camada HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthControllerTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static int seq = 0;

    @BeforeEach
    void limpar() {
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        seq++;
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private String emailUnico() {
        return "ctrl" + seq + "@lelo.ao";
    }

    private AuthResponse registarEFazerLogin(String email) {
        authService.register(new RegisterRequest(email, "Senha@1234", "Teste Ctrl", "+244923000001", "BUYER"));
        return authService.login(new LoginRequest(email, "Senha@1234"));
    }

    private User criarAdmin(String email) {
        authService.register(new RegisterRequest(email, "Senha@1234", "Admin Ctrl", null, "SELLER"));
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setRoles(List.of("ROLE_ADMIN"));
        return userRepository.save(user);
    }

    // -------------------------------------------------------------------------
    // POST /auth/register
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado dados válidos, quando POST /auth/register, então retorna 201 com ApiResponse<UserResponse>")
    void dadoDadosValidos_quandoRegisto_entaoRetorna201() throws Exception {
        Map<String, String> body = Map.of(
                "email", emailUnico(),
                "password", "Senha@1234",
                "fullName", "Novo Utilizador",
                "phone", "+244923000001",
                "role", "BUYER"
        );

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(body.get("email")))
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    @DisplayName("dado email duplicado, quando POST /auth/register, então retorna 409")
    void dadoEmailDuplicado_quandoRegisto_entaoRetorna409() throws Exception {
        String email = emailUnico();
        authService.register(new RegisterRequest(email, "Senha@1234", "Primeiro", null, "BUYER"));

        Map<String, String> body = Map.of(
                "email", email,
                "password", "Senha@1234",
                "fullName", "Segundo",
                "role", "BUYER"
        );

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));
    }

    // -------------------------------------------------------------------------
    // SEC-04: ADMIN não pode ser criado via registo público
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado role=ADMIN no registo, quando POST /auth/register, então retorna 400 (SEC-04)")
    void dadoRegistoComRoleAdmin_quandoRegista_entaoRetorna400() throws Exception {
        Map<String, String> body = Map.of(
                "email", emailUnico(),
                "password", "Senha@1234",
                "fullName", "Tentativa Admin",
                "role", "ADMIN"
        );

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // -------------------------------------------------------------------------
    // POST /auth/login
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado credenciais válidas, quando POST /auth/login, então retorna 200 com tokens")
    void dadoCredenciaisValidas_quandoLogin_entaoRetorna200ComTokens() throws Exception {
        String email = emailUnico();
        authService.register(new RegisterRequest(email, "Senha@1234", "Login Test", null, "BUYER"));

        Map<String, String> body = Map.of("email", email, "password", "Senha@1234");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").isNumber());
    }

    // -------------------------------------------------------------------------
    // SEC-05: utilizador desactivado não consegue autenticar
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador desactivado, quando POST /auth/login, então retorna 403 (SEC-05)")
    void dadoUserDesactivado_quandoLogin_entaoRetorna403() throws Exception {
        String email = emailUnico();
        authService.register(new RegisterRequest(email, "Senha@1234", "Desactivado", null, "BUYER"));
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        Map<String, String> body = Map.of("email", email, "password", "Senha@1234");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    // -------------------------------------------------------------------------
    // POST /auth/refresh — contrato correcto: body JSON, não header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado refreshToken válido em body JSON, quando POST /auth/refresh, então retorna 200 com novo par de tokens")
    void dadoRefreshTokenValido_quandoRefresh_entaoRetornaNovoParDeTokens() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        Map<String, String> body = Map.of("refreshToken", loginResponse.refreshToken());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                // Os novos tokens devem ser diferentes dos originais
                .andExpect(jsonPath("$.data.accessToken").value(not(loginResponse.accessToken())))
                .andExpect(jsonPath("$.data.refreshToken").value(not(loginResponse.refreshToken())));
    }

    @Test
    @DisplayName("dado refreshToken inválido em body, quando POST /auth/refresh, então retorna 401")
    void dadoRefreshTokenInvalido_quandoRefresh_entaoRetorna401() throws Exception {
        Map<String, String> body = Map.of("refreshToken", "token.completamente.invalido");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("dado refreshToken via Authorization header (contrato antigo), quando POST /auth/refresh, então retorna 400")
    void dadoRefreshViaHeaderAntigo_quandoRefresh_entaoRetorna400OuIgnora() throws Exception {
        // Este teste documenta que o contrato CORRECTO é via body JSON, não via header.
        // O endpoint deve rejeitar o formato antigo (ou simplesmente não o suportar).
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        // Enviar sem body — deve falhar com 400 (body obrigatório)
        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + loginResponse.refreshToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /auth/logout
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado token válido, quando POST /auth/logout, então retorna 204 No Content")
    void dadoTokenValido_quandoLogout_entaoRetorna204() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        Map<String, String> body = Map.of("refreshToken", loginResponse.refreshToken());

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("dado logout, quando tenta usar o mesmo accessToken, então retorna 401 (SEC-07)")
    void dadoTokenNaBlacklist_quandoAcessaEndpointProtegido_entaoRetorna401() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);
        String accessToken = loginResponse.accessToken();

        // Fazer logout
        Map<String, String> logoutBody = Map.of("refreshToken", loginResponse.refreshToken());
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutBody)))
                .andExpect(status().isNoContent());

        // Tentar usar o mesmo token para aceder a recurso protegido
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("dado sem Authorization header, quando POST /auth/logout, então retorna 401")
    void dadoSemToken_quandoLogout_entaoRetorna401() throws Exception {
        Map<String, String> body = Map.of("refreshToken", "qualquer-token");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /auth/me
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado token válido, quando GET /auth/me, então retorna perfil do utilizador autenticado")
    void dadoTokenValido_quandoGetMe_entaoRetornaPerfil() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + loginResponse.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.fullName").isNotEmpty())
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("dado sem token, quando GET /auth/me, então retorna 401")
    void dadoSemToken_quandoGetMe_entaoRetorna401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PUT /auth/change-password
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado password correcta, quando PUT /auth/change-password, então retorna 200")
    void dadoPasswordCorrecta_quandoChangePassword_entaoRetorna200() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        Map<String, String> body = Map.of(
                "currentPassword", "Senha@1234",
                "newPassword", "NovaSenha@9999"
        );

        mockMvc.perform(put("/auth/change-password")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("dado password incorrecta, quando PUT /auth/change-password, então retorna 400")
    void dadoPasswordIncorrecta_quandoChangePassword_entaoRetorna400() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        Map<String, String> body = Map.of(
                "currentPassword", "SenhaErrada@000",
                "newPassword", "NovaSenha@9999"
        );

        mockMvc.perform(put("/auth/change-password")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("dado nova password inválida (menos de 8 chars), quando PUT /auth/change-password, então retorna 400")
    void dadoNovaPasswordCurta_quandoChangePassword_entaoRetorna400() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        Map<String, String> body = Map.of(
                "currentPassword", "Senha@1234",
                "newPassword", "curta"
        );

        mockMvc.perform(put("/auth/change-password")
                        .header("Authorization", "Bearer " + loginResponse.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /admin/users (ADMIN only)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado role ADMIN, quando GET /admin/users, então retorna lista paginada com 200")
    void dadoRoleAdmin_quandoListaUsers_entaoRetornaListaPaginada() throws Exception {
        String adminEmail = emailUnico();
        User admin = criarAdmin(adminEmail);
        AuthResponse adminLogin = authService.login(new LoginRequest(adminEmail, "Senha@1234"));

        // Criar alguns utilizadores para listar
        for (int i = 0; i < 3; i++) {
            authService.register(new RegisterRequest(
                    "buyer" + seq + i + "@lelo.ao", "Senha@1234", "Buyer " + i, null, "BUYER"
            ));
        }

        mockMvc.perform(get("/admin/users")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + adminLogin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").isNumber())
                .andExpect(jsonPath("$.data.totalPages").isNumber());
    }

    @Test
    @DisplayName("dado role BUYER, quando GET /admin/users, então retorna 403")
    void dadoRoleBuyer_quandoListaUsers_entaoRetorna403() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer " + loginResponse.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("dado role ADMIN e search param, quando GET /admin/users?search=buyer, então retorna utilizadores filtrados")
    void dadoAdmin_quandoListaUsersComSearch_entaoFiltraResultados() throws Exception {
        String adminEmail = emailUnico();
        criarAdmin(adminEmail);
        AuthResponse adminLogin = authService.login(new LoginRequest(adminEmail, "Senha@1234"));

        authService.register(new RegisterRequest(
                "joao" + seq + "@lelo.ao", "Senha@1234", "João Silva", null, "BUYER"
        ));
        authService.register(new RegisterRequest(
                "maria" + seq + "@lelo.ao", "Senha@1234", "Maria Santos", null, "BUYER"
        ));

        mockMvc.perform(get("/admin/users")
                        .param("page", "0")
                        .param("size", "20")
                        .param("search", "joão")
                        .header("Authorization", "Bearer " + adminLogin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("dado role ADMIN e filtro enabled=false, quando GET /admin/users, então só retorna desactivados")
    void dadoAdmin_quandoListaUsersComEnabledFalse_entaoSoRetornaDesactivados() throws Exception {
        String adminEmail = emailUnico();
        criarAdmin(adminEmail);
        AuthResponse adminLogin = authService.login(new LoginRequest(adminEmail, "Senha@1234"));

        // Criar um utilizador e desactivá-lo
        authService.register(new RegisterRequest(
                "disabled" + seq + "@lelo.ao", "Senha@1234", "Desactivado", null, "BUYER"
        ));
        User desactivado = userRepository.findByEmail("disabled" + seq + "@lelo.ao").orElseThrow();
        desactivado.setEnabled(false);
        userRepository.save(desactivado);

        mockMvc.perform(get("/admin/users")
                        .param("page", "0")
                        .param("size", "20")
                        .param("enabled", "false")
                        .header("Authorization", "Bearer " + adminLogin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].enabled", everyItem(equalTo(false))));
    }

    // -------------------------------------------------------------------------
    // PUT /admin/users/{userId}/toggle-status (ADMIN only)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado role ADMIN, quando PUT /admin/users/{id}/toggle-status, então altera estado e retorna 200")
    void dadoAdmin_quandoToggleStatus_entaoAlteraEstado() throws Exception {
        String adminEmail = emailUnico();
        criarAdmin(adminEmail);
        AuthResponse adminLogin = authService.login(new LoginRequest(adminEmail, "Senha@1234"));

        String targetEmail = "target" + seq + "@lelo.ao";
        authService.register(new RegisterRequest(targetEmail, "Senha@1234", "Target User", null, "BUYER"));
        User target = userRepository.findByEmail(targetEmail).orElseThrow();

        mockMvc.perform(put("/admin/users/" + target.getId() + "/toggle-status")
                        .header("Authorization", "Bearer " + adminLogin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("dado role BUYER, quando PUT /admin/users/{id}/toggle-status, então retorna 403")
    void dadoRoleBuyer_quandoToggleStatus_entaoRetorna403() throws Exception {
        String email = emailUnico();
        AuthResponse loginResponse = registarEFazerLogin(email);

        mockMvc.perform(put("/admin/users/" + UUID.randomUUID() + "/toggle-status")
                        .header("Authorization", "Bearer " + loginResponse.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("dado admin e utilizadorId inexistente, quando toggle-status, então retorna 404")
    void dadoAdmin_quandoToggleStatusUserInexistente_entaoRetorna404() throws Exception {
        String adminEmail = emailUnico();
        criarAdmin(adminEmail);
        AuthResponse adminLogin = authService.login(new LoginRequest(adminEmail, "Senha@1234"));

        mockMvc.perform(put("/admin/users/" + UUID.randomUUID() + "/toggle-status")
                        .header("Authorization", "Bearer " + adminLogin.accessToken()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // SEC-03: endpoint protegido sem token → 401
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado sem Authorization header, quando acessa endpoint protegido, então retorna 401 (SEC-03)")
    void dadoSemToken_quandoAcessaEndpointProtegido_entaoRetorna401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
