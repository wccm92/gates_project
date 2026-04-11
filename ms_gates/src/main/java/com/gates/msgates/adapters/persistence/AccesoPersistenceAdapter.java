package com.gates.msgates.adapters.persistence;

import com.gates.msgates.adapters.persistence.entity.ControlAccesoEntity;
import com.gates.msgates.adapters.persistence.entity.RegistroAccesoEntity;
import com.gates.msgates.adapters.persistence.repository.ControlAccesoJpaRepository;
import com.gates.msgates.adapters.persistence.repository.RegistroAccesoJpaRepository;
import com.gates.msgates.domain.model.ControlAcceso;
import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;
import com.gates.msgates.domain.port.out.ControlAccesoRepositoryPort;
import com.gates.msgates.domain.port.out.RegistroAccesoRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AccesoPersistenceAdapter implements ControlAccesoRepositoryPort, RegistroAccesoRepositoryPort {

    private final ControlAccesoJpaRepository controlAccesoRepo;
    private final RegistroAccesoJpaRepository registroAccesoRepo;

    // ---- ControlAccesoRepositoryPort ----

    @Override
    public boolean tieneAccesoValido(String cedula, Long molineteId, LocalDateTime ahora) {
        return controlAccesoRepo.existeAccesoValido(cedula, molineteId, ahora);
    }

    @Override
    public ControlAcceso guardar(ControlAcceso controlAcceso) {
        return toDomain(controlAccesoRepo.save(toEntity(controlAcceso)));
    }

    @Override
    public Optional<ControlAcceso> buscarPorId(Long id) {
        return controlAccesoRepo.findById(id).map(this::toDomain);
    }

    @Override
    public void revocar(Long id) {
        controlAccesoRepo.findById(id).ifPresent(e -> {
            e.setActivo(false);
            controlAccesoRepo.save(e);
        });
    }

    @Override
    public List<ControlAcceso> listarPorCedula(String cedula) {
        return controlAccesoRepo.findByCedula(cedula).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ControlAcceso> listarPorMolinete(Long molineteId) {
        return controlAccesoRepo.findByMolineteId(molineteId).stream().map(this::toDomain).toList();
    }

    // ---- RegistroAccesoRepositoryPort ----

    @Override
    public void registrar(RegistroAcceso registro) {
        RegistroAccesoEntity entity = new RegistroAccesoEntity();
        entity.setCedula(registro.cedula());
        entity.setMolineteId(registro.molineteId());
        entity.setResultado(registro.resultado());
        entity.setMotivo(registro.motivo());
        entity.setTimestamp(registro.timestamp());
        registroAccesoRepo.save(entity);
    }

    @Override
    public List<RegistroAcceso> buscar(String cedula, Long molineteId,
                                        LocalDateTime desde, LocalDateTime hasta,
                                        ResultadoAcceso resultado) {
        return registroAccesoRepo
                .buscarConFiltros(cedula, molineteId, desde, hasta, resultado)
                .stream()
                .map(e -> new RegistroAcceso(e.getCedula(), e.getMolineteId(),
                        e.getResultado(), e.getMotivo(), e.getTimestamp()))
                .toList();
    }

    // ---- Mapeos ----

    private ControlAcceso toDomain(ControlAccesoEntity e) {
        return new ControlAcceso(e.getId(), e.getCedula(), e.getMolineteId(),
                e.getFechaInicio(), e.getFechaFin(), e.isActivo());
    }

    private ControlAccesoEntity toEntity(ControlAcceso ca) {
        ControlAccesoEntity e = new ControlAccesoEntity();
        e.setId(ca.id());
        e.setCedula(ca.cedula());
        e.setMolineteId(ca.molineteId());
        e.setFechaInicio(ca.fechaInicio());
        e.setFechaFin(ca.fechaFin());
        e.setActivo(ca.activo());
        return e;
    }
}
