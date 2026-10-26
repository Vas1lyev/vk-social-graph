package ru.vasilyev.mirea.vkgraph.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.vasilyev.mirea.vkgraph.vk.VkApiException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FriendCacheTest {
    @Test
    void persistsSuccessfulFriendList(@TempDir Path directory) {
        FriendCache cache = new FriendCache(directory, Duration.ofHours(24));

        cache.saveSuccess(10L, Set.of(30L, 20L));

        FriendCache.Entry restored = cache.load(10L).orElseThrow();
        assertThat(restored.success()).isTrue();
        assertThat(restored.friendIdSet()).containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    void persistsPermanentErrorButNotAuthorizationError(@TempDir Path directory) {
        FriendCache cache = new FriendCache(directory, Duration.ofHours(24));

        cache.saveError(10L, new VkApiException(30, "private"));
        cache.saveError(20L, new VkApiException(5, "expired"));

        assertThat(cache.load(10L)).isPresent();
        assertThat(cache.load(20L)).isEmpty();
    }
}
