package br.ufmg.urna;

import br.ufmg.urna.dados.DadosUrna;
import br.ufmg.urna.dados.RepositorioUrna;
import br.ufmg.urna.negocio.ServicoUrna;
import br.ufmg.urna.ui.ServidorHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServidorHttpTest {
    private ServidorHttp servidor;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Eleitor> eleitores = new LinkedHashMap<>();
        eleitores.put("1001", new Eleitor("1001", "Ana Oliveira", false));
        eleitores.put("1002", new Eleitor("1002", "Bruno Souza", false));
        List<Candidato> candidatos = List.of(
                new Candidato(Cargo.PRESIDENTE, 10, "Alex Silva"),
                new Candidato(Cargo.PRESIDENTE, 20, "Beatriz Rocha"),
                new Candidato(Cargo.DEPUTADO_FEDERAL, 101, "Debora Alves"),
                new Candidato(Cargo.DEPUTADO_FEDERAL, 102, "Enzo Carvalho"));
        DadosUrna iniciais = new DadosUrna(EstadoUrna.FECHADA, eleitores, candidatos, List.of(), 0);
        ServicoUrna urna = new ServicoUrna(new RepositorioUrna() {
            private DadosUrna dados = iniciais;
            @Override public DadosUrna carregar() { return dados.copia(); }
            @Override public void salvar(DadosUrna novos) { dados = novos.copia(); }
        }, "123456");

        servidor = new ServidorHttp(urna);
        servidor.iniciar(0);
        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + servidor.porta();
    }

    @AfterEach
    void tearDown() {
        if (servidor != null) servidor.parar();
    }

    @Test
    void executaFluxoCompletoDeVotacaoEEmissaoDeBoletimViaHttp() throws Exception {
        HttpResponse<String> painelInicial = get("/");
        assertEquals(200, painelInicial.statusCode());
        assertTrue(painelInicial.body().contains("FECHADA"));

        HttpResponse<String> abrir = post("/abrir", "senha=123456");
        assertTrue(abrir.body().contains("ABERTA"));

        HttpResponse<String> identificar = post("/identificar", "titulo=1001");
        assertTrue(identificar.body().contains("Ana Oliveira"));

        HttpResponse<String> conferirDuplicado = post("/conferir", "titulo=1001&presidente=10&deputado1=101&deputado2=101");
        assertTrue(conferirDuplicado.body().contains("Escolha dois candidatos distintos para Deputado federal."));

        HttpResponse<String> conferirValido = post("/conferir", "titulo=1001&presidente=10&deputado1=101&deputado2=102");
        assertTrue(conferirValido.body().contains("Confirme suas escolhas"));
        assertTrue(conferirValido.body().contains("Alex Silva (10)"));

        HttpResponse<String> confirmar = post("/confirmar", "titulo=1001&presidente=10&deputado1=101&deputado2=102");
        assertTrue(confirmar.body().contains("Voto confirmado e registrado."));

        HttpResponse<String> abster = post("/abster", "titulo=1002");
        assertTrue(abster.body().contains("Abstenção registrada."));

        HttpResponse<String> encerrar = post("/encerrar", "senha=123456");
        assertTrue(encerrar.body().contains("ENCERRADA"));

        HttpResponse<String> boletim = get("/resultado");
        assertEquals(200, boletim.statusCode());
        assertTrue(boletim.body().contains("BOLETIM DE URNA"));
        assertTrue(boletim.body().contains("100,00%"));
        assertTrue(boletim.body().contains("50,00%"));

        HttpResponse<String> naoEncontrado = get("/rota-inexistente");
        assertEquals(404, naoEncontrado.statusCode());
    }

    private HttpResponse<String> get(String caminho) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + caminho)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String caminho, String formBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + caminho))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
