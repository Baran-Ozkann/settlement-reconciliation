package com.baran.recon.application.port;

import java.util.Objects;

import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.statement.LineError;

/** One data line of a statement, as its parser read it: a valid line of its format, or why it is not one. */
public sealed interface ParsedLine {

    long lineNumber();

    record Psp(long lineNumber, PspLine line) implements ParsedLine {

        public Psp {
            Objects.requireNonNull(line, "line");
        }
    }

    record Bank(long lineNumber, BankLine line) implements ParsedLine {

        public Bank {
            Objects.requireNonNull(line, "line");
        }
    }

    record Invalid(LineError error) implements ParsedLine {

        public Invalid {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public long lineNumber() {
            return error.lineNumber();
        }
    }
}
