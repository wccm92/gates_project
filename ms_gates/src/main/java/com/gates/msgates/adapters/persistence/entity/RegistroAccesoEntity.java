package com.gates.msgates.adapters.persistence.entity;

import com.gates.msgates.domain.model.ResultadoAcceso;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "registro_accesos")
@Getter
@Setter
@NoArgsConstructor
public class RegistroAccesoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String cedula;

    @Column(name = "molinete_id")
    private Long molineteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultadoAcceso resultado;

    @Column(length = 255)
    private String motivo;

    @Column(nullable = false)
    private LocalDateTime timestamp;
}
