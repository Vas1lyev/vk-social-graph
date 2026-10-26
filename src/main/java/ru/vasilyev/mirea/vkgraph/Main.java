package ru.vasilyev.mirea.vkgraph;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ru.vasilyev.mirea.vkgraph.analysis.AnalysisResult;
import ru.vasilyev.mirea.vkgraph.analysis.AnalysisService;
import ru.vasilyev.mirea.vkgraph.analysis.CentralityResult;
import ru.vasilyev.mirea.vkgraph.analysis.CentralityService;
import ru.vasilyev.mirea.vkgraph.cache.FriendCache;
import ru.vasilyev.mirea.vkgraph.crawl.CrawlResult;
import ru.vasilyev.mirea.vkgraph.crawl.GraphCrawler;
import ru.vasilyev.mirea.vkgraph.crawl.OfflineSnapshotBuilder;
import ru.vasilyev.mirea.vkgraph.input.SeedInputReader;
import ru.vasilyev.mirea.vkgraph.output.CentralityOutputWriter;
import ru.vasilyev.mirea.vkgraph.output.OutputWriter;
import ru.vasilyev.mirea.vkgraph.config.TokenProvider;
import ru.vasilyev.mirea.vkgraph.config.EnvironmentConfig;
import ru.vasilyev.mirea.vkgraph.vk.VkApiClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "vk-social-graph", mixinStandardHelpOptions = true,
        description = "Строит граф знакомств VK.",
        subcommands = {Main.AllCommand.class, Main.OfflineCommand.class})
public final class Main implements Runnable {
    public static void main(String[] args) {
        String[] effectiveArgs = args.length == 0
                ? new String[]{"all", "--input", "data/bsmo-all.csv", "--output", "out"}
                : args;
        System.exit(new CommandLine(new Main()).execute(effectiveArgs));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "all", mixinStandardHelpOptions = true,
            description = "Собирает данные, анализирует граф и экспортирует результаты.")
    static final class AllCommand implements Callable<Integer> {
        @Option(names = "--input", required = true, description = "CSV-файл с колонкой vk_url")
        private Path input;

        @Option(names = "--output", defaultValue = "out", description = "Корневой каталог результатов")
        private Path output;

        @Option(names = "--depth", description = "Глубина 1 или 2; переопределяет VK_CRAWL_DEPTH из .env")
        private Integer depth;

        @Option(names = "--max-level2-parents",
                description = "Максимум пользователей L1; переопределяет VK_MAX_LEVEL2_PARENTS из .env")
        private Integer maxLevel2Parents;

        @Option(names = "--max-total-users", defaultValue = "100000")
        private int maxTotalUsers;

        @Option(names = "--l2-workers", description = "Потоки пакетной загрузки L2; переопределяет VK_L2_WORKERS")
        private Integer level2Workers;

        @Option(names = "--api-version", defaultValue = "5.199")
        private String apiVersion;

        @Option(names = "--delay-ms", description = "Задержка запросов; переопределяет VK_REQUEST_DELAY_MS")
        private Long delayMs;

        @Option(names = "--execute-batch-size",
                description = "Размер execute-пакета 1..25; переопределяет VK_EXECUTE_BATCH_SIZE")
        private Integer executeBatchSize;

        @Option(names = "--max-retries", defaultValue = "5")
        private int maxRetries;

