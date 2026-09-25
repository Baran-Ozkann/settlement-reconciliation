package com.baran.recon.domain.item;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * A PSP batch: batch ids are unique per PSP source (TDD 8.2), so the pair identifies one. A batch
 * has no row of its own, yet matches and breaks refer to it by a UUID like every other item, so
 * {@link #itemId()} derives one from the pair. It is name-based, so the same batch always
 * resolves to the same id, in any run and on any machine. Neither part can contain the separator,
 * so no two batches share a name.
 */
public record BatchKey(SourceCode source, String batchId) {

    private static final String NAMESPACE = "recon-batch";
    private static final char SEPARATOR = '/';

    public BatchKey {
        Objects.requireNonNull(source, "source");
        ItemRules.identifier("batch_id", batchId);
    }

    public UUID itemId() {
        String name = NAMESPACE + SEPARATOR + source.value() + SEPARATOR + batchId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
