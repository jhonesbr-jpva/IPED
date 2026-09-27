# Feature Specification: Busca semântica sobre os artefatos interpretados pelo IPED

**Feature Branch**: `009-semantic-search`

**Created**: 2026-09-26

**Status**: Implementada, com validação pendente na estação de referência (2026-09-27). Evidência em [validation-log.md](validation-log.md): SC-001 92,7%, SC-002 91,7%, SC-003 79,2%, SC-004 dentro dos limites em índice sintético de 1 M, SC-005/006/007/009/011/016/017 conferidos, SC-012 100%, SC-014 87,5%, SC-018 100% sem perda. **Pendentes**: SC-008, SC-010, SC-013 e SC-015 na estação de referência (perfil forense), a decisão de perfis de fábrica que depende deles (research D19), a calibração de `fingerprintTolerance` entre duas CPUs, e o cenário 2 da História 1 em 4 de 6 perguntas (limite do modelo de texto, research D23). Clarificações de escopo resolvidas e emendas A1–A4 do plano aceitas em 2026-09-26 (ver [Clarifications](#clarifications))

**Input**: User description: "Vamos adicionar ao IPED a capacidade de fazer busca semântica nos artefatos por ele interpretados."

---

## Contexto e problema

O IPED interpreta a evidência: extrai o texto de documentos, separa mensagens de chat e e-mails, reconhece texto em imagens, transcreve áudio, gera miniaturas das imagens. Tudo isso termina num índice que se consulta **por palavra**. A busca por palavra é exata, exaustiva e conferível — e só encontra o que se sabe nomear.

A pergunta que o perito traz raramente é uma palavra. É "onde se combina pagamento", "quem ameaça quem", "há menção a entrega de mercadoria", "conversas sobre sair do país". O texto que responde a essas perguntas está escrito na língua de quem o escreveu: gíria, abreviação, erro de digitação, eufemismo, código combinado, outro idioma, transcrição imperfeita de áudio. Entre a pergunta e a resposta não há palavra em comum, e a busca por palavra, correta como é, devolve zero.

Com imagens e vídeos é pior: não há palavra nenhuma. Uma foto de documento de identidade sobre a mesa, uma arma, um comprovante bancário fotografado só são encontrados por quem folheia a galeria, por um classificador que já exista para aquela classe de conteúdo, ou partindo de outra imagem parecida que alguém já tenha achado. Descrever em palavras o que se procura não leva a lugar nenhum.

Hoje isso se contorna de três formas, todas caras:

1. **Listas de palavras-chave** que o perito acumula por experiência — boas para o que já se viu, cegas para o que ainda não se viu.
2. **Leitura e navegação exaustivas** — viáveis em mil mensagens ou mil imagens, inviáveis em trezentas mil.
3. **Tentativa e erro com sinônimos** — que o agente conectado ao caso também faz, gastando contexto em rodadas de palpite, e que termina num "nada encontrado" facilmente lido como "não existe".

O IPED já ordena por proximidade em outras formas: **imagens semelhantes** e **faces semelhantes** partem de uma referência e devolvem os itens do caso ordenados por quão perto estão dela. Falta o equivalente para o **significado** — do texto e do conteúdo visual —, com a referência formulada em linguagem natural, ou apontada num item que o perito já achou.

### O que muda de natureza

A busca por palavra responde com um **conjunto**: o item casa ou não casa, o total é exato, e zero ocorrências é uma afirmação verificável sobre o texto. A busca semântica responde com uma **ordenação**: todo item tem alguma proximidade com qualquer pergunta, e não existe fronteira natural entre "encontrado" e "não encontrado". Três consequências precisam sobreviver à implementação, porque o material sustenta decisão judicial:

- **Ordenação não é conjunto.** Tudo que hoje trata resultado como conjunto — contagem total, "nenhum resultado", exportar "todas as ocorrências" — mente se aplicado a uma ordenação. O resultado semântico precisa se declarar como o que é: os mais próximos, até uma profundidade declarada, de um universo declarado.
- **Ausência de resultado semântico não é ausência de evidência.** Ela diz, no máximo, que nada ficou perto o bastante segundo um modelo. Não pode ser apresentada, nem lida, como prova de que o assunto não aparece no caso.
- **O achado é o conteúdo, não a pontuação.** Um resultado semântico pode não ter nenhuma relação aparente com a pergunta. O perito só consegue validá-lo examinando o que o aproximou: para texto, o trecho — que tem que ser literalmente do item, nunca um resumo ou paráfrase produzida pelo sistema; para conteúdo visual, a própria imagem ou o próprio quadro do vídeo — nunca uma descrição gerada que o perito leria no lugar de olhar. O sistema **propõe**; quem afirma é o perito, depois de examinar.

Há uma quarta, que a busca por palavra nunca teve: **dependência do modelo.** O mesmo caso, com outro modelo, dá outra ordenação. Um resultado que não se reproduz não serve a quem precisa reapresentá-lo, anos depois, a quem o contesta. A identidade do que produziu a ordenação precisa ficar registrada no caso.

E vale dizer o que esta feature **não** é: ela não responde perguntas sobre o caso, não resume, não traduz, não descreve imagens e não conversa. Ela é a **camada de recuperação**: ordena itens e mostra o conteúdo literal deles. Responder perguntas sobre o caso com um modelo de linguagem — o que se costuma chamar de RAG — acontece **fora do IPED**, num harness que consome estas buscas pelo agente. É por isso que o trecho literal e a citação por item são requisito, e não detalhe: são o que torna verificável, frase a frase, a resposta que um modelo compuser. Um painel de conversa com a evidência é outro produto, que pode vir a se apoiar nesta capacidade, mas não está aqui.

---

## Clarifications

### Session 2026-09-26

- Q: Em quais superfícies a busca semântica fica disponível nesta entrega? → A: Na interface de análise **e** para o agente conectado ao caso (servidor MCP). A capacidade reside no caso; as duas superfícies consomem a mesma coisa e produzem a mesma ordenação (FR-028, SC-011).
- Q: A busca cobre só texto ou também imagens por descrição em palavras? → A: Texto **e** imagens por descrição, **com opção de desligar a busca semântica em imagens** independentemente da de texto (FR-029, FR-032).
- Q: Casos processados antes desta capacidade podem ganhá-la sem reprocessar? → A: **Não.** Só casos processados com a capacidade ligada a têm; enriquecimento de caso existente fica fora desta entrega (FR-030).
- Q: Nos chats, cada mensagem é comparada sozinha ou junto com as vizinhas da mesma conversa? → A: **Sequência de conversa, ancorada nas mensagens** — mensagens consecutivas do mesmo chat são comparadas juntas; o resultado exibe a sequência literal e aponta as mensagens que a compõem, cada uma abrível e marcável (FR-005, FR-009).
- Q: Onde a lista de resultados semânticos termina? → A: **Profundidade padrão, extensível por pergunta até um teto** — ambos definidos pela instalação; o agente pagina até o mesmo teto. Não há piso de proximidade (FR-007, FR-019).
- Q: A busca por descrição cobre também vídeos? → A: **Sim, pelos quadros que o processamento já extrai para a miniatura de vídeo, com chave própria**, independente da de imagens. Imagens e vídeos formam a **modalidade visual**; o texto, a **modalidade texto** (FR-029, FR-032).
- Q: Os modelos vêm no pacote de instalação ou em pacote separado? → A: **Modelo de texto no pacote de instalação, modelo visual em pacote separado — e, para qualquer modalidade, opção de usar modelo remoto** no lugar do local, declarado pela instalação (FR-024, FR-041 a FR-045).
- Q: A recuperação combina busca por palavra com vetores, e onde fica o modelo de linguagem que responde (RAG)? → A: **Modo híbrido na modalidade texto** — a ordenação por palavra dos trechos e a ordenação por significado, combinadas pela posição de cada trecho nas duas — e **a geração fica fora do IPED**: o RAG é feito pelo harness que consome o MCP, e o IPED não chama modelo de linguagem (FR-046 a FR-048, SC-018).
- Q: (emenda A1, do plano) A lista de motivos de não cobertura cobre tudo o que o desenho produz? → A: **Não; acrescentados** "categoria não selecionada", "coberto pelas mensagens", "coberto pelo vídeo de origem", "sem miniatura" e "modelo remoto indisponível" (FR-008).
- Q: (emenda A2) O instante do quadro de vídeo é exato? → A: **Não; é aproximado e declarado como tal** — o IPED não grava o instante dos quadros, e ele é derivado da duração e do passo de extração (FR-035, FR-039, SC-006).
- Q: (emenda A3) O resultado diz se a busca foi exata? → A: **Sim; o método — exato ou aproximado — é declarado** junto da profundidade (FR-007, SC-006).
- Q: (emenda A4) Perguntas exigem o mesmo serviço que preparou o caso? → A: **Não; exigem o mesmo modelo, confirmado a cada sessão, qualquer que seja a fonte** — local ou remota. Consequência aceita junto: o destino das perguntas passa a ser declarado em cada resultado, e não no registro do caso (FR-024, FR-044).

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - O perito encontra pelo significado o que a busca por palavra não acha (Priority: P1)

O perito descreve, em linguagem natural, o que procura — "combinação de pagamento em dinheiro vivo", "ameaça de morte", "pedido para apagar mensagens" — e recebe os itens do caso ordenados pela proximidade de significado, cada um com o trecho que o aproximou da pergunta. Abre o item a partir do trecho, lê, e decide.

**Why this priority**: é o núcleo da capacidade. As demais histórias a restringem, a tornam verificável, a estendem ao conteúdo visual ou a levam ao agente.

**Independent Test**: num caso de avaliação com perguntas e itens pertinentes anotados de antemão por perito — escolhidos de modo que a busca por palavra, com os termos da pergunta, não os encontre —, formular cada pergunta e verificar em que posição os itens pertinentes aparecem.

**Acceptance Scenarios**:

1. **Given** um caso processado com a capacidade habilitada e uma mensagem que diz "deixa o pacote com o moleque da portaria, o acerto é amanhã", **When** o perito pergunta por "entrega de mercadoria e pagamento combinado", **Then** a mensagem aparece entre os primeiros resultados, com esse trecho exibido.
2. **Given** um documento de quarenta páginas em que um único parágrafo trata do assunto perguntado, **When** o perito faz a pergunta, **Then** o documento aparece e o trecho exibido é aquele parágrafo, não o início do documento.
3. **Given** um item escrito em inglês, **When** o perito pergunta em português sobre o assunto dele, **Then** o item aparece na ordenação.
4. **Given** um resultado qualquer, **When** o perito o abre, **Then** o item é apresentado com o trecho exibido localizado e destacado.
5. **Given** um chat em que "quanto?", "5 mil" e "fechado, amanhã na portaria" são mensagens separadas e consecutivas, **When** o perito pergunta por "combinação de preço e entrega", **Then** a sequência aparece entre os primeiros resultados, exibida literalmente, com as três mensagens apontadas e cada uma abrível e marcável.

---

### User Story 2 - O resultado diz o que é, e se reproduz (Priority: P1)

Ao ver uma ordenação semântica, o perito sabe que está diante dos mais próximos até uma profundidade declarada — não de todas as ocorrências —, sabe quantos itens do recorte ficaram fora do alcance da busca e por quê, e sabe qual modelo produziu a ordenação. Repetir a pergunta amanhã, no mesmo caso, dá a mesma lista.

**Why this priority**: também P1, e não P2, porque sem isto as demais histórias produzem achados que não se sustentam. Uma ordenação apresentada como conjunto induz conclusão de ausência; uma ordenação irreproduzível não pode ser reapresentada.

**Independent Test**: executar a mesma pergunta duas vezes, fechando e reabrindo o caso entre elas, e comparar as listas; inspecionar o resultado em busca de contagem total, de "nenhum resultado" e da declaração de profundidade, cobertura e modelo.

**Acceptance Scenarios**:

1. **Given** uma pergunta qualquer, **When** o resultado é exibido, **Then** ele declara a profundidade da ordenação, se a busca foi exata ou aproximada, quantos itens do recorte estavam cobertos e quantos não — com os motivos agrupados — e qual modelo o produziu.
2. **Given** a mesma pergunta, o mesmo caso e a mesma configuração, **When** a pergunta é repetida após reabrir o caso, **Then** a ordenação é idêntica.
3. **Given** uma pergunta sobre assunto ausente do caso, **When** o resultado é exibido, **Then** ele não afirma que o assunto não existe: mostra os mais próximos disponíveis, com a proximidade relativa, e nada nele se lê como "zero ocorrências".
4. **Given** um item do recorte que não pôde ser preparado para a busca semântica, **When** a pergunta é feita, **Then** o item entra na contagem de não cobertos, com o motivo, e não desaparece em silêncio.
5. **Given** um processamento concluído com a capacidade ligada, **When** o perito consulta o registro do processamento, **Then** encontra, por modalidade, quantos itens foram cobertos, quantos não e por quê, e quanto tempo a preparação semântica consumiu.
6. **Given** uma ordenação exibida na profundidade padrão, **When** o perito pede mais resultados, **Then** a ordenação se estende até o teto configurado, mantendo a ordem já exibida, e a profundidade declarada passa a ser a nova.
7. **Given** um caso processado antes desta capacidade existir, **When** o perito o abre, **Then** a busca semântica se declara indisponível, com o motivo e o caminho para obtê-la — reprocessar com a capacidade ligada —, e a busca por palavra funciona exatamente como antes.

---

### User Story 3 - A busca semântica respeita o recorte que o perito já montou (Priority: P2)

O perito já sabe onde olhar: o celular do investigado, as conversas entre março e maio, os itens de um marcador, o resultado de uma busca por palavra. Ele aplica a pergunta semântica **dentro** desse recorte, com os filtros que já usa, e a ordenação considera apenas o que está nele.

**Why this priority**: multiplica o valor da História 1 — "combinação de pagamento" no caso inteiro tem ruído; restrita às conversas de uma pessoa num período, vira investigação. Mas a História 1 já entrega valor sem ela.

**Independent Test**: aplicar uma pergunta com e sem um filtro de categoria e de período, e verificar que, com o filtro, nenhum item de fora aparece e a cobertura declarada se refere ao recorte.

**Acceptance Scenarios**:

1. **Given** um recorte por busca por palavra, categoria, marcador, período ou evidência de origem, **When** o perito faz a pergunta semântica, **Then** só itens do recorte aparecem na ordenação.
2. **Given** um recorte vazio, **When** o perito faz a pergunta, **Then** o resultado diz que o recorte está vazio — e não que a busca semântica não achou nada.

---

### User Story 4 - "Mais como este" (Priority: P2)

O perito encontrou uma mensagem que importa — uma ameaça, uma combinação de preço. Em vez de adivinhar como descrevê-la, parte dela, ou de um trecho que seleciona nela, e pede os itens de significado mais próximo, como já faz com imagens e faces semelhantes.

**Why this priority**: é a forma mais natural de uso depois do primeiro achado, e a que menos depende de o perito saber formular a pergunta. Apoia-se no mesmo alicerce da História 1.

**Independent Test**: a partir de um item pertinente do caso de avaliação, pedir os mais próximos e verificar se outros itens pertinentes ao mesmo assunto aparecem entre os primeiros.

**Acceptance Scenarios**:

1. **Given** um item com texto coberto, **When** o perito pede "mais como este", **Then** recebe a ordenação dos itens de significado mais próximo, sem o próprio item de referência.
2. **Given** um trecho selecionado dentro de um item, **When** o perito pede "mais como este", **Then** a referência é o trecho, e não o item inteiro.
3. **Given** um item sem texto coberto, **When** o perito pede "mais como este", **Then** a recusa diz por que o item não pode servir de referência.

---

### User Story 5 - O agente usa a mesma busca, com a mesma disciplina (Priority: P2)

O agente conectado ao caso formula perguntas semânticas — sobre texto e sobre conteúdo visual — e recebe a mesma ordenação que o perito receberia na interface para a mesma pergunta e o mesmo recorte, com trechos, profundidade, cobertura e modelo declarados, sob a trilha de auditoria e a política de egresso já existentes. A orientação que o agente carrega passa a ensinar quando usar busca por significado e quando usar busca por palavra.

**Why this priority**: reduz o ciclo de palpite de palavras-chave que hoje consome o contexto do agente e termina em falso "nada encontrado". Vem depois das Histórias 1 e 2 porque consome exatamente o que elas constroem.

**Independent Test**: fazer a mesma pergunta, com o mesmo recorte, pela interface e pelo agente, e comparar as ordenações; verificar o registro da chamada na trilha e o comportamento sob política de egresso que barra texto e miniatura.

**Acceptance Scenarios**:

1. **Given** a mesma pergunta e o mesmo recorte, **When** feitas pela interface e pelo agente, **Then** as ordenações coincidem.
2. **Given** uma política de egresso que barra texto de item, **When** o agente faz uma pergunta sobre texto, **Then** recebe a ordenação sem os trechos, com a recusa declarada, e os trechos não chegam a ele por nenhum outro caminho.
3. **Given** uma pergunta dirigida à modalidade visual, **When** o agente recebe a ordenação, **Then** ela identifica imagens e vídeos sem carregá-los — no caso de vídeo, com o instante aproximado do quadro que o aproximou —, e examinar cada um passa pela entrega de miniatura já existente, sob a mesma política de egresso.
4. **Given** uma pergunta cujos resultados são todos de baixa proximidade, **When** o agente recebe a resposta, **Then** a própria resposta traz a advertência de que se trata de ordenação e não de conjunto, e de que baixa proximidade não é ausência do assunto no caso.

---

### User Story 6 - Imagens e vídeos encontrados por descrição em palavras (Priority: P3)

O perito descreve em palavras o que procura — "documento de identidade fotografado sobre a mesa", "arma de fogo", "print de comprovante bancário" — e recebe as imagens e os vídeos do caso ordenados por proximidade com a descrição: cada imagem pela sua miniatura, cada vídeo pelo quadro que o aproximou e o instante aproximado em que ele aparece. Quando o custo não compensa, ou o perfil não pede, a instalação desliga imagens, vídeos ou os dois, e mantém a busca de texto.

**Why this priority**: estende a capacidade ao volume visual do caso, onde hoje descrever em palavras não leva a nada. Fica depois das demais porque tem custo de processamento próprio e uma forma de verificação diferente — o perito valida olhando a imagem, não lendo um trecho —, e porque a modalidade texto sozinha já entrega valor. As chaves próprias — uma para imagens, outra para vídeos — existem para que esse custo seja uma escolha da instalação.

**Independent Test**: num caso com imagens e vídeos de conteúdo conhecido, descrever em palavras um conteúdo presente e verificar a posição dos itens correspondentes; repetir o processamento com vídeos desligados, e depois com imagens desligadas, e verificar que cada chave afeta apenas o que governa e que a busca de texto não muda.

**Acceptance Scenarios**:

1. **Given** um caso com imagens de conteúdo conhecido, **When** o perito descreve em português um desses conteúdos, **Then** as imagens correspondentes aparecem entre as primeiras, cada uma com sua miniatura.
2. **Given** um resultado de imagem ou de vídeo, **When** exibido, **Then** o que o justifica é a própria imagem ou um quadro extraído do próprio vídeo, e nenhuma descrição gerada pelo sistema aparece como se fosse conteúdo dele.
3. **Given** um caso processado com a modalidade visual desligada, **When** o perito dirige uma pergunta a ela, **Then** a busca se declara indisponível para esse caso, com o motivo, e a busca de texto funciona normalmente.
4. **Given** uma pergunta dirigida à modalidade texto e à visual ao mesmo tempo, **When** o resultado é exibido, **Then** há duas ordenações separadas, cada uma com sua profundidade, sua cobertura e seu modelo — e nenhuma lista única que misture as duas.
5. **Given** o desfoque da galeria ligado, **When** resultados visuais são exibidos, **Then** miniaturas e quadros aparecem desfocados, como em qualquer outra exibição de imagem na interface.
6. **Given** um vídeo em que o conteúdo descrito aparece num dos quadros extraídos, **When** o perito faz a descrição, **Then** o vídeo aparece uma única vez na ordenação visual, com o quadro que o aproximou e o instante dele, marcado como aproximado.
7. **Given** um caso processado com imagens ligadas e vídeos desligados, **When** o perito faz uma descrição, **Then** a ordenação visual traz só imagens, e a cobertura declara os vídeos como não cobertos, com o motivo.

---

### User Story 7 - A pergunta mistura conceito e termo exato (Priority: P2)

O perito pergunta "pagamento combinado com o Baiano" ou "entrega do material na chácara do 18". Parte da pergunta é conceito e parte é termo que precisa aparecer do jeito que está: apelido, codinome, nome de lugar, jargão. No modo híbrido, os trechos que falam do assunto **e** trazem o termo sobem juntos; no modo semântico puro, o apelido pesaria tanto quanto qualquer outra palavra.

**Why this priority**: é o formato mais comum de pergunta real em investigação, e é também o que um modelo de linguagem, montando contexto para responder, mais precisa recuperar bem. Depende do alicerce da História 1 e não o substitui.

**Independent Test**: no conjunto de avaliação, formular as perguntas que misturam conceito e termo específico nos dois modos e comparar em que posição aparecem os itens pertinentes.

**Acceptance Scenarios**:

1. **Given** mensagens que tratam de pagamento, só algumas mencionando "Baiano", **When** o perito pergunta no modo híbrido por "pagamento combinado com o Baiano", **Then** as sequências que tratam de pagamento e mencionam o apelido aparecem antes das que tratam só de pagamento.
2. **Given** uma pergunta qualquer, **When** o resultado é exibido, **Then** ele declara em que modo foi produzido — semântico ou híbrido.
3. **Given** um trecho da ordenação, **When** o agente pede o contexto em volta dele, **Then** recebe o texto literal vizinho — trechos anteriores e seguintes, ou as mensagens em volta — sem trazer o item inteiro.

---

### Edge Cases

- **Item sem texto** (imagem sem OCR, binário, vídeo sem transcrição): fica fora da ordenação de texto e entra na contagem de não cobertos dela, com o motivo. Nunca é apresentado como "irrelevante para a pergunta".
- **Texto além do limite configurado por item**: coberto até o limite; o excedente é declarado na cobertura do item. Um trecho pertinente além do limite não será encontrado, e isso precisa ser visível.
- **Falha ao preparar um item** (texto corrompido, imagem que não decodifica, esgotamento de tempo, erro do componente que calcula significado): o processamento do caso segue, os demais itens não são afetados, e o item entra como não coberto, com o motivo.
- **Texto ruidoso** (OCR de baixa qualidade, transcrição com erros): é coberto como está. A cobertura declara que o item foi incluído, não que a inclusão tenha sido boa.
- **Imagens muito pequenas** (ícones, emojis, elementos de interface): abaixo do tamanho mínimo configurado, ficam fora da ordenação visual, e a exclusão aparece na cobertura com esse motivo.
- **Imagem que também tem texto** (captura de tela, foto de documento): pode aparecer na ordenação de texto, pelo OCR, e na visual. São ordenações separadas, e a presença nas duas não é duplicidade.
- **Vídeos só são vistos pelos quadros extraídos**: entram na ordenação visual pelos quadros que o processamento extrai para a miniatura de vídeo, e só por eles. Conteúdo que não aparece em nenhum quadro extraído não será encontrado, e a cobertura declara que o vídeo foi coberto por amostragem de quadros — nunca como vídeo examinado por inteiro. A transcrição do áudio, se houver, é coberta como texto.
- **Vídeos ligados sem quadros extraídos** (perfil que não gera miniatura de vídeo): os vídeos entram como não cobertos, com o motivo, e o registro do processamento aponta o conflito de configuração.
- **Pergunta que é um identificador** (CPF, CNPJ, telefone, e-mail, número de conta — formas reconhecíveis pela própria grafia): a busca por palavra é a ferramenta exata para isso. A busca semântica executa, mas o resultado sugere a busca por palavra e não se oferece como substituta. Nome próprio não tem grafia que o distinga de uma palavra comum e não é detectado; para ele, vale a orientação geral de usar a busca por palavra ao procurar um nome exato.
- **Pedido de profundidade além do teto**: recusado com o teto em vigor e a configuração que o governa; a ordenação até o teto continua disponível. O teto não é piso de relevância, e a recusa não pode sugerir que além dele não haja nada pertinente.
- **Modo híbrido sem nenhuma correspondência por palavra**: a parte por palavra não traz nada, a ordenação resultante é a semântica, e o resultado declara isso — não finge que a combinação aconteceu.
- **Identificador no modo híbrido**: a parte por palavra pode trazer o identificador exato para o topo, mas o resultado continua sendo ordenação com profundidade. Afirmar que um identificador **não** aparece no caso continua sendo papel da busca por palavra existente.
- **Pergunta muito longa** (um parágrafo colado): aceita até um limite; se for truncada, isso é declarado.
- **Cópias idênticas de um mesmo conteúdo** (mesmo arquivo em vários lugares): aparecem com a mesma proximidade; o filtro de duplicados que já existe continua valendo.
- **Achado num subitem** (anexo de e-mail, arquivo dentro de compactado, imagem embutida em documento): o resultado é o subitem, e a hierarquia até o item pai continua navegável como hoje.
- **Mensagens de chat não extraídas como itens** (perfis que processam o chat como um todo): o chat é pesquisável por sequências dentro do seu texto, e o resultado aponta a posição da sequência dentro dele.
- **Sequência de conversa parcialmente dentro do recorte** (um filtro de período corta a conversa ao meio): a sequência entra na ordenação se ao menos uma de suas mensagens estiver no recorte. As mensagens de fora são exibidas como contexto, marcadas visivelmente como fora do recorte, e não contam como resultado nem são marcadas junto.
- **Mensagem curta isolada** ("ok", "blz", um emoji): não é comparada sozinha; entra pela sequência a que pertence.
- **Itens apagados, recuperados por carving ou de espaço não alocado**: entram se tiverem texto, imagem decodificável ou quadros de vídeo extraídos, como qualquer item.
- **Caso aberto numa estação sem o modelo com que foi construído**: a busca semântica daquela modalidade se declara indisponível e diz qual modelo falta; a busca por palavra não é afetada.
- **Instalação atualizada com outro modelo**: casos antigos continuam vinculados ao modelo com que foram construídos. Buscar com modelo diferente é recusado com explicação — nunca executado em silêncio com resultado degradado.
- **Vários casos abertos juntos com modelos diferentes, ou com modalidades diferentes ligadas**: a busca cobre apenas os casos compatíveis com a pergunta e declara quais ficaram de fora e por quê.
- **Processamento retomado**: os itens restantes são preparados com o mesmo modelo, sem contagem em dobro e sem lacuna não declarada.
- **Evidência acrescentada a caso existente**: os itens novos são preparados com o modelo do caso. Se o caso foi processado sem a capacidade, os itens antigos continuam não cobertos — aparecem na cobertura com o motivo "processados antes da capacidade" — e o acréscimo não é caminho para enriquecê-los. Se o modelo da instalação diferir do modelo do caso, a parte semântica do acréscimo é recusada com explicação, e o restante do processamento segue.
- **Modelo remoto fora do ar ou limitando requisições durante o processamento**: os itens afetados entram como não cobertos, com o motivo; o processamento segue, e o registro informa quantos itens foram atingidos.
- **Modelo remoto fora do ar na hora da pergunta**: a busca daquela modalidade se declara indisponível, com o motivo. Não há troca automática de fonte: a instalação usa a fonte que configurou. A busca por palavra e a outra modalidade, se tiver fonte própria, não são afetadas.
- **Modelo remoto que passa a responder com outro modelo**: detectado pela identidade informada, e a pergunta é recusada como incompatível com o caso (FR-016, FR-043).
- **Caso preparado com modelo remoto aberto em estação que não alcança o serviço**: se a estação tiver o **mesmo modelo** instalado localmente, confirmado como tal, a busca funciona com ele. Se não tiver, a busca daquela modalidade se declara indisponível, nomeando o modelo que falta. Reabrir o caso anos depois exige que o mesmo modelo continue acessível por alguma fonte — e é por isso que o modelo, e não o serviço, é o que o caso registra como exigência.
- **Mesma pergunta em estações com hardware diferente**: pequenas diferenças numéricas podem alterar a ordem de itens de proximidade quase igual. Onde isso puder ocorrer, a ordenação não pode ser apresentada como idêntica por construção.
- **Texto do item escrito para enganar** (conteúdo que imita instrução ao agente): é dado de evidência, e chega ao agente como trecho de item, nunca como instrução — mesma disciplina que já vale para texto de item.

---

## Requirements *(mandatory)*

### Functional Requirements

**Capacidade e alcance**

- **FR-001**: O sistema MUST permitir formular uma pergunta em linguagem natural e devolver os itens do caso ordenados pela proximidade de significado entre a pergunta e o conteúdo de cada item.
- **FR-002**: A busca semântica de texto MUST cobrir o texto que o IPED extrai ou produz dos itens: conteúdo textual de documentos, corpo de e-mails, mensagens de chat, texto reconhecido por OCR e transcrições de áudio — estas duas últimas quando as respectivas análises tiverem sido executadas no caso.
- **FR-003**: Perguntas e textos MUST ser comparáveis entre idiomas: uma pergunta em português MUST poder encontrar item em inglês, e vice-versa. Português e inglês são o mínimo exigido.
- **FR-004**: Textos longos MUST ser comparados por trecho, e não apenas como um todo, de modo que um documento extenso com um único trecho pertinente seja encontrado.
- **FR-005**: Mensagens de chat MUST ser comparadas em **sequências de conversa** — mensagens consecutivas do mesmo chat, comparadas juntas —, e não isoladamente. O resultado MUST exibir a sequência literal e apontar as mensagens que a compõem, cada uma abrível e marcável; levar o resultado a marcador marca essas mensagens. Quando o processamento não tiver extraído as mensagens como itens, o chat MUST ser pesquisável por sequências dentro do seu texto.

**Resultado**

- **FR-006**: Cada resultado de texto MUST exibir o trecho do item — ou, em chats, a sequência de mensagens — que o aproximou da pergunta, transcrito literalmente, e MUST permitir abrir o item com esse trecho localizado. Nenhum texto gerado, resumido ou parafraseado pelo sistema pode aparecer no lugar do trecho.
- **FR-007**: O resultado MUST se apresentar como ordenação com profundidade limitada e declarada, e MUST declarar o método da busca: **exato** (todos os itens do recorte comparados) ou **aproximado** (reproduzível, mas pode deixar de fora um item que a comparação exaustiva incluiria). A ordenação começa numa profundidade padrão, e o perito MUST poder estendê-la, a cada pergunta, até um teto; o agente MUST poder paginar até o mesmo teto. A ordenação MUST NOT ser cortada por piso de proximidade. MUST NOT exibir total de ocorrências, nem "nenhum resultado", nem qualquer rótulo que o faça ser lido como o conjunto completo de itens sobre o assunto.
- **FR-008**: O resultado MUST declarar a cobertura do recorte: quantos itens estavam ao alcance da busca semântica naquela modalidade e quantos não, com os motivos agrupados (sem texto, texto acima do limite, imagem abaixo do tamanho mínimo, imagem não decodificável, vídeo sem quadros extraídos, sem miniatura, categoria não selecionada, coberto pelas mensagens, coberto pelo vídeo de origem, modelo remoto indisponível, análise não executada, modalidade ou chave desligada no processamento, item processado antes da capacidade, falha no preparo).
- **FR-009**: Um item MUST aparecer no máximo uma vez em cada ordenação, acompanhado do trecho ou dos trechos mais próximos, quando for texto. Sequências de conversa que se sobrepõem MUST ser consolidadas, de modo que nenhuma mensagem apareça em mais de uma entrada da mesma ordenação. Um vídeo MUST aparecer uma única vez na ordenação visual, pelo quadro mais próximo.
- **FR-010**: A proximidade, quando exibida, MUST ser rotulada como proximidade relativa e MUST NOT ser apresentada como probabilidade, confiança ou grau de pertinência.
- **FR-011**: Resultados da busca semântica MUST poder ser levados a marcadores e exportações pelos mecanismos existentes; ao fazê-lo, o perito MUST poder registrar junto a pergunta, o recorte, a profundidade e o modelo que os produziram.

**Recorte e referência**

- **FR-012**: O perito MUST poder restringir a busca semântica a um recorte definido pelos filtros que já existem — busca por palavra, categoria, marcador, período, evidência de origem —, e a ordenação MUST considerar apenas itens desse recorte.
- **FR-013**: O perito MUST poder partir de um item de texto, ou de um trecho selecionado nele, e obter os itens de significado mais próximo sem formular pergunta. O item de referência MUST NOT ocupar a ordenação.

**Reprodutibilidade**

- **FR-014**: O caso MUST registrar, para cada modalidade preparada, a identidade e a versão do modelo, os parâmetros, a data e onde o cálculo foi feito; essa informação MUST ser consultável pelo perito.
- **FR-015**: A mesma pergunta, no mesmo caso, com a mesma configuração e na mesma instalação, MUST produzir a mesma ordenação, inclusive após fechar e reabrir o caso. Empates MUST ser desfeitos de forma determinística.
- **FR-016**: Buscar numa modalidade com modelo diferente daquele com que ela foi construída no caso MUST ser recusado com explicação, nomeando os dois modelos.
- **FR-017**: Com vários casos abertos juntos, a busca semântica MUST cobrir apenas os casos compatíveis com a pergunta e MUST declarar quais ficaram de fora e por quê.

**Configuração e custo**

- **FR-018**: A capacidade MUST vir desligada por padrão e MUST ser habilitável por configuração e por perfil, sem recompilação.
- **FR-019**: A instalação MUST poder escolher, por configuração, quais categorias de item entram na busca semântica, o limite de volume de texto por item, a extensão das sequências de conversa, o tamanho mínimo de imagem, e a profundidade padrão e o teto da ordenação.
- **FR-020**: Com a capacidade desligada, o processamento MUST ter o mesmo comportamento e os mesmos resultados que tem hoje.
- **FR-021**: Ao final do processamento, o registro do processamento MUST informar, por modalidade — e, na visual, separando imagens e vídeos —, quantos itens foram cobertos, quantos não e por quê, e quanto tempo a preparação semântica consumiu.
- **FR-022**: Falha na preparação semântica de um item MUST NOT interromper o processamento do caso nem afetar os demais itens; o item fica registrado como não coberto, com o motivo.

**Confidencialidade**

- **FR-023**: Por padrão, todo o cálculo MUST ocorrer na própria estação, sem acesso à rede, e a capacidade MUST funcionar em estação isolada desde que os modelos estejam instalados.
- **FR-024**: Cada modalidade MUST poder usar, no lugar do modelo local, um **modelo remoto** — um serviço fora da estação que faz o cálculo —, desde que a instalação declare o destino explicitamente na configuração. Não existe destino padrão. O caso MUST registrar que conteúdo foi enviado na preparação, e para onde. Nas perguntas, o destino depende da fonte que a instalação em uso configurou, e por isso MUST ser declarado em cada resultado; a exibição ao perito antes do envio é regida por FR-044.
- **FR-025**: Os dados derivados que a busca semântica guarda MUST residir dentro da pasta do caso e MUST ser tratados como conteúdo de evidência. A representação interna usada para comparar significado MUST NOT ser entregue a nenhum consumidor externo, e o conteúdo devolvido — trechos e miniaturas — MUST obedecer à política de egresso existente, como texto e miniatura de item.

**Integridade e contrato**

- **FR-026**: A capacidade MUST NOT alterar a evidência original, nem os campos, a análise textual ou os resultados da busca por palavra existentes. Ela é estritamente aditiva.
- **FR-027**: Casos processados sem a capacidade MUST continuar abrindo e funcionando exatamente como antes; neles, a busca semântica MUST se declarar indisponível, com o motivo e o caminho para obtê-la.

**Escopo desta entrega**

- **FR-028**: A busca semântica MUST estar disponível na interface de análise do IPED **e** para o agente conectado ao caso (servidor MCP). A capacidade MUST residir no caso e não na superfície: para a mesma pergunta e o mesmo recorte, as duas superfícies MUST produzir a mesma ordenação.
- **FR-029**: A busca semântica MUST cobrir o texto dos itens (FR-002) — a **modalidade texto** — e o conteúdo visual do caso — a **modalidade visual**: imagens, e vídeos pelos quadros que o processamento extrai para a miniatura de vídeo. O perito MUST poder descrever em palavras o conteúdo procurado e receber imagens e vídeos ordenados por proximidade com a descrição.
- **FR-030**: Apenas casos processados com a capacidade ligada têm busca semântica. Acrescentar a busca semântica a um caso já processado, sem reprocessá-lo, está fora desta entrega; para esses casos, o caminho declarado (FR-027) é reprocessar com a capacidade ligada.

**Idioma da interface**

- **FR-031**: Todo texto que a capacidade exibe ao usuário MUST estar localizado, no mínimo, em português e inglês.

**Modalidade visual**

- **FR-032**: Texto, imagens e vídeos MUST ter habilitação própria e independente, em configuração e em perfil. Desligar qualquer um deles MUST eliminar o custo de preparação correspondente no processamento sem alterar em nada os demais.
- **FR-033**: O perito MUST poder dirigir cada pergunta à modalidade texto, à visual ou às duas, dentre as preparadas no caso. Uma pergunta dirigida a uma modalidade não preparada MUST ser declarada indisponível para aquele caso, com o motivo.
- **FR-034**: Quando a pergunta for dirigida às duas modalidades, as ordenações MUST ser apresentadas separadas, cada uma com sua profundidade, sua cobertura e seu modelo. Nenhuma ordenação única pode misturar proximidades de modalidades diferentes.
- **FR-035**: Cada resultado de imagem MUST ser apresentado pela própria imagem (sua miniatura), e cada resultado de vídeo pelo quadro que o aproximou, com o instante **aproximado** em que ele aparece no vídeo, declarado como tal — nunca apresentado como exato. Nenhum resultado visual pode ser acompanhado de descrição gerada pelo sistema apresentada como conteúdo.
- **FR-036**: Perguntas dirigidas à modalidade visual MUST poder ser feitas em português e em inglês.
- **FR-037**: Na interface, a exibição de resultados visuais MUST respeitar as proteções de exibição que a galeria já aplica, incluindo o desfoque global.

**Agente**

- **FR-038**: Toda pergunta semântica feita pelo agente MUST passar pela trilha de auditoria e pela política de egresso existentes, sem exceção criada por esta capacidade.
- **FR-039**: A ordenação visual entregue ao agente MUST identificar imagens e vídeos sem carregá-los — no caso de vídeo, com o instante aproximado do quadro que o aproximou —; examinar um deles MUST passar pela entrega de miniatura existente, sob a mesma política de egresso.
- **FR-040**: A resposta ao agente MUST carregar, nela mesma, a advertência de que se trata de ordenação e não de conjunto, e de que baixa proximidade não é ausência do assunto no caso. A orientação que o agente carrega MUST ensinar quando usar busca por significado e quando usar busca por palavra, e MUST exigir que toda afirmação construída a partir dos resultados cite o item e o trecho de onde veio.

**Modelos: distribuição e fonte**

- **FR-041**: O modelo local de texto MUST ser distribuído no pacote de instalação do IPED. O modelo local visual MUST ser distribuído em pacote separado, que se acrescenta a uma instalação existente sem acesso à rede.
- **FR-042**: Se uma chave estiver ligada e a modalidade dela não tiver fonte de modelo disponível — nem modelo local instalado, nem modelo remoto declarado e alcançável —, o processamento MUST recusar começar, antes de ler a evidência, nomeando o que falta.
- **FR-043**: Um modelo remoto MUST ser aceito apenas se o serviço informar, em suas respostas, a identidade do modelo que as produziu. É essa identidade — e não a que a configuração declara — que o caso registra (FR-014) e que decide a compatibilidade de cada pergunta (FR-016): um serviço que troque de modelo sem aviso é detectado e recusado, nunca usado em silêncio.
- **FR-044**: Responder perguntas sobre o caso MUST exigir **o mesmo modelo** com que a modalidade foi construída, confirmado como o mesmo a cada sessão de busca, **qualquer que seja a fonte** que a instalação em uso configurou — local ou remota. Se a fonte configurada não estiver disponível, ou não se confirmar como o mesmo modelo, a busca daquela modalidade MUST se declarar indisponível, com o motivo, e MUST NOT recorrer a outro modelo. Com fonte remota, a pergunta também é calculada pelo serviço, e a interface MUST deixar visível ao perito, antes de enviar, que a pergunta sairá da estação e para onde.
- **FR-045**: Credenciais de acesso a modelo remoto MUST NOT residir nos arquivos de configuração distribuídos: a configuração diz **onde** encontrá-las, nunca **quais** são.

**Modo híbrido e recuperação para o agente**

- **FR-046**: Na modalidade texto, a ordenação MUST poder ser **semântica** (só significado) ou **híbrida**: a ordenação dos trechos por palavra e a ordenação por significado, combinadas pela **posição** de cada trecho nas duas — nunca pela soma de pontuações de naturezas diferentes. O modo padrão é definido pela instalação. O perito e o agente MUST poder escolher o modo a cada pergunta, e o modo usado MUST ser declarado no resultado.
- **FR-047**: No modo híbrido, a parte por palavra MUST respeitar o mesmo recorte (FR-012) e a mesma unidade — trecho ou sequência de conversa — da parte semântica. A relevância exibida MUST ser rotulada como relevância combinada relativa, sob as mesmas proibições de FR-010. A parte por palavra MUST NOT alterar a ordenação nem os resultados da busca por palavra existente.
- **FR-048**: O agente MUST poder obter o texto literal em torno de um trecho ou sequência da ordenação — trechos ou mensagens vizinhas —, sem trazer o item inteiro, sob a mesma política de egresso que vale para texto de item.

### Key Entities

- **Pergunta semântica**: o que o perito procura, expresso em linguagem natural — ou um item ou trecho de texto tomado como referência. Vem acompanhada de um recorte, de uma profundidade e da modalidade a que se dirige.
- **Modalidade**: texto ou visual. A visual reúne imagens e vídeos, cada um com chave própria de preparação, comparados da mesma forma e por isso na mesma ordenação. Cada modalidade tem modelo, registro de construção e cobertura próprios, e as ordenações de modalidades diferentes nunca se misturam.
- **Trecho**: porção contígua do texto de um item, unidade da comparação na modalidade texto. É sempre literal: o que se exibe como trecho é texto que está no item.
- **Sequência de conversa**: mensagens consecutivas do mesmo chat, comparadas juntas; é a unidade de comparação para chats. É sempre literal e sempre ancorada nas mensagens que a compõem — é por elas que o perito abre, marca e filtra.
- **Representação de significado**: dado derivado de cada trecho, sequência de conversa, imagem ou quadro de vídeo, usado para compará-lo com a pergunta. Mora no caso, é tão sensível quanto o conteúdo de onde veio, e nunca é exibida nem entregue a consumidor externo.
- **Registro de construção**: o que o caso guarda, por modalidade, sobre como sua busca semântica foi construída — modelo e versão, parâmetros, data, onde foi calculada, e a cobertura resultante. É o que torna uma ordenação reapresentável.
- **Modo de ordenação**: semântico ou híbrido, só na modalidade texto. Declarado em todo resultado; o híbrido combina posições, não pontuações.
- **Fonte de modelo**: de onde vem o cálculo de uma modalidade — **modelo local** (do pacote de instalação ou do pacote separado) ou **modelo remoto** (serviço declarado pela instalação). É escolhida por modalidade pela instalação. O caso registra o **modelo** — com a identidade informada por ele — e a fonte usada na preparação. As perguntas podem vir de outra fonte, desde que ela se confirme como o mesmo modelo.
- **Resultado ordenado**: lista de itens de uma modalidade — com o trecho ou os trechos que os aproximaram, no caso de texto, com a miniatura, no caso de imagem, ou com o quadro e seu instante aproximado, no caso de vídeo — e a proximidade relativa, acompanhada da profundidade, da cobertura do recorte e da identidade do modelo.
- **Cobertura**: a contagem de itens do recorte que estavam ao alcance da busca semântica numa modalidade e dos que não estavam, com o motivo de cada exclusão. É a forma de "ausência declarada" que esta capacidade precisa ter.
- **Conjunto de avaliação**: perguntas conceituais e descrições visuais sobre um caso de conteúdo conhecido, com os itens pertinentes a cada uma anotados por perito **antes** da medição — para texto, escolhidos de modo que a busca por palavra com os termos da pergunta não os encontre. Sem ele, os critérios de sucesso de qualidade não são verificáveis.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Num conjunto de avaliação com pelo menos 30 perguntas em português sobre texto, ao menos um item pertinente aparece entre os 10 primeiros resultados em **80% ou mais** das perguntas — sendo que, por construção do conjunto, a busca por palavra não encontra esses itens.
- **SC-002**: Nas perguntas do conjunto de avaliação cujos itens pertinentes estão em outro idioma (pelo menos 10), ao menos um deles aparece entre os 10 primeiros em **70% ou mais** das perguntas.
- **SC-003**: Partindo de um item pertinente do conjunto de avaliação, "mais como este" coloca ao menos um outro item pertinente ao mesmo assunto entre os 10 primeiros em **70% ou mais** das tentativas.
- **SC-004**: Na estação de referência, com modelos locais e sem exigir placa gráfica, o resultado de uma pergunta chega ao perito em **até 5 segundos** num caso com 1 milhão de itens cobertos na modalidade consultada, e em **até 2 segundos** quando restrita a um recorte de até 10 mil itens.
- **SC-005**: A mesma pergunta, repetida no mesmo caso e na mesma instalação — inclusive depois de fechar e reabrir o caso —, produz a mesma ordenação em **100%** das repetições.
- **SC-006**: Em **100%** dos resultados de texto, o trecho exibido está contido literalmente no texto do item, e cada mensagem de uma sequência exibida é idêntica à mensagem apontada; em **100%** dos resultados visuais, o que se exibe é a miniatura da própria imagem ou um quadro extraído do próprio vídeo, com o instante marcado como aproximado, sem descrição gerada; e **100%** das respostas declaram profundidade, método, cobertura, modelo e modo, sem nenhuma contagem total nem "nenhum resultado".
- **SC-007**: Nos casos de referência processados antes desta capacidade, **100%** das buscas por palavra de um roteiro fixo produzem resultados idênticos antes e depois da instalação da nova versão, com a evidência original bit a bit inalterada.
- **SC-008**: Com a capacidade desligada (padrão), o tempo de processamento do caso de avaliação varia no máximo **5%** em relação à versão sem a capacidade, e nenhuma conexão de rede é aberta por causa dela.
- **SC-009**: Com a capacidade ligada e nenhum modelo remoto declarado, **nenhuma** conexão de rede sai da estação durante processamento ou busca, verificado por observação do tráfego.
- **SC-010**: Com apenas a modalidade texto ligada e modelo local, no perfil forense, na estação de referência e sem placa gráfica, o tempo total de processamento do caso de avaliação **não passa do dobro** do tempo sem a capacidade.
- **SC-011**: A mesma pergunta com o mesmo recorte produz, pela interface e pelo agente, a mesma ordenação em **100%** das comparações, nas duas modalidades.
- **SC-012**: Num conjunto de avaliação com pelo menos 30 descrições em português de conteúdos presentes em imagens conhecidas, ao menos uma imagem pertinente aparece entre as 10 primeiras em **70% ou mais** das descrições.
- **SC-013**: Com texto e imagens ligados e vídeos desligados, nas mesmas condições de SC-010, o tempo total de processamento **não passa do triplo** do tempo sem a capacidade; e desligar imagens traz esse tempo de volta ao de SC-010, com variação de no máximo **5%**.
- **SC-014**: Num conjunto de avaliação com pelo menos 15 descrições em português de conteúdos que aparecem nos quadros extraídos de vídeos conhecidos, ao menos um vídeo pertinente aparece entre os 10 primeiros em **70% ou mais** das descrições.
- **SC-015**: O acréscimo de tempo causado por ligar vídeos aparece em separado no registro do processamento, e desligá-los traz o tempo de volta ao de SC-013, com variação de no máximo **5%**.
- **SC-016**: Com modelo remoto declarado, **100%** das conexões de rede abertas pela capacidade, no processamento e nas perguntas, têm como destino o serviço declarado, verificado por observação do tráfego.
- **SC-017**: Com uma chave ligada e sem fonte de modelo disponível para ela, o processamento recusa começar em **100%** das tentativas, antes de ler a evidência, nomeando o que falta.
- **SC-018**: No conjunto de avaliação acrescido de pelo menos 15 perguntas que misturam conceito e termo específico (apelido, codinome, lugar, jargão), o modo híbrido coloca ao menos um item pertinente entre os 10 primeiros em **80% ou mais** dessas perguntas; e, nas perguntas de SC-001, não fica mais de **5 pontos percentuais** abaixo do modo semântico.

---

## Assumptions

- **Local por padrão, remoto por escolha declarada.** O cálculo roda na estação. Cada modalidade pode, por configuração, usar um modelo remoto — no mesmo espírito em que a transcrição de áudio e o classificador remoto de imagens já permitem —, e o caso registra que isso aconteceu. A spec não restringe o tipo de destino: servidor do próprio laboratório ou serviço de terceiros. A decisão de enviar conteúdo de evidência para fora é da instalação, que a declara; o caso a registra. Nenhum destino vem configurado de fábrica.
- **Sem exigir placa gráfica.** A capacidade tem que funcionar na estação comum do perito. Placa gráfica, quando houver, pode acelerar, mas não pode ser requisito.
- **Desligada por padrão**, como as demais análises caras (OCR, transcrição, faces), e **com texto, imagens e vídeos desligados**. Perfis podem ligar qualquer combinação.
- **Complementa, não substitui.** A busca por palavra continua sendo a autoridade para identificadores, para recuperação exaustiva e para afirmações de ausência. Os classificadores de imagem existentes (nudez, CSAM, faces) continuam sendo a via para as classes que cobrem. Esta capacidade existe para encontrar o que não se sabe nomear, ou para o que não há classificador.
- **O conteúdo de entrada é o que o IPED já produz.** Esta feature não melhora OCR, transcrição, extração de texto nem decodificação de imagem, nem a extração de quadros de vídeo; consome o que elas entregam, com a qualidade que tiverem.
- **Imagens** são os itens de imagem do caso, incluindo as extraídas de documentos e contêineres. **Vídeos** entram pelos quadros que o processamento já extrai para a miniatura de vídeo: esta feature não decodifica vídeo por conta própria, e a densidade de quadros é a da configuração de miniatura de vídeo em vigor. O instante de cada quadro é aproximado porque o processamento não o grava; gravá-lo com exatidão é melhoria separada, fora desta entrega. "Mais como esta" entre imagens continua sendo a similaridade visual que já existe; esta entrega não acrescenta referência por imagem.
- **Idiomas.** Português e inglês são exigidos (FR-003, FR-036). Espanhol é desejável pela frequência em casos de fronteira, e a escolha de modelo deve preferir o que o cobre, mas não é critério de aceite.
- **Metadados ficam fora.** Nomes de arquivo, caminhos, remetentes e demais metadados continuam buscáveis por palavra; a busca semântica cobre o texto de conteúdo, as imagens e os vídeos.
- **Escala.** Casos com até alguns milhões de itens cobertos por modalidade. O IPED processa centenas de milhões de itens por caso, mas só itens com texto, imagem ou vídeo entram aqui.
- **Estação de referência** é a bancada já usada nas features anteriores (48 núcleos). **Caso de avaliação** e **conjunto de avaliação** são construídos como parte desta entrega; sem eles, SC-001 a SC-003, SC-010, SC-012 a SC-015 e SC-018 não são verificáveis.
- **SC-010 e SC-013 são metas de partida.** Se a pesquisa do plano mostrar que o dobro (texto) ou o triplo (texto e imagens) são inalcançáveis sem placa gráfica, a revisão volta para esta especificação, com a medição — e não é relaxada em silêncio no plano.
- **Distribuição dos modelos locais**: o de texto vem no pacote de instalação e funciona assim que a chave é ligada; o visual, mais pesado, vem em pacote separado, para que só quem usa imagens e vídeos pague pelo tamanho (FR-041). Nenhum dos dois exige rede para ser instalado. A escolha de quais modelos é decisão do plano, sob as restrições de plataforma da constituição e de FR-003, FR-023, FR-036 e SC-004.
- **RAG fica no harness.** O IPED não chama modelo de linguagem. Um modelo externo, por mais capaz que seja, recebe desta capacidade só o que o agente pedir pelo MCP, sob a política de egresso, e a resposta que ele compuser é dele — não é conteúdo do caso. O que esta feature garante é que cada trecho recuperado seja literal e citável.
- **A parte por palavra do modo híbrido é nova e própria dos trechos.** A busca por palavra existente pontua pela frequência do termo no item inteiro, e continua assim; o modo híbrido não a reaproveita nem a altera.
- **Fora de escopo**: gerar respostas, resumos ou qualquer texto com modelo de linguagem dentro do IPED, traduzir, descrever imagens, reordenar resultados com modelo gerador, painel de conversa na interface, ajuste de modelo com dados do caso, **enriquecimento de caso já processado sem reprocessar**, decodificação própria de vídeo além dos quadros já extraídos, e referência por imagem para "mais como esta".

---

## Dependências e relação com outras features

- Consome a extração de texto, o OCR, a transcrição de áudio, as miniaturas e os quadros de vídeo que o processamento já faz; a cobertura de um caso depende de quais dessas análises rodaram nele.
- Segue o padrão de interação que **imagens semelhantes** e **faces semelhantes** estabeleceram: referência, ordenação por proximidade, filtro aplicado sobre o resultado corrente.
- Na superfície do agente, depende do repertório de busca da [001-iped-llm-integration](../001-iped-llm-integration/spec.md) — trilha de auditoria, política de egresso, classes de conteúdo — e **não o afrouxa**: a ordenação semântica passa pelas mesmas fronteiras que a busca por palavra, o trecho é texto de item e a miniatura é miniatura, para efeito de egresso.
- Na modalidade visual, o agente examina cada resultado pela entrega de miniatura como imagem que a [008-thumbnail-image-block](../008-thumbnail-image-block/spec.md) estabeleceu. É essa feature que torna a ordenação visual verificável pelo agente, em vez de uma lista de identificadores que ele não consegue olhar.
- Não altera a fronteira de escrita que a [006-export-allowlist-socket-transport](../006-export-allowlist-socket-transport/spec.md) estabeleceu. Credenciais de modelo remoto seguem o mesmo padrão que ela fixou para o segredo do transporte: a configuração diz onde, nunca qual (FR-045).
- É a camada de recuperação do RAG feito por um harness sobre o MCP, e alicerce plausível do painel de conversa com a evidência previsto para a interface — mas não constrói nenhum dos dois.

## Constitution Check — pontos a defender no plano

- **I. Integridade da evidência**: nenhuma escrita na evidência. A preparação semântica acontece durante o processamento, que já é a operação que escreve o caso; nenhuma operação nova de escrita sobre caso existente é introduzida (FR-030).
- **II. Caso processado é contrato permanente**: tudo o que a capacidade grava no caso é acréscimo. Nenhum campo existente muda de nome ou semântica, a análise textual da busca por palavra não é tocada, e casos antigos abrem como sempre (FR-026, FR-027, SC-007). A relevância por palavra do modo híbrido é calculada fora do índice principal, sem alterar a pontuação da busca por palavra existente (FR-047).
- **III. Estender antes de modificar**: a preparação semântica de cada modalidade nasce como análise nova, com configuração própria, registrada no pipeline — não como alteração de análise existente. Se combinar ordenação semântica com os filtros existentes (FR-012) exigir tocar o caminho de busca compartilhado, o plano MUST justificar por que não havia alternativa aditiva e demonstrar que a busca por palavra permanece idêntica. Capacidade de escopo próprio SHOULD nascer como módulo.
- **IV. Comportamento configurável vive em configuração**: habilitação de texto, imagens e vídeos, fonte de modelo por modalidade, categorias, limites por item, tamanho mínimo de imagem, profundidade, modelos, destino do modelo remoto e localização das credenciais vêm de configuração ou perfil (FR-018, FR-019, FR-024, FR-032, FR-045).
- **V. Nada implícito no que varia por ambiente**: charset explícito ao ler texto de item, registro pelo mecanismo de log do projeto, texto de interface localizado (FR-031), exibição de imagem na thread de interface, e variação numérica entre estações tratada como variação declarada (Edge Cases).
- **Restrições da plataforma**: o que calcula a representação de significado MUST rodar no runtime que o release embarca, ou fora do processo principal, como já fazem transcrição e reconhecimento de faces. Dependência nativa nova MUST ser documentada junto às ferramentas externas e na lista de terceiros.
