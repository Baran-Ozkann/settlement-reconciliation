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
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.SupportedCurrencies;

/**
 * Makes a projected entry that carries a value this service has not learned visible, rather than
 * silent: logged at WARN the first time each value is seen, and counted in a metric tagged with it.
 * The entry itself is projected like any other; this only reports it. Two such values:
 * <ul>
 *   <li>a tx_type this service does not map (FR-LED-9);</li>
 *   <li>a real ISO 4217 currency outside {@code recon.supported-currencies}. Not dead-lettered, for the
 *       same reason as the tx_type: the money moved on an account this service reconciles, and
 *       dropping the entry would turn it into a phantom break on the PSP side. A code that is not
 *       ISO 4217 at all never gets here; it fails the contract as SCHEMA_INVALID.</li>
 * </ul>
 *
 * <p>The value comes from the ledger's record and is not trusted. What is logged and tagged is the
 * value only if it looks like an enum name, and {@code other} if not, so a crafted value can neither
 * forge a log line nor give the metric an unbounded number of series.
 */
@Component
class UnfamiliarValueReporter {

    static final String UNMAPPED_TX_TYPE = "recon.ledger.unmapped.tx.type";
    static final String UNSUPPORTED_CURRENCY = "recon.ledger.unsupported.currency";

    private static final Logger LOG = LoggerFactory.getLogger(UnfamiliarValueReporter.class);
    private static final Pattern ENUM_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final String OTHER = "other";

    private final MeterRegistry meters;
    private final SupportedCurrencies supportedCurrencies;
    private final Set<String> warnedTxTypes = ConcurrentHashMap.newKeySet();
    private final Set<CurrencyCode> warnedCurrencies = ConcurrentHashMap.newKeySet();

    UnfamiliarValueReporter(MeterRegistry meters, SupportedCurrencies supportedCurrencies) {
        this.meters = meters;
        this.supportedCurrencies = supportedCurrencies;
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
        if (!supportedCurrencies.supports(event.currency())) {
            // An ISO 4217 code by now, so it is safe to log and makes a bounded tag.
            if (warnedCurrencies.add(event.currency())) {
                LOG.warn("Projected a ledger entry in {}, which is not a supported currency; it is stored and "
                        + "will not match a line in a supported one", event.currency());
            }
            Counter.builder(UNSUPPORTED_CURRENCY)
                    .description("Projected ledger entries in a currency outside recon.supported-currencies")
                    .tag("currency", event.currency().code())
                    .register(meters)
                    .increment();
        }
    }

    private static String tagValue(String raw) {
        return ENUM_NAME.matcher(raw).matches() ? raw : OTHER;
    }
}
