package br.ufmg.urna.negocio;

import br.ufmg.urna.BoletimUrna;
import br.ufmg.urna.Candidato;
import br.ufmg.urna.Cargo;
import br.ufmg.urna.Eleitor;
import br.ufmg.urna.EstadoUrna;
import br.ufmg.urna.Escolha;
import br.ufmg.urna.RecursoInvalidoException;
import br.ufmg.urna.TipoVoto;
import br.ufmg.urna.dados.DadosUrna;
import br.ufmg.urna.dados.RepositorioUrna;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ServicoUrna {
    private final RepositorioUrna repositorio;
    private final String senha;
    private DadosUrna dados;

    public ServicoUrna(RepositorioUrna repositorio, String senha) throws IOException {
        this.repositorio = Objects.requireNonNull(repositorio);
        if (senha == null || senha.isBlank()) throw new IllegalArgumentException("Configure uma senha não vazia.");
        this.senha = senha;
        this.dados = repositorio.carregar();
    }

    public synchronized EstadoUrna estado() { return dados.estado(); }
    public synchronized List<Candidato> candidatos(Cargo cargo) {
        return dados.candidatos().stream().filter(c -> c.cargo() == cargo).sorted(Comparator.comparingInt(Candidato::numero)).toList();
    }
    public synchronized int eleitoresAptos() { return dados.eleitores().size(); }

    public synchronized void abrir(String senhaDigitada) {
        if (!senha.equals(senhaDigitada)) throw new RecursoInvalidoException("Senha incorreta.");
        if (dados.estado() != EstadoUrna.FECHADA) throw new RecursoInvalidoException("A urna não está fechada.");
        alterar(d -> d.estado(EstadoUrna.ABERTA));
    }

    public synchronized void encerrar(String senhaDigitada) {
        if (!senha.equals(senhaDigitada)) throw new RecursoInvalidoException("Senha incorreta.");
        if (dados.estado() != EstadoUrna.ABERTA) throw new RecursoInvalidoException("A urna não está aberta.");
        alterar(d -> d.estado(EstadoUrna.ENCERRADA));
    }

    public synchronized Eleitor identificar(String titulo) {
        exigirEstado(EstadoUrna.ABERTA);
        Eleitor eleitor = dados.eleitores().get(titulo == null ? "" : titulo.trim());
        if (eleitor == null) throw new RecursoInvalidoException("Título não encontrado. Confira o número e tente novamente.");
        if (eleitor.participou()) throw new RecursoInvalidoException("Este título já participou da votação.");
        return eleitor;
    }

    public synchronized void registrarAbstencao(String titulo) {
        Eleitor eleitor = identificar(titulo);
        alterar(d -> {
            d.eleitores().put(eleitor.titulo(), eleitor.comParticipacao());
            d.registrarAbstencao();
        });
    }

    public synchronized void registrarVoto(String titulo, Escolha presidente, Escolha deputado1, Escolha deputado2) {
        Eleitor eleitor = identificar(titulo);
        validarEscolha(presidente, Cargo.PRESIDENTE);
        validarEscolha(deputado1, Cargo.DEPUTADO_FEDERAL);
        validarEscolha(deputado2, Cargo.DEPUTADO_FEDERAL);
        if (deputado1.tipo() == TipoVoto.NOMINAL && deputado2.tipo() == TipoVoto.NOMINAL
                && Objects.equals(deputado1.numeroCandidato(), deputado2.numeroCandidato())) {
            throw new RecursoInvalidoException("Escolha dois candidatos distintos para Deputado federal.");
        }
        alterar(d -> {
            d.eleitores().put(eleitor.titulo(), eleitor.comParticipacao());
            d.cedulas().add(List.of(presidente, deputado1, deputado2));
        });
    }

    public synchronized BoletimUrna apurar() {
        exigirEstado(EstadoUrna.ENCERRADA);
        Map<Candidato, Integer> totais = new LinkedHashMap<>();
        Map<Cargo, Integer> validosPorCargo = new LinkedHashMap<>();
        dados.candidatos().forEach(c -> totais.put(c, 0));
        int brancos = 0, nulos = 0, validos = 0;
        for (List<Escolha> cedula : dados.cedulas()) {
            for (int posicao = 0; posicao < cedula.size(); posicao++) {
                Escolha escolha = cedula.get(posicao);
                if (escolha.tipo() == TipoVoto.BRANCO) brancos++;
                else if (escolha.tipo() == TipoVoto.NULO) nulos++;
                else {
                    Cargo cargo = posicao == 0 ? Cargo.PRESIDENTE : Cargo.DEPUTADO_FEDERAL;
                    Candidato candidato = procurar(escolha.numeroCandidato(), cargo);
                    if (candidato != null) {
                        totais.merge(candidato, 1, Integer::sum);
                        validosPorCargo.merge(cargo, 1, Integer::sum);
                        validos++;
                    }
                }
            }
        }
        Map<Candidato, Double> percentuais = new LinkedHashMap<>();
        totais.forEach((c, n) -> {
            int denominador = validosPorCargo.getOrDefault(c.cargo(), 0);
            percentuais.put(c, denominador == 0 ? 0.0 : n * 100.0 / denominador);
        });
        List<Candidato> eleitos = new ArrayList<>();
        if (validos > 0) {
            escolher(dados.candidatos(), Cargo.PRESIDENTE, totais, 1).forEach(eleitos::add);
            escolher(dados.candidatos(), Cargo.DEPUTADO_FEDERAL, totais, 2).forEach(eleitos::add);
        }
        List<Candidato> derrotados = dados.candidatos().stream().filter(c -> !eleitos.contains(c))
                .sorted(Comparator.comparing(Candidato::cargo).thenComparingInt(Candidato::numero)).toList();
        int comparecimentos = (int) dados.eleitores().values().stream().filter(Eleitor::participou).count();
        return new BoletimUrna(dados.eleitores().size(), comparecimentos, dados.abstencoes(), validos,
                brancos, nulos, Map.copyOf(totais), Map.copyOf(percentuais), List.copyOf(eleitos), derrotados, validos > 0);
    }

    private List<Candidato> escolher(List<Candidato> lista, Cargo cargo, Map<Candidato, Integer> totais, int quantidade) {
        return lista.stream().filter(c -> c.cargo() == cargo)
                .filter(c -> totais.getOrDefault(c, 0) > 0)
                .sorted(Comparator.<Candidato>comparingInt(c -> totais.getOrDefault(c, 0)).reversed()
                        .thenComparingInt(Candidato::numero))
                .limit(quantidade).toList();
    }

    private Candidato procurar(Integer numero, Cargo cargo) {
        return dados.candidatos().stream().filter(c -> c.cargo() == cargo && Objects.equals(c.numero(), numero)).findFirst().orElse(null);
    }

    private void validarEscolha(Escolha escolha, Cargo cargo) {
        if (escolha == null || escolha.tipo() == null) throw new RecursoInvalidoException("Escolha de voto ausente.");
        if (escolha.tipo() == TipoVoto.NOMINAL) {
            boolean existe = dados.candidatos().stream().anyMatch(c -> c.cargo() == cargo && Objects.equals(c.numero(), escolha.numeroCandidato()));
            if (!existe) throw new RecursoInvalidoException("Número inválido para " + cargo.descricao() + ". Corrija antes de confirmar.");
        } else if (escolha.numeroCandidato() != null) {
            throw new RecursoInvalidoException("Voto branco ou nulo não pode conter número de candidato.");
        }
    }

    private void exigirEstado(EstadoUrna esperado) {
        if (dados.estado() != esperado) throw new RecursoInvalidoException("Operação não permitida: a urna está " + dados.estado().name().toLowerCase() + ".");
    }

    private void alterar(java.util.function.Consumer<DadosUrna> operacao) {
        DadosUrna novo = dados.copia();
        operacao.accept(novo);
        try {
            repositorio.salvar(novo);
            dados = novo;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível salvar a operação; os dados anteriores foram preservados.", e);
        }
    }
}
