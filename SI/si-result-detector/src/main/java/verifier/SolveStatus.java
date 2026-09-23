package verifier;

/** 检测器仅返回可满足或不可满足；进程超时由外部 runner 管理。 */
enum SolveStatus {
    SAT,
    UNSAT
}
