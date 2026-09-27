# Contract — superfície na interface de análise

**Feature**: [009-semantic-search](../spec.md) | **Decisão**: [research.md](../research.md), D13

A interface não calcula nada: chama o `SemanticSearcher` do `iped-semantic`, o mesmo que o MCP
chama. Este contrato fixa o que o perito **vê** e o que cada ação **faz**, porque é isso que as
Histórias 1 a 6 testam.

---

## Painel "Busca semântica"

Painel acoplável, como os demais. Fica disponível mesmo quando o caso não tem a capacidade; nesse
caso, mostra só a declaração de indisponibilidade.

| Elemento | Comportamento |
|---|---|
| Campo de pergunta | Texto livre. Acima do limite, o painel avisa que a pergunta foi truncada |
| Modalidade | Texto, Visual ou Ambas, só entre as preparadas no caso. As não preparadas aparecem desabilitadas, com o motivo como dica |
| Modo (só texto) | Semântico ou Híbrido; inicia no `defaultTextMode` (FR-046) |
| Recorte | Texto fixo: "sobre o resultado corrente: N itens". O recorte é o resultado da tabela no momento da pergunta (FR-012) |
| Declaração | Modo (e, no híbrido, quantas unidades a parte por palavra trouxe); profundidade exibida e teto; método (exato/aproximado); cobertura com motivos agrupados; modelo; destino remoto quando houver (FR-007, FR-008, FR-014) |
| Abas por modalidade | Uma ordenação por aba, nunca mescladas (FR-034) |
| Entrada de texto | Trecho literal, com o início destacado. Rótulo "proximidade relativa" no semântico e "relevância combinada relativa" no híbrido, com a dica "por significado: nº X · por palavra: nº Y" (FR-010, FR-047) |
| Entrada de sequência | As mensagens em ordem, com remetente e hora. As fora do recorte aparecem esmaecidas e marcadas "fora do recorte" |
| Entrada visual | Miniatura com o desfoque global da galeria respeitado (FR-037). Vídeo com "≈ mm:ss" |
| "Mais resultados" | Estende até o teto, mantendo a ordem já exibida. No teto, o botão desabilita e a dica nomeia a chave `maxDepth` |
| "Detalhes da construção" | Por modalidade: modelo e versão, fonte e destino da preparação, data, parâmetros, estado e contagens por motivo, lidos do registro do caso (FR-014) |
| Casos participantes | Com vários casos abertos juntos: quais participaram e quais ficaram de fora, com o motivo (FR-017) |
| "Aplicar à tabela" | Instala o `SemanticResultFilterer`: a tabela passa a mostrar a ordenação da aba ativa, com a proximidade na coluna de pontuação |
| "Marcar" | Leva as entradas selecionadas (numa sequência, as mensagens) a um marcador. O comentário do marcador recebe pergunta, recorte, profundidade, modelo e data (FR-011) |

**Textos que nunca aparecem**: "N resultados encontrados", "nenhum resultado", "0 ocorrências",
e porcentagem de confiança (FR-007, FR-010).

**Estados vazios**:
- Recorte vazio → "O resultado corrente está vazio; não há onde perguntar."
- Pergunta que parece identificador (só dígitos, e-mail, telefone) → a ordenação é exibida, com a
  dica "para identificadores, a busca por palavra é exata".

---

## Aviso de destino remoto (FR-044)

Quando a modalidade consultada usa modelo remoto:
- faixa permanente no painel: "Perguntas desta modalidade são enviadas a `<destino>`";
- na primeira pergunta da sessão, confirmação explícita antes do envio. Recusar não envia nada.

---

## Menus

| Onde | Ação | Efeito |
|---|---|---|
| Menu de contexto da tabela de resultados | "Mais como este" | Abre o painel com o item como referência (FR-013). Desabilitado, com dica do motivo, se o item não está coberto |
| Menu de contexto do visualizador de texto | "Mais como este trecho" | Usa o texto selecionado como referência |

---

## Abrir um resultado (cenário 4 da História 1)

Selecionar uma entrada seleciona o item na tabela e abre o visualizador. O trecho é destacado
pelo mecanismo existente de termos de destaque. Se o visualizador não destacar frase (verificação
de D13), ele rola até o deslocamento `charStart`.

---

## Linha de execução

A pergunta roda em `SwingWorker`. O painel mostra "calculando…" e continua responsivo. O modelo é
carregado na primeira pergunta, com aviso de carregamento. Nenhuma atualização de componente fora
da EDT.
