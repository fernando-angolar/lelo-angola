---
description: Verificar se o código de um módulo está conforme o spec e as regras do projecto
argument-hint: Nome do módulo a verificar (ex: auth, auction, bidding)
---

# Spec Check — Lelo Angola

Vais auditar o módulo **$ARGUMENTS** e verificar a sua conformidade com o spec e as regras do CLAUDE.md.

## Passo 1 — Ler o SPEC (fonte única de verdade)

**1a. Ler as secções globais** de `SPEC.md` (na raiz do projecto). Extrair:
- As **Regras de Negócio Globais** (secção 3, R-01 a R-17) — invariantes que valem para todo o sistema
- Os **Requisitos Não-Funcionais** (secção 4, NFR-01 a NFR-10)
- As **Convenções de Código** (secção 14): UUID, `Instant`, `@Version`, naming snake_case, migrations Flyway, `ApiResponse<T>`, sem Lombok
- O **Modelo de Dados Consolidado** (secção 13) — para confirmar que as tabelas do módulo batem certo

Se o `SPEC.md` não existir na raiz, reportar como problema **blocker** (o Spec-Driven perdeu a sua âncora).

**1b. Ler a secção do módulo** dentro do mesmo `SPEC.md`:

| Módulo | Secção do SPEC.md |
|--------|-------------------|
| auth | 5 — Segurança & Autenticação |
| auction | 6 — Gestão de Leilões |
| bidding | 7 — Sistema de Lances |
| deposit | 8 — Caução / Depósitos |
| payment | 9 — Pagamento & Segunda Oferta |
| invoice | 10 — Facturação (AGT / SAF-T) |
| realtime / notification | 11 — Tempo Real & Notificações |
| audit | 12 — Auditoria & Histórico |

Se o módulo não tiver secção no SPEC.md, reportar isso como problema crítico.

---

## Passo 2 — Listar todos os ficheiros do módulo

```
src/main/java/ao/com/angotech/modules/$ARGUMENTS/
src/test/java/ao/com/angotech/modules/$ARGUMENTS/
```

Listar os ficheiros encontrados e os que estão em falta segundo o spec.

---

## Passo 3 — Verificar a arquitectura

### Estrutura de pacotes
- [ ] `controller/` existe com `@RestController`
- [ ] `service/` tem a **interface** pública (não a impl)
- [ ] `service/impl/` tem a classe `@Service` que implementa a interface
- [ ] `domain/` tem as entidades
- [ ] `repository/` tem os repositórios Spring Data
- [ ] `dto/` tem Java `record`s
- [ ] `exception/` tem exceptions de domínio específicas (não `RuntimeException` genérico)

### Regra Controller
Para cada método do Controller verificar:
- [ ] Não contém lógica de negócio
- [ ] Não chama o Repository directamente
- [ ] Delega tudo ao Service
- [ ] Retorna `ApiResponse<T>` com o status code correcto
- [ ] Tem `@PreAuthorize` onde necessário

### Regra Service
- [ ] A interface define o contrato público
- [ ] A Impl tem `@Transactional(readOnly = true)` na classe
- [ ] Os métodos de escrita têm `@Transactional` override
- [ ] Nunca retorna entidades JPA directamente — sempre mapeia para DTO
- [ ] Lança exceptions de domínio específicas

### Regras de entidades
- [ ] IDs são `UUID` com `@GeneratedValue(strategy = GenerationType.UUID)`
- [ ] Timestamps críticos são `Instant` (não `LocalDateTime`)
- [ ] Entidades mutáveis têm `@Version Long version`
- [ ] Entidades imutáveis (`Bid`, `AuctionEvent`) não têm setters nos campos de negócio

---

## Passo 4 — Verificar as regras de negócio (R-01 a R-17)

As regras R-01 a R-17 do `SPEC.md` são **invariantes globais**. Não vivem só no `bidding` — cada uma é imposta por uma camada concreta. Verificar **as regras relevantes ao módulo em análise**, usando o mapa abaixo. Se o módulo não for responsável por uma regra, marcá-la como `N/A` (não como conforme).

