package br.ufmg.urna.dados;

import br.ufmg.urna.Candidato;
import br.ufmg.urna.Cargo;
import br.ufmg.urna.Eleitor;
import br.ufmg.urna.EstadoUrna;
import br.ufmg.urna.Escolha;
import br.ufmg.urna.TipoVoto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Persistência local em arquivo único: cada substituição é atômica e não grava título junto à cédula. */
public final class RepositorioArquivo implements RepositorioUrna {
    private final Path arquivo;
    private final Path eleitoresCsv;
    private final Path candidatosCsv;

    public RepositorioArquivo(Path arquivo, Path eleitoresCsv, Path candidatosCsv) {
        this.arquivo = arquivo;
        this.eleitoresCsv = eleitoresCsv;
        this.candidatosCsv = candidatosCsv;
    }

    @Override
    public synchronized DadosUrna carregar() throws IOException {
        if (Files.exists(arquivo)) return lerArquivo();
        return recarregarInicial();
    }

    @Override
    public synchronized DadosUrna recarregarInicial() throws IOException {
        DadosUrna dados = carregarCargaInicial();
        salvar(dados);
        return dados;
    }

    private DadosUrna carregarCargaInicial() throws IOException {
        Map<String, Eleitor> eleitores = new LinkedHashMap<>();
        List<Candidato> candidatos = new ArrayList<>();
        for (String linha : Files.readAllLines(eleitoresCsv, StandardCharsets.UTF_8).stream().skip(1).toList()) {
            if (linha.isBlank()) continue;
            String[] campos = linha.split(",", 2);
            if (campos.length != 2) throw new IOException("Linha inválida em " + eleitoresCsv + ": " + linha);
            String titulo = campos[0].trim();
            if (titulo.isEmpty() || campos[1].trim().isEmpty() || eleitores.putIfAbsent(titulo, new Eleitor(titulo, campos[1].trim(), false)) != null)
                throw new IOException("Título/nome vazio ou título duplicado em " + eleitoresCsv + ": " + titulo);
        }
        for (String linha : Files.readAllLines(candidatosCsv, StandardCharsets.UTF_8).stream().skip(1).toList()) {
            if (linha.isBlank()) continue;
            String[] campos = linha.split(",", 3);
            if (campos.length != 3) throw new IOException("Linha inválida em " + candidatosCsv + ": " + linha);
            try {
                candidatos.add(new Candidato(Cargo.valueOf(campos[0].trim()), Integer.parseInt(campos[1].trim()), campos[2].trim()));
            } catch (RuntimeException e) {
                throw new IOException("Candidato inválido: " + linha, e);
            }
        }
        if (eleitores.isEmpty() || candidatos.isEmpty()) throw new IOException("A carga de eleitores e candidatos não pode estar vazia.");
        Set<String> chavesCandidato = new HashSet<>();
        for (Candidato c : candidatos) {
            String chave = c.cargo() + ":" + c.numero();
            if (c.numero() < 0 || c.nome().isBlank() || !chavesCandidato.add(chave))
                throw new IOException("Número ou nome inválido, ou candidato duplicado: " + chave);
        }
        if (candidatos.stream().noneMatch(c -> c.cargo() == Cargo.PRESIDENTE)
                || candidatos.stream().filter(c -> c.cargo() == Cargo.DEPUTADO_FEDERAL).count() < 2)
            throw new IOException("Cadastre ao menos um candidato a presidente e dois a deputado federal.");
        return new DadosUrna(EstadoUrna.FECHADA, eleitores, candidatos, List.of(), 0);
    }

    private DadosUrna lerArquivo() throws IOException {
        EstadoUrna estado = EstadoUrna.FECHADA;
        int abstencoes = 0;
        Map<String, Eleitor> eleitores = new LinkedHashMap<>();
        List<Candidato> candidatos = new ArrayList<>();
        List<List<Escolha>> cedulas = new ArrayList<>();
        for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            String[] c = linha.split("\\t", -1);
            try {
                switch (c[0]) {
                    case "S" -> estado = EstadoUrna.valueOf(c[1]);
                    case "A" -> abstencoes = Integer.parseInt(c[1]);
                    case "E" -> {
                        String titulo = dec(c[1]), nome = dec(c[2]);
                        eleitores.put(titulo, new Eleitor(titulo, nome, Boolean.parseBoolean(c[3])));
                    }
                    case "C" -> candidatos.add(new Candidato(Cargo.valueOf(c[1]), Integer.parseInt(c[2]), dec(c[3])));
                    case "B" -> cedulas.add(List.of(decodificarEscolha(c[1]), decodificarEscolha(c[2]), decodificarEscolha(c[3])));
                    default -> throw new IllegalArgumentException("Tipo de registro desconhecido");
                }
            } catch (RuntimeException e) {
                throw new IOException("Arquivo de dados inválido na linha: " + linha, e);
            }
        }
        if (eleitores.isEmpty() || candidatos.isEmpty()) throw new IOException("Arquivo de dados incompleto.");
        return new DadosUrna(estado, eleitores, candidatos, cedulas, abstencoes);
    }

    @Override
    public synchronized void salvar(DadosUrna dados) throws IOException {
        Path pasta = arquivo.toAbsolutePath().getParent();
        Files.createDirectories(pasta);
        Path temporario = Files.createTempFile(pasta, "urna-", ".tmp");
        try {
            List<String> linhas = new ArrayList<>();
            linhas.add("S\t" + dados.estado());
            linhas.add("A\t" + dados.abstencoes());
            dados.eleitores().values().forEach(e -> linhas.add("E\t" + enc(e.titulo()) + "\t" + enc(e.nome()) + "\t" + e.participou()));
            dados.candidatos().forEach(c -> linhas.add("C\t" + c.cargo() + "\t" + c.numero() + "\t" + enc(c.nome())));
            // As três escolhas da cédula ficam agrupadas, sem título, horário ou identificador do eleitor.
            dados.cedulas().forEach(c -> linhas.add("B\t" + codificarEscolha(c.get(0)) + "\t" + codificarEscolha(c.get(1)) + "\t" + codificarEscolha(c.get(2))));
            Files.write(temporario, linhas, StandardCharsets.UTF_8);
            try {
                Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    private static String codificarEscolha(Escolha e) {
        return e.tipo() == TipoVoto.NOMINAL ? "N:" + e.numeroCandidato() : e.tipo().name();
    }

    private static Escolha decodificarEscolha(String texto) {
        if (texto.startsWith("N:")) return Escolha.nominal(Integer.parseInt(texto.substring(2)));
        return TipoVoto.valueOf(texto) == TipoVoto.BRANCO ? Escolha.branco() : Escolha.nulo();
    }

    private static String enc(String valor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valor.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String valor) {
        return new String(Base64.getUrlDecoder().decode(valor), StandardCharsets.UTF_8);
    }
}
