package com.baran.recon.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.statement.StatementFile;

/**
 * Uploaded statement files and their lines. Lines are taken a batch at a time, so a file's lines
 * never have to be held in memory together (FR-ING-5). Making a file and its lines one transaction
 * (FR-ING-6) is the caller's job.
 */
public interface StatementStore {

    /**
     * Within the current transaction, lets lines be stored before the file they belong to. A file
     * is stored once, in its final state, which is known only after its last line: the reference
     * from each line to its file is then checked when the transaction commits, and the commit is
     * refused if the file was never stored.
     */
    void checkLineFilesAtCommit();

    void storeFile(StatementFile file);

    /**
     * Stores each line whose line id its source has not stored yet, from any file, and skips the
     * others (FR-ING-4, INV-3). A skipped line is never lost silently: it is returned with the stored
     * line it met, so the caller can tell a line repeated within one file from one already ingested
     * from another.
     *
     * @return one conflict per skipped line, in the order given
     */
    List<LineConflict> storePspLinesIfAbsent(List<PspLine> lines);

    /** As {@link #storePspLinesIfAbsent}, for bank lines. */
    List<LineConflict> storeBankLinesIfAbsent(List<BankLine> lines);

    Optional<StatementFile> findFile(UUID id);

    /** The file with this content that was ingested; a rejected one never counts (FR-ING-3). */
    Optional<UUID> findIngestedFileBySha256(String sha256);

    /** The file ingested under this reference for the source; a rejected one never counts (FR-ING-3). */
    Optional<UUID> findIngestedFileByReference(SourceCode source, String statementReference);

    Optional<PspLine> findPspLine(UUID id);

    Optional<BankLine> findBankLine(UUID id);

    /**
     * A line that was not stored because its source already holds its line id.
     *
     * @param lineId       the id of the line that was not stored
     * @param storedLineId the line already stored under the same source and line id
     * @param storedFileId the file that stored line came from
     */
    record LineConflict(UUID lineId, UUID storedLineId, UUID storedFileId) {

        public LineConflict {
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(storedLineId, "storedLineId");
            Objects.requireNonNull(storedFileId, "storedFileId");
        }
    }
}
