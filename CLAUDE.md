# Lelo Angola — Guia para Claude Code

## O que é este projecto

**Lelo Angola** é uma plataforma digital de leilões online em tempo real (carros, imóveis, equipamentos industriais). O problema central que resolve é **consistência forte de lances em alta concorrência**: sem duplicados, sem race conditions, com histórico imutável e auditável.

Este é um projecto Spring Boot com lógica crítica de negócio. Trata-o com o mesmo rigor de um sistema financeiro.

---

## Stack e versões

| Tecnologia | Versão | Uso |
|-----------|--------|-----|
| Java | 17 | Linguagem |
| Spring Boot | 3.5.14 | Framework |
| PostgreSQL | 16 | BD principal (ACID) |
| Redis | 7 | Distributed locks + Pub/Sub |
| Kafka | 3.7+ | Eventos assíncronos |
| Redisson | 3.27.2 | Locks distribuídos (a adicionar) |
| jjwt | 0.11.5 | JWT |
| Flyway | gerido pelo Boot | Migrations SQL |

**Pacote base:** `ao.com.angotech`  
**Porta:** 8080  
**BD:** PostgreSQL na porta 5433 (não 5432 — ver docker-compose.yml)

---

## Como correr localmente

```bash
# 1. Subir a infra
docker-compose up -d

# 2. Verificar que PostgreSQL e Redis estão prontos
docker-compose ps

# 3. Correr a aplicação
./mvnw spring-boot:run

# 4. Verificar saúde
curl http://localhost:8080/health
```

---

## Estrutura de ficheiros actual

A estrutura já está migrada para o layout modular (`modules/` + `shared/`).

```
src/main/java/ao/com/angotech/
├── LeitaoAngolaApplication.java
├── modules/
│   ├── auth/                          ← MÓDULO COMPLETO (Fase 1)
│   │   ├── config/
│   │   │   └── SecurityConfig.java    ← JWT filter chain configurado
│   │   ├── controller/
│   │   │   ├── AuthController.java    ← /auth/register, /login, /refresh, /logout, /me, change-password
│   │   │   └── AdminController.java   ← gestão de utilizadores (admin)
│   │   ├── domain/
│   │   │   └── User.java              ← implementa UserDetails, roles como List<String>
│   │   ├── dto/                       ← Auth/Login/Register/Refresh/Logout/ChangePassword/UserResponse
│   │   ├── exception/                 ← 6 exceptions de domínio (InvalidCredentials, EmailAlreadyExists,
│   │   │                                 InvalidToken, DisabledUser, WrongPassword, UserNotFound)
│   │   ├── repository/
│   │   │   └── UserRepository.java
│   │   ├── security/
│   │   │   ├── CustomUserDetailsService.java
│   │   │   ├── JwtFilter.java
│   │   │   └── JwtService.java        ← gera e valida JWT + RefreshToken
│   │   └── service/
│   │       ├── AuthService.java       ← interface
│   │       └── impl/AuthServiceImpl.java
│   └── realtime/                      ← Fase 4 iniciada (só infra WebSocket)
│       ├── config/WebSocketConfig.java
│       └── security/StompAuthChannelInterceptor.java  ← autenticação STOMP
└── shared/
    ├── config/CorsConfig.java
    ├── controller/HealthController.java
    ├── exception/
    │   ├── BusinessException.java
    │   └── GlobalExceptionHandler.java
    └── response/ApiResponse.java

src/main/resources/
├── application.yaml                   ← config principal (PostgreSQL, JWT, Flyway)
├── application-dev.yml                ← overrides de dev (ddl-auto: validate)
└── db/migration/                      ← V1 (users), V7 (seed admin)

SPEC.md                                ← ESPECIFICAÇÃO TÉCNICA ÚNICA (fonte de verdade — raiz)
                                          Contém tudo: arquitectura, regras globais (R-01…R-17,
                                          NFR-01…NFR-10), e os módulos auth, leilões, lances,
                                          caução, pagamento, factura AGT, tempo real, auditoria,
                                          modelo de dados, convenções e plano de fases.

docs/
├── PLANO_DE_DESENVOLVIMENTO.md        ← plano operacional (como e quando)
└── postman-fase1.md                   ← colecção Postman da Fase 1
```

---

## Estado do desenvolvimento

