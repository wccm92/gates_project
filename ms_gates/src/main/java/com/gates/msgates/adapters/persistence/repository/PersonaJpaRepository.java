package com.gates.msgates.adapters.persistence.repository;

import com.gates.msgates.adapters.persistence.entity.PersonaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonaJpaRepository extends JpaRepository<PersonaEntity, Long> {

    Optional<PersonaEntity> findByCedula(String cedula);

    boolean existsByCedula(String cedula);

    List<PersonaEntity> findByActivo(boolean activo);
}
