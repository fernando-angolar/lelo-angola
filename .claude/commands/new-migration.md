---
description: Criar um ficheiro Flyway SQL correctamente nomeado e validado para o projecto Lelo Angola
argument-hint: Descrição breve da migration (ex: "create auctions tables", "add reserve_price to auctions")
---

# Nova Migration Flyway — Lelo Angola

Vais criar uma migration SQL para: **$ARGUMENTS**

## Passo 1 — Determinar o próximo número de versão

Verificar os ficheiros existentes em `src/main/resources/db/migration/`:

```bash
ls src/main/resources/db/migration/
```

O número de versão seguinte é `V{N+1}` onde `N` é o maior número existente.

Migrations já definidas no plano do projecto (CLAUDE.md):
| Versão | Conteúdo previsto |
|--------|------------------|
| V1 | users, user_roles |
| V2 | auction_items, auction_item_photos, auctions |
| V3 | bids (append-only, UNIQUE INDEX) |
| V4 | auction_events, admin_audit_log |
| V5 | notifications |
| V6 | outbox_events |
| V7 | seed admin user |
| V8 | deposits (caução) |
| V9 | payments |
| V10 | invoices (AGT/SAF-T) |

Se a migration se encaixa numa das previstas, usar esse número. Se é algo novo, usar o próximo disponível.

---

## Passo 2 — Ler o spec correspondente

Antes de escrever o SQL, ler o schema exacto na secção relevante do `SPEC.md` (subsecção "Modelo de dados" de cada módulo, e o resumo na secção 13):
- Leilões → secção 6
- Lances → secção 7
- Caução → secção 8
- Pagamento → secção 9
- Facturação → secção 10
- Auditoria → secção 12

O schema SQL do SPEC.md é a fonte de verdade. Não inventar colunas.

---

## Passo 3 — Nomear o ficheiro

Formato obrigatório:
```
V{N}__{descricao_em_snake_case}.sql
```

Exemplos correctos:
- `V2__create_auctions_tables.sql`
- `V3__create_bids_table.sql`
- `V8__add_reserve_price_to_auctions.sql`

Erros comuns a evitar:
- Não usar hífen (`-`), só underscore (`_`)
- Dois underscores entre versão e descrição (`__`)
- Descrição em minúsculas e snake_case

---

## Passo 4 — Escrever o SQL

### Convenções obrigatórias

```sql
-- IDs: sempre UUID com gen_random_uuid()
id UUID PRIMARY KEY DEFAULT gen_random_uuid()

-- Timestamps: sempre TIMESTAMPTZ (UTC)
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- Locking optimista: version em tabelas mutáveis
version BIGINT NOT NULL DEFAULT 0

-- Nomes de tabelas: snake_case no plural
CREATE TABLE auction_items (...)
CREATE TABLE user_roles (...)

-- Foreign keys: sempre com nome explícito
CONSTRAINT fk_bids_auction FOREIGN KEY (auction_id) REFERENCES auctions (id)

-- Índices: sempre com nome explícito
CREATE INDEX idx_bids_auction_id ON bids (auction_id)
CREATE UNIQUE INDEX uq_bids_auction_amount ON bids (auction_id, amount)
```

### Tabelas imutáveis (bids, auction_events)

Não adicionar `version` nem `updated_at` — são append-only por design (R-04).

### Seeds (V7+)

Para dados iniciais, usar INSERT com ON CONFLICT DO NOTHING:
```sql
INSERT INTO users (id, email, password, full_name, enabled)
VALUES (gen_random_uuid(), 'admin@lelo.ao', '{bcrypt_hash}', 'Admin', true)
ON CONFLICT (email) DO NOTHING;
```

---

## Passo 5 — Criar o ficheiro

Criar em `src/main/resources/db/migration/{nome}.sql`.

---

## Passo 6 — Validar

Verificar:
- [ ] O nome do ficheiro segue o padrão `V{N}__{descricao}.sql`
- [ ] Não há `DROP TABLE` ou operações destrutivas (migrations são irreversíveis)
- [ ] Todas as foreign keys têm `CONSTRAINT` nomeado
- [ ] Todos os índices têm nome explícito
- [ ] Campos UUID usam `gen_random_uuid()`
- [ ] Campos de data usam `TIMESTAMPTZ`
- [ ] O SQL é idempotente na primeira execução (não falha se já existir — usar `IF NOT EXISTS` onde aplicável para tabelas/índices)

Correr `/run-checks` para confirmar que o Flyway valida a migration correctamente.