| Módulo | Estado |
|--------|--------|
| Autenticação (Fase 1) | ✅ Completo — register, login, refresh, logout, /me (GET/PUT/DELETE), change-password, admin de utilizadores. Testado (unidade + integração + E2E) |
| Fundação transversal (`shared/`) | ✅ `GlobalExceptionHandler`, `ApiResponse<T>`, `BusinessException`, `CorsConfig`, `HealthController` |
| Gestão de Leilões (Fase 2) | ⬜ Não iniciado |
| Sistema de Lances (Fase 3, core) | ⬜ Não iniciado |
| Tempo Real / WebSocket (Fase 4) | 🟡 Infra iniciada — `WebSocketConfig` + `StompAuthChannelInterceptor`. Falta `BidController`, consumers, broadcast |
| Auditoria | ⬜ Não iniciado |

**Próximo passo:** Fase 2 (secção 15 do `SPEC.md`) — Gestão de Leilões: `V2`, entidades `Auction`/`AuctionItem` com domínio, `AuctionRepository` (pessimistic lock), CRUD (§6.4), `AuctionScheduler` (ShedLock) e `V4`/auditoria básica. Sempre em Test-First.

---

## Regra: Test-First (obrigatório em todo o desenvolvimento)

**Antes de escrever qualquer linha de código de produção, os testes têm de existir primeiro.**

Esta regra aplica-se a qualquer trabalho no projecto — nova feature, bug fix, refactor, novo endpoint, nova validação. Não há excepções.

### A ordem é sempre esta:

```
1. ANALISAR   → Ler o spec do módulo e identificar os comportamentos esperados
2. ESCREVER TESTES → Criar os testes (falham — RED)
3. IMPLEMENTAR     → Escrever o mínimo de código para os testes passarem (GREEN)
4. REFACTORING     → Limpar o código sem quebrar os testes (REFACTOR)
```

### Os três tipos de teste, todos obrigatórios:

**Testes de Unidade** — isolam uma única classe, sem Spring context, sem BD, sem rede.
- Testam lógica de domínio pura: validações, cálculos, regras encapsuladas na entidade
- Rápidos (< 100ms cada), correm sem infra
- Localização: `src/test/java/.../modules/{modulo}/domain/`
- Ferramentas: JUnit 5, Mockito

```java
// Exemplo: testar regra de domínio sem Spring
class AuctionTest {
    @Test
    void dadoLeilaoActive_quandoIsAcceptingBids_entaoRetornaTrue() {
        Auction auction = new Auction();
        auction.setStatus(AuctionStatus.ACTIVE);
        assertThat(auction.isAcceptingBids()).isTrue();
    }

    @Test
    void dadoLanceDentroJanela_quandoIsInAntiSnipingWindow_entaoRetornaTrue() {
        Auction auction = new Auction();
        auction.setEndTime(Instant.now().plusSeconds(180)); // 3 min restantes
        auction.setAntiSnipingMinutes(5);
        assertThat(auction.isInAntiSnipingWindow(Instant.now())).isTrue();
    }
}
```

**Testes de Integração** — testam a camada completa com BD real, Redis real, Kafka real via Testcontainers.
- Testam o Service com infra real: transacções ACID, locks, persistência
- Testam o Controller com MockMvc: mapeamento de rotas, status codes, `ApiResponse<T>`
- Localização: `src/test/java/.../modules/{modulo}/`
- Ferramentas: `@SpringBootTest`, `@Testcontainers`, `MockMvc`

```java
// Exemplo: testar service com BD real
@SpringBootTest
@Testcontainers
@Transactional
class AuctionServiceIntegrationTest {
    @Test
    void dadoVendedorAutenticado_quandoCriaLeilao_entaoPersisteComStatusScheduled() {
        // given
        CreateAuctionRequest request = AuctionTestBuilder.validRequest();
        // when
        AuctionDetailResponse result = auctionService.create(request, "seller@lelo.ao");
        // then
        assertThat(result.status()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(auctionRepository.findById(result.id())).isPresent();
    }
}
```

**Testes End-to-End (E2E)** — testam o fluxo completo da API como um cliente externo faria.
- HTTP real contra a app a correr (ou via `@SpringBootTest` com `WebEnvironment.RANDOM_PORT`)
- WebSocket real para fluxos de tempo real
- Localização: `src/test/java/.../e2e/`
- Ferramentas: `TestRestTemplate`, `WebSocketStompClient`

