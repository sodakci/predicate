import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SIPruningCliTest {
    @Test
    void exposesCompactProductionCliAndPredicateBaseline() {
        var commandLine = new CommandLine(new Main());
        assertEquals(Set.of("audit"), commandLine.getSubcommands().keySet());

        var audit = commandLine.getSubcommands().get("audit");
        assertNull(audit.getCommandSpec().findOption("--ww-pruning"));

        assertNull(audit.getCommandSpec().findOption("--predicate-encoding"));
        var gmwr = audit.getCommandSpec().findOption("--gmwr");
        assertNotNull(gmwr);
        assertEquals(false, gmwr.hidden());
        assertEquals(true, gmwr.negatable());
        assertNull(audit.getCommandSpec().findOption("--solver-timeout-seconds"));
        assertThrows(CommandLine.ParameterException.class,
                () -> commandLine.parseArgs("audit", "--solver-timeout-seconds", "1", "history"));
        assertNotNull(audit.getCommandSpec().findOption("--solver-stats"));
        assertEquals(false, audit.getCommandSpec()
                .findOption("--gmwr-prepropagation").hidden());
        assertNull(audit.getCommandSpec().findOption("--predicate-witness-coalescing"));
        assertNull(audit.getCommandSpec().findOption("--graph-edge-interning"));

        assertNull(audit.getCommandSpec().findOption("--type"));
        assertNull(audit.getCommandSpec().findOption("--solver"));
        assertNull(audit.getCommandSpec().findOption("--pruning-mode"));
        assertNull(audit.getCommandSpec().findOption("--no-pruning"));
        assertNull(audit.getCommandSpec().findOption("--no-coalescing"));
        assertNull(audit.getCommandSpec().findOption("--dot-output"));
        assertNull(audit.getCommandSpec()
                .findOption("--compare-derived-predicate-edges"));
    }
}
