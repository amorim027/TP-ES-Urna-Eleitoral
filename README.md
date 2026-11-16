# Sistema de Urna Eletrônica

Aplicação web local para a simulação acadêmica de uma eleição de turno único. O sistema usa Java 17, Maven e JUnit 5. A interface abre no navegador e atende ao fluxo de mesário e eleitor.

## Executar localmente

Requisitos: Docker Engine, Docker Compose v2 e acesso à internet na primeira compilação para baixar as imagens e dependências. No Windows ou macOS, pode-se usar Docker Desktop; no Linux, Docker Engine com o plugin Docker Compose. Não é necessário instalar Java ou Maven no computador: o Dockerfile compila o projeto com Java 17 e executa os testes durante a criação da imagem.

No Linux, a conta precisa ter permissão para acessar o Docker Engine. Se os comandos retornarem erro de permissão, use `sudo` antes de `docker compose` ou configure o acesso do seu usuário ao Docker. Antes de iniciar, confirme que `docker version` mostra as seções Client e Server e que `docker compose version` mostra a versão do Compose.

```text
docker version
docker compose version
```

Na raiz do projeto, execute:

```text
docker compose up --build -d
```

Abra [http://localhost:8080](http://localhost:8080). Na primeira execução, a urna importa os arquivos CSV de exemplo em `data/` e cria `urna.db` em `/app/data`, dentro do volume Docker `dados-urna`.

Se a porta 8080 já estiver ocupada, altere o lado esquerdo do mapeamento `8080:8080` no `compose.yaml`, por exemplo para `8081:8080`, e abra `http://localhost:8081`.

A senha inicial de demonstração é `123456`. Para trocar, crie um arquivo `.env` na raiz com `URNA_SENHA=sua-senha` antes de executar o Compose.

```text
docker compose logs -f
```

Esse comando acompanha os logs de inicialização. Para encerrar a aplicação sem apagar os dados, use `docker compose down`. Para iniciá-la novamente, use `docker compose up -d`.

Para zerar a urna e apagar votos/participação gravados, execute `docker compose down -v`; na próxima inicialização, os dados de demonstração serão carregados de novo. Essa operação apaga permanentemente os dados persistidos no volume.

O servidor lê `PORT` (padrão `8080`) e `URNA_DADOS_DIR` (padrão `data`). Os caminhos de carga podem ser alterados com `URNA_ELEITORES_CSV` e `URNA_CANDIDATOS_CSV`.

## Preparar eleitores e candidatos

Edite os CSVs antes da primeira execução, mantendo os cabeçalhos e os cargos aceitos:

- `data/eleitores.csv`: `titulo,nome`
- `data/candidatos.csv`: `cargo,numero,nome`; `cargo` é `PRESIDENTE` ou `DEPUTADO_FEDERAL`.

Para iniciar uma eleição limpa e recarregar os CSVs, execute `docker compose down -v` e depois `docker compose up --build -d`. Isso apaga o volume Docker `dados-urna`, que contém o arquivo persistente da eleição. Faça backup do volume antes de apagá-lo se precisar conservar os resultados.

## Regras implementadas

- A urna passa por `FECHADA`, `ABERTA` e `ENCERRADA`; abertura, encerramento e apuração respeitam esses estados.
- O eleitor se identifica pelo título. Um título inexistente é rejeitado. Não há confirmação de identidade pelo mesário.
- O eleitor registra uma escolha para presidente e duas para deputado federal. Cada escolha pode ser nominal, branca ou nula. Número inválido é informado e precisa ser corrigido antes da confirmação.
- A confirmação grava as três escolhas como uma cédula sem título, horário ou identificador de eleitor. A participação do eleitor fica em registro separado. Abstenções também são contadas sem guardar a escolha de voto.
- O boletim fica disponível após o encerramento. Percentuais usam a soma de votos nominais válidos como denominador; se ela for zero, os percentuais são 0% e não há eleitos.
- Em empate na última vaga, vence o menor número de candidato. Candidatos sem votos não são declarados eleitos.
- O sistema mantém os dados em um único arquivo local e o substitui atomicamente após cada operação persistida.

O `Dockerfile` e o `compose.yaml` são voltados à execução local. O modo atual é uma demonstração acadêmica e não foi projetado para uma eleição real.

## Estrutura

```text
src/main/java/br/ufmg/urna/          domínio da urna
src/main/java/br/ufmg/urna/negocio/ regras de votação e apuração
src/main/java/br/ufmg/urna/dados/   carga CSV e persistência local
src/main/java/br/ufmg/urna/ui/      interface web e formulários
src/test/java/                      testes automatizados JUnit 5
data/                                dados fictícios para demonstração
```

## Uso de ferramentas de IA

O desenvolvimento contou com assistência do Codex para analisar as especificações, planejar a implementação, criar código e testes, e revisar documentação. Os integrantes do grupo devem revisar os artefatos e registrar esse uso na entrega, conforme as instruções da disciplina.
