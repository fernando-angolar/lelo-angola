---
description: Pipeline de validação local — compilar, testar e verificar que a app arranca
argument-hint: Opcional — módulo específico a testar (ex: auth, auction)
---

# Run Checks — Lelo Angola

Pipeline de validação local completo antes de qualquer commit.

## Passo 1 — Compilar

```bash
./mvnw compile -q
```

Se falhar: mostrar o erro completo e parar aqui. Não avançar com erros de compilação.

---

## Passo 2 — Correr os testes

### Todos os testes
```bash
./mvnw test
```

### Módulo específico (se `$ARGUMENTS` fornecido)
```bash
./mvnw test -pl . -Dtest="*$ARGUMENTS*"
```

Verificar no output:
- Quantos testes correram
- Se há falhas (`FAILURES`) ou erros (`ERRORS`)
- Se há testes ignorados (`@Disabled`) — reportar ao utilizador

---

## Passo 3 — Verificar que a infra está activa

```bash
docker-compose ps
```

Se PostgreSQL ou Redis não estiverem `Up`, os testes de integração vão falhar. Avisar o utilizador:
```bash
docker-compose up -d
```

---

## Passo 4 — Verificar que a app arranca

```bash
./mvnw spring-boot:run &
sleep 15
curl -s http://localhost:8080/health | python3 -m json.tool
kill %1
```

Verificar que:
- A resposta é `{"status":"UP"}` ou equivalente
- Não há erros de Flyway no startup (migration inválida)
- Não há erros de contexto Spring (beans não encontrados, config inválida)

---

## Passo 5 — Relatório final

Apresentar um resumo claro:

```
✓ Compilação: OK
✓ Testes unitários: X passaram
✓ Testes de integração: X passaram
✓ App arranca: OK
✓ Flyway: X migrations aplicadas

ou

✗ Falha em: [passo]
  Erro: [mensagem]
  Acção recomendada: [o que fazer]
```

Se algum passo falhar, diagnosticar a causa antes de sugerir a correcção. Não ignorar falhas.
