package ru.vasilyev.mirea.vkgraph.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vasilyev.mirea.vkgraph.vk.VkApiClient;
import ru.vasilyev.mirea.vkgraph.vk.VkApiException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class FriendCache {
    private static final Logger log = LoggerFactory.getLogger(FriendCache.class);
    private final Path directory;
    private final long ttlMillis;
    private final ObjectMapper json = new ObjectMapper();

    public FriendCache(Path directory, Duration ttl) {
        this.directory = directory;
        this.ttlMillis = ttl.toMillis();
    }

    public Optional<Entry> load(long userId) {
        Path file = file(userId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Entry entry = json.readValue(file.toFile(), Entry.class);
            if (entry.userId() != userId
                    || System.currentTimeMillis() - entry.fetchedAtEpochMs() > ttlMillis) {
                return Optional.empty();
            }
            return Optional.of(entry);
        } catch (IOException | RuntimeException error) {
            log.warn("Не удалось прочитать кэш {}: {}", file, error.getMessage());
            return Optional.empty();
        }
    }

    public void save(VkApiClient.FriendsBatchResult batch) {
        batch.friends().forEach(this::saveSuccess);
        batch.errors().forEach((userId, error) -> {
            if (isPermanent(error.code())) {
                saveError(userId, error);
            }
        });
    }

    public void saveSuccess(long userId, Set<Long> friendIds) {
        List<Long> sorted = friendIds.stream().sorted().toList();
        save(new Entry(userId, "SUCCESS", sorted, 0, "", System.currentTimeMillis()));
    }

    public void saveError(long userId, VkApiException error) {
        if (isPermanent(error.code())) {
            save(new Entry(userId, "ERROR", List.of(), error.code(), error.getMessage(),
                    System.currentTimeMillis()));
        }
    }

    private synchronized void save(Entry entry) {
        try {
            Files.createDirectories(directory);
            Path target = file(entry.userId());
            Path temporary = Files.createTempFile(directory, entry.userId() + ".", ".tmp");
            json.writeValue(temporary.toFile(), entry);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log.warn("Не удалось записать кэш для {}: {}", entry.userId(), error.getMessage());
        }
    }

    private Path file(long userId) {
        return directory.resolve(userId + ".json");
    }

    private static boolean isPermanent(int code) {
        return code == 15 || code == 18 || code == 30 || code == 113;
    }

    public record Entry(long userId, String status, List<Long> friendIds,
                        int errorCode, String errorMessage, long fetchedAtEpochMs) {
        public boolean success() {
            return "SUCCESS".equals(status);
        }

        public Set<Long> friendIdSet() {
            return Set.copyOf(friendIds == null ? List.of() : friendIds);
        }

        public VkApiException error() {
            return new VkApiException(errorCode, errorMessage == null ? "Ошибка из кэша" : errorMessage);
        }
    }
}
