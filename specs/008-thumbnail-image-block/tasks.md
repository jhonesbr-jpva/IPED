---
description: "Task list for 008-thumbnail-image-block"
---

# Tasks: Miniatura como imagem do protocolo, não como base64 dentro de texto

**Input**: Design documents from `/specs/008-thumbnail-image-block/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: **incluídos**. O `plan.md` nomeia três arquivos de teste novos e um alterado, a
constituição exige `mvn test` passando onde há cobertura relevante (Fluxo de desenvolvimento),
e sete suítes existentes são a prova de que a mudança foi aditiva. Sem teste, FR-004 e FR-005
não são verificáveis — o que quebraria neles quebra em silêncio.

**Organization**: agrupadas por história de usuário. US2 e US3 **protegem** a mudança que US1
introduz, então não são independentes dela na ordem — mas são independentes uma da outra, e cada
uma é testável isoladamente.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivo diferente, sem dependência pendente)
- **[Story]**: US1, US2, US3
- Caminhos relativos à raiz do repositório

## Path Conventions

Módulo único: `iped-mcp/src/main/java/iped/mcp/…` e `iped-mcp/src/test/java/iped/mcp/…`.
Nenhum outro módulo é tocado.

## Ambiente — ler antes do primeiro comando

```powershell
$env:JAVA_HOME = "H:\java\LibericaJDK-11-Full"
```

Os dois defaults desta máquina estão errados (JDK 18, Corretto 25), o estado do shell não
persiste entre chamadas, e Java 11 aqui é restrição de **runtime**. Prefixe todo comando Maven.

---

## Phase 1: Setup

**Purpose**: fechar a lacuna que o plano deixou nos artefatos e montar a bancada. Sem T003, todo
teste de miniatura **pula**, e um skip não é um pass.

- [x] T001 Dobrar as resoluções D1, D2 e D4 de volta em `specs/008-thumbnail-image-block/spec.md`: FR-012 como "emitir sempre, sem negociação"; FR-013 como "fora de escopo, apenas a classe `thumbnail` emite imagem"; FR-003 com a ressalva de D4; FR-004 declarando-se distinto de FR-003; SC-002 com limiar de aprovação; e a linha de `Status` deixando de anunciar marcadores pendentes — **concluída em 2026-09-20**
- [x] T002 Marcar "No [NEEDS CLARIFICATION] markers remain" em `specs/008-thumbnail-image-block/checklists/requirements.md` e substituir a nota "Pendente — dois marcadores" pelo registro das resoluções e das correções de coerência — **concluída em 2026-09-20** (dependia de T001)
- [x] T003 Construir o caso de bancada com miniaturas reais seguindo `specs/008-thumbnail-image-block/quickstart.md` (Pré-requisitos): pasta com ~10 imagens de conteúdo conhecido, 1 PDF, 1 arquivo não visual e, se possível, 1 avatar de chat não-JPEG, processada **sem** `-profile` (o default traz `enableImageThumbs = true`; `fastmode` desliga as quatro chaves de miniatura e torna o caso inútil aqui)
- [x] T004 Registrar o verde de base antes de qualquer edição: `mvn -pl iped-mcp -am install -DskipTests` seguido de `mvn -pl iped-mcp test "-Diped.mcp.test.referenceCase=<caso de T003>" "-Diped.mcp.ipedRoot=<instalação IPED>"`, guardando a saída para comparação

**Checkpoint**: artefatos coerentes, bancada com miniaturas, linha de base conhecida.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: levar a classe de conteúdo até o montador de resultado. Esta fase **não muda
comportamento nenhum** — é encanamento puro, e é justamente por isso que é o lugar certo para
separá-la: se alguma suíte ficar vermelha aqui, o problema é o encanamento, não a feature.

**⚠️ CRITICAL**: nenhuma história começa antes desta fase fechar verde.

- [x] T005 Em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, dar a `renderResult` um segundo parâmetro com a classe de conteúdo do descritor (`tool.getContentClass()`, que pode ser `null`) e repassá-lo no único ponto de chamada, dentro de `callTool`, logo após `recordEnd(..., Outcome.OK, ...)`. O corpo do método continua montando exatamente um bloco de texto — **nenhuma mudança de saída nesta task**
- [x] T006 Rodar `mvn -pl iped-mcp test "-Diped.mcp.test.referenceCase=<caso de T003>" "-Diped.mcp.ipedRoot=<instalação IPED>"` e confirmar que a saída é **idêntica** à linha de base de T004. Qualquer diferença aqui é regressão de encanamento e precisa ser resolvida antes de seguir

**Checkpoint**: a classe de conteúdo chega ao montador e nada mudou de forma. Pode começar US1.

---

## Phase 3: User Story 1 — O perito pergunta o que há na imagem, e a resposta descreve a imagem (Priority: P1) 🎯 MVP

**Goal**: a miniatura chega ao agente como figura, com o tipo de mídia correto, sem transcrever
base64 no diálogo — e a orientação para de mandar exportar arquivo para olhar imagem.

**Independent Test**: num caso com miniaturas renderizadas, pedir a descrição de um item cujo
conteúdo visual seja conhecido de antemão e conferir contra a mesma imagem aberta na interface
do IPED. A partir daí a verificação visual de evidência existe.

**Referências**: [contracts/tool-result-image-block.md](contracts/tool-result-image-block.md)
seção 2; [data-model.md](data-model.md) E4, E5; [research.md](research.md) D3, D4, D5.

### Tests for User Story 1 ⚠️

> Escrever primeiro e confirmar que **falham** antes de implementar.

- [x] T007 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/contract/ImageBlockContractTest.java` cobrindo C1 a C7 do contrato: `content` com dois elementos; `content[0].type == "text"`; `content[1].type == "image"`; `content[1].data` byte a byte igual a `structuredContent.data`; `content[1].mimeType` igual a `structuredContent.media_type`; `isError == false`. Usar `McpSessionRule.raw(...)`, que já devolve o `result` inteiro
- [x] T008 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/unit/MediaTypeDetectionTest.java` provando que o tipo vem dos bytes e não de constante: bytes JPEG → `image/jpeg`, bytes PNG → `image/png`, bytes que não são imagem → **sem** `media_type`, **sem** bloco de imagem e **com** `media_type_note` (D3, E3)
- [x] T009 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java` com o caminho feliz ponta a ponta sobre o caso de referência: item com miniatura devolve bloco de imagem apresentável, e o base64 **não** aparece transcrito dentro de `content[0].text` (D4, SC-002)

