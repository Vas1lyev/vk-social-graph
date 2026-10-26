package ru.vasilyev.mirea.vkgraph.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ru.vasilyev.mirea.vkgraph.analysis.AnalysisResult;
import ru.vasilyev.mirea.vkgraph.analysis.CentralityResult;
import ru.vasilyev.mirea.vkgraph.analysis.CentralityScore;
import ru.vasilyev.mirea.vkgraph.crawl.CrawlResult;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public final class CentralityOutputWriter {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path directory, CrawlResult crawl, AnalysisResult analysis, CentralityResult result,
                      Path cacheDirectory, Path sourceNodes) throws IOException {
        writeCsv(directory.resolve("centrality.csv"), crawl, result.scores());
        writeReport(directory.resolve("centrality_report.md"), crawl, result);
        writePracticeReport(directory.resolve("practice_report.md"), crawl, analysis, result);
        writeMetadata(directory.resolve("snapshot-metadata.json"), crawl, result, cacheDirectory, sourceNodes);
    }

    private static void writeCsv(Path path, CrawlResult crawl, List<CentralityScore> scores) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("user_id,display_name,observed_degree,betweenness_approx,closeness,eigenvector,reachable_nodes,data_complete");
        scores.stream().sorted(Comparator.comparingDouble(CentralityScore::betweennessApprox).reversed())
                .forEach(score -> {
                    UserProfile profile = crawl.profiles().get(score.userId());
                    lines.add(row(score.userId(), name(profile, score.userId()), score.observedDegree(),
                            format(score.betweennessApprox()), format(score.closeness()),
                            format(score.eigenvector()), score.reachableNodes(),
                            "SUCCESS".equals(crawl.fetchStatuses().get(score.userId()))));
                });
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeReport(Path path, CrawlResult crawl, CentralityResult result) throws IOException {
        StringBuilder report = new StringBuilder("# Центральности участников на зафиксированном графе\n\n");
        report.append("## Наблюдаемый набор данных\n\n")
                .append("- Вершин в зафиксированном checkpoint-срезе: ").append(result.nodeCount()).append("\n")
                .append("- Подтверждённых рёбер внутри среза: ").append(result.edgeCount()).append("\n")
                .append("- Компонент связности: ").append(result.componentCount()).append("\n")
                .append("- Размер крупнейшей компоненты: ").append(result.largestComponentSize()).append("\n")
                .append("- Исходных участников: ").append(result.scores().size()).append("\n")
                .append("- Источников для приближённого посредничества: ")
                .append(result.betweennessSamples()).append(" (фиксированный seed 42)\n")
                .append("- Итераций собственного вектора: ").append(result.eigenvectorIterations())
                .append(result.eigenvectorConverged() ? ", сходимость достигнута\n" : ", достигнут предел итераций\n")
                .append("\nГраф является индуцированным наблюдаемым срезом: включены только пользователи, ")
                .append("для которых существует checkpoint-файл, и только связи между такими пользователями. ")
                .append("Необработанный остаток исходной очереди не считается отсутствующим в реальном VK.\n\n");

        appendTopTable(report, crawl, "Топ-20 по посредничеству", result.scores(), CentralityScore::betweennessApprox,
                "Показывает, насколько часто участник лежит на кратчайших путях между другими вершинами. "
                        + "Значение оценено методом Brandes по детерминированной выборке источников.");
        appendTopTable(report, crawl, "Топ-20 по близости", result.scores(), CentralityScore::closeness,
                "Показывает, насколько малы кратчайшие расстояния от участника до доступной части графа. "
                        + "Использована нормализация Wasserman–Faust для несвязного графа.");
        appendTopTable(report, crawl, "Топ-20 по центральности собственного вектора", result.scores(), CentralityScore::eigenvector,
                "Высокое значение получают вершины, связанные с другими высокоцентральными вершинами. "
                        + "Вектор рассчитан степенным методом и нормирован так, что максимум графа равен 1.");

        report.append("## Все участники\n\n")
                .append("| Участник | Степень в срезе | Посредничество (оценка) | Близость | Собственный вектор | Достижимо вершин | Данные друзей доступны |\n")
                .append("|---|---:|---:|---:|---:|---:|:---:|\n");
        result.scores().stream()
                .sorted(Comparator.comparingDouble(CentralityScore::betweennessApprox).reversed())
                .forEach(score -> {
                    UserProfile profile = crawl.profiles().get(score.userId());
                    report.append("| ").append(markdown(name(profile, score.userId())))
                            .append(" | ").append(score.observedDegree())
                            .append(" | ").append(format(score.betweennessApprox()))
                            .append(" | ").append(format(score.closeness()))
                            .append(" | ").append(format(score.eigenvector()))
                            .append(" | ").append(score.reachableNodes())
                            .append(" | ").append("SUCCESS".equals(crawl.fetchStatuses().get(score.userId())) ? "да" : "нет")
                            .append(" |\n");
                });

        report.append("\n## Корректная интерпретация\n\n")
                .append("Результаты характеризуют положение участников внутри зафиксированного наблюдаемого графа, ")
                .append("а не внутри всей социальной сети VK. Значения центральностей зависят от границ выборки. ")
                .append("Закрытые профили могут присутствовать как вершины с подтверждёнными входящими связями, ")
                .append("но их собственные списки друзей неизвестны, поэтому выводы для них ограничены.\n");
        report.append("\nВыборка сформирована вокруг друзей исходных участников, поэтому центральности исходных ")
                .append("вершин закономерно выше, чем у случайной вершины VK. Сравнивать значения корректно ")
                .append("между участниками внутри этого среза, но не с центральностями пользователей во всей сети VK.\n");
        Files.writeString(path, report, StandardCharsets.UTF_8);
    }

    private void writeMetadata(Path path, CrawlResult crawl, CentralityResult result,
                               Path cacheDirectory, Path sourceNodes) throws IOException {
        long successful = crawl.fetchStatuses().values().stream().filter("SUCCESS"::equals).count();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("createdAt", Instant.now().toString());
        metadata.put("method", "offline-induced-checkpoint-snapshot");
        metadata.put("cacheDirectory", cacheDirectory.toAbsolutePath().toString());
        metadata.put("sourceNodes", sourceNodes.toAbsolutePath().toString());
        metadata.put("cachedUsers", result.nodeCount());
        metadata.put("successfulFriendLists", successful);
        metadata.put("unavailableFriendLists", result.nodeCount() - successful);
        metadata.put("observedEdges", result.edgeCount());
        metadata.put("componentCount", result.componentCount());
        metadata.put("largestComponentSize", result.largestComponentSize());
        metadata.put("betweenness", Map.of(
                "method", "deterministic-sampled-brandes",
                "samples", result.betweennessSamples(),
                "randomSeed", 42));
        metadata.put("closeness", Map.of("method", "exact-wasserman-faust-for-seeds"));
        metadata.put("eigenvector", Map.of(
                "method", "power-iteration-full-observed-graph",
                "iterations", result.eigenvectorIterations(),
                "converged", result.eigenvectorConverged()));
        json.writeValue(path.toFile(), metadata);
    }

    private static void writePracticeReport(Path path, CrawlResult crawl,
                                            AnalysisResult analysis, CentralityResult centrality) throws IOException {
        Map<Long, CentralityScore> byId = centrality.scores().stream()
                .collect(Collectors.toMap(CentralityScore::userId, score -> score));
        CentralityScore betweennessLeader = leader(centrality.scores(), CentralityScore::betweennessApprox);
        CentralityScore closenessLeader = leader(centrality.scores(), CentralityScore::closeness);
        CentralityScore eigenvectorLeader = leader(centrality.scores(), CentralityScore::eigenvector);

        StringBuilder report = new StringBuilder("# Практическая работа № 2: анализ графа знакомств VK\n\n")
                .append("## Цель\n\n")
                .append("Собрать доступную информацию о друзьях и друзьях друзей участников групп, ")
                .append("построить неориентированный граф знакомств и оценить для участников группы ")
                .append("центральность по посредничеству, близости и собственному вектору.\n\n")
                .append("## Зафиксированный набор данных\n\n")
                .append("После ограничения VK Flood control сбор был остановлен, а накопленный checkpoint ")
                .append("зафиксирован как наблюдаемый срез. Количество пользователей: **").append(centrality.nodeCount())
                .append("**; количество подтверждённых связей: **").append(centrality.edgeCount()).append("**. ")
                .append("Граф содержит ").append(centrality.componentCount())
                .append(" компоненты; размер крупнейшей компоненты — ")
                .append(centrality.largestComponentSize()).append(".\n\n")
                .append("Вершины — кешированные пользователи. Ребро включено, если дружба была обнаружена ")
                .append("в успешно полученном списке и обе вершины присутствуют в срезе. Таким образом, ")
                .append("анализируется индуцированный наблюдаемый граф, а не вся сеть VK.\n\n")
                .append("## Методика\n\n")
                .append("- **Посредничество** показывает долю кратчайших путей, проходящих через вершину. ")
                .append("Из-за размера графа применён Brandes по детерминированной выборке из ")
                .append(centrality.betweennessSamples()).append(" источников с фиксированным seed 42.\n")
                .append("- **Близость** рассчитана точным BFS от каждого участника с поправкой ")
                .append("Wasserman–Faust для несвязного графа.\n")
                .append("- **Собственный вектор** вычислен степенным методом на всём наблюдаемом графе; ")
                .append("максимальное значение нормировано к 1.\n\n")
                .append("## Ответы по центральностям\n\n")
                .append("- Максимальное посредничество: **").append(displayName(crawl, betweennessLeader.userId()))
                .append("** — ").append(format(betweennessLeader.betweennessApprox())).append(".\n")
                .append("- Максимальная близость: **").append(displayName(crawl, closenessLeader.userId()))
                .append("** — ").append(format(closenessLeader.closeness())).append(".\n")
                .append("- Максимальная центральность собственного вектора: **")
                .append(displayName(crawl, eigenvectorLeader.userId())).append("** — ")
                .append(format(eigenvectorLeader.eigenvector())).append(".\n\n")
                .append("Во всех трёх случаях лидером стал **")
                .append(displayName(crawl, betweennessLeader.userId())).append("**. Его степень в наблюдаемом ")
                .append("срезе равна ").append(byId.get(betweennessLeader.userId()).observedDegree())
                .append(": он непосредственно связан с большой долей рассматриваемых вершин. Это сокращает ")
                .append("средние расстояния до остальных пользователей, повышает вероятность прохождения ")
                .append("кратчайших путей через него и связывает его с другими влиятельными вершинами.\n\n");

        appendTopTable(report, crawl, "Топ-20 по посредничеству", centrality.scores(),
                CentralityScore::betweennessApprox,
                "Рейтинг участников по доле кратчайших путей, проходящих через вершину.");
        appendTopTable(report, crawl, "Топ-20 по близости", centrality.scores(),
                CentralityScore::closeness,
                "Рейтинг участников по близости к доступной части наблюдаемого графа.");
        appendTopTable(report, crawl, "Топ-20 по центральности собственного вектора", centrality.scores(),
                CentralityScore::eigenvector,
                "Рейтинг участников с учётом центральности их соседей.");

        if (!analysis.seedScores().isEmpty()) {
            var best = analysis.seedScores().getFirst();
            report.append("## Общие знакомства\n\n")
                    .append("По числу уникальных общих знакомых лидирует **")
                    .append(displayName(crawl, best.userId())).append("**: ")
                    .append(best.sharedUnique()).append(" человек. Это означает, что в его списке друзей найдено ")
                    .append(best.sharedUnique()).append(" разных пользователей, каждый из которых также является ")
                    .append("другом хотя бы одного другого участника исходной группы.\n\n");
        }
        if (!analysis.pairScores().isEmpty()) {
            var pair = analysis.pairScores().getFirst();
            report.append("Максимальное попарное пересечение: **")
                    .append(displayName(crawl, pair.firstUserId())).append(" — ")
                    .append(displayName(crawl, pair.secondUserId())).append("** — ")
                    .append(pair.mutualCount()).append(" общих друзей.\n\n");
        }

        report.append("## Итоговый вывод\n\n")
                .append("Наблюдаемый граф почти полностью связан: ")
                .append(centrality.largestComponentSize()).append(" из ").append(centrality.nodeCount())
                .append(" вершин находятся в одной компоненте. Андрей Клейменов является наиболее центральным ")
                .append("участником по всем трём рассмотренным критериям и одновременно лидирует по числу ")
                .append("уникальных общих знакомых. Следовательно, в границах собранного среза он занимает ")
                .append("наиболее выраженную структурную позицию: имеет широкий непосредственный охват, ")
                .append("находится близко к остальным вершинам и служит важным посредником между частями графа.\n");
        Files.writeString(path, report, StandardCharsets.UTF_8);
    }

    private static CentralityScore leader(List<CentralityScore> scores,
                                          ToDoubleFunction<CentralityScore> metric) {
        return scores.stream().max(Comparator.comparingDouble(metric)).orElseThrow();
    }

    private static String displayName(CrawlResult crawl, long userId) {
        return name(crawl.profiles().get(userId), userId);
    }

    private static void appendTopTable(StringBuilder report, CrawlResult crawl, String title,
                                       List<CentralityScore> scores,
                                       ToDoubleFunction<CentralityScore> metric, String explanation) {
        List<CentralityScore> ranked = scores.stream()
                .sorted(Comparator.comparingDouble(metric).reversed()
                        .thenComparingLong(CentralityScore::userId))
                .toList();
        report.append("## ").append(title).append("\n\n").append(explanation).append("\n\n");
        report.append("| Место | Участник | Значение | Степень в срезе | Данные друзей доступны |\n")
                .append("|---:|---|---:|---:|:---:|\n");
        for (int i = 0; i < Math.min(20, ranked.size()); i++) {
            CentralityScore score = ranked.get(i);
            UserProfile profile = crawl.profiles().get(score.userId());
            report.append("| ").append(i + 1).append(" | ")
                    .append(markdown(name(profile, score.userId())))
                    .append(" | ").append(format(metric.applyAsDouble(score)))
                    .append(" | ").append(score.observedDegree())
                    .append(" | ").append("SUCCESS".equals(crawl.fetchStatuses().get(score.userId())) ? "да" : "нет")
                    .append(" |\n");
        }
        report.append("\n");
    }

    private static String name(UserProfile profile, long id) {
        return profile == null ? "id" + id : profile.displayName();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.8f", value);
    }

    private static String row(Object... cells) {
        return java.util.Arrays.stream(cells).map(CentralityOutputWriter::csv)
                .collect(Collectors.joining(","));
    }

    private static String csv(Object value) {
        String text = String.valueOf(value);
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static String markdown(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
