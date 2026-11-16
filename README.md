# Sistema de Urna Eletrônica — DCC603 (Engenharia de Software I — UFMG)

Aplicação web local para a simulação acadêmica de uma eleição de turno único (**Tema 2 — Trabalho Prático de Engenharia de Software I**, Prof. Eduardo Figueiredo). O sistema utiliza **Java 17**, **Maven**, **JUnit 5** e **Docker Compose**, atendendo ao fluxo completo de operação do mesário (credenciado pelo TSE) e de votação do eleitor.

## Integrantes — Grupo 2

- **Davi Ladeira Balsamão** (*Scrum Master & Desenvolvedor*)
- **Ítalo Leal Lana Santos** (*Product Owner & Desenvolvedor*)
- **Thiago Amorim Bastos** (*Desenvolvedor*)
- **Vitor Hugo Dias Santos** (*Desenvolvedor*)

## Documentação e Especificação (`docs/`)

Conforme exigido nas instruções do Trabalho Prático, todos os documentos e diagramas UML encontram-se em formato **PDF** (além dos fontes `.puml` e `.png`) no diretório [`docs/`](./docs/README.md):

- 📄 [**Documentação Completa Consolidada (Sprints 1, 2 e 3) — PDF**](./docs/Documentacao_Completa_TP_Urna_Eletronica.pdf)
- 📄 [**Sprint 1 — Modelo de Processo (Scrum), Cronograma e Requisitos — PDF**](./docs/Sprint1_Planejamento_e_Requisitos.pdf)
- 📄 [**Sprint 2 — Casos de Uso, Cenários, Arquitetura e Modelagem UML — PDF**](./docs/Sprint2_CasosDeUso_Arquitetura_Modelagem.pdf)
- 🗂️ [**Diagramas UML (`.puml` e `.png`)**](./docs/diagramas/): Casos de Uso, Componentes, Classes, Sequência e Máquina de Estados.
- 📘 [**Especificação Oficial do TP (`docs/especificacao/`)**](./docs/especificacao/):
  - [`tp-instrucao.pdf`](./docs/especificacao/tp-instrucao.pdf) — Instruções gerais do Trabalho Prático
  - [`tp-temas.pdf`](./docs/especificacao/tp-temas.pdf) — Lista de temas (Tema 2: Sistema de urna eletrônica)
  - [`tp-slides.pdf`](./docs/especificacao/tp-slides.pdf) — Diretrizes, critérios de avaliação e entregas
  - [`tp-alocacao.pdf`](./docs/especificacao/tp-alocacao.pdf) — Alocação de grupos e ordem de apresentação

---

## Executar localmente (Docker Compose)

Requisitos: Docker Engine, Docker Compose v2 e acesso à internet na primeira compilação para baixar as imagens e dependências. No Windows ou macOS, pode-se usar Docker Desktop; no Linux, Docker Engine com o plugin Docker Compose. Não é necessário instalar Java ou Maven no computador: o `Dockerfile` compila o projeto com Java 17 e executa os testes automatizados durante a criação da imagem.

No Linux, a conta precisa ter permissão para acessar o Docker Engine. Se os comandos retornarem erro de permissão, use `sudo` antes de `docker compose` ou configure o acesso do seu usuário ao Docker. Antes de iniciar, confirme que `docker version` mostra as seções Client e Server e que `docker compose version` mostra a versão do Compose:

```text
docker version
docker compose version
```

Na raiz do projeto, execute:

```text
docker compose up --build -d
```

