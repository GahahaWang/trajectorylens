package dev.soityy.trajectorylens;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LangFormatTest {

    private static final Pattern REWRITTEN_BY_VANILLA = Pattern.compile("%(\\d+\\$)?[\\d.]*f");

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn", "zh_tw"})
    void noDecimalSpecifiers(String locale) throws IOException {
        try (InputStream in = LangFormatTest.class.getResourceAsStream("/assets/trajectorylens/lang/" + locale + ".json")) {
            assertNotNull(in, locale);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = REWRITTEN_BY_VANILLA.matcher(text);
            assertFalse(m.find(), () -> locale + ": " + m.group());
        }
    }
}
