package com.gates.msgates.domain.usecase.port;

import java.util.Optional;

public interface ReaderCachePort {
    Optional<String> findPortId(int idLector);
}
