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

/** Interface web com dois terminais: Mesário (/) e Cabina de Votação (/votar). */
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
            boolean isPost = exchange.getRequestMethod().equalsIgnoreCase("POST");
            Map<String, String> form = lerParametros(exchange, isPost);
            String pagina;
            int status = 200;
            switch (caminho) {
                case "/healthz" -> { responderTexto(exchange, 200, "OK"); return; }
                case "/status-urna" -> { responderTexto(exchange, 200, statusCabina()); return; }
                case "/favicon.ico" -> { exchange.sendResponseHeaders(204, -1); return; }
                case "/" -> pagina = mesarioTela(form.get("tela"));
                case "/abrir" -> pagina = (!isPost && !form.containsKey("senha"))
                        ? mesario("", "")
                        : executarMesario(() -> { urna.abrir(form.get("senha")); return mesario("", ""); });
                case "/encerrar" -> pagina = (!isPost && !form.containsKey("senha"))
                        ? mesario("", "")
                        : executarAcaoSenha("Encerrar votação", "/encerrar", () -> { urna.encerrar(form.get("senha")); return mesario("", ""); });
                case "/reiniciar" -> {
                    if (isPost) urna.reiniciar();
                    pagina = mesario("", "");
                }
                case "/identificar" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? mesario("", "")
                        : identificar(form.get("titulo"));
                case "/liberar" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? mesario("", "")
                        : executarMesario(() -> { urna.liberarEleitor(form.get("titulo")); return mesario("", ""); });
                case "/cancelar-liberacao" -> {
                    if (isPost) urna.cancelarLiberacao();
                    pagina = mesario("", "");
                }
                case "/abster" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? mesario("", "")
                        : executarMesario(() -> {
                            urna.registrarAbstencao(form.get("titulo"));
                            return mesario("", "Abstenção registrada.");
                        });
                case "/votar" -> pagina = cabinaVotacao();
                case "/iniciar-voto" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? cabinaVotacao()
                        : formularioVoto(form, "");
                case "/conferir" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? cabinaVotacao()
                        : conferir(form, false);
                case "/editar" -> pagina = (!isPost && !form.containsKey("titulo"))
                        ? cabinaVotacao()
                        : formularioVoto(form, "Corrija os números antes de confirmar.");
                case "/confirmar" -> {
                    if (!isPost && !form.containsKey("titulo")) {
                        pagina = cabinaVotacao();
                    } else {
                        String erroOuHtml = confirmarVoto(form);
                        if (erroOuHtml == null) {
                            redirecionar(exchange, "/fim");
                            return;
                        }
                        pagina = erroOuHtml;
                    }
                }
                case "/fim" -> pagina = mensagem("", true, true, "/votar");
                case "/resultado" -> pagina = resultado();
                default -> { pagina = mensagem("Página não encontrada.", false, false, "/"); status = 404; }
            }
            responder(exchange, status, pagina);
        } catch (Exception e) {
            responder(exchange, 500, mensagem("Não foi possível processar a solicitação. " + e.getMessage(), false, false, "/"));
        } finally {
            exchange.close();
        }
    }

    private String statusCabina() {
        Eleitor lib = urna.eleitorLiberado();
        return urna.estado().name() + "|" + (lib != null ? lib.titulo() : "");
    }

    private String mesarioTela(String tela) {
        if (urna.estado() == EstadoUrna.ABERTA && urna.eleitorLiberado() == null && tela != null) {
            if ("menu".equals(tela)) return menuMesario();
            if ("encerrar".equals(tela)) return telaSenhaMesario("Encerrar votação", "/encerrar", "");
        }
        return mesario("", "");
    }

    private String menuMesario() {
        String lcd = "<div class='lcd-top'><span>TERMINAL DO MESÁRIO</span><span>OPÇÕES</span></div>"
                + "<div class='lcd-standby'>"
                + "<h2>Opções do mesário</h2>"
                + "<div class='lcd-menu-list'>"
                + "<div class='lcd-menu-opt'><kbd>1</kbd><span>Encerrar votação</span></div>"
                + "<div class='lcd-menu-opt'><kbd>2</kbd><span>Reiniciar votação</span></div>"
                + "</div>"
                + "<form id='form-reiniciar' method='post' action='/reiniciar'></form></div>"
                + "<div class='lcd-footer'><span><strong>1</strong> encerrar · <strong>2</strong> reiniciar · <strong>CORRIGE</strong> volta</span></div>";
        return paginaUrna("Opções do mesário", lcd, painelEleitoresSomenteLeitura("ABERTA"), "mesario-menu", "mesario", false);
    }

    private String telaSenhaMesario(String tituloAcao, String action, String erroSenha) {
        boolean temErroSenha = erroSenha != null && !erroSenha.isBlank();
        String lcd = "<div class='lcd-top'><span>TERMINAL DO MESÁRIO</span><span>" + esc(tituloAcao) + "</span></div>"
                + "<div class='lcd-standby'>"
                + "<div class='lcd-emblem'>" + brasaoSvg(40) + "</div>"
                + "<h2>" + esc(tituloAcao) + "</h2>"
                + "<form id='form-urna' method='post' action='" + esc(action) + "' class='lcd-inline-box'>"
                + "<label class='lcd-field-label' for='campo-ativo'>Senha do mesário</label>"
                + "<input id='campo-ativo' class='lcd-input-main" + (temErroSenha ? " input-erro" : "") + "' name='senha' type='"
                + (temErroSenha ? "text' value='" + esc(erroSenha) + "' data-erro='" + esc(erroSenha) + "'" : "password' value=''")
                + " data-tipo='password' maxlength='12' placeholder='123456' readonly tabindex='-1' autocomplete='off'>"
                + "</form></div>"
                + "<div class='lcd-footer'><span><strong>CONFIRMA</strong> encerra · <strong>CORRIGE</strong> limpa / volta</span></div>";
        return paginaUrna(tituloAcao, lcd, painelEleitoresSomenteLeitura("ABERTA"), "mesario-senha", "mesario", false);
    }

    private String painelEleitoresSomenteLeitura(String badgeTexto) {
        return "<section class='mesario-panel'>"
                + "<div class='mesario-header'><span>Referência da seção</span>"
                + "<span class='status-badge badge-open'>" + esc(badgeTexto) + "</span></div>"
                + listaEleitoresMesario()
                + "</section>";
    }

    private String mesario(String erroSenha, String avisoMesario) {
        EstadoUrna estadoAtual = urna.estado();
        String estado = estadoAtual.name();
        Eleitor liberado = urna.eleitorLiberado();
        boolean temErroSenha = erroSenha != null && !erroSenha.isBlank();

        StringBuilder lcd = new StringBuilder();
        lcd.append("<div class='lcd-top'><span>TERMINAL DO MESÁRIO</span><span>").append(esc(estado)).append("</span></div>");

        if (estadoAtual == EstadoUrna.FECHADA) {
            lcd.append("<div class='lcd-standby'>")
                    .append("<div class='lcd-emblem'>").append(brasaoSvg(42)).append("</div>")
                    .append("<h2>Estado da urna:<br><span class='estado-valor'>FECHADA</span></h2>")
                    .append("<p class='lcd-inst'>Eleitores: <strong>").append(urna.eleitoresAptos()).append("</strong></p>")
                    .append("<form id='form-urna' method='post' action='/abrir' class='lcd-inline-box'>")
                    .append("<label class='lcd-field-label' for='campo-ativo'>Senha do mesário</label>")
                    .append("<input id='campo-ativo' class='lcd-input-main").append(temErroSenha ? " input-erro" : "").append("' name='senha' type='")
                    .append(temErroSenha ? "text' value='" + esc(erroSenha) + "' data-erro='" + esc(erroSenha) + "'" : "password' value=''")
                    .append(" data-tipo='password' placeholder='123456' readonly tabindex='-1' autocomplete='off'>")
                    .append("</form></div>")
                    .append("<div class='lcd-footer'><span><strong>CONFIRMA</strong> abre · <strong>CORRIGE</strong> limpa</span></div>");
            return paginaUrna("Mesário", lcd.toString(), "", "standby", "mesario", false);
        }

        if (estadoAtual == EstadoUrna.ABERTA) {
            if (liberado != null) {
                lcd.append("<div class='lcd-standby'>")
                        .append("<div class='lcd-emblem'>").append(brasaoSvg(40)).append("</div>")
                        .append("<h2>Cabina liberada</h2>")
                        .append("<p class='lcd-VoterName' style='margin:6px 0'>").append(esc(liberado.nome())).append("</p>")
                        .append("<p class='lcd-inst'>Título: <code>").append(esc(liberado.titulo())).append("</code></p>")
                        .append("<p class='lcd-inst'>Aguardando voto na aba <strong>Votação</strong></p>")
                        .append("<form id='form-cancelar' method='post' action='/cancelar-liberacao'></form>")
                        .append("<form id='form-abster' method='post' action='/abster'>").append(hidden("titulo", liberado.titulo())).append("</form>")
                        .append("</div>")
                        .append("<div class='lcd-footer'><span><strong>CORRIGE</strong> cancela · <strong>BRANCO</strong> abstém</span></div>");

                return paginaUrna("Mesário", lcd.toString(), painelEleitoresSomenteLeitura("CABINA OCUPADA"), "aguardando-cabina", "mesario", true);
            }

            String erroTitulo = (avisoMesario != null && avisoMesario.startsWith("ERRO:")) ? avisoMesario.substring(5) : "";
            String avisoOk = (avisoMesario != null && !avisoMesario.startsWith("ERRO:")) ? avisoMesario : "";
            boolean temErroTitulo = !erroTitulo.isBlank();

            lcd.append("<div class='lcd-body-single lcd-centered'>")
                    .append("<h2>Liberar eleitor</h2>")
                    .append("<p class='lcd-inst' style='min-height:20px;color:#1F8A4C;font-weight:600'>")
                    .append(!avisoOk.isBlank() ? esc(avisoOk) : "")
                    .append("</p>")
                    .append("<form id='form-urna' method='post' action='/identificar' class='lcd-inline-box'>")
                    .append("<label class='lcd-field-label' for='campo-ativo'>Título (4 dígitos)</label>")
                    .append("<input id='campo-ativo' class='lcd-input-main").append(temErroTitulo ? " input-erro" : "").append("' name='titulo' type='text' value='")
                    .append(temErroTitulo ? esc(erroTitulo) + "' data-erro='" + esc(erroTitulo) : "")
                    .append("' data-tipo='text' maxlength='4' placeholder='1001' readonly tabindex='-1' autocomplete='off'>")
                    .append("</form></div>")
                    .append("<div class='lcd-footer'><span><strong>CONFIRMA</strong> busca · <strong>CORRIGE</strong> limpa · <strong>BRANCO</strong> opções</span></div>");

            return paginaUrna("Mesário", lcd.toString(), painelEleitoresSomenteLeitura("ABERTA"), "identificacao", "mesario", false);
        }

        // ENCERRADA
        BoletimUrna b = urna.apurar();
        lcd.append("<div class='lcd-standby'>")
                .append("<div class='lcd-emblem'>").append(brasaoSvg(40)).append("</div>")
                .append("<h2>Estado da urna:<br><span class='estado-valor'>ENCERRADA</span></h2>")
                .append("<div class='lcd-kpis'>")
                .append("<div><strong>").append(b.comparecimentos()).append("</strong><span>Comparecimentos</span></div>")
                .append("<div><strong>").append(b.abstencoes()).append("</strong><span>Abstenções</span></div>")
                .append("<div><strong>").append(b.votosValidos()).append("</strong><span>Votos válidos</span></div>")
                .append("</div>")
                .append("<p class='lcd-inst'>Boletim de Urna emitido abaixo.</p>")
                .append("<form id='form-urna' method='post' action='/reiniciar'></form>")
                .append("</div>")
                .append("<div class='lcd-footer'><span><strong>CONFIRMA</strong> reinicia a urna</span></div>");

        return paginaUrna("Mesário - Boletim de Urna", lcd.toString(), boletimHtml(b), "encerrada", "mesario", false);
    }

    private String listaEleitoresMesario() {
        StringBuilder sb = new StringBuilder("<div style='margin-top:14px'><p class='eyebrow'>ELEITORES CADASTRADOS (")
                .append(urna.eleitoresAptos()).append(")</p><div class='ref-grid' style='margin-top:8px'>");
        for (Eleitor e : urna.eleitores()) {
            sb.append("<div class='ref-item").append(e.participou() ? " ref-done" : "").append("'>")
                    .append("<code>").append(esc(e.titulo())).append("</code> ")
                    .append("<span>").append(esc(e.nome())).append("</span>")
                    .append(e.participou() ? "<em class='ref-tag'>Votou</em>" : "")
                    .append("</div>");
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    private String identificar(String titulo) {
        try {
            Eleitor eleitor = urna.identificar(titulo);
            StringBuilder lcd = new StringBuilder();
            lcd.append("<div class='lcd-top'><span>TERMINAL DO MESÁRIO</span><span>CONFIRMAR</span></div>")
                    .append("<div class='lcd-body-split'>")
                    .append("<div class='lcd-info'>")
                    .append("<h2>Confirmar eleitor</h2>")
                    .append("<p class='lcd-row'><span>Título:</span> <strong>").append(esc(eleitor.titulo())).append("</strong></p>")
                    .append("<p class='lcd-row'><span>Nome:</span> <strong class='lcd-VoterName'>").append(esc(eleitor.nome())).append("</strong></p>")
                    .append("<form id='form-urna' method='post' action='/liberar'>").append(hidden("titulo", eleitor.titulo())).append("</form>")
                    .append("<form id='form-branco' method='post' action='/abster'>").append(hidden("titulo", eleitor.titulo())).append("</form>")
                    .append("</div>")
                    .append("<div class='lcd-photo-box'><div class='lcd-avatar'>").append(avatarSvg()).append("</div><span>ELEITOR</span></div>")
                    .append("</div>")
                    .append("<div class='lcd-footer'>")
                    .append("<span><strong>CONFIRMA</strong> libera · <strong>CORRIGE</strong> volta · <strong>BRANCO</strong> abstém</span></div>");

            return paginaUrna("Confirmar eleitor", lcd.toString(), "", "confirmar-eleitor", "mesario", false);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg.startsWith("Título não encontrado")) msg = "Título não encontrado.";
            else if (msg.startsWith("Este título já participou")) msg = "Título já participou.";
            return mesario("", "ERRO:" + msg);
        }
    }

    private String cabinaVotacao() {
        EstadoUrna est = urna.estado();
        if (est == EstadoUrna.FECHADA) {
            StringBuilder lcd = new StringBuilder("<div class='lcd-top'><span>URNA DE VOTAÇÃO</span><span>FECHADA</span></div>")
                    .append("<div class='lcd-standby'>")
                    .append("<div class='lcd-emblem'>").append(brasaoSvg(44)).append("</div>")
                    .append("<h2>Estado da urna:<br><span class='estado-valor'>FECHADA</span></h2>")
                    .append("<p class='lcd-inst'>Aguarde o mesário abrir na aba <strong>Mesário</strong>.</p>")
                    .append("</div>")
                    .append("<div class='lcd-footer'><span>Cabina bloqueada</span></div>");
            return paginaUrna("Votação", lcd.toString(), "", "aguardando", "votacao", true);
        }

        if (est == EstadoUrna.ENCERRADA) {
            StringBuilder lcd = new StringBuilder("<div class='lcd-top'><span>URNA DE VOTAÇÃO</span><span>ENCERRADA</span></div>")
                    .append("<div class='lcd-standby'>")
                    .append("<div class='lcd-emblem'>").append(brasaoSvg(44)).append("</div>")
                    .append("<h2>Estado da urna:<br><span class='estado-valor'>ENCERRADA</span></h2>")
                    .append("<p class='lcd-inst'>Boletim disponível na aba <strong>Mesário</strong>.</p>")
                    .append("</div>")
                    .append("<div class='lcd-footer'><span>Seção encerrada</span></div>");
            return paginaUrna("Votação", lcd.toString(), "", "aguardando", "votacao", true);
        }

        Eleitor liberado = urna.eleitorLiberado();
        if (liberado == null) {
            StringBuilder lcd = new StringBuilder("<div class='lcd-top'><span>URNA DE VOTAÇÃO</span><span>AGUARDANDO</span></div>")
                    .append("<div class='lcd-standby'>")
                    .append("<div class='lcd-emblem'>").append(brasaoSvg(44)).append("</div>")
                    .append("<h2>Aguardando liberação<br>do mesário</h2>")
                    .append("<p class='lcd-inst'>Informe seu título na aba <strong>Mesário</strong>.</p>")
                    .append("</div>")
                    .append("<div class='lcd-footer'><span>Cabina pronta</span></div>");
            return paginaUrna("Votação", lcd.toString(), "", "aguardando", "votacao", true);
        }

        return formularioVoto(Map.of("titulo", liberado.titulo()), "");
    }

    private String formularioVoto(Map<String, String> dados, String aviso) {
        String titulo = dados.getOrDefault("titulo", "");
        Eleitor liberado = urna.eleitorLiberado();
        if (liberado == null || !liberado.titulo().equals(titulo.trim())) {
            return cabinaVotacao();
        }
        String nome;
        try { nome = urna.identificar(titulo).nome(); }
        catch (RuntimeException e) { return cabinaVotacao(); }

        List<Candidato> presList = urna.candidatos(Cargo.PRESIDENTE);
        List<Candidato> depList = urna.candidatos(Cargo.DEPUTADO_FEDERAL);

        StringBuilder jsonCand = new StringBuilder("{");
        jsonCand.append("\"PRESIDENTE\":{");
        for (int i = 0; i < presList.size(); i++) {
            Candidato c = presList.get(i);
            if (i > 0) jsonCand.append(",");
            jsonCand.append("\"").append(c.numero()).append("\":\"").append(escJs(c.nome())).append("\"");
        }
        jsonCand.append("},\"DEPUTADO_FEDERAL\":{");
        for (int i = 0; i < depList.size(); i++) {
            Candidato c = depList.get(i);
            if (i > 0) jsonCand.append(",");
            jsonCand.append("\"").append(c.numero()).append("\":\"").append(escJs(c.nome())).append("\"");
        }
        jsonCand.append("}}");

        StringBuilder lcd = new StringBuilder();
        lcd.append("<div class='lcd-top'><span>SEU VOTO PARA</span><span id='etapa-indicador'>CARGO 1 DE 3</span></div>");

        lcd.append("<div class='lcd-body-split'>")
                .append("<div class='lcd-info'>")
                .append("<h2 id='lcd-cargo-titulo' class='lcd-cargo'>PRESIDENTE</h2>")
                .append("<div id='lcd-numero-wrap' class='lcd-numero-row'><span>Número:</span><div id='lcd-caixas' class='lcd-boxes'></div></div>")
                .append("<div class='lcd-cand-details'>")
                .append("<p id='lcd-nome-row' class='lcd-row'><span>Nome:</span> <strong id='lcd-cand-nome'></strong></p>")
                .append("<div id='lcd-voto-banner' class='lcd-voto-banner' style='display:none'></div>")
                .append("<p class='lcd-status-line' id='lcd-cand-status'>")
                .append(aviso != null && !aviso.isBlank() ? esc(aviso) : "Digite o número do candidato ou aperte BRANCO")
                .append("</p>")
                .append("</div>")
                .append("</div>")
                .append("<div id='lcd-foto-wrap' class='lcd-photo-box' style='visibility:hidden'>")
                .append("<div class='lcd-avatar'>").append(avatarSvg()).append("</div>")
                .append("<span id='lcd-foto-legenda'>CANDIDATO</span></div>")
                .append("</div>");

        lcd.append("<form id='form-urna' method='post' action='/confirmar'>")
                .append(hidden("titulo", titulo))
                .append("<input type='hidden' id='input-presidente' name='presidente' value='").append(esc(dados.getOrDefault("presidente", ""))).append("'>")
                .append("<input type='hidden' id='input-deputado1' name='deputado1' value='").append(esc(dados.getOrDefault("deputado1", ""))).append("'>")
                .append("<input type='hidden' id='input-deputado2' name='deputado2' value='").append(esc(dados.getOrDefault("deputado2", ""))).append("'>")
                .append("</form>");

        lcd.append("<div class='lcd-footer lcd-tse-footer'><span><strong>CONFIRMA</strong> avança · <strong>CORRIGE</strong> limpa · <strong>BRANCO</strong> branco</span></div>")
                .append("<script>window.CANDIDATOS_URNA = ").append(jsonCand).append(";</script>");

        StringBuilder cola = new StringBuilder("<section class='mesario-panel compact-panel'>")
                .append("<div class='mesario-header'><span>Candidatos disponíveis (Eleitor: ").append(esc(nome)).append(")</span></div>")
                .append("<div class='colinha-columns'><div><h4>Presidente (2 dígitos)</h4><div class='ref-grid'>");
        for (Candidato c : presList) {
            cola.append("<div class='ref-item'><code>").append(c.numero()).append("</code> <span>").append(esc(c.nome())).append("</span></div>");
        }
        cola.append("<div class='ref-item'><code>Outro nº</code> <span>Anula o voto</span></div>")
                .append("</div></div><div><h4>Deputado Federal (3 dígitos, 2 distintos)</h4><div class='ref-grid'>");
        for (Candidato c : depList) {
            cola.append("<div class='ref-item'><code>").append(c.numero()).append("</code> <span>").append(esc(c.nome())).append("</span></div>");
        }
        cola.append("<div class='ref-item'><code>Outro nº</code> <span>Anula o voto</span></div>")
                .append("</div></div></div></section>");

        return paginaUrna("Votação", lcd.toString(), cola.toString(), "cedula", "votacao", true);
    }

    private String confirmarVoto(Map<String, String> form) {
        try {
            String titulo = form.getOrDefault("titulo", "");
            Eleitor liberado = urna.eleitorLiberado();
            if (liberado == null || !liberado.titulo().equals(titulo.trim())) {
                return cabinaVotacao();
            }
            Escolha presidente = escolha(form.get("presidente"));
            Escolha deputado1 = escolha(form.get("deputado1"));
            Escolha deputado2 = escolha(form.get("deputado2"));
            urna.registrarVoto(titulo, presidente, deputado1, deputado2);
            return null;
        } catch (RuntimeException e) {
            return formularioVoto(form, e.getMessage());
        }
    }

    private String conferir(Map<String, String> form, boolean confirmar) {
        try {
            String titulo = form.getOrDefault("titulo", "");
            Eleitor liberado = urna.eleitorLiberado();
            if (liberado == null || !liberado.titulo().equals(titulo.trim())) {
                return cabinaVotacao();
            }
            Escolha presidente = escolha(form.get("presidente"));
            Escolha deputado1 = escolha(form.get("deputado1"));
            Escolha deputado2 = escolha(form.get("deputado2"));
            if (confirmar) {
                urna.registrarVoto(titulo, presidente, deputado1, deputado2);
                return mensagem("Voto confirmado e registrado. Obrigado por participar.", true, true, "/votar");
            }
            Eleitor eleitor = urna.identificar(titulo);
            presidente = normalizarParaExibicao(presidente, Cargo.PRESIDENTE);
            deputado1 = normalizarParaExibicao(deputado1, Cargo.DEPUTADO_FEDERAL);
            deputado2 = normalizarParaExibicao(deputado2, Cargo.DEPUTADO_FEDERAL);
            if (deputado1.tipo() == TipoVoto.NOMINAL && deputado2.tipo() == TipoVoto.NOMINAL
                    && Objects.equals(deputado1.numeroCandidato(), deputado2.numeroCandidato())) {
                throw new RecursoInvalidoException("Escolha dois candidatos distintos para Deputado federal.");
            }
            StringBuilder lcd = new StringBuilder("<div class='lcd-top'><span>REVISE SUA CÉDULA</span><span>").append(esc(eleitor.nome())).append("</span></div>")
                    .append("<div class='lcd-body-single'><h2>Confirme suas escolhas</h2>")
                    .append("<dl class='lcd-review-dl'>");
            linhaEscolha(lcd, "Presidente", presidente, Cargo.PRESIDENTE);
            linhaEscolha(lcd, "Deputado federal 1", deputado1, Cargo.DEPUTADO_FEDERAL);
            linhaEscolha(lcd, "Deputado federal 2", deputado2, Cargo.DEPUTADO_FEDERAL);
            lcd.append("</dl>")
                    .append("<p class='muted'>Depois de confirmar, os votos não poderão ser alterados.</p>")
                    .append(formOcultaEscolhas("form-urna", "/confirmar", form))
                    .append(formOcultaEscolhas("form-corrige", "/editar", form))
                    .append("</div>")
                    .append("<div class='lcd-footer'><span><strong>CONFIRMA</strong> grava o voto · <strong>CORRIGE</strong> volta para editar</span></div>");

            return paginaUrna("Confirmação do voto", lcd.toString(), "", "revisao", "votacao", false);
        } catch (RuntimeException e) {
            return formularioVoto(form, e.getMessage());
        }
    }

    private Escolha normalizarParaExibicao(Escolha escolha, Cargo cargo) {
        if (escolha.tipo() == TipoVoto.NOMINAL) {
            if (escolha.numeroCandidato() == null || escolha.numeroCandidato() < 0) {
                throw new RecursoInvalidoException("Número inválido para " + cargo.descricao() + ".");
            }
            boolean existe = urna.candidatos(cargo).stream().anyMatch(c -> Objects.equals(c.numero(), escolha.numeroCandidato()));
            return existe ? escolha : Escolha.nulo();
        }
        return escolha;
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
        if (urna.estado() != EstadoUrna.ENCERRADA) return mensagem("O boletim só pode ser emitido após o encerramento.", false, false, "/");
        return mesario("", "");
    }

    private String boletimHtml(BoletimUrna b) {
        StringBuilder bu = new StringBuilder("<section class='mesario-panel bu-paper'>")
                .append("<div class='bu-header'><div><p class='eyebrow'>APURAÇÃO OFICIAL</p><h2>BOLETIM DE URNA</h2></div></div>")
                .append("<div class='bu-stats'>")
                .append("<div><strong>").append(b.eleitoresAptos()).append("</strong><span>eleitores aptos</span></div>")
                .append("<div><strong>").append(b.comparecimentos()).append("</strong><span>comparecimentos</span></div>")
                .append("<div><strong>").append(b.abstencoes()).append("</strong><span>abstenções</span></div>")
                .append("<div><strong>").append(b.votosValidos()).append("</strong><span>votos válidos</span></div>")
                .append("<div><strong>").append(b.votosBrancos()).append("</strong><span>votos brancos</span></div>")
                .append("<div><strong>").append(b.votosNulos()).append("</strong><span>votos nulos</span></div>")
                .append("</div>");

        if (!b.houveVotosValidos()) bu.append(alerta("Não houve votos válidos; não há candidatos eleitos.", false));

        for (Cargo cargo : Cargo.values()) {
            bu.append("<h3>").append(esc(cargo.descricao())).append("</h3>")
                    .append("<table><thead><tr><th>Candidato</th><th>Votos</th><th>% dos válidos</th><th>Resultado</th></tr></thead><tbody>");
            urna.candidatos(cargo).stream().sorted(Comparator.comparingInt(Candidato::numero)).forEach(c -> {
                boolean eleito = b.eleitos().contains(c);
                int votos = b.votosPorCandidato().getOrDefault(c, 0);
                double pct = b.percentualPorCandidato().getOrDefault(c, 0.0);
                String pctFmt = String.format(Locale.forLanguageTag("pt-BR"), "%.2f%%", pct);
                bu.append("<tr class='").append(eleito ? "row-elected" : "").append("'><td><strong>").append(c.numero()).append("</strong> - ").append(esc(c.nome())).append("</td><td>")
                        .append(votos).append("</td><td><div class='pct-cell'><strong>").append(pctFmt).append("</strong><div class='pct-bar'><div style='width:")
                        .append(String.format(Locale.US, "%.1f", Math.min(100.0, pct))).append("%'></div></div></div></td><td>")
                        .append(eleito ? "<span class='pill pill-elected'>Eleito</span>" : "<span class='pill pill-defeated'>Não eleito</span>")
                        .append("</td></tr>");
            });
            bu.append("</tbody></table>");
        }
        bu.append("<p class='muted'>Em caso de empate na última vaga, vence o menor número de candidato.</p></section>");
        return bu.toString();
    }

    private Escolha escolha(String valor) {
        if (valor == null) throw new RecursoInvalidoException("Preencha as três escolhas.");
        String normalizado = valor.trim().toUpperCase(Locale.ROOT);
        if (normalizado.equals("BRANCO")) return Escolha.branco();
        if (normalizado.equals("NULO")) return Escolha.nulo();
        try { return Escolha.nominal(Integer.parseInt(normalizado)); }
        catch (NumberFormatException e) { throw new RecursoInvalidoException("Digite um número válido, BRANCO ou NULO."); }
    }

    private String formOcultaEscolhas(String id, String action, Map<String, String> dados) {
        return "<form id='" + id + "' method='post' action='" + action + "'>" + hidden("titulo", dados.get("titulo"))
                + hidden("presidente", dados.get("presidente")) + hidden("deputado1", dados.get("deputado1")) + hidden("deputado2", dados.get("deputado2"))
                + "</form>";
    }

    private String hidden(String key, String value) { return "<input type='hidden' name='" + key + "' value='" + esc(value == null ? "" : value) + "'>"; }

    private String mensagem(String texto, boolean sucesso, boolean telaFim, String destino) {
        String aba = "/votar".equals(destino) ? "votacao" : "mesario";
        StringBuilder lcd = new StringBuilder();
        if (telaFim) {
            lcd.append("<div class='lcd-fim'><h1>FIM</h1></div>");
        } else {
            lcd.append("<div class='lcd-top'><span>JUSTIÇA ELEITORAL</span><span>URNA ELETRÔNICA</span></div>")
                    .append("<div class='lcd-standby'>").append(alerta(texto, sucesso))
                    .append("<a id='link-principal' href='").append(destino).append("' tabindex='-1' aria-hidden='true' class='hidden-link'></a></div>")
                    .append("<div class='lcd-footer'><span><strong>CONFIRMA</strong> continua</span></div>");
        }
        return paginaUrna("Urna eletrônica", lcd.toString(), "", telaFim ? "fim" : "mensagem", aba, false);
    }

    private String executarMesario(Operacao operacao) {
        try {
            return operacao.executar();
        } catch (RuntimeException e) {
            return mesario(e.getMessage(), "");
        }
    }

    private String executarAcaoSenha(String tituloAcao, String action, Operacao operacao) {
        try {
            return operacao.executar();
        } catch (RuntimeException e) {
            if (urna.estado() == EstadoUrna.ABERTA && urna.eleitorLiberado() == null) {
                return telaSenhaMesario(tituloAcao, action, e.getMessage());
            }
            return mesario(e.getMessage(), "");
        }
    }

    private static Map<String, String> lerParametros(HttpExchange exchange, boolean isPost) throws IOException {
        Map<String, String> campos = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isBlank()) {
            extrairPares(rawQuery, campos);
        }
        if (isPost) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                extrairPares(body, campos);
            }
        }
        return campos;
    }

    private static void extrairPares(String texto, Map<String, String> destino) {
        for (String parte : texto.split("&")) {
            if (parte.isEmpty()) continue;
            String[] kv = parte.split("=", 2);
            destino.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
    }

    private static String alerta(String texto, boolean sucesso) { return "<div class='alert " + (sucesso ? "success" : "error") + "'>" + esc(texto) + "</div>"; }
    private static String esc(String texto) { return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String escJs(String texto) { return texto.replace("\\", "\\\\").replace("\"", "\\\""); }

    private static void responderTexto(HttpExchange exchange, int status, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void redirecionar(HttpExchange exchange, String destino) throws IOException {
        exchange.getResponseHeaders().set("Location", destino);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(303, -1);
    }

    private static void responder(HttpExchange exchange, int status, String pagina) throws IOException {
        byte[] bytes = pagina.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String brasaoSvg(int size) {
        return "<svg width='" + size + "' height='" + size + "' viewBox='0 0 64 64' fill='none' xmlns='http://www.w3.org/2000/svg'>"
                + "<circle cx='32' cy='32' r='29' stroke='#334155' stroke-width='2.5' fill='#f8fafc'/>"
                + "<polygon points='32,8 37,23 53,23 40,33 45,49 32,39 19,49 24,33 11,23 27,23' stroke='#1e293b' stroke-width='2' fill='#e2e8f0'/>"
                + "<circle cx='32' cy='31' r='9' stroke='#1e293b' stroke-width='2' fill='#fff'/>"
                + "<path d='M24 31 Q32 27 40 31' stroke='#1e293b' stroke-width='1.8' fill='none'/>"
                + "</svg>";
    }

    private static String avatarSvg() {
        return "<svg width='60' height='60' viewBox='0 0 64 64' fill='none' xmlns='http://www.w3.org/2000/svg'>"
                + "<circle cx='32' cy='22' r='11' fill='#475569'/>"
                + "<path d='M14 54 C14 40 50 40 50 54 Z' fill='#475569'/>"
                + "</svg>";
    }

    private String paginaUrna(String titulo, String conteudoLcd, String conteudoAbaixo, String modo, String abaAtiva, boolean autoPoll) {
        String subTituloEquip = "mesario".equals(abaAtiva) ? "TERMINAL DO MESÁRIO" : "URNA ELETRÔNICA";
        String nav = "<a href='/' class='" + ("mesario".equals(abaAtiva) ? "tab-active" : "") + "'>Mesário</a>"
                + "<a href='/votar' class='" + ("votacao".equals(abaAtiva) ? "tab-active" : "") + "'>Votação</a>";

        return "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(titulo) + " - Urna Eletrônica</title>"
                + "<link rel='preconnect' href='https://fonts.googleapis.com'>"
                + "<link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@500;600;700&family=Newsreader:opsz,wght@6..72,400;6..72,500;6..72,600&display=swap' rel='stylesheet'>"
                + "<style>"
                + "*{box-sizing:border-box}"
                + ":root{"
                + "--font-sans:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "--font-serif:'Newsreader',Georgia,'Times New Roman',serif;"
                + "--font-mono:'JetBrains Mono','Courier New',monospace;"
                + "--bg-page:#F5F4EF;--bg-card:#FAF9F5;--bg-elevated:#FFFFFF;"
                + "--border-warm:#E3E0D6;--border-strong:#C8C4B7;"
                + "--ink:#141413;--ink-muted:#66655F;--accent:#C96442;"
                + "font-family:var(--font-sans);color:var(--ink);background:var(--bg-page);"
                + "-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale}"
                + "body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:var(--bg-page)}"
                + ".topbar{background:#FAF9F5;color:var(--ink);padding:12px max(24px,calc((100% - 1020px)/2));display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--border-warm)}"
                + ".topbar-brand{color:var(--ink);text-decoration:none;font-family:var(--font-serif);font-size:18px;font-weight:500}"
                + ".topbar-nav{display:flex;gap:8px}"
                + ".topbar-nav a{font-family:var(--font-sans);color:var(--ink-muted);text-decoration:none;font-size:13px;font-weight:600;padding:6px 14px;border-radius:7px;border:1px solid transparent;transition:all .15s}"
                + ".topbar-nav a:hover{color:var(--ink);background:#EAE7DF}"
                + ".topbar-nav a.tab-active{color:var(--ink);background:#FFFFFF;border-color:var(--border-strong);box-shadow:0 1px 2px rgba(20,20,19,.06)}"
                + "main{max-width:1020px;width:100%;margin:24px auto;padding:0 20px;flex:1}"
                + ".urna-chassis{background:linear-gradient(180deg,#EAE7DF 0%,#DEDAD0 100%);border:1px solid #C5C1B4;border-radius:14px;padding:24px;box-shadow:0 10px 28px rgba(20,20,19,.07),inset 0 1px 0 rgba(255,255,255,.8);display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:24px;align-items:stretch;max-width:100%}"
                + ".urna-screen-frame{background:#C2BEB1;padding:12px;border-radius:8px;box-shadow:inset 0 2px 5px rgba(20,20,19,.2);display:flex;min-width:0;max-width:100%;overflow:hidden}"
                + ".urna-lcd{background:#F8F7F2;border:2px solid #2C2B28;border-radius:4px;width:100%;min-width:0;max-width:100%;height:416px;min-height:416px;max-height:416px;overflow:hidden;padding:16px 20px;display:flex;flex-direction:column;justify-content:space-between;position:relative;pointer-events:none;user-select:none}"
                + ".lcd-top{flex-shrink:0;height:26px;display:flex;align-items:center;justify-content:space-between;font-family:var(--font-sans);font-size:11px;font-weight:700;color:#3D3D3A;letter-spacing:.05em;border-bottom:1px solid #DFDDD4;padding-bottom:6px;text-transform:uppercase}"
                + ".lcd-standby{text-align:center;margin:auto 0;padding:6px 8px}"
                + ".lcd-emblem{margin-bottom:6px}"
                + ".lcd-standby h2{font-family:var(--font-serif);margin:4px 0 8px;font-size:24px;font-weight:500;line-height:1.22;color:var(--ink)}"
                + ".estado-valor{font-family:var(--font-sans);font-size:20px;font-weight:800;letter-spacing:.06em;display:inline-block;margin-top:2px}"
                + ".lcd-kpis{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;max-width:400px;margin:10px auto}"
                + ".lcd-kpis div{background:#F3F1EA;border:1px solid #DEDAD0;padding:9px 6px;border-radius:6px;min-width:0}"
                + ".lcd-kpis strong{display:block;font-family:var(--font-mono);font-size:20px;font-weight:600;color:var(--ink)}.lcd-kpis span{display:block;font-size:11px;color:var(--ink-muted);overflow-wrap:anywhere}"
                + ".lcd-inst{font-size:13.5px;color:#3D3D3A;max-width:420px;margin:6px auto;line-height:1.45}"
                + ".lcd-inline-box{max-width:300px;margin:8px auto 0;text-align:center}"
                + ".lcd-menu-list{display:flex;flex-direction:column;gap:10px;max-width:290px;margin:14px auto 0}"
                + ".lcd-menu-opt{display:flex;align-items:center;gap:12px;background:#F3F1EA;border:1.5px solid #C8C4B7;border-radius:6px;padding:10px 14px;font-size:15px;font-weight:600;color:var(--ink)}"
                + ".lcd-menu-opt kbd{background:#2A2A27;color:#FFFFFF;font-family:var(--font-mono);font-size:15px;font-weight:700;padding:3px 10px;border-radius:4px;min-width:28px;text-align:center}"
                + ".lcd-hr{border:0;border-top:1px solid #DEDAD0;margin:12px auto;max-width:260px}"
                + ".lcd-body-single{margin:auto 0;padding:8px 0}"
                + ".lcd-centered{text-align:center}"
                + ".lcd-body-single h2{font-family:var(--font-serif);margin:0 0 8px;font-size:22px;font-weight:500}"
                + ".lcd-body-split{display:grid;grid-template-columns:minmax(0,1fr) 116px;gap:14px;align-items:start;margin:auto 0;padding:8px 0;min-width:0;max-width:100%}"
                + ".lcd-info{min-width:0}"
                + ".lcd-info h2{font-family:var(--font-serif);margin:0 0 8px;font-size:22px;font-weight:500}"
                + ".lcd-cargo{font-family:var(--font-sans);font-size:clamp(14px,3.6vw,19px);font-weight:800;letter-spacing:.01em;margin:0 0 8px;height:36px;line-height:1.15;display:flex;align-items:center;color:var(--ink);text-transform:uppercase;overflow-wrap:break-word}"
                + ".lcd-row{margin:4px 0;font-size:14px;min-height:22px}.lcd-row span{color:var(--ink-muted);display:inline-block;min-width:60px;font-weight:500}"
                + ".lcd-VoterName{font-family:var(--font-serif);font-size:20px;font-weight:500;color:var(--ink)}"
                + ".lcd-numero-row{display:flex;align-items:center;gap:10px;margin:8px 0 10px;height:48px}"
                + ".lcd-numero-row span{font-size:14px;font-weight:600;color:#3D3D3A}"
                + ".lcd-boxes{display:flex;gap:6px}"
                + ".digit-box{width:38px;height:46px;border:2px solid #141413;background:#FFFFFF;border-radius:3px;display:flex;align-items:center;justify-content:center;font-size:24px;font-weight:700;font-family:var(--font-mono);color:var(--ink)}"
                + ".digit-box.active{border-color:#C96442;animation:blink 1s step-end infinite}"
                + "@keyframes blink{50%{border-color:#B8B4A6}}"
                + ".lcd-cand-details{height:152px;overflow:hidden;display:flex;flex-direction:column;justify-content:flex-start;gap:6px}"
                + ".lcd-voto-banner{font-family:var(--font-sans);font-size:27px;font-weight:800;letter-spacing:.04em;color:var(--ink);margin:4px 0 2px;line-height:1.15}"
                + ".lcd-status-line{margin:4px 0 0;font-size:12.5px;font-weight:600;color:var(--ink-muted);min-height:18px}"
                + ".lcd-photo-box{border:1.5px solid #2C2B28;background:#FFFFFF;border-radius:4px;padding:8px;text-align:center;display:flex;flex-direction:column;align-items:center;justify-content:center;height:148px}"
                + ".lcd-photo-box span{font-size:10px;font-weight:700;letter-spacing:.05em;margin-top:6px;border-top:1px solid #E3E0D6;padding-top:4px;width:100%;color:#3D3D3A}"
                + ".lcd-field-label{display:block;font-weight:600;font-size:13px;color:#3D3D3A;text-align:center;margin-bottom:6px}"
                + ".lcd-input-main{display:block;width:100%;max-width:260px;height:44px;margin:0 auto;text-align:center;font-size:18px;font-family:var(--font-mono);font-weight:600;padding:6px 12px;border:1.5px solid #2C2B28;border-radius:6px;background:#FFFFFF;letter-spacing:.14em;color:var(--ink)}"
                + ".lcd-input-main::placeholder{color:#C8C5BA;opacity:1;letter-spacing:.14em;text-align:center;font-weight:500}"
                + ".lcd-input-main.input-erro{border-color:#B83B2A;background:#FDF2F0;color:#B83B2A;font-family:var(--font-sans);font-size:13.5px;font-weight:600;letter-spacing:0}"
                + ".hidden-link{position:absolute;width:1px;height:1px;overflow:hidden;opacity:0}"
                + ".lcd-review-dl{display:grid;grid-template-columns:165px 1fr;gap:8px 12px;background:#F3F1EA;border:1px solid #DEDAD0;border-radius:6px;padding:12px;margin:10px 0;font-size:13.5px}"
                + ".lcd-review-dl dt{color:var(--ink-muted);font-weight:500}.lcd-review-dl dd{margin:0;color:var(--ink);font-family:var(--font-mono)}"
                + ".lcd-fim{margin:auto;text-align:center}.lcd-fim h1{font-family:var(--font-sans);font-size:clamp(68px,16vw,96px);font-weight:800;margin:0;letter-spacing:.06em;color:var(--ink)}"
                + ".lcd-footer{flex-shrink:0;min-height:40px;border-top:2px solid #141413;padding-top:8px;font-size:11.5px;color:var(--ink);text-align:center;display:flex;align-items:center;justify-content:center}"
                + ".lcd-tse-footer{font-size:11px;line-height:1.35}"
                + ".urna-right{display:flex;flex-direction:column;gap:12px;min-width:0;max-width:100%}"
                + ".je-header{background:#FAF9F5;border:1px solid #C5C1B4;border-radius:8px;padding:10px 14px;display:flex;align-items:center;justify-content:center;gap:12px}"
                + ".je-title{font-family:var(--font-serif);font-weight:600;font-size:17px;color:var(--ink);line-height:1.15}"
                + ".je-title small{display:block;font-family:var(--font-sans);font-size:10px;font-weight:600;color:var(--ink-muted);letter-spacing:.08em;margin-top:2px;text-transform:uppercase}"
                + ".keypad{background:#2A2A27;border:1.5px solid #1A1A18;border-radius:10px;padding:20px 18px;flex:1;display:flex;flex-direction:column;justify-content:space-between;box-shadow:inset 0 2px 6px rgba(0,0,0,.35)}"
                + ".num-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;max-width:240px;margin:0 auto;width:100%}"
                + ".key-num{background:linear-gradient(180deg,#43433E 0%,#32322E 100%);color:#FFFFFF;border:1px solid #62615A;border-radius:6px;height:46px;font-family:var(--font-mono);font-size:19px;font-weight:700;display:flex;align-items:center;justify-content:center;padding:0;cursor:pointer;box-shadow:0 3px 0 #141412,inset 0 1px 0 rgba(255,255,255,.14);user-select:none;transition:background .1s,transform .06s}"
                + ".key-num:hover{background:linear-gradient(180deg,#4E4E48 0%,#3B3B36 100%);border-color:#78776F}"
                + ".key-num:active{transform:translateY(2px);box-shadow:0 1px 0 #141412}"
                + ".key-zero{grid-column:2}"
                + ".action-keys{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;align-items:stretch;margin-top:16px}"
                + ".key-act{font-family:var(--font-sans);border-radius:6px;height:46px;font-weight:800;font-size:11.5px;letter-spacing:.03em;cursor:pointer;padding:0 6px;display:flex;align-items:center;justify-content:center;text-align:center;text-transform:uppercase;user-select:none;transition:filter .1s,transform .06s}"
                + ".key-act:hover{filter:brightness(1.05)}"
                + ".key-act:active{transform:translateY(2px)}"
                + ".key-branco{background:linear-gradient(180deg,#FFFFFF 0%,#EAE7DF 100%);color:#141413;border:1px solid #C8C4B7;box-shadow:0 3px 0 #9E9B90}"
                + ".key-corrige{background:linear-gradient(180deg,#F06434 0%,#D95022 100%);color:#FFFFFF;border:1px solid #B83D14;box-shadow:0 3px 0 #7C2D12;text-shadow:0 1px 1px rgba(0,0,0,.2)}"
                + ".key-confirma{background:linear-gradient(180deg,#249E57 0%,#1B8045 100%);color:#FFFFFF;border:1px solid #146334;box-shadow:0 3px 0 #0F4626;text-shadow:0 1px 1px rgba(0,0,0,.2)}"
                + ".mesario-panel{background:var(--bg-card);border:1px solid var(--border-warm);border-radius:12px;padding:18px 20px;margin-top:18px}"
                + ".compact-panel{padding:14px 18px}"
                + ".mesario-header{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:10px;font-size:13px;font-weight:600;color:var(--ink-muted)}"
                + ".status-badge{padding:4px 10px;border-radius:999px;font-size:11px;font-weight:700}"
                + ".badge-open{background:#DCEFE2;color:#175630}"
                + ".ref-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(185px,1fr));gap:6px;margin-top:4px}"
                + ".ref-item{background:#FFFFFF;border:1px solid var(--border-warm);border-radius:6px;padding:5px 9px;font-size:12px;display:flex;align-items:center;gap:6px;color:var(--ink)}"
                + ".ref-item.ref-done{opacity:.55}"
                + ".ref-tag{margin-left:auto;font-style:normal;font-size:10.5px;font-weight:600;color:var(--ink-muted)}"
                + ".colinha-columns{display:grid;grid-template-columns:1fr 1.25fr;gap:14px}.colinha-columns h4{font-family:var(--font-serif);margin:2px 0 6px;font-size:14px;font-weight:500}"
                + "table{width:100%;border-collapse:collapse;margin-top:6px;font-size:13.5px}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--border-warm)}"
                + "th{background:#F3F1EA;font-size:11px;font-weight:600;text-transform:uppercase;color:var(--ink-muted)}"
                + ".row-elected{background:#EFF7F1}"
                + ".pill{display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;font-weight:600}"
                + ".pill-elected{background:#1F8A4C;color:#fff}.pill-defeated{background:#EAE7DF;color:var(--ink-muted)}"
                + ".bu-header{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--border-strong);padding-bottom:10px;margin-bottom:14px}"
                + ".bu-header h2{font-family:var(--font-serif);margin:2px 0 0;font-size:20px;font-weight:500}.eyebrow{font-size:10.5px;font-weight:600;color:var(--ink-muted);margin:0;letter-spacing:.06em}"
                + ".bu-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin-bottom:14px}"
                + ".bu-stats div{background:#FFFFFF;border:1px solid var(--border-warm);padding:10px 8px;border-radius:6px;text-align:center}"
                + ".bu-stats strong{display:block;font-family:var(--font-mono);font-size:18px;font-weight:600}.bu-stats span{font-size:11px;color:var(--ink-muted)}"
                + ".pct-cell{display:flex;align-items:center;gap:8px;font-family:var(--font-mono);font-size:12.5px}.pct-bar{flex:1;max-width:90px;height:6px;background:#EAE7DF;border-radius:99px;overflow:hidden}.pct-bar div{height:100%;background:#1F8A4C}"
                + ".alert{padding:8px 12px;border-radius:6px;margin:6px 0;font-size:12.5px;font-weight:600}"
                + ".success{background:#DCEFE2;color:#144527;border:1px solid #A8D5B7}.error{background:#F9E3DF;color:#7A2419;border:1px solid #E6B3AA}"
                + ".muted{color:var(--ink-muted);font-size:12.5px;margin:2px 0}code{font-family:var(--font-mono);background:#EAE7DF;padding:1px 5px;border-radius:4px;font-size:12px}"
                + "footer{text-align:center;padding:14px;font-size:12px;color:var(--ink-muted);border-top:1px solid var(--border-warm);background:#FAF9F5}"
                + "@media(max-width:820px){.urna-chassis{grid-template-columns:minmax(0,1fr);padding:16px;gap:16px}.bu-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.colinha-columns{grid-template-columns:minmax(0,1fr)}}"
                + "@media(max-width:560px){html,body{overflow-x:hidden}main{margin:12px auto;padding:0 12px}.urna-chassis{padding:12px;gap:12px}.urna-screen-frame{padding:8px}.urna-lcd{padding:12px 14px;height:360px;min-height:360px;max-height:360px}.lcd-body-split{grid-template-columns:minmax(0,1fr) 88px;gap:10px;padding:4px 0}.lcd-cargo{font-size:13.5px;height:28px;margin-bottom:4px}.lcd-cand-details{height:132px}.lcd-photo-box{height:112px;padding:6px}.lcd-avatar svg{width:42px;height:42px}.digit-box{width:34px;height:42px;font-size:20px}.lcd-voto-banner{font-size:21px}.lcd-kpis{gap:6px}.lcd-kpis div{padding:6px 4px}.lcd-kpis strong{font-size:17px}.lcd-kpis span{font-size:8.5px;letter-spacing:-0.02em}.bu-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.keypad{padding:14px 12px}.key-act{font-size:10.5px}}"
                + "</style></head><body data-modo='" + esc(modo) + "' data-status='" + esc(statusCabina()) + "' data-poll='" + autoPoll + "'>"
                + "<div class='topbar'><a class='topbar-brand' href='/'>Urna Eletrônica</a>"
                + "<nav class='topbar-nav'>" + nav + "</nav></div>"
                + "<main>"
                + "<div class='urna-chassis'>"
                + "<div class='urna-screen-frame'><div class='urna-lcd'>" + conteudoLcd + "</div></div>"
                + "<div class='urna-right'>"
                + "<div class='je-header'>" + brasaoSvg(34) + "<div class='je-title'>JUSTIÇA ELEITORAL<small>" + subTituloEquip + "</small></div></div>"
                + "<div class='keypad'>"
                + "<div class='num-grid'>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('1')\">1</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('2')\">2</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('3')\">3</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('4')\">4</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('5')\">5</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('6')\">6</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('7')\">7</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('8')\">8</button>"
                + "<button type='button' tabindex='-1' class='key-num' onclick=\"teclaDigito('9')\">9</button>"
                + "<button type='button' tabindex='-1' class='key-num key-zero' onclick=\"teclaDigito('0')\">0</button>"
                + "</div>"
                + "<div class='action-keys'>"
                + "<button type='button' tabindex='-1' class='key-act key-branco' onclick='teclaBranco()'>BRANCO</button>"
                + "<button type='button' tabindex='-1' class='key-act key-corrige' onclick='teclaCorrige()'>CORRIGE</button>"
                + "<button type='button' tabindex='-1' class='key-act key-confirma' onclick='teclaConfirma()'>CONFIRMA</button>"
                + "</div></div></div></div>"
                + conteudoAbaixo
                + "</main>"
                + "<footer>DCC603 Engenharia de Software I - UFMG - Grupo 2</footer>"
                + scriptUrna()
                + "</body></html>";
    }

    private static String scriptUrna() {
        return "<script>"
                + "const modo = document.body.dataset.modo;"
                + "let audioCtx = null;"
                + "let erroTimer = null;"
                + "function ctxAudio(){ if(!audioCtx){ const AC = window.AudioContext || window.webkitAudioContext; if(AC) audioCtx = new AC(); } return audioCtx; }"
                + "function bipTecla(freq=1150, dur=0.045){ try{ const ctx=ctxAudio(); if(!ctx) return; const o=ctx.createOscillator(), g=ctx.createGain(); o.type='sine'; o.frequency.value=freq; g.gain.value=0.08; o.connect(g); g.connect(ctx.destination); o.start(); o.stop(ctx.currentTime+dur); }catch(e){} }"
                + "function somFimUrna(){ try{ const ctx=ctxAudio(); if(!ctx) return; const notas=[2489,2637,2489,2637,2489,2637,2489,2637]; let t=ctx.currentTime;"
                + "notas.forEach((f)=>{ const o=ctx.createOscillator(), g=ctx.createGain(); o.type='triangle'; o.frequency.value=f; g.gain.value=0.14; o.connect(g); g.connect(ctx.destination); o.start(t); o.stop(t+0.075); t+=0.08; });"
                + "const of=ctx.createOscillator(), gf=ctx.createGain(); of.type='triangle'; of.frequency.value=2637; gf.gain.value=0.18; of.connect(gf); gf.connect(ctx.destination); of.start(t); of.stop(t+0.55); }catch(e){} }"
                + "if(modo==='fim'){ try{ sessionStorage.removeItem('urna_rascunho'); }catch(e){} window.addEventListener('load', ()=>{ setTimeout(somFimUrna, 80); setTimeout(()=>{ window.location.replace('/votar'); }, 7000); }); }"
                + "if(modo!=='cedula' && modo!=='fim'){ try{ sessionStorage.removeItem('urna_rascunho'); }catch(e){} }"
                + "if(document.body.dataset.poll==='true'){"
                + "  const statusInicial = document.body.dataset.status;"
                + "  setInterval(()=>{"
                + "    fetch('/status-urna', {cache:'no-store'}).then(r=>r.text()).then(st=>{"
                + "      if(st && st !== statusInicial){ try{ sessionStorage.removeItem('urna_rascunho'); }catch(e){} window.location.reload(); }"
                + "    }).catch(()=>{});"
                + "  }, 1200);"
                + "}"
                + "function limparErroCampo(){"
                + "  if(erroTimer){ clearTimeout(erroTimer); erroTimer = null; }"
                + "  const c = document.getElementById('campo-ativo');"
                + "  if(!c || !c.dataset.erroAtivo) return;"
                + "  delete c.dataset.erroAtivo;"
                + "  c.classList.remove('input-erro');"
                + "  c.type = c.dataset.tipo || 'text';"
                + "  c.value = '';"
                + "}"
                + "function mostrarErroCampo(msg){"
                + "  const c = document.getElementById('campo-ativo');"
                + "  if(!c) return;"
                + "  if(erroTimer) clearTimeout(erroTimer);"
                + "  c.dataset.erroAtivo = '1';"
                + "  c.type = 'text';"
                + "  c.value = msg;"
                + "  c.classList.add('input-erro');"
                + "  erroTimer = setTimeout(limparErroCampo, 2200);"
                + "}"
                + "const campoInicial = document.getElementById('campo-ativo');"
                + "if(campoInicial && campoInicial.dataset.erro){"
                + "  mostrarErroCampo(campoInicial.dataset.erro);"
                + "}"
                + "const etapas = ["
                + "  { id:'input-presidente', titulo:'PRESIDENTE', cargo:'PRESIDENTE', digitos:2, etapa:'CARGO 1 DE 3' },"
                + "  { id:'input-deputado1', titulo:'DEPUTADO FEDERAL (1ª VAGA)', cargo:'DEPUTADO_FEDERAL', digitos:3, etapa:'CARGO 2 DE 3' },"
                + "  { id:'input-deputado2', titulo:'DEPUTADO FEDERAL (2ª VAGA)', cargo:'DEPUTADO_FEDERAL', digitos:3, etapa:'CARGO 3 DE 3' }"
                + "];"
                + "let indiceEtapa = 0; let bufferAtual = '';"
                + "function desfocusTecla(){ if(document.activeElement && document.activeElement.tagName==='BUTTON') document.activeElement.blur(); }"
                + "function salvarRascunho(){"
                + "  if(modo!=='cedula') return;"
                + "  try{"
                + "    sessionStorage.setItem('urna_rascunho', JSON.stringify({"
                + "      st: document.body.dataset.status,"
                + "      i: indiceEtapa,"
                + "      p: document.getElementById('input-presidente').value,"
                + "      d1: document.getElementById('input-deputado1').value,"
                + "      d2: document.getElementById('input-deputado2').value,"
                + "      b: bufferAtual"
                + "    }));"
                + "  }catch(e){}"
                + "}"
                + "function renderEtapa(msgCustom){"
                + "  if(modo!=='cedula') return;"
                + "  const et = etapas[indiceEtapa];"
                + "  document.getElementById('etapa-indicador').textContent = et.etapa;"
                + "  document.getElementById('lcd-cargo-titulo').textContent = et.titulo;"
                + "  const numWrap = document.getElementById('lcd-numero-wrap');"
                + "  const caixas = document.getElementById('lcd-caixas');"
                + "  caixas.innerHTML = '';"
                + "  const nomeRow = document.getElementById('lcd-nome-row');"
                + "  const nomeEl = document.getElementById('lcd-cand-nome');"
                + "  const bannerEl = document.getElementById('lcd-voto-banner');"
                + "  const statusEl = document.getElementById('lcd-cand-status');"
                + "  const fotoWrap = document.getElementById('lcd-foto-wrap');"
                + "  if(bufferAtual==='BRANCO'){"
                + "    numWrap.style.visibility = 'hidden';"
                + "    nomeRow.style.visibility = 'hidden';"
                + "    nomeEl.textContent = '';"
                + "    bannerEl.style.display = 'block';"
                + "    bannerEl.style.color = '#141413';"
                + "    bannerEl.textContent = 'VOTO EM BRANCO';"
                + "    statusEl.textContent = msgCustom || 'Aperte CONFIRMA para confirmar ou CORRIGE para corrigir';"
                + "    statusEl.style.color = '#3D3D3A';"
                + "    fotoWrap.style.visibility = 'hidden';"
                + "    return;"
                + "  }"
                + "  numWrap.style.visibility = 'visible';"
                + "  for(let i=0;i<et.digitos;i++){"
                + "    const d = document.createElement('div');"
                + "    d.className = 'digit-box' + (i===bufferAtual.length ? ' active' : '');"
                + "    d.textContent = bufferAtual[i] || '';"
                + "    caixas.appendChild(d);"
                + "  }"
                + "  if(bufferAtual.length < et.digitos){"
                + "    nomeRow.style.visibility = 'hidden';"
                + "    nomeEl.textContent = '';"
                + "    bannerEl.style.display = 'none';"
                + "    bannerEl.textContent = '';"
                + "    statusEl.textContent = msgCustom || ('Digite os ' + et.digitos + ' dígitos ou aperte BRANCO');"
                + "    statusEl.style.color = msgCustom ? '#B83B2A' : '#66655F';"
                + "    fotoWrap.style.visibility = 'hidden';"
                + "  } else {"
                + "    const mapa = (window.CANDIDATOS_URNA && window.CANDIDATOS_URNA[et.cargo]) || {};"
                + "    const nome = mapa[bufferAtual];"
                + "    if(nome){"
                + "      const dep1Val = document.getElementById('input-deputado1').value;"
                + "      if(indiceEtapa===2 && dep1Val===bufferAtual){"
                + "        nomeRow.style.visibility = 'visible';"
                + "        nomeEl.textContent = nome;"
                + "        bannerEl.style.display = 'none';"
                + "        statusEl.textContent = 'Candidato já escolhido na 1ª vaga. Aperte CORRIGE.';"
                + "        statusEl.style.color = '#B83B2A';"
                + "        fotoWrap.style.visibility = 'hidden';"
                + "      } else {"
                + "        nomeRow.style.visibility = 'visible';"
                + "        nomeEl.textContent = nome;"
                + "        bannerEl.style.display = 'none';"
                + "        statusEl.textContent = 'Candidato selecionado · aperte CONFIRMA';"
                + "        statusEl.style.color = '#1F8A4C';"
                + "        fotoWrap.style.visibility = 'visible';"
                + "        document.getElementById('lcd-foto-legenda').textContent = et.cargo==='PRESIDENTE' ? 'PRESIDENTE' : 'DEP. FEDERAL';"
                + "      }"
                + "    } else {"
                + "      nomeRow.style.visibility = 'visible';"
                + "      nomeEl.textContent = 'NÚMERO ERRADO';"
                + "      bannerEl.style.display = 'block';"
                + "      bannerEl.style.color = '#141413';"
                + "      bannerEl.textContent = 'VOTO NULO';"
                + "      statusEl.textContent = 'Aperte CONFIRMA para anular ou CORRIGE para corrigir';"
                + "      statusEl.style.color = '#784912';"
                + "      fotoWrap.style.visibility = 'hidden';"
                + "    }"
                + "  }"
                + "}"
                + "function teclaDigito(d){"
                + "  desfocusTecla();"
                + "  if(modo==='fim') return;"
                + "  bipTecla();"
                + "  if(modo==='standby' || modo==='identificacao' || modo==='mesario-senha'){"
                + "    limparErroCampo();"
                + "    const c = document.getElementById('campo-ativo');"
                + "    const max = (c && c.maxLength > 0) ? c.maxLength : 12;"
                + "    if(c && c.value.length < max) c.value += d;"
                + "    return;"
                + "  }"
                + "  if(modo==='mesario-menu'){"
                + "    if(d==='1'){ window.location.href='/?tela=encerrar'; return; }"
                + "    if(d==='2'){ const fr=document.getElementById('form-reiniciar'); if(fr) fr.submit(); return; }"
                + "    return;"
                + "  }"
                + "  if(modo==='cedula'){"
                + "    const et = etapas[indiceEtapa];"
                + "    if(bufferAtual==='BRANCO') return;"
                + "    if(bufferAtual.length < et.digitos){ bufferAtual += d; salvarRascunho(); renderEtapa(); }"
                + "  }"
                + "}"
                + "function teclaCorrige(){"
                + "  desfocusTecla();"
                + "  if(modo==='fim') return;"
                + "  bipTecla(720,0.06);"
                + "  if(modo==='standby' || modo==='identificacao'){"
                + "    limparErroCampo();"
                + "    const c = document.getElementById('campo-ativo');"
                + "    if(c) c.value = '';"
                + "    return;"
                + "  }"
                + "  if(modo==='mesario-senha'){"
                + "    const c = document.getElementById('campo-ativo');"
                + "    const tinhaErro = c && c.dataset.erroAtivo;"
                + "    limparErroCampo();"
                + "    if(tinhaErro) return;"
                + "    if(c && c.value.length > 0){ c.value = ''; return; }"
                + "    window.location.href = '/';"
                + "    return;"
                + "  }"
                + "  if(modo==='mesario-menu' || modo==='confirmar-eleitor'){ window.location.href='/'; return; }"
                + "  if(modo==='aguardando-cabina'){ const fc=document.getElementById('form-cancelar'); if(fc) fc.submit(); return; }"
                + "  if(modo==='cedula'){ bufferAtual=''; salvarRascunho(); renderEtapa(); return; }"
                + "  if(modo==='revisao'){ const fc=document.getElementById('form-corrige'); if(fc) fc.submit(); }"
                + "}"
                + "function teclaBranco(){"
                + "  desfocusTecla();"
                + "  if(modo==='fim') return;"
                + "  bipTecla(920,0.06);"
                + "  if(modo==='identificacao'){ window.location.href='/?tela=menu'; return; }"
                + "  if(modo==='aguardando-cabina'){ const fa=document.getElementById('form-abster'); if(fa) fa.submit(); return; }"
                + "  if(modo==='confirmar-eleitor'){ const fb=document.getElementById('form-branco'); if(fb) fb.submit(); return; }"
                + "  if(modo==='cedula'){"
                + "    if(bufferAtual.length > 0 && bufferAtual !== 'BRANCO'){ bipTecla(400,0.12); renderEtapa('Para votar em BRANCO, aperte CORRIGE antes.'); return; }"
                + "    bufferAtual='BRANCO'; salvarRascunho(); renderEtapa();"
                + "  }"
                + "}"
                + "function teclaConfirma(){"
                + "  desfocusTecla();"
                + "  if(modo==='fim') return;"
                + "  if(modo==='standby' || modo==='mesario-senha'){"
                + "    const c = document.getElementById('campo-ativo');"
                + "    const tinhaErro = c && c.dataset.erroAtivo;"
                + "    limparErroCampo();"
                + "    if(tinhaErro){ bipTecla(400,0.12); return; }"
                + "    const frm = document.getElementById('form-urna');"
                + "    if(c && c.value.trim().length > 0 && frm){ bipTecla(1480,0.08); frm.submit(); return; }"
                + "    bipTecla(400,0.15); mostrarErroCampo('Digite a senha.'); return;"
                + "  }"
                + "  if(modo==='mesario-menu' || modo==='aguardando-cabina'){ bipTecla(400,0.12); return; }"
                + "  if(modo==='identificacao'){"
                + "    const c = document.getElementById('campo-ativo');"
                + "    const tinhaErro = c && c.dataset.erroAtivo;"
                + "    limparErroCampo();"
                + "    if(tinhaErro){ bipTecla(400,0.12); return; }"
                + "    const frm = document.getElementById('form-urna');"
                + "    if(c && c.value.trim().length > 0 && frm){ bipTecla(1480,0.08); frm.submit(); return; }"
                + "    bipTecla(400,0.15); mostrarErroCampo('Digite o título.'); return;"
                + "  }"
                + "  if(modo==='encerrada' || modo==='confirmar-eleitor'){ bipTecla(1480,0.08); const f=document.getElementById('form-urna'); if(f) f.submit(); return; }"
                + "  if(modo==='revisao'){ somFimUrna(); const f=document.getElementById('form-urna'); if(f) setTimeout(()=>f.submit(), 180); return; }"
                + "  if(modo==='cedula'){"
                + "    const et = etapas[indiceEtapa];"
                + "    let valorGravar = '';"
                + "    if(bufferAtual==='BRANCO'){ valorGravar='BRANCO'; }"
                + "    else if(bufferAtual.length === et.digitos){"
                + "      const mapa = (window.CANDIDATOS_URNA && window.CANDIDATOS_URNA[et.cargo]) || {};"
                + "      if(mapa[bufferAtual]){"
                + "        if(indiceEtapa===2 && document.getElementById('input-deputado1').value===bufferAtual){ bipTecla(400,0.18); return; }"
                + "        valorGravar = bufferAtual;"
                + "      } else {"
                + "        valorGravar = 'NULO';"
                + "      }"
                + "    } else { bipTecla(400,0.15); renderEtapa('Complete o número ou aperte BRANCO.'); return; }"
                + "    bipTecla(1560,0.08);"
                + "    document.getElementById(et.id).value = valorGravar;"
                + "    if(indiceEtapa < etapas.length - 1){"
                + "      indiceEtapa++;"
                + "      bufferAtual = '';"
                + "      salvarRascunho();"
                + "      renderEtapa();"
                + "    } else { try{ sessionStorage.removeItem('urna_rascunho'); }catch(e){} document.getElementById('form-urna').submit(); }"
                + "    return;"
                + "  }"
                + "  const lnk = document.getElementById('link-principal');"
                + "  if(lnk){ bipTecla(1480,0.08); window.location.href = lnk.getAttribute('href'); }"
                + "}"
                + "window.addEventListener('keydown', (ev)=>{"
                + "  if(ev.target && ev.target.tagName==='INPUT' && !ev.target.readOnly) return;"
                + "  if(ev.key===' ' && ev.target && ev.target.tagName==='BUTTON'){ ev.preventDefault(); return; }"
                + "  if(ev.key>='0' && ev.key<='9'){ ev.preventDefault(); teclaDigito(ev.key); }"
                + "  else if(ev.key==='Backspace'){ ev.preventDefault(); teclaCorrige(); }"
                + "  else if(ev.key==='Enter'){ ev.preventDefault(); teclaConfirma(); }"
                + "});"
                + "if(modo==='cedula'){"
                + "  try{"
                + "    const raw = sessionStorage.getItem('urna_rascunho');"
                + "    if(raw){"
                + "      const r = JSON.parse(raw);"
                + "      if(r && r.st === document.body.dataset.status){"
                + "        indiceEtapa = Math.max(0, Math.min(2, Number(r.i) || 0));"
                + "        document.getElementById('input-presidente').value = r.p || '';"
                + "        document.getElementById('input-deputado1').value = r.d1 || '';"
                + "        document.getElementById('input-deputado2').value = r.d2 || '';"
                + "        bufferAtual = r.b || '';"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "  if(!bufferAtual){"
                + "    const v0 = document.getElementById(etapas[indiceEtapa].id).value;"
                + "    bufferAtual = (v0==='NULO') ? '0'.repeat(etapas[indiceEtapa].digitos) : (v0 || '');"
                + "  }"
                + "  salvarRascunho();"
                + "  renderEtapa();"
                + "}"
                + "</script>";
    }

    @FunctionalInterface private interface Operacao { String executar(); }
}
