# Quickstart — Criação e Abertura de Casos na GUI RCP

**Feature**: `005-case-creation-wizard` | **Date**: 2026-06-16
**Input**: [plan.md](plan.md) · [research.md](research.md) · [contracts/](contracts/)

Guia de build, execução e **validação** dos fluxos desta feature. Estende a
[quickstart da 004](../004-rcp-gui-migration/quickstart.md) — pré-requisitos da
plataforma RCP (Tycho, target platform, Liberica 21) são os mesmos. Aqui só o que é
específico de criação/abertura de casos e gestão de perfis.

## Pré-requisitos

- **Liberica Full 21** em `H:\java\LibericaJDK-21-Full` (`JAVA_HOME`).
- Build FULL do IPED publicado no `.m2` (`mvn clean install`) — o reactor RCP consome
  os demais módulos como binários. ⚠️ **Sempre `mvn clean package`/`install`**, nunca
  `package` sozinho (memória do projeto: ECJ do m2e envenena `target/classes`).
- Uma evidência de referência pequena para os testes (ex.: uma pasta ou `.E01`).

## Build

```bash
# 1) Reactor FULL primeiro (publica iped-engine/iped-app/Bootstrap no .m2):
mvn clean install

# 2) RCP (inclui o novo bundle iped.rcp.casecreation):
mvn -f iped-rcp/pom.xml clean verify

# Testes headless dos serviços de criação/perfis (rápido, sem UI):
mvn -f iped-rcp/pom.xml -pl bundles/iped.rcp.core test

# Testes SWTBot dos fluxos de UI (Linux/GTK via Xvfb no CI; Windows manual):
mvn -f iped-rcp/pom.xml -pl tests/iped.rcp.tests.swtbot verify
```

> ⚠️ Subsets `-am` quebram a resolução p2 do Tycho; rode SWTBot por `-pl` a partir do
> `.m2` já populado pelo build FULL (memória `project_iped_rcp_migration`).

## Executar a UI

```bash
# A partir do release materializado (produto em <root>/ui/):
<root>/ui/iped-ui            # Linux
<root>\ui\iped-ui.exe        # Windows
```

Sem argumentos de caso, a aplicação sobe em **estado vazio** (R6) e o menu **File** é a
porta de entrada.

## Cenários de validação

### V1 — New Case ponta a ponta (US1, FR-003…FR-013, SC-001/SC-003)

1. Menu **File ▸ New Case** → o `NewCaseWizard` abre.
2. **Sources**: adicione a evidência de referência (diálogo nativo). **Output**: pasta
   gravável vazia. **Profile**: `triage`. Avance e **Conclua**.
3. **Esperado**: o processamento inicia **sem terminal**; a **janela de progresso SWT**
   aparece (a mesma da 004). O wizard **oferece abrir o caso em modo quase-ao-vivo**.
4. Aceite → a UI abre `<output>/iped` e atualiza a cada consolidação (R7); ao final, o
   caso permanece aberto/abrível.
5. **Equivalência (FR-013/SC-004)**: rode o mesmo perfil/evidência pela CLI
   (`java -jar iped.jar -d <ev> -o <out2> -profile triage`) e compare contagem de
   itens/categorias/campos — devem coincidir.

### V2 — Validações do wizard (FR-008, SC-003)

- Fonte inexistente, saída não gravável, ou saída = subpasta da fonte → o wizard
  **bloqueia** o avanço com mensagem clara e **não** lança processo.
- Output não vazio sem modo → oferece Append/Continue/Restart/escolher outra (FR-009).
- **Cancelar** em qualquer página → nenhum caso criado, nenhum artefato (FR-012).

### V3 — Open Case + recentes (US2, FR-002)

1. **File ▸ Open Case** → selecione um caso processado → abre na UI (paridade 004).
2. Pasta inválida → mensagem clara, sem travar.
3. **File ▸ Recent Cases** lista o caso aberto em V1/V3 para reabertura.

### V4 — Criar/editar perfil (US3, FR-014…FR-019, SC-005)

1. **File ▸ Manage Profiles** (ou atalho na página Profile do wizard).
2. **Novo perfil** clonando `triage`; nome único. O editor lista **todas** as opções
   (grupos por arquivo de config, descrições dos comentários `#`).
3. Altere uma flag (ex.: `enableCarving = false`); salve.
4. **Esperado**: cria `profiles/<novo>/IPEDConfig.txt` com **só** a chave alterada;
   o perfil aparece imediatamente na lista do wizard (FR-017).
5. Tente **editar `triage` (embarcado)** → oferece "Salvar como", **não** sobrescreve o
   embarcado (FR-018). Nome em colisão → rejeitado (FR-019).
6. Crie um caso com o novo perfil → o pipeline reflete a opção alterada (SC-005).

### V5 — i18n (FR-023, SC-007)

- Suba com `-nl pt_BR` e com `-nl en`: menu, wizard e editor 100% localizados; nos
  demais locales, **sem chaves cruas** (fallback EN).

## Mapa cenário → requisito

| Cenário | Cobre |
|---|---|
| V1 | US1; FR-003/004/005/006/010/011/013; SC-001/003/004 |
| V2 | FR-007/008/009/012; SC-003 |
| V3 | US2; FR-002 |
| V4 | US3; FR-014/015/016/017/018/019; SC-005 |
| V5 | FR-023; SC-007 |

## Não esquecer (gates de PR)

- Serviços novos em `iped.rcp.core` são **DS headless** e têm teste headless.
- Strings via `iped.rcp.core.i18n.Messages` + chaves nos 6 locales.
- `iped.rcp.casecreation` registrado em `feature.xml` + `iped-ui.product`.
- **Nenhum** arquivo do engine modificado no caminho default (FR-028); plano-B de
  resolução de perfil só se a instalação for read-only (Complexity Tracking).
