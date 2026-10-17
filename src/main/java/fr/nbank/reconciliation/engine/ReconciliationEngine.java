package fr.nbank.reconciliation.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moteur de rapprochement indépendant des connecteurs.
 * La recherche s'appuie sur la référence métier, pas sur un produit cartésien.
 * Convention de montant v1 : valeur absolue en NUMERIC. Le sens débit/crédit est
 * conservé sur l'écriture et n'entre pas dans la clé tant que la direction
 * financière n'a pas figé la convention de signe (décision ouverte de la spec).
 */
@Component
public class ReconciliationEngine {

    public record Row(
            UUID id,
            String sourceRecordId,
            LocalDate bookingDate,
            BigDecimal amount,
            String reference,
            String reversalOf) {
    }

    public record Match(String type, BigDecimal amount, String reference, Row external, List<Row> accounting) {
    }

    public record Break(
            String type,
            String priority,
            BigDecimal amount,
            String reference,
            UUID externalId,
            UUID accountingId,
            String detail) {
    }

    public record Result(
            List<Match> matches,
            List<Break> breaks,
            BigDecimal externalTotal,
            BigDecimal accountingTotal,
            BigDecimal matchedTotal,
            BigDecimal difference) {
    }

    public Result reconcile(List<Row> external, List<Row> accounting, int dateWindowDays, boolean allowGrouped) {
        List<Row> externals = new ArrayList<>(external);
        List<Row> books = new ArrayList<>(accounting);
        externals.sort(Comparator.comparing(Row::sourceRecordId));
        books.sort(Comparator.comparing(Row::sourceRecordId));

        List<Match> matches = new ArrayList<>();
        List<Break> breaks = new ArrayList<>();
        Map<String, List<Row>> glByRef = index(books);

        List<Row> remainingExternal = new ArrayList<>();
        for (Row movement : externals) {
            String origin = reversalOrigin(movement);
            if (origin == null) {
                remainingExternal.add(movement);
                continue;
            }
            List<Row> candidates = inWindow(glByRef.getOrDefault(origin, List.of()), movement.bookingDate(), dateWindowDays);
            List<Row> exact = candidates.stream().filter(row -> same(row.amount(), movement.amount())).toList();
            if (exact.size() == 1) {
                Row gl = exact.get(0);
                matches.add(new Match("REVERSAL", movement.amount(), movement.reference(), movement, List.of(gl)));
                remove(glByRef, origin, gl);
            } else if (exact.size() > 1) {
                breaks.add(ambiguous(movement, exact));
                exact.forEach(gl -> remove(glByRef, origin, gl));
            } else {
                remainingExternal.add(movement);
            }
        }

        Map<String, List<Row>> byReference = new LinkedHashMap<>();
        for (Row movement : remainingExternal) {
            byReference.computeIfAbsent(normalize(movement.reference()), key -> new ArrayList<>()).add(movement);
        }

        for (var entry : byReference.entrySet()) {
            String reference = entry.getKey();
            List<Row> group = entry.getValue();
            if (reference.isBlank()) {
                for (Row movement : group) {
                    breaks.add(unmatchedExternal(movement, "Référence absente"));
                }
                continue;
            }
            List<Row> glGroup = new ArrayList<>(glByRef.getOrDefault(reference, List.of()));
            if (group.size() == 1) {
                matchOne(group.get(0), glGroup, dateWindowDays, allowGrouped, matches, breaks, glByRef);
            } else if (glGroup.isEmpty()) {
                for (Row movement : group) {
                    breaks.add(unmatchedExternal(movement, "Aucun candidat dans la fenêtre"));
                }
            } else {
                List<Row> still = new ArrayList<>(glGroup);
                for (Row movement : group) {
                    List<Row> scoped = inWindow(still, movement.bookingDate(), dateWindowDays);
                    if (scoped.isEmpty()) {
                        breaks.add(unmatchedExternal(movement, "Aucun candidat dans la fenêtre"));
                    } else {
                        breaks.add(ambiguous(movement, scoped));
                        scoped.forEach(gl -> remove(glByRef, reference, gl));
                        still.removeAll(scoped);
                    }
                }
            }
        }

        for (List<Row> leftover : glByRef.values()) {
            for (Row gl : leftover) {
                breaks.add(new Break(
                        "UNMATCHED_ENTRY",
                        "MEDIUM",
                        gl.amount(),
                        gl.reference(),
                        null,
                        gl.id(),
                        "Écriture comptable sans mouvement externe"));
            }
        }

        BigDecimal externalTotal = sum(externals);
        BigDecimal accountingTotal = sum(books);
        BigDecimal matchedTotal = matches.stream().map(Match::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int explained = matches.size();
        for (Break item : breaks) {
            if (item.externalId() != null) {
                explained++;
            }
        }
        if (explained != externals.size()) {
            throw new IllegalStateException("Intégrité du lot de rapprochement non vérifiée");
        }
        return new Result(
                List.copyOf(matches),
                List.copyOf(breaks),
                externalTotal,
                accountingTotal,
                matchedTotal,
                externalTotal.subtract(accountingTotal));
    }

    private void matchOne(
            Row movement,
            List<Row> glGroup,
            int dateWindowDays,
            boolean allowGrouped,
            List<Match> matches,
            List<Break> breaks,
            Map<String, List<Row>> glByRef) {
        String reference = normalize(movement.reference());
        List<Row> scoped = inWindow(glGroup, movement.bookingDate(), dateWindowDays);
        List<Row> exact = scoped.stream().filter(row -> same(row.amount(), movement.amount())).toList();
        if (exact.size() == 1 && scoped.size() == 1) {
            matches.add(new Match("EXACT", movement.amount(), movement.reference(), movement, exact));
            remove(glByRef, reference, exact.get(0));
            return;
        }
        if (exact.size() > 1) {
            breaks.add(ambiguous(movement, exact));
            exact.forEach(gl -> remove(glByRef, reference, gl));
            return;
        }
        if (scoped.size() > 1 && sum(scoped).compareTo(movement.amount()) == 0) {
            if (allowGrouped) {
                matches.add(new Match("GROUPED", movement.amount(), movement.reference(), movement, List.copyOf(scoped)));
            } else {
                breaks.add(ambiguous(movement, scoped));
            }
            scoped.forEach(gl -> remove(glByRef, reference, gl));
            return;
        }
        if (!scoped.isEmpty()) {
            breaks.add(new Break(
                    "AMOUNT_MISMATCH",
                    "HIGH",
                    movement.amount(),
                    movement.reference(),
                    movement.id(),
                    scoped.get(0).id(),
                    "Même référence, montant différent"));
            scoped.forEach(gl -> remove(glByRef, reference, gl));
            return;
        }
        breaks.add(unmatchedExternal(movement, "Aucun candidat dans la fenêtre"));
    }

    private static Break ambiguous(Row movement, List<Row> candidates) {
        return new Break(
                "AMBIGUOUS_MATCH",
                "HIGH",
                movement.amount(),
                movement.reference(),
                movement.id(),
                candidates.isEmpty() ? null : candidates.get(0).id(),
                "Plusieurs candidats (" + candidates.size() + "), validation manuelle");
    }

    private static Break unmatchedExternal(Row movement, String detail) {
        return new Break(
                "UNMATCHED_TRANSACTION",
                "MEDIUM",
                movement.amount(),
                movement.reference(),
                movement.id(),
                null,
                detail);
    }

    private static Map<String, List<Row>> index(List<Row> rows) {
        Map<String, List<Row>> index = new LinkedHashMap<>();
        for (Row row : rows) {
            index.computeIfAbsent(normalize(row.reference()), key -> new ArrayList<>()).add(row);
        }
        return index;
    }

    private static List<Row> inWindow(List<Row> rows, LocalDate anchor, int window) {
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            long days = Math.abs(ChronoUnit.DAYS.between(anchor, row.bookingDate()));
            if (days <= window) {
                kept.add(row);
            }
        }
        return kept;
    }

    private static void remove(Map<String, List<Row>> index, String reference, Row row) {
        List<Row> bucket = index.get(reference);
        if (bucket != null) {
            bucket.remove(row);
        }
    }

    private static String reversalOrigin(Row row) {
        if (row.reversalOf() != null && !row.reversalOf().isBlank()) {
            return normalize(row.reversalOf());
        }
        String reference = normalize(row.reference());
        if (reference.startsWith("REV-") && reference.length() > 4) {
            return reference.substring(4);
        }
        return null;
    }

    static String normalize(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase();
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private static BigDecimal sum(List<Row> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (Row row : rows) {
            total = total.add(row.amount());
        }
        return total;
    }
}
