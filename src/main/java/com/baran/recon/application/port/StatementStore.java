package com.baran.recon.application.port;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.statement.StatementFile;

/**
 * Uploaded statement files and their lines. Lines are taken as a stream and written in batches, so
 * a file's lines never have to be held in memory together (FR-ING-5). Making a file and its lines
 * one transaction (FR-ING-6) is the caller's job.
 */
public interface StatementStore {

    void storeFile(StatementFile file);

    /** @return how many lines were stored */
    long storePspLines(Stream<PspLine> lines);

    /** @return how many lines were stored */
    long storeBankLines(Stream<BankLine> lines);

    Optional<StatementFile> findFile(UUID id);

    Optional<PspLine> findPspLine(UUID id);

    Optional<BankLine> findBankLine(UUID id);
}
