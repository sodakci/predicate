import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import verifier.SIVerifier;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SIPruningCliTest {
    @Test
    void exposesOneAuditCommandAndOnlyTheSupportedPruningModes() {
        var commandLine = new CommandLine(new Main());
        assertEquals(Set.of("audit"), commandLine.getSubcommands().keySet());

        var audit = commandLine.getSubcommands().get("audit");
        var option = audit.getCommandSpec().findOption("--ww-pruning");
        assertNotNull(option);
        assertEquals(SIVerifier.PruningMode.class, option.type());
        assertEquals(java.util.List.of(
                        SIVerifier.PruningMode.NONE,
                        SIVerifier.PruningMode.REACHABILITY),
                java.util.Arrays.asList(SIVerifier.PruningMode.values()));
        assertEquals(true, option.hidden());

        assertNotNull(audit.getCommandSpec().findOption("--solver-timeout-seconds"));
        assertNotNull(audit.getCommandSpec().findOption("--solver-stats"));
        assertEquals(true, audit.getCommandSpec()
                .findOption("--predicate-witness-coalescing").hidden());
        assertEquals(true, audit.getCommandSpec()
                .findOption("--graph-edge-interning").hidden());

        assertNull(audit.getCommandSpec().findOption("--type"));
        assertNull(audit.getCommandSpec().findOption("--solver"));
        assertNull(audit.getCommandSpec().findOption("--pruning-mode"));
        assertNull(audit.getCommandSpec().findOption("--no-pruning"));
        assertNull(audit.getCommandSpec().findOption("--no-coalescing"));
        assertNull(audit.getCommandSpec().findOption("--dot-output"));
        assertNull(audit.getCommandSpec()
                .findOption("--compare-derived-predicate-edges"));
        assertNull(audit.getCommandSpec().findOption("--predicate-encoding"));
    }
}
