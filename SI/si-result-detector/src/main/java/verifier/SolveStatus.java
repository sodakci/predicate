package verifier;

/** SAT, UNSAT and backend timeout are distinct checker outcomes. */
enum SolveStatus {
    SAT,
    UNSAT,
    TIMEOUT
}
