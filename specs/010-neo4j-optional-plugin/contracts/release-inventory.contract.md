# Contrato — Inventário da distribuição padrão (zero Neo4j)

**Feature**: 010-neo4j-optional-plugin · **Alvo**: `target/release/` (pacote padrão) · **Critério**: SC-001, FR-001, FR-014

## Invariante

A distribuição padrão (`target/release/iped-<versão>/` e `target/release/plugins/`) **não contém**:

1. Nenhum jar de `org.neo4j:*` nem `org.neo4j.driver:*` (motor, `neo4j-graphdb-api`, driver).
2. Nenhuma dependência exclusiva deles (`lib/neo4j/` deixa de existir; `antlr4-runtime-4.13.x` não aparece — `antlr4-runtime-4.9.2` do libfqlite permanece).
3. Nenhuma classe do IPED que importe `org.neo4j.*` (verificado no fonte de `iped-engine` e `iped-app`).
4. O zip `iped-graph-neo4j-*.zip` ou seu conteúdo.

## Enforcement

| Camada | Mecanismo | Falha |
|---|---|---|
| Build (Maven) | `maven-enforcer-plugin` `bannedDependencies` em `iped-engine` e `iped-app`: `org.neo4j:*`, `org.neo4j.driver:*` (inclui transitivas) | Build falha. |
| Build (Maven) | `iped-app/pom.xml` sem a dependência zip `iped-graph-server` e sem a execução `unpack-graph-server` | — |
| Fonte | `grep -rl "import org.neo4j" iped-engine/src iped-app/src` vazio — passo de CI | CI falha. |
| Release | Passo de CI após `mvn package`: `find target/release -iname '*neo4j*'` deve retornar vazio | CI falha. |
| Release | Passo de CI publica `iped-graph-neo4j/target/iped-graph-neo4j-*.zip` como artefato separado (`iped-graph-neo4j-<sha>`) | — |

## Verificação manual (quickstart)

```bash
mvn -B clean package
find target/release -iname '*neo4j*'                # esperado: nenhuma linha
ls target/release/iped-*/lib | grep -i -E 'netty|reactor|reactive-streams'   # esperado: nenhuma linha
grep -rl "import org.neo4j" iped-engine/src iped-app/src                    # esperado: nenhuma linha
ls iped-graph-neo4j/target/*.zip                     # esperado: iped-graph-neo4j-<versão>.zip
```

## Documentação associada

- `ThirdParty.txt` (núcleo) sem as entradas Neo4j CE / Neo4j Java Driver; nota apontando para o plugin.
- `plugins/Readme.txt` do release menciona o plugin de análise de vínculos como exemplo de plugin opcional.
- `README.md` lista "Graph analysis for communications" como funcionalidade **via plugin opcional**.
