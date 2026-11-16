## Descrição das Alterações
Descreva de forma clara e objetiva o que foi adicionado, corrigido ou atualizado neste Pull Request.

## Tipo de Mudança
- [ ] `feat`: Nova funcionalidade da Urna Eletrônica
- [ ] `fix`: Correção de bug ou regra de negócio
- [ ] `test`: Adição ou atualização de testes JUnit 5
- [ ] `docs`: Atualização de documentação PDF, Markdown ou diagramas UML
- [ ] `chore`: Configuração de ambiente, Docker, CI/CD ou Render

## Checklist de Validação (Gatekeeper)
- [ ] A branch de origem está sincronizada com `origin/main` e `origin/dev`
- [ ] Todos os testes automatizados JUnit 5 (`mvn test`) passam com 100% de sucesso
- [ ] As regras de sigilo do voto (`RNF01`) e persistência atômica (`RNF03`) foram preservadas
- [ ] A documentação e os diagramas em `docs/` estão consistentes com o código Java
