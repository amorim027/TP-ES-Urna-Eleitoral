package br.ufmg.urna;

import java.util.List;
import java.util.Map;

public record BoletimUrna(
        int eleitoresAptos,
        int comparecimentos,
        int abstencoes,
        int votosValidos,
        int votosBrancos,
        int votosNulos,
        Map<Candidato, Integer> votosPorCandidato,
        Map<Candidato, Double> percentualPorCandidato,
        List<Candidato> eleitos,
        List<Candidato> derrotados,
        boolean houveVotosValidos) { }
