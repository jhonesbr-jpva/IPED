# Mapa de atalhos de teclado — UI atual (Swing) → UI RCP (T046, FR-021)

> Inventário derivado do código da UI atual (`App.setupKeyboardShortcuts`,
> `ResultTableListener.keyPressed`, `App.SpaceKeyListener`, aceleradores de
> `MenuClass`, `BookmarksManager`) e mapeamento na nova UI e4. Status:
> `preservado` (mesma tecla, mesma semântica), `remapeado` (documentado),
> `pendente` (depende de feature ainda não portada — rastreada no inventário
> de paridade).

## Atalhos globais (janela)

| Atalho | UI atual | UI RCP | Status |
|---|---|---|---|
| `Ctrl+B` | Abre o gerenciador de bookmarks (`KeyEventDispatcher` global) | Comando e4 `iped.rcp.command.managebookmarks` + binding `M1+B` no contexto de janela → mesmo diálogo do menu de contexto, seleção corrente da chave compartilhada | preservado |
| `Ctrl+Q` | Toggle do filtro de blur da galeria | — | pendente (blur/grayscale da galeria são pendência registrada do T026; reservar `M1+Q` ao portar) |
| `Ctrl+W` | Toggle de grayscale da galeria | — | pendente (idem; reservar `M1+W`) |

## Tabela de resultados (foco na tabela)

| Atalho | UI atual (`ResultTableListener`) | UI RCP (`ResultsTablePart.onKeyDown`) | Status |
|---|---|---|---|
| `Espaço` | Alterna o check das linhas destacadas (valor-alvo = inverso da PRIMEIRA linha destacada) | Idêntico (`CheckActions.toggleChecked`); o space-check nativo de linha única do SWT é suprimido para manter a semântica legada | preservado |
| `Ctrl+R` / `Alt+R` | Marca/desmarca destacados + subitens | Idêntico (`CheckActions.checkWithRelated(SUBITEMS)`, queries do `RelatedItemsQueries` = porte 1:1 dos models auxiliares) | preservado |
| `Ctrl+P` / `Alt+P` | Marca/desmarca destacados + item pai | Idêntico (`PARENT`) | preservado |
| `Ctrl+F` / `Alt+F` | Marca/desmarca destacados + itens referenciados | Idêntico (`REFERENCING`) | preservado |
| `Ctrl+D` / `Alt+D` | Marca/desmarca destacados + itens que referenciam | Idêntico (`REFERENCED_BY`) | preservado |
| `Ctrl+C` | Copia a CÉLULA selecionada | Copia as LINHAS destacadas (campos visíveis separados por tab) — `FULL_SELECTION` do SWT não tem foco de célula | remapeado (divergência registrada no inventário) |
| digitar texto | Busca incremental na tabela (type-to-find do `JTable`) | Type-to-find nativo do SWT `Table` (primeira coluna) | preservado (nativo) |

## Galeria

| Atalho | UI atual | UI RCP | Status |
|---|---|---|---|
| Setas / `Shift+Setas` | Navegação/extensão de seleção (`GalleryTable`) | Navegação nativa do Nebula Gallery | preservado (nativo) |
| `Espaço` | Alterna o check dos destacados | — | pendente (checkbox de célula da galeria é pendência do T026; aplicar a mesma `CheckActions.toggleChecked` ao portar) |

## Gerenciador de bookmarks

| Atalho | UI atual (`BookmarksManager`) | UI RCP | Status |
|---|---|---|---|
| Tecla rápida por bookmark (`0-9`/`A-Z`, definida pelo usuário) | Adiciona a seleção ao bookmark da tecla | — | pendente (recurso dinâmico por bookmark; entra com a iteração de paridade do gerenciador — `BM-*` no inventário) |
| `Alt+tecla` | Remove a seleção do bookmark da tecla | — | pendente (idem) |

## Novos na UI RCP (sem equivalente legado)

| Atalho/menu | Função | Observação |
|---|---|---|
| Menu `View → Theme` | Tema claro/escuro/sistema (FR-018, T044) | A UI atual não tem menu bar; divergência justificada no inventário |
| Menu `View → UI Scale...` | Diálogo de escala (paridade do item do menu Options legado, mesma `~/.iped/UiScale.txt`) | Aplica no próximo start (igual ao legado) |

## Notas de implementação

- Bindings e4 vivem em `Application.e4xmi` (`bindingTables` no contexto
  `org.eclipse.ui.contexts.window`); atalhos locais da tabela vivem no
  `SWT.KeyDown` da part (semântica dependente de seleção/foco, como no
  legado, onde eram `KeyListener` do `JTable`).
- `M1` = `Ctrl` no Windows/Linux (plataformas suportadas).
- Validação manual dos atalhos por SO entra na passada de inspeção do gate
  T058 (SC-001/SC-010).
