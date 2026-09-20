# Feature Specification: Miniatura como imagem do protocolo, não como base64 dentro de texto

**Feature Branch**: `008-thumbnail-image-block`

**Created**: 2026-09-18

**Status**: Planejado — clarificações resolvidas em [plan.md](plan.md) / [research.md](research.md) (D1, D2, D4)

**Input**: User description: "Vamos mudar o servidor emitir um bloco image de verdade para o contentClass=thumbnail."

---

## Contexto e problema

A [001-iped-llm-integration](../001-iped-llm-integration/spec.md) entregou ao agente uma superfície para **ler** itens, e nela está `iped_item_thumbnail`: a miniatura que o IPED já renderizou durante o processamento, devolvida com `media_type`, `encoding: base64` e os bytes. A ferramenta existe, responde, declara ausência com motivo e respeita um teto. Do lado do servidor, nada nela está errado.

Falta o último passo, e ele não está na ferramenta: está no caminho que **toda** resposta percorre. Medido em 2026-09-18, o servidor monta o resultado de qualquer ferramenta como **um único bloco de texto** com o JSON serializado, mais a cópia estruturada. Não existe, em lugar nenhum do servidor, a emissão de um bloco de conteúdo do tipo imagem.

A consequência é que a miniatura chega ao agente **como texto**. Uma figura vira uma cadeia longa de base64 que ocupa contexto proporcional ao tamanho da imagem e **não mostra nada**. Um modelo com visão — capaz de descrever a foto se ela lhe fosse entregue como imagem — fica cego diante dela, e paga caro pela cegueira.

### Por que isso é pior do que uma capacidade ausente

Uma capacidade que não existe é honesta: ninguém a usa. Esta **parece** existir. A ferramenta está listada, é chamável, responde `available: true` e entrega dados. O que ela não faz é a única coisa que se queria dela.

Três efeitos, todos observados em campo:

**1. O agente gasta contexto para não ver.** Em uma arquitetura multiagente cujo propósito declarado é economizar contexto, a chamada mais cara do repertório passou a ser também a mais inútil.

**2. O agente constrói desvios — e o desvio bom depende de configuração alheia.** Um subagente de inspeção, com a ferramenta liberada, concluiu corretamente que não havia rota de renderização e propôs outra: exportar o item e abrir o arquivo resultante pelo sistema de arquivos. O raciocínio estava certo. Mas a exportação de item grava **na máquina do servidor**, sob a **primeira** raiz de exportação declarada, e o destino não é parâmetro. A leitura visual de evidência passa a depender de qual pasta alguém listou primeiro num arquivo de configuração — o que não é desenho, é acidente.

**3. O risco que importa: descrever o que não se viu.** Um agente sob pressão de responder, diante de uma cadeia base64 e de um pedido para dizer o que há na foto, tem todos os ingredientes para relatar uma imagem que não examinou. O material aqui sustenta decisão judicial. Uma alucinação sobre o conteúdo de uma foto apreendida não é um erro de qualidade — é uma afirmação falsa sobre prova, com a aparência de observação direta.

### O que muda de natureza

Até aqui, o servidor tratava **todo** resultado como texto, uniformemente. Essa uniformidade é o que se quebra, e é preciso reconhecer o que ela protegia: um único formato de saída não tem caso particular, não tem cliente que o entenda pela metade e não tem negociação. Ao introduzir um segundo tipo de bloco, passam a existir clientes que o renderizam e clientes que não, e o servidor precisa continuar correto para os dois.

Por isso esta feature é **aditiva por obrigação, não por gosto**: o bloco de texto permanece, a cópia estruturada permanece, e o bloco de imagem se soma. Nenhum cliente que funciona hoje pode passar a falhar, e nenhum pode perder a informação de que a miniatura existe.

Vale dizer também o que esta feature **não** resolve: ela não amplia o que o agente pode alcançar. A miniatura já lhe era entregue; o que muda é a forma em que ela chega. A política de egresso continua sendo quem decide se conteúdo de evidência sai, e continua desligada ou ligada por configuração, exatamente como antes.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - O perito pergunta o que há na imagem, e a resposta descreve a imagem (Priority: P1)

