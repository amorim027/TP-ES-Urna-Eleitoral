package br.ufmg.urna;

public record Eleitor(String titulo, String nome, boolean participou) {
    public Eleitor comParticipacao() { return new Eleitor(titulo, nome, true); }
}
