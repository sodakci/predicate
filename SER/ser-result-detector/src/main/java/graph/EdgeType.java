package graph;

public enum EdgeType {
    WW, RW, WR, SO,
    /** Predicate-read read dependency: result-changing writer -> reader (graph A / readFrom role). */
    PR_WR,
    /** Predicate-read anti dependency: reader -> later result-changing writer (graph B role). */
    PR_RW
}
