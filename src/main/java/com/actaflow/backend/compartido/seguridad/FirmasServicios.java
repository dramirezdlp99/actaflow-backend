package com.actaflow.backend.compartido.seguridad;

import java.time.Clock;

import com.actaflow.backend.compartido.config.ActaFlowProperties;

import org.springframework.stereotype.Component;

/**
 * Las dos firmas del backend, cada una con su propio secreto:
 * la del BFF (peticiones entrantes) y la del motor de IA (peticiones salientes).
 */
@Component
public class FirmasServicios {

    private final FirmaHmac bff;
    private final FirmaHmac motor;

    public FirmasServicios(ActaFlowProperties propiedades, Clock reloj) {
        long tolerancia = propiedades.hmac().toleranceSeconds();
        this.bff = new FirmaHmac(propiedades.bff().hmacSecret(), tolerancia, reloj);
        this.motor = new FirmaHmac(propiedades.motor().hmacSecret(), tolerancia, reloj);
    }

    public FirmaHmac bff() {
        return bff;
    }

    public FirmaHmac motor() {
        return motor;
    }
}
