import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import verifier.SIVerifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SIPruningCliTest {
    @Test
    void exposesAllSiPruningModesAndConstraintStatCommand() {
        var commandLine = new CommandLine(new Main());
        assertTrue(commandLine.getSubcommands().containsKey("constraint-stat"));

        var audit = commandLine.getSubcommands().get("audit");
        var option = audit.getCommandSpec().findOption("--pruning-mode");
        assertNotNull(option);
        assertTrue(option.type() == SIVerifier.PruningMode.class);
        assertTrue(java.util.Arrays.asList(SIVerifier.PruningMode.values())
                .containsAll(java.util.List.of(
                        SIVerifier.PruningMode.NONE,
                        SIVerifier.PruningMode.REACHABILITY,
                        SIVerifier.PruningMode.SNAPSHOT,
                        SIVerifier.PruningMode.PRUN)));
    }
}
