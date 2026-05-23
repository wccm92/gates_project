package com.gates.msgates.config;

import com.gates.msgates.adapters.CompositeVisitorRepository;
import com.gates.msgates.adapters.JdbcVisitorRepository;
import com.gates.msgates.adapters.RemoteJdbcVisitorRepository;
import com.gates.msgates.domain.usecase.CheckAccessUseCase;
import com.gates.msgates.domain.usecase.ProcessReadingUseCase;
import com.gates.msgates.domain.usecase.ReadingParser;
import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import com.gates.msgates.domain.usecase.port.CredentialPublisherPort;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;

/**
 * Wires framework-free domain components into the Spring context.
 * Keeps domain classes free of Spring annotations.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({
        LocalDbProperties.class,
        RemoteDbProperties.class,
        AccessHttpProperties.class
})
public class BeanConfiguration {

    // --- DataSources ---

    @Bean
    public DataSource localDataSource(LocalDbProperties props) {
        return DataSourceBuilder.create()
                .url(props.url())
                .username(props.username())
                .password(props.password())
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    @Bean
    public DataSource remoteDataSource(RemoteDbProperties props) {
        return DataSourceBuilder.create()
                .url(props.url())
                .username(props.username())
                .password(props.password())
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    @Bean
    public JdbcTemplate localJdbcTemplate(@Qualifier("localDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public JdbcTemplate remoteJdbcTemplate(@Qualifier("remoteDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // --- Repository adapters ---

    @Bean
    public JdbcVisitorRepository localVisitorRepository(
            @Qualifier("localJdbcTemplate") JdbcTemplate jdbc,
            LocalDbProperties props) {
        return new JdbcVisitorRepository(jdbc, props.table());
    }

    @Bean
    public RemoteJdbcVisitorRepository remoteVisitorRepository(
            @Qualifier("remoteJdbcTemplate") JdbcTemplate jdbc,
            RemoteDbProperties props) {
        return new RemoteJdbcVisitorRepository(jdbc, props.table());
    }

    @Bean
    @Primary
    public VisitorRepositoryPort visitorRepositoryPort(JdbcVisitorRepository localVisitorRepository,
                                                       RemoteJdbcVisitorRepository remoteVisitorRepository) {
        return new CompositeVisitorRepository(localVisitorRepository, remoteVisitorRepository);
    }

    // --- Use cases ---

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
                                                 AccessNotifierPort notifier,
                                                 ApplicationEventPublisher eventPublisher) {
        return new CheckAccessUseCase(repository, notifier, eventPublisher);
    }

    // --- HTTP client ---

    @Bean
    public RestClient accessRestClient(AccessHttpProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Service", "ms-gates")
                .build();
    }
}
