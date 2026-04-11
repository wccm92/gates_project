package com.gates.msgates.adapters.persistence;

import com.gates.msgates.adapters.persistence.entity.PersonaEntity;
import com.gates.msgates.adapters.persistence.repository.PersonaJpaRepository;
import com.gates.msgates.domain.model.Persona;
import com.gates.msgates.domain.port.out.PersonaRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PersonaPersistenceAdapter implements PersonaRepositoryPort {

    private final PersonaJpaRepository repository;

    @Override
    public Optional<Persona> buscarPorCedula(String cedula) {
        return repository.findByCedula(cedula).map(this::toDomain);
    }

    @Override
    public boolean existePorCedula(String cedula) {
        return repository.existsByCedula(cedula);
    }

    @Override
    public Persona guardar(Persona persona) {
        PersonaEntity entity = toEntity(persona);
        return toDomain(repository.save(entity));
    }

    @Override
    public List<Persona> listar(boolean soloActivos) {
        List<PersonaEntity> entities = soloActivos
                ? repository.findByActivo(true)
                : repository.findAll();
        return entities.stream().map(this::toDomain).toList();
    }

    // ---- Mapeos ----

    private Persona toDomain(PersonaEntity e) {
        return new Persona(e.getId(), e.getCedula(), e.getNombre(),
                e.getApellido(), e.getEmail(), e.isActivo());
    }

    private PersonaEntity toEntity(Persona p) {
        PersonaEntity e = new PersonaEntity();
        e.setId(p.id());
        e.setCedula(p.cedula());
        e.setNombre(p.nombre());
        e.setApellido(p.apellido());
        e.setEmail(p.email());
        e.setActivo(p.activo());
        return e;
    }
}
