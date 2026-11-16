package br.ufmg.urna;

import br.ufmg.urna.dados.DadosUrna;
import br.ufmg.urna.dados.RepositorioUrna;
import br.ufmg.urna.negocio.ServicoUrna;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServicoUrnaTest {
    @Test
    void exigeSenhaEEstadoCorretosParaAbrir() throws Exception {
        ServicoUrna urna = urnaComEleitores(1);
        assertThrows(RecursoInvalidoException.class, () -> urna.abrir("errada"));
        urna.abrir("123");
        assertEquals(EstadoUrna.ABERTA, urna.estado());
        assertThrows(RecursoInvalidoException.class, () -> urna.abrir("123"));
    }

    @Test
    void rejeitaTituloInexistenteSemCriarParticipacao() throws Exception {
        ServicoUrna urna = urnaComEleitores(1);
        urna.abrir("123");
        assertThrows(RecursoInvalidoException.class, () -> urna.identificar("999"));
        assertEquals("Eleitor 1", urna.identificar("1001").nome());
    }

    @Test
    void votoConfirmadoNaoPodeSerAlteradoEEmpatesUsamMenorNumero() throws Exception {
        ServicoUrna urna = urnaComEleitores(3);
        urna.abrir("123");
        urna.registrarVoto("1001", Escolha.nominal(10), Escolha.nominal(101), Escolha.nominal(102));
        urna.registrarVoto("1002", Escolha.nominal(20), Escolha.nominal(102), Escolha.nominal(103));
        urna.registrarAbstencao("1003");
        assertThrows(RecursoInvalidoException.class, () -> urna.registrarVoto("1001", Escolha.nominal(20), Escolha.nominal(101), Escolha.nominal(102)));
        urna.encerrar("123");

        BoletimUrna boletim = urna.apurar();
        assertEquals(6, boletim.votosValidos());
        assertEquals(3, boletim.comparecimentos());
        assertEquals(1, boletim.abstencoes());
        assertTrue(boletim.eleitos().contains(new Candidato(Cargo.PRESIDENTE, 10, "Presidente 10")));
        assertTrue(boletim.eleitos().contains(new Candidato(Cargo.DEPUTADO_FEDERAL, 101, "Deputado 101")));
        assertTrue(boletim.eleitos().contains(new Candidato(Cargo.DEPUTADO_FEDERAL, 102, "Deputado 102")));
    }

    @Test
    void candidatoInvalidoNaoMarcaEleitorComoParticipante() throws Exception {
        ServicoUrna urna = urnaComEleitores(1);
        urna.abrir("123");
        assertThrows(RecursoInvalidoException.class,
                () -> urna.registrarVoto("1001", Escolha.nominal(999), Escolha.branco(), Escolha.nulo()));
        assertEquals("Eleitor 1", urna.identificar("1001").nome());
    }

    @Test
    void semVotosValidosNaoDeclaraEleitosEPercentuaisSaoZero() throws Exception {
        ServicoUrna urna = urnaComEleitores(2);
        urna.abrir("123");
        urna.registrarVoto("1001", Escolha.branco(), Escolha.nulo(), Escolha.branco());
        urna.registrarAbstencao("1002");
        urna.encerrar("123");
        BoletimUrna boletim = urna.apurar();
        assertFalse(boletim.houveVotosValidos());
        assertTrue(boletim.eleitos().isEmpty());
        assertEquals(2, boletim.votosBrancos());
        assertEquals(1, boletim.votosNulos());
        assertTrue(boletim.percentualPorCandidato().values().stream().allMatch(p -> p == 0.0));
    }

    private ServicoUrna urnaComEleitores(int quantidade) throws IOException {
        Map<String, Eleitor> eleitores = new LinkedHashMap<>();
        for (int i = 1; i <= quantidade; i++) eleitores.put("100" + i, new Eleitor("100" + i, "Eleitor " + i, false));
        List<Candidato> candidatos = List.of(
                new Candidato(Cargo.PRESIDENTE, 10, "Presidente 10"),
                new Candidato(Cargo.PRESIDENTE, 20, "Presidente 20"),
                new Candidato(Cargo.DEPUTADO_FEDERAL, 101, "Deputado 101"),
                new Candidato(Cargo.DEPUTADO_FEDERAL, 102, "Deputado 102"),
                new Candidato(Cargo.DEPUTADO_FEDERAL, 103, "Deputado 103"));
        DadosUrna iniciais = new DadosUrna(EstadoUrna.FECHADA, eleitores, candidatos, List.of(), 0);
        return new ServicoUrna(new RepositorioUrna() {
            private DadosUrna dados = iniciais;
            @Override public DadosUrna carregar() { return dados.copia(); }
            @Override public void salvar(DadosUrna novos) { dados = novos.copia(); }
        }, "123");
    }
}
