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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/** Interface web responsiva para operação local ou em nuvem (Render) e demonstração acadêmica. */
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
                case "/healthz" -> { responderTexto(exchange, 200, "OK"); return; }
                case "/" -> pagina = inicio("", true);
                case "/abrir" -> pagina = executar(() -> { urna.abrir(form.get("senha")); return inicio("Votação aberta com sucesso.", true); });
                case "/encerrar" -> pagina = executar(() -> { urna.encerrar(form.get("senha")); return inicio("Votação encerrada. O Boletim de Urna já está disponível.", true); });
                case "/reiniciar" -> pagina = executar(() -> { urna.reiniciar(form.get("senha")); return inicio("Urna reiniciada (zerésima aplicada). Todos os votos anteriores foram apagados.", true); });
                case "/votar" -> pagina = urna.estado() == EstadoUrna.ABERTA ? identificacao("") : mensagem("A votação não está aberta.", false);
                case "/identificar" -> pagina = identificar(form.get("titulo"));
                case "/iniciar-voto" -> pagina = formularioVoto(form, "", true);
                case "/conferir" -> pagina = conferir(form, false);
                case "/editar" -> pagina = formularioVoto(form, "Revise os números antes de continuar.", false);
                case "/confirmar" -> pagina = conferir(form, true);
                case "/abster" -> pagina = executar(() -> { urna.registrarAbstencao(form.get("titulo")); return mensagem("Abstenção registrada com sucesso.", true); });
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

    private String inicio(String aviso, boolean sucessoAviso) {
        EstadoUrna estadoAtual = urna.estado();
        String estado = estadoAtual.name();
        int aptos = urna.eleitoresAptos();
        int comparecimentos = urna.comparecimentos();
        int pendentes = Math.max(0, aptos - comparecimentos);

        StringBuilder corpo = new StringBuilder();
        if (!aviso.isBlank()) corpo.append(alerta(aviso, sucessoAviso));

        corpo.append("<section class='card'>")
                .append("<div class='card-header'><div><p class='eyebrow'>PAINEL DO MESÁRIO (CREDENCIADO TSE)</p>")
                .append("<h2>Estado da urna: ").append(esc(estado)).append("</h2></div>")
                .append(badgeEstado(estadoAtual)).append("</div>")
                .append("<div class='stats stats-3'>")
                .append("<div><strong>").append(aptos).append("</strong><span>eleitores carregados</span></div>")
                .append("<div><strong>").append(comparecimentos).append("</strong><span>já participaram</span></div>")
                .append("<div><strong>").append(pendentes).append("</strong><span>aguardando voto</span></div>")
                .append("</div>");

        if (estadoAtual == EstadoUrna.FECHADA) {
            corpo.append("<div class='action-box'><h3>Iniciar Eleição</h3>")
                    .append("<p class='muted'>Informe a senha do mesário credenciado pelo TSE para abrir a seção eleitoral (demonstração: <code>123456</code>).</p>")
                    .append("<form method='post' action='/abrir'><label>Senha do mesário<input name='senha' type='password' placeholder='Digite a senha de abertura' required autocomplete='current-password'></label>")
                    .append("<button type='submit'>Abrir votação</button></form></div>");
        } else if (estadoAtual == EstadoUrna.ABERTA) {
            corpo.append("<div class='action-box highlight-box'><h3>Seção Eleitoral em Andamento</h3>")
                    .append("<p>A urna está pronta para receber os eleitores identificados pelo número do título.</p>")
                    .append("<p><a class='button btn-large' href='/votar'>Abrir terminal de votação do eleitor →</a></p></div>")
                    .append("<hr><div class='action-box'><h3>Encerrar Votação</h3>")
                    .append("<p class='muted'>Após todos votarem, insira a senha do mesário para encerrar e liberar o Boletim de Urna.</p>")
                    .append("<form method='post' action='/encerrar'><label>Senha do mesário<input name='senha' type='password' placeholder='Digite a senha de encerramento' required autocomplete='current-password'></label>")
                    .append("<button type='submit' class='danger'>Encerrar votação</button></form></div>");
        } else {
            corpo.append("<div class='action-box highlight-box'><h3>Votação Encerrada</h3>")
                    .append("<p>A votação foi concluída e os votos foram totalizados. Emita o Boletim de Urna abaixo.</p>")
                    .append("<p><a class='button btn-large' href='/resultado'>Ver Boletim de Urna (Apuração) →</a></p></div>");
        }

        if (estadoAtual != EstadoUrna.FECHADA) {
            corpo.append("<details class='reset-details'><summary>Opções avançadas de demonstração (Reiniciar / Zerar Urna)</summary>")
                    .append("<p class='muted'>Para iniciar uma nova simulação limpa do zero (zerésima), confirme a senha do mesário:</p>")
                    .append("<form method='post' action='/reiniciar'><label>Senha do mesário<input name='senha' type='password' placeholder='Senha para zerar a urna' required autocomplete='current-password'></label>")
                    .append("<button type='submit' class='secondary'>Reiniciar eleição (Zerar Urna)</button></form></details>");
        }
        corpo.append("</section>");

        corpo.append("<section class='card'><p class='eyebrow'>CADERNO DE VOTAÇÃO DA SEÇÃO</p>")
                .append("<h3>Eleitores Aptos Cadastrados</h3>")
                .append("<p class='muted'>O caderno registra apenas se o eleitor compareceu. Por garantia de sigilo (RNF01), nenhuma cédula é vinculada ao título.</p>")
                .append("<table><thead><tr><th>Título</th><th>Nome do Eleitor</th><th>Situação</th><th>Ação rápida</th></tr></thead><tbody>");
        for (Eleitor e : urna.eleitores()) {
            corpo.append("<tr><td><code>").append(esc(e.titulo())).append("</code></td><td>").append(esc(e.nome())).append("</td><td>")
                    .append(e.participou() ? "<span class='pill pill-done'>Já participou</span>" : "<span class='pill pill-wait'>Aguardando</span>")
                    .append("</td><td>");
            if (!e.participou() && estadoAtual == EstadoUrna.ABERTA) {
                corpo.append("<form class='inline' method='post' action='/identificar'>")
                        .append(hidden("titulo", e.titulo()))
                        .append("<button type='submit' class='btn-small'>Atender eleitor</button></form>");
            } else {
                corpo.append("<span class='muted'>—</span>");
            }
            corpo.append("</td></tr>");
        }
        corpo.append("</tbody></table></section>");
        return pagina("Painel da urna", corpo.toString(), false);
    }

    private String identificar(String titulo) {
        try {
            Eleitor eleitor = urna.identificar(titulo);
            return pagina("Confirme seus dados", "<section class='card'><p class='eyebrow'>ETAPA 1 DE 3 · IDENTIFICAÇÃO</p><h2>Confira seu nome</h2>"
                    + "<div class='voter-banner'><span class='muted'>Título nº " + esc(eleitor.titulo()) + "</span><p class='big'>" + esc(eleitor.nome()) + "</p></div>"
                    + "<p>Seus dados estão corretos? Ao confirmar, a cédula eletrônica será liberada para o seu voto.</p>"
                    + formHidden("/iniciar-voto", "titulo", eleitor.titulo(), "Confirmar dados e iniciar votação →")
                    + "<p><a class='muted' href='/votar'>← Voltar e digitar outro título</a></p><hr><h3>Optar pela abstenção</h3>"
                    + "<p class='muted'>Caso não deseje votar, registre sua abstenção (seu comparecimento será contabilizado sem registrar cédula de voto).</p>"
                    + formHiddenSecondary("/abster", "titulo", eleitor.titulo(), "Registrar abstenção") + "</section>", true);
        } catch (RuntimeException e) { return identificacao(e.getMessage()); }
    }

    private String identificacao(String erro) {
        StringBuilder opcoesRapidas = new StringBuilder();
        List<Eleitor> pendentes = urna.eleitores().stream().filter(e -> !e.participou()).toList();
        if (!pendentes.isEmpty()) {
            opcoesRapidas.append("<div class='hint'><span>Títulos disponíveis para teste:</span>");
            for (Eleitor e : pendentes) {
                opcoesRapidas.append("<button type='button' class='chip-btn' onclick=\"document.getElementById('campo-titulo').value='")
                        .append(esc(e.titulo())).append("'\">").append(esc(e.titulo())).append(" (").append(esc(e.nome())).append(")</button>");
            }
            opcoesRapidas.append("</div>");
        }
        String corpo = (erro == null || erro.isBlank() ? "" : alerta(erro, false))
                + "<section class='card'><p class='eyebrow'>ETAPA 1 DE 3 · IDENTIFICAÇÃO DO ELEITOR</p><h2>Digite o número do título</h2>"
                + "<form method='post' action='/identificar'><label>Número do título de eleitor"
                + "<input id='campo-titulo' name='titulo' inputmode='numeric' placeholder='Ex.: 10000001' autocomplete='off' required autofocus></label>"
                + opcoesRapidas
                + "<button type='submit'>Continuar</button></form></section>"
                + "<p><a href='/'>← Voltar ao painel do mesário</a></p>";
        return pagina("Identificação", corpo, true);
    }

    private String formularioVoto(Map<String, String> dados, String aviso, boolean mostrarIdentidade) {
        String titulo = dados.getOrDefault("titulo", "");
        String nome = "";
        try { nome = urna.identificar(titulo).nome(); }
        catch (RuntimeException e) {
            if (mostrarIdentidade) return mensagem(e.getMessage(), false);
        }
        StringBuilder corpo = new StringBuilder("<section class='card'><p class='eyebrow'>ETAPA 2 DE 3 · CÉDULA ELETRÔNICA</p><h2>Escolha para cada cargo</h2>");
        if (!nome.isBlank()) corpo.append("<p>Eleitor(a) confirmado(a): <strong>").append(esc(nome)).append("</strong></p>");
        if (aviso != null && !aviso.isBlank()) corpo.append(alerta(aviso, false));
        corpo.append("<p class='muted'>Digite o número do candidato, <strong>BRANCO</strong> ou <strong>NULO</strong> (ou clique diretamente em uma das opções abaixo de cada campo). Para Deputado Federal, escolha dois candidatos distintos.</p>")
                .append("<form method='post' action='/conferir'>").append(hidden("titulo", titulo));
        campoEscolha(corpo, "presidente", "1. Presidente da República (1 vaga)", dados.get("presidente"), urna.candidatos(Cargo.PRESIDENTE));
        campoEscolha(corpo, "deputado1", "2. Deputado Federal — 1ª escolha", dados.get("deputado1"), urna.candidatos(Cargo.DEPUTADO_FEDERAL));
        campoEscolha(corpo, "deputado2", "3. Deputado Federal — 2ª escolha (distinta da 1ª)", dados.get("deputado2"), urna.candidatos(Cargo.DEPUTADO_FEDERAL));
        corpo.append("<button type='submit' class='btn-large'>Conferir votos →</button></form></section><p><a href='/'>← Cancelar e voltar ao painel</a></p>");
        return pagina("Votação", corpo.toString(), true);
    }

    private void campoEscolha(StringBuilder html, String campo, String descricao, String valor, List<Candidato> candidatos) {
        String idCampo = "campo-" + campo;
        html.append("<div class='field-group'><label for='").append(idCampo).append("'>").append(esc(descricao))
                .append("<input id='").append(idCampo).append("' name='").append(campo).append("' value='").append(esc(valor == null ? "" : valor))
                .append("' placeholder='Digite o número, BRANCO ou NULO' autocomplete='off' required></label><div class='hint'>");
        for (Candidato c : candidatos) {
            html.append("<button type='button' class='chip-btn' onclick=\"document.getElementById('").append(idCampo).append("').value='").append(c.numero()).append("'\">")
                    .append(c.numero()).append(" — ").append(esc(c.nome())).append("</button>");
        }
        html.append("<button type='button' class='chip-btn chip-neutral' onclick=\"document.getElementById('").append(idCampo).append("').value='BRANCO'\">BRANCO</button>")
                .append("<button type='button' class='chip-btn chip-neutral' onclick=\"document.getElementById('").append(idCampo).append("').value='NULO'\">NULO</button></div></div>");
    }

    private String conferir(Map<String, String> form, boolean confirmar) {
        try {
            String titulo = form.getOrDefault("titulo", "");
            Escolha presidente = escolha(form.get("presidente"));
            Escolha deputado1 = escolha(form.get("deputado1"));
            Escolha deputado2 = escolha(form.get("deputado2"));
            if (confirmar) {
                urna.registrarVoto(titulo, presidente, deputado1, deputado2);
                return mensagem("FIM — Voto confirmado e registrado com sigilo absoluto. Obrigado por participar!", true);
            }
            Eleitor eleitor = urna.identificar(titulo);
            validarParaExibicao(presidente, Cargo.PRESIDENTE);
            validarParaExibicao(deputado1, Cargo.DEPUTADO_FEDERAL);
            validarParaExibicao(deputado2, Cargo.DEPUTADO_FEDERAL);
            if (deputado1.tipo() == TipoVoto.NOMINAL && deputado2.tipo() == TipoVoto.NOMINAL
                    && Objects.equals(deputado1.numeroCandidato(), deputado2.numeroCandidato())) {
                throw new RecursoInvalidoException("Escolha dois candidatos distintos para Deputado federal.");
            }
            StringBuilder corpo = new StringBuilder("<section class='card'><p class='eyebrow'>ETAPA 3 DE 3 · REVISE SUA CÉDULA</p><h2>Confirme suas escolhas</h2><p>Eleitor(a): <strong>").append(esc(eleitor.nome())).append("</strong></p><dl class='review-grid'>");
            linhaEscolha(corpo, "Presidente", presidente, Cargo.PRESIDENTE);
            linhaEscolha(corpo, "Deputado federal — vaga 1", deputado1, Cargo.DEPUTADO_FEDERAL);
            linhaEscolha(corpo, "Deputado federal — vaga 2", deputado2, Cargo.DEPUTADO_FEDERAL);
            corpo.append("</dl><p class='muted'>Depois de confirmar, o voto é gravado de forma anônima e não poderá ser alterado.</p><div class='btn-row'>");
            corpo.append(formOcultaEscolhas("/confirmar", form, "CONFIRMAR E REGISTRAR VOTO", true));
            corpo.append(formOcultaEscolhas("/editar", form, "CORRIGE (Voltar e editar)", false)).append("</div></section>");
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
        html.append("<dt>").append(esc(rotulo)).append("</dt><dd><strong>").append(esc(valor)).append("</strong></dd>");
    }

    private String resultado() {
        if (urna.estado() != EstadoUrna.ENCERRADA) return mensagem("O boletim só pode ser emitido após o encerramento.", false);
        BoletimUrna b = urna.apurar();
        StringBuilder corpo = new StringBuilder("<section class='card'><div class='card-header'><div><p class='eyebrow'>JUSTIÇA ELEITORAL ACADÊMICA · BOLETIM DE URNA</p><h2>Resultado Oficial da Apuração</h2></div>")
                .append(badgeEstado(EstadoUrna.ENCERRADA)).append("</div>");
        corpo.append("<div class='stats'>")
                .append("<div><strong>").append(b.eleitoresAptos()).append("</strong><span>eleitores aptos</span></div>")
                .append("<div><strong>").append(b.comparecimentos()).append("</strong><span>comparecimentos</span></div>")
                .append("<div><strong>").append(b.abstencoes()).append("</strong><span>abstenções</span></div>")
                .append("<div><strong>").append(b.votosValidos()).append("</strong><span>votos válidos</span></div>")
                .append("<div><strong>").append(b.votosBrancos()).append("</strong><span>votos brancos</span></div>")
                .append("<div><strong>").append(b.votosNulos()).append("</strong><span>votos nulos</span></div>")
                .append("</div>");
        if (!b.houveVotosValidos()) corpo.append(alerta("Não houve votos válidos; não há candidatos eleitos.", false));
        for (Cargo cargo : Cargo.values()) {
            int vagas = cargo == Cargo.PRESIDENTE ? 1 : 2;
            corpo.append("<h3>").append(esc(cargo.descricao())).append(" <span class='muted'>(").append(vagas).append(vagas == 1 ? " vaga" : " vagas").append(")</span></h3>")
                    .append("<table><thead><tr><th>Candidato</th><th>Votos</th><th>% dos válidos no cargo</th><th>Resultado</th></tr></thead><tbody>");
            urna.candidatos(cargo).stream().sorted(Comparator.comparingInt(Candidato::numero)).forEach(c -> {
                boolean eleito = b.eleitos().contains(c);
                int votos = b.votosPorCandidato().getOrDefault(c, 0);
                double pct = b.percentualPorCandidato().getOrDefault(c, 0.0);
                String pctFmt = String.format(Locale.forLanguageTag("pt-BR"), "%.2f%%", pct);
                corpo.append("<tr class='").append(eleito ? "row-elected" : "").append("'><td><strong>").append(c.numero()).append("</strong> — ").append(esc(c.nome())).append("</td><td>")
                        .append(votos).append("</td><td><div class='pct-cell'><span>").append(pctFmt).append("</span><div class='pct-bar'><div style='width:")
                        .append(String.format(Locale.US, "%.1f", Math.min(100.0, pct))).append("%'></div></div></div></td><td>")
                        .append(eleito ? "<span class='pill pill-elected'>Eleito</span>" : "<span class='pill pill-defeated'>Não eleito</span>")
                        .append("</td></tr>");
            });
            corpo.append("</tbody></table>");
        }
        corpo.append("<p class='muted'>Critério de desempate: em caso de empate na última vaga, vence o candidato com menor número de registro. Candidatos com zero votos não são eleitos.</p></section><p><a class='button' href='/'>← Voltar ao painel do mesário</a></p>");
        return pagina("Boletim de urna", corpo.toString(), false);
    }

    private Escolha escolha(String valor) {
        if (valor == null) throw new RecursoInvalidoException("Preencha as três escolhas.");
        String normalizado = valor.trim().toUpperCase(Locale.ROOT);
        if (normalizado.equals("BRANCO")) return Escolha.branco();
        if (normalizado.equals("NULO")) return Escolha.nulo();
        try { return Escolha.nominal(Integer.parseInt(normalizado)); }
        catch (NumberFormatException e) { throw new RecursoInvalidoException("Digite um número válido, BRANCO ou NULO."); }
    }

    private String badgeEstado(EstadoUrna estado) {
        String cls = switch (estado) {
            case FECHADA -> "badge-closed";
            case ABERTA -> "badge-open";
            case ENCERRADA -> "badge-finished";
        };
        return "<span class='status-badge " + cls + "'>" + esc(estado.name()) + "</span>";
    }

    private String formOcultaEscolhas(String action, Map<String, String> dados, String rotulo, boolean destaque) {
        return "<form class='inline' method='post' action='" + action + "'>" + hidden("titulo", dados.get("titulo"))
                + hidden("presidente", dados.get("presidente")) + hidden("deputado1", dados.get("deputado1")) + hidden("deputado2", dados.get("deputado2"))
                + "<button type='submit'" + (destaque ? " class='success-btn'" : " class='secondary'") + ">" + esc(rotulo) + "</button></form>";
    }

    private String formHidden(String action, String key, String value, String button) {
        return "<form method='post' action='" + action + "'>" + hidden(key, value) + "<button type='submit'>" + esc(button) + "</button></form>";
    }

    private String formHiddenSecondary(String action, String key, String value, String button) {
        return "<form method='post' action='" + action + "'>" + hidden(key, value) + "<button type='submit' class='secondary'>" + esc(button) + "</button></form>";
    }

    private String hidden(String key, String value) { return "<input type='hidden' name='" + key + "' value='" + esc(value == null ? "" : value) + "'>"; }
    private String mensagem(String texto, boolean sucesso) { return pagina("Urna eletrônica", "<section class='card'>" + alerta(texto, sucesso) + "<p><a class='button' href='/'>Voltar ao painel principal</a></p></section>", false); }
    private String executar(Operacao operacao) { try { return operacao.executar(); } catch (RuntimeException e) { return inicio(e.getMessage(), false); } }

    private static Map<String, String> lerFormulario(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> campos = new HashMap<>();
        for (String parte : body.split("&")) {
            if (parte.isEmpty()) continue;
            String[] kv = parte.split("=", 2);
            campos.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return campos;
    }

    private static String alerta(String texto, boolean sucesso) { return "<div class='alert " + (sucesso ? "success" : "error") + "'>" + esc(texto) + "</div>"; }
    private static String esc(String texto) { return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

    private static void responderTexto(HttpExchange exchange, int status, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

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
                + "<title>" + esc(titulo) + " — Urna Eletrônica DCC603</title><style>"
                + ":root{font-family:'Segoe UI',system-ui,-apple-system,Arial,sans-serif;color:#15243a;background:#f0f4f8}"
                + "body{margin:0;min-height:100vh;display:flex;flex-direction:column}"
                + "header{background:linear-gradient(135deg,#0c2340 0%,#143d6b 100%);color:white;padding:16px max(20px,calc((100% - 880px)/2));box-shadow:0 2px 10px rgba(0,0,0,.12);display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px}"
                + "header a{color:white;text-decoration:none;font-size:20px;font-weight:700;letter-spacing:-.01em}"
                + "header .sub{font-size:12px;color:#b8cbe0;display:block;font-weight:400;margin-top:2px}"
                + "header .nav-links a{font-size:13px;font-weight:600;background:rgba(255,255,255,.12);padding:7px 12px;border-radius:6px;margin-left:6px}"
                + "header .nav-links a:hover{background:rgba(255,255,255,.22)}"
                + "main{max-width:880px;width:100%;box-sizing:border-box;margin:28px auto;padding:0 18px;flex:1}"
                + "footer{text-align:center;padding:18px;color:#64748b;font-size:13px;border-top:1px solid #dde5ee;background:#fff}"
                + ".card{background:white;border:1px solid #d8e2ee;border-radius:14px;padding:26px;margin-bottom:20px;box-shadow:0 6px 20px rgba(16,41,74,.06)}"
                + ".card-header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:14px}"
                + "h2{margin:4px 0 8px;font-size:22px}h3{margin:16px 0 8px;font-size:17px}"
                + ".eyebrow{font-size:11px;font-weight:700;letter-spacing:.12em;color:#4f6885;margin:0}"
                + ".big{font-size:26px;font-weight:700;margin:6px 0 0;color:#0c2340}"
                + ".voter-banner{background:#f1f6fc;border-left:4px solid #145bb8;padding:14px 18px;border-radius:8px;margin:14px 0}"
                + ".status-badge{padding:6px 12px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:.06em}"
                + ".badge-closed{background:#fef3c7;color:#92400e}.badge-open{background:#d1fae5;color:#065f46}.badge-finished{background:#dbeafe;color:#1e40af}"
                + ".action-box{background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;padding:18px;margin-top:14px}"
                + ".highlight-box{background:#f0f7ff;border-color:#bfdbfe}"
                + ".field-group{background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;padding:14px 16px;margin:14px 0}"
                + "label{display:block;font-weight:600;margin:6px 0}"
                + "input{display:block;box-sizing:border-box;width:100%;padding:12px 14px;margin-top:8px;border:1px solid #b6c4d4;border-radius:8px;font-size:16px;background:#fff}"
                + "input:focus{outline:2px solid #145bb8;border-color:#145bb8}"
                + "button,.button{display:inline-block;border:0;border-radius:8px;padding:12px 20px;margin:10px 8px 4px 0;background:#145bb8;color:white;text-decoration:none;font-weight:600;font-size:15px;cursor:pointer;transition:filter .15s}"
                + "button:hover,.button:hover{filter:brightness(1.08)}"
                + ".btn-large{padding:14px 24px;font-size:16px}.btn-small{padding:6px 12px;font-size:13px;margin:0}"
                + ".success-btn{background:#15803d}.danger{background:#b91c1c}.secondary{background:#e2e8f0;color:#1e293b}"
                + ".alert{padding:13px 16px;border-radius:9px;margin:12px 0;font-weight:500}"
                + ".success{background:#dcfce7;color:#14532d;border:1px solid #86efac}.error{background:#fee2e2;color:#7f1d1d;border:1px solid #fca5a5}"
                + ".muted,.hint{color:#5b6e84;font-size:14px}.hint{display:flex;flex-wrap:wrap;gap:7px;margin:10px 0 4px;align-items:center}"
                + ".chip-btn{background:#e8f0fe;color:#174ea6;border:1px solid #c6dafc;padding:6px 11px;border-radius:6px;font-size:13px;font-weight:600;margin:0;cursor:pointer}"
                + ".chip-btn:hover{background:#d2e3fc}.chip-neutral{background:#f1f5f9;color:#334155;border-color:#cbd5e1}"
                + "hr{border:0;border-top:1px solid #dce3ec;margin:22px 0}"
                + ".review-grid{display:grid;grid-template-columns:1fr 1.3fr;gap:12px;background:#f8fafc;padding:18px;border-radius:10px;border:1px solid #e2e8f0}"
                + "dt{font-weight:600;color:#475569}dd{margin:0;color:#0f172a}"
                + ".inline{display:inline}.btn-row{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}"
                + "table{width:100%;border-collapse:collapse;margin:10px 0 22px}th,td{text-align:left;padding:11px 10px;border-bottom:1px solid #e2e8f0;vertical-align:middle}"
                + "th{font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:#475569;background:#f8fafc}"
                + ".row-elected{background:#f0fdf4}"
                + ".pill{display:inline-block;padding:4px 10px;border-radius:999px;font-size:12px;font-weight:700}"
                + ".pill-elected{background:#16a34a;color:#fff}.pill-defeated{background:#e2e8f0;color:#475569}"
                + ".pill-done{background:#dbeafe;color:#1e40af}.pill-wait{background:#f1f5f9;color:#475569}"
                + ".pct-cell{display:flex;align-items:center;gap:10px}.pct-bar{flex:1;max-width:110px;height:8px;background:#e2e8f0;border-radius:99px;overflow:hidden}"
                + ".pct-bar div{height:100%;background:#145bb8;border-radius:99px}"
                + ".stats{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:14px 0}"
                + ".stats div{background:#f1f6fc;border:1px solid #dbe7f5;padding:14px;border-radius:10px}"
                + ".stats strong,.stats span{display:block}.stats strong{font-size:24px;color:#0c2340}.stats span{font-size:13px;color:#52657a}"
                + ".reset-details{margin-top:18px;padding-top:12px;border-top:1px dashed #cbd5e1}.reset-details summary{cursor:pointer;color:#475569;font-weight:600;font-size:14px}"
                + "code{background:#e2e8f0;padding:2px 6px;border-radius:4px;font-size:13px}"
                + "@media(max-width:640px){main{margin:16px auto}.card{padding:18px}.stats{grid-template-columns:1fr 1fr}table{font-size:13px}th,td{padding:8px 5px}.review-grid{grid-template-columns:1fr}}"
                + "</style></head><body><header><div><a href='/'>Urna Eletrônica · TSE Acadêmico</a><span class='sub'>DCC603 Engenharia de Software I · UFMG · Grupo 2</span></div>"
                + "<nav class='nav-links'><a href='/'>Painel do Mesário</a><a href='/votar'>Terminal do Eleitor</a><a href='/resultado'>Boletim de Urna</a></nav></header><main>"
                + (telaEleitor ? "<p class='muted'>Terminal de Votação do Eleitor · Eleição de Turno Único</p>" : "")
                + conteudo
                + "</main><footer>Sistema de Urna Eletrônica — Grupo 2 (Davi Balsamão, Ítalo Leal, Thiago Amorim e Vitor Hugo) · DCC603/UFMG</footer></body></html>";
    }

    @FunctionalInterface private interface Operacao { String executar(); }
}
