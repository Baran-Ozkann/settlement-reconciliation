package com.baran.recon.adapters.in.file;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.statement.ValidationCode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TDD 7: fields by the RFC 4180 subset, refused rather than read generously")
class CsvFieldsTest {

    @Test
    @DisplayName("plain fields split on commas; empty fields are kept, at either end too")
    void plainFields() {
        assertThat(CsvFields.split("a,b,c", 3).fields()).containsExactly("a", "b", "c");
        assertThat(CsvFields.split(",b,", 3).fields()).containsExactly("", "b", "");
    }

    @Test
    @DisplayName("inside quotes a comma is text and a doubled quote is one quote")
    void quotedFields() {
        assertThat(CsvFields.split("\"a,1\",\"say \"\"hi\"\"\",\"\"", 3).fields())
                .containsExactly("a,1", "say \"hi\"", "");
    }

    @ParameterizedTest(name = "[{0}] has the wrong number of fields")
    @ValueSource(strings = {"a,b", "a,b,c,d", "", "\"a,b\",c"})
    @DisplayName("COLUMN_COUNT: a line with more or fewer fields than the layout")
    void wrongColumnCount(String line) {
        assertThat(CsvFields.split(line, 3).error()).contains(ValidationCode.COLUMN_COUNT);
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = {"a,b\"c,d", "\"open,b,c", "\"a\"x,b,c", "a,\"b\" ,c", "a,b,\"c"})
    @DisplayName("INVALID_FORMAT: a stray quote, text after a closing quote, or a quote never closed")
    void malformedQuoting(String line) {
        CsvFields.Split split = CsvFields.split(line, 3);

        assertThat(split.error()).contains(ValidationCode.INVALID_FORMAT);
        assertThat(split.fields()).isEmpty();
    }

    @Test
    @DisplayName("a valid split has no error")
    void validSplitHasNoError() {
        assertThat(CsvFields.split("a,b,c", 3).error()).isEmpty();
        assertThat(CsvFields.split("a", 1).fields()).isEqualTo(List.of("a"));
    }
}
