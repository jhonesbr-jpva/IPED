# Specification Quality Checklist: Miniatura como imagem do protocolo

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

**Iteração 1 (2026-09-18)** — dois itens corrigidos antes desta revisão:

- *No implementation details*: a seção de Assumptions citava o runtime da plataforma e o diretório de
  configuração pelo nome. Reescrita para falar de restrição de plataforma e de decisão por
  configuração, sem nomear versão nem caminho.
- O restante das seções já descrevia comportamento observável, não mecanismo.

**Iteração 2 (2026-09-20) — os dois marcadores foram resolvidos** durante `/speckit-plan`, e a
resolução está dobrada de volta no spec:

1. **FR-012 — negociação de capacidade.** Resolvido em [research.md](../research.md) D1: **emitir
   sempre**. A revisão de protocolo implementada não oferece canal para negociar tipos de bloco de
   conteúdo, e a tolerância a blocos não reconhecidos é premissa dela. Negociar exigiria uma
   extensão proprietária.
2. **FR-013 — conteúdo bruto de imagem.** Resolvido em [research.md](../research.md) D2: **fora de
   escopo**. Classe de conteúdo própria, propósito diferente, e truncado por desenho — imagem
   truncada é exatamente o que a feature proíbe emitir.

**Correções aplicadas após `/speckit-analyze` (2026-09-20)**, nenhuma delas de qualidade de
requisito, todas de coerência entre artefatos:

- **FR-003** ganhou a ressalva da decisão D4 (a codificação sai da representação textual quando a
  imagem é entregue na mesma resposta; a cópia estruturada permanece completa), sem a qual o spec
  contradizia o plano.
- **FR-004** passou a dizer explicitamente que é distinto de FR-003, e por quê — a sobreposição
  entre os dois é deliberada e carrega peso.
- **SC-002** ganhou limiar de aprovação (redução ≥ 90% dos tokens da chamada, em miniaturas de
  100 KB ou mais). Antes era direcionalmente claro e não verificável.

Todos os itens deste checklist passam.
