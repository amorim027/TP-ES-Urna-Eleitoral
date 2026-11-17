# Sistema de Urna Eletrônica

Simulador web de urna eletrônica para eleição de turno único (Presidente e Deputado Federal), desenvolvido para a disciplina DCC603 (Engenharia de Software I) da UFMG.

- **Aplicação online (Render):** https://urna-eleitoral.onrender.com (Senha mesário: 123456)
- **Execução local (Docker):** `docker compose up --build -d` em http://localhost:8080

## Grupo 2

- Davi Ladeira Balsamão
- Ítalo Leal Lana Santos
- Thiago Amorim Bastos
- Vitor Hugo Dias Santos

## Documentação (`docs/`)

- [Documentação Completa (Sprints 1, 2 e 3)](./docs/Documentacao_Completa_TP_Urna_Eletronica.pdf)
- [Sprint 1: Planejamento e Requisitos](./docs/Sprint1_Planejamento_e_Requisitos.pdf)
- [Sprint 2: Casos de Uso, Arquitetura e Modelagem](./docs/Sprint2_CasosDeUso_Arquitetura_Modelagem.pdf)
- [Sprint 3: Implementação e Testes](./docs/Sprint3_Implementacao_e_Testes.pdf)
- [Slides da Apresentação Final](./docs/Apresentacao_Final_Sprint3_Urna_Eletronica.pdf)
- [Diagramas UML (`.puml` e `.png`)](./docs/diagramas/)
- [Especificação do TP (`docs/especificacao/`)](./docs/especificacao/)

## Como executar localmente

Com Docker e Docker Compose instalados:

```bash
docker compose up --build -d
```

Acesse `http://localhost:8080` (Senha mesário: 123456). Para encerrar mantendo os dados, use `docker compose down`. Para zerar os dados do volume, use `docker compose down -v`.

Sem Docker (com Java 17 e Maven):

```bash
mvn test
mvn package
java -jar target/urna-eleitoral-1.0.0.jar
```

## Dados de entrada (`data/`)

- `data/eleitores.csv`: `titulo,nome` (`1001` a `1010`)
- `data/candidatos.csv`: `cargo,numero,nome` (`PRESIDENTE`: `10`, `20`, `30`; `DEPUTADO_FEDERAL`: `101`, `102`, `103`, `104`)

## Regras principais

- Ciclo de estados controlado por senha do mesário: `FECHADA`, `ABERTA` e `ENCERRADA`.
- Identificação do eleitor pelo título com confirmação do nome antes de votar. Cada eleitor participa uma única vez (voto ou abstenção).
- Cédula com 1 voto para Presidente e 2 votos para Deputado Federal (candidatos distintos quando nominais), aceitando voto nominal, `BRANCO` ou `NULO`.
- Persistência atômica em `data/urna.db` sem vínculo entre o título do eleitor e as escolhas da cédula.
- Boletim de Urna disponível após o encerramento, com percentual calculado sobre os votos válidos de cada cargo e desempate pelo menor número de candidato.

## Estrutura do projeto

```text
src/main/java/br/ufmg/urna/          domínio (records, enums e exceções)
src/main/java/br/ufmg/urna/negocio/  regras de votação e apuração (ServicoUrna)
src/main/java/br/ufmg/urna/dados/    leitura CSV e persistência atômica (RepositorioArquivo)
src/main/java/br/ufmg/urna/ui/       servidor HTTP e interface da urna (ServidorHttp)
src/test/java/br/ufmg/urna/          testes automatizados JUnit 5
data/                                carga inicial de eleitores e candidatos
docs/                                PDFs das Sprints, diagramas UML e especificação
```

## Branches

- `dev`: desenvolvimento e execução local com Docker.
- `main`: produção publicada no Render (`https://urna-eleitoral.onrender.com`).

## Declaração de uso de IA

Ferramentas de IA (Codex e Gemini) foram utilizadas como auxílio na análise de requisitos, implementação, geração de diagramas e testes JUnit 5, com revisão e validação integral pelos integrantes do grupo.
