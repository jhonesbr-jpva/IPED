# Quickstart — validar a miniatura como bloco de imagem

**Feature**: `008-thumbnail-image-block` | **Date**: 2026-09-18

Como provar, ponta a ponta, que a feature faz o que o spec promete. Os detalhes da forma da
resposta estão em [contracts/tool-result-image-block.md](contracts/tool-result-image-block.md);
as regras dos payloads, em [data-model.md](data-model.md). Aqui está o que rodar e o que esperar.

---

## Pré-requisitos

**JDK**: `H:\java\LibericaJDK-11-Full` (OpenJDK 11.0.31 LTS, JavaFX). Os dois defaults desta
máquina estão errados; defina `JAVA_HOME` em **cada** comando — o estado do shell não persiste
entre chamadas.

```powershell
$env:JAVA_HOME = "H:\java\LibericaJDK-11-Full"
```

**Um caso com miniaturas de verdade.** Este é o ponto que mais atrapalha, e vale ler antes de
gastar tempo:

> O caso de bancada `H:\iped-cases\rockpi4-smoke` **não serve** para esta feature. Ele foi
> processado em `fastmode`, e o perfil desliga as quatro chaves que produzem miniatura —
> `enableImageThumbs`, `enableVideoThumbs`, `enableDocThumbs`, `enableImageSimilarity`
> (`iped-app/resources/config/profiles/fastmode/IPEDConfig.txt`). Todo item ali cai no ramo
> `available: false`, e uma suíte que só exercita esse ramo passa sem provar nada.

O caminho barato é processar uma pasta pequena de imagens de conteúdo conhecido com o perfil
padrão, que traz `enableImageThumbs = true`:

```powershell
# ~10 imagens cujo conteúdo você consegue descrever de cor, mais 1 PDF e 1 arquivo não visual
C:\iped\iped-mcp\iped-4.3.1\jre\bin\java.exe -Diped-locale=en `
  -jar C:\iped\iped-mcp\iped-4.3.1\iped.jar `
  -d <pasta-com-imagens> -o H:\iped-cases\thumbs-bench --nogui --nologfile
```

Sem `-profile`: o default gera miniaturas. Não passe `-Xms`/`-Xmx` — o `Bootstrap` lança
exceção quando a JVM que o lançou já os carrega. A saída do motor é **CP1252** nesta
plataforma, e `ModuleNotFoundError: numpy` em `stderr` é tarefa Python opcional ausente, não
falha.

Inclua ao menos **um avatar de chat** (WhatsApp, Telegram, Discord, vCard) se quiser exercitar
D3 de verdade — é por ali que entram miniaturas que não são JPEG.

---

## Cenário 1 — a suíte automatizada

```powershell
$env:JAVA_HOME = "H:\java\LibericaJDK-11-Full"
mvn -pl iped-mcp -am install -DskipTests
mvn -pl iped-mcp test "-Diped.mcp.referenceCase=H:\iped-cases\thumbs-bench"
```

**Esperado**: verde, e **nenhum skip** nas suítes de miniatura. Um skip aqui não é um pass — é
o caso de referência ausente, e a mensagem do `Assume` diz exatamente isso.

Suítes que precisam continuar passando **sem edição**, porque são a prova de que a mudança foi
aditiva:

| Suíte | O que ela protege |
|---|---|
| `contract/ToolSchemaTest` | a superfície de `tools/list` não mudou de schema |
| `contract/TransportParityTest` | STDIO e SOCKET expõem a mesma coisa (FR-011) |
| `unit/AvailabilityTest` | ausência declarada, sem `data` (FR-009, SC-006) |
| `unit/AuditChainTest` | a trilha encadeada não mudou de formato (FR-007) |
| `integration/EgressPolicyTest` | a política continua barrando por classe (FR-006) |
| `integration/AuditDurabilityTest` | o registro write-ahead continua antes da execução (FR-007) |
| `integration/ReadOnlyInvariantTest` | nada foi escrito na evidência (Princípio I) |