### Implementation for User Story 1

- [x] T010 [US1] Em `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java`, método `thumbnail(...)`: trocar `result.put("media_type", "image/jpeg")` por detecção a partir dos próprios bytes via Apache Tika (`MimeTypes`/`TikaConfig`, já no classpath). Quando a detecção não produzir um tipo `image/*`, **não** gravar `media_type` e gravar `media_type_note` explicando que o tipo não pôde ser estabelecido e que por isso os bytes não são entregues como imagem. `available`, `encoding`, `bytes` e `data` seguem inalterados (FR-002, D3, E3)
- [x] T011 [US1] Criar `iped-mcp/src/main/java/iped/mcp/protocol/ImageBlock.java` com duas responsabilidades e nenhum estado: (a) decidir se o payload é emitível como imagem — R4.2 de `data-model.md`: classe `thumbnail`, `available == true`, `data` presente e não vazio, `media_type` presente casando com `image/*`; (b) montar o `ObjectNode` `{type, data, mimeType}` reaproveitando as **mesmas** referências do payload, sem recodificar (R4.1). Javadoc em inglês, dizendo por que o gatilho é a classe de conteúdo e não o nome da ferramenta
- [x] T012 [US1] Em `iped-mcp/src/main/java/iped/mcp/protocol/ImageBlock.java`, acrescentar a elisão do bloco de texto (D4, R5.1): produzir a cópia do payload em que **apenas** a chave `data` de nível superior é trocada pela nota fixa em inglês apontando para o bloco de imagem e para `structuredContent`. `available`, `media_type`, `bytes` e `encoding` permanecem (R5.2)
- [x] T013 [US1] Em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, `renderResult`: quando `ImageBlock` disser que o payload é emitível, serializar no bloco de texto a cópia elidida de T012 e acrescentar o bloco de imagem **depois** dele, em `content[1]`. `structuredContent` continua recebendo o payload original, intacto (R1.3, R5.3). Caso contrário, o caminho de hoje, sem desvio
- [x] T014 [P] [US1] Em `iped-mcp/src/main/java/iped/mcp/tools/ItemTools.java`, reescrever a `description` de `iped_item_thumbnail`: sai "base64-encoded", que passou a orientar mal, entra a descrição de que a miniatura chega como imagem quando o cliente a apresenta, mantendo que a ausência é declarada com o motivo. **Não tocar no `inputSchema`** — `ToolSchemaTest` e `TransportParityTest` dependem dele
- [x] T015 [P] [US1] Reescrever a seção **"Bytes and pictures: what you can actually look at"** de `iped-mcp/src/main/resources/skill/SKILL.md` (FR-010): remover a afirmação de que "o servidor emite um bloco de texto por chamada e nunca um bloco de imagem" e a recomendação de exportar o item para conseguir ver uma figura; descrever como a miniatura chega agora. Preservar intactas as duas regras que continuam valendo: `iped_item_content` não serve para olhar figura, e ausência declarada não é fato sobre a evidência. Não alterar nenhuma das frases que `SkillParityTest` verifica
- [x] T016 [US1] Rodar `mvn -pl iped-mcp test "-Diped.mcp.test.referenceCase=<caso de T003>" "-Diped.mcp.ipedRoot=<instalação IPED>"` e confirmar T007, T008, T009 verdes e nenhuma suíte existente editada

