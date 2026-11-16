# Sistema de Urna Eletrônica — Diretrizes e Governança (AGENTS.md)

Este documento estabelece o fluxo de branches (`dev` e `main`), regras de engenharia de software e validação para o projeto **TP-ES-Urna-Eleitoral** (**Grupo 2 — DCC603 Engenharia de Software I — UFMG**).

---

## 1. Governança e Integrantes (Grupo 2)

- **Ítalo Leal Lana Santos (@Lealwbs)** — *Product Owner & Desenvolvedor*
- **Davi Ladeira Balsamão** — *Scrum Master & Desenvolvedor*
- **Thiago Amorim Bastos (@amorim027)** — *Desenvolvedor*
- **Vitor Hugo Dias Santos** — *Desenvolvedor*

---

## 2. Estratégia de Branches (`dev` e `main`)

O repositório adota separação estrita entre o ambiente de integração/desenvolvimento e o ambiente de produção (implantado automaticamente no **Render**):

1. **`main` (Produção / Entrega Oficial — Render)**:
   - Contém apenas código estável, revisado, documentado e 100% aprovado na suíte JUnit 5.
   - Conectada ao serviço web no **Render** (`render.yaml`).
2. **`dev` (Desenvolvimento e Homologação)**:
   - Branch principal de integração contínua do grupo.
   - Antes de iniciar qualquer trabalho em `dev` (ou em uma branch `feat/*` / `fix/*`), sincronize obrigatoriamente com `origin/main`.
   - Novas funcionalidades, ajustes de UI, testes e atualizações de documentação são validados em `dev` e promovidos para `main` via Pull Request.

---

## 3. Regras Inegociáveis do Domínio Eleitoral

1. **Sigilo Absoluto do Voto (`RNF01`)**:
   - Os registros de cédula (`B\t...` em `urna.db`) jamais devem conter o número do título, nome do eleitor ou horário do voto.
2. **Persistência Atômica (`RNF03`)**:
   - Toda alteração de estado (`DadosUrna`) deve ser gravada em arquivo temporário e movida atomicamente (`ATOMIC_MOVE`) para `urna.db`.
3. **Regras de Votação e Apuração (`RF01`–`RF13`)**:
   - Ciclo de estados estrito: `FECHADA -> ABERTA -> ENCERRADA` (com suporte a `reiniciar` autenticado para zerésima de demonstração).
   - Cédula com 3 escolhas: 1 para `PRESIDENTE` e 2 para `DEPUTADO_FEDERAL` (exigindo candidatos distintos caso ambos os votos para deputado federal sejam nominais).
   - Percentuais calculados por cargo sobre o total de votos nominais válidos daquele cargo.
4. **Validação Obrigatória**:
   - Nenhuma alteração deve ser mesclada sem que toda a suíte JUnit 5 (`ServicoUrnaTest`, `RepositorioArquivoTest`, `ServidorHttpTest`) passe sem erros.
