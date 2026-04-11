package com.gates.msgates.adapters.persistence.repository;

import com.gates.msgates.adapters.persistence.entity.ControlAccesoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ControlAccesoJpaRepository extends JpaRepository<ControlAccesoEntity, Long> {

    /**
     * Verifica si la persona tiene un permiso activo y vigente para el molinete indicado.
     * Un permiso con molineteId = null se aplica a TODOS los molinetes.
     */
    @Query("""
            SELECT CASE WHEN COUNT(ca) > 0 THEN TRUE ELSE FALSE END
            FROM ControlAccesoEntity ca
            WHERE ca.cedula = :cedula
              AND ca.activo = true
              AND (ca.molineteId IS NULL OR ca.molineteId = :molineteId)
              AND (ca.fechaInicio IS NULL OR ca.fechaInicio <= :ahora)
              AND (ca.fechaFin   IS NULL OR ca.fechaFin   >= :ahora)
            """)
    boolean existeAccesoValido(@Param("cedula") String cedula,
                                @Param("molineteId") Long molineteId,
                                @Param("ahora") LocalDateTime ahora);

    List<ControlAccesoEntity> findByCedula(String cedula);

    List<ControlAccesoEntity> findByMolineteId(Long molineteId);
}
