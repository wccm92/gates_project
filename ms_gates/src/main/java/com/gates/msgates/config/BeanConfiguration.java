package com.gates.msgates.config;

import com.gates.msgates.domain.usecase.ProcessReadingUseCase;
import com.gates.msgates.domain.usecase.ReadingParser;
import com.gates.msgates.domain.usecase.port.CredentialPublisherPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires framework-free domain components into the Spring context.
 * Keeps domain classes free of Spring annotations.
 */
@Configuration
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
}
