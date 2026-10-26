package ru.vasilyev.mirea.vkgraph.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentConfigTest {
    @Test
    void readsPositiveIntegerFromDotEnv(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "VK_MAX_LEVEL2_PARENTS=250\n");

        assertThat(new EnvironmentConfig().getPositiveInt(file, "VK_MAX_LEVEL2_PARENTS", 100)).isEqualTo(250);
    }

    @Test
    void rejectsInvalidPositiveInteger(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "VK_MAX_LEVEL2_PARENTS=0\n");

        assertThatThrownBy(() -> new EnvironmentConfig().getPositiveInt(file, "VK_MAX_LEVEL2_PARENTS", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VK_MAX_LEVEL2_PARENTS");
    }

    @Test
    void supportsUnlimitedValue(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "VK_MAX_LEVEL2_PARENTS=all\n");

        assertThat(new EnvironmentConfig().getPositiveIntOrUnlimited(
                file, "VK_MAX_LEVEL2_PARENTS", 100)).isEqualTo(Integer.MAX_VALUE);
    }
}
