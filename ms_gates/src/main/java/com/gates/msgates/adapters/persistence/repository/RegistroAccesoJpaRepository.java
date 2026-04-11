package com.gates.msgates.adapters.persistence.repository;

import com.gates.msgates.adapters.persistence.entity.RegistroAccesoEntity;
import com.gates.msgates.domain.model.ResultadoAcceso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RegistroAccesoJpaRepository extends JpaRepository<RegistroAccesoEntity, Long> {

    @Query("""
            SELECT r FROM RegistroAccesoEntity r
            WHERE (:cedula     IS NULL OR r.cedula     = :cedula)
              AND (:molineteId IS NULL OR r.molineteId = :molineteId)
              AND (:desde      IS NULL OR r.timestamp  >= :desde)
              AND (:hasta      IS NULL OR r.timestamp  <= :hasta)
              AND (:resultado  IS NULL OR r.resultado  = :resultado)
            ORDER BY r.timestamp DESC
            """)
    List<RegistroAccesoEntity> buscarConFiltros(
            @Param("cedula") String cedula,
            @Param("molineteId") Long molineteId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta,
            @Param("resultado") ResultadoAcceso resultado);
}
