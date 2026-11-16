package br.ufmg.urna;

import br.ufmg.urna.dados.RepositorioArquivo;
import br.ufmg.urna.negocio.ServicoUrna;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositorioArquivoTest {
    @TempDir
    Path tempDir;

    @Test
    void carregaCsvEPersisteOperacoesGarantindoSigiloDaCedula() throws Exception {
        Path eleitoresCsv = tempDir.resolve("eleitores.csv");
        Path candidatosCsv = tempDir.resolve("candidatos.csv");
        Path arquivoDb = tempDir.resolve("urna.db");

        Files.writeString(eleitoresCsv, """
                titulo,nome
                10000001,Ana Oliveira
                10000002,Bruno Souza
                """, StandardCharsets.UTF_8);
        Files.writeString(candidatosCsv, """
                cargo,numero,nome
                PRESIDENTE,10,Alex Silva
                DEPUTADO_FEDERAL,101,Debora Alves
                DEPUTADO_FEDERAL,102,Enzo Carvalho
                """, StandardCharsets.UTF_8);

        RepositorioArquivo repo = new RepositorioArquivo(arquivoDb, eleitoresCsv, candidatosCsv);
        ServicoUrna urna = new ServicoUrna(repo, "senha123");
        urna.abrir("senha123");
        urna.registrarVoto("10000001", Escolha.nominal(10), Escolha.nominal(101), Escolha.branco());
        urna.registrarAbstencao("10000002");
        urna.encerrar("senha123");

        List<String> linhasCedula = Files.readAllLines(arquivoDb, StandardCharsets.UTF_8).stream()
                .filter(l -> l.startsWith("B\t"))
                .toList();
        assertEquals(1, linhasCedula.size());
        assertEquals("B\tN:10\tN:101\tBRANCO", linhasCedula.get(0));
        assertFalse(linhasCedula.get(0).contains("10000001"));

        ServicoUrna urnaRecarregada = new ServicoUrna(new RepositorioArquivo(arquivoDb, eleitoresCsv, candidatosCsv), "senha123");
        assertEquals(EstadoUrna.ENCERRADA, urnaRecarregada.estado());
        BoletimUrna boletim = urnaRecarregada.apurar();
        assertEquals(2, boletim.comparecimentos());
        assertEquals(1, boletim.abstencoes());
        assertEquals(2, boletim.votosValidos());
        assertEquals(1, boletim.votosBrancos());
    }

    @Test
    void rejeitaCargaInicialComTituloDuplicadoOuCandidatosInsuficientes() throws Exception {
        Path eleitoresDuplicados = tempDir.resolve("eleitores_dup.csv");
        Path candidatosValidos = tempDir.resolve("candidatos_ok.csv");
        Files.writeString(eleitoresDuplicados, """
                titulo,nome
                1001,Ana
                1001,Bruno
                """, StandardCharsets.UTF_8);
        Files.writeString(candidatosValidos, """
                cargo,numero,nome
                PRESIDENTE,10,Alex
                DEPUTADO_FEDERAL,101,Debora
                DEPUTADO_FEDERAL,102,Enzo
                """, StandardCharsets.UTF_8);

        RepositorioArquivo repoDup = new RepositorioArquivo(tempDir.resolve("dup.db"), eleitoresDuplicados, candidatosValidos);
        assertThrows(IOException.class, repoDup::carregar);

        Path eleitoresValidos = tempDir.resolve("eleitores_ok.csv");
        Path candidatosInsuficientes = tempDir.resolve("candidatos_insuf.csv");
        Files.writeString(eleitoresValidos, "titulo,nome\n1001,Ana\n", StandardCharsets.UTF_8);
        Files.writeString(candidatosInsuficientes, """
                cargo,numero,nome
                PRESIDENTE,10,Alex
                DEPUTADO_FEDERAL,101,Debora
                """, StandardCharsets.UTF_8);

        RepositorioArquivo repoInsuf = new RepositorioArquivo(tempDir.resolve("insuf.db"), eleitoresValidos, candidatosInsuficientes);
        assertThrows(IOException.class, repoInsuf::carregar);
    }
}
