package com.baran.recon.application.statement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.StatementStore.LineConflict;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.statement.DuplicateLine;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.LineSummary;
import com.baran.recon.domain.statement.ValidationCode;

/**
 * Takes a file's parsed lines as the parser hands them on and stores them a batch at a time, inside
 * the ingestion transaction. It keeps the counts, the first errors and duplicates, and one batch of
 * lines; nothing else grows with the file (FR-ING-5).
 *
 * <p>A batch is at most {@value #BATCH_SIZE} data lines, valid or not, so the errors of a batch can
 * be put in line order with the duplicates the store finds in it before they are counted: the
 * listed errors are the file's first ones, in file order.
 *
 * <p>A line the store skips because its source already has the line id is one of two things
 * (FR-ING-4). If the line it met is from this file, or its line id already repeated another file's
 * line earlier in this file, it is {@code DUPLICATE_LINE_IN_FILE}, a line error. Otherwise it repeats
 * a line another file stored, and a DUPLICATE_LINE break is opened on that line. The set of such line
 * ids is the one thing kept beyond a batch; it grows with lines repeated from other files only, each
 * of which also writes a break.
 */
final class LineCollector implements Consumer<ParsedLine> {

    static final int BATCH_SIZE = 1_000;

    private final UUID fileId;
    private final StatementStore store;
    private final BreakStore breaks;
    private final Actor uploader;
    private final Instant at;

    private final List<PspLine> pspLines = new ArrayList<>();
    private final List<BankLine> bankLines = new ArrayList<>();
    private final Map<UUID, Long> lineNumbers = new HashMap<>();
    private final List<LineError> batchErrors = new ArrayList<>();
    private final Set<String> repeatedFromOtherFiles = new HashSet<>();
    private int batchLines;

    private long lineCount;
    private long invalidLineCount;
    private final List<LineError> errors = new ArrayList<>();
    private long duplicateLineCount;
    private final List<DuplicateLine> duplicates = new ArrayList<>();
    /** Null until a line is stored; read through {@link #storedValueDates}. */
    private LocalDate firstStoredValueDate;
    private LocalDate lastStoredValueDate;

    LineCollector(UUID fileId, StatementStore store, BreakStore breaks, Actor uploader, Instant at) {
        this.fileId = fileId;
        this.store = store;
        this.breaks = breaks;
        this.uploader = uploader;
        this.at = at;
    }

    @Override
    public void accept(ParsedLine line) {
        lineCount++;
        switch (line) {
            case ParsedLine.Invalid invalid -> batchErrors.add(invalid.error());
            case ParsedLine.Psp psp -> {
                pspLines.add(psp.line());
                lineNumbers.put(psp.line().id(), psp.lineNumber());
            }
            case ParsedLine.Bank bank -> {
                bankLines.add(bank.line());
                lineNumbers.put(bank.line().id(), bank.lineNumber());
            }
        }
        if (++batchLines == BATCH_SIZE) {
            flush();
        }
    }

    /** Stores what is left of the last batch and gives the file's summary. */
    LineSummary finish() {
        flush();
        return new LineSummary(Optional.empty(), lineCount, invalidLineCount, errors, duplicateLineCount, duplicates);
    }

    /**
     * The earliest and latest value dates among the lines stored so far; empty while none was. A
     * line skipped as a duplicate does not count: it is the stored line's, not this file's.
     */
    Optional<StoredValueDates> storedValueDates() {
        return firstStoredValueDate == null
                ? Optional.empty()
                : Optional.of(new StoredValueDates(firstStoredValueDate, lastStoredValueDate));
    }

    private void flush() {
        List<DuplicateLine> batchDuplicates = new ArrayList<>();
        if (!pspLines.isEmpty()) {
            Map<UUID, String> lineIds = new HashMap<>();
            pspLines.forEach(line -> lineIds.put(line.id(), line.lineId()));
            List<LineConflict> conflicts = store.storePspLinesIfAbsent(pspLines);
            countStoredValueDates(pspLines.stream().map(line -> new DatedLine(line.id(), line.valueDate())), conflicts);
            resolve(conflicts, ItemSide.PSP, lineIds, batchDuplicates);
        }
        if (!bankLines.isEmpty()) {
            Map<UUID, String> lineIds = new HashMap<>();
            bankLines.forEach(line -> lineIds.put(line.id(), line.lineId()));
            List<LineConflict> conflicts = store.storeBankLinesIfAbsent(bankLines);
            countStoredValueDates(bankLines.stream().map(line -> new DatedLine(line.id(), line.valueDate())), conflicts);
            resolve(conflicts, ItemSide.BANK, lineIds, batchDuplicates);
        }
        batchErrors.sort(Comparator.comparingLong(LineError::lineNumber));
        batchErrors.forEach(this::countError);
        batchDuplicates.forEach(this::countDuplicate);
        pspLines.clear();
        bankLines.clear();
        lineNumbers.clear();
        batchErrors.clear();
        batchLines = 0;
    }

    private void resolve(List<LineConflict> conflicts, ItemSide side, Map<UUID, String> lineIds,
                         List<DuplicateLine> batchDuplicates) {
        for (LineConflict conflict : conflicts) {
            long lineNumber = lineNumbers.get(conflict.lineId());
            String lineId = lineIds.get(conflict.lineId());
            if (conflict.storedFileId().equals(fileId) || repeatedFromOtherFiles.contains(lineId)) {
                batchErrors.add(new LineError(lineNumber, ValidationCode.DUPLICATE_LINE_IN_FILE));
            } else {
                repeatedFromOtherFiles.add(lineId);
                batchDuplicates.add(openDuplicateLineBreak(side, conflict, lineNumber));
            }
        }
    }

    private DuplicateLine openDuplicateLineBreak(ItemSide side, LineConflict conflict, long lineNumber) {
        String reason = "Line " + lineNumber + " of statement file " + fileId + " repeats this line's line_id";
        BreakStore.Opening opening = breaks.openUnlessUnresolved(Break.open(UUID.randomUUID(), BreakType.DUPLICATE_LINE,
                new ItemRef(side, conflict.storedLineId()), List.of(), Optional.empty(), uploader, Optional.of(reason), at));
        return new DuplicateLine(lineNumber, opening.breakId(), opening.opened());
    }

    private void countStoredValueDates(Stream<DatedLine> batch, List<LineConflict> conflicts) {
        Set<UUID> skipped = new HashSet<>();
        conflicts.forEach(conflict -> skipped.add(conflict.lineId()));
        batch.filter(line -> !skipped.contains(line.id())).map(DatedLine::valueDate).forEach(valueDate -> {
            if (firstStoredValueDate == null || valueDate.isBefore(firstStoredValueDate)) {
                firstStoredValueDate = valueDate;
            }
            if (lastStoredValueDate == null || valueDate.isAfter(lastStoredValueDate)) {
                lastStoredValueDate = valueDate;
            }
        });
    }

    private void countError(LineError error) {
        invalidLineCount++;
        if (errors.size() < LineSummary.MAX_LISTED) {
            errors.add(error);
        }
    }

    private void countDuplicate(DuplicateLine duplicate) {
        duplicateLineCount++;
        if (duplicates.size() < LineSummary.MAX_LISTED) {
            duplicates.add(duplicate);
        }
    }

    /** The earliest and latest value dates of a file's stored lines. */
    record StoredValueDates(LocalDate first, LocalDate last) {
    }

    private record DatedLine(UUID id, LocalDate valueDate) {
    }
}
