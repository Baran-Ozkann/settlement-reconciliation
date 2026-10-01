package com.baran.recon.application.statement;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.domain.statement.StatementFile;

/** A statement file as recorded: its status, counts and line errors (TDD 11, for a VIEWER). */
public final class ViewStatement {

    private final StatementStore store;

    public ViewStatement(StatementStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Optional<StatementFile> find(UUID id) {
        return store.findFile(id);
    }
}
