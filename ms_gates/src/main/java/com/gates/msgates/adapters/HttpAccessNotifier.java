package com.gates.msgates.adapters;

import com.gates.msgates.config.AccessHttpProperties;
import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
public class HttpAccessNotifier implements AccessNotifierPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessNotifier.class);

    private final RestClient restClient;
    private final String path;

    public HttpAccessNotifier(RestClient accessRestClient, AccessHttpProperties props) {
        this.restClient = accessRestClient;
        this.path = props.path();
    }

    @Override
    public int notify(Credential credential) {
        String body = "{\"doc\":\"" + credential.value() + "\"}";
        log.debug("Sending HTTP access notification — path={}, body={}", path, body);
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Source", "ms-gates")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            log.debug("HTTP access notification response — status={}", status);
            return status;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            log.debug("HTTP access notification non-2xx response — status={}", status);
            return status;
        } catch (Exception e) {
            log.error("HTTP access notification transport error", e);
            return -1;
        }
    }
}
