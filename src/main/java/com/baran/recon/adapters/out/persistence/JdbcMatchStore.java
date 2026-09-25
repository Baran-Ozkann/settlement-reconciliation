package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.baran.recon.application.port.ItemAlreadyMatchedException;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.match.MatchItem;
import com.baran.recon.domain.match.MatchStatus;
import com.baran.recon.domain.match.RuleId;

import static com.baran.recon.adapters.out.persistence.SqlValues.currency;
import static com.baran.recon.adapters.out.persistence.SqlValues.instant;
import static com.baran.recon.adapters.out.persistence.SqlValues.money;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;
import static com.baran.recon.adapters.out.persistence.SqlValues.uuid;

@Repository
class JdbcMatchStore implements MatchStore {

    private static final String ACTIVE_ITEM_INDEX = "match_items_active_item_unique";

    private static final String INSERT_MATCH = """
            INSERT INTO matches (id, run_id, rule_id, rule_version, cardinality, status, amount_difference,
                                 currency, low_confidence, created_at)
            VALUES (:id, :runId, :ruleId, :ruleVersion, :cardinality, :status, :amountDifference,
                    :currency, :lowConfidence, :createdAt)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO match_items (match_id, side, item_id, active)
            VALUES (:matchId, :side, :itemId, :active)
            """;

    /** A match is made by a run, never by a person, so its creation is always the system's. */
    private static final String INSERT_CREATED_EVENT = """
            INSERT INTO match_events (match_id, event_type, actor, reason, occurred_at)
            VALUES (:matchId, 'CREATED', :actor, NULL, :occurredAt)
            """;

    private static final String SELECT_MATCH = """
            SELECT id, run_id, rule_id, rule_version, status, amount_difference, currency, created_at
              FROM matches
             WHERE id = :id
            """;

    private static final String SELECT_ITEMS = """
            SELECT side, item_id FROM match_items WHERE match_id = :matchId ORDER BY side, item_id
            """;

    private final JdbcClient jdbc;

    JdbcMatchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void record(Match match) {
        jdbc.sql(INSERT_MATCH)
                .param("id", match.id())
                .param("runId", match.runId())
                .param("ruleId", match.rule().name())
                .param("ruleVersion", match.ruleVersion())
                .param("cardinality", match.cardinality().name())
                .param("status", match.status().name())
                .param("amountDifference", match.amountDifference().minorUnits())
                .param("currency", match.amountDifference().currency().code())
                .param("lowConfidence", match.lowConfidence())
                .param("createdAt", timestamp(match.createdAt()))
                .update();
        boolean active = match.status() == MatchStatus.ACTIVE;
        try {
            for (MatchItem item : match.items()) {
                jdbc.sql(INSERT_ITEM)
                        .param("matchId", match.id())
                        .param("side", item.side().name())
                        .param("itemId", item.itemId())
                        .param("active", active)
                        .update();
            }
        } catch (DuplicateKeyException duplicate) {
            if (PostgresErrors.violatedConstraint(duplicate).filter(ACTIVE_ITEM_INDEX::equals).isPresent()) {
                throw new ItemAlreadyMatchedException(match.id(), duplicate);
            }
            throw duplicate;
        }
        jdbc.sql(INSERT_CREATED_EVENT)
                .param("matchId", match.id())
                .param("actor", Actor.SYSTEM.name())
                .param("occurredAt", timestamp(match.createdAt()))
                .update();
    }

    @Override
    public Optional<Match> findById(UUID id) {
        return jdbc.sql(SELECT_MATCH).param("id", id).query(this::map).optional();
    }

    private Match map(ResultSet row, int rowNumber) throws SQLException {
        UUID id = uuid(row, "id");
        List<MatchItem> items = jdbc.sql(SELECT_ITEMS).param("matchId", id)
                .query((item, n) -> new MatchItem(ItemSide.valueOf(item.getString("side")), uuid(item, "item_id")))
                .list();
        return new Match(
                id,
                uuid(row, "run_id"),
                RuleId.valueOf(row.getString("rule_id")),
                row.getInt("rule_version"),
                MatchStatus.valueOf(row.getString("status")),
                money(row, "amount_difference", currency(row, "currency")),
                instant(row, "created_at"),
                items);
    }
}
