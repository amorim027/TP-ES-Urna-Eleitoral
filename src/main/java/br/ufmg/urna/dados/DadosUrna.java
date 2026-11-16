package br.ufmg.urna.dados;

import br.ufmg.urna.Candidato;
import br.ufmg.urna.Eleitor;
import br.ufmg.urna.EstadoUrna;
import br.ufmg.urna.Escolha;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DadosUrna {
    private EstadoUrna estado;
    private final Map<String, Eleitor> eleitores;
    private final List<Candidato> candidatos;
    private final List<List<Escolha>> cedulas;
    private int abstencoes;

    public DadosUrna(EstadoUrna estado, Map<String, Eleitor> eleitores,
                     List<Candidato> candidatos, List<List<Escolha>> cedulas, int abstencoes) {
        this.estado = estado;
        this.eleitores = new LinkedHashMap<>(eleitores);
        this.candidatos = new ArrayList<>(candidatos);
        this.cedulas = new ArrayList<>();
        cedulas.forEach(c -> this.cedulas.add(new ArrayList<>(c)));
        this.abstencoes = abstencoes;
    }

    public EstadoUrna estado() { return estado; }
    public void estado(EstadoUrna estado) { this.estado = estado; }
    public Map<String, Eleitor> eleitores() { return eleitores; }
    public List<Candidato> candidatos() { return candidatos; }
    public List<List<Escolha>> cedulas() { return cedulas; }
    public int abstencoes() { return abstencoes; }
    public void registrarAbstencao() { abstencoes++; }

    public DadosUrna copia() {
        return new DadosUrna(estado, eleitores, candidatos, cedulas, abstencoes);
    }
}