Abra [http://localhost:8080](http://localhost:8080). Na primeira execução, a urna importa os arquivos CSV de exemplo em `data/` e cria `urna.db` em `/app/data`, dentro do volume Docker `dados-urna`.

Se a porta `8080` já estiver ocupada, altere o lado esquerdo do mapeamento `8080:8080` no `compose.yaml`, por exemplo para `8081:8080`, e abra `http://localhost:8081`.

A senha inicial de demonstração do mesário é `123456`. Para trocar, crie um arquivo `.env` na raiz com `URNA_SENHA=sua-senha` antes de executar o Compose.

```text
docker compose logs -f
```

Esse comando acompanha os logs de inicialização. Para encerrar a aplicação sem apagar os dados, use `docker compose down`. Para iniciá-la novamente, use `docker compose up -d`.

Para zerar a urna e apagar votos/participação gravados, execute `docker compose down -v`; na próxima inicialização, os dados de demonstração serão carregados de novo. Essa operação apaga permanentemente os dados persistidos no volume.

O servidor lê `PORT` (padrão `8080`) e `URNA_DADOS_DIR` (padrão `data`). Os caminhos de carga podem ser alterados com `URNA_ELEITORES_CSV` e `URNA_CANDIDATOS_CSV`.

## Executar e testar localmente com Java 17 e Maven (sem Docker)

Caso possua JDK 17+ e Maven instalados localmente:

```text
mvn test
mvn package
java -jar target/urna-eleitoral-1.0.0.jar
```

## Preparar eleitores e candidatos

Edite os CSVs antes da primeira execução, mantendo os cabeçalhos e os cargos aceitos:

- `data/eleitores.csv`: `titulo,nome`
- `data/candidatos.csv`: `cargo,numero,nome`; `cargo` é `PRESIDENTE` ou `DEPUTADO_FEDERAL`.

Para iniciar uma eleição limpa e recarregar os CSVs, execute `docker compose down -v` e depois `docker compose up --build -d`. Isso apaga o volume Docker `dados-urna`, que contém o arquivo persistente da eleição. Faça backup do volume antes de apagá-lo se precisar conservar os resultados.

## Regras implementadas

- A urna passa pelos estados `FECHADA`, `ABERTA` e `ENCERRADA`; abertura e encerramento exigem a senha do mesário, e a votação e a apuração respeitam estritamente esses estados.
- O eleitor se identifica pelo número do título. Um título inexistente ou que já tenha participado é rejeitado. Após informar um título válido, a urna apresenta o nome do eleitor para confirmação antes de iniciar a votação.
- O eleitor registra uma escolha para **Presidente** e duas escolhas para **Deputado Federal** (exigindo candidatos distintos quando ambos os votos para deputado federal forem nominais). Cada escolha pode ser nominal, `BRANCO` ou `NULO`. Número inválido ou duplicado é informado e precisa ser corrigido antes da confirmação; na tela de revisão o eleitor pode confirmar ou voltar para editar.
- A confirmação grava as três escolhas como uma cédula anônima, sem título, horário ou identificador de eleitor (`RNF01`). A participação do eleitor fica em registro separado. Abstenções também são contabilizadas marcando o comparecimento do eleitor sem gravar cédula de voto.
- O Boletim de Urna fica disponível após o encerramento. Os percentuais de cada candidato usam o total de votos nominais válidos do respectivo cargo como denominador (excluindo brancos, nulos e abstenções); se não houver votos válidos no cargo, os percentuais são `0,00%` e não há eleitos.
- Em caso de empate na última vaga, vence o candidato de menor número. Candidatos sem votos válidos não são declarados eleitos.
- O sistema mantém os dados em um único arquivo local (`urna.db`) e o substitui atomicamente (`ATOMIC_MOVE`) após cada operação persistida.

O `Dockerfile` e o `compose.yaml` são voltados à execução local. O modo atual é uma demonstração acadêmica e não foi projetado para uma eleição real.

## Estrutura do Repositório

```text
src/main/java/br/ufmg/urna/          modelo de domínio (records, enums e exceções)
src/main/java/br/ufmg/urna/negocio/  regras de negócio de votação e apuração (ServicoUrna)
src/main/java/br/ufmg/urna/dados/    carga CSV e persistência atômica local (RepositorioArquivo)
src/main/java/br/ufmg/urna/ui/       interface web HTTP e formulários (ServidorHttp)
src/test/java/br/ufmg/urna/          suíte de testes automatizados JUnit 5 (negócio, persistência e HTTP)
data/                                dados fictícios para demonstração (eleitores.csv e candidatos.csv)
docs/                                documentação completa em PDF (Sprints 1, 2 e 3) e índice
docs/diagramas/                      diagramas UML em PlantUML (.puml) e PNG (.png)
docs/especificacao/                  especificação oficial do Trabalho Prático (PDFs da disciplina)
```

## Uso de ferramentas de IA

Em conformidade com o item 8 das instruções do Trabalho Prático, o desenvolvimento contou com assistência de ferramentas de IA generativa (OpenAI Codex e Google Gemini / Antigravity) para analisar as especificações, planejar a arquitetura, implementar código e testes automatizados em JUnit 5, gerar diagramas PlantUML/UML e revisar a consistência da documentação em PDF. Todos os artefatos foram revisados e validados pelos integrantes do Grupo 2.
