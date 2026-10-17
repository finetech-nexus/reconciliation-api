package fr.nbank.reconciliation;

import fr.nbank.reconciliation.engine.ReconciliationEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationEngineTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();
    private final LocalDate day = LocalDate.of(2026, 10, 16);

    @Test
    void matchesExactGroupedAmbiguousReversalAndBreaks() {
        var external = List.of(
                row("TX-1", "25000.000", "REF-001", day, null),
                row("TX-2", "12000.000", "REF-002", day, null),
                row("TX-3", "8000.000", "REF-005", day, null),
                row("TX-4", "5000.000", "REF-AMB", day, null),
                row("TX-5", "700.000", "REF-NONE", day, null),
                row("TX-6", "25000.000", "REV-REF-009", day, null));
        var accounting = List.of(
                row("GL-1", "25000.000", "REF-001", day, null),
                row("GL-2", "11999.000", "REF-002", day, null),
                row("GL-3", "3000.000", "REF-005", day, null),
                row("GL-4", "5000.000", "REF-005", day, null),
                row("GL-5", "5000.000", "REF-AMB", day, null),
                row("GL-6", "5000.000", "REF-AMB", day, null),
                row("GL-7", "1500.000", "REF-ORPHAN", day, null),
                row("GL-8", "25000.000", "REF-009", day, null),
                row("GL-9", "10.000", "REF-OLD", day.minusDays(10), null));

        var result = engine.reconcile(external, accounting, 2, true);

        assertEquals(1, result.matches().stream().filter(match -> "EXACT".equals(match.type())).count());
        assertEquals(1, result.matches().stream().filter(match -> "GROUPED".equals(match.type())).count());
        assertEquals(1, result.matches().stream().filter(match -> "REVERSAL".equals(match.type())).count());
        assertTrue(result.matches().stream().filter(match -> "GROUPED".equals(match.type()))
                .allMatch(match -> match.accounting().size() == 2));
        assertEquals(1, result.breaks().stream().filter(item -> "AMOUNT_MISMATCH".equals(item.type())).count());
        assertEquals(1, result.breaks().stream().filter(item -> "AMBIGUOUS_MATCH".equals(item.type())).count());
        assertEquals(1, result.breaks().stream().filter(item -> "UNMATCHED_TRANSACTION".equals(item.type())).count());
        assertEquals(2, result.breaks().stream().filter(item -> "UNMATCHED_ENTRY".equals(item.type())).count());
        assertEquals(external.size(), result.matches().size()
                + result.breaks().stream().filter(item -> item.externalId() != null).count());
    }

    @Test
    void keepsHistoricalResultWhenCalledAgain() {
        var external = List.of(row("TX-1", "10.000", "REF-1", day, null));
        var accounting = List.of(row("GL-1", "10.000", "REF-1", day, null));
        var first = engine.reconcile(external, accounting, 2, true);
        var second = engine.reconcile(
                List.of(row("TX-2", "4.000", "REF-2", day, null)),
                List.of(row("GL-2", "4.000", "REF-2", day, null)),
                2,
                false);
        assertEquals(1, first.matches().size());
        assertEquals("EXACT", first.matches().get(0).type());
        assertEquals(1, second.matches().size());
        assertEquals("REF-2", second.matches().get(0).reference());
    }

    private static ReconciliationEngine.Row row(String id, String amount, String reference, LocalDate date, String reversalOf) {
        return new ReconciliationEngine.Row(UUID.randomUUID(), id, date, new BigDecimal(amount), reference, reversalOf);
    }
}
