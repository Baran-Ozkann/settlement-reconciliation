package com.baran.recon.domain.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FR-ING-9: an uploaded file name is sanitized before it is stored or logged")
class SanitizedFilenameTest {

    @ParameterizedTest(name = "\"{0}\" becomes \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "psp-2026-09-24.csv|psp-2026-09-24.csv",
            "../../etc/passwd|passwd",
            "..\\..\\Windows\\win.ini|win.ini",
            "C:\\Users\\someone\\statement.csv|statement.csv",
            "name with spaces.csv|name_with_spaces.csv",
            "ödeme_raporu.csv|_deme_raporu.csv",
            "semi;colon|semi_colon",
            "..|..",
    })
    void examples(String original, String sanitized) {
        assertThat(SanitizedFilename.of(original)).isEqualTo(sanitized);
    }

    @Test
    @DisplayName("control characters that could forge a log line become underscores")
    void controlCharactersAreReplaced() {
        assertThat(SanitizedFilename.of("a\r\nINFO forged\tline\u0000.csv")).isEqualTo("a__INFO_forged_line_.csv");
    }

    @Test
    @DisplayName("a code point outside the basic plane becomes one underscore, not two")
    void supplementaryCodePointIsOneCharacter() {
        assertThat(SanitizedFilename.of("report\uD83D\uDCC4.csv")).isEqualTo("report_.csv");
    }

    @Test
    @DisplayName("a name longer than 100 characters is cut to 100")
    void longNameIsCut() {
        assertThat(SanitizedFilename.of("a".repeat(250))).isEqualTo("a".repeat(SanitizedFilename.MAX_LENGTH));
    }

    @Test
    @DisplayName("no name, an empty name, or a name that is only a directory becomes \"unnamed\"")
    void nothingLeftIsUnnamed() {
        assertThat(SanitizedFilename.of(null)).isEqualTo(SanitizedFilename.UNNAMED);
        assertThat(SanitizedFilename.of("")).isEqualTo(SanitizedFilename.UNNAMED);
        assertThat(SanitizedFilename.of("uploads/")).isEqualTo(SanitizedFilename.UNNAMED);
    }
}