| Regra | Camada que a impõe | Módulo(s) | O que verificar |
|-------|--------------------|-----------|-----------------|
| **R-01** | `Auction.isAcceptingBids()` + validação no `BidServiceImpl` | auction, bidding | Lances só aceites com `status = ACTIVE` ou `EXTENDED` |
| **R-02** | Validação no `BidServiceImpl` | bidding | `amount > currentHighestBid + minIncrement` |
| **R-03** | `BidServiceImpl` define `Instant.now()` | bidding | `timestamp` do lance vem do servidor, nunca do payload do cliente |
| **R-04** | Entidade `Bid` + migration `bids` | bidding | `Bid` sem setters de negócio, sem `@PreUpdate/@PreRemove`; zero UPDATE/DELETE em `bids` |
| **R-05** | Determinação de vencedor no fim (scheduler/service) | auction, bidding | Vencedor = maior lance válido no momento do fim |
| **R-06** | Lógica de fecho do leilão | auction | Só marca "vendido" se `highestBid >= reservePrice` (quando definido) |
| **R-07** | `Auction.isInAntiSnipingWindow()` + `applyExtension()` | auction, bidding | Lance nos últimos N min estende `endTime` em N min |
| **R-08** | Migration `bids` + tratamento de excepção | bidding | `UNIQUE INDEX (auction_id, amount)` existe e `DataIntegrityViolationException` → `DuplicateBidException` |
| **R-09** | `Auction.canExtend()` + `applyExtension()` | auction, bidding | Anti-sniping pára de estender após `maxExtensions` (padrão 3) |
| **R-10** | Validação no `BidServiceImpl` | bidding | Vendedor não licita no próprio leilão → `SelfBiddingException` |
| **R-11** | `DepositService.hasHeldDeposit()` no `validateBid` | deposit, bidding | Só licita quem tem caução `HELD` |
| **R-12** | Fecho do leilão / consumer `auction-finished` | deposit, auction | Cauções dos não-vencedores → `RELEASED` |
| **R-13** | `AuctionScheduler.handlePaymentDeadlines()` | deposit, payment | Vencedor sem pagar em 48h → caução `CAPTURED` + segunda oferta |
| **R-14** | `UNIQUE (idempotency_key)` + `PaymentServiceImpl` | payment | Retry/webhook não gera cobrança duplicada |
| **R-15** | Migrations + entidades | todos | Dinheiro em `NUMERIC(18,2)`/`BigDecimal`, nunca float |
| **R-16** | `PaymentServiceImpl.chargeWinner()` | payment, auth | BI e NIF exigidos antes de pagar/facturar |
| **R-17** | `InvoiceServiceImpl` | invoice | Factura com `agt_reference` único (AGT/SAF-T) |

Para cada regra relevante, apontar **o ficheiro e a linha** onde é imposta. Se uma regra relevante não tiver ponto de imposição no código, é um **blocker**.

### Verificações extra específicas do `bidding`
- [ ] Redisson `RLock` (`lock:auction:{id}`) obtido **antes** de qualquer validação
- [ ] Lock com timeout de 10s
- [ ] `SELECT ... FOR UPDATE` (pessimistic lock) dentro da transacção
- [ ] Lock libertado no `finally` (mesmo em caso de excepção)
- [ ] Evento publicado no Kafka / Outbox após o commit

---

## Passo 5 — Verificar cobertura de testes

Verificar que existem:
- [ ] Testes de unidade em `domain/` (sem Spring, sem BD)
- [ ] Testes de integração do Service com `@Testcontainers`
- [ ] Testes do Controller com `@WebMvcTest`
- [ ] Nenhum `@Disabled`
- [ ] Nomes no formato `dado{contexto}_quando{accao}_entao{resultado}`
- [ ] Testes de erro e edge cases (não só happy path)

---

## Passo 6 — Verificar contratos de API

Para cada endpoint do spec, verificar:
- [ ] Path correcto
- [ ] Método HTTP correcto (GET, POST, PUT, DELETE, PATCH)
- [ ] Body de request conforme o spec
- [ ] Status code de sucesso correcto (200, 201, 204)
- [ ] Status codes de erro correctos (400, 401, 403, 404, 409)
- [ ] Formato de resposta: `{"success": true, "data": {...}}`
- [ ] Formato de erro: `{"success": false, "error": {"code": "...", "message": "..."}}`

---

## Passo 7 — Relatório final

Apresentar uma lista clara de:

**Conforme** — o que está correcto  
**Não conforme** — o que viola o spec ou as regras (com ficheiro e linha)  
**Em falta** — o que está no spec mas não está implementado  

Ordenar por severidade: blocker > major > minor.

Para cada problema reportar:
- Ficheiro e linha
- Regra violada (referir a regra do `SPEC.md` — ex: R-03, convenção "Timestamps `Instant`" — ou a secção do spec do módulo)
- Correcção sugerida

Terminar com um veredicto explícito:
- **Conforme ao SPEC** — módulo respeita as secções globais, a secção do módulo e todas as regras R-01 a R-17 relevantes
- **Não conforme** — listar os blockers que impedem o fecho da fase correspondente no `docs/PLANO_DE_DESENVOLVIMENTO.md`
