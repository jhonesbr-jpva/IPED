# Quickstart — validar a busca semântica

**Feature**: [spec.md](spec.md) | **Plano**: [plan.md](plan.md)

Guia de validação ponta a ponta. Cada cenário diz o que exercitar, como e o que precisa
acontecer, e aponta o critério da spec que ele fecha. Detalhes de campos e formatos ficam nos
[contracts/](contracts/) e no [data-model.md](data-model.md), e não se repetem aqui.

---

## Pré-requisitos

| O quê | Para quê |
|---|---|
| JDK de build: `H:\java\LibericaJDK-11-Full` (`JAVA_HOME`) | Java 11 é restrição de runtime |
| Release montado: `mvn clean install` → `target/release/iped-4.3.1/` (`<release>`) | Todos os cenários de processamento e interface |
| **Conjunto de avaliação** (research D18) em `<eval-src>`, com gabarito versionado em `<eval-src>/answers/` | SC-001 a SC-003, SC-010, SC-012 a SC-015 |
| **Caso de referência processado antes da capacidade** (a bancada da 007/008) em `<old-case>` | SC-007, FR-027 |
| Modelo visual (pacote separado) extraído em `<release>/models/semantic/visual/` | Cenários 7 e 8 |
| Estação de referência (48 núcleos, sem uso de GPU) | Medições de tempo |
| Python 3 com as dependências de `iped-semantic/tools/semantic-server/requirements.txt` | Cenário 11 (só ele) |

O gabarito precisa ter sido **fechado antes** da primeira medição. Pergunta cuja busca por palavra
encontra os pertinentes é descartada do conjunto de texto (condição de SC-001).

---

## Cenário 0 — portão de dependências (research D2)

**Antes de qualquer código que dependa do ONNX Runtime.**

```bash
# versão de class file de todas as classes dos dois jars: nenhuma acima de 55 (Java 11)
for j in onnxruntime-*.jar onnxruntime-extensions-*.jar; do
  unzip -o -q "$j" -d /tmp/ortcheck && find /tmp/ortcheck -name '*.class' -exec file {} + | grep -v 'version 5[2-5]' ; done
```

- Nenhuma saída no `grep` (nenhuma classe acima do Java 11).
- Nativos de Windows x64 e Linux x64 presentes nos jars.
- Com a rede desligada, a sessão carrega e responde a uma frase.

Reprovou → plano B de D2, antes de seguir.

---

## Cenário 1 — suítes automatizadas

```bash
mvn -pl iped-semantic -am install                                     # unitários
mvn -pl iped-semantic test -Diped.semantic.test.referenceCase=<caso>  # + integração
mvn -pl iped-semantic test -Diped.semantic.test.evalCase=<eval-case>  # + harness de avaliação
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.referenceCase=<eval-case>                         # ferramentas semânticas no MCP
```

As suítes com parâmetro **pulam** sem ele, como as do `iped-mcp`. O resumo tem de dizer quantas
pularam: uma suíte que passa sem ter rodado não fecha nenhum critério.

---

## Cenário 2 — desligado é igual a antes (FR-020, FR-027, SC-007, SC-008)

1. Processe `<eval-src>` duas vezes com o perfil forense: com o release **anterior** à feature e
   com o novo, as três chaves desligadas. O tempo varia no máximo **5%** (SC-008).
2. Durante o processamento com a capacidade desligada, observe as conexões do processo
   (`Get-NetTCPConnection -OwningProcess <pid>` em amostragem, ou `pktmon`). Nenhuma conexão
   atribuível à capacidade.
3. Abra `<old-case>` no release novo. Rode o **roteiro fixo** de buscas por palavra (versionado
   com o gabarito) e compare com a saída do release anterior: **idêntica** (SC-007). O SHA-256 da
   evidência de origem, antes e depois: igual.
4. No mesmo caso, o painel e `iped_semantic_status` declaram a busca indisponível, com
   "reprocesse com a capacidade ligada" (FR-027).

---

## Cenário 3 — processar com texto e ler o registro (FR-021, SC-010)

```text
IPEDConfig.txt: enableSemanticText = true
```

1. Processe `<eval-src>` no perfil forense. Anote o tempo total e compare com o do Cenário 2:
   **≤ 2×** (SC-010, meta de partida — se reprovar, a revisão volta para a spec).
