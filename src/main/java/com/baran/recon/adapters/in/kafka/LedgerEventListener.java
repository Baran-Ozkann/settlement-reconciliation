package com.baran.recon.adapters.in.kafka;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.baran.recon.adapters.in.kafka.ParsedRecord.Accepted;
import com.baran.recon.adapters.in.kafka.ParsedRecord.Rejected;
import com.baran.recon.application.ledger.LedgerEvent;
import com.baran.recon.application.ledger.ProjectLedgerEvents;
import com.baran.recon.application.ledger.ProjectionOutcome;

/**
 * Consumes the ledger's account activity (FR-LED-1) a polled batch at a time.
 *
 * <p>Order within a batch: project the valid records in one transaction, then dead-letter what could
 * not be projected, then acknowledge. The offset is committed only after both have finished
 * (FR-LED-4). A failure anywhere before the acknowledgement throws, and the error handler redelivers
 * the batch: the projection skips what it already stored, so the only thing a retry can repeat is a
 * dead letter, which is delivered at least once.
 *
 * <p>A record that cannot be projected is dead-lettered and the partition moves on (FR-LED-5). A
 * failure that is not the record's fault - the database or the dead-letter topic unavailable - is
 * never dead-lettered: it is thrown, and retried until it clears.
 */
@Component
class LedgerEventListener {

    static final String LISTENER_ID = "ledger-account-activity";
    static final String CONSUMER_GROUP = "settlement-reconciliation";

    private static final Logger LOG = LoggerFactory.getLogger(LedgerEventListener.class);

    private final LedgerRecordParser parser;
    private final ProjectLedgerEvents projection;
    private final DeadLetterPublisher deadLetters;
    private final UnfamiliarValueReporter unfamiliar;

    LedgerEventListener(LedgerRecordParser parser, ProjectLedgerEvents projection, DeadLetterPublisher deadLetters,
                        UnfamiliarValueReporter unfamiliar) {
        this.parser = parser;
        this.projection = projection;
        this.deadLetters = deadLetters;
        this.unfamiliar = unfamiliar;
    }

    @KafkaListener(id = LISTENER_ID, groupId = CONSUMER_GROUP, topics = LedgerTopics.ACCOUNT_ACTIVITY, batch = "true")
    void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment acknowledgment) {
        List<ConsumerRecord<String, byte[]>> acceptedRecords = new ArrayList<>();
        List<LedgerEvent> events = new ArrayList<>();
        List<ConsumerRecord<String, byte[]>> rejectedRecords = new ArrayList<>();
        List<Rejected> rejections = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            switch (parser.parse(record.headers(), record.value())) {
                case Accepted accepted -> {
                    acceptedRecords.add(record);
                    events.add(accepted.event());
                }
                case Rejected rejected -> {
                    rejectedRecords.add(record);
                    rejections.add(rejected);
                }
            }
        }

        List<ProjectionOutcome> outcomes = projection.project(events);

        for (int i = 0; i < rejectedRecords.size(); i++) {
            deadLetter(rejectedRecords.get(i), rejections.get(i).reason(), rejections.get(i).message());
        }
        for (int i = 0; i < outcomes.size(); i++) {
            switch (outcomes.get(i)) {
                case STORED -> unfamiliar.projected(events.get(i));
                case DUPLICATE_ENTRY_ID -> duplicateEntry(acceptedRecords.get(i), events.get(i));
                case DUPLICATE_EVENT, UNMAPPED_ACCOUNT -> {
                    // Nothing new was stored, so there is nothing to report or dead-letter.
                }
            }
        }
        acknowledgment.acknowledge();
    }

    /**
     * FR-LED-8: the ledger published one entry under two event ids, which its outbox must never do.
     * That is a fault on the ledger's side, so it is logged at ERROR, not only dead-lettered.
     */
    private void duplicateEntry(ConsumerRecord<String, byte[]> record, LedgerEvent event) {
        long entryId = event.entryId().orElseThrow();
        LOG.error("Ledger entry {} arrived again under event-id {} at {}; the ledger published one entry twice",
                entryId, event.eventId(), coordinates(record));
        deadLetters.publish(record, DeadLetterReason.DUPLICATE_ENTRY_ID,
                "entry_id " + entryId + " was already projected from another event");
    }

    private void deadLetter(ConsumerRecord<String, byte[]> record, DeadLetterReason reason, String message) {
        LOG.warn("Dead-lettering the ledger record at {} as {}: {}", coordinates(record), reason, message);
        deadLetters.publish(record, reason, message);
    }

    private static String coordinates(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