```java
// Exemplo: testar fluxo completo via HTTP
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BidFlowE2ETest {
    @Test
    void dadoLeilaoActive_quandoBidderDaLanceValido_entaoRecebeConfirmacaoEBroadcast()
        throws Exception {
        // 1. Criar leilão (como seller)
        // 2. Conectar via WebSocket (como buyer)
        // 3. Subscrever /topic/auction/{id}
        // 4. Enviar lance
        // 5. Verificar resposta privada em /user/queue/bid-result
        // 6. Verificar broadcast em /topic/auction/{id}
        // 7. Verificar persistência na BD
    }
}
```

### Estrutura de testes por módulo

```
src/test/java/ao/com/angotech/
├── modules/
│   ├── auth/
│   │   ├── domain/              ← testes de unidade (User, JwtService)
│   │   ├── AuthServiceTest.java ← integração (com BD real)
│   │   └── AuthControllerTest.java ← MockMvc
│   ├── auction/
│   │   ├── domain/AuctionTest.java ← unidade: isAcceptingBids, anti-sniping, etc.
│   │   ├── AuctionServiceTest.java ← integração
│   │   ├── AuctionControllerTest.java ← MockMvc
│   │   └── AuctionSchedulerTest.java ← integração com relógio simulado
│   └── bidding/
│       ├── domain/BidTest.java     ← unidade: imutabilidade, validações
│       ├── BidServiceConcurrencyTest.java ← 50 threads simultâneas
│       ├── BidServiceValidationTest.java  ← R-01 a R-08
│       └── BidControllerWebSocketTest.java ← WebSocket real
└── e2e/
    ├── AuthFlowE2ETest.java       ← registo → login → uso → logout
    ├── AuctionFlowE2ETest.java    ← criar → listar → detalhe
    └── BidFlowE2ETest.java        ← bid → broadcast → histórico
```

### Nomes de testes — convenção obrigatória

```
dado{contexto}_quando{accao}_entao{resultado}

Exemplos:
- dadoLeilaoFechado_quandoSubmeteLance_entaoRetorna409
- dadoAmountAbaixoMinimo_quandoValidaBid_entaoLancaBidTooLowException
- dado50LancesSimultaneos_quandoProcessados_entaoZeroDuplicados
```

### O que não é aceitável

- Escrever código de produção e depois dizer "vou adicionar testes depois"
- Testes que só testam o happy path — os edge cases e erros são obrigatórios
- Testes que mockem o `BidService` ou `AuctionRepository` nos testes de concorrência
- `@Disabled` em testes que falham — ou o teste está errado ou o código está errado
- Testes que dependem da ordem de execução

---

## Regras de negócio imutáveis (nunca violar)

Estas regras são definidas em `SPEC.md` e **não podem ser alteradas sem revisão de produto**:

- **R-01** — Só aceitar lances quando `status = ACTIVE` ou `EXTENDED`
- **R-02** — `Bid.amount > currentHighestBid + minIncrement`
- **R-03** — `timestamp` do lance é sempre definido pelo servidor
- **R-04** — Lances são imutáveis: zero UPDATE ou DELETE em `bids`
- **R-05** — Vencedor = maior lance válido no momento do fim
- **R-06** — Se `reservePrice` definido, só "vendido" se `highestBid >= reservePrice`
- **R-07** — Anti-sniping: lance nos últimos N min estende o timer em N min
- **R-08** — Dois lances com o mesmo `amount` no mesmo leilão são proibidos (UNIQUE INDEX)

---

## Convenções de código

### Estrutura de pacotes (alvo — ainda em migração)
```
ao.com.angotech.modules.{modulo}.controller
ao.com.angotech.modules.{modulo}.service
ao.com.angotech.modules.{modulo}.service.impl
ao.com.angotech.modules.{modulo}.domain      ← entidades e value objects
ao.com.angotech.modules.{modulo}.repository
ao.com.angotech.modules.{modulo}.dto
ao.com.angotech.modules.{modulo}.event
ao.com.angotech.shared.exception
ao.com.angotech.shared.response
ao.com.angotech.infrastructure.kafka
ao.com.angotech.infrastructure.redis
```

---

## Regra: Responsabilidade do Controller

O Controller é a **porta de entrada da API**. A sua única responsabilidade é:

1. Receber a requisição HTTP
2. Validar dados básicos de entrada (`@Valid`, tipos, formato)
3. Extrair o utilizador autenticado (`@AuthenticationPrincipal`)
4. Delegar **toda** a lógica ao Service
5. Devolver a resposta HTTP com o código de status correcto

