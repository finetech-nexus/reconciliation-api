package fr.nbank.reconciliation.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliaStore {

    public static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final JdbcTemplate jdbc;

    public ReconciliaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Rows.SourceRow> findSource(String code) {
        List<Rows.SourceRow> rows = jdbc.query(
                "SELECT id, code, name FROM source_system WHERE code = ? AND active = TRUE",
                (rs, i) -> new Rows.SourceRow(uuid(rs, "id"), rs.getString("code"), rs.getString("name")),
                code);
        return rows.stream().findFirst();
    }

    public Optional<Rows.AccountRow> findAccount(String code) {
        List<Rows.AccountRow> rows = jdbc.query(
                """
                SELECT id, account_code, name, currency, correspondent
                FROM bank_account WHERE account_code = ? AND active = TRUE
                """,
                ACCOUNT,
                code);
        return rows.stream().findFirst();
    }

    public List<Rows.AccountRow> listAccounts(List<UUID> scope) {
        if (scope.isEmpty()) {
            return jdbc.query(
                    "SELECT id, account_code, name, currency, correspondent FROM bank_account WHERE active = TRUE ORDER BY account_code",
                    ACCOUNT);
        }
        String marks = marks(scope.size());
        return jdbc.query(
                "SELECT id, account_code, name, currency, correspondent FROM bank_account WHERE active = TRUE AND id IN ("
                        + marks + ") ORDER BY account_code",
                ACCOUNT,
                scope.toArray());
    }

    public boolean unrestricted(UUID actorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actor_account_scope WHERE actor_id = ?",
                Integer.class,
                actorId);
        return count == null || count == 0;
    }

    public boolean inScope(UUID actorId, UUID accountId) {
        if (unrestricted(actorId)) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actor_account_scope WHERE actor_id = ? AND account_id = ?",
                Integer.class,
                actorId,
                accountId);
        return count != null && count > 0;
    }

    public List<UUID> scopeIds(UUID actorId) {
        if (unrestricted(actorId)) {
            return List.of();
        }
        return jdbc.query(
                "SELECT account_id FROM actor_account_scope WHERE actor_id = ?",
                (rs, i) -> uuid(rs, "account_id"),
                actorId);
    }

    public Optional<Rows.RuleRow> findRule(String version) {
        List<Rows.RuleRow> rows = jdbc.query(
                """
                SELECT id, version, priority, date_window_days, allow_grouped, direction_mode
                FROM reconciliation_rule WHERE version = ?
                """,
                RULE,
                version);
        return rows.stream().findFirst();
    }

    public Optional<Rows.RuleRow> latestRule() {
        List<Rows.RuleRow> rows = jdbc.query(
                """
                SELECT id, version, priority, date_window_days, allow_grouped, direction_mode
                FROM reconciliation_rule ORDER BY priority ASC, created_at DESC LIMIT 1
                """,
                RULE);
        return rows.stream().findFirst();
    }

    public List<Rows.RuleRow> listRules() {
        return jdbc.query(
                """
                SELECT id, version, priority, date_window_days, allow_grouped, direction_mode
                FROM reconciliation_rule ORDER BY priority ASC, version ASC
                """,
                RULE);
    }

    public void insertRule(UUID id, String version, int priority, int window, boolean grouped, String directionMode) {
        jdbc.update(
                """
                INSERT INTO reconciliation_rule (id, version, priority, date_window_days, allow_grouped, direction_mode, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                id, version, priority, window, grouped, directionMode);
    }

    public String nextPublicId(String sequence, String prefix, LocalDate date) {
        Integer current = jdbc.queryForObject(
                "SELECT last_value FROM id_sequence WHERE name = ? FOR UPDATE",
                Integer.class,
                sequence);
        if (current == null) {
            throw new IllegalStateException("Séquence absente: " + sequence);
        }
        int next = current + 1;
        jdbc.update("UPDATE id_sequence SET last_value = ? WHERE name = ?", next, sequence);
        return prefix + "-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", next);
    }

    public void insertBatch(
            UUID id,
            String publicId,
            UUID sourceSystemId,
            String sourceType,
            LocalDate businessDate,
            String sourceBatchId,
            String currency,
            Integer expected,
            String correlationId,
            String metadata) {
        jdbc.update(
                """
                INSERT INTO import_batch (
                    id, public_id, source_system_id, source_type, business_date, source_batch_id,
                    currency, expected_record_count, status, correlation_id, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?)
                """,
                id, publicId, sourceSystemId, sourceType, businessDate, sourceBatchId, currency, expected, correlationId, metadata);
    }

    public Optional<Rows.BatchRow> findBatch(String publicId) {
        return one(jdbc.query(BATCH_SQL + " WHERE b.public_id = ?", BATCH, publicId));
    }

    public Optional<Rows.BatchRow> findBatchBySource(UUID sourceSystemId, String sourceBatchId) {
        return one(jdbc.query(
                BATCH_SQL + " WHERE b.source_system_id = ? AND b.source_batch_id = ?",
                BATCH,
                sourceSystemId,
                sourceBatchId));
    }

    public Optional<String> findPublicIdByChecksum(String checksum) {
        List<String> rows = jdbc.query(
                "SELECT public_id FROM import_batch WHERE checksum = ?",
                (rs, i) -> rs.getString("public_id"),
                checksum);
        return rows.stream().findFirst();
    }

    public void updateBatch(
            UUID id,
            String status,
            int accepted,
            int rejected,
            int quarantined,
            BigDecimal acceptedAmount,
            BigDecimal controlTotal,
            String checksum) {
        jdbc.update(
                """
                UPDATE import_batch
                SET status = ?, accepted_records = ?, rejected_records = ?, quarantined_records = ?,
                    accepted_amount = ?, control_total = COALESCE(?, control_total), checksum = COALESCE(?, checksum)
                WHERE id = ?
                """,
                status, accepted, rejected, quarantined, acceptedAmount, controlTotal, checksum, id);
    }

    public void clearStaged(UUID batchId) {
        jdbc.update("DELETE FROM import_record WHERE batch_id = ? AND status = 'STAGED'", batchId);
    }

    public void insertStage(Rows.StageRow row, UUID batchId) {
        jdbc.update(
                """
                INSERT INTO import_record (
                    id, batch_id, source_record_id, account_code, booking_date, value_date, currency,
                    amount, direction, reference, reversal_of, status, reject_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), batchId, row.sourceRecordId(), row.accountCode(), row.bookingDate(), row.valueDate(),
                row.currency(), row.amount(), row.direction(), row.reference(), row.reversalOf(), row.status(), row.rejectReason());
    }

    public List<Rows.StageRow> listStaged(UUID batchId) {
        return jdbc.query(
                """
                SELECT id, source_record_id, account_code, booking_date, value_date, currency, amount,
                       direction, reference, reversal_of, status, reject_reason
                FROM import_record WHERE batch_id = ? AND status = 'STAGED' ORDER BY source_record_id
                """,
                STAGE,
                batchId);
    }

    public void updateStageStatus(UUID id, String status, String reason) {
        jdbc.update("UPDATE import_record SET status = ?, reject_reason = ? WHERE id = ?", status, reason, id);
    }

    public Rows.Slice<Rows.StageRow> pageRecords(UUID batchId, int page, int size) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM import_record WHERE batch_id = ?", Integer.class, batchId);
        List<Rows.StageRow> content = jdbc.query(
                """
                SELECT id, source_record_id, account_code, booking_date, value_date, currency, amount,
                       direction, reference, reversal_of, status, reject_reason
                FROM import_record WHERE batch_id = ? ORDER BY source_record_id LIMIT ? OFFSET ?
                """,
                STAGE,
                batchId, size, page * size);
        return new Rows.Slice<>(content, total == null ? 0 : total);
    }

    public Rows.Slice<Rows.BatchRow> pageBatches(List<UUID> scope, int page, int size) {
        String filter = scope.isEmpty() ? "" : " AND EXISTS (SELECT 1 FROM import_record r JOIN bank_account a ON a.account_code = r.account_code WHERE r.batch_id = b.id AND a.id IN (" + marks(scope.size()) + "))";
        Object[] args = scope.toArray();
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch b WHERE 1 = 1" + filter, Integer.class, args);
        List<Object> pageArgs = new ArrayList<>(scope);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Rows.BatchRow> content = jdbc.query(
                BATCH_SQL + " WHERE 1 = 1" + filter + " ORDER BY b.created_at DESC LIMIT ? OFFSET ?",
                BATCH,
                pageArgs.toArray());
        return new Rows.Slice<>(content, total == null ? 0 : total);
    }

    public boolean accountingExists(UUID sourceSystemId, String sourceRecordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounting_entry WHERE source_system_id = ? AND source_entry_id = ?",
                Integer.class, sourceSystemId, sourceRecordId);
        return count != null && count > 0;
    }

    public boolean externalExists(UUID sourceSystemId, String sourceRecordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_transaction WHERE source_system_id = ? AND external_transaction_id = ?",
                Integer.class, sourceSystemId, sourceRecordId);
        return count != null && count > 0;
    }

    public void insertAccounting(
            UUID id, UUID sourceSystemId, String sourceRecordId, UUID batchId, UUID accountId,
            LocalDate bookingDate, LocalDate valueDate, String currency, BigDecimal amount,
            String direction, String reference, String reversalOf) {
        jdbc.update(
                """
                INSERT INTO accounting_entry (
                    id, source_system_id, source_entry_id, batch_id, account_id, booking_date, value_date,
                    currency, amount, direction, reference, reversal_of)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, sourceSystemId, sourceRecordId, batchId, accountId, bookingDate, valueDate,
                currency, amount, direction, reference, reversalOf);
    }

    public void insertExternal(
            UUID id, UUID sourceSystemId, String sourceRecordId, UUID batchId, UUID accountId,
            LocalDate bookingDate, LocalDate valueDate, String currency, BigDecimal amount,
            String direction, String reference, String reversalOf) {
        jdbc.update(
                """
                INSERT INTO external_transaction (
                    id, source_system_id, external_transaction_id, batch_id, account_id, booking_date, value_date,
                    currency, amount, direction, reference, reversal_of)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, sourceSystemId, sourceRecordId, batchId, accountId, bookingDate, valueDate,
                currency, amount, direction, reference, reversalOf);
    }

    public List<Rows.CanonicalRow> loadAccounting(UUID accountId, String currency, LocalDate from, LocalDate to, boolean includeMatched) {
        return load("accounting_entry", "source_entry_id", accountId, currency, from, to, includeMatched);
    }

    public List<Rows.CanonicalRow> loadExternal(UUID accountId, String currency, LocalDate from, LocalDate to, boolean includeMatched) {
        return load("external_transaction", "external_transaction_id", accountId, currency, from, to, includeMatched);
    }

    private List<Rows.CanonicalRow> load(String table, String idColumn, UUID accountId, String currency, LocalDate from, LocalDate to, boolean includeMatched) {
        String sql = "SELECT id, " + idColumn + " AS source_record_id, booking_date, amount, reference, reversal_of FROM "
                + table + " WHERE account_id = ? AND TRIM(currency) = ? AND booking_date BETWEEN ? AND ?";
        if (!includeMatched) {
            sql += " AND open_for_match = TRUE";
        }
        return jdbc.query(sql, CANONICAL, accountId, currency, from, to);
    }

    public void setOpen(String table, UUID id, boolean open) {
        if (!table.equals("accounting_entry") && !table.equals("external_transaction")) {
            throw new IllegalArgumentException(table);
        }
        jdbc.update("UPDATE " + table + " SET open_for_match = ? WHERE id = ?", open, id);
    }

    public void supersede(UUID accountId, LocalDate from, LocalDate to, UUID keepId) {
        jdbc.update(
                """
                UPDATE reconciliation_run
                SET status = 'SUPERSEDED'
                WHERE account_id = ? AND period_from = ? AND period_to = ? AND status = 'COMPLETED' AND id <> ?
                """,
                accountId, from, to, keepId);
    }

    public void insertRun(
            UUID id, String publicId, UUID accountId, String currency, LocalDate from, LocalDate to,
            UUID ruleId, String ruleVersion, String status, boolean replay, BigDecimal externalTotal,
            BigDecimal accountingTotal, BigDecimal matchedTotal, BigDecimal difference, int matchedCount,
            int exceptionCount, String timings, String correlationId) {
        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    id, public_id, account_id, currency, period_from, period_to, rule_id, rule_version, status,
                    replay, external_total, accounting_total, matched_total, difference, matched_count,
                    exception_count, timings, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, publicId, accountId, currency, from, to, ruleId, ruleVersion, status, replay,
                externalTotal, accountingTotal, matchedTotal, difference, matchedCount, exceptionCount, timings, correlationId);
    }

    public void insertMatch(UUID id, UUID runId, String type, BigDecimal amount, String currency, String reference) {
        jdbc.update(
                """
                INSERT INTO reconciliation_match (id, run_id, match_type, amount, currency, reference)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, runId, type, amount, currency, reference);
    }

    public void insertMatchItem(UUID matchId, UUID recordId, String side, BigDecimal amount, String sourceRecordId) {
        jdbc.update(
                """
                INSERT INTO reconciliation_match_item (match_id, record_id, record_side, amount, source_record_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                matchId, recordId, side, amount, sourceRecordId);
    }

    public boolean hasOpenException(UUID recordId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM reconciliation_exception
                WHERE status IN ('OPEN', 'PENDING_APPROVAL', 'REOPENED')
                  AND (external_transaction_id = ? OR accounting_entry_id = ?)
                """,
                Integer.class, recordId, recordId);
        return count != null && count > 0;
    }

    public List<UUID> openExceptionIds(UUID recordId) {
        return jdbc.query(
                """
                SELECT id FROM reconciliation_exception
                WHERE status IN ('OPEN', 'PENDING_APPROVAL', 'REOPENED')
                  AND (external_transaction_id = ? OR accounting_entry_id = ?)
                """,
                (rs, i) -> uuid(rs, "id"),
                recordId, recordId);
    }

    public void systemResolve(UUID exceptionId, String comment) {
        jdbc.update(
                """
                UPDATE reconciliation_exception
                SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                exceptionId);
        jdbc.update(
                """
                INSERT INTO exception_action (id, exception_id, actor_id, action_type, comment)
                VALUES (?, ?, ?, 'SYSTEM_MATCHED', ?)
                """,
                UUID.randomUUID(), exceptionId, SYSTEM_ACTOR, clip(comment));
    }

    public void insertException(
            UUID id, String publicId, UUID runId, UUID accountId, UUID externalId, UUID accountingId,
            String type, BigDecimal amount, String currency, String reference, String priority, String detail,
            OffsetDateTime detectedAt, OffsetDateTime dueAt) {
        jdbc.update(
                """
                INSERT INTO reconciliation_exception (
                    id, public_id, run_id, account_id, external_transaction_id, accounting_entry_id,
                    exception_type, amount, currency, reference, status, priority, detail, detected_at, due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
                """,
                id, publicId, runId, accountId, externalId, accountingId, type, amount, currency, reference,
                priority, clip(detail), detectedAt, dueAt);
    }

    public Optional<Rows.RunRow> findRun(String publicId) {
        return one(jdbc.query(RUN_SQL + " WHERE r.public_id = ?", RUN, publicId));
    }

    public List<Rows.MatchRow> listMatches(UUID runId) {
        return jdbc.query(
                "SELECT id, match_type, amount, reference FROM reconciliation_match WHERE run_id = ? ORDER BY reference",
                (rs, i) -> new Rows.MatchRow(uuid(rs, "id"), rs.getString("match_type"), rs.getBigDecimal("amount"), rs.getString("reference")),
                runId);
    }

    public List<Rows.MatchItemRow> listMatchItems(UUID runId) {
        return jdbc.query(
                """
                SELECT i.match_id, i.record_side, i.amount, i.source_record_id
                FROM reconciliation_match_item i
                JOIN reconciliation_match m ON m.id = i.match_id
                WHERE m.run_id = ?
                ORDER BY i.source_record_id
                """,
                (rs, n) -> new Rows.MatchItemRow(uuid(rs, "match_id"), rs.getString("record_side"), rs.getBigDecimal("amount"), rs.getString("source_record_id")),
                runId);
    }

    public Rows.Slice<Rows.RunRow> pageRuns(List<UUID> scope, int page, int size) {
        String filter = scope.isEmpty() ? "" : " AND r.account_id IN (" + marks(scope.size()) + ")";
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run r WHERE 1 = 1" + filter, Integer.class, scope.toArray());
        List<Object> args = new ArrayList<>(scope);
        args.add(size);
        args.add(page * size);
        List<Rows.RunRow> content = jdbc.query(
                RUN_SQL + " WHERE 1 = 1" + filter + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?",
                RUN,
                args.toArray());
        return new Rows.Slice<>(content, total == null ? 0 : total);
    }

    public Optional<Rows.ExceptionRow> findException(String publicId) {
        return one(jdbc.query(EX_SQL + " WHERE e.public_id = ?", EX, publicId));
    }

    public Rows.Slice<Rows.ExceptionRow> pageExceptions(List<UUID> scope, String status, int page, int size) {
        StringBuilder filter = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!scope.isEmpty()) {
            filter.append(" AND e.account_id IN (").append(marks(scope.size())).append(")");
            args.addAll(scope);
        }
        if (status != null && !status.isBlank()) {
            filter.append(" AND e.status = ?");
            args.add(status);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_exception e" + filter, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Rows.ExceptionRow> content = jdbc.query(
                EX_SQL + filter + " ORDER BY e.detected_at DESC LIMIT ? OFFSET ?",
                EX,
                pageArgs.toArray());
        return new Rows.Slice<>(content, total == null ? 0 : total);
    }

    public List<Rows.ActionRow> listActions(UUID exceptionId) {
        return jdbc.query(
                """
                SELECT a.id, a.actor_id, a.action_type, a.comment, a.created_at, p.decision
                FROM exception_action a
                LEFT JOIN approval p ON p.action_id = a.id
                WHERE a.exception_id = ?
                ORDER BY a.created_at
                """,
                (rs, i) -> new Rows.ActionRow(
                        uuid(rs, "id"),
                        uuid(rs, "actor_id"),
                        rs.getString("action_type"),
                        rs.getString("comment"),
                        time(rs, "created_at"),
                        rs.getString("decision")),
                exceptionId);
    }

    public UUID latestProposalActor(UUID exceptionId) {
        List<UUID> rows = jdbc.query(
                """
                SELECT actor_id FROM exception_action
                WHERE exception_id = ? AND action_type = 'PROPOSE'
                ORDER BY created_at DESC LIMIT 1
                """,
                (rs, i) -> uuid(rs, "actor_id"),
                exceptionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertAction(UUID id, UUID exceptionId, UUID actorId, String type, String comment) {
        jdbc.update(
                """
                INSERT INTO exception_action (id, exception_id, actor_id, action_type, comment)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, exceptionId, actorId, type, clip(comment));
    }

    public void insertApproval(UUID id, UUID actionId, UUID approverId, String decision, String comment) {
        jdbc.update(
                """
                INSERT INTO approval (id, action_id, approver_id, decision, comment)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, actionId, approverId, decision, clip(comment));
    }

    public void updateException(UUID id, String status, UUID ownerId, OffsetDateTime resolvedAt) {
        jdbc.update(
                """
                UPDATE reconciliation_exception
                SET status = ?, owner_id = COALESCE(?, owner_id), resolved_at = ?
                WHERE id = ?
                """,
                status, ownerId, resolvedAt, id);
    }

    public void audit(UUID id, UUID actorId, String action, String entityType, String entityId, String correlationId, String detail) {
        jdbc.update(
                """
                INSERT INTO audit_event (id, event_time, actor_id, action, entity_type, entity_id, correlation_id, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, OffsetDateTime.now(ZoneOffset.UTC), actorId, action, entityType, entityId, correlationId, clip(detail));
    }

    public Rows.Slice<Rows.AuditRow> pageAudit(int page, int size) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
        List<Rows.AuditRow> content = jdbc.query(
                """
                SELECT event_time, actor_id, action, entity_type, entity_id, correlation_id, detail
                FROM audit_event ORDER BY event_time DESC LIMIT ? OFFSET ?
                """,
                (rs, i) -> new Rows.AuditRow(
                        time(rs, "event_time"), uuid(rs, "actor_id"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("correlation_id"), rs.getString("detail")),
                size, page * size);
        return new Rows.Slice<>(content, total == null ? 0 : total);
    }

    public void insertAttachment(UUID id, UUID exceptionId, String filename, String contentType, String storageKey, String checksum, long byteSize, UUID actorId) {
        jdbc.update(
                """
                INSERT INTO attachment (id, exception_id, filename, content_type, storage_key, checksum, byte_size, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, exceptionId, filename, contentType, storageKey, checksum, byteSize, actorId);
    }

    public Optional<Rows.IdemRow> findIdem(String key) {
        List<Rows.IdemRow> rows = jdbc.query(
                "SELECT request_hash, http_status, response_body FROM idempotency_key WHERE idem_key = ?",
                (rs, i) -> new Rows.IdemRow(rs.getString("request_hash"), rs.getInt("http_status"), rs.getString("response_body")),
                key);
        return rows.stream().findFirst();
    }

    public void saveIdem(String key, String hash, int status, String body) {
        jdbc.update(
                "INSERT INTO idempotency_key (idem_key, request_hash, http_status, response_body) VALUES (?, ?, ?, ?)",
                key, hash, status, body);
    }

    public void insertWork(UUID id, String queue, String payload, String status) {
        jdbc.update(
                """
                INSERT INTO work_item (id, queue_name, payload, status, finished_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'DONE' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """,
                id, queue, payload, status, status);
    }

    public int countImports(LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE business_date BETWEEN ? AND ?",
                Integer.class, from, to);
        return count == null ? 0 : count;
    }

    public int countOpenExceptions() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_exception WHERE status IN ('OPEN', 'PENDING_APPROVAL', 'REOPENED')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public BigDecimal sumMatched(LocalDate from, LocalDate to) {
        BigDecimal value = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(matched_total), 0) FROM reconciliation_run
                WHERE status = 'COMPLETED' AND period_from >= ? AND period_to <= ?
                """,
                BigDecimal.class, from, to);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal sumOpenExceptions() {
        BigDecimal value = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0) FROM reconciliation_exception
                WHERE status IN ('OPEN', 'PENDING_APPROVAL', 'REOPENED')
                """,
                BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public int sumMatchedCount(LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(matched_count), 0) FROM reconciliation_run
                WHERE status = 'COMPLETED' AND period_from >= ? AND period_to <= ?
                """,
                Integer.class, from, to);
        return count == null ? 0 : count;
    }

    public int sumExceptionCount(LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(exception_count), 0) FROM reconciliation_run
                WHERE status = 'COMPLETED' AND period_from >= ? AND period_to <= ?
                """,
                Integer.class, from, to);
        return count == null ? 0 : count;
    }

    public Rows.CoreProfile coreProfile() {
        return jdbc.query(
                """
                SELECT connector_type, base_url, tenant, accounts_path, entries_path
                FROM core_profile ORDER BY updated_at DESC
                """,
                (rs, i) -> new Rows.CoreProfile(
                        rs.getString("connector_type"), rs.getString("base_url"), rs.getString("tenant"),
                        rs.getString("accounts_path"), rs.getString("entries_path")))
                .stream().findFirst()
                .orElse(new Rows.CoreProfile("FINERACT", "", "default", "", ""));
    }

    public void saveCore(String connectorType, String baseUrl, String tenant, String accountsPath, String entriesPath) {
        jdbc.update(
                """
                UPDATE core_profile
                SET connector_type = ?, base_url = ?, tenant = ?, accounts_path = ?, entries_path = ?, updated_at = CURRENT_TIMESTAMP
                """,
                connectorType, baseUrl, tenant.isBlank() ? "default" : tenant, accountsPath, entriesPath);
    }

    public Optional<Rows.CorrespondentRow> findCorrespondent(String code) {
        return jdbc.query(
                "SELECT code, name, bic, currency, account_code FROM correspondent WHERE code = ? AND active = TRUE",
                (rs, i) -> correspondent(rs),
                code).stream().findFirst();
    }

    public List<Rows.CorrespondentRow> listCorrespondents() {
        return jdbc.query(
                "SELECT code, name, bic, currency, account_code FROM correspondent WHERE active = TRUE ORDER BY code",
                (rs, i) -> correspondent(rs));
    }

    public void insertCorrespondent(UUID id, String code, String name, String bic, String currency, String accountCode) {
        jdbc.update(
                """
                INSERT INTO correspondent (id, code, name, bic, currency, account_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, code, name, bic, currency, accountCode);
    }

    public void insertAccount(UUID id, String code, String name, String currency, UUID sourceId, String correspondent) {
        jdbc.update(
                """
                INSERT INTO bank_account (id, account_code, name, currency, source_system_id, correspondent)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, code, name, currency, sourceId, correspondent);
    }

    public Optional<String> accountForGl(UUID sourceId, String glCode) {
        List<String> rows = jdbc.query(
                """
                SELECT a.account_code
                FROM gl_account_mapping m JOIN bank_account a ON a.id = m.bank_account_id
                WHERE m.source_system_id = ? AND m.source_gl_code = ?
                """,
                (rs, i) -> rs.getString("account_code"),
                sourceId, glCode);
        return rows.stream().findFirst();
    }

    public Optional<Rows.CursorRow> findCursor(UUID sourceId, String accountCode) {
        return jdbc.query(
                """
                SELECT s.code AS source_code, c.account_code, c.last_business_date, c.last_sequence,
                       c.last_batch_public_id, c.gap_detected
                FROM statement_cursor c JOIN source_system s ON s.id = c.source_system_id
                WHERE c.source_system_id = ? AND c.account_code = ?
                """,
                (rs, i) -> cursor(rs),
                sourceId, accountCode).stream().findFirst();
    }

    public List<Rows.CursorRow> listCursors() {
        return jdbc.query(
                """
                SELECT s.code AS source_code, c.account_code, c.last_business_date, c.last_sequence,
                       c.last_batch_public_id, c.gap_detected
                FROM statement_cursor c JOIN source_system s ON s.id = c.source_system_id
                ORDER BY s.code, c.account_code
                """,
                (rs, i) -> cursor(rs));
    }

    public void saveCursor(UUID sourceId, String accountCode, LocalDate businessDate, int sequence, String batchPublicId, boolean gap) {
        int updated = jdbc.update(
                """
                UPDATE statement_cursor
                SET last_business_date = ?, last_sequence = ?, last_batch_public_id = ?, gap_detected = ?, updated_at = CURRENT_TIMESTAMP
                WHERE source_system_id = ? AND account_code = ?
                """,
                businessDate, sequence, batchPublicId, gap, sourceId, accountCode);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO statement_cursor
                        (source_system_id, account_code, last_business_date, last_sequence, last_batch_public_id, gap_detected)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    sourceId, accountCode, businessDate, sequence, batchPublicId, gap);
        }
    }

    public void purgeOperational() {
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM exception_action");
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM reconciliation_exception");
        jdbc.update("DELETE FROM reconciliation_match_item");
        jdbc.update("DELETE FROM reconciliation_match");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM external_transaction");
        jdbc.update("DELETE FROM accounting_entry");
        jdbc.update("DELETE FROM import_record");
        jdbc.update("DELETE FROM import_batch");
        jdbc.update("DELETE FROM work_item");
        jdbc.update("DELETE FROM statement_cursor");
        jdbc.update("DELETE FROM idempotency_key");
    }

    private static Rows.CorrespondentRow correspondent(ResultSet rs) throws SQLException {
        return new Rows.CorrespondentRow(
                rs.getString("code"), rs.getString("name"), rs.getString("bic"),
                trim(rs.getString("currency")), rs.getString("account_code"));
    }

    private static Rows.CursorRow cursor(ResultSet rs) throws SQLException {
        return new Rows.CursorRow(
                rs.getString("source_code"), rs.getString("account_code"),
                rs.getObject("last_business_date", LocalDate.class), rs.getInt("last_sequence"),
                rs.getString("last_batch_public_id"), rs.getBoolean("gap_detected"));
    }

    private static final String BATCH_SQL = """
            SELECT b.id, b.public_id, b.source_system_id, s.code AS source_code, b.source_type, b.business_date,
                   b.source_batch_id, b.currency, b.expected_record_count, b.status, b.accepted_records,
                   b.rejected_records, b.quarantined_records, b.accepted_amount, b.control_total, b.checksum, b.correlation_id
            FROM import_batch b JOIN source_system s ON s.id = b.source_system_id
            """;

    private static final String RUN_SQL = """
            SELECT r.id, r.public_id, a.account_code, r.currency, r.period_from, r.period_to, r.rule_version, r.status,
                   r.replay, r.external_total, r.accounting_total, r.matched_total, r.difference, r.matched_count,
                   r.exception_count, r.timings, r.correlation_id, r.created_at
            FROM reconciliation_run r JOIN bank_account a ON a.id = r.account_id
            """;

    private static final String EX_SQL = """
            SELECT e.id, e.public_id, a.account_code, e.external_transaction_id, e.accounting_entry_id, e.exception_type,
                   e.amount, e.currency, e.reference, e.status, e.priority, e.owner_id, e.detail, e.detected_at, e.due_at,
                   e.resolved_at, r.public_id AS run_public_id
            FROM reconciliation_exception e
            JOIN bank_account a ON a.id = e.account_id
            LEFT JOIN reconciliation_run r ON r.id = e.run_id
            """;

    private static final RowMapper<Rows.AccountRow> ACCOUNT = (rs, i) -> new Rows.AccountRow(
            uuid(rs, "id"), rs.getString("account_code"), rs.getString("name"), trim(rs.getString("currency")), rs.getString("correspondent"));

    private static final RowMapper<Rows.RuleRow> RULE = (rs, i) -> new Rows.RuleRow(
            uuid(rs, "id"), rs.getString("version"), rs.getInt("priority"), rs.getInt("date_window_days"),
            rs.getBoolean("allow_grouped"), trim(rs.getString("direction_mode")));

    private static final RowMapper<Rows.BatchRow> BATCH = (rs, i) -> new Rows.BatchRow(
            uuid(rs, "id"), rs.getString("public_id"), uuid(rs, "source_system_id"), rs.getString("source_code"),
            rs.getString("source_type"), rs.getObject("business_date", LocalDate.class), rs.getString("source_batch_id"),
            trim(rs.getString("currency")), (Integer) rs.getObject("expected_record_count"), rs.getString("status"),
            rs.getInt("accepted_records"), rs.getInt("rejected_records"), rs.getInt("quarantined_records"),
            rs.getBigDecimal("accepted_amount"), rs.getBigDecimal("control_total"), rs.getString("checksum"),
            rs.getString("correlation_id"));

    private static final RowMapper<Rows.StageRow> STAGE = (rs, i) -> new Rows.StageRow(
            uuid(rs, "id"), rs.getString("source_record_id"), rs.getString("account_code"),
            rs.getObject("booking_date", LocalDate.class), rs.getObject("value_date", LocalDate.class),
            trim(rs.getString("currency")), rs.getBigDecimal("amount"), trim(rs.getString("direction")),
            rs.getString("reference"), rs.getString("reversal_of"), rs.getString("status"), rs.getString("reject_reason"));

    private static final RowMapper<Rows.CanonicalRow> CANONICAL = (rs, i) -> new Rows.CanonicalRow(
            uuid(rs, "id"), rs.getString("source_record_id"), rs.getObject("booking_date", LocalDate.class),
            rs.getBigDecimal("amount"), rs.getString("reference"), rs.getString("reversal_of"));

    private static final RowMapper<Rows.RunRow> RUN = (rs, i) -> new Rows.RunRow(
            uuid(rs, "id"), rs.getString("public_id"), rs.getString("account_code"), trim(rs.getString("currency")),
            rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
            rs.getString("rule_version"), rs.getString("status"), rs.getBoolean("replay"),
            rs.getBigDecimal("external_total"), rs.getBigDecimal("accounting_total"), rs.getBigDecimal("matched_total"),
            rs.getBigDecimal("difference"), rs.getInt("matched_count"), rs.getInt("exception_count"),
            rs.getString("timings"), rs.getString("correlation_id"), time(rs, "created_at"));

    private static final RowMapper<Rows.ExceptionRow> EX = (rs, i) -> new Rows.ExceptionRow(
            uuid(rs, "id"), rs.getString("public_id"), rs.getString("account_code"),
            uuid(rs, "external_transaction_id"), uuid(rs, "accounting_entry_id"), rs.getString("exception_type"),
            rs.getBigDecimal("amount"), trim(rs.getString("currency")), rs.getString("reference"), rs.getString("status"),
            rs.getString("priority"), uuid(rs, "owner_id"), rs.getString("detail"), time(rs, "detected_at"),
            time(rs, "due_at"), time(rs, "resolved_at"), rs.getString("run_public_id"));

    private static <T> Optional<T> one(List<T> rows) {
        return rows.stream().findFirst();
    }

    private static String marks(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID id) {
            return id;
        }
        return UUID.fromString(value.toString());
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
