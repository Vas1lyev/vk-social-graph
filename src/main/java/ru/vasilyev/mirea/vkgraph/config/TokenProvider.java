package ru.vasilyev.mirea.vkgraph.config;

import java.io.IOException;
import java.nio.file.Path;

public final class TokenProvider {
    public String load(Path envFile) throws IOException {
        String token = new EnvironmentConfig().get(envFile, "VK_ACCESS_TOKEN");
        return "replace_with_your_vk_token".equals(token) ? null : token;
    }
}