**O Controller nunca deve:**
- Conter lógica de negócio
- Fazer chamadas directas ao Repository
- Lançar ou tratar exceptions de domínio (isso é do GlobalExceptionHandler)
- Fazer transformações de dados complexas (isso é do Service ou Mapper)
- Tomar decisões baseadas em estado do domínio

```java
// CORRECTO — Controller delega tudo
@PostMapping
@PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
public ResponseEntity<ApiResponse<AuctionResponse>> create(
    @Valid @RequestBody CreateAuctionRequest request,
    @AuthenticationPrincipal UserDetails currentUser
) {
    AuctionResponse auction = auctionService.create(request, currentUser.getUsername());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(auction));
}

// ERRADO — lógica de negócio no Controller
@PostMapping
public ResponseEntity<?> create(@RequestBody CreateAuctionRequest request) {
    if (request.getEndTime().isBefore(Instant.now())) {  // ← NUNCA no controller
        return ResponseEntity.badRequest().body("Data inválida");
    }
    // ...
}
```

---

## Regra: Responsabilidade do Service

O Service é onde **toda a lógica de negócio vive**. Cada Service deve:

1. Orquestrar a lógica de negócio completa
2. Aplicar as regras de domínio (R-01 a R-08)
3. Coordenar entre Repositories, outros Services e infra (Kafka, Redis)
4. Gerir transacções (`@Transactional`)
5. Lançar exceptions de domínio específicas quando as regras são violadas
6. Mapear entidades para DTOs de resposta

**Todo Service tem obrigatoriamente:**
- Uma **interface** que define o contrato público
- Uma classe **Impl** que implementa a interface, anotada com `@Service`

```java
// AuctionService.java — interface (contrato público)
public interface AuctionService {
    AuctionResponse create(CreateAuctionRequest request, String sellerEmail);
    AuctionDetailResponse findById(UUID id);
    Page<AuctionSummaryResponse> findAll(AuctionFilter filter, Pageable pageable);
    AuctionResponse update(UUID id, UpdateAuctionRequest request, String editorEmail);
    void cancel(UUID id, String reason, String cancellerEmail);
}

// AuctionServiceImpl.java — implementação com lógica
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuctionServiceImpl implements AuctionService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    @Transactional
    public AuctionResponse create(CreateAuctionRequest request, String sellerEmail) {
        // Toda a lógica aqui — validações, persistência, eventos, mapeamento
    }
}
```

**O Service nunca deve:**
- Lidar com detalhes de HTTP (HttpServletRequest, ResponseEntity, status codes)
- Retornar entidades JPA directamente — sempre mapear para DTO
- Ter anotações de controller (`@GetMapping`, `@PostMapping`, etc.)

---

## Regra: Interface + Impl obrigatório

**Todo Service no projecto segue este padrão sem excepção:**

```
modules/{modulo}/service/
├── AuctionService.java          ← interface pública
├── BidService.java              ← interface pública
└── impl/
    ├── AuctionServiceImpl.java  ← @Service, implementa AuctionService
    └── BidServiceImpl.java      ← @Service, implementa BidService
```

Razões:
- Facilita testes (mock da interface, não da implementação)
- Permite múltiplas implementações (ex: versão com Redisson, versão de fallback)
- Evita dependências circulares
- Separação clara de contrato vs. detalhe de implementação

O código existente (`AuthService` / `AuthServiceImpl`) já segue este padrão — manter para todos os módulos.

---

### Outras regras de código

- **IDs:** `UUID` gerado pelo servidor — `@GeneratedValue(strategy = GenerationType.UUID)`
- **Timestamps:** usar `Instant` (UTC) para campos críticos — nunca `LocalDateTime`
- **Locking otimista:** `@Version Long version` em todas as entidades mutáveis
- **DTOs:** usar Java `record` para imutabilidade
- **Entidades imutáveis:** `Bid` e `AuctionEvent` não têm setters nos campos de negócio
- **Migrations:** só Flyway — nunca `ddl-auto: create` fora de dev/test
- **Nomes de tabelas:** snake_case — ex: `auction_items`, `user_roles`
- **Respostas de API:** sempre envolver em `ApiResponse<T>`
- **Exceptions:** lançar exceptions de domínio específicas (ex: `BidTooLowException`) — nunca `RuntimeException` genérico em código de produção
- **Transacções:** `@Transactional(readOnly = true)` na classe Impl, `@Transactional` override nos métodos de escrita

### Padrão de response
```java
// Sempre usar:
return ResponseEntity.ok(ApiResponse.success(data));
return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data));
return ResponseEntity.status(409).body(ApiResponse.error("BID_TOO_LOW", mensagem));
```

