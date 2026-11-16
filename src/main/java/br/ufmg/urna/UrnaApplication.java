package br.ufmg.urna;

import br.ufmg.urna.dados.RepositorioArquivo;
import br.ufmg.urna.negocio.ServicoUrna;
import br.ufmg.urna.ui.ServidorHttp;

import java.nio.file.Path;

public final class UrnaApplication {
    private UrnaApplication() { }

    public static void main(String[] args) throws Exception {
        Path pastaDados = Path.of(System.getenv().getOrDefault("URNA_DADOS_DIR", "data"));
        Path arquivo = pastaDados.resolve("urna.db");
        Path eleitores = Path.of(System.getenv().getOrDefault("URNA_ELEITORES_CSV", "data/eleitores.csv"));
        Path candidatos = Path.of(System.getenv().getOrDefault("URNA_CANDIDATOS_CSV", "data/candidatos.csv"));
        String senha = System.getenv().getOrDefault("URNA_SENHA", "123456");
        int porta = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        ServicoUrna urna = new ServicoUrna(new RepositorioArquivo(arquivo, eleitores, candidatos), senha);
        new ServidorHttp(urna).iniciar(porta);
    }
}
