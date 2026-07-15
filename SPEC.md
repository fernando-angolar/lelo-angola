# Lelo Angola — Especificação Técnica Única

> **Versão:** 2.0
> **Data:** 2026-07-14
> **Stack:** Spring Boot 3.5.14 · Java 17 · PostgreSQL 16 · Redis 7 · Kafka 3.7+ · WebSocket/STOMP
> **Pacote base:** `ao.com.angotech`
> **Porta:** 8080 · **PostgreSQL:** 5433 · **Moeda:** Kwanza (AOA)

---

## Sobre este documento

Este é o **documento-raiz único** do Spec Driven Development do projecto Lelo Angola. Substitui a antiga pasta `specs/` — toda a especificação técnica vive aqui, como fonte única de verdade entre negócio, produto e engenharia: o que construir, como construir e em que ordem.

### Índice

1. [Visão do Negócio](#1-visão-do-negócio)
2. [Arquitectura & Stack](#2-arquitectura--stack)
3. [Regras de Negócio Globais (invariantes)](#3-regras-de-negócio-globais-invariantes)
4. [Requisitos Não-Funcionais (NFR)](#4-requisitos-não-funcionais-nfr)
5. [Módulo — Segurança & Autenticação](#5-módulo--segurança--autenticação)
6. [Módulo — Gestão de Leilões](#6-módulo--gestão-de-leilões)
7. [Módulo — Sistema de Lances (Core Crítico)](#7-módulo--sistema-de-lances-core-crítico)
8. [Módulo — Caução / Depósitos](#8-módulo--caução--depósitos)
9. [Módulo — Pagamento & Segunda Oferta](#9-módulo--pagamento--segunda-oferta)
10. [Módulo — Facturação (AGT / SAF-T)](#10-módulo--facturação-agt--saf-t)
11. [Módulo — Tempo Real & Notificações](#11-módulo--tempo-real--notificações)
12. [Módulo — Auditoria & Histórico](#12-módulo--auditoria--histórico)
13. [Modelo de Dados Consolidado & Migrações Flyway](#13-modelo-de-dados-consolidado--migrações-flyway)
14. [Convenções de Código](#14-convenções-de-código)
15. [Plano de Implementação por Fases](#15-plano-de-implementação-por-fases)
16. [Métricas de Sucesso do MVP](#16-métricas-de-sucesso-do-mvp)

---

## 1. Visão do Negócio

**Lelo Angola** é uma plataforma digital de leilões online em tempo real para bens diversos (carros, imóveis, equipamentos industriais, maquinaria). O problema central a resolver é a **falta de consistência e justiça nos lances em alta concorrência**: race conditions, bids duplicados, histórico alterável e ausência de auditoria confiável.

**Proposta de valor:**
- Leilões em tempo real com **consistência forte** (o maior lance válido vence sempre)
- **Histórico imutável e auditável** de todos os lances
- **Ciclo financeiro completo e conforme à lei angolana**: caução obrigatória, pagamento via Multicaixa Express / GPO, factura AGT
- Experiência fluida para compradores e vendedores angolanos

Este é um projecto com lógica crítica de negócio. É tratado com o mesmo rigor de um sistema financeiro.

### Ciclo de vida de ponta a ponta

```
Vendedor cria leilão → agendado → activo (lances em tempo real) → fechado
   → vencedor determinado → paga caução exigida para licitar → paga o bem (Multicaixa/GPO)
   → factura AGT emitida → liquidado (SETTLED)
                          └→ (não paga em 48h) → caução capturada → segunda oferta ao 2.º
```

---

## 2. Arquitectura & Stack

### 2.1 Decisão de Arquitectura — Monólito Modular com CQRS parcial

O projecto usa um **monólito modular** organizado por domínio de negócio, equilibrando velocidade de desenvolvimento (equipa pequena, MVP), fronteiras claras para futura extração de serviços, e baixa complexidade operacional.

O fluxo de lances aplica **CQRS parcial**:
- **Write side:** comando → lock distribuído → transação ACID → evento Kafka
- **Read side:** projecções actualizadas via Kafka consumer (leitura rápida sem lock)

### 2.2 Diagrama de Componentes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        LELO ANGOLA — MONÓLITO                            │
│                                                                          │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐   │
│  │  Auth  │ │Auction │ │Bidding │ │Deposit │ │Payment │ │ Notify │   │
│  │ Module │ │ Module │ │ Module │ │ Module │ │+Invoice│ │ Module │   │
│  └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘   │
│      │          │          │          │          │          │          │
│  ┌───┴──────────┴──────────┴──────────┴──────────┴──────────┴──────┐  │
│  │                    Spring Application Core                        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
        │                 │                 │
  ┌─────┴────┐      ┌─────┴────┐      ┌─────┴────┐
  │PostgreSQL│      │  Redis   │      │  Kafka   │
  │  (ACID)  │      │(Lock+Pub)│      │ (Events) │
  └──────────┘      └──────────┘      └──────────┘
```

### 2.3 Estrutura de Pacotes

```
ao.com.angotech/
├── config/                     # Configurações Spring (Security, WebSocket, Kafka, Redis)
├── modules/
│   ├── auth/                   # Autenticação e autorização
│   │   ├── controller/ service/ service/impl/ dto/ security/
│   ├── auction/                # Gestão de leilões
│   │   ├── controller/ service/ service/impl/ domain/ repository/ dto/ event/
│   ├── bidding/                # Sistema de lances (core crítico)
│   │   ├── controller/ service/ service/impl/ domain/ repository/ command/ event/
│   ├── deposit/                # Caução (holdDeposit, release, capture)
│   │   ├── controller/ service/ service/impl/ domain/ repository/ dto/
│   ├── payment/                # Pagamento + segunda oferta + factura
│   │   ├── controller/ service/ service/impl/ domain/ repository/ dto/ gateway/
│   ├── realtime/               # WebSocket + STOMP + broadcast
│   │   ├── config/ publisher/
│   ├── notification/           # Notificações (email, push, ws)
│   │   ├── service/ service/impl/ kafka/
│   └── audit/                  # Auditoria e Event Store
│       ├── entity/ repository/ service/ service/impl/
├── shared/
│   ├── exception/              # GlobalExceptionHandler, exceptions de domínio
│   ├── response/               # ApiResponse<T> wrapper padrão
│   └── validation/             # ex.: @AngolanesePhone, @Nif
└── infrastructure/
    ├── kafka/  redis/  scheduler/
```

**Nota de migração:** O código existente em `ao.com.angotech.entity/.service/.controller/.dto/.security` deve ser movido para `ao.com.angotech.modules.auth.*`.

### 2.4 Stack Detalhada

| Camada | Tecnologia | Versão | Justificação |
|--------|-----------|--------|-------------|
| Framework | Spring Boot | 3.5.14 | Base do projecto |
| Linguagem | Java | 17 | LTS, records, sealed classes |
| BD Principal | PostgreSQL | 16 | ACID forte, advisory locks, JSONB |
| Migrations | Flyway | gerido pelo Boot | Versionamento de schema |
| Cache / Pub-Sub | Redis | 7 | Pub/Sub WebSocket multi-instância |
| Distributed Lock | Redisson | 3.27.2 | Watchdog automático |
| Mensageria | Apache Kafka | 3.7+ | Ordenação garantida, replay |
| WebSocket | Spring WebSocket + STOMP | gerido pelo Boot | Tempo real de baixa latência |
| Segurança | Spring Security + JJWT | 0.11.5 | JWT + Refresh Token |
| Testes | JUnit 5 + Testcontainers | gerido pelo Boot | Integração realista |
| Observabilidade | Micrometer + Actuator | gerido pelo Boot | Métricas expostas |

### 2.5 Padrão de Consistência — três camadas de protecção

| Camada | Protege contra | Mecanismo |
|--------|---------------|-----------|
| Redisson Lock | Race conditions entre instâncias da app | `RLock.lock()` com watchdog |
| `SELECT FOR UPDATE` | Race conditions dentro da mesma instância | SQL pessimistic lock |
| `@Transactional` | Atomicidade da operação completa | PostgreSQL transaction |
| `@Version` (optimistic) | Rede de segurança final | `OptimisticLockException` → retry |

> **Decisão deliberada:** A fonte de verdade do lance é sempre o PostgreSQL (ACID + histórico imutável), **não** o Redis. O Redis é usado apenas para *lock distribuído* e *pub/sub de broadcast*, nunca como estado autoritativo. Isto garante a invariante R-04 (imutabilidade auditável).

### 2.6 Tópicos Kafka

| Tópico | Produtor | Consumidor | Finalidade |
|--------|----------|-----------|-----------|
| `auction-bids` | BidService | BidProjectionConsumer, NotificationConsumer | Lance submetido com sucesso |
| `auction-events` | AuctionService | AuditConsumer, NotificationConsumer | Criação, mudança de estado, cancelamento |
| `auction-finished` | AuctionScheduler | WinnerNotificationConsumer, DepositConsumer, PaymentConsumer | Leilão finalizado |
| `payment-events` | PaymentService | InvoiceConsumer, NotificationConsumer | Pagamento confirmado/falhado |
| `notifications` | Qualquer | NotificationDispatchConsumer | Envio de email/push/SMS |

Todos os tópicos usam a chave `auctionId` para garantir ordenação por partição.

### 2.7 Infra (docker-compose.yml)

PostgreSQL 16 (porta **5433**), Redis 7 (6379), Kafka 3.7+ com Zookeeper (9092). Ver `docker-compose.yml` na raiz. Kafka usa `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"` em dev.

### 2.8 Fora do MVP

- Event Sourcing completo (apenas Event Store simplificado)
- Proxy bidding / auto-bid
- KYC avançado com verificação de documento
- Notificações push mobile (FCM/APNs) e SMS
- Upload de fotos directo para S3/R2
- Leilões reversos · Blockchain (nunca — PostgreSQL + audit log é suficiente)

---

## 3. Regras de Negócio Globais (invariantes)

Estas regras aplicam-se a todo o sistema e **nunca podem ser violadas sem revisão de produto**.

| ID | Regra |
|----|-------|
| **R-01** | Um leilão só aceita lances quando o seu estado é `ACTIVE` ou `EXTENDED`. |
| **R-02** | `Bid.amount > Auction.currentHighestBid.amount + Auction.minIncrement` (ou `>= initialPrice` no primeiro lance). |
| **R-03** | O `timestamp` de cada lance é sempre definido pelo servidor (nunca pelo cliente). |
| **R-04** | Uma vez registado, um lance é imutável — zero UPDATE ou DELETE na tabela `bids`. |
| **R-05** | O vencedor é o portador do maior lance válido no momento em que o leilão termina. |
| **R-06** | Se `reservePrice` estiver definido, o leilão só é "vendido" se `highestBid >= reservePrice`. |
| **R-07** | Anti-sniping: um lance nos últimos `antiSnipingMinutes` (padrão 5) estende `endTime` em `extensionMinutes` (padrão 5). |
| **R-08** | Dois lances com o mesmo `amount` no mesmo leilão nunca coexistem como válidos (UNIQUE INDEX — o segundo é rejeitado). |
| **R-09** | Anti-sniping tem um máximo de extensões por leilão (`maxExtensions`, padrão **3**). Atingido o limite, os lances continuam a ser aceites mas já **não** estendem o `endTime` — evita leilão infinito. |
| **R-10** | Um utilizador **não pode licitar no seu próprio leilão** (self-bidding proibido). |
| **R-11** | Só pode licitar quem tiver uma caução (`Deposit`) em estado `HELD` para aquele leilão. |
| **R-12** | Ao fechar o leilão, a caução dos **não-vencedores** é libertada (`RELEASED`) automaticamente; a caução do vencedor mantém-se `HELD` até à liquidação. |
| **R-13** | Se o vencedor não pagar dentro do prazo (`paymentDeadline`, padrão **48h**), a sua caução é **capturada** (`CAPTURED`) e o leilão passa a segunda oferta. |
| **R-14** | Toda operação de pagamento é **idempotente**, identificada por `idempotencyKey` — retries e webhooks duplicados nunca geram cobrança duplicada. |
| **R-15** | Valores monetários são sempre em Kwanza (AOA), com precisão fixa (`NUMERIC(18,2)` / `BigDecimal`), **nunca `float`/`double`**. |
| **R-16** | **BI e NIF** do vencedor são obrigatórios antes de confirmar o pagamento e emitir factura (não exigidos no registo, para não gerar fricção a quem só navega). |
| **R-17** | Toda factura é emitida em conformidade com **AGT / SAF-T Angola**, com referência única. |

---

## 4. Requisitos Não-Funcionais (NFR)

| ID | Requisito | Critério de aceitação |
|----|-----------|------------------------|
| **NFR-01** | Latência de broadcast | < 500ms entre lance aceite e clientes a verem o novo valor |
| **NFR-02** | Consistência atómica | Nenhuma race condition gera dois lances "vencedores" simultâneos (Redisson + `SELECT FOR UPDATE` + `@Version`) |
| **NFR-03** | Reconexão resiliente | Cliente WebSocket reconecta com backoff exponencial; **ao reconectar, pede sempre o estado actual** ao servidor (`GET /auctions/{id}`) antes de confiar em broadcasts |
| **NFR-04** | Baixo consumo de dados | Payloads WebSocket < 2KB; imagens de produto optimizadas/comprimidas |
| **NFR-05** | Idempotência | Toda operação de pagamento usa idempotency key (ver R-14) |
| **NFR-06** | Fuso horário | Persistência sempre em **UTC** (`Instant`); apresentação em WAT (UTC+1, sem DST) é responsabilidade do cliente |
| **NFR-07** | Conformidade legal — dados pessoais | Tratamento conforme **Lei n.º 22/11**: consentimento no registo, direito de acesso (`GET /auth/me`), rectificação (`PUT /auth/me`), e eliminação/anonimização de conta (`DELETE /auth/me`) preservando o histórico legal de lances anonimizado |
| **NFR-08** | Auditoria | Todo lance e pagamento persistido de forma imutável em PostgreSQL, para auditoria e disputa |
| **NFR-09** | Latência de lance | p95 < 200ms, p99 < 500ms em condições normais |
| **NFR-10** | Segurança | OWASP Top 10 — validação em todas as entradas, rate limiting em login e lances |

---

## 5. Módulo — Segurança & Autenticação

> **Pacote:** `ao.com.angotech.modules.auth` · **Prioridade:** Must Have · **Estado:** Parcialmente implementado

### 5.1 Estado actual vs. o que falta

**Já implementado ✅:** `User` (implementa `UserDetails`), `JwtService`, `JwtFilter`, `CustomUserDetailsService`, `SecurityConfig`, `AuthController` (`/auth/register`, `/auth/login`), `AuthService`/`AuthServiceImpl`, DTOs.

**Falta implementar ⚠️:**
- `POST /auth/refresh` (token rotation), `POST /auth/logout` (blacklist Redis), `GET /auth/me`, `PUT /auth/change-password`
- `PUT /auth/me` (rectificação) e `DELETE /auth/me` (eliminação — NFR-07)
- Admin: listar/bloquear/desbloquear utilizadores
- Roles `ROLE_BUYER`, `ROLE_SELLER`, `ROLE_ADMIN`
- Migration `V1__create_users_table.sql` e remover `ddl-auto: create`
- Campos fiscais `bi_number` e `nif` (nullable — preenchidos antes de pagar, R-16)

### 5.2 Modelo de dados — `V1__create_users_table.sql`

```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255),
    phone       VARCHAR(50),
    bi_number   VARCHAR(30),   -- Bilhete de Identidade (nullable até pagar, R-16)
    nif         VARCHAR(30),   -- Número de Identificação Fiscal (nullable até pagar)
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_users_email ON users(email);
```

### 5.3 Roles e Permissões

| Role | Constante | Capacidades |
|------|-----------|-------------|
| `ROLE_BUYER` | `Roles.BUYER` | Ver leilões, dar lances, consultar histórico próprio |
| `ROLE_SELLER` | `Roles.SELLER` | Criar/gerir leilões próprios + capacidades de BUYER |
| `ROLE_ADMIN` | `Roles.ADMIN` | Acesso total: utilizadores, leilões, auditoria, relatórios |

Um utilizador pode ter múltiplos roles (ex.: seller + buyer).

### 5.4 Contratos de API

```
POST /auth/register    → 201 {id, email, fullName, roles}   | 409 email existe · 400 inválido
POST /auth/login       → 200 {accessToken, refreshToken, tokenType, expiresIn}  | 401 · 403 desactivado
POST /auth/refresh     → 200 {accessToken, refreshToken (novo — rotation), ...} | 401
POST /auth/logout      → 204  (adiciona jti à blacklist Redis, TTL = tempo restante do token)
GET  /auth/me          → 200 {id, email, fullName, phone, biNumber, nif, roles, createdAt}
PUT  /auth/me          → 200  (rectificação de dados pessoais + preenchimento de BI/NIF)  — NFR-07, R-16
DELETE /auth/me        → 204  (elimina conta; anonimiza histórico de lances)               — NFR-07
PUT  /auth/change-password → 204
GET  /admin/users              (ADMIN) → 200 paginado {content, totalElements, totalPages}
PUT  /admin/users/{id}/toggle-status (ADMIN) → 200 {id, enabled, message}
```

**Registo:**
```json
{ "email": "joao@exemplo.ao", "password": "MinhaPass@123",
  "fullName": "João Silva", "phone": "+244 923 000 000", "role": "BUYER" }
```

### 5.5 Regras de validação

| Campo | Regra |
|-------|-------|
| `email` | Formato válido, único |
| `password` | Mínimo 8 caracteres, 1 maiúscula, 1 número |
| `fullName` | Obrigatório, 2–100 caracteres |
| `phone` | Opcional, formato angolano (`+244` ou `9xx xxx xxx`) |
| `role` no registo | Só `BUYER` ou `SELLER` (ADMIN criado internamente) |
| `nif` | Validado no formato AGT quando preenchido (`@Nif`) |

### 5.6 Segurança JWT

```yaml
jwt:
  secret: "<256 bits mínimo, via variável de ambiente em produção>"
  expiration: 86400000          # 24h (dev) — 15 min em produção
  refresh-expiration: 604800000 # 7 dias
```

- Adicionar `jti` (JWT ID) como claim — necessário para blacklist.
- Blacklist (logout): chave Redis `token:blacklist:{jti}`, TTL = tempo restante do access token. O `JwtFilter` verifica a chave antes de autorizar.
- CSRF desactivado (correcto para stateless JWT); CORS restrito ao domínio do frontend.

### 5.7 WebSocket Security

O JWT é enviado no header STOMP `connect`. O handshake WebSocket é autenticado:

```java
@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {
    @Override
    protected void configureInbound(MessageSecurityMetadataSourceRegistry messages) {
        messages
            .simpSubscribeDestMatchers("/topic/auction/**").authenticated()
            .simpDestMatchers("/app/**").authenticated()
            .anyMessage().authenticated();
    }
}
```

### 5.8 Critérios de Aceitação

| ID | Critério |
|----|----------|
| SEC-01 | Registo cria utilizador com password BCrypt |
| SEC-02 | Login retorna JWT válido com roles correctos |
| SEC-03 | Endpoint protegido retorna 401 sem token |
| SEC-04 | Admin não pode ser criado via registo público (`role: ADMIN` → 400) |
| SEC-05 | Utilizador bloqueado não autentica (login → 403) |
| SEC-06 | Refresh token expirado retorna 401 |
| SEC-07 | Token na blacklist é rejeitado após logout |
| SEC-08 | Rate limiting bloqueia > 5 tentativas de login falhadas em 1 min |
| SEC-09 | `DELETE /auth/me` anonimiza o histórico de lances sem violar R-04 |

---

## 6. Módulo — Gestão de Leilões

> **Pacote:** `ao.com.angotech.modules.auction` · **Prioridade:** Must Have · **Depende de:** §5

### 6.1 Máquina de Estados do Leilão

```
DRAFT ─► SCHEDULED ─► ACTIVE ⇄ EXTENDED ─► FINISHED ─┬─► AWAITING_PAYMENT ─► SETTLED
  │           │          │                            │        │
  │           │          │                            │        └─(48h sem pagar)─► SECOND_CHANCE ─► AWAITING_PAYMENT
  │           │          │                            └─(sem vencedor / reserva não atingida)─► UNSOLD
  └───────────┴──────────┴──────────────► CANCELLED  (a qualquer momento antes de FINISHED)
```

| Estado | Descrição | Quem transita |
|--------|-----------|---------------|
| `DRAFT` | Criado mas não publicado | Seller edita, Admin |
| `SCHEDULED` | Publicado, aguarda `startTime` | Sistema (scheduler) → ACTIVE |
| `ACTIVE` | A decorrer, aceita lances | Sistema/lance → EXTENDED |
| `EXTENDED` | Timer estendido por anti-sniping (até `maxExtensions`, R-09) | Sistema → FINISHED |
| `FINISHED` | Timer terminou, vencedor determinado (R-05) | Sistema → AWAITING_PAYMENT / UNSOLD |
| `AWAITING_PAYMENT` | Há vencedor e reserva atingida; aguarda pagamento até `paymentDeadline` | Pagamento → SETTLED · Timeout → SECOND_CHANCE |
| `SECOND_CHANCE` | Vencedor faltou; oferta ao 2.º maior lance (R-13, FR-08) | → AWAITING_PAYMENT / UNSOLD |
| `SETTLED` | Pago e concluído, factura emitida | Terminal |
| `UNSOLD` | Sem vencedor válido (sem lances, reserva não atingida, ou esgotadas as segundas ofertas) | Terminal |
| `CANCELLED` | Cancelado antes de FINISHED | Seller (antes de ACTIVE), Admin (qualquer) |

### 6.2 Modelo de dados — `V2__create_auctions_tables.sql`

```sql
CREATE TYPE auction_status AS ENUM (
    'DRAFT', 'SCHEDULED', 'ACTIVE', 'EXTENDED', 'FINISHED',
    'AWAITING_PAYMENT', 'SECOND_CHANCE', 'SETTLED', 'UNSOLD', 'CANCELLED'
);

CREATE TABLE auction_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    category    VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE auction_item_photos (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id  UUID NOT NULL REFERENCES auction_items(id) ON DELETE CASCADE,
    url      VARCHAR(500) NOT NULL,
    position SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE auctions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id            UUID NOT NULL REFERENCES users(id),
    item_id              UUID NOT NULL REFERENCES auction_items(id),
    status               auction_status NOT NULL DEFAULT 'DRAFT',
    initial_price        NUMERIC(18, 2) NOT NULL CHECK (initial_price > 0),
    min_increment        NUMERIC(18, 2) NOT NULL CHECK (min_increment > 0),
    reserve_price        NUMERIC(18, 2),
    deposit_required     NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (deposit_required >= 0),  -- caução (R-11)
    current_highest_bid  NUMERIC(18, 2),
    current_winner_id    UUID REFERENCES users(id),
    start_time           TIMESTAMPTZ NOT NULL,
    end_time             TIMESTAMPTZ NOT NULL,
    original_end_time    TIMESTAMPTZ NOT NULL,
    anti_sniping_minutes SMALLINT NOT NULL DEFAULT 5,
    extension_minutes    SMALLINT NOT NULL DEFAULT 5,
    extension_count      SMALLINT NOT NULL DEFAULT 0,   -- extensões já aplicadas (R-09)
    max_extensions       SMALLINT NOT NULL DEFAULT 3,   -- limite anti-sniping (R-09)
    payment_deadline     TIMESTAMPTZ,                   -- prazo de pagamento do vencedor (R-13)
    bid_count            INTEGER NOT NULL DEFAULT 0,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ,
    CONSTRAINT end_after_start CHECK (end_time > start_time)
);

CREATE INDEX idx_auctions_status ON auctions(status);
CREATE INDEX idx_auctions_seller ON auctions(seller_id);
CREATE INDEX idx_auctions_end_time ON auctions(end_time) WHERE status IN ('ACTIVE', 'EXTENDED');
CREATE INDEX idx_auctions_payment_deadline ON auctions(payment_deadline) WHERE status = 'AWAITING_PAYMENT';
```

### 6.3 Entidade `Auction` — regras de domínio encapsuladas

```java
// ao.com.angotech.modules.auction.domain
public boolean isAcceptingBids() {
    return status == AuctionStatus.ACTIVE || status == AuctionStatus.EXTENDED;
}

public boolean isInAntiSnipingWindow(Instant now) {
    return Duration.between(now, endTime).toMinutes() <= antiSnipingMinutes;
}

/** R-09: só estende se ainda houver extensões disponíveis. */
public boolean canExtend() {
    return extensionCount < maxExtensions;
}

public void applyExtension() {
    if (!canExtend()) return;            // atingido o limite → aceita o lance mas não estende
    this.endTime = endTime.plus(extensionMinutes, ChronoUnit.MINUTES);
    this.extensionCount++;
    this.status = AuctionStatus.EXTENDED;
}

public void applyBid(UUID winnerId, BigDecimal amount) {
    this.currentWinnerId = winnerId;
    this.currentHighestBid = amount;
    this.bidCount++;
    this.updatedAt = Instant.now();
}

public void finish() {              // R-05 + R-06
    this.status = AuctionStatus.FINISHED;
    this.updatedAt = Instant.now();
}
```

Campos: `id` (UUID), `sellerId`, `item` (`@ManyToOne` LAZY), `status`, `initialPrice`, `minIncrement`, `reservePrice`, `depositRequired`, `currentHighestBid`, `currentWinnerId`, `startTime`/`endTime`/`originalEndTime` (`Instant`), `antiSnipingMinutes`, `extensionMinutes`, `extensionCount`, `maxExtensions`, `paymentDeadline`, `bidCount`, `@Version version`, `createdAt`/`updatedAt`.

### 6.4 Contratos de API

```
POST   /auctions          (SELLER, ADMIN)  → 201  {id, status: SCHEDULED, ...}
GET    /auctions          (público)        → 200  paginado  (?status=&category=&minPrice=&maxPrice=&page=&size=&sort=)
GET    /auctions/{id}      (público)        → 200  AuctionDetailResponse (inclui nextMinimumBid, timeRemainingSeconds)
GET    /auctions/my        (SELLER)         → 200  paginado (filtrado por sellerId)
PUT    /auctions/{id}      (dono, ADMIN)    → 200  | 409 se não estiver em DRAFT/SCHEDULED
DELETE /auctions/{id}      (dono, ADMIN)    → 204  (cancelamento; ACTIVE requer {reason} e notifica participantes)
```

**POST body:**
```json
{ "title": "Toyota Hilux 2022", "description": "...", "category": "VEHICLES",
  "photos": ["https://cdn.lelo.ao/img1.jpg"],
  "initialPrice": 4500000.00, "minIncrement": 50000.00, "reservePrice": 4000000.00,
  "depositRequired": 200000.00,
  "startTime": "2026-05-20T10:00:00Z", "endTime": "2026-05-20T18:00:00Z",
  "antiSnipingMinutes": 5, "extensionMinutes": 5, "maxExtensions": 3 }
```

**Validações:** `initialPrice > 0`; `minIncrement > 0`; `reservePrice >= initialPrice` se presente; `depositRequired >= 0`; `startTime > agora + 1h`; `endTime > startTime + 30min`; mínimo 1 foto; `maxExtensions >= 0`.

### 6.5 Categorias de Bens

```java
public enum ItemCategory {
    VEHICLES, REAL_ESTATE, INDUSTRIAL, MACHINERY, ELECTRONICS, FURNITURE, ART, OTHER
}
```

### 6.6 Scheduler — transições automáticas

```java
@Component
public class AuctionScheduler {

    @Scheduled(fixedDelay = 10_000) @Transactional
    public void activateScheduledAuctions() {
        // status=SCHEDULED AND start_time<=NOW() → ACTIVE + AuctionActivatedEvent
    }

    @Scheduled(fixedDelay = 5_000) @Transactional
    public void finishExpiredAuctions() {
        // status IN (ACTIVE,EXTENDED) AND end_time<=NOW()
        // → FINISHED, determinar vencedor (R-05/R-06), definir payment_deadline se houver vencedor,
        //   transitar para AWAITING_PAYMENT ou UNSOLD, publicar AuctionFinishedEvent
    }

    @Scheduled(fixedDelay = 30_000) @Transactional
    public void handlePaymentDeadlines() {
        // status=AWAITING_PAYMENT AND payment_deadline<=NOW()
        // → capturar caução do vencedor (R-13) e disparar SecondChanceService (FR-08)
    }
}
```

**Escalabilidade:** usar `ShedLock`/`@SchedulerLock` para garantir que só uma instância executa cada job.

### 6.7 Eventos Kafka (`auction-events`, `auction-finished`)

| Evento | Trigger | Payload |
|--------|---------|---------|
| `AuctionCreatedEvent` | POST /auctions | auctionId, sellerId, title, startTime |
| `AuctionActivatedEvent` | Scheduler | auctionId |
| `AuctionExtendedEvent` | Lance no anti-sniping window | auctionId, newEndTime, extensionCount |
| `AuctionFinishedEvent` | Scheduler | auctionId, winnerId, winningBid, reserveMet, paymentDeadline |
| `AuctionCancelledEvent` | Vendedor/Admin | auctionId, reason |
| `SecondChanceEvent` | Timeout de pagamento | auctionId, previousWinnerId, newWinnerId, newPaymentDeadline |

### 6.8 Critérios de Aceitação

| ID | Critério |
|----|----------|
| AUC-01 | Vendedor cria leilão → 201, status SCHEDULED |
| AUC-02 | Leilão muda para ACTIVE automaticamente na `startTime` |
| AUC-03 | Listagem retorna apenas ACTIVE por padrão |
| AUC-04 | Editar leilão ACTIVE retorna 409 |
| AUC-05 | Cancelar leilão ACTIVE notifica participantes |
| AUC-06 | Leilão sem lances → UNSOLD |
| AUC-07 | Leilão com `highestBid < reservePrice` → UNSOLD (R-06) |
| AUC-08 | Paginação e filtros funcionam |
| AUC-09 | Leilão com vencedor válido → AWAITING_PAYMENT com `paymentDeadline` definido |
| AUC-10 | Anti-sniping pára de estender após `maxExtensions` (R-09) |

---

## 7. Módulo — Sistema de Lances (Core Crítico)

> **Pacote:** `ao.com.angotech.modules.bidding` · **Prioridade:** Must Have (o mais crítico) · **Depende de:** §5, §6, §8, Redis, Kafka

Falhas aqui resultam em dois compradores a "ganhar" o mesmo leilão, lances fora de ordem, e perda de confiança. Rigor de sistema financeiro.

### 7.1 Modelo de dados — `V3__create_bids_table.sql`

```sql
CREATE TABLE bids (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id  UUID NOT NULL REFERENCES auctions(id),
    bidder_id   UUID NOT NULL REFERENCES users(id),
    amount      NUMERIC(18, 2) NOT NULL,
    timestamp   TIMESTAMPTZ NOT NULL,     -- servidor, imutável (R-03)
    ip_address  INET,                     -- auditoria anti-fraude
    user_agent  VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- R-08: dois lances com o mesmo amount no mesmo leilão são proibidos
CREATE UNIQUE INDEX uq_bids_auction_amount ON bids(auction_id, amount);
CREATE INDEX idx_bids_auction_id ON bids(auction_id);
CREATE INDEX idx_bids_bidder_id ON bids(bidder_id);
CREATE INDEX idx_bids_auction_timestamp ON bids(auction_id, timestamp DESC);
```

**A tabela `bids` é append-only. Nenhum UPDATE ou DELETE (R-04).** A entidade `Bid` não tem setters nos campos de negócio.

### 7.2 Fluxo completo de submissão de lance

```
1. WebSocket @MessageMapping("/app/auction/{auctionId}/bid")   (bidderId vem do Principal JWT)
2. BidService.placeBid(command)
   ├── Redisson RLock ("lock:auction:{auctionId}") — timeout 10s
   ├── @Transactional
   │   ├── SELECT ... FOR UPDATE (pessimistic lock)
   │   ├── Validar R-01, R-02, R-08, R-10, R-11
   │   ├── INSERT bid (timestamp do servidor — R-03)
   │   ├── UPDATE auction (currentHighestBid; applyExtension() se R-07 e canExtend() R-09)
   │   └── INSERT auction_events (event store)
   └── Kafka publish "auction-bids"  (idealmente via Outbox — ver §7.5)
3. Kafka Consumer → actualiza read model + broadcast WebSocket
```

```java
@Transactional
public BidResult placeBid(PlaceBidCommand command) {
    RLock lock = redissonClient.getLock("lock:auction:" + command.auctionId());
    lock.lock(10, TimeUnit.SECONDS);
    try {
        Auction auction = auctionRepository.findByIdWithPessimisticLock(command.auctionId())
            .orElseThrow(() -> new AuctionNotFoundException(command.auctionId()));

        validateBid(auction, command);                 // R-01, R-02, R-10, R-11
        Instant serverTimestamp = Instant.now();       // R-03

        Bid bid = new Bid(command.auctionId(), command.bidderId(),
                          command.amount(), serverTimestamp, command.ipAddress());
        bidRepository.save(bid);                        // R-08 via UNIQUE INDEX

        auction.applyBid(command.bidderId(), command.amount());
        if (auction.isInAntiSnipingWindow(serverTimestamp)) {
            auction.applyExtension();                   // R-07 + R-09
        }
        auctionRepository.save(auction);
        eventRepository.save(new AuctionEvent(auction.getId(), "BID_PLACED", buildEventPayload(bid)));

        kafkaTemplate.send("auction-bids", command.auctionId().toString(),
            new BidPlacedEvent(bid.getId(), command.auctionId(), command.bidderId(),
                command.amount(), serverTimestamp, auction.getEndTime(), auction.getBidCount()));

        return BidResult.success(bid, auction);
    } finally {
        lock.unlock();
    }
}

private void validateBid(Auction auction, PlaceBidCommand command) {
    if (!auction.isAcceptingBids())                                   // R-01
        throw new AuctionNotActiveException(auction.getId(), auction.getStatus());
    if (auction.getSellerId().equals(command.bidderId()))            // R-10
        throw new SelfBiddingException(auction.getId());
    if (!depositService.hasHeldDeposit(auction.getId(), command.bidderId())) // R-11
        throw new NoDepositException(auction.getId(), command.bidderId());
    BigDecimal minimumRequired = auction.getCurrentHighestBid() != null   // R-02
        ? auction.getCurrentHighestBid().add(auction.getMinIncrement())
        : auction.getInitialPrice();
    if (command.amount().compareTo(minimumRequired) < 0)
        throw new BidTooLowException(command.amount(), minimumRequired);
    // R-08 detectado pelo UNIQUE INDEX → DataIntegrityViolationException → DuplicateBidException
}
```

**Repository:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Auction a WHERE a.id = :id")
Optional<Auction> findByIdWithPessimisticLock(@Param("id") UUID id);
```

### 7.3 Comando, Resultado e Controller

```java
public record PlaceBidCommand(UUID auctionId, UUID bidderId, BigDecimal amount, String ipAddress) {}

public record BidResult(boolean success, UUID bidId, BigDecimal amount, Instant timestamp,
    BigDecimal newHighestBid, Instant newEndTime, int bidCount, String rejectionReason) {
    public static BidResult success(Bid bid, Auction auction) { ... }
    public static BidResult rejected(String reason) { ... }
}

@Controller
public class BidController {
    @MessageMapping("/auction/{auctionId}/bid")
    @SendToUser("/queue/bid-result")               // resposta privada ao remetente
    public BidResult placeBid(@DestinationVariable UUID auctionId,
                              @Payload PlaceBidRequest request, Principal principal,
                              @Header("ip") String ipAddress) {
        return bidService.placeBid(new PlaceBidCommand(
            auctionId, extractUserId(principal), request.amount(), ipAddress));
    }
}
```

O broadcast para todos os participantes é feito pelo Kafka Consumer (ver §11).

### 7.4 Tratamento de erros e casos edge

| Cenário | Comportamento |
|---------|---------------|
| Lance menor que mínimo | `BidTooLowException` (400) |
| Leilão não existe | `AuctionNotFoundException` (404) |
| Leilão não está ACTIVE/EXTENDED | `AuctionNotActiveException` (409) |
| Lance duplicado (mesmo amount) | `DataIntegrityViolationException` → `DuplicateBidException` (409) |
| Vendedor a licitar no próprio leilão | `SelfBiddingException` (403) — R-10 |
| Bidder sem caução HELD | `NoDepositException` (403) — R-11 |
| Lock timeout (10s) | `LockAcquisitionException` → `BidResult.rejected("Sistema ocupado, tente novamente")` |
| `OptimisticLockException` | Retry automático (máx. 3×, backoff 50ms) |

### 7.5 Rate Limiting e Outbox

- **Rate limit:** máx. **2 lances/segundo** por utilizador por leilão. Chave Redis `ratelimit:bid:{bidderId}:{auctionId}`, TTL 1s, MAX 2.
- **Transactional Outbox (produção):** gravar o evento em `outbox_events` na mesma transação; um poller (1s) publica no Kafka e marca `published=true`. Garante at-least-once mesmo em crash após commit.

```sql
CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB NOT NULL,
    published    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 7.6 Critérios de Aceitação

| ID | Critério |
|----|----------|
| BID-01 | Lance válido persistido com timestamp do servidor |
| BID-02 | Lance inválido (amount baixo) rejeitado imediatamente |
| BID-03 | 100 lances simultâneos no mesmo leilão: apenas 1 por valor é aceite |
| BID-04 | Dois lances com o mesmo amount → segundo rejeitado (R-08) |
| BID-05 | Anti-sniping estende o timer (R-07) |
| BID-06 | Vendedor não licita no próprio leilão (R-10) |
| BID-07 | Histórico ordenado por timestamp do servidor |
| BID-08 | Latência de submissão ≤ 200ms |
| BID-09 | Lock libertado mesmo em excepção (try/finally) |
| BID-10 | Evento Kafka publicado após commit |
| BID-11 | Bidder sem caução HELD é rejeitado (R-11) |
| BID-12 | Após `maxExtensions`, o lance é aceite mas não estende (R-09) |

---

## 8. Módulo — Caução / Depósitos

> **Pacote:** `ao.com.angotech.modules.deposit` · **Prioridade:** Must Have · **Depende de:** §5, §6 · **Regras:** R-11, R-12, R-13

### 8.1 Visão geral

A caução dá seriedade ao leilão: só licita quem tem "pele em jogo". Um utilizador reserva uma caução (`Deposit`) para um leilão antes de poder dar lances. A caução fica **`HELD`** enquanto o leilão decorre; ao fechar, é **`RELEASED`** para os não-vencedores. A do vencedor mantém-se `HELD` até ao pagamento e, se ele faltar, é **`CAPTURED`** (penalização) — ver R-13.

```
holdDeposit()  → HELD
   ├─ leilão fecha, é não-vencedor            → releaseDeposit()  → RELEASED   (R-12)
   ├─ é vencedor e paga o bem                 → releaseDeposit()  → RELEASED
   └─ é vencedor e não paga em 48h            → captureDeposit()  → CAPTURED   (R-13)
```

### 8.2 Modelo de dados — `V8__create_deposits_table.sql`

```sql
CREATE TYPE deposit_status AS ENUM ('HELD', 'RELEASED', 'CAPTURED');

CREATE TABLE deposits (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id   UUID NOT NULL REFERENCES auctions(id),
    user_id      UUID NOT NULL REFERENCES users(id),
    amount       NUMERIC(18, 2) NOT NULL CHECK (amount >= 0),
    status       deposit_status NOT NULL DEFAULT 'HELD',
    held_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,           -- momento do release/capture
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_deposit_auction_user UNIQUE (auction_id, user_id)  -- 1 caução por utilizador/leilão
);

CREATE INDEX idx_deposits_auction ON deposits(auction_id, status);
CREATE INDEX idx_deposits_user ON deposits(user_id);
```

### 8.3 Serviço (interface + impl)

```java
public interface DepositService {
    Deposit holdDeposit(UUID auctionId, UUID userId);      // FR-06 / R-11
    boolean hasHeldDeposit(UUID auctionId, UUID userId);   // usado por BidService.validateBid
    void releaseDeposit(UUID depositId);                   // R-12
    void captureDeposit(UUID depositId);                   // R-13
    void releaseAllNonWinners(UUID auctionId, UUID winnerId); // chamado ao fechar o leilão
}
```

`holdDeposit` só permite HELD quando o leilão está `ACTIVE`/`EXTENDED`/`SCHEDULED` e o utilizador não é o vendedor (R-10). `releaseDeposit`/`captureDeposit` são idempotentes: se já resolvido, não faz nada.

### 8.4 Contratos de API

```
POST /auctions/{auctionId}/deposit   (BUYER)  → 201 {id, amount, status: HELD}   | 409 já existe · 403 é o vendedor
GET  /auctions/{auctionId}/deposit/me (BUYER) → 200 {status, amount, heldAt}      | 404 sem caução
GET  /deposits/me                     (BUYER)  → 200 paginado (histórico próprio de cauções)
```

Release e capture **não têm endpoint público** — são accionados pelo scheduler (§6.6) e pelo consumer de `auction-finished`.

### 8.5 Critérios de Aceitação

| ID | Critério |
|----|----------|
| DEP-01 | Utilizador reserva caução → status HELD |
| DEP-02 | Segunda caução no mesmo leilão pelo mesmo utilizador → 409 (UNIQUE) |
| DEP-03 | Vendedor não pode reservar caução no próprio leilão → 403 |
| DEP-04 | `BidService` rejeita lance sem caução HELD (R-11) |
| DEP-05 | Ao fechar, cauções dos não-vencedores → RELEASED (R-12) |
| DEP-06 | Vencedor sem pagamento em 48h → caução CAPTURED (R-13) |
| DEP-07 | `releaseDeposit`/`captureDeposit` são idempotentes |

---

## 9. Módulo — Pagamento & Segunda Oferta

> **Pacote:** `ao.com.angotech.modules.payment` · **Prioridade:** Must Have · **Depende de:** §6, §8 · **Regras:** R-13, R-14, R-16

### 9.1 Visão geral

Após o leilão fechar com vencedor (`AWAITING_PAYMENT`), o vencedor tem de:
1. Preencher **BI e NIF** se ainda não o fez (R-16, via `PUT /auth/me`).
2. Pagar o valor do lance vencedor via **Multicaixa Express**, **GPO** ou **cartão**.

O pagamento é **idempotente** (R-14): cada tentativa/webhook usa uma `idempotencyKey`. Confirmado o pagamento, dispara-se a emissão de factura (§10) e a caução do vencedor é libertada. Se o prazo (`paymentDeadline`, 48h) expirar sem pagamento, a caução é capturada (R-13) e o `SecondChanceService` oferece ao 2.º maior licitador, reabrindo a janela de pagamento (FR-08).

### 9.2 Modelo de dados — `V9__create_payments_table.sql`

```sql
CREATE TYPE payment_method AS ENUM ('MULTICAIXA_EXPRESS', 'GPO', 'CARD');
CREATE TYPE payment_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED');

CREATE TABLE payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id       UUID NOT NULL REFERENCES auctions(id),
    payer_id         UUID NOT NULL REFERENCES users(id),
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    method           payment_method NOT NULL,
    status           payment_status NOT NULL DEFAULT 'PENDING',
    idempotency_key  VARCHAR(100) NOT NULL,
    provider_ref     VARCHAR(255),        -- referência do provedor (Multicaixa/GPO)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_idempotency UNIQUE (idempotency_key)   -- R-14
);

CREATE INDEX idx_payments_auction ON payments(auction_id);
CREATE INDEX idx_payments_payer ON payments(payer_id);
```

### 9.3 Serviços (interface + impl)

```java
public interface PaymentService {
    Payment chargeWinner(UUID auctionId, PaymentMethod method, String idempotencyKey); // FR-07 / R-14
    void handleWebhook(PaymentMethod provider, String rawPayload);   // confirmação assíncrona do provedor
    Payment findByAuction(UUID auctionId);
}

public interface SecondChanceService {
    void offerToNextBidder(UUID auctionId);   // FR-08 — chamado no timeout de pagamento
}
```

**`chargeWinner`:**
- Exige `status = AWAITING_PAYMENT` e que o pagador seja o `currentWinnerId`.
- Exige BI e NIF preenchidos (R-16) — senão `MissingFiscalIdentityException` (422).
- Se já existir pagamento com a mesma `idempotencyKey`, devolve-o (R-14) sem nova cobrança.
- Cria `Payment(PENDING)`, invoca o gateway (`gateway/MulticaixaGateway`, `gateway/GpoGateway`), guarda `provider_ref`.

**`handleWebhook`:** valida a assinatura do provedor, localiza o pagamento por `provider_ref`, e em `COMPLETED` → marca leilão `SETTLED`, liberta caução do vencedor, publica `payment-events` (dispara factura §10 e notificação). Idempotente por `provider_ref` + estado.

**`SecondChanceService.offerToNextBidder`:** busca o 2.º maior lance válido (que respeite reserva, R-06); se existir, define novo `currentWinnerId`, novo `paymentDeadline` (+48h), estado `AWAITING_PAYMENT`, publica `SecondChanceEvent`. Se não existir, estado `UNSOLD`.

### 9.4 Contratos de API

```
POST /auctions/{auctionId}/payment           (vencedor)  → 201 {id, status: PENDING, providerRef}
  body: { "method": "MULTICAIXA_EXPRESS", "idempotencyKey": "uuid-do-cliente" }
  erros: 403 não é o vencedor · 409 estado != AWAITING_PAYMENT · 422 BI/NIF em falta (R-16)
GET  /auctions/{auctionId}/payment/me         (vencedor)  → 200 {status, method, amount, completedAt}
POST /webhooks/payments/{provider}            (provedor)  → 200  (idempotente, assinatura validada)
```

### 9.5 Notificações relacionadas

`OUTBID`, `AUCTION_WON`, `PAYMENT_REMINDER` (24h antes do deadline), `SECOND_CHANCE` (ao 2.º licitador), `PAYMENT_CONFIRMED`.

### 9.6 Critérios de Aceitação

| ID | Critério |
|----|----------|
| PAY-01 | Vencedor paga → Payment PENDING criado com `provider_ref` |
| PAY-02 | Segunda cobrança com a mesma `idempotencyKey` → devolve o mesmo Payment, sem duplicar (R-14) |
| PAY-03 | Pagar sem BI/NIF → 422 (R-16) |
| PAY-04 | Não-vencedor tentar pagar → 403 |
| PAY-05 | Webhook COMPLETED → leilão SETTLED + caução do vencedor RELEASED + factura emitida |
| PAY-06 | Webhook duplicado não altera estado nem duplica factura |
| PAY-07 | Timeout de 48h → caução CAPTURED (R-13) + segunda oferta ao 2.º (FR-08) |
| PAY-08 | Sem 2.º licitador válido → leilão UNSOLD |

---

## 10. Módulo — Facturação (AGT / SAF-T)

> **Pacote:** `ao.com.angotech.modules.payment` (submódulo invoice) · **Prioridade:** Must Have · **Regras:** R-16, R-17

### 10.1 Visão geral

Após o pagamento `COMPLETED`, gera-se uma factura em conformidade com **AGT / SAF-T Angola**, com referência única, associada ao pagamento. Requer BI e NIF do comprador (R-16).

### 10.2 Modelo de dados — `V10__create_invoices_table.sql`

```sql
CREATE TABLE invoices (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id     UUID NOT NULL REFERENCES payments(id),
    agt_reference  VARCHAR(100) NOT NULL,     -- referência única AGT/SAF-T
    buyer_nif      VARCHAR(30) NOT NULL,
    buyer_name     VARCHAR(255) NOT NULL,
    amount         NUMERIC(18, 2) NOT NULL,
    pdf_url        VARCHAR(500),
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_invoice_payment UNIQUE (payment_id),         -- 1 factura por pagamento
    CONSTRAINT uq_invoice_agt_ref UNIQUE (agt_reference)
);

CREATE INDEX idx_invoices_payment ON invoices(payment_id);
```

### 10.3 Serviço (interface + impl)

```java
public interface InvoiceService {
    Invoice generateInvoice(UUID paymentId);   // FR-09 — idempotente (UNIQUE payment_id)
    Invoice findByPayment(UUID paymentId);
}
```

`generateInvoice` é accionado pelo consumer de `payment-events` (status COMPLETED). Idempotente: se já existir factura para o pagamento, devolve-a.

### 10.4 Contratos de API

```
GET /auctions/{auctionId}/invoice    (comprador, ADMIN) → 200 {agtReference, amount, pdfUrl, issuedAt}
GET /invoices/{id}/pdf               (comprador, ADMIN) → 200 application/pdf
```

### 10.5 Critérios de Aceitação

| ID | Critério |
|----|----------|
| INV-01 | Pagamento COMPLETED gera factura com `agt_reference` único |
| INV-02 | Reprocessar o mesmo pagamento não duplica factura (UNIQUE) |
| INV-03 | Factura contém NIF e nome do comprador (R-16) |
| INV-04 | PDF disponível e descarregável pelo comprador |

---

## 11. Módulo — Tempo Real & Notificações

> **Pacotes:** `ao.com.angotech.modules.realtime` + `ao.com.angotech.modules.notification` · **Depende de:** §7, Redis Pub/Sub, Kafka

### 11.1 Arquitectura

Dois componentes: **broadcast de lances** (actualização instantânea a todos os participantes) e **notificações pessoais** (mensagens dirigidas a um utilizador). Transporte via **WebSocket + STOMP**; coordenação entre instâncias via **Redis Pub/Sub**; **Kafka** como fonte de eventos.

### 11.2 Configuração WebSocket

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();  // restringir origem em produção
    }
}
```

Multi-instância: publicar broadcasts também no Redis Pub/Sub (`auction:{auctionId}`) e retransmitir via `SimpMessagingTemplate` em cada instância.

### 11.3 Canais STOMP

| Canal | Tipo | Quem recebe | Conteúdo |
|-------|------|-------------|----------|
| `/topic/auction/{id}` | Broadcast | Todos subscritos ao leilão | Novo lance, timer |
| `/topic/auction/{id}/status` | Broadcast | Todos subscritos | Mudança de estado |
| `/user/queue/bid-result` | Privado | Só o bidder | Confirmação/rejeição do lance |
| `/user/queue/notifications` | Privado | Utilizador autenticado | Ultrapassado, vencedor, pagamento, etc. |

### 11.4 Mensagens de broadcast

```json
// /topic/auction/{id}
{ "type": "BID_PLACED", "auctionId": "uuid", "newHighestBid": 4800000.00,
  "bidderDisplayName": "J***a", "bidCount": 13, "endTimeEpochMs": 1716220800000, "wasExtended": false }

// /topic/auction/{id}/status
{ "type": "AUCTION_FINISHED", "auctionId": "uuid", "status": "AWAITING_PAYMENT",
  "winnerDisplayName": "J***a", "winningBid": 5200000.00, "reserveMet": true }
```

O nome do bidder é sempre **anonimizado** no broadcast público (`J***a`) — R-03 do isolamento (FR-03).

### 11.5 Fluxo de broadcast e contagem regressiva

`BidService → Kafka "auction-bids" → BidBroadcastConsumer` → actualiza `auction_read_model`, `redis.publish("auction:{id}", msg)` e `messagingTemplate.convertAndSend("/topic/auction/{id}", msg)`.

A **contagem regressiva não é enviada a cada segundo**. O servidor envia `endTimeEpochMs`; o cliente calcula localmente. Só há novo `endTimeEpochMs` quando o timer muda (anti-sniping, `wasExtended: true`). Em reconexão (NFR-03), o cliente pede o estado actual antes de confiar no countdown.

### 11.6 Notificações — tipos e modelo

| Tipo | Trigger | Canal |
|------|---------|-------|
| `OUTBID` | Lance maior aceite | WS + Email |
| `AUCTION_WON` | Leilão finalizado | WS + Email + SMS |
| `PAYMENT_REMINDER` | 24h antes do deadline | Email + SMS |
| `SECOND_CHANCE` | Oferta ao 2.º licitador | WS + Email + SMS |
| `PAYMENT_CONFIRMED` | Pagamento COMPLETED | WS + Email |
| `AUCTION_CANCELLED` | Leilão cancelado | WS + Email |
| `AUCTION_STARTING` | 30 min antes do início | Email + Push |
| `AUCTION_SOLD` | Vendido (ao vendedor) | WS + Email |

```sql
-- V5__create_notifications_table.sql
CREATE TYPE notification_type AS ENUM (
    'OUTBID','AUCTION_WON','AUCTION_CANCELLED','PAYMENT_REMINDER',
    'SECOND_CHANCE','PAYMENT_CONFIRMED','AUCTION_STARTING','AUCTION_SOLD'
);
CREATE TABLE notifications (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  UUID NOT NULL REFERENCES users(id),
    type     notification_type NOT NULL,
    title    VARCHAR(255) NOT NULL,
    message  TEXT NOT NULL,
    payload  JSONB,
    read     BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at  TIMESTAMPTZ
);
CREATE INDEX idx_notifications_user ON notifications(user_id, read, sent_at DESC);
```

### 11.7 API REST de notificações

```
GET /notifications                 (auth) → 200 {content, unreadCount}  (?page=&size=&unreadOnly=)
PUT /notifications/{id}/read        (auth) → 204
PUT /notifications/read-all         (auth) → 204
```

### 11.8 Critérios de Aceitação

| ID | Critério |
|----|----------|
| RT-01 | Lance aceite → broadcast a todos os clientes em ≤ 500ms |
| RT-02 | Bidder recebe confirmação privada em `/user/queue/bid-result` |
| RT-03 | Bidder ultrapassado recebe `OUTBID` |
| RT-04 | Timer do cliente não diverge > 2s do servidor |
| RT-05 | Extensão de timer recebida por todos (`wasExtended: true`) |
| RT-06 | Leilão finalizado envia status a todos |
| RT-07 | Notificações persistidas na BD |
| RT-08 | WebSocket rejeita conexão sem JWT válido |
| RT-09 | Múltiplas instâncias propagam broadcasts via Redis Pub/Sub |
| RT-10 | Broadcast público nunca expõe nome completo do bidder (FR-03) |

---

## 12. Módulo — Auditoria & Histórico

> **Pacote:** `ao.com.angotech.modules.audit` · **Depende de:** §6, §7 · **Regras:** R-04, NFR-08

### 12.1 Princípios

1. **Append-only:** nenhum evento é alterado ou eliminado.
2. **Timestamp do servidor:** sem confiança no cliente.
3. **Rastreabilidade completa:** toda acção que altera estado gera registo.

### 12.2 Modelo de dados — `V4__create_audit_tables.sql`

```sql
CREATE TABLE auction_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id    UUID NOT NULL REFERENCES auctions(id),
    event_type    VARCHAR(100) NOT NULL,
    actor_id      UUID REFERENCES users(id),   -- null = sistema
    actor_role    VARCHAR(50),
    payload       JSONB NOT NULL,
    ip_address    INET,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sequence_num  BIGINT NOT NULL              -- ordenação garantida por leilão
);
CREATE SEQUENCE auction_event_seq START 1 INCREMENT 1;
CREATE INDEX idx_auction_events_auction ON auction_events(auction_id, sequence_num);
CREATE INDEX idx_auction_events_type ON auction_events(event_type);
CREATE INDEX idx_auction_events_actor ON auction_events(actor_id);

CREATE TABLE admin_audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     UUID NOT NULL REFERENCES users(id),
    action       VARCHAR(100) NOT NULL,
    target_type  VARCHAR(50) NOT NULL,   -- 'USER','AUCTION','BID'
    target_id    UUID NOT NULL,
    details      JSONB,
    ip_address   INET,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_log_admin ON admin_audit_log(admin_id);
CREATE INDEX idx_admin_log_target ON admin_audit_log(target_type, target_id);
```

### 12.3 Tipos de evento

**`auction_events`:** `AUCTION_CREATED`, `AUCTION_UPDATED`, `AUCTION_ACTIVATED`, `BID_PLACED`, `BID_REJECTED`, `AUCTION_EXTENDED`, `AUCTION_FINISHED`, `AUCTION_CANCELLED`, `DEPOSIT_HELD`, `DEPOSIT_RELEASED`, `DEPOSIT_CAPTURED`, `PAYMENT_COMPLETED`, `SECOND_CHANCE_OFFERED`, `INVOICE_ISSUED`.

**`admin_audit_log`:** `USER_BLOCKED`, `USER_UNBLOCKED`, `AUCTION_FORCE_CANCELLED`, `BID_INVALIDATED`, `USER_ROLE_CHANGED`.

### 12.4 Serviço

```java
public interface AuditService {
    void recordAuctionEvent(UUID auctionId, String eventType, UUID actorId,
                            String actorRole, Map<String,Object> payload, String ipAddress);
    void recordAdminAction(UUID adminId, String action, String targetType,
                           UUID targetId, Map<String,Object> details, String ipAddress);
}
```

O `AuditService` é chamado **dentro da mesma transação** da operação de negócio — o evento é persistido ou revertido junto com ela. `sequence_num` vem de `nextval('auction_event_seq')`.

### 12.5 Contratos de API

```
GET /auctions/{id}/bids           (público)  → 200 histórico anonimizado (bidderDisplayName "J***a", isWinning)
GET /auctions/{id}/events         (ADMIN)    → 200 event store completo com identidades e IPs
GET /admin/audit                  (ADMIN)    → 200 paginado (?targetType=&targetId=&fromDate=&toDate=)
GET /auctions/{id}/bids/export    (dono,ADMIN)→ CSV/PDF com identidades completas
```

O nome completo do bidder **nunca** é exposto publicamente — só ADMIN e o vendedor (na exportação) vêem identidades.

### 12.6 Política de retenção

| Tabela | Retenção | Motivo |
|--------|----------|--------|
| `bids`, `auction_events` | ≥ 5 anos | Obrigação legal, disputas |
| `payments`, `invoices` | ≥ 7 anos | Conformidade fiscal AGT |
| `admin_audit_log` | 7 anos | Conformidade |
| `notifications` | 90 dias | Operacional |

Backup: snapshot diário do PostgreSQL (retenção 30 dias) + WAL incremental para PITR.

### 12.7 Critérios de Aceitação

| ID | Critério |
|----|----------|
| AUD-01 | Todo lance aceite gera `BID_PLACED` |
| AUD-02 | Todo lance recusado gera `BID_REJECTED` |
| AUD-03 | Histórico de lances é imutável (sem UPDATE/DELETE) |
| AUD-04 | Endpoint público não expõe nome completo do bidder |
| AUD-05 | Admin vê histórico completo com identidades |
| AUD-06 | Acção de admin registada em `admin_audit_log` |
| AUD-07 | `sequence_num` correctamente crescente por leilão |
| AUD-08 | Exportação CSV ordenada por timestamp |
| AUD-09 | Eventos de caução/pagamento/factura registados no event store |

---

## 13. Modelo de Dados Consolidado & Migrações Flyway

Ficheiros em `src/main/resources/db/migration/`. Apenas Flyway gere o schema (`ddl-auto: validate`, nunca `create` fora de dev/test).

| Versão | Ficheiro | Conteúdo |
|--------|----------|----------|
| V1 | `V1__create_users_table.sql` | users (+ bi_number, nif), user_roles |
| V2 | `V2__create_auctions_tables.sql` | auction_items, auction_item_photos, auctions (com extension_count, max_extensions, deposit_required, payment_deadline e estados de liquidação) |
| V3 | `V3__create_bids_table.sql` | bids (append-only, UNIQUE INDEX auction_id+amount) |
| V4 | `V4__create_audit_tables.sql` | auction_events, admin_audit_log |
| V5 | `V5__create_notifications_table.sql` | notifications |
| V6 | `V6__create_outbox_table.sql` | outbox_events |
| V7 | `V7__seed_admin_user.sql` | Utilizador admin inicial |
| V8 | `V8__create_deposits_table.sql` | deposits (caução) |
| V9 | `V9__create_payments_table.sql` | payments |
| V10 | `V10__create_invoices_table.sql` | invoices (AGT/SAF-T) |

Resumo de tabelas: `users`, `user_roles`, `auction_items`, `auction_item_photos`, `auctions`, `bids`, `deposits`, `payments`, `invoices`, `auction_events`, `admin_audit_log`, `notifications`, `outbox_events`.

---

## 14. Convenções de Código

### 14.1 Test-First (obrigatório, sem excepções)

Antes de qualquer linha de código de produção, os testes têm de existir primeiro. Ordem: **ANALISAR → ESCREVER TESTES (RED) → IMPLEMENTAR (GREEN) → REFACTOR**.

Três tipos, todos obrigatórios:
- **Unidade** — isola uma classe, sem Spring/BD/rede. Lógica de domínio (validações, cálculos, regras da entidade). `.../modules/{modulo}/domain/`. JUnit 5 + Mockito.
- **Integração** — camada completa com BD/Redis/Kafka reais via Testcontainers. Service e Controller (MockMvc). `.../modules/{modulo}/`. `@SpringBootTest` + `@Testcontainers`.
- **E2E** — fluxo completo como cliente externo. HTTP real (`WebEnvironment.RANDOM_PORT`), WebSocket real. `.../e2e/`.

Convenção de nomes: `dado{contexto}_quando{accao}_entao{resultado}`.

Não aceitável: código antes dos testes; testar só o happy path; mockar `BidService`/repositórios nos testes de concorrência; `@Disabled` em testes que falham; testes dependentes de ordem.

### 14.2 Arquitectura em camadas

**Controller** — porta de entrada: recebe HTTP, valida input básico (`@Valid`), extrai `@AuthenticationPrincipal`, **delega tudo ao Service**, devolve `ResponseEntity<ApiResponse<T>>`. Nunca contém lógica de negócio, chamadas a repositório, ou tratamento de exceptions de domínio.

**Service** — onde vive toda a lógica: orquestra, aplica R-01…R-17, coordena repositórios/infra, gere `@Transactional`, lança exceptions de domínio, mapeia entidades → DTO. Nunca lida com HTTP nem devolve entidades JPA.

**Interface + Impl obrigatório** para todo Service:
```
modules/{modulo}/service/AuctionService.java          ← interface
modules/{modulo}/service/impl/AuctionServiceImpl.java ← @Service implements
```

### 14.3 Outras regras

| Aspecto | Decisão |
|---------|---------|
| IDs | `UUID` gerado pelo servidor (`GenerationType.UUID`) |
| Timestamps | `Instant` (UTC) — nunca `LocalDateTime` em campos críticos |
| Optimistic lock | `@Version Long version` nas entidades mutáveis |
| DTOs | Java `record` (imutáveis) |
| Entidades imutáveis | `Bid`, `AuctionEvent`, `Invoice` sem setters de negócio |
| Dinheiro | `NUMERIC(18,2)` / `BigDecimal`, Kwanza, nunca float (R-15) |
| Migrations | só Flyway (`V{n}__{descricao}.sql`) |
| Nomes de tabelas | snake_case |
| Respostas de API | sempre `ApiResponse<T>` |
| Exceptions | exceptions de domínio específicas (`BidTooLowException`) — nunca `RuntimeException` genérico |
| Transacções | `@Transactional(readOnly=true)` na classe Impl, `@Transactional` override nos métodos de escrita |
| **Lombok** | **Não usar** — código Java escrito manualmente |

### 14.4 Padrão de resposta

```java
return ResponseEntity.ok(ApiResponse.success(data));
return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data));
return ResponseEntity.status(409).body(ApiResponse.error("BID_TOO_LOW", mensagem));
```

---

## 15. Plano de Implementação por Fases

Cada fase produz um sistema funcional e testável. Nunca iniciar a próxima com testes falhados na actual. A ordem segue as dependências técnicas.

### Fase 1 — Fundação (1–2 semanas)
Estrutura `modules/`, `shared/exception/GlobalExceptionHandler`, `shared/response/ApiResponse<T>`, validações customizadas. `docker-compose` com Kafka. Dependências no `pom.xml` (Redisson, Kafka, WebSocket, MapStruct, Testcontainers — **sem Lombok**). `ddl-auto: validate`. Completar auth (§5): `V1`, `jti`, refresh, logout, `/auth/me` (GET/PUT/DELETE), change-password, admin de utilizadores, `V7` seed admin.
**Saída:** auth completa, testes verdes, Kafka a correr.

### Fase 2 — Gestão de Leilões (2–3 semanas)
`V2`, entidades `Auction`/`AuctionItem` com domínio (incl. `canExtend`, R-09), `AuctionRepository` com pessimistic lock, CRUD (§6.4), `AuctionScheduler` (activate/finish/**payment-deadline**) com `ShedLock`, `V4` + auditoria básica.
**Saída:** fluxo de leilão sem lances; scheduler testado; estados de liquidação modelados.

### Fase 3 — Sistema de Lances + Caução (3–4 semanas) ★ CORE CRÍTICO
`RedissonConfig`, `KafkaConfig`, `V3`, `V8` (deposits). `Bid` imutável, `BidRepository`, `PlaceBidCommand`, `BidResult`, `BidService.placeBid` (locks + R-01…R-11), `DepositService` (hold/release/capture, R-11…R-13). Exceptions de domínio. Auditoria `BID_PLACED`/`BID_REJECTED`.
**Testes críticos:** 50 threads → 0 duplicados; ordem por timestamp; anti-sniping + cap (R-09); rejeição sem caução (R-11); load k6 (≤ 200ms).
**Saída:** zero duplicados em 1000 lances concorrentes; caução integrada.

### Fase 4 — Tempo Real (1–2 semanas)
`WebSocketConfig` + `WebSocketSecurityConfig`, `BidController` (`@MessageMapping`/`@SendToUser`), `BidBroadcastConsumer`, `AuctionStatusConsumer`, Redis Pub/Sub multi-instância. `V5` + notificações (`OUTBID`, `AUCTION_WON`, `SECOND_CHANCE`, etc.). API REST de notificações.
**Saída:** demo em tempo real completa; broadcast < 500ms.

### Fase 5 — Pagamento, Segunda Oferta & Factura (2–3 semanas)
`V9` (payments), `V10` (invoices). `PaymentService` (chargeWinner + webhook, idempotência R-14), gateways Multicaixa/GPO, `SecondChanceService` (FR-08), `InvoiceService` (AGT/SAF-T). Consumers `payment-events`. `PUT /auth/me` para BI/NIF (R-16). `V6` outbox.
**Testes:** idempotência de pagamento; timeout 48h → capture + segunda oferta; factura única por pagamento.
**Saída:** ciclo financeiro completo, conforme AGT.

### Fase 6 — Qualidade & Observabilidade (1 semana)
Rate limiting (login + lances), CORS, OWASP review, conformidade NFR-07 (Lei 22/11: `DELETE /auth/me` anonimiza). Micrometer (`bids.placed.total`, `bids.rejected.total`, `auction.lock.wait.time`, `auction.active.count`), Prometheus + Grafana, logs JSON. Swagger/OpenAPI. Load test k6 (p95 < 200ms, p99 < 500ms).
**Saída:** pronto para staging.

### Dependências entre fases
```
Fase 1 → Fase 2 → Fase 3 (crítica) → Fase 4 → Fase 5 → Fase 6
```
Fase 4 (WebSocket config) pode começar em paralelo com a Fase 3, mas a integração final aguarda a Fase 3 testada.

---

## 16. Métricas de Sucesso do MVP

| Métrica | Meta |
|---------|------|
| Latência de submissão de lance (p95) | < 200ms |
| Duplicados de lance aceites | 0 |
| Broadcast para clientes (p95) | < 500ms |
| Disponibilidade durante leilão | 99,9% |
| Lances concorrentes suportados no mesmo leilão | 500/s |
| Tempo de finalização automática após `endTime` | < 10s |
| Cobranças duplicadas (mesma idempotencyKey) | 0 |
| Facturas em conformidade AGT | 100% |
