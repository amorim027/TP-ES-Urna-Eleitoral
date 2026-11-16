package br.ufmg.urna;

public record Escolha(TipoVoto tipo, Integer numeroCandidato) {
    public static Escolha nominal(int numero) { return new Escolha(TipoVoto.NOMINAL, numero); }
    public static Escolha branco() { return new Escolha(TipoVoto.BRANCO, null); }
    public static Escolha nulo() { return new Escolha(TipoVoto.NULO, null); }
}
