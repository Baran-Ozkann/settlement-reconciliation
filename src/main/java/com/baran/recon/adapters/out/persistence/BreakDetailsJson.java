package com.baran.recon.adapters.out.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakDetails.BatchChangedAfterMatch;
import com.baran.recon.domain.breaks.BreakDetails.BatchTotalConflict;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

/**
 * The stored form of a break's {@code details} (TDD 8.2, v1.15): the one place a {@link BreakDetails}
 * becomes JSON and back. Keys are written in the order TDD 8.2 lists them, although a JSONB value
 * keeps its own key order; a list keeps its order, so the sums stay in currency-code order (INV-5).
 * Amounts are JSON integers in minor units, exact in JSONB (INV-8).
 *
 * <p>Reading accepts only what writing produces: a known kind, exactly its keys, integer amounts and
 * counts. Anything else is a stored value no code path wrote, and fails loudly.
 */
final class BreakDetailsJson {

    static final String BATCH_TOTAL_CONFLICT = "BATCH_TOTAL_CONFLICT";
    static final String BATCH_CHANGED_AFTER_MATCH = "BATCH_CHANGED_AFTER_MATCH";

    private final JsonMapper json;

    BreakDetailsJson(JsonMapper json) {
        this.json = json;
    }

    String write(BreakDetails details) {
        ObjectNode root = json.createObjectNode();
        switch (details) {
            case BatchTotalConflict conflict -> {
                root.put("kind", BATCH_TOTAL_CONFLICT);
                root.putObject("bank")
                        .put("amount", conflict.bank().minorUnits())
                        .put("currency", conflict.bank().currency().code());
                writeFigures(root.putObject("batch"), conflict.batch());
            }
            case BatchChangedAfterMatch changed -> {
                root.put("kind", BATCH_CHANGED_AFTER_MATCH);
                root.put("matchId", changed.matchId().toString());
                writeFigures(root.putObject("matched"), changed.matched());
                writeFigures(root.putObject("current"), changed.current());
            }
        }
        return json.writeValueAsString(root);
    }

    BreakDetails read(String stored) {
        JsonNode root = json.readTree(stored);
        String kind = text(root, "kind");
        return switch (kind) {
            case BATCH_TOTAL_CONFLICT -> {
                requireKeys(root, Set.of("kind", "bank", "batch"));
                JsonNode bank = root.get("bank");
                requireKeys(bank, Set.of("amount", "currency"));
                yield new BatchTotalConflict(Money.of(integer(bank, "amount"), CurrencyCode.of(text(bank, "currency"))),
                        readFigures(root.get("batch")));
            }
            case BATCH_CHANGED_AFTER_MATCH -> {
                requireKeys(root, Set.of("kind", "matchId", "matched", "current"));
                yield new BatchChangedAfterMatch(UUID.fromString(text(root, "matchId")),
                        readFigures(root.get("matched")), readFigures(root.get("current")));
            }
            default -> throw new IllegalStateException("stored break details of unknown kind " + kind);
        };
    }

    private static void writeFigures(ObjectNode node, BatchFigures figures) {
        node.put("lineCount", figures.lineCount());
        ArrayNode sums = node.putArray("sums");
        figures.sums().forEach(sum -> sums.addObject()
                .put("currency", sum.currency().code())
                .put("amount", sum.minorUnits()));
    }

    private static BatchFigures readFigures(JsonNode node) {
        requireKeys(node, Set.of("lineCount", "sums"));
        JsonNode sums = node.get("sums");
        if (!sums.isArray()) {
            throw new IllegalStateException("stored batch sums are not a list");
        }
        List<Money> read = new ArrayList<>();
        for (JsonNode sum : sums.values()) {
            requireKeys(sum, Set.of("currency", "amount"));
            read.add(Money.of(integer(sum, "amount"), CurrencyCode.of(text(sum, "currency"))));
        }
        return new BatchFigures(Math.toIntExact(integer(node, "lineCount")), read);
    }

    private static void requireKeys(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(keys)) {
            throw new IllegalStateException("stored break details do not have the keys " + keys);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString()) {
            throw new IllegalStateException("stored break details: " + key + " is not a string");
        }
        return value.stringValue();
    }

    private static long integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("stored break details: " + key + " is not an integer");
        }
        return value.longValue();
    }
}