**Checkpoint**: a miniatura chega como figura. O MVP está de pé e vale por si — o Cenário 7 do
quickstart já pode ser rodado com modelo de visão.

---

## Phase 4: User Story 2 — O cliente que não apresenta imagens continua correto (Priority: P1)

**Goal**: provar que nada regrediu para quem não renderiza imagem, e que nenhuma outra
ferramenta adquiriu bloco de imagem por efeito colateral.

**Independent Test**: exercitar a mesma chamada por um cliente que ignore blocos de imagem e
verificar que a resposta continua completa e interpretável, sem erro de protocolo.

**Por que P1 e não P2**: uma regressão aqui é silenciosa e atinge instalação que não pediu nada.

**Referências**: [contracts/tool-result-image-block.md](contracts/tool-result-image-block.md)
seções 6 e 8; [data-model.md](data-model.md) R1.3, R2.1, R5.2.

### Tests for User Story 2 ⚠️

- [x] T017 [US2] Em `iped-mcp/src/test/java/iped/mcp/contract/ImageBlockContractTest.java`, acrescentar o teste de FR-005: chamar `iped_item_metadata`, `iped_item_text` e `iped_item_content` no mesmo item e afirmar que `content` tem **exatamente um** elemento em cada — nenhuma ferramenta fora da classe `thumbnail` mudou de forma (mesmo arquivo de T007, portanto depois dele)
- [x] T018 [US2] Em `iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java`, acrescentar o teste do cliente cego: ler **somente** `content[0].text`, parseá-lo como JSON e afirmar que `available`, `media_type`, `bytes` e `encoding` estão lá (FR-004, R5.2) — sem tocar em `content[1]`
- [x] T019 [US2] Em `iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java`, acrescentar o teste do consumidor estruturado: `structuredContent` traz `data` íntegro e decodificável para os bytes exatos da miniatura, e o conjunto de chaves é o mesmo de antes da feature, salvo `media_type` corrigido (FR-003, R1.3) — mesmo arquivo de T018, portanto em sequência

