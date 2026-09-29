package com.baran.recon.domain.statement;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.CharRange;
import net.jqwik.api.constraints.Chars;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;

@Label("FR-ING-9: whatever name arrives, only the allow-list is ever stored")
class SanitizedFilenamePropertiesTest {

    private static final String SEED = "20260929";

    @Property(seed = SEED, tries = 2000)
    @Label("any text sanitizes to 1-100 characters of [A-Za-z0-9._-], with no separator left")
    void resultIsAlwaysInStoredForm(@ForAll @StringLength(max = 400) String original) {
        String sanitized = SanitizedFilename.of(original);

        assertThat(sanitized).matches("[A-Za-z0-9._-]{1,100}");
        assertThat(SanitizedFilename.isSanitized(sanitized)).isTrue();
    }

    @Property(seed = SEED)
    @Label("sanitizing a sanitized name changes nothing")
    void sanitizingIsIdempotent(@ForAll @StringLength(max = 400) String original) {
        String once = SanitizedFilename.of(original);

        assertThat(SanitizedFilename.of(once)).isEqualTo(once);
    }

    @Property(seed = SEED)
    @Label("a name already in stored form is kept as it is")
    void storedFormIsKept(@ForAll @StringLength(min = 1, max = 100)
                          @CharRange(from = 'a', to = 'z') @CharRange(from = 'A', to = 'Z')
                          @CharRange(from = '0', to = '9') @Chars({'.', '_', '-'}) String name) {
        assertThat(SanitizedFilename.of(name)).isEqualTo(name);
    }
}
