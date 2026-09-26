package com.baran.recon.archfixture.clean.adapters.in.kafka;

import org.springframework.kafka.core.KafkaTemplate;

public class DeadLetters {

    private final KafkaTemplate<String, byte[]> template;

    public DeadLetters(KafkaTemplate<String, byte[]> template) {
        this.template = template;
    }

    public void deadLetter(String key, byte[] value) {
        template.send("recon.ledger-account-activity.dlq", key, value);
    }
}
