package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.usecase.port.CredentialPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConsoleCredentialPublisher implements CredentialPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCredentialPublisher.class);

    @Override
    public void publish(Credential credential) {
        log.info("Credential extracted: {}", credential.value());
        System.out.println(credential.value());
    }
}
