package br.ufmg.urna.ui;

import br.ufmg.urna.BoletimUrna;
import br.ufmg.urna.Candidato;
import br.ufmg.urna.Cargo;
import br.ufmg.urna.Eleitor;
import br.ufmg.urna.EstadoUrna;
import br.ufmg.urna.Escolha;
import br.ufmg.urna.RecursoInvalidoException;
import br.ufmg.urna.TipoVoto;
import br.ufmg.urna.negocio.ServicoUrna;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Interface web simples para operação local e demonstração em sala. */
public final class ServidorHttp {
    private final ServicoUrna urna;
    private HttpServer server;

    public ServidorHttp(ServicoUrna urna) { this.urna = urna; }

    public void iniciar(int porta) throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", porta), 0);
        server.createContext("/", this::atender);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Urna pronta em http://localhost:" + server.getAddress().getPort());
    }

    public int porta() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void parar() {
        if (server != null) server.stop(0);
    }

    private void atender(HttpExchange exchange) throws IOException {
        try {
            String caminho = exchange.getRequestURI().getPath();
            Map<String, String> form = exchange.getRequestMethod().equalsIgnoreCase("POST") ? lerFormulario(exchange) : Map.of();
            String pagina;
            int status = 200;
            switch (caminho) {
                case "/" -> pagina = inicio("");
                case "/abrir" -> pagina = executar(() -> { urna.abrir(form.get("senha")); return inicio("Votação aberta."); });
                case "/encerrar" -> pagina = executar(() -> { urna.encerrar(form.get("senha")); return inicio("Votação encerrada."); });
                case "/votar" -> pagina = urna.estado() == EstadoUrna.ABERTA ? identificacao("") : mensagem("A votação não está aberta.", false);
                case "/identificar" -> pagina = identificar(form.get("titulo"));
                case "/iniciar-voto" -> pagina = formularioVoto(form, "", true);
                case "/conferir" -> pagina = conferir(form, false);
                case "/editar" -> pagina = formularioVoto(form, "Revise os números antes de continuar.", false);
                case "/confirmar" -> pagina = conferir(form, true);
                case "/abster" -> pagina = executar(() -> { urna.registrarAbstencao(form.get("titulo")); return mensagem("Abstenção registrada.", true); });
                case "/resultado" -> pagina = resultado();
                default -> { pagina = mensagem("Página não encontrada.", false); status = 404; }
            }
            responder(exchange, status, pagina);
        } catch (Exception e) {
            responder(exchange, 500, mensagem("Não foi possível processar a solicitação. " + e.getMessage(), false));
        } finally {
            exchange.close();
        }
    }

    private String inicio(String aviso) {
        String estado = urna.estado().name();
        StringBuilder corpo = new StringBuilder();
        if (!aviso.isBlank()) corpo.append(alerta(aviso, true));
        corpo.append("<section class='card'><p class='eyebrow'>PAINEL DO MESÁRIO</p><h2>Estado da urna: ").append(esc(estado)).append("</h2>")
                .append("<p>Eleitores carregados: <strong>").append(urna.eleitoresAptos()).append("</strong></p>");
        if (urna.estado() == EstadoUrna.FECHADA) {
            corpo.append("<form method='post' action='/abrir'><label>Senha do mesário<input name='senha' type='password' required autocomplete='current-password'></label><button>Abrir votação</button></form>");
        } else if (urna.estado() == EstadoUrna.ABERTA) {
            corpo.append("<p><a class='button' href='/votar'>Abrir tela do eleitor</a></p>")
                    .append("<form method='post' action='/encerrar'><label>Senha do mesário<input name='senha' type='password' required autocomplete='current-password'></label><button class='danger'>Encerrar votação</button></form>");
        } else {
            corpo.append("<p>A votação foi encerrada. Os resultados ficam disponíveis abaixo.</p><a class='button' href='/resultado'>Ver boletim de urna</a>");
        }
        corpo.append("</section><p class='muted'>Aplicação acadêmica de demonstração; os dados ficam neste computador.</p>");
        return pagina("Painel da urna", corpo.toString(), false);
    }

    private String identificar(String titulo) {
        try {
            Eleitor eleitor = urna.identificar(titulo);
            return pagina("Confirme seus dados", "<section class='card'><p class='eyebrow'>IDENTIFICAÇÃO</p><h2>Confira seu nome</h2><p class='big'>" + esc(eleitor.nome()) + "</p>"
                    + "<p>Seus dados estão corretos? Ao continuar, você poderá registrar seu voto.</p>"
                    + formHidden("/iniciar-voto", "titulo", eleitor.titulo(), "Continuar para a votação")
                    + "<p><a class='muted' href='/votar'>Voltar e corrigir o título</a></p><hr><h3>Não deseja votar?</h3>"
                    + formHidden("/abster", "titulo", eleitor.titulo(), "Registrar abstenção") + "</section>", true);
        } catch (RuntimeException e) { return identificacao(e.getMessage()); }
    }

    private String identificacao(String erro) {
        String corpo = (erro == null || erro.isBlank() ? "" : alerta(erro, false))
                + "<section class='card'><p class='eyebrow'>IDENTIFICAÇÃO DO ELEITOR</p><h2>Digite o número do título</h2>"
                + "<form method='post' action='/identificar'><label>Número do título<input name='titulo' inputmode='numeric' autocomplete='off' required autofocus></label><button>Continuar</button></form></section>"
                + "<p><a href='/'>Voltar ao painel do mesário</a></p>";
        return pagina("Identificação", corpo, true);
    }

    private String formularioVoto(Map<String, String> dados, String aviso, boolean mostrarIdentidade) {
        String titulo = dados.getOrDefault("titulo", "");
        String nome = "";
        try { nome = urna.identificar(titulo).nome(); }
        catch (RuntimeException e) {
            // Após o voto ser confirmado o título já aparece como utilizado; o formulário só é mostrado antes da gravação.
            if (mostrarIdentidade) return mensagem(e.getMessage(), false);
        }
        StringBuilder corpo = new StringBuilder("<section class='card'><p class='eyebrow'>CÉDULA ELETRÔNICA</p><h2>Escolha para cada cargo</h2>");
        if (!nome.isBlank()) corpo.append("<p>Eleitor: <strong>").append(esc(nome)).append("</strong></p>");
        if (aviso != null && !aviso.isBlank()) corpo.append(alerta(aviso, false));
        corpo.append("<p>Informe o número do candidato. Digite <strong>BRANCO</strong> ou <strong>NULO</strong> para essas opções.</p>")
                .append("<form method='post' action='/conferir'>").append(hidden("titulo", titulo));
        campoEscolha(corpo, "presidente", "Presidente", dados.get("presidente"), urna.candidatos(Cargo.PRESIDENTE));
        campoEscolha(corpo, "deputado1", "Deputado federal — vaga 1", dados.get("deputado1"), urna.candidatos(Cargo.DEPUTADO_FEDERAL));
        campoEscolha(corpo, "deputado2", "Deputado federal — vaga 2", dados.get("deputado2"), urna.candidatos(Cargo.DEPUTADO_FEDERAL));
        corpo.append("<button>Conferir votos</button></form></section><p><a href='/'>Voltar ao painel</a></p>");
        return pagina("Votação", corpo.toString(), true);
    }

    private void campoEscolha(StringBuilder html, String campo, String descricao, String valor, List<Candidato> candidatos) {
        html.append("<label>").append(esc(descricao)).append("<input name='").append(campo).append("' value='").append(esc(valor == null ? "" : valor))
                .append("' autocomplete='off' required></label><div class='hint'>");
        for (Candidato c : candidatos) html.append("<span>").append(c.numero()).append(" — ").append(esc(c.nome())).append("</span>");
        html.append("<span>BRANCO</span><span>NULO</span></div>");
    }

    private String conferir(Map<String, String> form, boolean confirmar) {
        try {
            String titulo = form.getOrDefault("titulo", "");
            Escolha presidente = escolha(form.get("presidente"));
            Escolha deputado1 = escolha(form.get("deputado1"));
            Escolha deputado2 = escolha(form.get("deputado2"));
            if (confirmar) {
                urna.registrarVoto(titulo, presidente, deputado1, deputado2);
                return mensagem("Voto confirmado e registrado. Obrigado por participar.", true);
            }
            Eleitor eleitor = urna.identificar(titulo);
            validarParaExibicao(presidente, Cargo.PRESIDENTE);
            validarParaExibicao(deputado1, Cargo.DEPUTADO_FEDERAL);
            validarParaExibicao(deputado2, Cargo.DEPUTADO_FEDERAL);
            if (deputado1.tipo() == TipoVoto.NOMINAL && deputado2.tipo() == TipoVoto.NOMINAL
                    && java.util.Objects.equals(deputado1.numeroCandidato(), deputado2.numeroCandidato())) {
                throw new RecursoInvalidoException("Escolha dois candidatos distintos para Deputado federal.");
            }
            StringBuilder corpo = new StringBuilder("<section class='card'><p class='eyebrow'>REVISE SUA CÉDULA</p><h2>Confirme suas escolhas</h2><p>Eleitor: <strong>").append(esc(eleitor.nome())).append("</strong></p><dl>");
            linhaEscolha(corpo, "Presidente", presidente, Cargo.PRESIDENTE);
            linhaEscolha(corpo, "Deputado federal — vaga 1", deputado1, Cargo.DEPUTADO_FEDERAL);
            linhaEscolha(corpo, "Deputado federal — vaga 2", deputado2, Cargo.DEPUTADO_FEDERAL);
            corpo.append("</dl><p>Depois de confirmar, os votos não poderão ser alterados.</p>");
            corpo.append(formOcultaEscolhas("/confirmar", form, "Confirmar e registrar voto", true));
            corpo.append(formOcultaEscolhas("/editar", form, "Corrigir escolhas", false)).append("</section>");
            return pagina("Confirmação do voto", corpo.toString(), true);
        } catch (RuntimeException e) {
            return formularioVoto(form, e.getMessage(), true);
        }
    }

    private void validarParaExibicao(Escolha escolha, Cargo cargo) {
        if (escolha.tipo() == TipoVoto.NOMINAL && urna.candidatos(cargo).stream().noneMatch(c -> c.numero() == escolha.numeroCandidato()))
            throw new RecursoInvalidoException("Número inválido para " + cargo.descricao() + ". Corrija antes de confirmar.");
    }

    private void linhaEscolha(StringBuilder html, String rotulo, Escolha escolha, Cargo cargo) {
        String valor = switch (escolha.tipo()) {
            case BRANCO -> "Voto em branco";
            case NULO -> "Voto nulo";
            case NOMINAL -> urna.candidatos(cargo).stream().filter(c -> c.numero() == escolha.numeroCandidato()).map(Candidato::nome).findFirst()
                    .orElse("Candidato " + escolha.numeroCandidato())
                    + " (" + escolha.numeroCandidato() + ")";
        };
        html.append("<dt>").append(esc(rotulo)).append("</dt><dd>").append(esc(valor)).append("</dd>");
    }

    private String resultado() {
        if (urna.estado() != EstadoUrna.ENCERRADA) return mensagem("O boletim só pode ser emitido após o encerramento.", false);
        BoletimUrna b = urna.apurar();
        StringBuilder corpo = new StringBuilder("<section class='card'><p class='eyebrow'>BOLETIM DE URNA</p><h2>Resultado da eleição</h2>");
        corpo.append("<div class='stats'><div><strong>").append(b.comparecimentos()).append("</strong><span>comparecimentos</span></div><div><strong>").append(b.abstencoes()).append("</strong><span>abstenções</span></div><div><strong>").append(b.votosBrancos()).append("</strong><span>votos brancos</span></div><div><strong>").append(b.votosNulos()).append("</strong><span>votos nulos</span></div></div>")
                .append("<p>Votos válidos: <strong>").append(b.votosValidos()).append("</strong></p>");
        if (!b.houveVotosValidos()) corpo.append(alerta("Não houve votos válidos; não há candidatos eleitos.", false));
        for (Cargo cargo : Cargo.values()) {
            corpo.append("<h3>").append(esc(cargo.descricao())).append("</h3><table><thead><tr><th>Candidato</th><th>Votos</th><th>% dos válidos</th><th>Resultado</th></tr></thead><tbody>");
            urna.candidatos(cargo).stream().sorted(java.util.Comparator.comparingInt(Candidato::numero)).forEach(c -> {
                boolean eleito = b.eleitos().contains(c);
                corpo.append("<tr><td>").append(c.numero()).append(" — ").append(esc(c.nome())).append("</td><td>").append(b.votosPorCandidato().getOrDefault(c, 0))
                        .append("</td><td>").append(String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.2f%%", b.percentualPorCandidato().getOrDefault(c, 0.0)))
                        .append("</td><td>").append(eleito ? "Eleito" : "Não eleito").append("</td></tr>");
            });
            corpo.append("</tbody></table>");
        }
        corpo.append("<p class='muted'>Em caso de empate na última vaga, vence o menor número de candidato.</p></section><p><a href='/'>Voltar ao painel</a></p>");
        return pagina("Boletim de urna", corpo.toString(), false);
    }

    private Escolha escolha(String valor) {
        if (valor == null) throw new RecursoInvalidoException("Preencha as três escolhas.");
        String normalizado = valor.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizado.equals("BRANCO")) return Escolha.branco();
        if (normalizado.equals("NULO")) return Escolha.nulo();
        try { return Escolha.nominal(Integer.parseInt(normalizado)); }
        catch (NumberFormatException e) { throw new RecursoInvalidoException("Digite um número válido, BRANCO ou NULO."); }
    }

    private String formOcultaEscolhas(String action, Map<String, String> dados, String rotulo, boolean destaque) {
        return "<form class='inline' method='post' action='" + action + "'>" + hidden("titulo", dados.get("titulo"))
                + hidden("presidente", dados.get("presidente")) + hidden("deputado1", dados.get("deputado1")) + hidden("deputado2", dados.get("deputado2"))
                + "<button" + (destaque ? "" : " class='secondary'") + ">" + esc(rotulo) + "</button></form>";
    }

    private String formHidden(String action, String key, String value) { return formHidden(action, key, value, "Confirmar e continuar"); }
    private String formHidden(String action, String key, String value, String button) {
        return "<form method='post' action='" + action + "'>" + hidden(key, value) + "<button>" + esc(button) + "</button></form>";
    }

    private String hidden(String key, String value) { return "<input type='hidden' name='" + key + "' value='" + esc(value == null ? "" : value) + "'>"; }
    private String mensagem(String texto, boolean sucesso) { return pagina("Urna eletrônica", "<section class='card'>" + alerta(texto, sucesso) + "<p><a class='button' href='/'>Voltar ao painel</a></p></section>", false); }
    private String executar(Operacao operacao) { try { return operacao.executar(); } catch (RuntimeException e) { return inicio(e.getMessage()); } }

    private static Map<String, String> lerFormulario(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> campos = new HashMap<>();
        for (String parte : body.split("&")) {
            String[] kv = parte.split("=", 2);
            campos.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return campos;
    }

    private static String alerta(String texto, boolean sucesso) { return "<div class='alert " + (sucesso ? "success" : "error") + "'>" + esc(texto) + "</div>"; }
    private static String esc(String texto) { return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static void responder(HttpExchange exchange, int status, String pagina) throws IOException {
        byte[] bytes = pagina.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String pagina(String titulo, String conteudo, boolean telaEleitor) {
        return "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(titulo) + " — Urna</title><style>"
                + ":root{font-family:Arial,sans-serif;color:#15243a;background:#f3f6fa}body{margin:0}header{background:#10294a;color:white;padding:18px max(20px,calc((100% - 820px)/2))}header a{color:white;text-decoration:none;font-size:20px;font-weight:bold}main{max-width:820px;margin:32px auto;padding:0 18px}.card{background:white;border:1px solid #dce3ec;border-radius:12px;padding:26px;box-shadow:0 4px 14px #10294a0b}h2{margin-top:4px}.eyebrow{font-size:12px;font-weight:bold;letter-spacing:.12em;color:#58708c}.big{font-size:25px;font-weight:bold}label{display:block;font-weight:bold;margin:18px 0 8px}input{display:block;box-sizing:border-box;width:100%;padding:12px;margin-top:8px;border:1px solid #aebdce;border-radius:7px;font-size:17px}button,.button{display:inline-block;border:0;border-radius:7px;padding:12px 18px;margin:12px 8px 4px 0;background:#145bb8;color:white;text-decoration:none;font-weight:bold;font-size:15px;cursor:pointer}.danger{background:#a92d2d}.secondary{background:#e8eef5;color:#17304f}.alert{padding:12px 15px;border-radius:7px;margin:12px 0}.success{background:#e6f5ed;color:#155b37}.error{background:#fff1e6;color:#78421c}.muted,.hint{color:#66778c;font-size:14px}.hint{display:flex;flex-wrap:wrap;gap:7px;margin:8px 0 16px}.hint span{background:#edf2f7;padding:5px 8px;border-radius:5px}hr{border:0;border-top:1px solid #dce3ec;margin:24px 0}dl{display:grid;grid-template-columns:1fr 1fr;gap:12px}dt{font-weight:bold}dd{margin:0}.inline{display:inline}table{width:100%;border-collapse:collapse;margin:10px 0 25px}th,td{text-align:left;padding:10px 8px;border-bottom:1px solid #e1e7ee}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.stats div{background:#edf3f9;padding:14px;border-radius:8px}.stats strong,.stats span{display:block}.stats strong{font-size:24px}.stats span{font-size:13px;color:#63768c}@media(max-width:600px){main{margin:16px auto}.card{padding:18px}.stats{grid-template-columns:1fr 1fr}table{font-size:13px}th,td{padding:7px 4px}dl{grid-template-columns:1fr}}"
                + "</style></head><body><header><a href='/'>Urna Eletrônica</a></header><main>" + (telaEleitor ? "<p class='muted'>Área de votação</p>" : "") + conteudo + "</main></body></html>";
    }

    @FunctionalInterface private interface Operacao { String executar(); }
}