Se alguma delas precisar ser editada para passar, a mudança deixou de ser aditiva — pare e
reveja, não ajuste o teste.

---

## Cenário 2 — a resposta na mão (User Story 1 e 2, FR-001 a FR-005)

Sem harness nenhum, falando JSON-RPC direto com o servidor por stdio. É o jeito mais rápido de
ver o `content[]` cru.

1. Suba o servidor apontando para a instalação e abra o caso.
2. Chame `iped_item_thumbnail` num item que você sabe ter miniatura.
3. Olhe o `result`.

**O que deve estar lá** (contrato, seção 2):

- `content` com **dois** elementos.
- `content[0].type == "text"` — a posição 0 nunca muda de tipo.
- `content[1].type == "image"`, com `data` e `mimeType`.
- `content[1].data` **idêntico** a `structuredContent.data`, byte a byte.
- `content[1].mimeType` **idêntico** a `structuredContent.media_type`.
- no bloco de texto: `available`, `media_type`, `bytes` e `encoding` presentes; no lugar do
  base64, a nota apontando para o bloco de imagem.
- `isError == false`.

**O que prova FR-005**: chame `iped_item_content` e `iped_item_metadata` no mesmo item.
`content` tem **um** elemento nos dois casos. Nenhuma outra ferramenta ganhou imagem.

**O que prova FR-004**: leia apenas `content[0].text`. A miniatura existe, e você sabe o tipo e
o tamanho, sem ter interpretado bloco de imagem nenhum.

---

## Cenário 3 — o tipo é detectado, não presumido (FR-002, D3)

Encontre um item cuja miniatura **não** seja JPEG — um avatar de contato de chat é a aposta
certa, porque os bytes vêm direto do banco da fonte via `ExtraProperties.THUMBNAIL_BASE64`.

```
iped_search  → q: "categoria de contatos ou mensagens"
iped_item_thumbnail → no item escolhido
```

**Esperado**: `media_type` é o tipo real (`image/png`, `image/gif`), e `content[1].mimeType`
traz o mesmo. **Antes desta feature, esse campo dizia `image/jpeg` para todo item** — a
diferença é justamente a correção.

**Caso do tipo indeterminado** (contrato, seção 4): se aparecer um item com bytes que o Tika
não classifica como `image/*`, o resultado sai `available: true`, com `data`, com
`media_type_note`, **sem** `media_type` e **sem** bloco de imagem. Não é erro; é a recusa de
inventar um tipo.

---

## Cenário 4 — as três ausências continuam distinguíveis (User Story 3, FR-008, FR-009, SC-006)

Três chamadas, três respostas diferentes, **nenhuma** com bloco de imagem:

| Item | `reason` deve citar |
|---|---|
| um `.txt` ou `.log` | que não há o que renderizar; aponta `content_type` |
| um item cuja miniatura passe do teto | o **tamanho real**, o **teto**, e `maxThumbnailBytes` em `conf/McpServerConfig.txt` |
| um item visual não renderizado no processamento | que a miniatura não foi produzida |

Para forçar o caso do teto sem procurar item grande: baixe `maxThumbnailBytes` para `1024` em
`conf/McpServerConfig.txt`, reinicie o servidor e repita no item do Cenário 2.

**Esperado em todos**: `content` com **um** elemento, `available: false`, `data` ausente.

---

## Cenário 5 — a política de egresso continua à frente da forma (FR-006)

Em `conf/McpServerConfig.txt`:

```properties
egressPolicyActive = true
egressAllowedClasses = metadata, text
```

Reinicie e repita o Cenário 2.

**Esperado**: erro JSON-RPC com `BLOCKED_BY_POLICY`, `rule: "allowedClasses"`,
`contentClass: "thumbnail"`. **Sem `content`, sem `structuredContent`, sem bloco de imagem** — a
recusa acontece no dispatcher, antes de qualquer bloco ser montado. A forma nova da resposta não
é rota alternativa por onde conteúdo saia.

