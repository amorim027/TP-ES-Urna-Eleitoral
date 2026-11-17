# Guia de Contribuição

## Fluxo entre `dev` e `main`

1. Atualize a branch `dev` a partir de `main`:
   ```bash
   git checkout dev
   git pull origin main
   git pull origin dev
   ```
2. Valide os testes automatizados:
   ```bash
   mvn test
   ```
3. Faça commits objetivos seguindo Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
4. Promova `dev` para `main` via Pull Request após validar localmente.