2. Abra `<eval-case>/iped/semantic/build-record.json`:
   - `state: COMPLETE`;
   - identidade do modelo presente;
   - `counts` por motivo presente, com cobertos + não cobertos = itens considerados;
   - nenhuma credencial nem conteúdo de item no arquivo.
3. O log do processamento traz as mesmas contagens e o tempo da preparação semântica.

---

## Cenário 4 — qualidade em texto (SC-001, SC-002, História 1)

Pelo harness (Cenário 1) ou manualmente pelo painel, para cada pergunta do gabarito:

- SC-001: ao menos um pertinente entre os 10 primeiros em **≥ 80%** das perguntas em PT
  (≥ 30 perguntas).
- SC-002: nas perguntas com pertinente em outro idioma (≥ 10), **≥ 70%**.
- Documento longo com um único parágrafo pertinente: o trecho exibido é o parágrafo (cenário 2 da
  História 1).
- Abrir uma entrada destaca o trecho no visualizador de texto (cenário 4 da História 1). Se o
  visualizador não destacar frase, confirme o fallback de rolar até `charStart`.

---

## Cenário 5 — sequências de conversa (FR-005, FR-009, SC-006)

No chat do conjunto de avaliação com "quanto?" / "5 mil" / "fechado, amanhã na portaria":

1. Pergunte "combinação de preço e entrega". A sequência aparece entre os primeiros, com as três
   mensagens apontadas, e cada uma abre e é marcável (cenário 5 da História 1).
2. Nenhuma mensagem aparece em duas entradas da mesma ordenação.
3. Integração: para cada linha exibida, o texto é **idêntico** ao `Message:Body` da mensagem
   apontada (SC-006).
4. Filtre a tabela por um período que corte a conversa ao meio e pergunte de novo. A sequência
   entra, e as mensagens de fora vêm marcadas "fora do recorte" e não vão para o marcador.
5. O item do chat inteiro (a conversa renderizada) **não** aparece como documento. A cobertura o
   conta como "coberto pelas mensagens".

---

## Cenário 6 — o resultado diz o que é (História 2, SC-005, SC-006)

1. Faça a mesma pergunta, feche o caso, reabra, repita: **mesma lista** (SC-005). Repita com
   recorte grande (método `approximate`) e pequeno (`exact`).
2. Inspecione painel e resposta do MCP. Estão presentes modo, profundidade, teto, método,
   cobertura com motivos e modelo. Estão ausentes total, "nenhum resultado" e porcentagem de confiança (SC-006,
   FR-010).
3. "Mais resultados" até o teto: os primeiros 100 continuam na mesma ordem. No teto, o botão
   desabilita, e a dica nomeia `maxDepth` sem sugerir que além dele não haja nada.
4. Pergunte sobre um assunto ausente do conjunto: a lista aparece, e nada nela se lê como
   ausência.
5. Com a tabela vazia, pergunte: "recorte vazio", e não "nada encontrado".
6. **Detalhes da construção** (FR-014): no painel, para cada modalidade, aparecem modelo e versão,
   fonte e destino da preparação, data, parâmetros, estado e contagens por motivo, e batem com
   `build-record.json`. `iped_semantic_status` traz os mesmos dados (`built_at`, `params`,
   `state`, `counts`).
7. **Vários casos abertos juntos** (FR-017): abra juntos dois casos preparados com o mesmo modelo e
   um terceiro processado sem a capacidade (ou com outro modelo). A ordenação traz entradas dos
   dois compatíveis intercaladas pela proximidade. O terceiro aparece em "casos participantes"
   como excluído, com o motivo (`CASES_EXCLUDED` no MCP), e a cobertura soma só os recortes dos
   incluídos.

---

## Cenário 7 — recorte e "mais como este" (Histórias 3 e 4, SC-003)

1. Filtre por categoria e período e pergunte. Nenhum item de fora aparece, e a cobertura se
   refere ao recorte.
2. Menu de contexto de um item pertinente → "Mais como este". O item não está na lista. SC-003:
   outro pertinente ao mesmo assunto entre os 10 primeiros em **≥ 70%** das tentativas.
3. Selecione um trecho no visualizador de texto → "Mais como este trecho": a referência é o
   trecho.
4. Num item sem texto coberto, a ação vem desabilitada, com o motivo.

---

## Cenário 7b — modo híbrido (História 7, FR-046, FR-047, SC-018)