Confirme na trilha: a tentativa aparece como `DENIED`, com a regra anexada (FR-007, FR-041).

---

## Cenário 6 — paridade entre transportes (FR-011, D6)

Rode o Cenário 2 duas vezes, uma sobre `STDIO` e outra sobre `SOCKET`, no mesmo item do mesmo
caso.

**Esperado**: o `content[]` sai idêntico nos dois. Nenhum transporte ganha comportamento
próprio — o bloco nasce acima dos dois.

---

## Cenário 7 — o agente descreve o que está na imagem (SC-001, SC-003, SC-005)

Este é o único cenário que precisa de um harness com modelo de visão. É também o único que
mede a razão de existir da feature.

1. Carregue a skill `iped-forensics` e abra o caso de bancada.
2. Peça: *"descreva o que há nos itens 1 a 10"*.
3. Confira cada descrição abrindo o mesmo item na interface do IPED.

**Esperado**:

- **SC-001**: ao menos 9 de 10 descrições corretas.
- **SC-003**: em nenhum momento o agente chamou `iped_export_item` para conseguir ver. Confira
  na trilha de auditoria, que registra toda chamada.
- **SC-005**: nenhuma afirmação sobre conteúdo visual sem a chamada de miniatura correspondente
  na trilha. A trilha é o que torna isso conferível — e essa conferibilidade é a entrega.

**Se o agente ainda não vir a figura**, o problema está no harness, não no servidor: confirme
primeiro, pelo Cenário 2, que o `content[1]` sai correto. Um harness que descarta blocos de
imagem é propriedade dele (Assumptions do spec).

---

## Cenário 8 — o custo de contexto caiu (SC-002)

Mesmo item, mesma pergunta, antes e depois da mudança, sobre uma miniatura de **100 KB ou
maior**. Compare os tokens consumidos pela chamada.

**Esperado**: a cadeia base64 **não** aparece mais transcrita no diálogo. O custo passa a ser o
de uma imagem, mais o payload curto do bloco de texto.

**Critério de aprovação (SC-002)**: redução de **pelo menos 90%** nos tokens da chamada. Anote o
número medido em `validation-log.md` — "caiu bastante" não é medição.

> Este cenário é o que a decisão **D4** existe para satisfazer, e é onde ela se verifica. Abaixo
> do limiar, a elisão não está surtindo efeito e o problema é de implementação, não de critério.

---

## Cenário 9 — a orientação não recomenda mais o desvio (FR-010)

```powershell
mvn -pl iped-mcp test -Dtest=SkillParityTest
```

Depois, leia `iped-mcp/src/main/resources/skill/SKILL.md`, seção **"Bytes and pictures"**.

**Esperado**: a seção não diz mais que "o servidor emite um bloco de texto por chamada e nunca
um bloco de imagem", nem manda exportar o item para olhar uma figura. Diz como a miniatura
chega, e mantém intactas as duas regras que continuam valendo: `iped_item_content` não é para
olhar figura, e ausência declarada não é fato sobre a evidência.

`SkillParityTest` verifica que os wrappers dos três harnesses continuam byte a byte idênticos
ao canônico — o wrapper é gerado em `prepare-package`, então rode `mvn -pl iped-mcp package`
antes se quiser essa verificação valendo em vez de pulando.

---

## Checklist de encerramento

- [ ] `mvn -pl iped-mcp -am install` passa com o JDK 11 Liberica
- [ ] nenhuma das sete suítes protegidas precisou ser editada
- [ ] Cenários 2 a 6 conferidos na resposta crua
- [ ] Cenário 7 rodado com modelo de visão, resultado registrado
- [ ] Cenário 8 medido e o número anotado, com a redução ≥ 90% confirmada
- [ ] `validation-log.md` preenchido — um veredito por cenário, com evidência
- [ ] `iped-mcp/CLAUDE.md` atualizado (a forma da resposta é contrato do módulo)
