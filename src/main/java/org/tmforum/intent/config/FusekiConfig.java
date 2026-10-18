package org.tmforum.intent.config;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Opt-in embedded Fuseki (SPARQL endpoint + web UI) over the app's TDB2 dataset.
 * Enabled with the {@code fuseki} Spring profile, e.g. {@code -Dspring-boot.run.profiles=access,fuseki}.
 */
@Configuration
@Profile("fuseki")
public class FusekiConfig {

    private static final Logger log = LoggerFactory.getLogger(FusekiConfig.class);

    @Value("${intent.fuseki.port:3030}")
    private int port;

    @Value("${intent.fuseki.dataset-name:intents}")
    private String datasetName;

    /** Read-only unless explicitly enabled — writes should go through the TMF921 API. */
    @Value("${intent.fuseki.allow-update:false}")
    private boolean allowUpdate;

    /** Bind to localhost only; set false inside containers so the published port is reachable. */
    @Value("${intent.fuseki.loopback:true}")
    private boolean loopback;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FusekiServer fusekiServer(Dataset dataset) throws IOException {
        log.info("Starting embedded Fuseki on port {} (dataset /{}, allowUpdate={}, loopback={})",
                port, datasetName, allowUpdate, loopback);
        Map<String, Object> datasets = datasetsJson();
        Instant started = Instant.now();
        return FusekiServer.create()
                .port(port)
                .loopback(loopback)
                .staticFileBase(extractUi().toString())
                .enablePing(true)
                .enableStats(true)
                .enableMetrics(true)
                .enableTasks(true)
                .addServlet("/$/datasets", jsonServlet(() -> datasets))
                .addServlet("/$/server", jsonServlet(() -> {
                    Map<String, Object> server = new LinkedHashMap<>(datasets);
                    server.put("version", "embedded");
                    server.put("startDateTime", started.toString());
                    server.put("uptime", Instant.now().getEpochSecond() - started.getEpochSecond());
                    return server;
                }))
                .add("/" + datasetName, dataset.asDatasetGraph(), allowUpdate)
                .build();
    }

    /** The Fuseki UI ships as classpath resources (webapp/**), but Fuseki serves static content from a directory. */
    private Path extractUi() throws IOException {
        Path dir = Files.createTempDirectory("fuseki-ui");
        dir.toFile().deleteOnExit();
        String prefix = "webapp/";
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:" + prefix + "**")) {
            if (!r.isReadable() || r.getFilename() == null || r.getURL().getPath().endsWith("/")) {
                continue;
            }
            String url = r.getURL().toString();
            Path target = dir.resolve(url.substring(url.lastIndexOf(prefix) + prefix.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    /**
     * The embedded Fuseki has no admin API, but the UI calls /$/datasets and /$/server to list datasets
     * and their endpoints. Serve a minimal read-only description of the single dataset.
     */
    private Map<String, Object> datasetsJson() {
        List<Map<String, Object>> services = new ArrayList<>();
        services.add(service("query", "SPARQL Query", "sparql", "query"));
        services.add(service("gsp-r", "Graph Store Protocol (Read)", "get", ""));
        if (allowUpdate) {
            services.add(service("update", "SPARQL Update", "update"));
            services.add(service("gsp-rw", "Graph Store Protocol", "data", ""));
        }
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("ds.name", "/" + datasetName);
        ds.put("ds.state", true);
        ds.put("ds.services", services);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasets", List.of(ds));
        return out;
    }

    private static Map<String, Object> service(String type, String description, String... endpoints) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("srv.type", type);
        s.put("srv.description", description);
        s.put("srv.endpoints", List.of(endpoints));
        return s;
    }

    private static HttpServlet jsonServlet(Supplier<Map<String, Object>> body) {
        ObjectMapper mapper = new ObjectMapper();
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("application/json");
                mapper.writeValue(resp.getOutputStream(), body.get());
            }
        };
    }
}
