package com.gates.msgates.config;

import com.gates.msgates.domain.usecase.CheckAccessUseCase;
import com.gates.msgates.domain.usecase.ProcessReadingUseCase;
import com.gates.msgates.domain.usecase.ReadingParser;
import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import com.gates.msgates.domain.usecase.port.CredentialPublisherPort;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires framework-free domain components into the Spring context.
 * Keeps domain classes free of Spring annotations.
 */
@Configuration
@EnableConfigurationProperties(AccessHttpProperties.class)
public class BeanConfiguration {

    @Bean
    public ReadingParser readingParser() {
        return new ReadingParser();
    }

    @Bean
    public ProcessReadingUseCase processReadingUseCase(ReadingParser parser,
                                                       CredentialPublisherPort publisher) {
        return new ProcessReadingUseCase(parser, publisher);
    }

    @Bean
    public CheckAccessUseCase checkAccessUseCase(VisitorRepositoryPort repository,
                                                 AccessNotifierPort notifier) {
        return new CheckAccessUseCase(repository, notifier);
    }

    @Bean
    public RestClient accessRestClient(AccessHttpProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Service", "ms-gates")
                .build();
    }
}
