package org.tmforum.intent.unit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared test inputs: the real HSI seed intent, the real PON inventory and observation Turtle. */
public final class TestInputs {

    private TestInputs() {}

    static final String BBF = "http://broadband-forum.org/Intent#";

    public static final String[] ALL_OK = {
            "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps",
            "LatencyMetric", "8", "ms", "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent"};
    public static final String[] BANDWIDTH_ONLY = {
            "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps"};

    public static String seedIntent() {
        return read("seed_data/hsionlyintent_v0.5.ttl");
    }

    public static String inventory() {
        return read("BBF_access/pon_resource_onto.ttl") + "\n" + read("BBF_access/pon_resource_data.ttl");
    }

    /** Observations as (metric, value, unit) triples; an empty unit means a value without unit. */
    public static String observations(String... metricValueUnit) {
        StringBuilder sb = new StringBuilder("""
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                """);
        for (int i = 0; i < metricValueUnit.length; i += 3) {
            sb.append("<http://example.org/obs/").append(i).append("> a met:Observation ;\n")
              .append("  met:observedMetric <").append(BBF).append(metricValueUnit[i]).append("> ;\n")
              .append("  rdf:value \"").append(metricValueUnit[i + 1]).append("\"^^xsd:decimal ;\n");
            if (!metricValueUnit[i + 2].isEmpty()) {
                sb.append("  quan:unit \"").append(metricValueUnit[i + 2]).append("\" ;\n");
            }
            sb.append("  met:obtainedAt \"2026-06-11T10:00:0").append(i / 3).append("Z\"^^xsd:dateTime .\n");
        }
        return sb.toString();
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