O perito pede ao agente que examine um conjunto de itens visuais — fotos de documentos, capturas de tela, imagens recuperadas. O agente pede a miniatura de cada um e descreve o que **está** ali: que tipo de documento é, se há texto legível, se a foto mostra pessoa, objeto ou tela. O que ele afirma é conferível abrindo o mesmo item na interface do IPED.

**Why this priority**: é a razão de existir da feature. Sem isto, as outras duas histórias protegem um comportamento que não entrega valor nenhum.

**Independent Test**: com um caso que contenha itens com miniatura renderizada, pedir a descrição de um item cujo conteúdo visual seja conhecido de antemão e comparar a descrição com a imagem aberta na interface. Entrega valor sozinha: a partir daí a verificação visual de evidência existe.

**Acceptance Scenarios**:

1. **Given** um item com miniatura renderizada e um cliente capaz de apresentar imagens, **When** o agente pede a miniatura, **Then** a figura lhe é entregue como imagem e a descrição que ele produz corresponde ao conteúdo visual do item.
2. **Given** o mesmo item, **When** a miniatura é entregue, **Then** o custo de contexto da chamada é o de uma imagem, e não o de uma cadeia base64 transcrita no diálogo.
3. **Given** um conjunto de dez itens visuais, **When** o agente os inspeciona, **Then** ele não recorre a exportar arquivos para conseguir vê-los.

---

### User Story 2 - O cliente que não apresenta imagens continua correto (Priority: P1)

Um harness que não renderiza imagem — ou um consumidor automatizado que apenas lê dados — continua funcionando exatamente como antes: não quebra, não recebe resposta malformada, e continua sabendo que a miniatura existe, qual é seu tipo e qual seu tamanho.

**Why this priority**: também P1, e não P2, porque uma regressão aqui é silenciosa e atinge instalações que não pediram nada. A feature não pode ser entregue sem ela.

**Independent Test**: exercitar a mesma chamada por um cliente que ignore blocos de imagem e verificar que a resposta continua completa e interpretável, sem erro de protocolo.

**Acceptance Scenarios**:

1. **Given** um cliente que não trata blocos de imagem, **When** pede a miniatura de um item, **Then** recebe uma resposta válida e a informação de que a miniatura existe, com tipo e tamanho.
2. **Given** um consumidor que lê apenas a cópia estruturada da resposta, **When** pede a miniatura, **Then** recebe os mesmos campos que recebia antes desta feature.

---

### User Story 3 - A ausência continua sendo declarada, e continua não sendo fato sobre a evidência (Priority: P2)

Quando não há miniatura — porque o item não é visual, porque nada foi renderizado no processamento, ou porque a imagem excede o teto configurado — o agente recebe a declaração de ausência com o motivo, e o motivo distingue esses casos entre si.

**Why this priority**: o comportamento já existe e precisa sobreviver à mudança. É proteção de contrato, não capacidade nova.

**Independent Test**: pedir a miniatura de um item sem conteúdo visual, de um item cuja miniatura ultrapasse o teto, e de um item comum, e verificar que os três se distinguem pela resposta.

**Acceptance Scenarios**:

1. **Given** um item sem miniatura, **When** o agente a pede, **Then** recebe ausência declarada com o motivo e uma indicação do que consultar em seu lugar, e **nenhum** bloco de imagem é emitido.
2. **Given** uma miniatura acima do teto configurado, **When** o agente a pede, **Then** recebe o tamanho real, o teto em vigor e a configuração que o governa — e não uma imagem truncada.

---

### Edge Cases

