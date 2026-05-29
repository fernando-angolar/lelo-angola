// RED: este teste falha porque JwtService.generateToken() não inclui o claim "jti",
//      JwtService não expõe extractJti(), e JwtService não expõe getRemainingTtlMillis().
package ao.com.angotech.modules.auth.domain;

import ao.com.angotech.modules.auth.domain.User;
import ao.com.angotech.modules.auth.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de unidade para JwtService.
 * Sem Spring context, sem BD, sem rede — apenas lógica de geração/validação de tokens.
 */
class JwtServiceTest {

    private JwtService jwtService;

    private static final String SECRET = "leloAngolaTestSecretKey1234567890ForTestingOnlyNotProduction";
    private static final long JWT_EXPIRATION = 86400000L;       // 24h em ms
    private static final long REFRESH_EXPIRATION = 604800000L;  // 7 dias em ms

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", JWT_EXPIRATION);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", REFRESH_EXPIRATION);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private User buildUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@lelo.ao");
        user.setPassword("hashedPassword");
        user.setFullName("Teste Utilizador");
        user.setRoles(List.of("ROLE_BUYER"));
        user.setEnabled(true);
        return user;
    }

    // -------------------------------------------------------------------------
    // Testes: jti claim
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador válido, quando gera accessToken, então token inclui claim jti único")
    void dadoUserValido_quandoGeraToken_entaoIncluiJtiClaim() {
        User user = buildUser();

        String token = jwtService.generateToken(user);

        Claims claims = parseToken(token);
        assertThat(claims.getId())
                .as("O claim 'jti' deve estar presente e não ser nulo")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @DisplayName("dado utilizador válido, quando gera dois tokens, então cada token tem jti diferente")
    void dadoUserValido_quandoGeraDoisTokens_entaoCadaTokenTemJtiDiferente() {
        User user = buildUser();

        String token1 = jwtService.generateToken(user);
        String token2 = jwtService.generateToken(user);

        String jti1 = parseToken(token1).getId();
        String jti2 = parseToken(token2).getId();

        assertThat(jti1)
                .as("Dois tokens gerados para o mesmo utilizador devem ter jti distintos")
                .isNotEqualTo(jti2);
    }

    @Test
    @DisplayName("dado token com jti, quando extrai jti, então retorna UUID válido")
    void dadoToken_quandoExtractJti_entaoRetornaJtiValido() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        String jti = jwtService.extractJti(token);

        assertThat(jti).isNotNull().isNotBlank();
        // Deve ser parseável como UUID — lança excepção se inválido
        UUID parsed = UUID.fromString(jti);
        assertThat(parsed).isNotNull();
    }

    @Test
    @DisplayName("dado token com jti, quando extrai jti com extractJti, então coincide com claim do token")
    void dadoToken_quandoExtractJti_entaoJtiCoincidesComClaimDoToken() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        String jtiViaMetodo = jwtService.extractJti(token);
        String jtiViaParse = parseToken(token).getId();

        assertThat(jtiViaMetodo).isEqualTo(jtiViaParse);
    }

    // -------------------------------------------------------------------------
    // Testes: TTL restante
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado token recém emitido, quando getRemainingTtlMillis, então retorna valor próximo da expiração total")
    void dadoTokenRecem_quandoGetRemainingTtlMillis_entaoRetornaValorProximoExpiracao() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        long remainingMs = jwtService.getRemainingTtlMillis(token);

        // Token tem 24h de validade; recém emitido deve ter próximo de 24h restante
        // Tolerância de 5 segundos para tempo de execução do teste
        long toleranciaMs = 5_000L;
        assertThat(remainingMs)
                .as("TTL restante deve ser próximo de 24h (86400000ms)")
                .isGreaterThan(JWT_EXPIRATION - toleranciaMs)
                .isLessThanOrEqualTo(JWT_EXPIRATION);
    }

    @Test
    @DisplayName("dado token de refresh recém emitido, quando getRemainingTtlMillis, então retorna valor próximo de 7 dias")
    void dadoRefreshTokenRecem_quandoGetRemainingTtlMillis_entaoRetornaValorProximo7Dias() {
        User user = buildUser();
        String refreshToken = jwtService.generateRefreshToken(user);

        long remainingMs = jwtService.getRemainingTtlMillis(refreshToken);

        long toleranciaMs = 5_000L;
        assertThat(remainingMs)
                .as("TTL restante do refresh token deve ser próximo de 7 dias (604800000ms)")
                .isGreaterThan(REFRESH_EXPIRATION - toleranciaMs)
                .isLessThanOrEqualTo(REFRESH_EXPIRATION);
    }

    @Test
    @DisplayName("dado token válido, quando getRemainingTtlMillis, então retorna valor positivo")
    void dadoTokenValido_quandoGetRemainingTtlMillis_entaoRetornaValorPositivo() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        long remaining = jwtService.getRemainingTtlMillis(token);

        assertThat(remaining).isPositive();
    }

    // -------------------------------------------------------------------------
    // Testes: claims existentes (regressão — não devem quebrar)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dado utilizador com role BUYER, quando gera token, então token contém roles nos claims")
    void dadoUserComRoleBuyer_quandoGeraToken_entaoTokenContemRoles() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        Claims claims = parseToken(token);

        assertThat(claims.get("roles")).isNotNull();
    }

    @Test
    @DisplayName("dado utilizador, quando gera token, então subject é o email do utilizador")
    void dadoUser_quandoGeraToken_entaoSubjectEEmailDoUtilizador() {
        User user = buildUser();
        String token = jwtService.generateToken(user);

        assertThat(jwtService.extractUsername(token)).isEqualTo("test@lelo.ao");
    }

    // -------------------------------------------------------------------------
    // Utilitário interno
    // -------------------------------------------------------------------------

    private Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes());
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
