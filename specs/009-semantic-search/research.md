# Phase 0 — Research: busca semântica sobre os artefatos interpretados

**Feature**: [spec.md](spec.md) | **Plano**: [plan.md](plan.md) | **Data**: 2026-09-26

Cada decisão abaixo traz o que foi escolhido, por quê, e o que foi descartado. Onde a escolha
depende de fato que não pôde ser medido nesta fase, isso está dito, e a verificação vira tarefa
obrigatória antes do código que depende dela — não uma premissa silenciosa.

Achados do código que condicionam tudo:

| Achado | Onde | Consequência |
|---|---|---|
| O Lucene 9.2 da árvore tem `KnnVectorField` (HNSW), `KnnVectorQuery(campo, vetor, k, filtro)` e `exactSearch` protegido; máximo de 1024 dimensões | `lucene-core-9.2.0.jar` | Busca vetorial sem dependência nova, com filtro — é o que torna FR-012 barato |
| O Lucene 9.2 aceita **um vetor por campo por documento** | idem | Trechos, sequências e quadros não cabem num documento por item. Precisam de documentos próprios |
| `TaskInstallerConfig` instancia tasks por `Class.forName` | `iped-engine/.../config/TaskInstallerConfig.java:80` | Tasks podem morar em módulo novo, desde que o jar esteja em `lib/` |
| Texto extraído chega às tasks por `IItem.getTextReader()`, com cabeçalho de metadados ao final (`StandardParser.METADATA_HEADER`) | `LanguageDetectTask.java:72-95` | O mesmo padrão serve à preparação de texto, inclusive o corte do cabeçalho — que não é conteúdo |
| `AudioTranscriptTask` roda **antes** do `ParsingTask`; a transcrição fica em `audio:transcription` (`ExtraProperties.TRANSCRIPT_ATTR`) | `TaskInstaller.xml:23-25` | Transcrição já disponível para qualquer task depois do parsing |
| Mensagem de chat é subitem com `Message:Body`, `Communication:Date`, `Communication:From`, `parentViewPosition`, e o chat é o pai | `WhatsAppParser.java:1038-1122` | As sequências de conversa podem ser montadas sem tocar parser. Os tipos de mensagem são conhecidos (`message/x-whatsapp-message`, `-telegram-`, `-skype-`, `-discord-`, `-threema-`) |
| Miniatura de imagem padrão tem **256 px** (`imgThumbSize = 256`) e está no item (`getThumb()`) depois do `ImageThumbTask` | `ImageThumbsConfig.txt:33` | Os modelos de imagem consomem 224 px: dá para usar a miniatura já feita, sem decodificar o original de novo |
| Os quadros de vídeo ficam num **mosaico JPEG no preview**, com comentário `Frames=RxC`; os instantes **não são gravados** — o MPlayer extrai com `-ss 1 -sstep <passo>` | `VideoThumbTask.java:455-477`, `ImageUtil.getBestFramesFit`, `VideoThumbsMaker.java:190-255` | Os quadros são recuperáveis do mosaico; o instante só é **derivável de forma aproximada** (D8) |
| A interface filtra e reordena o resultado corrente por `IResultSetFilterer`/`IResultSetFilter.filterResult(src)` — é assim que "faces semelhantes" funciona | `App.java:1883-1960` | A busca semântica entra pelo mesmo mecanismo, e o recorte (FR-012) é o `src` que o filtro recebe |
| A política de egresso do MCP é consultável por item e por classe (`EgressPolicy.check(ContentClass, item)`), com classes `metadata`, `text`, `thumbnail`, `binary` | `iped-mcp/.../egress/EgressPolicy.java:70` | A ordenação pode sair como metadado e cada trecho ser liberado ou retido pela política, item a item |
| Modelos atuais entram no release como artefatos Maven do repositório do IPED, desempacotados em `models/` | `iped-app/pom.xml:487-557` | O modelo de texto segue o mesmo caminho (FR-041) |
| Nenhum runtime de inferência na árvore (sem ONNX, DJL, TensorFlow Java) | `pom.xml` de todos os módulos | Dependência nova é inevitável; D2 escolhe qual |
| A busca por palavra do IPED **não é BM25**: `IndexerSimilarity` pontua pela frequência bruta do termo (`score = freq`), sem IDF nem normalização de tamanho, e pontua o **item inteiro** | `iped-engine/.../search/IndexerSimilarity.java`, usada em `Manager:413` e `IPEDSource:508` | O modo híbrido não pode reaproveitar a pontuação existente. Precisa de relevância por palavra própria, por trecho (D20) |
| `iped_item_text` só lê do início do texto até `max_chars`; não há leitura por intervalo | `iped-mcp/.../tools/ItemTools.java:82-90` | Um RAG não consegue ampliar o contexto em volta de um trecho do meio de um documento longo sem trazer tudo o que vem antes (D21) |

---

## D1 — Onde a capacidade mora

**Decisão**: novo módulo Maven **`iped-semantic`**, entre `iped-engine` e os consumidores. Ele
contém as tasks de preparação, o armazenamento no caso, o buscador e as fontes de modelo. O
`iped-app` (interface) e o `iped-mcp` (agente) dependem dele e não reimplementam nada. É assim
que FR-028 ("a capacidade reside no caso, não na superfície") e SC-011 (mesma ordenação pelos dois
caminhos) viram propriedade do desenho, e não de disciplina: **há um único buscador**.

**Por quê**: a constituição pede que capacidade de escopo próprio nasça como módulo quando puder
consumir só API pública. Isola a dependência nativa nova (D2) de quem não a usa, e mantém o
`iped-engine` intocado.

**Descartado**:
- *Pacote dentro de `iped-engine`* — arrastaria o runtime de inferência para todo consumidor do
  engine, inclusive a Web API e o `iped-hashdb`.
- *Serviço fora do processo desde o início* — isola falha nativa, mas acrescenta ciclo de vida,
  porta e protocolo a um recurso que roda na mesma máquina. Fica como evolução se D2 mostrar
  instabilidade (ver "Riscos").

---

## D2 — Runtime de inferência local

**Decisão**: **ONNX Runtime Java (CPU)** para executar os modelos, e **ONNX Runtime Extensions**
para a tokenização **dentro do próprio grafo**. Cada modelo é distribuído como um arquivo ONNX que
recebe texto cru (ou pixels) e devolve o vetor. A execução acontece **no processo**, com uma sessão
compartilhada por modelo em cada JVM (processamento, interface, servidor MCP).

