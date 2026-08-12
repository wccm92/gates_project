package com.gates.msgates.adapters;

import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock notifier for local development: skips the real HTTP call to the Axis
 * device and always reports a successful (200) notification.
 *
 * Activated with {@code app.access.mock=true} (env var {@code ACCESS_MOCK=true}).
 * When enabled, {@link HttpAccessNotifier} is NOT registered.
 */
@Component
@ConditionalOnProperty(name = "app.access.mock", havingValue = "true")
public class MockAccessNotifier implements AccessNotifierPort {

    private static final Logger log = LoggerFactory.getLogger(MockAccessNotifier.class);

    @Override
    public int notify(String idPort) {
        log.info("[MOCK] notificación HTTP simulada — idPort={} → status=200", idPort);
        return 200;
    }
}
