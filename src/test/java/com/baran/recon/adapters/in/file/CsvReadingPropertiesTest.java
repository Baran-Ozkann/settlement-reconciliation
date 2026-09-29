package com.baran.recon.adapters.in.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.CharRange;
import net.jqwik.api.constraints.Chars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import com.baran.recon.adapters.in.file.LineReader.RawLine;

import static org.assertj.core.api.Assertions.assertThat;

@Label("TDD 7, FR-ING-8: the hand-written line and field readers, over generated input")
class CsvReadingPropertiesTest {

    private static final String SEED = "20260929";

    @Property(seed = SEED, tries = 2000)
    @Label("fields written with RFC 4180 quoting split back into exactly the same fields")
    void quotedFieldsRoundTrip(@ForAll @Size(min = 1, max = 12) List<@StringLength(max = 20)
            @CharRange(from = ' ', to = '~') @Chars({',', '"', 'ş', '€'}) String> fields) {
        String line = fields.stream().map(CsvReadingPropertiesTest::quoted).collect(Collectors.joining(","));

        CsvFields.Split split = CsvFields.split(line, fields.size());

        assertThat(split.error()).isEmpty();
        assertThat(split.fields()).isEqualTo(fields);
    }

    @Property(seed = SEED, tries = 2000)
    @Label("any text splits into fields or a code, and never throws")
    void anyTextSplitsOrIsRefused(@ForAll @StringLength(max = 200) String line) {
        CsvFields.Split split = CsvFields.split(line, 4);

        assertThat(split.error().isPresent()).isNotEqualTo(split.fields().size() == 4);
    }

    @Property(seed = SEED, tries = 1000)
    @Label("any bytes become numbered lines, each with text or a code, and nothing throws")
    void anyBytesBecomeLines(@ForAll @Size(max = 600) byte[] content) throws IOException {
        LineReader reader = new LineReader(new ByteArrayInputStream(content), 32);
        long expected = 1;
        for (Optional<RawLine> line = reader.next(); line.isPresent(); line = reader.next()) {
            assertThat(line.get().number()).isEqualTo(expected++);
            assertThat(line.get().text().isPresent()).isNotEqualTo(line.get().error().isPresent());
            line.get().text().ifPresent(text -> assertThat(text).doesNotContain("\n"));
        }
    }

    private static String quoted(String field) {
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
