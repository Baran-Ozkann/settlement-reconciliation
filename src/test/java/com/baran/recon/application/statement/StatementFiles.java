package com.baran.recon.application.statement;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Synthetic statement files for the ingestion tests. Every value is fake, and each file's line ids are its own. */
public final class StatementFiles {

    public static final String PSP_HEADER =
            "line_id,transaction_reference,batch_id,transaction_date,value_date,type,gross_amount,fee_amount,net_amount,currency";
    public static final String BANK_HEADER = "line_id,booking_date,value_date,amount,currency,reference,description";

    private StatementFiles() {
    }

    /** A prefix no other test uses, so line ids and references never meet another test's in the shared database. */
    public static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static String pspLine(String lineId) {
        return lineId + ",5f0c7a1e-0000-4000-8000-000000000001,B-001,2026-09-23,2026-09-24,PAYMENT,125.00,2.50,122.50,TRY";
    }

    /** As {@link #pspLine(String)}, on the given value date, and on the day before it as its transaction date. */
    public static String pspLine(String lineId, String valueDate) {
        String transactionDate = LocalDate.parse(valueDate).minusDays(1).toString();
        return lineId + ",5f0c7a1e-0000-4000-8000-000000000001,B-001," + transactionDate + "," + valueDate
                + ",PAYMENT,125.00,2.50,122.50,TRY";
    }

    /** As {@link #bankLine(String)}, on the given value date, and booked the day before. */
    public static String bankLine(String lineId, String valueDate) {
        String bookingDate = LocalDate.parse(valueDate).minusDays(1).toString();
        return lineId + "," + bookingDate + "," + valueDate
                + ",245.00,TRY,PSP ALPHA BATCH-B-001 payout,Settlement Test Merchant 001";
    }

    public static String bankLine(String lineId) {
        return lineId + ",2026-09-24,2026-09-25,245.00,TRY,PSP ALPHA BATCH-B-001 payout,Settlement Test Merchant 001";
    }

    public static String psp(List<String> lines) {
        return PSP_HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    public static String psp(String... lines) {
        return psp(List.of(lines));
    }

    public static String bank(List<String> lines) {
        return BANK_HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    public static UploadedStatement upload(String source, String reference, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new UploadedStatement(source, reference, "statement.csv", () -> new ByteArrayInputStream(bytes),
                "operator-001");
    }
}