### Implementation for User Story 2

- [x] T020 [US2] Rodar T017 a T019 e registrar o resultado em `specs/008-thumbnail-image-block/validation-log.md`. **Critério de conclusão**: os três verdes, com o arquivo de produção que precisou mudar nomeado, ou a frase explícita "nenhuma correção foi necessária" acompanhada da saída do Maven que a sustenta. A história é de proteção — "nada a corrigir" é o resultado esperado, mas só vale se os testes rodaram
- [x] T021 [US2] Confirmar que as **sete** suítes protegidas passam **sem uma linha editada**: `contract/ToolSchemaTest`, `contract/TransportParityTest`, `unit/AvailabilityTest`, `unit/AuditChainTest`, `integration/EgressPolicyTest`, `integration/AuditDurabilityTest`, `integration/ReadOnlyInvariantTest`. As duas de auditoria são a única cobertura explícita do caminho `OK` de FR-007 ("a trilha continua registrando antes da execução, sem alteração de formato") — sem elas, esse requisito dependeria do `mvn test` genérico. Editar qualquer uma das sete para passar significa que a mudança deixou de ser aditiva: parar e rever, não ajustar o teste

**Checkpoint**: US1 e US2 de pé. Quem renderiza vê a figura; quem não renderiza não perdeu nada.

---

## Phase 5: User Story 3 — A ausência continua sendo declarada, e continua não sendo fato sobre a evidência (Priority: P2)

**Goal**: os três motivos de ausência seguem distinguíveis, nenhum deles emite bloco de imagem,
e a recusa por teto continua trazendo tamanho real e teto em vigor.

**Independent Test**: pedir a miniatura de um item sem conteúdo visual, de um item cuja
miniatura ultrapasse o teto, e de um item comum, e verificar que os três se distinguem pela
resposta.

**Referências**: [contracts/tool-result-image-block.md](contracts/tool-result-image-block.md)
seções 3, 4 e 5; [data-model.md](data-model.md) R3.3, R4.3.

### Tests for User Story 3 ⚠️

- [x] T022 [US3] Em `iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java`, acrescentar as três ausências (FR-009, SC-006): item não visual, item sem renderização, item acima do teto. Em cada um: `content` com **um** elemento, `available == false`, `reason` não vazia e distinta das outras duas, `data` ausente
- [x] T023 [US3] No mesmo arquivo, acrescentar o teste do teto (FR-008): baixar `maxThumbnailBytes` na config do `McpSessionRule` e afirmar que a recusa cita o **tamanho real**, o **teto em vigor** e a chave de configuração que o governa — e que **nenhum** bloco de imagem é emitido, nem com dado truncado (R4.3)
- [x] T024 [US3] No mesmo arquivo, acrescentar o teste da política de egresso à frente da forma (FR-006): com `egressPolicyActive = true` e `egressAllowedClasses` sem `thumbnail`, a chamada volta como erro JSON-RPC `BLOCKED_BY_POLICY`, **sem** `content` e **sem** `structuredContent`; e a tentativa aparece na trilha como `DENIED` com a regra anexada (FR-007). Afirmar também R1.5: a resposta de erro **não** carrega bloco de imagem, e a sinalização de falha vive no envelope JSON-RPC, não em `content`

### Implementation for User Story 3

- [x] T025 [US3] Em `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java`, garantir que os três ramos de ausência produzem `reason` mutuamente distinguível. **Critério de conclusão**: T022 verde com as três `reason` comparadas duas a duas e nenhuma igual a outra. Ajustar texto apenas se houver colisão; não inventar motivo novo — os três são os de hoje
- [x] T026 [US3] Em `iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java`, acrescentar o teste do tipo indeterminado (E3, contrato seção 4). **Critério de conclusão**: um teste que afirme, sobre bytes que não são imagem, `available == true`, `data` presente, `media_type_note` presente, `media_type` **ausente**, `content` com **um** elemento e o base64 **não** elidido no bloco de texto — não há bloco de imagem para onde apontar

