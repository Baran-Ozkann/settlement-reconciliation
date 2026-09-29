package com.baran.recon.adapters.in.file;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.baran.recon.domain.statement.ValidationCode;

/**
 * Splits one line into fields by the part of RFC 4180 that TDD 7 allows. Fields are separated by
 * commas. A field may be enclosed in double quotes, and inside quotes a comma is text and {@code ""}
 * is one quote. Anything else is refused rather than read generously:
 * <ul>
 *   <li>a quote inside an unquoted field, text after a closing quote, or a quote left open is
 *       {@code INVALID_FORMAT};</li>
 *   <li>a line that is not exactly the layout's number of fields is {@code COLUMN_COUNT}.</li>
 * </ul>
 * A record is one physical line: a line break inside quotes cannot occur here, because the line
 * reader has already ended the line there, and the quote it leaves open is refused.
 */
final class CsvFields {

    private CsvFields() {
    }

    /** The fields, or the code that refuses the line. */
    static Split split(String line, int columns) {
        List<String> fields = new ArrayList<>(columns);
        StringBuilder field = new StringBuilder();
        int i = 0;
        int length = line.length();
        while (true) {
            field.setLength(0);
            if (i < length && line.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < length) {
                    char c = line.charAt(i++);
                    if (c != '"') {
                        field.append(c);
                    } else if (i < length && line.charAt(i) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        closed = true;
                        break;
                    }
                }
                if (!closed || i < length && line.charAt(i) != ',') {
                    return Split.refused(ValidationCode.INVALID_FORMAT);
                }
            } else {
                while (i < length && line.charAt(i) != ',') {
                    char c = line.charAt(i++);
                    if (c == '"') {
                        return Split.refused(ValidationCode.INVALID_FORMAT);
                    }
                    field.append(c);
                }
            }
            fields.add(field.toString());
            if (i >= length) {
                break;
            }
            i++; // the comma
        }
        return fields.size() == columns ? Split.of(fields) : Split.refused(ValidationCode.COLUMN_COUNT);
    }

    /** Either the line's fields or why it has none. */
    record Split(List<String> fields, Optional<ValidationCode> error) {

        static Split of(List<String> fields) {
            return new Split(List.copyOf(fields), Optional.empty());
        }

        static Split refused(ValidationCode code) {
            return new Split(List.of(), Optional.of(code));
        }
    }
}
