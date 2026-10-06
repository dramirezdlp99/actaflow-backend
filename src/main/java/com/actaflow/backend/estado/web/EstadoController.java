package com.actaflow.backend.estado.web;

import com.actaflow.backend.estado.application.ConsultarEstadoService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de las capas. Publico en el sentido de que no exige sesion de usuario,
 * pero, como todo {@code /api/**}, solo acepta peticiones firmadas por el BFF.
 */
@RestController
@RequestMapping("/api/v1")
public class EstadoController {

    private final ConsultarEstadoService servicio;

    public EstadoController(ConsultarEstadoService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/status")
    public RespuestaEstado status() {
        return RespuestaEstado.de(servicio.consultar());
    }
}
