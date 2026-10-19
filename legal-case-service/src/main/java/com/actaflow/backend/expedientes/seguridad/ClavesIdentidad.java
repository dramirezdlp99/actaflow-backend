package com.actaflow.backend.expedientes.seguridad;

import java.lang.System.Logger.Level;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.actaflow.backend.plataforma.seguridad.TokenAcceso;
import com.actaflow.contracts.identity.v1.VerificationKey;

/**
 * Claves publicas con las que identity-service firma los tokens, en cache.
 *
 * <p>Se piden a TokenVerificationService (publico, como un JWKS) la primera vez y cada hora. Si llega
 * un token con un id de clave desconocido (identity roto su secreto), se vuelven a pedir, pero como
 * mucho una vez cada {@link #ESPERA_MINIMA}: un atacante no puede convertir tokens inventados en una
 * avalancha de llamadas a identity.
 */
public final class ClavesIdentidad {

    static final Duration VIGENCIA = Duration.ofHours(1);
    static final Duration ESPERA_MINIMA = Duration.ofSeconds(30);
    private static final System.Logger LOG = System.getLogger(ClavesIdentidad.class.getName());

    private final Supplier<List<VerificationKey>> fuente;
    private final Clock reloj;
    private Map<String, PublicKey> claves = Map.of();
    private Instant pedidasEn = Instant.MIN;
    private Instant ultimoIntento = Instant.MIN;

    /** @param fuente llamada a GetVerificationKeys (en las pruebas, una lista fija) */
    public ClavesIdentidad(Supplier<List<VerificationKey>> fuente, Clock reloj) {
        this.fuente = fuente;
        this.reloj = reloj;
    }

    /** Clave para ese id, pidiendo las claves otra vez si hace falta y esta permitido. */
    public synchronized Optional<PublicKey> clave(String idClave) {
        Instant ahora = reloj.instant();
        boolean vencidas = ahora.isAfter(pedidasEn.plus(VIGENCIA));
        boolean desconocida = !claves.containsKey(idClave);
        if ((vencidas || desconocida) && !ahora.isBefore(ultimoIntento.plus(ESPERA_MINIMA))) {
            pedir(ahora);
        }
        return Optional.ofNullable(claves.get(idClave));
    }

    private void pedir(Instant ahora) {
        ultimoIntento = ahora;
        try {
            Map<String, PublicKey> nuevas = new HashMap<>();
            for (VerificationKey clave : fuente.get()) {
                if ("Ed25519".equals(clave.getAlgorithm())) {
                    nuevas.put(clave.getKeyId(), TokenAcceso.clavePublica(clave.getPublicKeySpki().toByteArray()));
                }
            }
            claves = Map.copyOf(nuevas);
            pedidasEn = ahora;
        } catch (RuntimeException e) {
            // Se conservan las claves anteriores: si identity esta despertando, los tokens ya
            // conocidos se siguen verificando.
            LOG.log(Level.WARNING, "No se pudieron pedir las claves de identity-service: {0}", e.getMessage());
        }
    }
}
