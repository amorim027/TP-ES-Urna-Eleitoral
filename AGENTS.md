# Diretrizes do Projeto (AGENTS.md)

## Integrantes (Grupo 2)

- Davi Ladeira Balsamão
- Ítalo Leal Lana Santos (@Lealwbs)
- Thiago Amorim Bastos (@amorim027)
- Vitor Hugo Dias Santos

## Branches (`dev` e `main`)

1. `main`: produção publicada automaticamente no Render (`https://urna-eleitoral.onrender.com`).
2. `dev`: desenvolvimento e execução local (`docker compose up --build -d`). Sempre sincronizar com `origin/main` antes de novas alterações.

## Regras do Domínio

1. Sigilo do voto (`RNF01`): registros de cédula (`B\t...` em `urna.db`) nunca contêm título, nome ou horário.
2. Persistência atômica (`RNF03`): escrita em arquivo temporário seguida de `ATOMIC_MOVE` para `urna.db`.
3. Votação e apuração:
   - Estados: `FECHADA -> ABERTA -> ENCERRADA`.
   - Cédula com 1 voto para `PRESIDENTE` e 2 votos para `DEPUTADO_FEDERAL` (distintos se nominais).
   - Percentual calculado sobre o total de votos nominais válidos de cada cargo.
4. Validação: toda a suíte JUnit 5 (`mvn test`) deve passar antes de atualizar `dev` ou `main`.
