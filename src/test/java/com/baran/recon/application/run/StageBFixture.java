package com.baran.recon.application.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.BatchKey;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.match.MatchItem;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.statement.LineSummary;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synthetic PSP and bank lines for the Stage B tests, written through the application's own stores
 * as recon_app, with matches and breaks on bank lines and batches built directly, as Stage B's rules
 * would write them. Each file is a file of its own, so a batch can span several. Every insert is
 * asserted to have written its row, so a key collision fails at the fixture (CLAUDE.md 7.3).
 */
final class StageBFixture {

    private static final Instant AT = Instant.parse("2026-10-12T08:00:00Z");

    private final StatementStore statements;
    private final MatchStore matches;
    private final BreakStore breaks;
    private final RunStore runs;
    private final Transactions transactions;

    StageBFixture(StatementStore statements, MatchStore matches, BreakStore breaks, RunStore runs,
                  Transactions transactions) {
        this.statements = statements;
        this.matches = matches;
        this.breaks = breaks;
        this.runs = runs;
        this.transactions = transactions;
    }

    /** PSP lines in one file of their own, stored in the order given. */
    void pspFile(SourceCode source, List<Psp> lines) {
        UUID fileId = UUID.randomUUID();
        List<PspLine> stored = lines.stream().map(line -> line.toLine(fileId, source)).toList();
        List<StatementStore.LineConflict> conflicts = transactions.inTransaction(() -> {
            statements.storeFile(file(fileId, source, lines.size()));
            return statements.storePspLinesIfAbsent(stored);
        });
        assertThat(conflicts).as("every PSP line stored").isEmpty();
    }

    /** Bank lines in one file of their own; returns each line's stored id by its line id. */
    Map<String, UUID> bankFile(SourceCode source, List<Bank> lines) {
        UUID fileId = UUID.randomUUID();
        List<BankLine> stored = lines.stream().map(line -> line.toLine(fileId, source)).toList();
        List<StatementStore.LineConflict> conflicts = transactions.inTransaction(() -> {
            statements.storeFile(file(fileId, source, lines.size()));
            return statements.storeBankLinesIfAbsent(stored);
        });
        assertThat(conflicts).as("every bank line stored").isEmpty();
        return stored.stream().collect(Collectors.toMap(BankLine::lineId, BankLine::id));
    }

    /** The batch as an item, by its PSP source and batch id. */
    static ItemRef batch(SourceCode psp, String batchId) {
        return new ItemRef(ItemSide.BATCH, new BatchKey(psp, batchId).itemId());
    }

    static ItemRef bank(UUID lineId) {
        return new ItemRef(ItemSide.BANK, lineId);
    }

    /** A finished run of the source, for a match to have been made by. */
    UUID completedRun(SourceCode source) {
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), source, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 9), Map.of(), AT, "system").complete(Map.of(), AT);
        transactions.inTransaction(() -> {
            runs.insert(run);
            return null;
        });
        return run.id();
    }

    /** An active B1 match of the batch and the bank line, made by the run. */
    void matchBatch(UUID runId, ItemRef batch, ItemRef bankLine, String currency, int batchLineCount) {
        matches.record(Match.activeBatch(UUID.randomUUID(), runId, RuleId.B1_BATCH_TOTAL, 1,
                Money.zero(CurrencyCode.of(currency)), AT,
                List.of(new MatchItem(batch.side(), batch.id()), new MatchItem(bankLine.side(), bankLine.id())),
                batchLineCount));
    }

    /** An OPEN break opened by the system, with no run. */
    void openBreak(BreakType type, ItemRef subject, List<ItemRef> related) {
        openBreak(type, subject, related, Optional.empty());
    }

    void openBreak(BreakType type, ItemRef subject, List<ItemRef> related, Optional<BreakDetails> details) {
        breaks.open(Break.open(UUID.randomUUID(), type, subject, related, details, Optional.empty(), Actor.SYSTEM,
                Optional.empty(), AT));
    }

    private static StatementFile file(UUID fileId, SourceCode source, int lines) {
        return new StatementFile(fileId, source, "STMT-" + fileId, sha256(), "statement.csv", 100,
                StatementFileStatus.INGESTED, new LineSummary(Optional.empty(), lines, 0, List.of(), 0, List.of()),
                "operator-001", AT);
    }

    private static String sha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A PSP line to store: a positive gross is a payment, a negative one a refund; net is gross less fee. */
    record Psp(String lineId, String batchId, long gross, long fee, String currency, LocalDate valueDate) {

        PspLine toLine(UUID fileId, SourceCode source) {
            CurrencyCode code = CurrencyCode.of(currency);
            return new PspLine(UUID.randomUUID(), fileId, source, lineId, Optional.empty(), batchId,
                    gross > 0 ? PspLineType.PAYMENT : PspLineType.REFUND, valueDate, valueDate, Money.of(gross, code),
                    Money.of(fee, code), Money.of(gross - fee, code));
        }
    }

    /** A bank line to store, naming the batch id extracted from its reference, if any. */
    record Bank(String lineId, long amount, String currency, LocalDate valueDate, Optional<String> batchId) {

        BankLine toLine(UUID fileId, SourceCode source) {
            return new BankLine(UUID.randomUUID(), fileId, source, lineId, valueDate, valueDate,
                    Money.of(amount, CurrencyCode.of(currency)), batchId.map(id -> "BATCH-" + id), batchId,
                    Optional.of("Settlement Test Merchant 001"));
        }
    }
}