        @Override
        public Integer call() throws Exception {
            Path envFile = Path.of(".env");
            EnvironmentConfig environment = new EnvironmentConfig();
            String logLevel = environment.get(envFile, "VK_LOG_LEVEL");
            if (logLevel == null) {
                logLevel = "INFO";
            }
            logLevel = logLevel.toUpperCase(java.util.Locale.ROOT);
            if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(logLevel)) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "VK_LOG_LEVEL должен быть TRACE, DEBUG, INFO, WARN или ERROR");
            }
            System.setProperty("VK_LOG_LEVEL", logLevel);
            String token = new TokenProvider().load(envFile);
            if (token == null) {
                System.err.println("Не найден VK_ACCESS_TOKEN. Создайте .env по образцу .env.example "
                        + "или задайте переменную окружения в Run Configuration.");
                return 3;
            }
            int crawlDepth = depth != null
                    ? depth
                    : environment.getPositiveInt(envFile, "VK_CRAWL_DEPTH", 2);
            if (crawlDepth < 1 || crawlDepth > 2) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Глубина должна быть 1 или 2 (--depth / VK_CRAWL_DEPTH)");
            }
            int level2Limit = maxLevel2Parents != null
                    ? maxLevel2Parents
                    : environment.getPositiveIntOrUnlimited(
                            envFile, "VK_MAX_LEVEL2_PARENTS", 100);
            if (level2Limit <= 0) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "--max-level2-parents должен быть положительным целым числом");
            }
            int workers = level2Workers != null
                    ? level2Workers
                    : environment.getPositiveInt(envFile, "VK_L2_WORKERS", 4);
            if (workers < 1 || workers > 16) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Число потоков должно быть от 1 до 16 (--l2-workers / VK_L2_WORKERS)");
            }
            long requestDelayMs = delayMs != null
                    ? delayMs
                    : environment.getPositiveInt(envFile, "VK_REQUEST_DELAY_MS", 350);
            if (requestDelayMs < 0) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Задержка запросов не может быть отрицательной");
            }
            int batchSize = executeBatchSize != null
                    ? executeBatchSize
                    : environment.getPositiveInt(envFile, "VK_EXECUTE_BATCH_SIZE", 25);
            if (batchSize < 1 || batchSize > 25) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Размер execute-пакета должен быть от 1 до 25");
            }
            String cacheDirectory = environment.get(envFile, "VK_CACHE_DIR");
            if (cacheDirectory == null) {
                cacheDirectory = "state/friends";
            }
            int cacheTtlHours = environment.getPositiveInt(envFile, "VK_CACHE_TTL_HOURS", 24);

            List<String> seeds = new SeedInputReader().read(input);
            VkApiClient client = new VkApiClient(
                    token, apiVersion, Duration.ofSeconds(20), requestDelayMs, maxRetries);
            FriendCache cache = new FriendCache(Path.of(cacheDirectory), Duration.ofHours(cacheTtlHours));
            CrawlResult crawl = new GraphCrawler(client, cache).crawl(
                    seeds, crawlDepth, level2Limit, maxTotalUsers, workers, batchSize);
            AnalysisResult analysis = new AnalysisService().analyze(crawl);
            Path resultDir = new OutputWriter().write(output, crawl, analysis, input, apiVersion, crawlDepth);

            System.out.println("Результаты: " + resultDir.toAbsolutePath());
            return crawl.complete() ? 0 : 4;
        }
    }

    @Command(name = "offline", mixinStandardHelpOptions = true,
            description = "Строит зафиксированный граф и центральности только из checkpoint-кэша.")
    static final class OfflineCommand implements Callable<Integer> {
        @Option(names = "--output", defaultValue = "out", description = "Корневой каталог результатов")
        private Path output;

        @Option(names = "--cache-dir", description = "Каталог checkpoint; по умолчанию VK_CACHE_DIR или state/friends")
        private Path cacheDirectory;

        @Option(names = "--seed-nodes",
                description = "nodes.csv предыдущего запуска; если не указан, выбирается последний файл из --source-output-root")
        private Path seedNodes;

        @Option(names = "--source-output-root", defaultValue = "out",
                description = "Где искать последний nodes.csv с исходными участниками")
        private Path sourceOutputRoot;

        @Option(names = "--betweenness-samples", defaultValue = "256",
                description = "Число детерминированных источников для оценки посредничества")
        private int betweennessSamples;

        @Override
        public Integer call() throws Exception {
            Path envFile = Path.of(".env");
            EnvironmentConfig environment = new EnvironmentConfig();
            String logLevel = environment.get(envFile, "VK_LOG_LEVEL");
            System.setProperty("VK_LOG_LEVEL", logLevel == null ? "INFO" : logLevel.toUpperCase(java.util.Locale.ROOT));
            if (betweennessSamples <= 0) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "--betweenness-samples должен быть положительным целым числом");
            }
            Path actualCache = cacheDirectory;
            if (actualCache == null) {
                String configured = environment.get(envFile, "VK_CACHE_DIR");
                actualCache = Path.of(configured == null ? "state/friends" : configured);
            }

            OfflineSnapshotBuilder builder = new OfflineSnapshotBuilder();
            Path actualSeedNodes = seedNodes == null
                    ? builder.findLatestNodesFile(sourceOutputRoot)
                    : seedNodes;
            CrawlResult crawl = builder.build(actualCache, actualSeedNodes);
            AnalysisResult analysis = new AnalysisService().analyze(crawl);
            CentralityResult centrality = new CentralityService().analyze(
                    crawl.profiles().keySet(), crawl.edges(), crawl.seedIds(), betweennessSamples);

            Path resultDir = new OutputWriter().write(
                    output, crawl, analysis, actualSeedNodes, "offline-cache", 2);
            new CentralityOutputWriter().write(
                    resultDir, crawl, analysis, centrality, actualCache, actualSeedNodes);

            System.out.println("Офлайн-результаты: " + resultDir.toAbsolutePath());
            System.out.println("Зафиксировано пользователей: " + crawl.profiles().size());
            System.out.println("Рёбер в наблюдаемом графе: " + crawl.edges().size());
            return 0;
        }
    }
}
