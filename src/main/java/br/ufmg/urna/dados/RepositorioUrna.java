package br.ufmg.urna.dados;

import java.io.IOException;

public interface RepositorioUrna {
    DadosUrna carregar() throws IOException;
    void salvar(DadosUrna dados) throws IOException;
}
