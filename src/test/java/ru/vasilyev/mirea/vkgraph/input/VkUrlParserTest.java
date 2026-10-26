package ru.vasilyev.mirea.vkgraph.input;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VkUrlParserTest {
    private final VkUrlParser parser = new VkUrlParser();

    @Test
    void parsesSupportedUrls() {
        assertThat(parser.parse("https://vk.ru/example?from=search#x")).isEqualTo("example");
        assertThat(parser.parse("https://m.vk.com/id123/")).isEqualTo("id123");
        assertThat(parser.parse("vk.com/name.surname")).isEqualTo("name.surname");
    }

    @Test
    void rejectsForeignHost() {
        assertThatThrownBy(() -> parser.parse("https://example.com/user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимый хост");
    }
}
