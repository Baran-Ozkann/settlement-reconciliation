package com.baran.recon.adapters.in.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.adapters.in.file.LineReader.RawLine;
import com.baran.recon.domain.statement.ValidationCode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FR-ING-5, FR-ING-8: physical lines, bounded in length and decoded as strict UTF-8")
class LineReaderTest {

    private static final int MAX = 16;

    @Test
    @DisplayName("TDD 7: lines end at LF or CRLF; the last line needs no ending; a final ending adds no line")
    void lineEndings() throws IOException {
        assertThat(texts("a\nb\r\nc")).containsExactly("a", "b", "c");
        assertThat(texts("a\nb\n")).containsExactly("a", "b");
        assertThat(texts("a\r\n\r\nb")).containsExactly("a", "", "b");
    }

    @Test
    @DisplayName("an empty file has no lines at all")
    void emptyFileHasNoLines() throws IOException {
        assertThat(read(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("a CR that does not precede LF is content, at the end of the file too")
    void loneCarriageReturnIsContent() throws IOException {
        assertThat(texts("a\rb\nc\r")).containsExactly("a\rb", "c\r");
    }

    @Test
    @DisplayName("line numbers count every physical line, invalid ones included")
    void lineNumbersCountEveryLine() throws IOException {
        List<RawLine> lines = read(bytes("ok\n" + "x".repeat(MAX + 1) + "\nok\n"));

        assertThat(lines).extracting(RawLine::number).containsExactly(1L, 2L, 3L);
        assertThat(lines.get(1).error()).contains(ValidationCode.LINE_TOO_LONG);
        assertThat(lines.get(2).text()).contains("ok");
    }

    @Test
    @DisplayName("FR-ING-8: a line of exactly the limit is read; one byte more is LINE_TOO_LONG")
    void lengthLimitBoundary() throws IOException {
        assertThat(texts("x".repeat(MAX))).containsExactly("x".repeat(MAX));
        assertThat(read(bytes("x".repeat(MAX + 1))).getFirst().error()).contains(ValidationCode.LINE_TOO_LONG);
    }

    @Test
    @DisplayName("FR-ING-8: the limit counts bytes, not characters, and not the line ending")
    void lengthLimitCountsBytesWithoutTheEnding() throws IOException {
        assertThat(texts("x".repeat(MAX) + "\r\n" + "y".repeat(MAX) + "\n")).containsExactly("x".repeat(MAX), "y".repeat(MAX));
        // Eight two-byte characters are 16 bytes; nine are 18.
        assertThat(texts("ş".repeat(8))).containsExactly("ş".repeat(8));
        assertThat(read(bytes("ş".repeat(9))).getFirst().error()).contains(ValidationCode.LINE_TOO_LONG);
    }

    @Test
    @DisplayName("FR-ING-5: a line far longer than the limit is skipped, not kept, and the next line is intact")
    void hugeLineIsSkipped() throws IOException {
        List<RawLine> lines = read(bytes("z".repeat(300_000) + "\nafter"));

        assertThat(lines.getFirst().error()).contains(ValidationCode.LINE_TOO_LONG);
        assertThat(lines.get(1).text()).contains("after");
    }

    @Test
    @DisplayName("FR-ING-8: a leading BOM is dropped, and does not count towards the limit")
    void leadingBomIsDropped() throws IOException {
        byte[] withBom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes("x".repeat(MAX) + "\r\nb"));

        assertThat(read(withBom)).extracting(line -> line.text().orElseThrow()).containsExactly("x".repeat(MAX), "b");
    }

    @Test
    @DisplayName("a BOM anywhere but the start of the file is an ordinary character")
    void laterBomIsKept() throws IOException {
        byte[] content = concat(bytes("a\n"), new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes("b"));

        assertThat(texts(content)).containsExactly("a", "﻿b");
    }

    @ParameterizedTest(name = "bytes {0} are INVALID_ENCODING")
    @ValueSource(strings = {"FF", "C3", "C0AF", "EDA080", "F4908080", "E282"})
    @DisplayName("FR-ING-8: invalid, truncated, overlong, surrogate and out-of-range sequences are INVALID_ENCODING")
    void malformedUtf8IsRefused(String hex) throws IOException {
        byte[] content = concat(bytes("ok,"), java.util.HexFormat.of().parseHex(hex), bytes(",x\nnext"));

        List<RawLine> lines = read(content);

        assertThat(lines.getFirst().error()).contains(ValidationCode.INVALID_ENCODING);
        assertThat(lines.getFirst().text()).isEmpty();
        assertThat(lines.get(1).text()).contains("next");
    }

    @Test
    @DisplayName("valid multi-byte UTF-8 decodes, even when every read returns a single byte")
    void multiByteAcrossReads() throws IOException {
        byte[] content = bytes("Ödeme şubesi €\r\nİkinci satır");
        List<RawLine> lines = new ArrayList<>();
        LineReader reader = new LineReader(oneByteAtATime(content), 64);
        for (Optional<RawLine> line = reader.next(); line.isPresent(); line = reader.next()) {
            lines.add(line.get());
        }

        assertThat(lines).extracting(line -> line.text().orElseThrow()).containsExactly("Ödeme şubesi €", "İkinci satır");
    }

    private static List<String> texts(String content) throws IOException {
        return texts(bytes(content));
    }

    private static List<String> texts(byte[] content) throws IOException {
        return read(content).stream().map(line -> line.text().orElseThrow()).toList();
    }

    private static List<RawLine> read(byte[] content) throws IOException {
        LineReader reader = new LineReader(new ByteArrayInputStream(content), MAX);
        List<RawLine> lines = new ArrayList<>();
        for (Optional<RawLine> line = reader.next(); line.isPresent(); line = reader.next()) {
            lines.add(line.get());
        }
        return lines;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static InputStream oneByteAtATime(byte[] content) {
        return new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(1, length));
            }
        };
    }
}
