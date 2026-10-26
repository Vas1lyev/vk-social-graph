package ru.vasilyev.mirea.vkgraph.input;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class VkUrlParser {
    private static final Set<String> HOSTS = Set.of("vk.com", "vk.ru", "m.vk.com", "m.vk.ru");

    public String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Пустая ссылка VK");
        }
        String value = raw.strip().replace("\uFEFF", "");
        URI uri = URI.create(value.contains("://") ? value : "https://" + value);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOSTS.contains(host)) {
            throw new IllegalArgumentException("Недопустимый хост VK: " + host);
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/+", "");
        String screenName = path.split("/", 2)[0];
        if (screenName.isBlank()) {
            throw new IllegalArgumentException("В ссылке отсутствует идентификатор профиля: " + raw);
        }
        return screenName;
    }
}