1. Rode o gabarito inteiro nos dois modos (harness ou painel).
   - Nas ≥ 15 perguntas que misturam conceito e termo específico, o híbrido coloca pertinente
     entre os 10 primeiros em **≥ 80%** (SC-018).
   - Nas perguntas de SC-001, o híbrido não fica mais de **5 pontos** abaixo do semântico.
   - O resultado decide o `defaultTextMode` de fábrica (research D20); registre a decisão.
2. "pagamento combinado com o Baiano": as sequências com o apelido **e** o assunto vêm antes das
   que só tratam do assunto. Cada entrada mostra "por significado: nº X · por palavra: nº Y".
3. Pergunta sem nenhuma palavra que exista no caso: `lexical_matches: 0`, e a ordenação é igual à
   do modo semântico, declarada assim.
4. Com um recorte por período, nenhuma unidade de fora aparece, nem pela parte por palavra.
5. Rode o roteiro fixo de buscas por palavra do Cenário 2 no caso com texto preparado: **idêntico**
   ao do release anterior. O BM25 do índice semântico não alcança a busca existente (FR-047).

---

## Cenário 8 — imagens e vídeos (História 6, SC-012 a SC-015)

1. Processe com `enableSemanticImages = true` e `enableSemanticVideos = false`. Tempo **≤ 3×** o
   do Cenário 2 (SC-013).
2. SC-012: ≥ 30 descrições em PT, pertinente entre as 10 primeiras em **≥ 70%**. Registre à
   parte o acerto das ≥ 5 descrições em inglês (FR-036): a pergunta em inglês tem de funcionar
   sobre o mesmo modelo, sem tradução.
3. A ordenação visual traz só imagens, e a cobertura declara os vídeos como não cobertos, com o
   motivo (cenário 7 da História 6).
4. Reprocesse com `enableSemanticVideos = true`. O registro separa o tempo de imagens e de vídeos
   (SC-015). SC-014: ≥ 15 descrições, pertinente entre os 10 primeiros em **≥ 70%**. Cada vídeo
   aparece uma vez, com o quadro e "≈ mm:ss".
5. Reprocesse com vídeos desligados: o tempo volta ao do passo 1, ±5% (SC-015). Desligue imagens:
   o tempo volta ao do Cenário 3, ±5% (SC-013).
6. Pergunta dirigida a "Ambas": duas abas, nenhuma lista mesclada (FR-034).
7. Com o desfoque da galeria ligado, miniaturas e quadros aparecem desfocados no painel (FR-037).
8. Num perfil sem miniatura de vídeo, com vídeos ligados: vídeos não cobertos com motivo, e o
   conflito registrado em `build-record.json`.

---

## Cenário 9 — escala e revocação (SC-004, research D5)

1. **Revocação**: no conjunto de avaliação, para cada pergunta, compare os 100 primeiros da
   busca aproximada com os da exata (forçando `exactSearchMaxItems` acima do tamanho do caso).
   Registre a revocação. Se o aproximado perder pertinentes que o exato traz, ajuste
   `oversampling` ou o `beamWidth` do codec e registre.
2. **Latência**: gere, pelo harness, um índice semântico sintético com **1 M itens** cobertos (a
   latência depende do tamanho do índice, não do conteúdo). Pergunta sem recorte **≤ 5 s**, e
   recorte de 10 mil itens **≤ 2 s** (SC-004), com modelo local, frio e quente. Registre os dois.

---

## Cenário 10 — o agente (História 5, SC-011)

1. Para 10 perguntas do gabarito, com o mesmo recorte, compare a ordenação do painel com a de
   `iped_semantic_search`: **idênticas** (SC-011), nas duas modalidades.
2. Com a política de egresso sem a classe `text`, a ordenação vem com `excerpt_withheld` em cada
   entrada, e nenhum trecho aparece em campo algum.
3. Toda resposta traz `ordering_notice`. Entradas visuais não trazem imagem, e
   `iped_item_thumbnail` sobre elas devolve o bloco de imagem da 008.
3b. **Teto na paginação** (FR-007): pagine com `next_cursor` até o fim. A última página sai sem
   `next_cursor` e com `ceiling_reached: true`, e o aviso diz que o teto é limite de
   profundidade, não de relevância. Não existe argumento de profundidade, e nenhuma chamada é
   recusada por profundidade.
