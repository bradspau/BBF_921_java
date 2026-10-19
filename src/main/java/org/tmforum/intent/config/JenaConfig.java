package org.tmforum.intent.config;

import jakarta.annotation.PreDestroy;
import org.apache.jena.query.Dataset;
import org.apache.jena.tdb2.TDB2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class JenaConfig {

    private static final Logger log = LoggerFactory.getLogger(JenaConfig.class);

    @Value("${intent.tdb2-path}")
    private String tdb2Path;

    private Dataset dataset;

    @Bean
    public Dataset dataset() {
        log.info("Opening TDB2 dataset at {}", tdb2Path);
        dataset = TDB2Factory.connectDataset(tdb2Path);
        return dataset;
    }

    @PreDestroy
    public void closeDataset() {
        if (dataset != null) {
            log.info("Closing TDB2 dataset");
            dataset.close();
        }
    }
}
