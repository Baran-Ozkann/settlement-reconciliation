package com.baran.recon.adapters.in.kafka;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.baran.recon.application.ledger.LedgerEvent;
import com.baran.recon.domain.item.LedgerTxType;

/**
 * Makes a projected entry that carries a value this service has not learned visible, rather than
 * silent (FR-LED-9): logged at WARN the first time each value is seen, and counted in a metric
 * tagged with it. The entry itself is projected like any other; this only reports it.
 *
 * <p>The value comes from the ledger's record and is not trusted. What is logged and tagged is the
 * value only if it looks like an enum name, and {@code other} if not, so a crafted value can neither
 * forge a log line nor give the metric an unbounded number of series.
 */
@Component
class UnfamiliarValueReporter {

    static final String UNMAPPED_TX_TYPE = "recon.ledger.unmapped.tx.type";

    private static final Logger LOG = LoggerFactory.getLogger(UnfamiliarValueReporter.class);
    private static final Pattern ENUM_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final String OTHER = "other";

    private final MeterRegistry meters;
    private final Set<String> warnedTxTypes = ConcurrentHashMap.newKeySet();

    UnfamiliarValueReporter(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Called once for each event that was stored, so a redelivery is not counted twice. */
    void projected(LedgerEvent event) {
        if (!LedgerTxType.isKnown(event.txType())) {
            String txType = tagValue(event.txType());
            if (warnedTxTypes.add(txType)) {
                LOG.warn("Projected a ledger entry with tx_type {}, which this service does not map yet; "
                        + "it is stored as it arrived and no matching rule reads it", txType);
            }
            Counter.builder(UNMAPPED_TX_TYPE)
                    .description("Projected ledger entries whose tx_type this service does not map (FR-LED-9)")
                    .tag("tx_type", txType)
                    .register(meters)
                    .increment();
        }
    }

    private static String tagValue(String raw) {
        return ENUM_NAME.matcher(raw).matches() ? raw : OTHER;
    }
}
