package ru.vasilyev.mirea.vkgraph.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vasilyev.mirea.vkgraph.cache.FriendCache;
import ru.vasilyev.mirea.vkgraph.domain.Edge;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;
import ru.vasilyev.mirea.vkgraph.vk.VkApiClient;
import ru.vasilyev.mirea.vkgraph.vk.VkApiException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GraphCrawler {
    private static final Logger log = LoggerFactory.getLogger(GraphCrawler.class);
    private final VkApiClient client;
    private final FriendCache cache;

    public GraphCrawler(VkApiClient client, FriendCache cache) {
        this.client = client;
        this.cache = cache;
    }

    public CrawlResult crawl(List<String> seedInputs, int depth, int maxLevel2Parents,
                             int maxTotalUsers, int level2Workers, int executeBatchSize) {
        List<UserProfile> resolved = client.resolveUsers(seedInputs);
        Map<Long, UserProfile> profiles = new LinkedHashMap<>();
        List<Long> seedIds = new ArrayList<>();
        Set<String> resolvedNames = new LinkedHashSet<>();
        for (UserProfile profile : resolved) {
            UserProfile seed = profile.asSeed();
            profiles.put(seed.id(), seed);
            seedIds.add(seed.id());
            resolvedNames.add(seed.screenName().toLowerCase());
            resolvedNames.add("id" + seed.id());
        }
        List<String> unresolved = seedInputs.stream()
                .filter(value -> !resolvedNames.contains(value.toLowerCase()))
                .toList();

        Set<Edge> edges = new TreeSet<>();
        Map<Long, Set<Long>> seedFriends = new LinkedHashMap<>();
        Map<Long, String> statuses = new LinkedHashMap<>();
        LinkedHashSet<Long> level2Parents = new LinkedHashSet<>();
        boolean complete = unresolved.isEmpty();

        for (long seedId : seedIds) {
            try {
                FriendCache.Entry cached = cache.load(seedId).orElse(null);
                if (cached != null && !cached.success()) {
                    complete = false;
                    statuses.put(seedId, status(cached.error()));
                    log.info("Исходный пользователь {} восстановлен из кэша: VK {}",
                            seedId, cached.errorCode());
                    continue;
                }
                Set<Long> friends;
                if (cached != null) {
                    friends = cached.friendIdSet();
                    log.info("Друзья исходного пользователя {} восстановлены из кэша", seedId);
                } else {
                    log.info("Загружаю друзей исходного пользователя {}", seedId);
                    friends = client.getAllFriendIds(seedId);
                    cache.saveSuccess(seedId, friends);
                }
                seedFriends.put(seedId, friends);
                statuses.put(seedId, "SUCCESS");
                for (long friendId : friends) {
                    if (profiles.size() >= maxTotalUsers && !profiles.containsKey(friendId)) {
                        complete = false;
                        continue;
                    }
                    profiles.putIfAbsent(friendId, placeholder(friendId, 1));
                    if (friendId != seedId) {
                        edges.add(new Edge(seedId, friendId));
                    }
                    if (!seedIds.contains(friendId)) {
                        level2Parents.add(friendId);
                    }
                }
            } catch (VkApiException error) {
                complete = false;
                statuses.put(seedId, status(error));
                cache.saveError(seedId, error);
                log.warn("Не удалось получить друзей {}: VK {} {}", seedId, error.code(), error.getMessage());
            }
        }

        enrichSharedAcquaintances(profiles, seedFriends, seedIds);

        if (depth == 2) {
            List<Long> parents = level2Parents.stream().sorted().limit(maxLevel2Parents).toList();
            if (level2Parents.size() > parents.size()) {
                complete = false;
            }
            List<Long> pendingParents = new ArrayList<>();
            int done = 0;
            int succeeded = 0;
            int failed = 0;
            for (long parentId : parents) {
                FriendCache.Entry cached = cache.load(parentId).orElse(null);
                if (cached == null) {
                    pendingParents.add(parentId);
                    continue;
                }
                done++;
                if (cached.success()) {
                    succeeded++;
                    statuses.put(parentId, "SUCCESS");
                    if (!mergeLevel2Friends(parentId, cached.friendIdSet(), profiles, edges, maxTotalUsers)) {
                        complete = false;
                    }
                } else {
                    failed++;
                    complete = false;
                    statuses.put(parentId, status(cached.error()));
                }
            }
            List<List<Long>> batches = new ArrayList<>();
            for (int start = 0; start < pendingParents.size(); start += executeBatchSize) {
                batches.add(pendingParents.subList(
                        start, Math.min(pendingParents.size(), start + executeBatchSize)));
            }
            log.info("L2: {} пользователей; из кэша: {}; осталось: {}; пакетов: {}; размер пакета: {}; потоков: {}",
                    parents.size(), done, pendingParents.size(), batches.size(), executeBatchSize, level2Workers);
            if (done > 0) {
                log.info("Восстановлено из checkpoint: {}/{}; успешно: {}; ошибок: {}",
                        done, parents.size(), succeeded, failed);
            }
            if (!batches.isEmpty()) {
                ExecutorService executor = Executors.newFixedThreadPool(level2Workers);
                CompletionService<VkApiClient.FriendsBatchResult> completion =
                        new ExecutorCompletionService<>(executor);
                batches.forEach(batchIds -> completion.submit(() -> {
                    VkApiClient.FriendsBatchResult result = client.getFriendIdsBatch(batchIds);
                    cache.save(result);
                    return result;
                }));
                int lastLogged = done;
                try {
                    for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                        VkApiClient.FriendsBatchResult batch = completion.take().get();
                        for (var entry : batch.friends().entrySet()) {
                            long parentId = entry.getKey();
                            statuses.put(parentId, "SUCCESS");
                            if (!mergeLevel2Friends(parentId, entry.getValue(), profiles, edges, maxTotalUsers)) {
                                complete = false;
                            }
                        }
                        for (var entry : batch.errors().entrySet()) {
                            complete = false;
                            statuses.put(entry.getKey(), status(entry.getValue()));
                            log.debug("Недоступны друзья {}: VK {} {}", entry.getKey(),
                                    entry.getValue().code(), entry.getValue().getMessage());
                        }
                        succeeded += batch.friends().size();
                        failed += batch.errors().size();
                        done += batch.requestedCount();
                        if (done - lastLogged >= 50 || done == parents.size()) {
                            log.info("Обработано родителей L2: {}/{}; успешно: {}; ошибок: {}",
                                    done, parents.size(), succeeded, failed);
                            lastLogged = done;
                        }
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new VkApiException(-1, "Обход второго уровня прерван");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("Ошибка обхода второго уровня", cause);
                } finally {
                    executor.shutdownNow();
                }
            }
        }

        Map<Long, UserProfile> sortedProfiles = profiles.values().stream()
                .sorted(Comparator.comparingLong(UserProfile::id))
                .collect(LinkedHashMap::new, (map, value) -> map.put(value.id(), value), Map::putAll);
        return new CrawlResult(Map.copyOf(sortedProfiles), Set.copyOf(edges), List.copyOf(seedIds),
                Map.copyOf(seedFriends), Map.copyOf(statuses), unresolved, complete);
    }

    private static UserProfile placeholder(long id, int level) {
        return new UserProfile(id, "id" + id, "id" + id, false, level);
    }

    private static boolean mergeLevel2Friends(long parentId, Set<Long> friends,
                                               Map<Long, UserProfile> profiles, Set<Edge> edges,
                                               int maxTotalUsers) {
        boolean complete = true;
        for (long friendId : friends) {
            if (profiles.size() >= maxTotalUsers && !profiles.containsKey(friendId)) {
                complete = false;
                continue;
            }
            profiles.putIfAbsent(friendId, placeholder(friendId, 2));
            if (friendId != parentId) {
                edges.add(new Edge(parentId, friendId));
            }
        }
        return complete;
    }

    private void enrichSharedAcquaintances(Map<Long, UserProfile> profiles,
                                           Map<Long, Set<Long>> seedFriends,
                                           List<Long> seedIds) {
        Map<Long, Integer> frequency = new HashMap<>();
        seedFriends.values().forEach(ids -> ids.forEach(id -> frequency.merge(id, 1, Integer::sum)));
        Set<Long> seedSet = Set.copyOf(seedIds);
        List<String> sharedIds = frequency.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2 && !seedSet.contains(entry.getKey()))
                .map(entry -> Long.toString(entry.getKey()))
                .sorted()
                .toList();
        for (int start = 0; start < sharedIds.size(); start += 1000) {
            List<String> batch = sharedIds.subList(start, Math.min(sharedIds.size(), start + 1000));
            try {
                for (UserProfile resolved : client.resolveUsers(batch)) {
                    UserProfile existing = profiles.get(resolved.id());
                    int level = existing == null ? 1 : existing.level();
                    profiles.put(resolved.id(), new UserProfile(resolved.id(), resolved.screenName(),
                            resolved.displayName(), false, level));
                }
            } catch (VkApiException error) {
                log.warn("Не удалось получить имена общих знакомых: VK {} {}", error.code(), error.getMessage());
                return;
            }
        }
    }

    private static String status(VkApiException error) {
        return switch (error.code()) {
            case 5, 27, 28 -> "AUTH_ERROR";
            case 15, 30 -> "PRIVATE";
            case 18 -> "DEACTIVATED";
            case 6, 9, 29 -> "RATE_LIMITED";
            case 113 -> "NOT_FOUND";
            default -> "TEMPORARY_ERROR";
        };
    }
}
