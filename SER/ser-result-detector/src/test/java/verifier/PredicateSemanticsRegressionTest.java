package verifier;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredicateSemanticsRegressionTest {
    @TempDir
    Path directory;

    @Test
    void acceleratedAndSnapshotStrategiesAgreeWithSerialExecution() throws Exception {
        PredicateSemanticsRegression.runAll(directory);
    }
}
