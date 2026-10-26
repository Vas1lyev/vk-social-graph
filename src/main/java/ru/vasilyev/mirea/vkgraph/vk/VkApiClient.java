package ru.vasilyev.mirea.vkgraph.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class VkApiClient {
    private static final Logger log = LoggerFactory.getLogger(VkApiClient.class);
    private static final URI API = URI.create("https://api.vk.ru/method/");

    private final String token;
    private final String version;
    private final Duration timeout;
    private final long delayMs;
    private final int maxRetries;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private long nextRequestAt;

    public VkApiClient(String token, String version, Duration timeout, long delayMs, int maxRetries) {
        this.token = token;
        this.version = version;
        this.timeout = timeout;
        this.delayMs = Math.max(0, delayMs);
        this.maxRetries = Math.max(0, maxRetries);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public List<UserProfile> resolveUsers(Collection<String> screenNames) {
        if (screenNames.isEmpty()) {
            return List.of();
        }
        JsonNode response = call("users.get", Map.of(
                "user_ids", String.join(",", screenNames),
                "fields", "screen_name,domain"
        ));
        List<UserProfile> profiles = new ArrayList<>();
        for (JsonNode item : response) {
            long id = item.path("id").asLong();
            String screenName = item.path("screen_name").asText(item.path("domain").asText("id" + id));
            String name = (item.path("first_name").asText("") + " " + item.path("last_name").asText("")).strip();
            profiles.add(new UserProfile(id, screenName, name.isBlank() ? "id" + id : name, false, 2));
        }
        return profiles;
    }

    public Set<Long> getAllFriendIds(long userId) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        int offset = 0;
        while (true) {
            JsonNode response = call("friends.get", Map.of(
                    "user_id", Long.toString(userId),
                    "count", "5000",
                    "offset", Integer.toString(offset)
            ));
            JsonNode items = response.path("items");
            for (JsonNode item : items) {
                result.add(item.asLong());
            }
            int total = response.path("count").asInt(result.size());
            if (!items.isArray() || items.isEmpty() || result.size() >= total) {
                return Set.copyOf(result);
            }
            offset += items.size();
        }
    }

    public FriendsBatchResult getFriendIdsBatch(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return new FriendsBatchResult(Map.of(), Map.of(), 0);
        }
        if (userIds.size() > 25) {
            throw new IllegalArgumentException("execute поддерживает пакет не более 25 вызовов");
        }
        if (userIds.size() == 1) {
            return fetchIndividually(userIds);
        }

        JsonNode response;
        try {
            response = call("execute", Map.of("code", buildFriendsBatchCode(userIds)));
        } catch (VkApiException batchError) {
            if (batchError.code() == 5 || batchError.code() == 6 || batchError.code() == 9
                    || batchError.code() == 27 || batchError.code() == 28 || batchError.code() == 29) {
                throw batchError;
            }
            log.warn("Пакетный execute недоступен (VK {}), переключаю пакет на обычные запросы",
                    batchError.code());
            return fetchIndividually(userIds);
        }

        Map<Long, Set<Long>> friends = new LinkedHashMap<>();
        Map<Long, VkApiException> errors = new LinkedHashMap<>();
        for (int index = 0; index < userIds.size(); index++) {
            long userId = userIds.get(index);
            JsonNode item = response.path(index);
            if (item.isObject() && item.path("items").isArray()) {
                LinkedHashSet<Long> ids = new LinkedHashSet<>();
                item.path("items").forEach(id -> ids.add(id.asLong()));
                int total = item.path("count").asInt(ids.size());
                if (ids.size() >= total) {
                    friends.put(userId, Set.copyOf(ids));
                    continue;
                }
            }
            fetchOne(userId, friends, errors);
        }
        return new FriendsBatchResult(Map.copyOf(friends), Map.copyOf(errors), userIds.size());
    }

    static String buildFriendsBatchCode(List<Long> userIds) {
        return "return [" + userIds.stream()
                .map(id -> "API.friends.get({\"user_id\":" + id + ",\"count\":5000})")
                .collect(Collectors.joining(",")) + "];";
    }

    private FriendsBatchResult fetchIndividually(List<Long> userIds) {
        Map<Long, Set<Long>> friends = new LinkedHashMap<>();
        Map<Long, VkApiException> errors = new LinkedHashMap<>();
        userIds.forEach(userId -> fetchOne(userId, friends, errors));
        return new FriendsBatchResult(Map.copyOf(friends), Map.copyOf(errors), userIds.size());
    }

    private void fetchOne(long userId, Map<Long, Set<Long>> friends, Map<Long, VkApiException> errors) {
        try {
            friends.put(userId, getAllFriendIds(userId));
        } catch (VkApiException error) {
            errors.put(userId, error);
        }
    }

    private JsonNode call(String method, Map<String, String> parameters) {
        Map<String, String> form = new LinkedHashMap<>(parameters);
        form.put("access_token", token);
        form.put("v", version);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            waitForSlot();
            log.debug("VK API {}: попытка {}/{}", method, attempt + 1, maxRetries + 1);
            HttpRequest request = HttpRequest.newBuilder(API.resolve(method))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            try {
                HttpResponse<String> httpResponse = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (httpResponse.statusCode() == 429 || httpResponse.statusCode() >= 500) {
                    if (attempt < maxRetries) {
                        scheduleBackoff(attempt, httpResponse.statusCode() == 429 ? 2_000L : 500L);
                        continue;
                    }
                    throw new VkApiException(httpResponse.statusCode(), "HTTP " + httpResponse.statusCode());
                }
                JsonNode root = json.readTree(httpResponse.body());
                JsonNode error = root.get("error");
                if (error != null) {
                    int code = error.path("error_code").asInt(-1);
                    if (isRateLimit(code) && attempt < maxRetries) {
                        log.warn("VK API {}: ограничение {}, общий cooldown перед повтором {}/{}",
                                method, code, attempt + 1, maxRetries);
                        scheduleBackoff(attempt, code == 9 ? 30_000L : 500L);
                        continue;
                    }
                    throw new VkApiException(code, error.path("error_msg").asText("Неизвестная ошибка VK API"));
                }
                JsonNode response = root.get("response");
                if (response == null) {
                    throw new VkApiException(-1, "VK API не вернул поле response");
                }
                JsonNode executeErrors = root.path("execute_errors");
                VkApiException executeRateLimit = firstRateLimit(executeErrors);
                if (executeRateLimit != null) {
                    if (attempt < maxRetries) {
                        log.warn("VK API execute: внутреннее ограничение {}, общий cooldown перед повтором {}/{}",
                                executeRateLimit.code(), attempt + 1, maxRetries);
                        scheduleBackoff(attempt, executeRateLimit.code() == 9 ? 30_000L : 500L);
                        continue;
                    }
                    throw executeRateLimit;
                }
                return response;
            } catch (IOException error) {
                if (attempt >= maxRetries) {
                    throw new VkApiException(-1, "Сетевая ошибка: " + error.getMessage());
                }
                scheduleBackoff(attempt, 500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new VkApiException(-1, "Запрос прерван");
            }
        }
        throw new IllegalStateException("Недостижимый код");
    }

    private synchronized void waitForSlot() {
        long wait = nextRequestAt - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        nextRequestAt = System.currentTimeMillis() + delayMs;
    }

    private synchronized void scheduleBackoff(int attempt, long baseMs) {
        long pause = Math.min(60_000L, baseMs * (1L << Math.min(attempt, 5)));
        nextRequestAt = Math.max(nextRequestAt, System.currentTimeMillis() + pause);
    }

    private static boolean isRateLimit(int code) {
        return code == 6 || code == 9 || code == 29;
    }

    private static VkApiException firstRateLimit(JsonNode errors) {
        if (!errors.isArray()) {
            return null;
        }
        for (JsonNode error : errors) {
            int code = error.path("error_code").asInt(-1);
            if (isRateLimit(code)) {
                return new VkApiException(code, error.path("error_msg").asText("Ограничение VK API"));
            }
        }
        return null;
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new VkApiException(-1, "Ожидание прервано");
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    public record FriendsBatchResult(Map<Long, Set<Long>> friends,
                                     Map<Long, VkApiException> errors,
                                     int requestedCount) {
    }
}
