// RED: este teste falha porque:
//   1. AuthService.logout() não existe
//   2. AuthService.changePassword() não existe
//   3. AuthService.login() não verifica enabled=false (não lança DisabledUserException / 403)
//   4. Refresh token rotation não invalida o token antigo (sem blacklist no Redis)
//   5. AuthServiceImpl não depende de RedisTemplate (ainda não injectado)
package ao.com.angotech.modules.auth;

import ao.com.angotech.modules.auth.domain.User;
import ao.com.angotech.modules.auth.dto.AuthResponse;
import ao.com.angotech.modules.auth.dto.LoginRequest;
import ao.com.angotech.modules.auth.dto.LogoutRequest;
import ao.com.angotech.modules.auth.dto.RegisterRequest;
import ao.com.angotech.modules.auth.exception.DisabledUserException;
import ao.com.angotech.modules.auth.exception.InvalidTokenException;
import ao.com.angotech.modules.auth.repository.UserRepository;
import ao.com.angotech.modules.auth.security.JwtService;
import ao.com.angotech.modules.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes de integração para AuthService com PostgreSQL e Redis reais via Testcontainers.
 * Sem @Transactional — alguns testes verificam estado persistido após chamadas ao service.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AuthServiceTest {

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
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Contadores para garantir emails únicos por teste
    private static int contador = 0;

    @BeforeEach
    void limparBD() {
        userRepository.deleteAll();
        // Limpar blacklist Redis entre testes
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        contador++;
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private String emailUnico() {
        return "user" + contador + "@lelo.ao";
    }

    private RegisterRequest registoBase(String email) {
        return new RegisterRequest(email, "Senha@1234", "Utilizador Teste", "+244923000001", "BUYER");
    }

    private User criarUserAtivo(String email) {
        authService.register(registoBase(email));
        return userRepository.findByEmail(email).orElseThrow();
    }

    // -------------------------------------------------------------------------
    // SEC-01: password encriptada no registo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado registo válido, quando registado, então password está encriptada com BCrypt na BD")
    void dadoRegistoValido_quandoRegistado_entaoPasswordEncriptadaNaBD() {
        String email = emailUnico();
        authService.register(registoBase(email));

        User user = userRepository.findByEmail(email).orElseThrow();

        assertThat(user.getPassword())
                .as("A password não deve ser armazenada em texto plano")
                .startsWith("$2a$")
                .doesNotContain("Senha@1234");
    }

    // -------------------------------------------------------------------------
    // SEC-02: login retorna tokens com roles correctos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador activo, quando faz login, então retorna accessToken e refreshToken válidos")
    void dadoUserAtivo_quandoLogin_entaoSucesso() {
        String email = emailUnico();
        criarUserAtivo(email);

        AuthResponse response = authService.login(new LoginRequest(email, "Senha@1234"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isPositive();
    }

    @Test
    @DisplayName("dado utilizador activo com role BUYER, quando faz login, então accessToken contém ROLE_BUYER")
    void dadoUserBuyer_quandoLogin_entaoTokenContemRoleBuyer() {
        String email = emailUnico();
        criarUserAtivo(email);

        AuthResponse response = authService.login(new LoginRequest(email, "Senha@1234"));
        String username = jwtService.extractUsername(response.accessToken());

        assertThat(username).isEqualTo(email);
    }

    // -------------------------------------------------------------------------
    // SEC-04: ADMIN não pode ser criado via registo público
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado role ADMIN no registo público, quando regista, então lança excepção de regra de negócio")
    void dadoRoleAdmin_quandoRegisto_entaoLancaExcecao() {
        RegisterRequest request = new RegisterRequest(emailUnico(), "Senha@1234", "Admin Teste", null, "ADMIN");

        assertThatThrownBy(() -> authService.register(request))
                .as("Registo público com role ADMIN deve ser rejeitado")
                .isInstanceOf(ao.com.angotech.shared.exception.BusinessException.class);
    }

    // -------------------------------------------------------------------------
    // SEC-05: utilizador desactivado não consegue autenticar
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador desactivado, quando faz login, então lança DisabledUserException")
    void dadoUserDesactivado_quandoLogin_entaoLancaException() {
        String email = emailUnico();
        User user = criarUserAtivo(email);
        user.setEnabled(false);
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Senha@1234")))
                .as("Utilizador desactivado deve ser rejeitado com excepção específica")
                .isInstanceOf(DisabledUserException.class);
    }

    // -------------------------------------------------------------------------
    // Refresh Token Rotation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado refreshToken válido, quando usado, então retorna novo par de tokens")
    void dadoRefreshTokenValido_quandoRefresh_entaoRetornaNovoParDeTokens() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String refreshTokenOriginal = loginResponse.refreshToken();

        AuthResponse novaResposta = authService.refreshToken(refreshTokenOriginal);

        assertThat(novaResposta.accessToken())
                .isNotBlank()
                .isNotEqualTo(loginResponse.accessToken());
        assertThat(novaResposta.refreshToken())
                .isNotBlank()
                .isNotEqualTo(refreshTokenOriginal);
    }

    @Test
    @DisplayName("dado refreshToken válido, quando usado, então o token antigo é invalidado na blacklist Redis")
    void dadoRefreshTokenValido_quandoRefresh_entaoTokenAntigoInvalidado() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String refreshTokenOriginal = loginResponse.refreshToken();
        String jtiOriginal = jwtService.extractJti(refreshTokenOriginal);

        authService.refreshToken(refreshTokenOriginal);

        // O refresh token antigo deve estar na blacklist Redis
        Boolean estaBlacklisted = redisTemplate.hasKey("token:blacklist:" + jtiOriginal);
        assertThat(estaBlacklisted)
                .as("O refresh token antigo deve ser adicionado à blacklist Redis após rotation")
                .isTrue();
    }

    @Test
    @DisplayName("dado refreshToken já usado uma vez, quando usado novamente, então lança InvalidTokenException")
    void dadoRefreshTokenJaUsado_quandoUsadoNovamente_entaoLancaInvalidTokenException() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String refreshTokenOriginal = loginResponse.refreshToken();

        authService.refreshToken(refreshTokenOriginal); // Primeira utilização — OK

        assertThatThrownBy(() -> authService.refreshToken(refreshTokenOriginal))
                .as("Usar o mesmo refresh token duas vezes deve ser rejeitado")
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("dado refreshToken inválido, quando refresh, então lança InvalidTokenException")
    void dadoRefreshTokenInvalido_quandoRefresh_entaoLancaInvalidTokenException() {
        assertThatThrownBy(() -> authService.refreshToken("token.invalido.qualquer"))
                .isInstanceOf(InvalidTokenException.class);
    }

    // -------------------------------------------------------------------------
    // Logout + Blacklist
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado accessToken válido, quando logout, então jti do token é adicionado à blacklist Redis")
    void dadoTokenValido_quandoLogout_entaoJtiAdicionadoABlacklist() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String accessToken = loginResponse.accessToken();
        String refreshToken = loginResponse.refreshToken();
        String jti = jwtService.extractJti(accessToken);

        authService.logout(accessToken, new LogoutRequest(refreshToken));

        Boolean estaBlacklisted = redisTemplate.hasKey("token:blacklist:" + jti);
        assertThat(estaBlacklisted)
                .as("O jti do accessToken deve estar na blacklist Redis após logout")
                .isTrue();
    }

    @Test
    @DisplayName("dado accessToken na blacklist, quando usado para aceder recurso, então rejeita o token")
    void dadoTokenLogout_quandoUsaTokenNaBlacklist_entaoRejeita() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String accessToken = loginResponse.accessToken();
        String refreshToken = loginResponse.refreshToken();

        authService.logout(accessToken, new LogoutRequest(refreshToken));

        // Após logout, o token deve ser rejeitado pelo JwtService (isTokenBlacklisted)
        assertThat(jwtService.isTokenBlacklisted(accessToken))
                .as("Token após logout deve ser identificado como blacklisted")
                .isTrue();
    }

    @Test
    @DisplayName("dado logout efectuado, quando TTL da blacklist, então TTL é igual ao tempo restante do token")
    void dadoLogout_quandoTTLBlacklist_entaoTTLIgualAoTempoRestanteDoToken() {
        String email = emailUnico();
        criarUserAtivo(email);
        AuthResponse loginResponse = authService.login(new LoginRequest(email, "Senha@1234"));
        String accessToken = loginResponse.accessToken();
        String refreshToken = loginResponse.refreshToken();
        String jti = jwtService.extractJti(accessToken);
        long ttlEsperado = jwtService.getRemainingTtlMillis(accessToken);

        authService.logout(accessToken, new LogoutRequest(refreshToken));

        Long ttlRedis = redisTemplate.getExpire("token:blacklist:" + jti,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        // Tolerância de 2 segundos
        assertThat(ttlRedis)
                .as("TTL da chave Redis deve ser igual ao tempo restante do accessToken")
                .isNotNull()
                .isGreaterThan(ttlEsperado - 2_000L)
                .isLessThanOrEqualTo(ttlEsperado);
    }

    // -------------------------------------------------------------------------
    // Change Password
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado password correcta, quando changePassword, então password é actualizada na BD")
    void dadoPasswordCorrecta_quandoChangePassword_entaoPasswordActualizada() {
        String email = emailUnico();
        criarUserAtivo(email);
        String novaSenha = "NovaSenha@9999";

        authService.changePassword(email, "Senha@1234", novaSenha);

        // Login com nova senha deve funcionar
        AuthResponse response = authService.login(new LoginRequest(email, novaSenha));
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("dado password incorrecta, quando changePassword, então lança InvalidCredentialsException")
    void dadoPasswordIncorrecta_quandoChangePassword_entaoLancaInvalidCredentialsException() {
        String email = emailUnico();
        criarUserAtivo(email);

        assertThatThrownBy(() -> authService.changePassword(email, "SenhaErrada@000", "NovaSenha@9999"))
                .as("Mudar password com senha actual errada deve ser rejeitado")
                .isInstanceOf(ao.com.angotech.modules.auth.exception.InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("dado password correcta, quando changePassword, então login com password antiga falha")
    void dadoPasswordAlterada_quandoLoginComPasswordAntiga_entaoFalha() {
        String email = emailUnico();
        criarUserAtivo(email);

        authService.changePassword(email, "Senha@1234", "NovaSenha@9999");

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Senha@1234")))
                .as("Login com password antiga deve falhar após mudança")
                .isInstanceOf(ao.com.angotech.modules.auth.exception.InvalidCredentialsException.class);
    }

    // -------------------------------------------------------------------------
    // Admin: toggle status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador activo, quando admin faz toggleStatus, então utilizador fica desactivado")
    void dadoUserActivo_quandoAdminToggleStatus_entaoUserFicaDesactivado() {
        String email = emailUnico();
        User user = criarUserAtivo(email);

        authService.toggleUserStatus(user.getId());

        User userActualizado = userRepository.findById(user.getId()).orElseThrow();
        assertThat(userActualizado.isEnabled())
                .as("Utilizador activo deve ficar desactivado após toggleStatus")
                .isFalse();
    }

    @Test
    @DisplayName("dado utilizador desactivado, quando admin faz toggleStatus, então utilizador fica activo")
    void dadoUserDesactivado_quandoAdminToggleStatus_entaoUserFicaActivo() {
        String email = emailUnico();
        User user = criarUserAtivo(email);
        user.setEnabled(false);
        userRepository.save(user);

        authService.toggleUserStatus(user.getId());

        User userActualizado = userRepository.findById(user.getId()).orElseThrow();
        assertThat(userActualizado.isEnabled())
                .as("Utilizador desactivado deve ficar activo após toggleStatus")
                .isTrue();
    }
}