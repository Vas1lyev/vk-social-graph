package ru.vasilyev.mirea.vkgraph.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TokenProviderTest {
    @Test
    void readsQuotedTokenFromDotEnv(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "# local secret\nVK_ACCESS_TOKEN='test-token'\n");

        assertThat(new TokenProvider().load(file)).isEqualTo("test-token");
    }
}
