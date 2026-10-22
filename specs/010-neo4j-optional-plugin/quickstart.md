# Quickstart — Neo4j como plugin opcional do IPED

Guia de desenvolvimento e validação da feature `010-neo4j-optional-plugin`. Complementa [plan.md](plan.md), [research.md](research.md) e os contratos em [contracts/](contracts/).

## 1. Pré-requisitos

- JDK 21 com JavaFX (Liberica Full), Maven 3.6+, `JAVA_HOME` configurado (ver `CLAUDE.md` §5).
- Um baseline de comparação: um release da versão anterior (com Neo4j embarcado) e uma evidência de referência pequena com comunicações (e-mails/chats) para gerar grafo.

## 2. Construir

```bash
# núcleo + plugin (reactor completo)
mvn -B clean package

# só o plugin (e o que ele depende)
mvn -pl iped-graph-neo4j -am package
```

Saídas:
- Distribuição padrão: `target/release/iped-<versão>/` + `target/release/plugins/` (apenas `Readme.txt`).
- Plugin: `iped-graph-neo4j/target/iped-graph-neo4j-<versão>.zip` (**fora** de `target/release/`).

Verificar o invariante "zero Neo4j" (SC-001) com os comandos de [contracts/release-inventory.contract.md](contracts/release-inventory.contract.md).

## 3. Instalar o plugin numa distribuição

```text
<dist>/
├── iped-<versão>/        # app root (LocalConfig.txt: pluginFolder = ../plugins/)
└── plugins/              # pasta de plugins (irmã)
    ├── iped-graph-neo4j-<versão>.jar
    └── iped-graph-neo4j/
        ├── neo4j/…
        ├── plugin.properties, LICENSE.txt, ThirdParty.txt, README.md
```

1. Extrair `iped-graph-neo4j-<versão>.zip` dentro de `<dist>/plugins/`.
2. Reiniciar o IPED.
3. Conferir no log: `Graph plugin loaded: iped-graph-neo4j <versão> (SPI v1, built for IPED <versão>) from …`.

Remover: apagar o jar e a pasta `iped-graph-neo4j/`; reiniciar.

## 4. Roteiro de validação (mapeado aos critérios da spec)

| # | Passo | Esperado | Critério |
|---|---|---|---|
| 1 | Inventário da distribuição (seção 2) | 0 arquivos Neo4j; enforcer e CI verdes | SC-001, SC-009 |
| 2 | Processar a evidência de referência **sem** o plugin, perfil padrão (`enableGraphGeneration=true`) | Conclui; 1 `WARN` "Graph generation is enabled but the graph plugin is not installed"; 0 erros; sem `iped/neo4j/`; campos forenses iguais ao baseline (hashes, MIME, texto, categorias, carved, YARA, timeline) | SC-002, FR-004 |
| 3 | Abrir o caso do passo 2 na UI sem o plugin | Sem aba "Vínculos", sem item "adicionar ao grafo", sem diálogo de erro, sem stack trace; checklist de smoke 100% | SC-005, FR-006 |
| 4 | Abrir um caso **com** dados de vínculos (baseline) na UI sem o plugin | Diálogo modeless único "Link analysis unavailable"; tudo o mais funciona | FR-007 |
| 5 | `--append` no caso do passo 4 sem o plugin | Recusa com a mensagem e exit ≠ 0; caso intocado | FR-008 |
| 6 | Repetir com `--ignore-missing-graph-plugin` | Prossegue; `WARN` em log e console | FR-008 |
| 7 | Instalar o plugin (seção 3); cronometrar | ≤ 3 passos, < 5 min, 0 edições de config | SC-003 |
| 8 | Processar a evidência de referência **com** o plugin | `iped/neo4j/csv` e `data` criados; contagens de nós/relacionamentos iguais ao baseline | SC-004, FR-009 |
| 9 | UI com o plugin: roteiro fixo de 10 operações (expandir, expandir com filtro, conexões, caminhos, buscar entidades, buscar vínculos, mostrar evidência, remover, desfazer/refazer, exportar vínculos) | Resultados idênticos ao baseline | SC-004 |
| 10 | `--append` e remoção de evidência com o plugin | Grafo atualizado como no baseline | FR-009 |
| 11 | Caso criado com o plugin: conferir `<caso>/plugins/`; abrir pelo `IPED-SearchApp` do caso em outra máquina sem o plugin instalado | Aba "Vínculos" disponível a partir da cópia | FR-010 |
| 12 | Multi-caso (2 casos com grafo) com o plugin | Grafo combinado gerado e aba funcional | FR-009 |
| 13 | Remover o plugin; repetir 2 e 3 | Mesmo resultado; 0 erros residuais | SC-008, FR-015 |
| 14 | Perfis/configs da versão anterior (cópia de `profiles/*`, `TaskInstaller.xml` com `GraphTask`) com e sem o plugin | Carregam sem edição; sem erro | SC-007, FR-005 |
| 15 | Medir tempo de inicialização (CLI e UI) com/sem plugin vs. versão anterior | Diferença < 1 s | SC-006 |
| 16 | Plugin corrompido (apagar `iped-graph-neo4j/neo4j/`), duas cópias do jar, jar com `IPED-Graph-SPI-Version: 2` | Rejeitado com o motivo no log; IPED segue sem grafo | FR-011, FR-012 |
| 17 | `--restart` sem o plugin num caso com grafo | Não bloqueia; `iped/neo4j/` removido; caso final sem dados de vínculos | FR-008 |
| 18 | Linux (WSL): passos 2, 3, 7, 8 | Idênticos | FR-017 |

## 5. Testes automatizados

```bash
mvn -pl iped-engine test -Dtest='GraphPluginRegistryTest,GraphPluginGuardTest,GraphFileWriterTest'
mvn -pl iped-graph-neo4j test
# integração gated (exige o zip montado e extraído):
IPED_GRAPH_PLUGIN_HOME=/caminho/plugins mvn -pl iped-graph-neo4j verify -Pgraph-it
```

## 6. Depuração

- Estado do plugin: primeira linha `Graph plugin …` no log; `GraphPluginRegistry.get().getDetail()` em depuração.
- Processos filhos: o lançador usa `<home>/neo4j/*`; logs `[graph-server]` em `DEBUG`.
- Pasta de plugins efetiva: `PluginConfig.getPluginFolder()` (log `WARN` quando ausente mostra o caminho).
