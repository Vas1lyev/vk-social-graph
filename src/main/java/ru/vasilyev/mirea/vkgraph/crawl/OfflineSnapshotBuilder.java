package ru.vasilyev.mirea.vkgraph.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vasilyev.mirea.vkgraph.cache.FriendCache;
import ru.vasilyev.mirea.vkgraph.domain.Edge;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class OfflineSnapshotBuilder {
    private static final Logger log = LoggerFactory.getLogger(OfflineSnapshotBuilder.class);
    private final ObjectMapper json = new ObjectMapper();

    public Path findLatestNodesFile(Path outputRoot) throws IOException {
        if (!Files.isDirectory(outputRoot)) {
            throw new IllegalArgumentException("Каталог результатов не найден: " + outputRoot);
        }
        try (Stream<Path> paths = Files.walk(outputRoot, 2)) {
            return paths.filter(path -> path.getFileName().toString().equals("nodes.csv"))
                    .sorted(Comparator.reverseOrder())
                    .filter(this::containsSeeds)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Не найден nodes.csv с исходными участниками в " + outputRoot));
        }
    }

    public CrawlResult build(Path cacheDirectory, Path nodesFile) throws IOException {
        Map<Long, FriendCache.Entry> cached = readCache(cacheDirectory);
        if (cached.isEmpty()) {
            throw new IllegalArgumentException("Checkpoint-кэш пуст: " + cacheDirectory);
        }
        Set<Long> observedIds = Set.copyOf(cached.keySet());
        Map<Long, UserProfile> knownProfiles = readProfiles(nodesFile, observedIds);
        List<Long> seedIds = knownProfiles.values().stream()
                .filter(UserProfile::seed)
                .map(UserProfile::id)
                .sorted()
                .toList();
        if (seedIds.isEmpty()) {
            throw new IllegalArgumentException("В nodes.csv не найдены исходные участники: " + nodesFile);
        }

        Map<Long, UserProfile> profiles = new LinkedHashMap<>();
        observedIds.stream().sorted().forEach(id -> {
            UserProfile known = knownProfiles.get(id);
            if (known != null) {
                profiles.put(id, known.seed() ? known.asSeed()
                        : new UserProfile(id, known.screenName(), known.displayName(), false, 1));
            } else {
                profiles.put(id, new UserProfile(id, "id" + id, "id" + id, false, 1));
            }
        });

        Set<Edge> edges = new TreeSet<>();
        Map<Long, Set<Long>> seedFriends = new LinkedHashMap<>();
        Map<Long, String> statuses = new LinkedHashMap<>();
        Set<Long> seedSet = Set.copyOf(seedIds);
        for (FriendCache.Entry entry : cached.values()) {
            statuses.put(entry.userId(), status(entry));
            if (!entry.success()) {
                continue;
            }
            Set<Long> fullFriends = entry.friendIdSet();
            if (seedSet.contains(entry.userId())) {
                seedFriends.put(entry.userId(), fullFriends);
            }
            for (long friendId : fullFriends) {
                if (friendId != entry.userId() && observedIds.contains(friendId)) {
                    edges.add(new Edge(entry.userId(), friendId));
                }
            }
        }

        log.info("Офлайн-срез: {} кешированных пользователей, {} успешных списков, {} рёбер",
                cached.size(), cached.values().stream().filter(FriendCache.Entry::success).count(), edges.size());
        return new CrawlResult(Map.copyOf(profiles), Set.copyOf(edges), List.copyOf(seedIds),
                Map.copyOf(seedFriends), Map.copyOf(statuses), List.of(), false);
    }

    private Map<Long, FriendCache.Entry> readCache(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Каталог checkpoint не найден: " + directory);
        }
        Map<Long, FriendCache.Entry> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                try {
                    FriendCache.Entry entry = json.readValue(file.toFile(), FriendCache.Entry.class);
                    result.put(entry.userId(), entry);
                } catch (IOException | RuntimeException error) {
                    log.warn("Пропускаю повреждённый checkpoint {}: {}", file, error.getMessage());
                }
            }
        }
        return result;
    }

    private static Map<Long, UserProfile> readProfiles(Path path, Set<Long> observedIds) throws IOException {
        Map<Long, UserProfile> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            List<String> cells = csvCells(lines.get(lineNumber));
            if (cells.size() < 5) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(cells.get(0));
            } catch (NumberFormatException ignored) {
                continue;
            }
            boolean seed = Boolean.parseBoolean(cells.get(4));
            if (!seed && !observedIds.contains(id)) {
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(cells.get(3));
            } catch (NumberFormatException ignored) {
                level = seed ? 0 : 1;
            }
            result.put(id, new UserProfile(id, cells.get(1), cells.get(2), seed, level));
        }
        return result;
    }

    private boolean containsSeeds(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.skip(1).limit(100_000)
                    .map(OfflineSnapshotBuilder::csvCells)
                    .anyMatch(cells -> cells.size() >= 5 && Boolean.parseBoolean(cells.get(4)));
        } catch (IOException error) {
            return false;
        }
    }

    private static List<String> csvCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String status(FriendCache.Entry entry) {
        if (entry.success()) {
            return "SUCCESS";
        }
        return switch (entry.errorCode()) {
            case 15, 30 -> "PRIVATE";
            case 18 -> "DEACTIVATED";
            case 113 -> "NOT_FOUND";
            default -> "TEMPORARY_ERROR";
        };
    }
}
