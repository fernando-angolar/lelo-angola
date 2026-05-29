---
description: Verificar se o código de um módulo está conforme o spec e as regras do projecto
argument-hint: Nome do módulo a verificar (ex: auth, auction, bidding)
---

# Spec Check — Lelo Angola

Vais auditar o módulo **$ARGUMENTS** e verificar a sua conformidade com o spec e as regras do CLAUDE.md.

## Passo 1 — Ler o spec do módulo

Localizar e ler o spec correspondente em `docs/specs/`:

| Módulo | Spec |
|--------|------|
| auth | `02-security-auth.md` |
| auction | `03-auction-management.md` |
| bidding | `04-bidding-system.md` |
| realtime | `05-realtime.md` |
| audit | `06-audit-history.md` |

Se o módulo não tiver spec, reportar isso como problema crítico.

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

## Passo 4 — Verificar regras de negócio (módulo bidding)

Se o módulo for `bidding`, verificar explicitamente cada regra:

- [ ] **R-01** — Lances só aceites com `status = ACTIVE` ou `EXTENDED`
- [ ] **R-02** — `amount > currentHighestBid + minIncrement`
- [ ] **R-03** — `timestamp` do lance definido pelo servidor (não pelo cliente)
- [ ] **R-04** — Zero UPDATE ou DELETE em `bids` (append-only)
- [ ] **R-05** — Vencedor = maior lance válido no fim
- [ ] **R-06** — `reservePrice` verificado antes de marcar como "vendido"
- [ ] **R-07** — Anti-sniping implementado: lance nos últimos N min estende timer
- [ ] **R-08** — `UNIQUE INDEX (auction_id, amount)` existe e `DataIntegrityViolationException` é tratado como `DuplicateBidException`

Para bidding verificar também:
- [ ] Redisson `RLock` usado antes de qualquer validação
- [ ] Lock com timeout de 10s
- [ ] `SELECT ... FOR UPDATE` dentro da transacção
- [ ] Evento publicado no Kafka após commit

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
- Regra violada
- Correcção sugerida
