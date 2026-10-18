package fr.nbank.reconciliation.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RecordParsers {

    private static final List<String> HEADER = List.of(
            "source_id", "account_id", "booking_date", "value_date", "currency", "amount", "direction", "reference");

    private final ObjectMapper json;

    public RecordParsers(ObjectMapper json) {
        this.json = json;
    }

    public ParsedFile parse(String contentType, String body) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (body != null && body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }
        String trimmed = body == null ? "" : body.stripLeading();
        if (type.contains("spreadsheet") || type.contains("xlsx") || type.contains("xml") || type.contains("fixed")) {
            return ParsedFile.error("Profil non activé pour ce format. Le connecteur doit être validé sans modifier le moteur.");
        }
        if (type.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJson(body);
        }
        return parseCsv(body, delimiterOf(body));
    }

    public ParsedFile parseCsv(String body, char delimiter) {
        if (body == null || body.isBlank()) {
            return ParsedFile.error("Fichier vide");
        }
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) {
            start++;
        }
        if (start >= lines.length) {
            return ParsedFile.error("Fichier vide");
        }
        List<String> header = split(lines[start], delimiter).stream()
                .map(cell -> cell.toLowerCase(Locale.ROOT))
                .toList();
        if (!header.equals(HEADER) && !(header.size() == 9 && header.subList(0, 8).equals(HEADER) && header.get(8).equals("reversal_of"))) {
            return ParsedFile.error("En-tête CSV invalide. Colonnes attendues : " + String.join(String.valueOf(delimiter), HEADER));
        }
        boolean reversal = header.size() == 9;
        List<ParsedFile.ParsedRow> rows = new ArrayList<>();
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            rows.add(readColumns(i + 1, split(lines[i], delimiter), reversal));
        }
        if (rows.isEmpty()) {
            return ParsedFile.error("Aucune ligne de données");
        }
        return new ParsedFile(null, rows);
    }

    private ParsedFile parseJson(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode array = root.isArray() ? root : root.get("records");
            if (array == null || !array.isArray() || array.isEmpty()) {
                return ParsedFile.error("JSON invalide : tableau records attendu");
            }
            List<ParsedFile.ParsedRow> rows = new ArrayList<>();
            int line = 1;
            for (JsonNode node : array) {
                rows.add(readJson(line++, node));
            }
            return new ParsedFile(null, rows);
        } catch (Exception ex) {
            return ParsedFile.error("JSON illisible");
        }
    }

    private ParsedFile.ParsedRow readJson(int line, JsonNode node) {
        return build(
                line,
                text(node, "source_id", "sourceRecordId"),
                text(node, "account_id", "accountCode"),
                text(node, "booking_date", "bookingDate"),
                text(node, "value_date", "valueDate"),
                text(node, "currency", "currency"),
                text(node, "amount", "amount"),
                text(node, "direction", "direction"),
                text(node, "reference", "reference"),
                text(node, "reversal_of", "reversalOf"));
    }

    private ParsedFile.ParsedRow readColumns(int line, List<String> columns, boolean reversal) {
        if (columns.size() < 8) {
            return reject(line, "Nombre de colonnes incorrect");
        }
        return build(
                line,
                columns.get(0),
                columns.get(1),
                columns.get(2),
                columns.get(3),
                columns.get(4),
                columns.get(5),
                columns.get(6),
                columns.get(7),
                reversal && columns.size() > 8 ? columns.get(8) : null);
    }

    private ParsedFile.ParsedRow build(
            int line, String sourceId, String account, String booking, String value, String currency,
            String amount, String direction, String reference, String reversalOf) {
        if (blank(sourceId) || blank(account) || blank(booking) || blank(value) || blank(currency) || blank(amount) || blank(direction) || blank(reference)) {
            return reject(line, "Champ obligatoire manquant");
        }
        LocalDate bookingDate;
        LocalDate valueDate;
        try {
            bookingDate = LocalDate.parse(booking.trim());
            valueDate = LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return reject(line, "Date invalide, format ISO-8601 attendu");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amount.trim());
        } catch (NumberFormatException ex) {
            return reject(line, "Montant invalide");
        }
        if (parsed.scale() > 6 || parsed.signum() < 0) {
            return reject(line, "Montant hors contrat NUMERIC(20,6) positif ou nul");
        }
        String dir = direction.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DEBIT", "CREDIT").contains(dir)) {
            return reject(line, "Sens attendu DEBIT ou CREDIT");
        }
        String iso = currency.trim().toUpperCase(Locale.ROOT);
        if (!iso.matches("[A-Z]{3}")) {
            return reject(line, "Devise ISO 4217 attendue");
        }
        return new ParsedFile.ParsedRow(
                line, sourceId.trim(), account.trim(), bookingDate, valueDate, iso, parsed, dir,
                reference.trim(), blank(reversalOf) ? null : reversalOf.trim(), null);
    }

    private static ParsedFile.ParsedRow reject(int line, String reason) {
        return new ParsedFile.ParsedRow(line, null, null, null, null, null, null, null, null, null, reason);
    }

    private static String text(JsonNode node, String snake, String camel) {
        JsonNode value = node.get(snake);
        if (value == null || value.isNull()) {
            value = node.get(camel);
        }
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static char delimiterOf(String body) {
        if (body == null) {
            return ';';
        }
        String first = body.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        long commas = first.chars().filter(ch -> ch == ',').count();
        long semicolons = first.chars().filter(ch -> ch == ';').count();
        return commas > semicolons ? ',' : ';';
    }

    private static List<String> split(String line, char delimiter) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == delimiter) {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString().trim());
        return columns;
    }
}
