# Guia de Contribuição e Fluxo Git (`dev` e `main`)

Bem-vindos ao repositório do **Sistema de Urna Eletrônica (Grupo 2 — DCC603/UFMG)**.

## Fluxo de Trabalho com Branches (`dev` → `main`)

Seguindo o padrão de governança com duas branches permanentes (`dev` para desenvolvimento/homologação e `main` para produção no Render):

1. **Parta sempre da branch `dev` sincronizada com `main`**:
   ```bash
   git checkout dev
   git pull origin main
   git pull origin dev
   ```
2. **Crie uma branch para sua tarefa (opcional) ou trabalhe em `dev` com commits atômicos**:
   ```bash
   git checkout -b feat/minha-melhoria
   ```
3. **Execute a bateria de testes JUnit 5**:
   ```bash
   mvn test
   ```
4. **Use Conventional Commits**:
   - `feat(urna): ...`
   - `fix(urna): ...`
   - `test(urna): ...`
   - `docs(projeto): ...`
   - `chore(deploy): ...`
5. **Abra Pull Request para `dev` e, após homologação, promova `dev` → `main` via Pull Request**.
