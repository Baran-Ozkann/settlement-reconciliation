package com.baran.recon.adapters.in.kafka;

/** A send named a topic other than the dead-letter topic; nothing was sent (INV-9). */
public final class TopicWriteRefusedException extends RuntimeException {

    TopicWriteRefusedException(String topic) {
        super("this service writes only to " + LedgerTopics.DEAD_LETTER + "; a send to " + topic + " was refused");
    }
}