4. A trilha de auditoria registra cada chamada, sem campo novo.
5. `iped_semantic_status` num caso antigo: indisponível, com o remédio.
6. **Contexto para RAG** (FR-048): para um trecho do meio de um documento longo,
   `iped_semantic_context` com `before=2, after=2` devolve texto **idêntico** ao intervalo
   `[char_start, char_end)` do item, sem repetir a sobreposição. Em sequência, devolve as mensagens
   vizinhas. Com a classe `text` fora da política de egresso, a chamada é recusada.
7. **RAG ponta a ponta**: num harness com LLM conectado ao MCP, peça a resposta a três perguntas do
   gabarito. Toda afirmação da resposta cita `item_id` e trecho, e cada citação confere com o
   caso, lendo o intervalo. Nenhuma afirmação de ausência se apoia só em busca semântica (FR-040).

---

## Cenário 11 — modelo remoto (FR-024, FR-042 a FR-045, SC-016, SC-017)

1. Suba o servidor de referência (`iped-semantic/tools/semantic-server/`) com o artefato de
   texto e credencial em arquivo. Configure `textModelSource = remote`, `iped-embed/1`, o
   endpoint e `textRemoteCredentialFile`.
2. Processe. Observando o tráfego, **todas** as conexões da capacidade vão ao destino declarado
   (SC-016). O registro traz o destino da preparação, sem credencial. Cada resultado de pergunta traz
   `sent_to`.
3. Na interface, a faixa de destino aparece, e a primeira pergunta pede confirmação. Recusar não
   envia nada.
4. Derrube o servidor e pergunte: indisponível, destino nomeado, e nenhuma resposta vinda de outro
   modelo.
5. Suba o servidor com **outro** artefato sob o mesmo `id`: a impressão digital diverge e a
   pergunta é recusada como `MODEL_INCOMPATIBLE`.
5b. **Outra fonte, mesmo modelo** (emenda A4): com o caso preparado pelo servidor de referência,
   desligue a rede da estação e configure `textModelSource = local` com o **mesmo** artefato. A
   busca funciona, `iped_semantic_status` mostra `source: local` e `prepared_with: remote`, e nada
   sai da estação. Troque o artefato local por outro modelo: recusa `MODEL_INCOMPATIBLE`.
6. **Falha rápida** (SC-017): ligue `enableSemanticImages` sem o modelo visual instalado e sem
   remoto. O processamento recusa começar, nomeando o que falta, **antes** de ler a evidência. A
   evidência não é aberta: confira no log e pelo `Get-Process`/handles.
7. `grep` pela credencial em `conf/`, no caso e nos logs: nenhuma ocorrência (FR-045).

---

## Cenário 12 — acréscimo e retomada (research D16)

1. Interrompa um processamento com texto ligado no meio da preparação e retome com `--continue`.
   `state` passa de `PARTIAL` a `COMPLETE`, sem trechos duplicados (mesma contagem que um
   processamento limpo).
2. `--append` de nova evidência a `<old-case>` com texto ligado. Os itens novos ficam cobertos, e
   os antigos aparecem como "processados antes da capacidade" (não cobertos).
3. `--append` com um modelo de texto diferente do registrado: a parte semântica é recusada com
   explicação, e o restante do processamento conclui.

---

## Checklist de encerramento

- [ ] Cenário 0 aprovado antes do código dependente
- [ ] SC-001 ___% · SC-002 ___% · SC-003 ___%
- [ ] SC-004 ___ s (1 M) · ___ s (10 mil) · revocação do aproximado ___
- [ ] SC-005 · SC-006 · SC-007 · SC-008 (___%) · SC-009
- [ ] SC-010 ___× · SC-013 ___× · SC-015 ±___%
- [ ] SC-011 · SC-012 ___% (PT) · inglês ___% (FR-036) · SC-014 ___%
- [ ] FR-014 (detalhes da construção) · FR-017 (vários casos, `CASES_EXCLUDED`) · FR-007 (`ceiling_reached` no MCP)
- [ ] SC-016 · SC-017
- [ ] SC-018 ___% (misto) · perda no conceitual ___ pp · `defaultTextMode` de fábrica decidido: ___
- [ ] Decisão de perfis de fábrica tomada com os números acima (research D19)
- [ ] `iped-semantic/CLAUDE.md` criado; CLAUDE.md de `iped-app`, `iped-mcp` e `iped-viewers` atualizados; `ThirdParty.txt` e `licenses/` atualizados