- **Item sem miniatura**: nenhuma imagem é emitida; a ausência declarada é a resposta inteira, e não deve ser relatada como "item vazio".
- **Miniatura acima do teto**: nada é emitido além da recusa explicada. Entregar imagem cortada seria pior que não entregar.
- **Política de egresso barrando a classe**: a recusa continua vindo da política, antes de qualquer bloco ser montado. A forma da resposta não pode virar um caminho alternativo por onde conteúdo saia.
- **Tipo de mídia diferente do usual**: o tipo declarado acompanha a imagem; nenhum tipo é presumido por omissão.
- **Cliente que não anuncia suporte a imagem**: precisa continuar correto — ver o marcador de clarificação sobre negociação.
- **Resposta que contém imagem e erro ao mesmo tempo**: a sinalização de erro continua legível sem depender de o cliente entender blocos de imagem.
- **Item cujo conteúdo bruto é uma imagem** (não a miniatura): ver o marcador de clarificação sobre a extensão a conteúdo bruto.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O servidor MUST entregar a miniatura de um item como conteúdo de imagem do protocolo, de modo que um cliente capaz de apresentá-la a exiba como figura.
- **FR-002**: O servidor MUST declarar o tipo de mídia da imagem entregue, sem presumir um tipo por omissão.
- **FR-003**: O servidor MUST continuar emitindo, na mesma resposta, a representação textual e a cópia estruturada que já emitia. A **cópia estruturada** MUST permanecer completa — todos os campos, incluindo a codificação da miniatura. Na **representação textual**, quando e somente quando a miniatura for entregue como imagem na mesma resposta, a codificação MUST ser substituída por uma indicação de onde ela está; todos os demais campos permanecem. Ver a justificativa em [research.md](research.md), D4.
- **FR-004**: Um cliente que não interprete conteúdo de imagem MUST continuar recebendo resposta válida e MUST continuar sabendo que a miniatura existe, com tipo e tamanho. Este requisito é **deliberadamente distinto** de FR-003: garante o conhecimento da existência, do tipo e do tamanho, não a presença da codificação na representação textual — e é essa distinção que torna FR-003 satisfazível junto com SC-002.
- **FR-005**: O servidor MUST emitir conteúdo de imagem **apenas** para resultados cuja classe de conteúdo seja miniatura; nenhuma outra ferramenta pode passar a emitir imagem como efeito colateral desta mudança.
- **FR-006**: A política de egresso MUST continuar sendo avaliada por classe de conteúdo antes de a resposta ser montada, e uma recusa MUST continuar impedindo que a miniatura seja entregue em qualquer forma.
- **FR-007**: A trilha de auditoria MUST continuar registrando a chamada antes de sua execução, sem alteração de formato motivada por esta feature.
- **FR-008**: O teto configurado para miniaturas MUST continuar em vigor, e uma miniatura acima dele MUST continuar sendo recusada com o tamanho real e o teto, nunca entregue truncada.
- **FR-009**: A ausência de miniatura MUST continuar sendo declarada com motivo, distinguindo item sem conteúdo visual, item sem renderização e recusa por teto — e nesses casos nenhum conteúdo de imagem é emitido.
- **FR-010**: A orientação carregada pelo agente MUST ser atualizada para descrever a forma em que a miniatura chega, e MUST deixar de recomendar o desvio por exportação como rota para examinar imagem.
- **FR-011**: A mudança MUST valer igualmente para todos os transportes suportados, sem que nenhum deles adquira comportamento próprio.
- **FR-012**: O servidor MUST emitir o conteúdo de imagem **independentemente do que o cliente anuncie** na abertura da sessão, e MUST NOT condicionar a emissão a qualquer declaração de capacidade. A revisão de protocolo que este servidor implementa não oferece canal para negociar tipos de bloco de conteúdo, e a tolerância a blocos não reconhecidos é premissa dela; negociar exigiria inventar uma extensão proprietária. Resolvido em [research.md](research.md), D1.
- **FR-013**: O conteúdo bruto de um item cujo tipo de mídia seja de imagem **MUST NOT** ser entregue como conteúdo de imagem por esta feature. A entrega visual se limita à miniatura: o conteúdo bruto tem classe de conteúdo própria — que a política de egresso pode permitir ou bloquear separadamente —, propósito diferente (estabelecer o que um arquivo é) e é truncado por desenho, e imagem truncada é justamente o que esta feature proíbe emitir. Resolvido em [research.md](research.md), D2.

### Key Entities

