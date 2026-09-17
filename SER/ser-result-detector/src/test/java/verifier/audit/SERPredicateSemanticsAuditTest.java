package verifier.audit;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import verifier.PredicateSemanticsRegression;

/** Runs the independent serial-oracle differential suite as an opt-in audit. */
@Tag("audit")
class SERPredicateSemanticsAuditTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceleratedAndSnapshotEncodingsMatchIndependentSerialReplay() throws Exception {
        PredicateSemanticsRegression.runAll(temporaryDirectory);
    }
}
