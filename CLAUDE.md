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

```
src/main/java/ao/com/angotech/
├── LeitaoAngolaApplication.java
├── config/
│   └── SecurityConfig.java            ← JWT filter chain configurado
├── controller/
│   ├── AuthController.java            ← /auth/register, /auth/login, /auth/refresh
│   └── HealthController.java
├── dto/
│   ├── AuthResponse.java
│   ├── LoginRequest.java
│   ├── RegisterRequest.java
│   └── UserResponse.java
├── entity/
│   └── User.java                      ← implementa UserDetails, roles como List<String>
├── repository/
│   └── UserRepository.java
├── security/
│   ├── CustomUserDetailsService.java
│   ├── JwtFilter.java
│   └── JwtService.java                ← gera e valida JWT + RefreshToken
└── service/
    ├── AuthService.java               ← interface
    └── impl/AuthServiceImpl.java      ← register, login, refreshToken implementados

src/main/resources/
├── application.yaml                   ← config principal (PostgreSQL, JWT, Flyway)
└── application-dev.yml                ← overrides de dev

docs/
├── SPEC.md                            ← índice do Spec Driven e regras globais
└── specs/
    ├── 01-architecture.md             ← estrutura de pacotes, fluxos, stack
    ├── 02-security-auth.md            ← auth completo: o que falta, contratos de API
    ├── 03-auction-management.md       ← leilões: estados, schema, scheduler
    ├── 04-bidding-system.md           ← CORE CRÍTICO: lances com lock distribuído
    ├── 05-realtime.md                 ← WebSocket/STOMP, Kafka→Redis→broadcast
    ├── 06-audit-history.md            ← event store imutável, tipos de eventos
    └── 07-implementation-plan.md      ← 6 fases com critérios de saída
```

---

## Estado do desenvolvimento

| Módulo | Estado |
|--------|--------|
| Autenticação (base) | Parcialmente implementado — falta refresh seguro, logout com blacklist Redis, endpoints de admin |
| Gestão de Leilões | Não iniciado |
| Sistema de Lances | Não iniciado |
| Tempo Real (WebSocket) | Não iniciado |
| Auditoria | Não iniciado |

**Próximo passo:** Fase 1 do `docs/specs/07-implementation-plan.md` — reorganizar pacotes e completar autenticação.

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

Estas regras são definidas em `docs/SPEC.md` e **não podem ser alteradas sem revisão de produto**:

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

Ver detalhe completo em `docs/specs/04-bidding-system.md`.

---

## Migrations Flyway

Ficheiros em `src/main/resources/db/migration/`:

| Versão | Ficheiro | Conteúdo |
|--------|---------|---------|
| V1 | `V1__create_users_table.sql` | users, user_roles |
| V2 | `V2__create_auctions_tables.sql` | auction_items, auction_item_photos, auctions |
| V3 | `V3__create_bids_table.sql` | bids (append-only, UNIQUE INDEX) |
| V4 | `V4__create_audit_tables.sql` | auction_events, admin_audit_log |
| V5 | `V5__create_notifications_table.sql` | notifications |
| V6 | `V6__create_outbox_table.sql` | outbox_events |
| V7 | `V7__seed_admin_user.sql` | Utilizador admin inicial |

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

## Dependências ainda não adicionadas ao pom.xml

Necessárias para as Fases 3-4:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Alertas importantes

- `application.yaml` tem `ddl-auto: create` — **mudar para `validate`** ao criar a primeira migration V1
- O `jwt.secret` actual é fraco — em produção usar variável de ambiente com mínimo 256 bits
- O `AuthServiceImpl` lança `RuntimeException` genérico — substituir por exceptions de domínio em cada refatoração
- A tabela `bids` tem um `UNIQUE INDEX (auction_id, amount)` — `DataIntegrityViolationException` deve ser tratado e traduzido para `DuplicateBidException`