**Checkpoint**: as três histórias de pé e independentemente verificáveis.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T027 [P] Estender `iped-mcp/src/test/java/iped/mcp/contract/TransportParityTest.java` com um `tools/call` de miniatura sobre `STDIO` e sobre `SOCKET`, comparando o `content[]` dos dois (FR-011, D6). Hoje a suíte só compara `tools/list`, que não exercita resultado — manter o teste existente intacto e acrescentar um segundo
- [x] T028 [P] Atualizar `iped-mcp/CLAUDE.md`: a forma da resposta passou a ser contrato do módulo. Registrar na seção 5 (invariantes) que só a classe `thumbnail` emite bloco de imagem e que `structuredContent` nunca é afetado pela forma, e na seção 3 (decisões que condicionam o desenho) o porquê de D1 (sem negociação) e D4 (elisão confinada ao bloco de texto)
- [x] T029 [P] Registrar em `iped-mcp/CLAUDE.md`, seção 7 (Testes), que `fastmode` desliga as quatro chaves de miniatura e que um caso processado com ele faz toda suíte de miniatura pular em silêncio — mesma disciplina já documentada ali para hash e preview
- [x] T030 Rodar `mvn -pl iped-mcp -am install "-Diped.mcp.test.referenceCase=<caso de T003>" "-Diped.mcp.ipedRoot=<instalação IPED>"` — a exigência da constituição antes de qualquer commit que altere código
- [ ] T031 **PARCIAL — 8 de 9 feitos em 2026-09-20; falta o Cenário 7.** Os cenários 1 a 6, 8 e 9 estão executados e registrados em `validation-log.md`. O **Cenário 7** exige um harness com modelo de visão conectado ao servidor e **não foi executado**: sem ele, SC-001, SC-003 e SC-005 permanecem não verificados, ainda que o servidor comprovadamente emita o bloco de imagem correto (Cenário 2). Executar os nove cenários de `specs/008-thumbnail-image-block/quickstart.md` e registrar o resultado de cada um em `specs/008-thumbnail-image-block/validation-log.md` — uma linha por cenário, com veredito e evidência. Inclui o Cenário 7, que precisa de harness com modelo de visão e é o único que mede a razão de existir da feature (SC-001, SC-003, SC-005)
- [x] T032 Medir SC-002 no Cenário 8 e anotar em `specs/008-thumbnail-image-block/validation-log.md`: mesmo item, mesma pergunta, tokens consumidos pela chamada antes e depois, sobre a maior miniatura da bancada (item 9, `photo-11-large-noise-panel.jpg`, 16 141 B → 21 524 caracteres base64). **Critério de aprovação**: redução de **pelo menos 90%**. Abaixo disso, a decisão D4 não está surtindo o efeito que a motiva — investigar antes de fechar, porque é o critério que D4 existe para satisfazer

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 e T002 já concluídas (coerência de artefatos). Resta T003 → T004, e **T003 é o gargalo real** — sem o caso de bancada com miniaturas, tudo o mais pula
- **Foundational (Phase 2)**: depende de T004 (linha de base). **Bloqueia todas as histórias**
- **US1 (Phase 3)**: depende da Phase 2. É o MVP
- **US2 (Phase 4)**: depende de US1 — protege a mudança que US1 introduz
- **US3 (Phase 5)**: depende de US1 pelo mesmo motivo. **Independente de US2**
- **Polish (Phase 6)**: depende das três histórias

### User Story Dependencies

Esta feature foge do padrão de histórias mutuamente independentes, e vale dizer por quê: US2 e
US3 não acrescentam capacidade — elas **verificam que a capacidade de US1 não quebrou nada**.
Uma regressão de compatibilidade só é observável depois que a mudança existe. US2 e US3 são,
entre si, completamente independentes e podem correr em paralelo.

### Within Each User Story

