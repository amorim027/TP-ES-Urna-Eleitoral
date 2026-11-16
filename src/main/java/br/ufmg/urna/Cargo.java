package br.ufmg.urna;

public enum Cargo {
    PRESIDENTE("Presidente"), DEPUTADO_FEDERAL("Deputado federal");

    private final String descricao;

    Cargo(String descricao) { this.descricao = descricao; }

    public String descricao() { return descricao; }
}
