package org.tmforum.intent.config;

import jakarta.annotation.PreDestroy;
import org.apache.jena.query.Dataset;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.tdb2.TDB2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class JenaConfig {

    private static final Logger log = LoggerFactory.getLogger(JenaConfig.class);

    @Value("${intent.tdb2-path}")
    private String tdb2Path;

    @Value("${intent.rules-path:classpath:rules/tio_all.rules}")
    private String rulesPath;

    private Dataset dataset;

    @Bean
    public Dataset dataset() {
        log.info("Opening TDB2 dataset at {}", tdb2Path);
        dataset = TDB2Factory.connectDataset(tdb2Path);
        return dataset;
    }

    @Bean
    public GenericRuleReasoner ruleReasoner(ResourceLoader resourceLoader) throws Exception {
        Resource resource = resourceLoader.getResource(rulesPath);
        String rulesText;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            rulesText = reader.lines().collect(Collectors.joining("\n"));
        }
        List<Rule> rules = Rule.parseRules(rulesText);
        log.info("Loaded {} TIO rules from {}", rules.size(), rulesPath);
        GenericRuleReasoner reasoner = new GenericRuleReasoner(rules);
        reasoner.setMode(GenericRuleReasoner.FORWARD_RETE);
        return reasoner;
    }

    @PreDestroy
    public void closeDataset() {
        if (dataset != null) {
            log.info("Closing TDB2 dataset");
            dataset.close();
        }
    }
}
