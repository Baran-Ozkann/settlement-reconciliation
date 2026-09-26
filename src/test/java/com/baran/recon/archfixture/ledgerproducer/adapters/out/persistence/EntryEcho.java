package com.baran.recon.archfixture.ledgerproducer.adapters.out.persistence;

import org.springframework.kafka.core.KafkaTemplate;

/** Writes back to the ledger's topic from persistence: what INV-9 forbids. */
public class EntryEcho {

    private final KafkaTemplate<String, String> template;

    public EntryEcho(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    public void echo(String key, String payload) {
        template.send("ledger.account-activity", key, payload);
    }
}
