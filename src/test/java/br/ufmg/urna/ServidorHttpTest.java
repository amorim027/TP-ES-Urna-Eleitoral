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
            @Override public DadosUrna recarregarInicial() { dados = iniciais.copia(); return dados.copia(); }
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

        HttpResponse<String> urnaFechada = get("/votar");
        assertEquals(200, urnaFechada.statusCode());
        assertTrue(urnaFechada.body().contains("FECHADA"));

        HttpResponse<String> senhaErrada = post("/abrir", "senha=errada");
        assertEquals(200, senhaErrada.statusCode());
        assertTrue(senhaErrada.body().contains("FECHADA"));
        assertTrue(senhaErrada.body().contains("Senha incorreta."));

        HttpResponse<String> abrir = post("/abrir", "senha=123456");
        assertTrue(abrir.body().contains("ABERTA"));
        assertEquals(13, contarBotoes(abrir.body()));

        HttpResponse<String> menuMesario = get("/?tela=menu");
        assertEquals(200, menuMesario.statusCode());
        assertTrue(menuMesario.body().contains("Encerrar votação"));
        assertEquals(13, contarBotoes(menuMesario.body()));

        HttpResponse<String> encerrarSenhaErrada = post("/encerrar", "senha=000000");
        assertEquals(200, encerrarSenhaErrada.statusCode());
        assertTrue(encerrarSenhaErrada.body().contains("Senha incorreta."));
        assertTrue(encerrarSenhaErrada.body().contains("Encerrar votação"));
        assertEquals(13, contarBotoes(encerrarSenhaErrada.body()));

        HttpResponse<String> urnaBloqueada = get("/votar");
        assertTrue(urnaBloqueada.body().contains("Aguardando liberação"));

        HttpResponse<String> votoSemLiberar = post("/conferir", "titulo=1001&presidente=10&deputado1=101&deputado2=102");
        assertTrue(votoSemLiberar.body().contains("Aguardando liberação"));

        HttpResponse<String> identificar = post("/identificar", "titulo=1001");
        assertTrue(identificar.body().contains("Ana Oliveira"));

        HttpResponse<String> liberar = post("/liberar", "titulo=1001");
        assertTrue(liberar.body().contains("Cabina liberada"));
        assertTrue(liberar.body().contains("Ana Oliveira"));
        assertEquals(13, contarBotoes(liberar.body()));

        HttpResponse<String> cabinaLiberada = get("/votar");
        assertTrue(cabinaLiberada.body().contains("PRESIDENTE"));
        assertTrue(cabinaLiberada.body().contains("Ana Oliveira"));
        assertTrue(cabinaLiberada.body().contains("action='/confirmar'"));
        assertTrue(cabinaLiberada.body().contains("data-poll='true'"));
        assertTrue(cabinaLiberada.body().contains("tabindex='-1'"));
        assertEquals(13, contarBotoes(cabinaLiberada.body()));

        HttpResponse<String> confirmarDuplicado = post("/confirmar", "titulo=1001&presidente=10&deputado1=101&deputado2=101");
        assertTrue(confirmarDuplicado.body().contains("Escolha dois candidatos distintos para Deputado federal."));

        HttpResponse<String> confirmar = post("/confirmar", "titulo=1001&presidente=10&deputado1=101&deputado2=999");
        assertEquals(303, confirmar.statusCode());
        assertEquals("/fim", confirmar.headers().firstValue("Location").orElse(""));

        HttpResponse<String> telaFim = get("/fim");
        assertEquals(200, telaFim.statusCode());
        assertTrue(telaFim.body().contains("<h1>FIM</h1>"));
        assertTrue(telaFim.body().contains("7000"));
        assertTrue(telaFim.body().contains("location.replace('/votar')"));
        assertFalse(telaFim.body().contains("Aguardando liberação"));
        assertEquals(13, contarBotoes(telaFim.body()));

        HttpResponse<String> urnaLivreNovamente = get("/votar");
        assertTrue(urnaLivreNovamente.body().contains("Aguardando liberação"));

        HttpResponse<String> abster = post("/abster", "titulo=1002");
        assertTrue(abster.body().contains("Abstenção registrada"));

        HttpResponse<String> encerrar = post("/encerrar", "senha=123456");
        assertTrue(encerrar.body().contains("ENCERRADA"));
        assertTrue(encerrar.body().contains("BOLETIM DE URNA"));
        assertTrue(encerrar.body().contains("100,00%"));
        assertFalse(encerrar.body().contains("50,00%"));
        assertTrue(encerrar.body().contains("Não eleito"));
        assertFalse(encerrar.body().contains("Derrotado"));
        assertEquals(13, contarBotoes(encerrar.body()));

        assertFalse(encerrar.body().contains("name='senha'"));
        assertTrue(encerrar.body().contains("CONFIRMA</strong> reinicia a urna"));

        HttpResponse<String> boletim = get("/resultado");
        assertEquals(200, boletim.statusCode());
        assertTrue(boletim.body().contains("BOLETIM DE URNA"));

        HttpResponse<String> health = get("/healthz");
        assertEquals(200, health.statusCode());
        assertEquals("OK", health.body());

        HttpResponse<String> getIdentificarSemParametro = get("/identificar");
        assertEquals(200, getIdentificarSemParametro.statusCode());

        HttpResponse<String> reiniciar = post("/reiniciar", "");
        assertEquals(200, reiniciar.statusCode());
        assertTrue(reiniciar.body().contains("FECHADA"));

        HttpResponse<String> status = get("/status-urna");
        assertEquals(200, status.statusCode());
        assertEquals("FECHADA|", status.body());

        HttpResponse<String> naoEncontrado = get("/rota-inexistente");
        assertEquals(404, naoEncontrado.statusCode());
    }

    private int contarBotoes(String html) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("<button", idx)) != -1) {
            count++;
            idx += 7;
        }
        return count;
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
