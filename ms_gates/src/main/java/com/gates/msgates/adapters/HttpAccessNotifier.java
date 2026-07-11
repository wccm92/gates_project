package com.gates.msgates.adapters;

import com.gates.msgates.config.AccessHttpProperties;
import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Component
public class HttpAccessNotifier implements AccessNotifierPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessNotifier.class);

    /** Fixed tail appended after the idPort — e.g. action=<idPort>:/1000 */
    private static final String PORT_SUFFIX = ":/1000%5C";


    private final RestClient restClient;
    private final String baseUrl;
    private final String path;

    public HttpAccessNotifier(RestClient accessRestClient, AccessHttpProperties props) {
        this.restClient = accessRestClient;
        this.baseUrl = props.baseUrl();
        this.path = props.path();
    }

    @Override
    public int notify(String idPort) {
        // e.g. http://172.22.30.74/axis-cgi/io/port.cgi?action=1:/1000
        URI uri = URI.create(baseUrl + path + idPort + PORT_SUFFIX);
        log.debug("Sending HTTP access notification (GET) — uri={}", uri);
        try {
            ResponseEntity<Void> response = restClient.get()
                    .uri(uri)
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