---

## Fluxo crítico — Submissão de lance

Este fluxo deve ser respeitado em qualquer implementação do módulo de lances:

```
1. WebSocket @MessageMapping("/app/auction/{id}/bid")
2. BidService.placeBid(command)
   ├── Redisson RLock ("lock:auction:{auctionId}") — timeout 10s
   ├── @Transactional
   │   ├── SELECT ... FOR UPDATE (pessimistic lock)
   │   ├── Validar R-01 a R-08
   │   ├── INSERT bid (timestamp do servidor)
   │   ├── UPDATE auction (currentHighestBid, endTime se anti-sniping)
   │   └── INSERT auction_events (event store)
   └── Kafka publish "auction-bids"
3. Kafka Consumer → actualizar read model + broadcast WebSocket
```

Ver detalhe completo na secção 7 do `SPEC.md`.

---

## Migrations Flyway

Ficheiros em `src/main/resources/db/migration/`. Estado actual: **só V1 e V7 existem** (as restantes serão criadas nas Fases 2–5). Nota: a numeração no SPEC §15 usa V8 (deposits), V9 (payments), V10 (invoices) — seguir o SPEC como fonte de verdade.

| Versão | Ficheiro | Conteúdo | Estado |
|--------|---------|---------|--------|
| V1 | `V1__create_users_table.sql` | users, user_roles | ✅ Existe |
| V7 | `V7__seed_admin_user.sql` | Utilizador admin inicial | ✅ Existe |
| V2 | `V2__create_auctions_tables.sql` | auction_items, auction_item_photos, auctions | ⬜ Fase 2 |
| V4 | `V4__create_audit_tables.sql` | auction_events, admin_audit_log | ⬜ Fase 2 |
| V3 | `V3__create_bids_table.sql` | bids (append-only, UNIQUE INDEX) | ⬜ Fase 3 |
| V8 | `V8__create_deposits_table.sql` | deposits (caução) | ⬜ Fase 3 |
| V5 | `V5__create_notifications_table.sql` | notifications | ⬜ Fase 4 |
| V9 / V10 | payments / invoices | pagamento, facturação AGT | ⬜ Fase 5 |
| V6 | `V6__create_outbox_table.sql` | outbox_events | ⬜ Fase 5 |

O schema SQL exacto de cada tabela está no spec do módulo correspondente.

---

## Agentes disponíveis

Este projecto tem agentes especializados em `.claude/agents/`:

| Agente | Quando usar |
|--------|-------------|
| `especialista-java-springboot` | Dúvidas técnicas avançadas de Java/Spring Boot — pesquisa documentação e repositórios GitHub de referência em tempo real |
| `spec-enforcer` | Antes de implementar qualquer funcionalidade — verifica conformidade com specs e regras de Controller/Service/Impl |
| `migration-writer` | Para criar ou validar ficheiros Flyway SQL |
| `bid-engineer` | Para qualquer trabalho no módulo de lances (módulo crítico) |
| `test-writer` | Para criar testes de integração com Testcontainers |
| `api-designer` | Para definir ou rever contratos de API REST/WebSocket |

---

## Dependências no pom.xml

**Já presentes:** web, security, **websocket**, data-jpa, validation, actuator, data-redis, flyway (core + postgresql), jjwt 0.11.5 (api/impl/jackson), postgresql, spring-boot-starter-test, **spring-boot-testcontainers** + testcontainers (postgresql, kafka).

**Ainda por adicionar** (Fase 3 — core crítico):

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>       <!-- só o Testcontainers do Kafka está presente -->
</dependency>
```

> **Sem Lombok** — o SPEC (§15, Fase 1) exclui explicitamente o Lombok. Não adicionar.

---

## Alertas importantes

- `application-dev.yml` já usa `ddl-auto: validate` (correcto) — **nunca** repor `create`/`update` fora de test; o schema é gerido por Flyway
- O `jwt.secret` de dev (`application-dev.yml`) é fraco e está marcado como dev-only — em produção usar variável de ambiente com mínimo 256 bits
- O módulo auth já tem exceptions de domínio próprias (`modules/auth/exception/`) tratadas pelo `GlobalExceptionHandler` — seguir o mesmo padrão nos novos módulos, nunca `RuntimeException` genérico
- A tabela `bids` (Fase 3) terá `UNIQUE INDEX (auction_id, amount)` — `DataIntegrityViolationException` deve ser tratado e traduzido para `DuplicateBidException`
