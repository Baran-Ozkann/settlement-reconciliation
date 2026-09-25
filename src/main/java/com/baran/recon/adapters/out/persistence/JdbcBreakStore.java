package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.ItemAlreadyHasOpenBreakException;
import com.baran.recon.application.port.StaleBreakTransitionException;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakEvent;
import com.baran.recon.domain.breaks.BreakStatus;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.breaks.ResolutionCode;
import com.baran.recon.domain.breaks.Transition;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;

import static com.baran.recon.adapters.out.persistence.SqlValues.instant;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalInstant;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalText;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalUuid;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestampOrNull;
import static com.baran.recon.adapters.out.persistence.SqlValues.uuid;

@Repository
class JdbcBreakStore implements BreakStore {

    private static final String UNRESOLVED_PER_ITEM_INDEX = "breaks_one_unresolved_per_item";

    private static final String INSERT_BREAK = """
            INSERT INTO breaks (id, break_type, item_side, item_id, related_items, status, resolution_code,
                                opened_run_id, previous_break_id, opened_at, resolved_at)
            VALUES (:id, :breakType, :itemSide, :itemId, CAST(:relatedItems AS JSONB), :status, :resolutionCode,
                    :openedRunId, :previousBreakId, :openedAt, :resolvedAt)
            """;

    /**
     * Only the three columns a transition changes, which are also the only ones recon_app may
     * update. The status guard makes a concurrent change fail instead of being overwritten.
     */
    private static final String UPDATE_BREAK = """
            UPDATE breaks
               SET status = :status, resolution_code = :resolutionCode, resolved_at = :resolvedAt
             WHERE id = :id AND status = :expectedStatus
            """;

    private static final String INSERT_EVENT = """
            INSERT INTO break_events (break_id, from_status, to_status, resolution_code, actor, reason, occurred_at)
            VALUES (:breakId, :fromStatus, :toStatus, :resolutionCode, :actor, :reason, :occurredAt)
            """;

    private static final String SELECT_BREAK = """
            SELECT id, break_type, item_side, item_id, related_items, status, resolution_code, opened_run_id,
                   previous_break_id, opened_at, resolved_at
              FROM breaks
             WHERE id = :id
            """;

    /** The id is a sequence, so it orders events as they were written; the time alone may tie. */
    private static final String SELECT_EVENTS = """
            SELECT break_id, from_status, to_status, resolution_code, actor, reason, occurred_at
              FROM break_events
             WHERE break_id = :breakId
             ORDER BY id
            """;

    private static final TypeReference<List<ItemJson>> ITEM_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcBreakStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public void open(Transition opened) {
        Break created = opened.result();
        try {
            jdbc.sql(INSERT_BREAK)
                    .param("id", created.id())
                    .param("breakType", created.type().name())
                    .param("itemSide", created.item().side().name())
                    .param("itemId", created.item().id())
                    .param("relatedItems", json.writeValueAsString(created.relatedItems().stream().map(ItemJson::of).toList()))
                    .param("status", created.status().name())
                    .param("resolutionCode", created.resolutionCode().map(Enum::name).orElse(null), Types.VARCHAR)
                    .param("openedRunId", created.openedRunId().orElse(null), Types.OTHER)
                    .param("previousBreakId", created.previousBreakId().orElse(null), Types.OTHER)
                    .param("openedAt", timestamp(created.openedAt()))
                    .param("resolvedAt", timestampOrNull(created.resolvedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
        } catch (DuplicateKeyException duplicate) {
            if (PostgresErrors.violatedConstraint(duplicate).filter(UNRESOLVED_PER_ITEM_INDEX::equals).isPresent()) {
                throw new ItemAlreadyHasOpenBreakException(created.item(), duplicate);
            }
            throw duplicate;
        }
        appendEvent(opened.event());
    }

    @Override
    @Transactional
    public void apply(Transition transition) {
        Break changed = transition.result();
        BreakStatus expected = transition.event().from()
                .orElseThrow(() -> new IllegalArgumentException("an opening transition is stored with open()"));
        int updated = jdbc.sql(UPDATE_BREAK)
                .param("status", changed.status().name())
                .param("resolutionCode", changed.resolutionCode().map(Enum::name).orElse(null), Types.VARCHAR)
                .param("resolvedAt", timestampOrNull(changed.resolvedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", changed.id())
                .param("expectedStatus", expected.name())
                .update();
        if (updated != 1) {
            throw new StaleBreakTransitionException(changed.id());
        }
        appendEvent(transition.event());
    }

    @Override
    public Optional<Break> findById(UUID id) {
        return jdbc.sql(SELECT_BREAK).param("id", id).query(this::mapBreak).optional();
    }

    @Override
    public List<BreakEvent> eventsOf(UUID breakId) {
        return jdbc.sql(SELECT_EVENTS).param("breakId", breakId).query(JdbcBreakStore::mapEvent).list();
    }

    private void appendEvent(BreakEvent event) {
        jdbc.sql(INSERT_EVENT)
                .param("breakId", event.breakId())
                .param("fromStatus", event.from().map(Enum::name).orElse(null), Types.VARCHAR)
                .param("toStatus", event.to().name())
                .param("resolutionCode", event.resolutionCode().map(Enum::name).orElse(null), Types.VARCHAR)
                .param("actor", event.actor().name())
                .param("reason", event.reason().orElse(null), Types.VARCHAR)
                .param("occurredAt", timestamp(event.occurredAt()))
                .update();
    }

    private Break mapBreak(ResultSet row, int rowNumber) throws SQLException {
        List<ItemRef> related = json.readValue(row.getString("related_items"), ITEM_LIST).stream()
                .map(ItemJson::toItemRef).toList();
        return new Break(
                uuid(row, "id"),
                BreakType.valueOf(row.getString("break_type")),
                new ItemRef(ItemSide.valueOf(row.getString("item_side")), uuid(row, "item_id")),
                related,
                BreakStatus.valueOf(row.getString("status")),
                optionalText(row, "resolution_code").map(ResolutionCode::valueOf),
                optionalUuid(row, "opened_run_id"),
                optionalUuid(row, "previous_break_id"),
                instant(row, "opened_at"),
                optionalInstant(row, "resolved_at"));
    }

    private static BreakEvent mapEvent(ResultSet row, int rowNumber) throws SQLException {
        return new BreakEvent(
                uuid(row, "break_id"),
                optionalText(row, "from_status").map(BreakStatus::valueOf),
                BreakStatus.valueOf(row.getString("to_status")),
                optionalText(row, "resolution_code").map(ResolutionCode::valueOf),
                new Actor(row.getString("actor")),
                optionalText(row, "reason"),
                instant(row, "occurred_at"));
    }

    /** The stored form of a related item. */
    private record ItemJson(String side, UUID id) {

        static ItemJson of(ItemRef item) {
            return new ItemJson(item.side().name(), item.id());
        }

        ItemRef toItemRef() {
            return new ItemRef(ItemSide.valueOf(side), id);
        }
    }
}
