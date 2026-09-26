# Specification Quality Checklist: Busca semântica sobre os artefatos interpretados pelo IPED

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-26
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

- **Iteração 1** — três falhas corrigidas na própria spec:
  - FR-003 misturava requisito (PT/EN) com desejo (espanhol) dentro de um MUST; o espanhol foi para Assumptions.
  - FR-021 (registro de cobertura ao fim do processamento) não tinha cenário de aceitação; acrescentado à História 2.
  - FR-022 (falha isolada por item) não aparecia nos casos de borda; acrescentado.
- **Iteração 2** — três marcadores de escopo levados ao usuário e resolvidos em 2026-09-26 (seção Clarifications da spec):
  - Q1 = A: interface **e** agente. História 5 entra; FR-038 a FR-040 e SC-011 passam a ser incondicionais.
  - Q2 = B, com chave própria para imagens: a antiga História 7 vira História 6 (P3), com FR-032 a FR-037 e SC-012/SC-013. As ordenações de texto e imagem nunca se misturam (FR-034), porque proximidades de modalidades diferentes não são comparáveis.
  - Q3 = B: a História 6 original (enriquecimento) sai; FR-030 declara o limite, e o acréscimo de evidência a caso antigo fica explicitamente proibido de servir de enriquecimento disfarçado (Edge Cases).
- **Iteração 3** — revalidada depois da reescrita. Duas correções de consistência: a ordem de FR-031 e uma referência cruzada errada (FR-033 → FR-032) na seção Clarifications. Todos os itens passam.
- Varredura por termos de implementação (linguagem, biblioteca, formato interno) sem ocorrências. Duas menções deliberadas, e não vazamento:
  - "servidor MCP" em FR-028 nomeia uma **superfície de produto** existente, não uma tecnologia a adotar.
  - A seção "Constitution Check" cita runtime do release, módulo e thread de interface porque existe para antecipar ao plano o que a constituição exige. Ela não prescreve solução.
- Interpretação registrada da "opção de desligar a busca semântica em imagens": chave de **configuração/perfil**, independente da de texto, que elimina o custo de preparação (FR-032, SC-013). Além disso, o perito escolhe por pergunta a modalidade entre as preparadas (FR-033). Se a intenção era só a segunda, FR-032 é o ponto a rever.
- Premissas assumidas sem pergunta, que `/speckit-clarify` pode querer confirmar: vídeos fora da busca de imagens nesta entrega; nenhuma referência por imagem para "mais como esta" (continua a similaridade visual existente); as duas modalidades desligadas de fábrica.
- SC-010 (texto, no máximo o dobro) e SC-013 (texto + imagens, no máximo o triplo) são metas de partida declaradas nas Assumptions: se a pesquisa do plano as refutar, a revisão volta para a spec.
- SC-001 a SC-003, SC-010, SC-012 e SC-013 dependem do **conjunto de avaliação** (entidade-chave), que precisa ser construído na entrega.