- Testes primeiro, falhando, antes da implementação
- T010 (detecção de tipo) antes de T011 (montagem do bloco): o bloco depende do `media_type`
- T011 antes de T012 e T013: mesmo arquivo em T011/T012, e T013 consome os dois
- T014 e T015 são independentes de T010 a T013 e podem correr a qualquer momento da fase

### Parallel Opportunities

O marcador `[P]` aqui significa **arquivo distinto e nenhuma dependência pendente** — só isso. Uma
task que escreve o mesmo arquivo que outra não leva `[P]`, ainda que nada mais a prenda.

- **Phase 1**: T001 e T002 concluídas; T003 e T004 são sequenciais entre si (T004 mede a linha de base do caso que T003 constrói)
- **Phase 3**: T007, T008 e T009 em paralelo — três arquivos distintos, é a única janela larga da feature. T014 e T015 também em paralelo com tudo, porque tocam arquivos que mais ninguém toca
- **Phase 4**: **nenhum paralelismo**. T017 escreve o arquivo que T007 criou; T018 e T019 escrevem o arquivo que T009 criou, e um ao do outro
- **Phase 5**: **nenhum paralelismo interno**. T022, T023, T024 e T026 escrevem todos `ThumbnailImageBlockTest.java`. São paralelos a qualquer task de US2
- **Phase 6**: T027, T028 e T029 em paralelo (três arquivos distintos)
- **US2 e US3 inteiras** em paralelo, depois que US1 fecha — é o único paralelismo de verdade entre histórias

---

## Parallel Example: User Story 1

```bash
# Os três testes de US1, juntos — arquivos distintos, sem dependência entre si:
Task: "Criar iped-mcp/src/test/java/iped/mcp/contract/ImageBlockContractTest.java (C1 a C7)"
Task: "Criar iped-mcp/src/test/java/iped/mcp/unit/MediaTypeDetectionTest.java (D3)"
Task: "Criar iped-mcp/src/test/java/iped/mcp/integration/ThumbnailImageBlockTest.java (caminho feliz)"

# Enquanto a implementação corre, a orientação e a descrição não colidem com ela:
Task: "Reescrever a description de iped_item_thumbnail em iped-mcp/.../tools/ItemTools.java"
Task: "Reescrever a seção Bytes and pictures em iped-mcp/src/main/resources/skill/SKILL.md"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1: Setup — e **não pule T003**, ou tudo passa sem provar nada
2. Phase 2: Foundational — encanamento, saída idêntica à linha de base
3. Phase 3: US1
4. **PARAR E VALIDAR**: Cenários 2, 3 e 7 do quickstart
5. Já entrega valor: a verificação visual de evidência passa a existir

### Incremental Delivery

1. Setup + Foundational → encanamento pronto, comportamento intacto
2. US1 → a figura chega → **MVP**
3. US2 → compatibilidade provada → seguro para instalação que não renderiza imagem
4. US3 → ausências e recusas protegidas → contrato inteiro preservado
5. Polish → paridade de transporte, documentação de módulo, medição

### Parallel Team Strategy

Com duas pessoas, depois de US1 fechar: uma toca US2, outra toca US3. Não se cruzam em arquivo
nenhum de produção, porque nenhuma das duas escreve produção — escrevem teste, e só corrigem o
que os testes apontarem.

---

## Notes

- `McpSessionRule.raw(tool, ...)` já devolve o `result` inteiro, `content[]` incluso. Nenhum
  auxiliar de teste novo é necessário — `call(...)` devolve só `structuredContent`, que é o que
  as suítes existentes usam e o motivo de elas não enxergarem a mudança de forma
- A cópia estruturada é a fronteira de compatibilidade desta feature. Qualquer task que a altere
  além do `media_type` corrigido está fora do plano
- Comitar por task ou por grupo lógico. Cada checkpoint é um ponto seguro para parar
- Evitar: editar as sete suítes protegidas; tocar `inputSchema`; criar chave de configuração
  nova; presumir tipo de mídia quando a detecção falhar