- **Resultado de ferramenta**: o que o servidor devolve a uma chamada. Hoje carrega uma representação textual e uma cópia estruturada; passa a poder carregar também uma representação visual.
- **Classe de conteúdo**: a marcação que distingue o que uma ferramenta devolve — miniatura, texto, conteúdo bruto, metadado. É por ela que a política de egresso decide, e é ela que passa a decidir também a forma da entrega.
- **Miniatura**: imagem renderizada durante o processamento do caso, com tipo de mídia e tamanho declarados, sujeita a teto configurável e cuja ausência é sempre explicada.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Diante de dez itens visuais de conteúdo conhecido, o agente descreve corretamente o que há em pelo menos nove, conferido contra as mesmas imagens abertas na interface do IPED.
- **SC-002**: Examinar uma imagem deixa de transcrever a codificação da figura no diálogo: o custo de contexto de olhar um item cai para o de uma imagem. **Limiar de aprovação**: medido em tokens consumidos pela chamada, na mesma evidência antes e depois, a redução é de **pelo menos 90%** para miniaturas de 100 KB ou maiores. Abaixo disso, a entrega não está surtindo o efeito que a motiva.
- **SC-003**: Nenhum agente precisa exportar arquivos para examinar imagem; a rota por exportação some dos registros de trabalho produzidos em uma perícia completa.
- **SC-004**: Instalações que não apresentam imagens não registram nenhuma falha nova nem perda de informação após a mudança, verificado exercitando a mesma chamada por um cliente que ignore imagens.
- **SC-005**: Nenhuma afirmação sobre conteúdo visual aparece em artefato de perícia sem que a imagem correspondente tenha sido efetivamente entregue ao modelo — a verificação passa a ser possível, e o relato passa a ser conferível.
- **SC-006**: As três formas de ausência de miniatura continuam distinguíveis pela resposta, sem regressão.

---

## Assumptions

- **O bloco de texto permanece ao lado da imagem.** A descrição do usuário levanta a dúvida; adota-se a permanência como padrão, porque a própria restrição de não quebrar clientes existentes a exige. Remover o texto seria uma decisão separada, com perda de informação para quem lê dados estruturados.
- **O escopo é o servidor, não o harness.** Se um cliente específico apresenta ou não a imagem ao modelo é propriedade dele. Esta feature garante que a informação chegue em forma apresentável; não garante que todo harness a apresente.
- **Nenhuma dependência nova.** A entrega continua sob as restrições de plataforma que a constituição impõe a este branch; adotar biblioteca com baseline superior está fora de escopo.
- **A miniatura já existe.** Esta feature não renderiza nada: consome o que o processamento do caso já produziu. Itens processados sem geração de miniatura continuam sem ela.
- **A leitura continua sendo leitura.** Nada aqui altera evidência, e o modo de abertura do caso permanece somente-leitura.
- **Precedência de configuração inalterada.** Tetos, política de egresso e modo de acesso continuam sendo decididos por configuração da instalação, e não por valor fixo embutido no produto.

---

## Dependências e relação com outras features

- Depende do repertório de leitura de itens entregue pela [001-iped-llm-integration](../001-iped-llm-integration/spec.md), de onde vem a ferramenta de miniatura e a classe de conteúdo.
- Toca a fronteira que a [006-export-allowlist-socket-transport](../006-export-allowlist-socket-transport/spec.md) estabeleceu, mas **não a move**: o que muda é a forma da resposta, não o que o agente alcança.
- Reduz a dependência acidental entre exame visual e configuração de raízes de exportação, que é o desvio observado em campo.

## Constitution Check — pontos a defender no plano

- **III. Estender antes de modificar**: a mudança recai sobre o caminho compartilhado por todas as ferramentas. O plano MUST justificar por que o caso não podia ser resolvido de forma aditiva fora dele e MUST demonstrar que a resposta anterior permanece contida na nova.
- **I. Integridade da evidência**: nenhuma escrita, nenhum caminho novo até a evidência.
- **IV / V**: tetos e política continuam em configuração; o tipo de mídia é declarado e não presumido.
