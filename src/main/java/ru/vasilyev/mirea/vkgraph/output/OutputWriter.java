package ru.vasilyev.mirea.vkgraph.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ru.vasilyev.mirea.vkgraph.analysis.AnalysisResult;
import ru.vasilyev.mirea.vkgraph.analysis.PairScore;
import ru.vasilyev.mirea.vkgraph.analysis.SeedScore;
import ru.vasilyev.mirea.vkgraph.crawl.CrawlResult;
import ru.vasilyev.mirea.vkgraph.domain.Edge;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class OutputWriter {
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(Path root, CrawlResult crawl, AnalysisResult analysis, Path input,
                      String apiVersion, int depth) throws IOException {
        Path directory = root.resolve(RUN_ID.format(Instant.now()));
        Files.createDirectories(directory);
        writeNodes(directory.resolve("nodes.csv"), crawl);
        writeEdges(directory.resolve("edges.csv"), crawl.edges());
        writeScores(directory.resolve("seed_ranking.csv"), crawl, analysis.seedScores());
        writePairs(directory.resolve("pair_mutual.csv"), crawl, analysis.pairScores());
        writeUnavailable(directory.resolve("unavailable_profiles.csv"), crawl);
        writeGraphMl(directory.resolve("graph.graphml"), crawl);
        writeHtml(directory.resolve("graph.html"), crawl);
        writeReport(directory.resolve("report.md"), crawl, analysis);
        writeMetadata(directory.resolve("run-metadata.json"), crawl, input, apiVersion, depth);
        return directory;
    }

    private static void writeNodes(Path path, CrawlResult crawl) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("user_id,screen_name,display_name,level,is_seed,fetch_status");
        crawl.profiles().values().stream().sorted(Comparator.comparingLong(UserProfile::id)).forEach(profile ->
                lines.add(row(profile.id(), profile.screenName(), profile.displayName(), profile.level(), profile.seed(),
                        crawl.fetchStatuses().getOrDefault(profile.id(), "NOT_FETCHED"))));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeEdges(Path path, Set<Edge> edges) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("source_id,target_id");
        edges.stream().sorted().forEach(edge -> lines.add(row(edge.source(), edge.target())));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeScores(Path path, CrawlResult crawl, List<SeedScore> scores) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("user_id,display_name,shared_unique,shared_sum,seed_degree,data_complete");
        for (SeedScore score : scores) {
            UserProfile profile = crawl.profiles().get(score.userId());
            lines.add(row(score.userId(), profile.displayName(), score.sharedUnique(), score.sharedSum(),
                    score.seedDegree(), score.dataComplete()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writePairs(Path path, CrawlResult crawl, List<PairScore> scores) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("first_user_id,first_name,second_user_id,second_name,mutual_count");
        for (PairScore score : scores) {
            lines.add(row(score.firstUserId(), crawl.profiles().get(score.firstUserId()).displayName(),
                    score.secondUserId(), crawl.profiles().get(score.secondUserId()).displayName(), score.mutualCount()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeUnavailable(Path path, CrawlResult crawl) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("user_or_input,status");
        crawl.unresolvedInputs().forEach(input -> lines.add(row(input, "NOT_FOUND")));
        crawl.fetchStatuses().entrySet().stream()
                .filter(entry -> !"SUCCESS".equals(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(row(entry.getKey(), entry.getValue())));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private void writeMetadata(Path path, CrawlResult crawl, Path input, String apiVersion, int depth) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("createdAt", Instant.now().toString());
        metadata.put("input", input.toAbsolutePath().toString());
        metadata.put("apiVersion", apiVersion);
        metadata.put("depth", depth);
        metadata.put("complete", crawl.complete());
        metadata.put("seedCount", crawl.seedIds().size());
        metadata.put("nodeCount", crawl.profiles().size());
        metadata.put("edgeCount", crawl.edges().size());
        json.writeValue(path.toFile(), metadata);
    }

    private static void writeGraphMl(Path path, CrawlResult crawl) throws IOException {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="label" for="node" attr.name="label" attr.type="string"/>
                  <key id="level" for="node" attr.name="level" attr.type="int"/>
                  <key id="seed" for="node" attr.name="seed" attr.type="boolean"/>
                  <graph id="vk" edgedefault="undirected">
                """);
        crawl.profiles().values().stream().sorted(Comparator.comparingLong(UserProfile::id)).forEach(profile ->
                xml.append("    <node id=\"n").append(profile.id()).append("\"><data key=\"label\">")
                        .append(xml(profile.displayName())).append("</data><data key=\"level\">")
                        .append(profile.level()).append("</data><data key=\"seed\">")
                        .append(profile.seed()).append("</data></node>\n"));
        int index = 0;
        for (Edge edge : crawl.edges().stream().sorted().toList()) {
            xml.append("    <edge id=\"e").append(index++).append("\" source=\"n").append(edge.source())
                    .append("\" target=\"n").append(edge.target()).append("\"/>\n");
        }
        xml.append("  </graph>\n</graphml>\n");
        Files.writeString(path, xml, StandardCharsets.UTF_8);
    }

    private void writeHtml(Path path, CrawlResult crawl) throws IOException {
        Set<Long> seedIds = Set.copyOf(crawl.seedIds());
        Map<Long, Integer> seedConnections = new java.util.HashMap<>();
        for (Edge edge : crawl.edges()) {
            if (seedIds.contains(edge.source()) && !seedIds.contains(edge.target())) {
                seedConnections.merge(edge.target(), 1, Integer::sum);
            }
            if (seedIds.contains(edge.target()) && !seedIds.contains(edge.source())) {
                seedConnections.merge(edge.source(), 1, Integer::sum);
            }
        }
        List<UserProfile> visibleProfiles = crawl.profiles().values().stream()
                .filter(profile -> profile.seed() || seedConnections.getOrDefault(profile.id(), 0) >= 2)
                .sorted(Comparator.comparing(UserProfile::seed).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (UserProfile profile) -> seedConnections.getOrDefault(profile.id(), 0)).reversed())
                        .thenComparingLong(UserProfile::id))
                .toList();
        Set<Long> visibleIds = visibleProfiles.stream().map(UserProfile::id).collect(Collectors.toSet());
        List<Map<String, Object>> nodes = visibleProfiles.stream().map(profile -> Map.<String, Object>of(
                "id", profile.id(), "label", profile.displayName(), "seed", profile.seed(), "level", profile.level(),
                "seedConnections", seedConnections.getOrDefault(profile.id(), 0)
        )).toList();
        List<Map<String, Long>> edges = crawl.edges().stream()
                .filter(edge -> visibleIds.contains(edge.source()) && visibleIds.contains(edge.target()))
                .map(edge -> Map.of("source", edge.source(), "target", edge.target())).toList();
        String data = json.writeValueAsString(Map.of("nodes", nodes, "edges", edges)).replace("</", "<\\/");
        String html = """
                <!doctype html><html lang="ru"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Граф знакомств VK</title><style>
                html,body{margin:0;height:100%%;font:14px system-ui;background:#111827;color:#e5e7eb}
                #bar{position:fixed;z-index:2;padding:10px;background:#111827dd;width:100%%}
                input{padding:7px;border-radius:6px;border:1px solid #475569;background:#1f2937;color:white}
                canvas{display:block;width:100%%;height:100%%}.hint{color:#94a3b8;margin-left:10px}
                </style></head><body><div id="bar"><input id="search" placeholder="ID или имя">
                <span id="stats" class="hint"></span></div>
                <canvas id="graph"></canvas><script>const DATA=%s;
                const c=document.getElementById('graph'),x=c.getContext('2d'),dpr=devicePixelRatio||1;
                document.getElementById('stats').textContent=`Показано: ${DATA.nodes.length} вершин и ${DATA.edges.length} связей. Оранжевые — участники, остальные знакомы минимум с двумя участниками.`;
                let zoom=.75,ox=0,oy=35,drag=false,lx=0,ly=0,q='';
                function resize(){c.width=innerWidth*dpr;c.height=innerHeight*dpr;draw()}addEventListener('resize',resize);
                function positions(){const p=new Map(),target=new Map(),s=DATA.nodes.filter(v=>v.seed),o=DATA.nodes.filter(v=>!v.seed),near=new Map(),cols=Math.min(5,s.length);
                s.forEach((v,i)=>{const col=i%%cols,row=Math.floor(i/cols),rows=Math.ceil(s.length/cols);p.set(v.id,[(col-(cols-1)/2)*300,(row-(rows-1)/2)*230])});
                for(const e of DATA.edges){if(p.has(e.source)){if(!near.has(e.target))near.set(e.target,[]);near.get(e.target).push(p.get(e.source))}if(p.has(e.target)){if(!near.has(e.source))near.set(e.source,[]);near.get(e.source).push(p.get(e.target))}}
                o.forEach((v,i)=>{const a=near.get(v.id)||[],cx=a.reduce((z,q)=>z+q[0],0)/Math.max(1,a.length),cy=a.reduce((z,q)=>z+q[1],0)/Math.max(1,a.length),h=(Number(v.id)*2654435761)>>>0,t=(h/4294967296)*6.283,r=25+(i%%9)*9;target.set(v.id,[cx,cy]);p.set(v.id,[cx+Math.cos(t)*r,cy+Math.sin(t)*r])});
                for(let step=0;step<140;step++){for(const v of o){const a=p.get(v.id),t=target.get(v.id);a[0]+=(t[0]-a[0])*.035;a[1]+=(t[1]-a[1])*.035}for(let i=0;i<o.length;i++)for(let j=i+1;j<o.length;j++){const a=p.get(o[i].id),b=p.get(o[j].id),dx=a[0]-b[0],dy=a[1]-b[1],d=Math.max(1,Math.hypot(dx,dy));if(d<34){const f=(34-d)*.12;a[0]+=dx/d*f;a[1]+=dy/d*f;b[0]-=dx/d*f;b[1]-=dy/d*f}}}return p}
                const pos=positions();function draw(){x.setTransform(dpr,0,0,dpr,0,0);x.clearRect(0,0,innerWidth,innerHeight);x.save();x.translate(innerWidth/2+ox,innerHeight/2+oy);x.scale(zoom,zoom);x.strokeStyle='#334155';x.lineWidth=.6/zoom;
                for(const e of DATA.edges){const a=pos.get(e.source),b=pos.get(e.target);if(!a||!b)continue;x.beginPath();x.moveTo(...a);x.lineTo(...b);x.stroke()}
                for(const v of DATA.nodes){const p=pos.get(v.id),hit=q&&(String(v.id).includes(q)||v.label.toLowerCase().includes(q));x.beginPath();x.fillStyle=hit?'#fde047':v.seed?'#f97316':v.seedConnections>=4?'#a78bfa':'#38bdf8';const size=v.seed?8:3+v.seedConnections;x.arc(p[0],p[1],size/zoom,0,6.283);x.fill();if(v.seed||v.seedConnections>=4||hit){x.fillStyle='#e5e7eb';x.font=`${12/zoom}px system-ui`;x.fillText(v.label,p[0]+9/zoom,p[1]-6/zoom)}}x.restore()}
                c.onwheel=e=>{e.preventDefault();zoom=Math.max(.05,Math.min(8,zoom*(e.deltaY<0?1.12:.89)));draw()};c.onmousedown=e=>{drag=true;lx=e.clientX;ly=e.clientY};onmouseup=()=>drag=false;onmousemove=e=>{if(drag){ox+=e.clientX-lx;oy+=e.clientY-ly;lx=e.clientX;ly=e.clientY;draw()}};
                document.getElementById('search').oninput=e=>{q=e.target.value.toLowerCase();draw()};resize();</script></body></html>
                """.formatted(data);
        Files.writeString(path, html, StandardCharsets.UTF_8);
    }

    private static void writeReport(Path path, CrawlResult crawl, AnalysisResult analysis) throws IOException {
        StringBuilder report = new StringBuilder("# Отчёт по графу знакомств VK\n\n");
        report.append("- Исходных пользователей: ").append(crawl.seedIds().size()).append("\n")
                .append("- Вершин: ").append(crawl.profiles().size()).append("\n")
                .append("- Рёбер: ").append(crawl.edges().size()).append("\n")
                .append("- Полнота обхода: ").append(crawl.complete() ? "полный" : "частичный").append("\n\n");
        if (!analysis.seedScores().isEmpty()) {
            int best = analysis.seedScores().getFirst().sharedUnique();
            report.append("## Лидеры по уникальным общим знакомым\n\n");
            analysis.seedScores().stream().filter(score -> score.sharedUnique() == best).forEach(score -> {
                UserProfile profile = crawl.profiles().get(score.userId());
                report.append("- ").append(profile.displayName()).append(" (`").append(score.userId())
                        .append("`): ").append(score.sharedUnique()).append("\n");
            });
            report.append("\n## Полный рейтинг участников\n\n")
                    .append("| Место | Участник | Уникальных общих знакомых | Сумма попарных пересечений | Знакомых в группе | Данные полны |\n")
                    .append("|---:|---|---:|---:|---:|:---:|\n");
            for (int index = 0; index < analysis.seedScores().size(); index++) {
                var score = analysis.seedScores().get(index);
                UserProfile profile = crawl.profiles().get(score.userId());
                report.append("| ").append(index + 1).append(" | ").append(markdown(profile.displayName()))
                        .append(" | ").append(score.sharedUnique()).append(" | ").append(score.sharedSum())
                        .append(" | ").append(score.seedDegree()).append(" | ")
                        .append(score.dataComplete() ? "да" : "нет").append(" |\n");
            }
        }
        if (!analysis.pairScores().isEmpty()) {
            int best = analysis.pairScores().getFirst().mutualCount();
            report.append("\n## Пары с максимальным числом общих друзей\n\n");
            analysis.pairScores().stream().filter(score -> score.mutualCount() == best).forEach(score ->
                    report.append("- ").append(crawl.profiles().get(score.firstUserId()).displayName())
                            .append(" — ").append(crawl.profiles().get(score.secondUserId()).displayName())
                            .append(": ").append(score.mutualCount()).append("\n"));
            report.append("\n## Топ-10 пар\n\n| Место | Пара | Общих друзей |\n|---:|---|---:|\n");
            for (int index = 0; index < Math.min(10, analysis.pairScores().size()); index++) {
                var score = analysis.pairScores().get(index);
                String first = crawl.profiles().get(score.firstUserId()).displayName();
                String second = crawl.profiles().get(score.secondUserId()).displayName();
                report.append("| ").append(index + 1).append(" | ").append(markdown(first)).append(" — ")
                        .append(markdown(second)).append(" | ").append(score.mutualCount()).append(" |\n");
            }
        }
        report.append("\n## Как интерпретировать результат\n\n")
                .append("Основной ответ практики — первый человек в полном рейтинге: у него больше всего уникальных друзей, ")
                .append("которые знакомы хотя бы с одним другим участником исходной группы. Топ пар показывает, ")
                .append("какие два участника имеют больше всего общих друзей между собой.\n");
        report.append("\n## Ограничения\n\nДанные отражают только связи, доступные токену и настройкам приватности на дату запуска. ")
                .append("Недоступные списки друзей не трактуются как пустые.\n");
        Files.writeString(path, report, StandardCharsets.UTF_8);
    }

    private static String markdown(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String row(Object... cells) {
        return java.util.Arrays.stream(cells).map(OutputWriter::csv).collect(Collectors.joining(","));
    }

    private static String csv(Object value) {
        String text = String.valueOf(value);
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
