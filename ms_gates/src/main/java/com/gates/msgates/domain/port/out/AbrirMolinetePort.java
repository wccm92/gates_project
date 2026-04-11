package com.gates.msgates.domain.port.out;

/**
 * Puerto de salida: envía señal al dispositivo Axis para abrir el molinete.
 */
public interface AbrirMolinetePort {

    /**
     * Envía la señal de apertura al dispositivo Axis asociado al molinete.
     *
     * @param molineteId identificador del molinete
     * @return true si el dispositivo confirmó la apertura, false en caso de error
     */
    boolean abrir(Long molineteId);
}
