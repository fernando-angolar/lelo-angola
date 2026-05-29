# Lelo Angola

Plataforma digital de leilões online em tempo real para Angola — carros, imóveis e equipamentos industriais. O problema central que resolve é **consistência forte de lances em alta concorrência**: sem duplicados, sem race conditions, com histórico imutável e auditável.

## Stack

| Tecnologia | Versão | Uso |
|---|---|---|
| Java | 17 | Linguagem |
| Spring Boot | 3.5.14 | Framework |
| PostgreSQL | 16 | Base de dados principal (ACID) |
| Redis | 7 | Token blacklist + Distributed locks + Pub/Sub |
| Kafka | 3.7 | Eventos assíncronos |
| Flyway | gerido pelo Boot | Migrations SQL |
| jjwt | 0.11.5 | JWT (access + refresh tokens) |
| Testcontainers | gerido pelo Boot | Testes de integração com infra real |

## Pré-requisitos

- Java 17+
- Maven 3.9+ (ou usar o wrapper `./mvnw`)
- Docker Desktop

## Correr localmente

```bash
# 1. Subir a infra (PostgreSQL na porta 5433, Redis na 6379, Kafka na 9092)
docker-compose up -d

# 2. Verificar que os serviços estão prontos
docker-compose ps

# 3. Correr a aplicação
./mvnw spring-boot:run

# 4. Verificar saúde
curl http://localhost:8080/health
```

## Correr os testes

Os testes de integração usam Testcontainers e precisam do Docker a correr.

```bash
# Todos os testes
./mvnw test

# Apenas testes unitários (sem Docker)
./mvnw test -Dtest="**/domain/**"
```

## API — Autenticação

Todas as respostas seguem o envelope `ApiResponse<T>`:

```json
{ "success": true, "data": { ... } }
{ "success": false, "errorCode": "...", "message": "..." }
```

### Endpoints públicos

| Método | Endpoint | Descrição |
|---|---|---|
| `POST` | `/auth/register` | Registar novo utilizador (BUYER ou SELLER) |
| `POST` | `/auth/login` | Login — devolve access token + refresh token |
| `POST` | `/auth/refresh` | Renovar tokens (rotation — o refresh token antigo é invalidado) |

### Endpoints autenticados

| Método | Endpoint | Descrição |
|---|---|---|
| `POST` | `/auth/logout` | Logout — invalida tokens na blacklist Redis |
| `GET` | `/auth/me` | Perfil do utilizador autenticado |
| `PUT` | `/auth/change-password` | Mudar password |

### Endpoints de administração (role: ADMIN)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/admin/users` | Listar utilizadores com paginação e filtros |
| `PUT` | `/admin/users/{id}/toggle-status` | Activar/desactivar utilizador |

### Exemplos

**Registo:**
```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"joao@lelo.ao","password":"Senha@1234","fullName":"João Silva","role":"BUYER"}'
```

**Login:**
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"joao@lelo.ao","password":"Senha@1234"}'
```

**Refresh:**
```bash
curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"eyJ..."}'
```

**Logout:**
```bash
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"eyJ..."}'
```

## Segurança JWT

- **Access token**: 24h (15 min em produção)
- **Refresh token**: 7 dias com rotation — cada uso invalida o token anterior
- **Blacklist**: chave Redis `token:blacklist:{jti}` com TTL igual ao tempo restante do token
- O `JwtFilter` verifica a blacklist em cada pedido antes de autenticar

## Estrutura de pacotes

```
ao.com.angotech
├── modules/
│   └── auth/
│       ├── config/        SecurityConfig
│       ├── controller/    AuthController, AdminController
│       ├── domain/        User
│       ├── dto/           AuthResponse, UserResponse, ...
│       ├── exception/     DisabledUserException, InvalidTokenException, ...
│       ├── repository/    UserRepository (JpaSpecificationExecutor)
│       ├── security/      JwtService, JwtFilter, CustomUserDetailsService
│       └── service/       AuthService (interface) + impl/AuthServiceImpl
└── shared/
    ├── controller/        HealthController
    ├── exception/         BusinessException, GlobalExceptionHandler
    └── response/          ApiResponse<T>
```

## Migrations Flyway

| Versão | Ficheiro | Estado |
|---|---|---|
| V1 | `V1__create_users_table.sql` | Criado |
| V2 | `V2__create_auctions_tables.sql` | Pendente (Fase 2) |
| V3 | `V3__create_bids_table.sql` | Pendente (Fase 3) |
| V4 | `V4__create_audit_tables.sql` | Pendente (Fase 2) |
| V5 | `V5__create_notifications_table.sql` | Pendente (Fase 4) |
| V6 | `V6__create_outbox_table.sql` | Pendente (Fase 5) |
| V7 | `V7__seed_admin_user.sql` | Pendente (Fase 1) |

## Estado do desenvolvimento

| Fase | Módulo | Estado |
|---|---|---|
| 1 | Autenticação completa + infra | Completo — 51 testes verdes |
| 2 | Gestão de leilões | Não iniciado |
| 3 | Sistema de lances (core crítico) | Não iniciado |
| 4 | Tempo real (WebSocket + Kafka) | Não iniciado |
| 5 | Qualidade e observabilidade | Não iniciado |

## Regras de negócio críticas (módulo de lances)

| Regra | Descrição |
|---|---|
| R-01 | Só aceitar lances quando `status = ACTIVE` ou `EXTENDED` |
| R-02 | `Bid.amount > currentHighestBid + minIncrement` |
| R-03 | Timestamp do lance definido pelo servidor |
| R-04 | Lances imutáveis — zero UPDATE ou DELETE em `bids` |
| R-05 | Vencedor = maior lance válido no momento do fim |
| R-06 | Só "vendido" se `highestBid >= reservePrice` |
| R-07 | Anti-sniping: lance nos últimos N min estende o timer em N min |
| R-08 | Dois lances com o mesmo `amount` no mesmo leilão são proibidos |

## Convenções

- **IDs**: `UUID` gerado pelo servidor
- **Timestamps**: `Instant` (UTC) — nunca `LocalDateTime`
- **DTOs**: Java `record` para imutabilidade
- **Services**: sempre `interface` + classe `Impl` separada
- **Respostas**: sempre `ApiResponse<T>`
- **Testes**: Test-First (RED → GREEN → REFACTOR) — nenhum código de produção sem testes primeiro
