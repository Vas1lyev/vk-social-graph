package ru.vasilyev.mirea.vkgraph.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

public final class SeedInputReader {
    private final VkUrlParser parser = new VkUrlParser();

    public List<String> read(Path path) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String firstCell = line.split(",", 2)[0].strip();
            if (firstCell.isBlank() || firstCell.equalsIgnoreCase("vk_url") || firstCell.startsWith("Ссылка")) {
                continue;
            }
            try {
                result.add(parser.parse(firstCell));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Ошибка в строке " + lineNumber + ": " + error.getMessage(), error);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Во входном файле нет VK-ссылок: " + path);
        }
        return List.copyOf(result);
    }
}
