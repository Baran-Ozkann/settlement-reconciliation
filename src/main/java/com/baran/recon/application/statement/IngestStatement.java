package com.baran.recon.application.statement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.application.port.StatementFileAlreadyIngestedException;
import com.baran.recon.application.port.StatementParser;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.LineSummary;
import com.baran.recon.domain.statement.SanitizedFilename;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;

/**
 * Ingests one uploaded statement file (TDD 5.3). In order:
 * <ol>
 *   <li>the source must be configured, and its type chooses the parser (FR-ING-2); the statement
 *       reference must be well formed;</li>
 *   <li>the content is hashed, and a file already ingested with the same hash, or under the same
 *       source and reference, is refused with the original's id before a line is read (FR-ING-3);</li>
 *   <li>in one transaction, the file is parsed line by line and its lines stored in batches as they
 *       come; a line already stored for the source is a duplicate (FR-ING-4). The file row is written
 *       last, INGESTED, once its summary is known (FR-ING-6);</li>
 *   <li>if the file breaks its header or the invalid-line threshold (FR-ING-7), that transaction is
 *       rolled back, so none of its lines and no break remain, and the file is recorded REJECTED with
 *       its line errors in a transaction of its own.</li>
 * </ol>
 *
 * <p>Nothing else is recorded. A file over the line limit is refused as a file over the size limit
 * is, and a failure of the store or of reading the upload leaves no trace, as a crash would
 * (NFR-REL-1): a REJECTED file carries line errors, and those failures have none. Either way the
 * same file can be uploaded again.
 */
public final class IngestStatement {

    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final ConfiguredSources sources;
    private final Map<SourceType, StatementParser> parsers;
    private final StatementStore store;
    private final BreakStore breaks;
    private final Transactions transactions;
    private final Clock clock;
    private final IngestionLimits limits;

    public IngestStatement(ConfiguredSources sources, List<StatementParser> parsers, StatementStore store,
                           BreakStore breaks, Transactions transactions, Clock clock, IngestionLimits limits) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.parsers = new EnumMap<>(SourceType.class);
        for (StatementParser parser : parsers) {
            if (this.parsers.put(parser.sourceType(), parser) != null) {
                throw new IllegalArgumentException("two parsers read " + parser.sourceType());
            }
        }
        for (SourceType type : SourceType.values()) {
            if (!this.parsers.containsKey(type)) {
                throw new IllegalArgumentException("no parser reads " + type);
            }
        }
        this.store = Objects.requireNonNull(store, "store");
        this.breaks = Objects.requireNonNull(breaks, "breaks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * @return the file as recorded: INGESTED, or REJECTED with the line errors that rejected it
     * @throws UploadRefusedException if the upload was refused before any line was read
     * @throws TooManyLinesException  if the file has more data lines than the limit
     * @throws IOException            if the upload could not be read
     */
    public StatementFile ingest(UploadedStatement upload) throws IOException {
        SourceDefinition source = source(upload.source());
        if (!StatementFile.isValidStatementReference(upload.statementReference())) {
            throw UploadRefusedException.invalidStatementReference();
        }
        Actor uploader = Actor.operator(upload.uploadedBy());
        Hashed hashed = hash(upload.content());
        refuseIfAlreadyIngested(source.code(), upload.statementReference(), hashed.sha256());

        UUID fileId = UUID.randomUUID();
        // TIMESTAMPTZ keeps microseconds: truncated here, the file returned is the file stored.
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Recorder recorder = (status, lines) -> new StatementFile(fileId, source.code(), upload.statementReference(),
                hashed.sha256(), SanitizedFilename.of(upload.originalFilename()), hashed.size(), status, lines,
                upload.uploadedBy(), receivedAt);
        try {
            return transactions.inTransaction(() -> {
                store.checkLineFilesAtCommit();
                LineSummary lines = parse(upload.content(), fileId, source, uploader, receivedAt);
                if (lines.headerError().isPresent() || limits.rejects(lines.invalidLineCount(), lines.lineCount())) {
                    throw new Rejected(lines);
                }
                StatementFile file = recorder.record(StatementFileStatus.INGESTED, lines);
                store.storeFile(file);
                return file;
            });
        } catch (Rejected rejected) {
            StatementFile file = recorder.record(StatementFileStatus.REJECTED, rejected.withoutDuplicates());
            transactions.inTransaction(() -> {
                store.storeFile(file);
                return file;
            });
            return file;
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        } catch (StatementFileAlreadyIngestedException concurrent) {
            // A concurrent upload of the same file won the partial unique index: refused as if it
            // had been found before parsing, with its id.
            refuseIfAlreadyIngested(source.code(), upload.statementReference(), hashed.sha256());
            throw concurrent;
        }
    }

    private SourceDefinition source(String code) {
        if (code == null || !SOURCE_CODE.matcher(code).matches()) {
            throw UploadRefusedException.unknownSource();
        }
        return sources.find(SourceCode.of(code)).orElseThrow(UploadRefusedException::unknownSource);
    }

    private void refuseIfAlreadyIngested(SourceCode source, String reference, String sha256) {
        Optional<UUID> sameContent = store.findIngestedFileBySha256(sha256);
        if (sameContent.isPresent()) {
            throw UploadRefusedException.duplicateContent(sameContent.get());
        }
        Optional<UUID> sameReference = store.findIngestedFileByReference(source, reference);
        if (sameReference.isPresent()) {
            throw UploadRefusedException.duplicateReference(sameReference.get());
        }
    }

    private LineSummary parse(UploadedStatement.Content content, UUID fileId, SourceDefinition source, Actor uploader,
                              Instant at) {
        LineCollector collector = new LineCollector(fileId, store, breaks, uploader, at);
        try (InputStream in = content.open()) {
            Optional<LineError> headerError = parsers.get(source.type())
                    .parse(in, new StatementContext(fileId, source), collector);
            return headerError.map(LineSummary::headerRejected).orElseGet(collector::finish);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The size is counted from the bytes read, never taken from the client. */
    private static Hashed hash(UploadedStatement.Content content) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new DigestInputStream(content.open(), digest)) {
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                size += read;
            }
        }
        return new Hashed(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException required) {
            throw new IllegalStateException("every Java platform provides SHA-256", required);
        }
    }

    private record Hashed(String sha256, long size) {
    }

    @FunctionalInterface
    private interface Recorder {

        StatementFile record(StatementFileStatus status, LineSummary lines);
    }

    /** Thrown inside the ingestion transaction so that it rolls back; caught just outside it. */
    private static final class Rejected extends RuntimeException {

        private final transient LineSummary lines;

        Rejected(LineSummary lines) {
            super("rejected", null, false, false);
            this.lines = lines;
        }

        /** Its lines and breaks were rolled back, so a rejected file repeated nothing. */
        LineSummary withoutDuplicates() {
            return new LineSummary(lines.headerError(), lines.lineCount(), lines.invalidLineCount(), lines.errors(), 0,
                    List.of());
        }
    }
}
