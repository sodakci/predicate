package verifier;

/**
 * Three-state SAT outcome. Timeout is never represented as UNSAT.
 */
public enum SolveStatus {
    SAT,
    UNSAT,
    TIMEOUT
}
