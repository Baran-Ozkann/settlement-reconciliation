package com.baran.recon.adapters.in.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import com.baran.recon.domain.statement.ValidationCode;

/**
 * Splits an uploaded file into physical lines, holding no more than one line of bytes at a time
 * (FR-ING-5, FR-ING-8).
 *
 * <ul>
 *   <li>A line ends at {@code \n}; a {@code \r} right before it belongs to the ending (TDD 7). A
 *       {@code \r} anywhere else is content, which the field rules then refuse. The last line needs
 *       no ending, and a file that ends with one has no empty line after it.</li>
 *   <li>A line is at most {@code maxLineBytes} bytes, not counting its ending. A longer one is read
 *       to its end and discarded unkept, and reported as {@code LINE_TOO_LONG}, so its length costs
 *       nothing but the time to skip it.</li>
 *   <li>Every line is decoded as strict UTF-8: a malformed or unmappable byte sequence is
 *       {@code INVALID_ENCODING}, never replaced with U+FFFD, which would turn bytes the sender
 *       never meant into a line that might pass every other rule.</li>
 *   <li>A byte order mark at the very start of the file is dropped (FR-ING-8). One anywhere else is
 *       an ordinary character.</li>
 * </ul>
 */
final class LineReader {

    private static final int CHUNK = 64 * 1024;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final InputStream in;
    private final int maxLineBytes;
    private final byte[] chunk = new byte[CHUNK];
    private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    /** Room for one line, the {@code \r} that may precede its {@code \n}, and a leading BOM. */
    private final byte[] line;

    private int chunkLength;
    private int chunkPosition;
    private long lineNumber;
    private boolean endOfInput;

    LineReader(InputStream in, int maxLineBytes) {
        this.in = Objects.requireNonNull(in, "in");
        if (maxLineBytes < 1) {
            throw new IllegalArgumentException("maxLineBytes must be positive");
        }
        this.maxLineBytes = maxLineBytes;
        this.line = new byte[maxLineBytes + 1 + BOM.length];
    }

    /** The next line, or empty at the end of the file. */
    Optional<RawLine> next() throws IOException {
        int length = 0;
        boolean tooLong = false;
        boolean sawAnyByte = false;
        boolean terminated = false;
        while (!terminated) {
            if (chunkPosition == chunkLength && !fill()) {
                if (!sawAnyByte) {
                    return Optional.empty();
                }
                break;
            }
            sawAnyByte = true;
            byte b = chunk[chunkPosition++];
            if (b == '\n') {
                terminated = true;
            } else if (length < line.length) {
                line[length++] = b;
            } else {
                tooLong = true;
            }
        }
        lineNumber++;
        if (terminated && !tooLong && length > 0 && line[length - 1] == '\r') {
            length--;
        }
        int start = lineNumber == 1 && startsWithBom(length) ? BOM.length : 0;
        if (tooLong || length - start > maxLineBytes) {
            return Optional.of(RawLine.invalid(lineNumber, ValidationCode.LINE_TOO_LONG));
        }
        return Optional.of(decode(start, length));
    }

    private RawLine decode(int start, int end) {
        if (isAscii(start, end)) {
            return RawLine.text(lineNumber, new String(line, start, end - start, StandardCharsets.US_ASCII));
        }
        try {
            CharBuffer decoded = utf8.reset().decode(ByteBuffer.wrap(line, start, end - start));
            return RawLine.text(lineNumber, decoded.toString());
        } catch (CharacterCodingException malformed) {
            return RawLine.invalid(lineNumber, ValidationCode.INVALID_ENCODING);
        }
    }

    private boolean isAscii(int start, int end) {
        for (int i = start; i < end; i++) {
            if (line[i] < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithBom(int length) {
        return length >= BOM.length && line[0] == BOM[0] && line[1] == BOM[1] && line[2] == BOM[2];
    }

    private boolean fill() throws IOException {
        chunkPosition = 0;
        chunkLength = 0;
        while (!endOfInput && chunkLength == 0) {
            int read = in.read(chunk);
            if (read < 0) {
                endOfInput = true;
            } else {
                chunkLength = read;
            }
        }
        return chunkLength > 0;
    }

    /** One physical line: its number, and its text or the reason it has none. */
    record RawLine(long number, Optional<String> text, Optional<ValidationCode> error) {

        static RawLine text(long number, String text) {
            return new RawLine(number, Optional.of(text), Optional.empty());
        }

        static RawLine invalid(long number, ValidationCode code) {
            return new RawLine(number, Optional.empty(), Optional.of(code));
        }
    }
}
