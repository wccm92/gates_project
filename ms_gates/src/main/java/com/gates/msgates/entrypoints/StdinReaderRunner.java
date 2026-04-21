package com.gates.msgates.entrypoints;

import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.usecase.ProcessReadingUseCase;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class StdinReaderRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StdinReaderRunner.class);
    private static final int MAX_LINE_LENGTH = 1024;

    private final ProcessReadingUseCase useCase;
    private final ConfigurableApplicationContext applicationContext;
    private final Thread readerThread;
    private volatile boolean running = true;

    public StdinReaderRunner(ProcessReadingUseCase useCase,
                             ConfigurableApplicationContext applicationContext) {
        this.useCase = useCase;
        this.applicationContext = applicationContext;
        this.readerThread = new Thread(this::readLoop, "stdin-reader");
        this.readerThread.setDaemon(true);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting stdin reader thread");
        readerThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                processLine(line);
            }
            log.info("stdin closed (EOF), reader thread exiting");
        } catch (IOException e) {
            log.error("stdin read failed, reader thread exiting", e);
        } finally {
            requestShutdown();
        }
    }

    private void processLine(String line) {
        if (line.isBlank()) {
            return;
        }
        if (line.length() > MAX_LINE_LENGTH) {
            log.warn("Dropping oversized line [length={}, max={}]", line.length(), MAX_LINE_LENGTH);
            return;
        }
        log.debug("Received line [length={}]", line.length());
        useCase.handle(new RawReading(line, Instant.now()));
    }

    private void requestShutdown() {
        if (applicationContext.isActive()) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
