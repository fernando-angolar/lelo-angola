---
description: Criar um novo módulo completo seguindo as convenções do projecto Lelo Angola
argument-hint: Nome do módulo (ex: auction, bidding, notification)
---

# Novo Módulo — Lelo Angola

Vais criar o módulo **$ARGUMENTS** de raiz, respeitando todas as regras do CLAUDE.md.

## Regra fundamental: Test-First


A ordem é **sempre** esta:
1. ANALISAR → ler a secção correspondente do `SPEC.md` (secções 5–12 mapeiam os módulos)
2. ESCREVER TESTES → criar testes (RED)
3. IMPLEMENTAR → código mínimo para os testes passarem (GREEN)
4. REFACTORING → limpar sem quebrar testes

**Nunca escrever código de produção antes dos testes.**

---

## Fase 1 — Ler o spec

1. Abrir `SPEC.md` (fonte única de verdade) e localizar a secção do módulo **$ARGUMENTS**
2. Identificar:
   - Entidades e campos
   - Regras de negócio aplicáveis (R-01 a R-17 — ver quais afectam o módulo)
   - Endpoints da API (método, path, request, response, status codes)
   - Schema SQL da tabela
   - Eventos Kafka (se aplicável)

Se o spec não existir ou estiver incompleto, perguntar ao utilizador antes de avançar.

---

## Fase 2 — Estrutura de pacotes

Criar a seguinte estrutura em `src/main/java/ao/com/angotech/modules/$ARGUMENTS/`:

```
controller/     ← @RestController, só delega ao service
service/        ← interface pública
service/impl/   ← @Service, toda a lógica de negócio
domain/         ← entidades JPA, value objects
repository/     ← interfaces Spring Data
dto/            ← Java records (imutáveis)
exception/      ← exceptions de domínio específicas
event/          ← eventos Kafka (se aplicável)
```

E em `src/test/java/ao/com/angotech/modules/$ARGUMENTS/`:

```
domain/         ← testes de unidade (sem Spring, sem BD)
*ServiceTest.java       ← integração com Testcontainers
*ControllerTest.java    ← MockMvc
```

---

## Fase 3 — Escrever os testes PRIMEIRO

### 3a. Testes de unidade (sem infra)

Localização: `src/test/java/ao/com/angotech/modules/$ARGUMENTS/domain/`

Cobrir:
- Regras de domínio na entidade (métodos de negócio)
- Validações de value objects
- Casos de erro e edge cases

Convenção de nomes obrigatória:
```
dado{contexto}_quando{accao}_entao{resultado}
```

### 3b. Testes de integração do Service

Localização: `src/test/java/ao/com/angotech/modules/$ARGUMENTS/`

```java
@SpringBootTest
@Testcontainers
@Transactional
class $ARGUMENTSServiceTest {
    // Usar BD real via Testcontainers
    // Testar happy path + erros de negócio
}
```

### 3c. Testes de Controller (MockMvc)

```java
@WebMvcTest($ARGUMENTSController.class)
class $ARGUMENTSControllerTest {
    // Verificar: rotas, status codes, formato ApiResponse<T>
    // Verificar: autorização (@PreAuthorize)
    // Verificar: validação de input (@Valid)
}
```

**Confirmar que todos os testes estão RED antes de passar à implementação.**

---

## Fase 4 — Implementar (pela ordem correcta)

1. **Migration SQL** — chamar `/new-migration` para criar o ficheiro Flyway
2. **Entidade** — com `@Entity`, UUID, `Instant`, `@Version`, sem setters em campos imutáveis
3. **Repository** — interface Spring Data JPA
4. **Exceptions** — uma por caso de erro de negócio
5. **DTOs** — Java `record` para request e response
6. **Service interface** — contrato público, sem implementação
7. **ServiceImpl** — `@Service`, `@Transactional(readOnly = true)` na classe, `@Transactional` nos métodos de escrita
8. **Controller** — só delega, nunca lógica de negócio

---

## Fase 5 — Verificar conformidade

Antes de declarar o módulo pronto, verificar:

- [ ] Todos os testes passam (GREEN)
- [ ] Nenhum `RuntimeException` genérico — só exceptions de domínio
- [ ] Entidades não retornadas directamente — sempre mapeadas para DTO
- [ ] Controller não tem lógica de negócio
- [ ] Service tem interface + Impl separados
- [ ] Respostas envolvidas em `ApiResponse<T>`
- [ ] IDs são UUID gerados pelo servidor
- [ ] Timestamps são `Instant` (UTC)
- [ ] Entidades mutáveis têm `@Version Long version`
- [ ] Migration Flyway criada (nunca `ddl-auto: create`)

Correr `/run-checks` para validar localmente.