**Por quê**:
- O ONNX Runtime Java exige **Java 8 ou superior** para executar ([documentação oficial](https://onnxruntime.ai/docs/get-started/with-java.html)),
  cabe no JRE 11 que o release embarca, e o artefato `com.microsoft.onnxruntime:onnxruntime` traz
  as bibliotecas nativas de CPU para Windows x64, Linux x64 e macOS dentro do jar.
- O ONNX Runtime Extensions publica pacote Java no Maven desde a 0.11 e cobre os tokenizadores
  XLM-RoBERTa, BERT e CLIP ([releases](https://github.com/microsoft/onnxruntime-extensions/releases)).
  Com o tokenizador no grafo, **o que prepara no processamento e o que calcula a pergunta na
  interface são literalmente o mesmo arquivo** — não há como divergirem por implementação.
- Nada é baixado em tempo de execução: FR-023 (estação isolada) por construção.

**Descartado**:
- *Python via JEP ou processo externo* (o caminho de faces e Whisper) — exige no Linux que o
  usuário instale pacotes de ML, e o modelo de texto tem que funcionar assim que a chave é ligada
  (FR-041). Continua sendo a forma da **ferramenta de exportação** e do **servidor remoto de
  referência** (D9), que rodam fora do IPED.
- *Tokenizador do DJL (`ai.djl.huggingface:tokenizers`)* — por padrão **baixa as bibliotecas
  nativas da internet** ([documentação do DJL](http://djl.ai/extensions/tokenizers/)). Contornável
  com pacote offline, mas é uma dependência cujo comportamento padrão contradiz FR-023.
- *Tokenizador em Java puro* — viável para WordPiece e BPE, mas a normalização do SentencePiece do
  XLM-R (`precompiled_charsmap`) é difícil de reproduzir com fidelidade. Fica como **plano B** se o
  spike abaixo reprovar as extensões.

**Verificação obrigatória (spike, antes de qualquer código dependente)**: nos jars exatos a serem
adotados, (a) versão de class file ≤ 55 (Java 11), (b) nativos de Windows x64 e Linux x64
presentes no jar, (c) carregamento sem acesso à rede, (d) sessão `run()` concorrente estável em
Java 11. A documentação consultada não afirma o baseline Java das extensões, e a constituição
proíbe adotar dependência que não rode no JRE 11 **mesmo que compile**.

### Resultado do spike (T002, 2026-09-26) — **aprovado, com a versão corrigida em 2026-09-27**

Versões fixadas em `iped-semantic/pom.xml`: **`onnxruntime` 1.20.0** e **`onnxruntime-extensions`
0.13.0**. Teste: `iped-semantic/src/test/java/iped/semantic/spike/OrtBaselineSpikeTest.java`.

**Correção de 2026-09-27.** A primeira rodada aprovou a 1.30.0 no JDK de build (Liberica 11.0.31),
e isso não bastava: a restrição é de **runtime**, e o release embarca o JRE 11.0.13, cujo
`jre\bin\msvcp140.dll` é a versão 14.16 (VS2017). No primeiro processamento real, a JVM caiu com
`EXCEPTION_ACCESS_VIOLATION` em `msvcp140.dll` ao criar o `OrtEnvironment`: o ONNX Runtime recente
é compilado com um MSVC mais novo, e o carregador do Windows resolve `msvcp140.dll` para o que a JVM
já carregou. Matriz rodada no JRE do release (programa independente e depois a suíte inteira com
`-Djvm=<release>/jre/bin/java.exe`):

| ONNX Runtime | com extensões 0.12.4 / 0.13.0 |
|---|---|
| 1.17.3, 1.18.0, 1.19.2, **1.20.0** | carrega; operador padrão, operador das extensões e o modelo real funcionam, com o mesmo vetor nas quatro |
| 1.21.0, 1.21.1, 1.22.0 | `UnsatisfiedLinkError` ao carregar o nativo |
| 1.30.0 | queda da JVM em `msvcp140.dll` |

As extensões 0.11.0 não exportam `RegisterCustomOps` e ficam fora. A 1.20.0 é a mais nova que
funciona. **O portão passa a ser rodado sempre com `-Djvm` apontando para o JRE do release**; trocar
o JRE embarcado ou a versão do ONNX Runtime exige rodá-lo de novo. A primeira rodada, no JDK de
build, está descrita abaixo; os critérios (a) a (d) se repetiram no JRE 11.0.13 com a 1.20.0
(suíte do módulo: 61 testes, nenhum pulado, `java.runtime.version = 11.0.13+8-LTS` no relatório).

| Critério | Resultado |
|---|---|
| (a) class file ≤ 55 | Todas as classes dos dois jars são **major 52** (Java 8): 58 no `onnxruntime` 1.20.0 (64 na 1.30.0), 2 no `onnxruntime-extensions`. Conferido também em 1.22.0 e 0.12.4, com o mesmo resultado |
| (b) nativos Windows x64 e Linux x64 | Presentes nos dois jars (`ai/onnxruntime/native/{win-x64,linux-x64}/…` e `ai/onnxruntime/extensions/native/{win-x64,linux-x64}/…`). O de Linux não foi **carregado** aqui; isso fica para o CI Ubuntu |
| (c) carga sem rede | Sessão com operador padrão e sessão com operador das extensões (`StringUpper`, domínio `ai.onnx.contrib`, via `registerCustomOpLibrary`) criadas e executadas com um `SecurityManager` que recusa toda `SocketPermission`: **nenhum pedido**. O único pedido observado vinha da inicialização do Log4j (resolução do nome da própria máquina), que o IPED já faz; o teste inicializa o log antes de erguer o guarda. Código nativo não é observável por esse meio, e o Cenário 0 do quickstart repete a verificação com a máquina fora da rede |
| (d) `run()` concorrente | Uma sessão compartilhada por 4 threads, 500 execuções cada, todas corretas |

Consequências para o desenho:
- O `OrtxLibrary` do jar das extensões **sempre** extrai o nativo para `java.io.tmpdir`. O IPED não
  o usa: `iped.semantic.model.OrtNatives` copia os nativos dos dois jars para
  `tools/onnxruntime/<os-arch>/`, define `onnxruntime.native.path` antes do primeiro uso do runtime
  e passa o caminho da biblioteca das extensões a `registerCustomOpLibrary` (D17).
- A 1.20.0 tem 93 MB (a 1.30.0, reprovada, teria 55 MB): o jar embute símbolos de depuração do
  macOS, que não são extraídos. O `OrtNatives` remove de `tools/onnxruntime/` biblioteca opcional
  que o jar em uso não traz, para nunca misturar nativos de duas versões.
- Os modelos ainda não haviam sido exportados quando o portão rodou. A carga do modelo real, com o
  tokenizador no grafo, é verificada pelos testes do `LocalOnnxEmbedder` (T011).

---

## D3 — Modelos

**Decisão**: começar com um candidato principal por modalidade e um alternativo. A escolha final
fica condicionada à medição dos critérios de sucesso no conjunto de avaliação (D18), e não à
reputação do modelo.

| Modalidade | Principal | Alternativo | Licença | Dimensões |
|---|---|---|---|---|
| Texto | `intfloat/multilingual-e5-small`, quantizado em int8 | `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` | MIT / Apache-2.0 | 384 / 384 |
| Visual | `sentence-transformers/clip-ViT-B-32-multilingual-v1` (texto multilíngue destilado + imagem CLIP ViT-B/32) | `google/siglip-base-patch16-256-multilingual` | Apache-2.0 (+ MIT da parte de imagem do CLIP) / Apache-2.0 | 512 / 768 |

**Por quê**:
- *Texto*: o e5-small é multilíngue (cerca de 100 idiomas, PT, EN e ES incluídos), treinado para
  recuperação assimétrica (pergunta curta contra trecho longo), aceita 512 tokens por trecho e é
  pequeno o bastante para CPU. Exige os prefixos `query:` e `passage:`, que ficam registrados como
  parâmetro do modelo no registro de construção.
- *Visual*: o CLIP multilíngue permite que a descrição em português (FR-036) seja comparada com a
  imagem sem tradução. O ViT-B/32 é o codificador de imagem mais barato da família, o que importa
  para SC-013. O SigLIP multilíngue é mais preciso e cerca de quatro vezes mais caro por imagem:
  só entra se o principal reprovar SC-012 ou SC-014.
- Todas as licenças permitem redistribuição, o que FR-041 exige para o modelo de texto no pacote.

**Identidade de modelo** (usada por FR-014, FR-016 e FR-043): `<repositório>@<revisão>+<variante>`
(por exemplo, `intfloat/multilingual-e5-small@<sha>+onnx-int8-ortx`) e o SHA-256 do arquivo ONNX.
Os artefatos são produzidos por um **script de exportação reprodutível**
(`iped-semantic/tools/export-models/`), que fixa revisão, versão do exportador e quantização. Um
mesmo script produz o artefato do pacote e o do servidor remoto de referência.

**Descartado**: `BAAI/bge-m3` (568 M parâmetros, 1024 dimensões — pesado demais para CPU no
volume de um caso); `jina-clip-v2` (licença não comercial); modelos só em inglês (reprovariam
FR-003 e FR-036 por construção).

---

## D4 — Armazenamento no caso

**Decisão**: **índices Lucene próprios**, um por modalidade, em `<caso>/iped/semantic/text/` e
`<caso>/iped/semantic/visual/`, mais `semantic/build-record.json`. **Nada** é gravado no índice
principal. Leiaute completo em [contracts/case-storage.md](contracts/case-storage.md).

**Por quê**:
- Um vetor por documento (achado acima) obriga a ter um documento por trecho, sequência ou
  quadro. Pôr esses documentos no índice principal misturaria documentos que não são itens com
  documentos que são, e toda busca por palavra existente passaria a ter de excluí-los.
- **Princípio II mais forte que "aditivo"**: o índice principal não ganha nem campo. Caso antigo
  não tem `semantic/`, e é por essa ausência que a busca se declara indisponível (FR-027).
- O ciclo de vida é separado: dá para descartar e reconstruir a parte semântica sem tocar o resto.

**Descartado**:
- *Campo vetorial no índice principal* — um vetor por item não comporta trechos. E muda o índice
  que a constituição trata como contrato.
- *Banco vetorial externo* — serviço a mais, e o caso deixa de ser autocontido e portátil.
- *Arquivo binário com varredura exaustiva* — cerca de 3 M trechos × 384 floats ≈ 4,6 GB por
  consulta fria, sem integração com filtro.

---

## D5 — Como a busca responde

**Decisão**:
1. **Recorte como filtro**. O conjunto de itens do resultado corrente (o `src` do filtro na
   interface, ou a consulta/marcador no MCP) vira um bitset de ids. Uma `Query` própria casa os
   documentos semânticos cujo `itemId` está no bitset, no mesmo padrão do `BookmarkQuery` do MCP.
   Esse filtro alimenta a `KnnVectorQuery`.
2. **Uma única lista até o teto**. A busca é executada **sempre** com `k = teto × sobreamostragem`.
   A profundidade exibida é um prefixo dessa lista. Estender a profundidade (FR-007) ou paginar no
   agente **revela mais da mesma lista**, nunca refaz a busca. Isso importa porque no HNSW o `k`
   também é a largura da busca: refazer com `k` maior pode reordenar o topo, e o cenário 6 da
   História 2 exige manter a ordem já exibida.
3. **Exato abaixo de um limiar, aproximado acima**. Se o recorte tem até `exactSearchMaxItems`
   itens (padrão 50 000), a varredura é exaustiva sobre os vetores do recorte. Acima disso, usa o
   HNSW. **O método usado é declarado no resultado.**
4. **Ordem determinística**: proximidade decrescente, depois `itemId` crescente, depois ordinal do
   trecho (FR-015).
5. **Consolidação** (FR-009): por item, o melhor trecho, mais até dois trechos seguintes como
   contexto. Por sequência, a absorção é gulosa: janelas de menor proximidade que se sobrepõem a
   uma já aceita são absorvidas por ela, e nenhuma mensagem aparece em duas entradas. Por vídeo,
   só o melhor quadro. Se a consolidação deixar menos entradas que o teto, a profundidade real é
   declarada.

**Por quê**: o HNSW é o que torna SC-004 (5 s em 1 M itens) alcançável na CPU. O Lucene 9.2
guarda os vetores fora do heap, então varrer tudo exaustivamente custaria o volume inteiro em
disco a cada pergunta fria. Recortes pequenos, que são o uso típico da História 3, ficam exatos.

**Descartado**: *sempre exato* (proibitivo na escala de SC-004); *sempre aproximado* (desperdiça
exatidão onde ela é barata); *piso de proximidade* (proibido por FR-007).

**Modo híbrido**: a parte semântica segue exatamente estas regras, e a combinação com a parte por
palavra está em D20.

**Consequência para o spec**: "os mais próximos" é exato só abaixo do limiar. A ordenação
continua sendo reproduzível (os mesmos arquivos de índice dão a mesma resposta), mas pode omitir um
item que a varredura exaustiva poria na lista. Isso aparece no resultado — emenda **A3**,
aceita e aplicada à FR-007 (ver [plan.md](plan.md)). A revocação do aproximado contra o exato é medida no
conjunto de avaliação (quickstart, Cenário 9).

---

## D6 — Preparação de texto

**Decisão**: `SemanticTextTask`, registrada no `TaskInstaller.xml` **depois do
`NamedEntityTask`** e antes do `ExportFileTask`. Nesse ponto, texto e transcrição já estão
disponíveis e o cache de texto ainda não foi descartado.

| Fonte | Unidade | Tratamento |
|---|---|---|
| Documento, e-mail, qualquer item com texto extraído (inclui OCR, que o parsing já anexa) | trecho | Janelas de ~1000 caracteres com ~200 de sobreposição, respeitando fim de frase. Corta o cabeçalho de metadados. Limite de `maxTextCharsPerItem` (padrão 200 000), com excedente declarado |
| Transcrição (`audio:transcription`) | trecho | Mesmo fatiamento, com tipo `transcript` |
| Mensagem de chat (tipo em `messageMediaTypes`) | — | **Não** é preparada sozinha: vai para a área de montagem (D7) |
| Item do próprio chat (a conversa renderizada) | trecho | Preparado como documento. **Descartado no `finish()`** se mensagens desse chat foram montadas em sequência (motivo "coberto pelas mensagens"). Continua valendo quando o perfil não extrai mensagens (`extractMessages=false`, triagem) |

O custo fica fora do caminho do worker: a task entrega os trechos a um `EmbeddingService`
compartilhado na JVM, que processa em lotes com fila limitada. Quando a fila enche, o worker
espera, e é assim que se controla a memória. O `finish()` drena a fila e faz o commit.

**Descartado**: *preparar no `IndexTask`* (tocaria task existente — Princípio III); *preparar
depois do processamento, lendo o índice* (o texto integral não fica armazenado no índice —
`content` é indexado e não armazenado —, e reextrair custaria reler a evidência).

---

## D7 — Sequências de conversa

**Decisão**: montadas no `finish()` da `SemanticTextTask`, a partir de uma área de montagem
persistente (`semantic/staging.mv.db`, H2 MVStore, que o engine já usa para previews). Durante o
processamento, cada mensagem entra ali com (chat, item, data, posição, remetente, texto). No
`finish()`, por chat:

- Ordem por `Communication:Date`, depois `parentViewPosition`, depois id.
- Janelas de `chatWindowMessages` mensagens (padrão 8), passo `chatWindowStride` (padrão 4), no
  máximo `chatWindowMaxChars` caracteres (padrão 1500). A janela quebra num intervalo maior que
  `chatMaxGapMinutes` (padrão 720): conversa de outro dia não completa sentido desta.
- O texto calculado inclui o remetente de cada linha (`remetente: texto`), porque "quem ameaça
  quem" depende disso. O texto **exibido** é a sequência literal das mensagens, com remetente e
  hora, como o visualizador de chat já mostra.
- Cada sequência guarda os ids das mensagens que a compõem, e é por eles que o perito abre, marca e
  filtra (FR-005).
- A área de montagem é apagada depois do commit. Com `--continue`, ela sobrevive e é retomada.

**Por quê no `finish()`**: os workers processam itens em ordem arbitrária. Uma janela precisa das
vizinhas, que podem ainda não ter passado.

**Tipos de mensagem**: `messageMediaTypes` é lista de configuração. O padrão traz os tipos
emitidos pelos parsers de chat da árvore (WhatsApp, Telegram, Skype, Discord, Threema). Os tipos de
mensagem de UFDR ainda precisam ser enumerados — tarefa de levantamento, não premissa.

**Levantamento (T026, 2026-09-26, só leitura).** Lista final do padrão, em
`SemanticSearchConfig.java` e `conf/SemanticSearchConfig.txt`:

| Tipo | Origem |
|---|---|
| `message/x-whatsapp-message`, `message/x-telegram-message`, `message/x-skype-message`, `message/x-discord-message`, `message/x-threema-message` | parsers de chat da árvore; todos `sub-class-of message/x-chat-message` em `CustomSignatures.xml` |
| `message/x-jabber-message` | também subclasse de `message/x-chat-message` em `CustomSignatures.xml` (nenhum parser Java da árvore o emite; entra para não ficar de fora quando um script o fizer) |
| `application/x-ufed-instantmessage` | mensagens de chat de UFDR (`iped-parsers/.../ufed/handler/InstantMessageHandler.java` e `BaseModelHandler.java`) |

As mensagens de UFDR têm os mesmos campos que as sequências usam: `BaseModelHandler` move
`ufed:Body` para `Message-Body` (`ExtraProperties.MESSAGE_BODY`) e `ufed:TimeStamp` para
`Communication:Date`; `InstantMessageHandler` grava `Communication:From` e `parentViewPosition`
(o `anchorId` do modelo, que pode não ser numérico: nesse caso a ordem cai para data e id). O chat
é o item pai (`application/x-ufed-chat`), como nos parsers da árvore.

Ficam **fora de propósito** os anexos (`*-attachment`, `message/x-skype-filetransfer`): são itens
com conteúdo próprio (o arquivo), preparados como documento. As chamadas (`call/x-*`) não têm
corpo de mensagem. E-mails (`message/rfc822` e variantes) são documentos, não conversa.

Observação de nome: o corpo da mensagem está na chave `Message-Body` (prefixo `Message-`), e não
`Message:Body` como o texto da spec abrevia; o remetente normalizado está em `Communication:From`,
com `Message:From` do Tika como alternativa quando a normalização não ocorreu.

**Recorte parcial** (caso de borda da spec): a entrada entra se ao menos uma mensagem estiver no
recorte. As de fora vêm marcadas como contexto, não contam e não são marcadas junto.

---

## D8 — Preparação visual

**Decisão**: `SemanticVisualTask`, registrada **depois do `ImageSimilarityTask`**, quando as
miniaturas já existem.

- **Imagens**: a entrada é a **miniatura do item** (256 px no padrão). Não há nova decodificação do
  original nem leitura da evidência além do que o processamento já fez. Ficam fora com motivo:
  imagens menores que `minImageSize` (padrão 48 px no maior lado, lido das dimensões originais),
  item sem miniatura, e miniatura desligada no perfil. Este último é um conflito de configuração, e
  o registro do processamento o aponta.
- **Vídeos**: o mosaico do preview é lido como o `VideoThumbTask` já o lê para a galeria, e
  recortado nas células que o comentário `Frames=RxC` descreve (a mesma geometria de borda de
  `ImageUtil.getBestFramesFit`). Cada quadro é calculado separadamente, e o vídeo fica com o melhor
  quadro na ordenação.
- **Instante do quadro**: **aproximado**. O MPlayer extrai com `-ss 1 -sstep <passo>`, e o passo
  depende da duração e do número de quadros. O instante é derivado dessa mesma fórmula e exibido
  como "≈ mm:ss". O salto é para quadro-chave, então o erro real é da ordem de um passo. Emenda
  **A2**, aceita e aplicada à FR-035.
  - *Nota da implementação (2026-09-27)*: há um segundo caminho. Quando o passo inteiro em
    segundos dá menos de 2 — vídeo curto, abaixo de uns 47 s com 20 células —, o `VideoThumbsMaker`
    tira o `-ss 1 -sstep` e extrai por `framestep` de `min(240, fps)·(duração − 1 ms)/(células + 2)`
    quadros, desde o início. A célula `k` guarda o quadro extraído `k + 1` (o primeiro é
    descartado). O `FrameInstant` segue os dois caminhos, com o fps de `video:framerate`, e a
    duração vem de `video:xmpDM:duration` (a normalização de metadados do parsing, que roda depois
    do `VideoThumbTask`, põe o prefixo `video:`). Na bancada, um vídeo de 40 s com praia (0–10 s),
    carro (10–20 s) e cidade à noite (20–40 s): a fórmula só por segundos dava o carro em ≈ 00:07 e a
    noite em ≈ 00:18; com os dois caminhos, ≈ 00:10 e ≈ 00:30.
- **Quadros como subitens** (`videoThumbsSubitems=true`): com a chave de vídeos ligada, esses
  subitens saem da ordenação de imagens (motivo "coberto pelo vídeo de origem"). O mesmo conteúdo
  não aparece duas vezes, como imagem e como vídeo.

**Descartado**: *gravar os instantes exatos no `VideoThumbTask`* (alteraria task existente para
servir a esta — Princípio III; fica como melhoria separada, que tornaria a emenda A2 desnecessária);
*decodificar o vídeo de novo* (proibido pela spec e caro).

---

## D9 — Modelo remoto

**Decisão**: dois protocolos, escolhidos por modalidade em configuração.

| Protocolo | Modalidades | Identidade (FR-043) |
|---|---|---|
| `openai-embeddings` — `POST {base}/v1/embeddings` | só texto | campo `model` da resposta |
| `iped-embed/1` — contrato próprio | texto e imagem | objeto `model` da resposta, com id e hash do artefato |

Contratos em [contracts/remote-model-protocol.md](contracts/remote-model-protocol.md). O
`openai-embeddings` cobre, sem código a mais, serviços comerciais e servidores locais que
falam esse formato. Imagem não tem formato padrão, por isso o `iped-embed/1`. Junto dele vai um
**servidor de referência** (`iped-semantic/tools/semantic-server/`), que serve os **mesmos artefatos
ONNX** do pacote. Um laboratório com GPU hospeda o modelo sem escrever código, e o caminho remoto
pode ser testado sem depender de terceiros (SC-016).

**Regras**:
- Cliente HTTP do próprio JDK 11 (`java.net.http`), sem dependência nova.
- HTTPS obrigatório. Aceita-se HTTP puro só para endereço de loopback ou com
  `remoteAllowPlainHttp=true` explícito.
- Credenciais seguem o padrão da 006 (FR-045): a configuração nomeia variável de ambiente ou
  arquivo, nunca o valor.
- Falha no processamento (serviço fora, limite de requisições): novas tentativas com espera
  crescente. Esgotadas, o item fica não coberto com motivo "serviço remoto indisponível", e o
  processamento segue (FR-022).
- Falha na pergunta: a modalidade se declara indisponível e **não recorre a outro modelo**
  (FR-044). Também não há troca automática de fonte: a instalação usa a que configurou.
- **A fonte das perguntas é a da instalação em uso, não a do caso** (emenda A4). Um caso
  preparado no servidor do laboratório pode ser pesquisado com o artefato local idêntico, e o
  contrário também vale, desde que a impressão digital confirme o mesmo modelo (D10). O caso
  registra o destino da preparação. O destino das perguntas vai declarado em cada resultado
  (`sent_to`) e no aviso da interface (FR-024).
- Identidade informada que diverge da registrada **ou** impressão digital (D10) fora da
  tolerância: pergunta recusada (FR-016, FR-043). A impressão digital é a única defesa contra o
  serviço que troca os pesos mantendo o nome.

**Descartado**: *só o formato OpenAI* (não tem imagem); *só protocolo próprio* (excluiria
serviços que o usuário quer poder usar); *gRPC* (dependência nova sem ganho nesta escala).

---

## D10 — Reprodutibilidade e compatibilidade

**Decisão**: cada modalidade preparada grava no registro de construção uma **impressão digital do
modelo**: os vetores de um conjunto fixo de sondas. São 16 frases em PT, EN e ES e, na visual,
também 8 imagens sintéticas geradas de forma determinística (gradientes e formas), versionadas
com o código. Ao abrir uma sessão de busca, as sondas são recalculadas com a fonte em uso, seja ela a mesma da
preparação ou outra (emenda A4): o que decide a compatibilidade é o modelo, não o lugar onde ele
roda. Se a
similaridade de cosseno de todas ficar em ≥ `fingerprintTolerance` (padrão 0,9995, a calibrar),
o modelo é o mesmo. Abaixo disso, a busca é recusada com os dois modelos nomeados.

**Por quê**: a identidade declarada detecta modelo diferente **com nome diferente**. A impressão
digital detecta também peso diferente com o mesmo nome (serviço remoto), arquivo corrompido e
exportação diferente. A tolerância absorve a variação numérica entre CPUs (caso de borda da spec).
Com isso, "mesma instalação dá a mesma ordenação" (SC-005) vale por construção, e "outra estação
pode variar em quase-empates" fica declarado em vez de escondido.

---

## D11 — Profundidade e teto

**Decisão**: `defaultDepth = 100` e `maxDepth = 1000`, em configuração (FR-019). A interface
mostra a profundidade padrão e tem "mais resultados" até o teto. O MCP pagina com cursor sobre a
lista do teto, com o tamanho de página já governado pelo `McpServerConfig`. No teto, a interface
desabilita "mais resultados", e o MCP encerra a paginação sem `next_cursor` e com
`ceiling_reached: true`. Nos dois casos, a mensagem nomeia o teto e a chave que o governa e não
sugere que além dele não haja nada. Como o MCP não tem argumento de profundidade, não existe
recusa por profundidade (achado I2 da análise).

---

## D12 — Cobertura

**Decisão**: para cada item considerado, o índice da modalidade guarda um **documento de estado**
(sem vetor) com o código do motivo. A cobertura de um recorte é a contagem desses códigos sobre o
bitset do recorte, feita sobre um vetor de bytes indexado por id, carregado uma vez por sessão.
Item do recorte **sem** documento de estado tem motivo **derivado** do registro de construção:
"modalidade desligada no processamento" se ela estava desligada, e "processado antes da
capacidade" se o item é anterior à preparação (acréscimo a caso antigo).

Motivos que o desenho produz e que **não estão** na enumeração de FR-008: "categoria não
selecionada", "coberto pelas mensagens", "coberto pelo vídeo de origem", "sem miniatura",
"modelo remoto indisponível". Emenda **A1**, aceita e aplicada à FR-008.

**Como ficou na implementação (2026-09-27).** Todo item que passa pela task tem documento de
estado; por isso a derivação só cobre dois casos, e nenhum deles precisa do tipo do item:
- **`BEFORE_CAPABILITY`**: item sem documento de estado com id menor que `capabilityStartId`,
  gravado no registro quando a capacidade é ligada num acréscimo a caso que já tinha itens (o
  primeiro id do acréscimo, lido do índice principal já gravado).
- **`PREP_FAILURE`**: item sem documento de estado e posterior à capacidade. É o item cuja
  preparação não se concluiu, tipicamente por um processamento interrompido cujo `--continue` não o
  revisita (o `SkipCommitedTask` pula o que o índice principal já gravou). A janela é pequena: o
  índice semântico grava junto com o principal (a task ouve "Manager.CommitStarted" e espera o que
  já foi enviado ao modelo antes do `prepareCommit` do engine), mas o que escapar é declarado, não
  escondido.
- **`MODALITY_OFF`** é **gravado**, e não derivado: a task visual registra esse motivo nos itens
  do tipo cuja chave está desligada (vídeos com só imagens ligadas, e vice-versa). Uma modalidade
  inteira desligada não tem registro, e aí a busca já se declara indisponível antes de contar
  cobertura.

---

## D13 — Interface

**Decisão**:
- **Painel "Busca semântica"**, acoplável como os demais painéis da interface. Tem campo de
  pergunta, escolha de modalidade (texto, visual, ambas), declaração (profundidade, método,
  cobertura com motivos, modelo, destino remoto se houver) e uma aba por modalidade. Em texto,
  cada entrada mostra o trecho literal, ou a sequência com remetente e hora. No visual, mostra a
  miniatura (com o desfoque da galeria respeitado, FR-037) e o instante aproximado nos vídeos.
- **`SemanticResultFilterer`**, que implementa `IResultSetFilterer`: aplica a ordenação escolhida
  à tabela de resultados. O `src` que ele recebe **é** o recorte (FR-012), e a proximidade vai na
  coluna de pontuação, como em "imagens semelhantes". Uma modalidade de cada vez (FR-034).
- **"Mais como este"** no menu de contexto da tabela (FR-013) e "Mais como este trecho" sobre
  texto selecionado no visualizador de texto. O segundo exige tocar o `ATextViewer`
  (`iped-viewers`); o toque é aditivo (uma ação de menu que publica a seleção) e está justificado
  no Constitution Check.
- **Destaque do trecho** (cenário 4 da História 1): pelo mecanismo existente
  (`App.getHighlightTerms()` → `FileProcessor` → visualizador), com as palavras iniciais literais
  do trecho como frase. **Verificação obrigatória**: confirmar que o visualizador de texto destaca
  frase, não só termo. Se não destacar, o fallback é localizar e rolar até o deslocamento
  guardado no trecho.
- **Aviso de destino remoto** (FR-044): quando a modalidade do caso usa modelo remoto, o painel
  mostra o destino de forma permanente e pede confirmação na primeira pergunta de cada sessão.
- **Marcador com anotação** (FR-011): o comentário do marcador (`Bookmarks.setBookmarkComment`)
  recebe pergunta, recorte, profundidade e modelo.
- Consultas rodam fora da EDT (`SwingWorker`). O modelo é carregado sob demanda, na primeira
  pergunta, e não na abertura do caso.

**Verificação do destaque (T033, 2026-09-27, só leitura).** O mecanismo é outro do que a
decisão supunha. `App.getHighlightTerms()` → `FileProcessor` → `ViewerController.loadFile` serve
aos visualizadores que destacam termos (HTML, PDF e afins). O **visualizador de texto** não usa
esses termos: `iped-app/.../ui/viewers/TextViewer` → `TextParser` → `TextHighlighter` destaca os
acertos de um `QueryScorer` sobre `App.getQuery()`, a consulta de destaque que
`UICaseSearchFilterListener.saveHighlightTerms()` define a cada busca. O `QueryScorer` destaca
`PhraseQuery` respeitando posição, ou seja, destaca **frase**. `App.getQuery()` só é lido pelo
`TextHighlighter` (conferido por busca na árvore), então estendê-la não altera resultado de busca.

Decisão: ao abrir uma entrada de trecho, o painel soma à consulta de destaque, por `OR`, uma frase
com as primeiras palavras literais do trecho (montada pelo `QueryBuilder` do caso, com a mesma
análise do campo de conteúdo), e publica a mesma frase em `setHighlightTerms` para os demais
visualizadores. O visualizador de texto passa a destacar o trecho e a lista de acertos permite ir
até ele. A próxima busca por palavra substitui a consulta de destaque, como já faz. Não foi
preciso o fallback de rolar até `charStart`, nem tocar em `iped-viewers`.

---

## D14 — Agente (MCP)

**Decisão**: `SemanticTools`, com quatro ferramentas: `iped_semantic_status`,
`iped_semantic_search`, `iped_semantic_similar` e `iped_semantic_context` (esta última em D21). Contratos em
[contracts/mcp-tools.md](contracts/mcp-tools.md).

- Descritor com classe de conteúdo `metadata`. Cada trecho passa por
  `EgressPolicy.check(text, item)` e, se bloqueado, sai no lugar dele `excerpt_withheld` com o
  motivo (cenário 2 da História 5). Resultado visual identifica o item e o instante, **sem**
  imagem: examinar passa por `iped_item_thumbnail`, que já emite bloco de imagem desde a 008
  (FR-039).
- Toda resposta carrega `ordering_notice` (FR-040) e, com modelo remoto, `sent_to`.
- Recorte pelos mesmos parâmetros de `iped_search` (`query`, `bookmark`), resolvidos pelo mesmo
  `PagedSearcher`/`BookmarkQuery`.
- Auditoria automática pelo `McpDispatcher`, sem mudança de formato (`AuditRecord` sem campo novo).
- A skill ganha `references/semantic-search.md`: quando usar significado e quando usar palavra, e
  por que ordenação não é conjunto.

---

## D15 — Custo e fluxo do processamento

**Decisão**:
- `EmbeddingService` por modelo e por JVM: fila limitada, lotes de `batchSize` (padrão 32),
  `inferenceThreads` threads (padrão metade dos núcleos). Os workers só enfileiram.
- Com a chave desligada, as tasks retornam em `isEnabled()`, nenhum modelo é carregado e nenhuma
  classe nativa é tocada (FR-020, SC-008).
- **Falha rápida** (FR-042, SC-017): o `init()` da task valida a fonte de modelo (arquivo presente
  e impressão digital calculável, ou serviço alcançável e identidade informada). Se falhar, lança
  exceção, e o processamento não começa. **Verificação obrigatória**: confirmar que o `Manager`
  inicializa as tasks antes do primeiro `read()` da evidência. Se não for assim, a verificação
  sobe para a validação de configuração do `Bootstrap`.
- Commit do índice semântico a cada `commitIntervalMinutes` (padrão 10) e no `finish()`. Com
  `--continue`, itens reprocessados substituem seus documentos (`updateDocuments` por `itemId`).
  Documentos de itens que o índice principal não tem nunca aparecem, porque o recorte vem sempre
  do índice principal.
- O registro de construção e o log recebem tempo e contagens por modalidade, com imagens e vídeos
  separados (FR-021, SC-015).

**Verificação do `Manager` (T016, 2026-09-26, só leitura) — a ordem é garantida.** Em
`iped-engine/.../core/Manager.java`, `process()` executa, nesta ordem: `prepareOutputFolder()`,
`openIndex()` (índice principal do caso, não a evidência), `initWorkers()` e só então cria e inicia
os dois `ItemProducer` (`counter` e `producer`), que são quem abre as fontes de dados e carrega o
Sleuthkit. `initWorkers()` chama `Worker.init()` de todos os workers, e cada um chama
`task.init(ConfigurationManager.get())` de todas as suas tasks antes de qualquer `start()`. Uma
exceção no `init()` sobe até o `catch (Exception e)` de `process()`, que chama
`interruptProcessing()` e relança. Os produtores nunca são criados. Portanto, a validação de fonte
de FR-042 fica no `init()` da task (`SourceValidator`), sem desvio para o `Bootstrap`. O que já
existe no disco antes dela é a pasta de saída e o índice principal vazio, não leitura da evidência.

**Meta de custo não medida**: nenhum número de SC-010, SC-013 ou SC-015 pode ser afirmado agora. A
primeira medição na bancada é tarefa com critério de parada: se o dobro ou o triplo não forem
atingidos só com CPU, a revisão volta para a spec (Assumptions), não para o plano.

---

## D16 — Acréscimo e retomada

**Decisão**:
- **`--append` em caso com parte semântica**: a instalação usa o modelo registrado no caso. Se a
  impressão digital da fonte atual não bater, a task se desliga para aquele processamento, com
  explicação no log e no registro, e o resto segue (caso de borda da spec).
- **`--append` em caso sem parte semântica**: o registro é criado agora. Itens antigos ficam sem
  documento de estado e saem com o motivo derivado "processado antes da capacidade" (D12). O
  acréscimo não os prepara.
- **Diferença entre FR-042 e este caso**: fonte **ausente** impede o início; fonte **presente mas
  diferente** num acréscimo só desliga a parte semântica. A diferença é deliberada: no primeiro
  caso, a configuração pediu algo impossível; no segundo, o caso já tem uma escolha registrada que
  não pode ser misturada.

---

## D17 — Empacotamento e terceiros

**Decisão**:
- `onnxruntime` e `onnxruntime-extensions` em `lib/`, com versões fixadas após o spike de D2. Os
  nativos vêm dentro dos jars. A extração vai para `tools/onnxruntime/` (propriedade
  `onnxruntime.native.path`), e não para a pasta temporária do sistema, pelo mesmo motivo que as
  demais ferramentas moram em `tools/`.
- Modelo de texto: artefato `zip` no repositório Maven do IPED, desempacotado em
  `models/semantic/text/<id>/` pela mesma mecânica do Vosk. Dentro vão ONNX, `model.json`
  (identidade, dimensões, prefixos, sondas) e licença.
- Modelo visual: pacote `zip` separado, **fora** do release, extraído em
  `models/semantic/visual/<id>/`. As instruções de instalação ficam no manual.
- `ThirdParty.txt` e `licenses/` recebem ONNX Runtime (MIT), ONNX Runtime Extensions (MIT) e os
  modelos.

---

## D18 — Conjunto de avaliação

**Decisão**: construído dentro da entrega, **sem evidência real**:
- Conversas de chat escritas pela equipe em PT, com gíria, abreviação e código, empacotadas num
  banco de chat que o parser da árvore lê. Documentos PT, EN e ES. Áudios curtos com transcrição.
- Imagens e vídeos de bancos de licença aberta, com conteúdo conhecido.
- Perguntas e itens pertinentes anotados **antes** da primeira medição e versionados. Para texto,
  cada pergunta é conferida contra a busca por palavra, e é descartada se ela achar os
  pertinentes (condição de SC-001).
- Ao menos 15 perguntas que misturam conceito e termo específico (apelido, codinome, lugar,
  jargão). Para estas, a condição de SC-001 não vale: a busca por palavra pode achar parte dos
  pertinentes, e é justamente a combinação que se mede (SC-018).
- Um harness de teste (`iped-semantic`, ativado por `-Diped.semantic.test.evalCase`) calcula
  SC-001 a SC-003, SC-012, SC-014 e SC-018 (nos dois modos), e a revocação do aproximado contra o
  exato.

---

## D19 — Perfis de fábrica (pendência adiada na clarificação)

**Decisão**: nesta entrega, **nenhum perfil distribuído liga a capacidade**. Decidir quais perfis
ligam o quê exige os números de SC-010, SC-013 e SC-015, que só existem depois da primeira medição.
Isso vira a tarefa final do plano de tarefas, com a medição anexada.

---

## D20 — Modo híbrido (FR-046, FR-047, SC-018)

**Decisão**: relevância por palavra **dentro do índice semântico de texto**, por trecho e por
sequência, combinada com a ordenação semântica por **Reciprocal Rank Fusion** (RRF).

- **Onde**: o campo `text` dos trechos e o texto das linhas das sequências, que o índice semântico
  já guarda (D4), passam a ser também **indexados**. O analisador é o `AppAnalyzer` do IPED, usado
  como está (não modificado): a folga de acentos e de caixa fica igual à da busca por palavra que
  o perito conhece ("ação" casa "acao"). A similaridade é `BM25Similarity` (k1 = 1,2, b = 0,75),
  configurada **só** no escritor e no buscador do índice semântico.
- **Pergunta**: o texto da pergunta vira termos com escape. Não é interpretado como expressão do
  IPED; é uma disjunção de termos, com a IDF do BM25 fazendo o trabalho de rebaixar as palavras
  comuns.
- **Recorte e unidade**: o mesmo filtro de ids de D5, sobre os mesmos documentos (trecho,
  transcrição, sequência). As duas listas falam da mesma unidade, e é isso que torna a fusão por
  posição legítima.
- **Fusão**: para cada unidade, `Σ 1/(rrfK + posição)` sobre as duas listas, com `rrfK = 60` e cada
  lista truncada em `lexicalCandidates` (padrão = teto × sobreamostragem). O empate é desfeito como
  em D5. Depois vem a mesma consolidação de D5 (por item, por sequência).
- **Declaração**: `mode: hybrid` e o método da parte semântica (`exact`/`approximate`). Se a parte
  por palavra não trouxer nada, o resultado declara `lexical_matches: 0`, e a ordenação é a
  semântica (caso de borda da spec).
- **Modo padrão**: `defaultTextMode` em configuração. O de fábrica será o que vencer no SC-018,
  medido no conjunto de avaliação. Começa como `hybrid`.

**Por quê**:
- *Por posição, e não por pontuação*: BM25 e cosseno não são comparáveis. Somá-los exigiria
  normalização calibrada por caso e por pergunta, que é exatamente o raciocínio que levou a spec a
  proibir misturar proximidades (FR-034). O RRF não precisa de calibração e é determinístico.
- *No índice semântico, e não no principal*: a pontuação existente é por item e por frequência;
  trocá-la mudaria a busca por palavra em todo caso aberto (Princípio II). E BM25 por item não
  serve a RAG, que consome trecho.

**Descartado**:
- *Combinação linear de pontuações* — calibração frágil e não reproduzível entre casos.
- *BM25 sobre o índice principal* (trocando a similaridade no buscador) — pontua o item inteiro;
  além disso, as normas do campo de conteúdo foram gravadas sob a similaridade atual.
- *Reordenar com modelo de reclassificação ou com LLM* — fora de escopo pela spec, e reintroduz
  um modelo cuja saída não é reproduzível na forma que FR-015 exige.

**Custo**: o índice de texto cresce com os postings dos trechos, na ordem do tamanho do próprio
texto armazenado. Medido no Cenário 3 do quickstart.

---

## D21 — RAG no harness, e o que o IPED entrega a ele (FR-040, FR-048)

**Decisão**: o IPED é o **recuperador**. A geração acontece no harness que consome o MCP. O IPED
não recebe credencial de LLM, não monta prompt e não guarda resposta gerada. Para que o RAG feito
fora seja bom e verificável, o MCP entrega:

1. **Citação em cada entrada**: `item_id` e, em texto, `char_start`/`char_end` do trecho — ou os
   `item_id` de cada mensagem da sequência. Toda frase de uma resposta pode ser ligada a um
   intervalo literal do caso.
2. **Contexto sob demanda**: ferramenta nova `iped_semantic_context`, que devolve o texto literal
   em volta de uma entrada — os trechos vizinhos, remendados sem a sobreposição pelos
   deslocamentos, ou as mensagens anteriores e seguintes da conversa —, com teto de caracteres e
   classe `text` (egresso). Ela lê do índice semântico, que já guarda o texto dos trechos, e por
   isso não reextrai o item nem toca a `iped_item_text`.
3. **Orientação**: a skill ganha a disciplina de RAG. Cada afirmação cita item e trecho; a busca
   semântica serve para achar; a busca por palavra serve para afirmar ausência; e a resposta
   gerada nunca é apresentada como conteúdo do caso.

**Descartado**:
- *IPED chamando o LLM* — decidido com o usuário como outra feature, eventual, construída sobre
  esta.
- *Acrescentar intervalo à `iped_item_text`* — mudaria ferramenta existente. E reextrair texto de
  item é caro (`SnippetBuilder` já trabalha sob orçamento por isso), enquanto o trecho já está
  armazenado.

---

## Riscos

| Risco | Efeito | Mitigação |
|---|---|---|
| Falha nativa do ONNX Runtime derrubar a JVM do processamento | Processamento interrompido | `--continue` retoma. Se ocorrer na bancada, mover a inferência para processo filho (D1, alternativa descartada por ora) |
| Extensões sem baseline Java 11 | D2 cai | Plano B: tokenizador em Java puro (WordPiece/BPE); SentencePiece por implementação própria com teste de paridade contra o tokenizador de referência |
| Custo acima das metas só com CPU | SC-010/013 reprovados | Modelo alternativo, int8, `maxTextCharsPerItem` menor. Se nada bastar, volta à spec |
| HNSW com revocação baixa em recortes grandes | Item pertinente fora da lista | Medir (Cenário 9). Subir sobreamostragem ou `beamWidth` do codec próprio do índice semântico |
| Instante de quadro muito impreciso | Perito não acha o trecho do vídeo | Declarado como aproximado (A2). Melhoria separada no `VideoThumbTask` |
| Híbrido pior que semântico em perguntas só conceituais | Modo padrão errado | SC-018 exige no máximo 5 pontos de perda; o padrão de fábrica sai da medição (D20) |
| Viés de idioma do modelo de texto (observado em 2026-09-26) | Item em outro idioma abaixo de itens irrelevantes do idioma da pergunta, o que ameaça SC-002 | No fp32 de referência, "entrega de mercadoria e pagamento combinado" dá 0,801 ao trecho pertinente em inglês e 0,834 a uma receita de bolo em português; o int8 reproduz a ordem. Entre trechos do mesmo idioma, o pertinente vence. SC-002 é medido no conjunto de avaliação (T022); se reprovar, o alternativo de D3 entra na comparação |
