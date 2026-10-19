package com.actaflow.backend.identidad.aplicacion;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.actaflow.backend.identidad.aplicacion.Cuentas.Cuenta;
import com.actaflow.backend.identidad.dominio.Criptografia;
import com.actaflow.backend.plataforma.rpc.FalloRpc;
import com.actaflow.backend.plataforma.rpc.FalloRpc.Tipo;
import com.actaflow.backend.identidad.dominio.Principal;
import com.actaflow.backend.identidad.dominio.Rol;
import com.actaflow.backend.plataforma.persistencia.Bd;
import com.actaflow.contracts.common.v1.PageInfo;
import com.actaflow.contracts.identity.v1.AccountPublicKey;
import com.actaflow.contracts.identity.v1.InviteMemberResponse;
import com.actaflow.contracts.identity.v1.ListMembersResponse;
import com.actaflow.contracts.identity.v1.Member;
import com.actaflow.contracts.identity.v1.Organization;

/** Organizacion, miembros, invitaciones y directorio de claves publicas. */
public final class ServicioOrganizacion {

    static final Duration VIGENCIA_INVITACION = Duration.ofDays(7);

    private final Bd bd;
    private final Criptografia cripto;
    private final Clock reloj;

    public ServicioOrganizacion(Bd bd, Criptografia cripto, Clock reloj) {
        this.bd = bd;
        this.cripto = cripto;
        this.reloj = reloj;
    }

    public Organization organizacion(Principal principal) {
        return bd.transaccion(c -> Bd.uno(c, "select id, name, tax_id, city from organization where id = ?",
                f -> Organization.newBuilder().setId(Bd.uuid(f, "id").toString()).setName(f.getString("name"))
                        .setTaxId(f.getString("tax_id")).setCity(f.getString("city")).build(),
                principal.organizacionId()))
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Organizacion no encontrada"));
    }

    /** Miembros de la organizacion, por fecha de alta. Los externos no pueden listarlos. */
    public ListMembersResponse miembros(Principal principal, int tamano, String token, Optional<Rol> rol) {
        exigirInterno(principal);
        int desde = desplazamiento(token);
        List<Cuenta> pagina = bd.transaccion(c -> Bd.varios(c, "select " + Cuentas.COLUMNAS
                + " from account where organization_id = ?"
                + " and (cast(? as varchar) is null or role = cast(? as varchar))"
                + " order by created_at, id limit ? offset ?", Cuentas::leer, principal.organizacionId(),
                rol.map(Rol::name).orElse(null), rol.map(Rol::name).orElse(null), tamano + 1, desde));
        ListMembersResponse.Builder respuesta = ListMembersResponse.newBuilder();
        pagina.stream().limit(tamano).map(ServicioOrganizacion::miembro).forEach(respuesta::addMembers);
        String siguiente = pagina.size() > tamano ? token(desde + tamano) : "";
        return respuesta.setPageInfo(PageInfo.newBuilder().setNextPageToken(siguiente)).build();
    }

    /**
     * Invita a una persona por correo. El token se devuelve una sola vez (el servidor guarda su
     * hash); un reintento con la misma clave de idempotencia renueva el token de esa invitacion.
     */
    public InviteMemberResponse invitar(Principal principal, String correo, Rol rol, UUID idempotencia) {
        exigirAdmin(principal);
        String normalizado = ServicioAutenticacion.normalizarCorreo(correo);
        Instant ahora = reloj.instant();
        String token = Criptografia.tokenAleatorio(32);
        byte[] hash = cripto.huella("invitation", token);
        Instant vence = ahora.plus(VIGENCIA_INVITACION);
        UUID id = bd.transaccion(c -> {
            if (Cuentas.porCorreo(c, normalizado, false).isPresent()) {
                throw new FalloRpc(Tipo.YA_EXISTE, "Ya existe una cuenta con ese correo");
            }
            Optional<UUID> previa = Bd.uno(c, "select id from invitation where organization_id = ?"
                    + " and idempotency_key = ? and used_at is null for update", f -> Bd.uuid(f, "id"),
                    principal.organizacionId(), idempotencia);
            if (previa.isPresent()) {
                Bd.ejecutar(c, "update invitation set token_hash = ?, expires_at = ? where id = ?", hash, vence,
                        previa.get());
                return previa.get();
            }
            UUID nueva = UUID.randomUUID();
            Bd.ejecutar(c, "insert into invitation (id, organization_id, email, role, token_hash, expires_at,"
                    + " created_by, idempotency_key, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", nueva,
                    principal.organizacionId(), normalizado, rol.name(), hash, vence, principal.cuentaId(),
                    idempotencia, ahora);
            return nueva;
        });
        return InviteMemberResponse.newBuilder().setInvitationId(id.toString())
                .setExpiresAt(Cuentas.marca(vence)).setInvitationToken(token).build();
    }

    /** Cambia el rol de un miembro; nunca deja la organizacion sin administradores activos. */
    public Member cambiarRol(Principal principal, UUID cuentaId, Rol rol) {
        exigirAdmin(principal);
        return bd.transaccion(c -> {
            Cuenta cuenta = miembroDe(c, principal, cuentaId);
            boolean pierdeAdmin = cuenta.rol() == Rol.ADMIN && rol != Rol.ADMIN && cuenta.activa();
            if (pierdeAdmin && adminsActivos(c, principal) <= 1) {
                throw new FalloRpc(Tipo.PRECONDICION, "La organizacion debe conservar un administrador");
            }
            Bd.ejecutar(c, "update account set role = ? where id = ?", rol.name(), cuentaId);
            return miembro(Cuentas.porId(c, cuentaId, false).orElseThrow());
        });
    }

    /** Activa o desactiva un miembro. Desactivar cierra todas sus sesiones. */
    public Member activar(Principal principal, UUID cuentaId, boolean activa) {
        exigirAdmin(principal);
        if (principal.cuentaId().equals(cuentaId)) {
            throw new FalloRpc(Tipo.PRECONDICION, "No puedes cambiar tu propio estado");
        }
        Instant ahora = reloj.instant();
        return bd.transaccion(c -> {
            miembroDe(c, principal, cuentaId);
            Bd.ejecutar(c, "update account set active = ? where id = ?", activa, cuentaId);
            if (!activa) {
                Bd.ejecutar(c, "update refresh_token set revoked_at = ? where account_id = ? and revoked_at is null",
                        ahora, cuentaId);
            }
            return miembro(Cuentas.porId(c, cuentaId, false).orElseThrow());
        });
    }

    /** Claves publicas de cuentas de la misma organizacion (para envolver claves de caso). */
    public List<AccountPublicKey> clavesPublicas(Principal principal, List<UUID> ids) {
        List<AccountPublicKey> claves = new ArrayList<>();
        bd.transaccion(c -> {
            for (UUID id : ids) {
                Cuentas.porId(c, id, false)
                        .filter(x -> x.organizacionId().equals(principal.organizacionId()) && x.confirmada())
                        .ifPresent(x -> claves.add(AccountPublicKey.newBuilder().setAccountId(x.id().toString())
                                .setKey(Cuentas.clavePublica(x)).build()));
            }
            return claves;
        });
        return claves;
    }

    private Cuenta miembroDe(Connection c, Principal principal, UUID cuentaId) throws SQLException {
        return Cuentas.porId(c, cuentaId, true)
                .filter(x -> x.organizacionId().equals(principal.organizacionId()))
                .orElseThrow(() -> new FalloRpc(Tipo.NO_ENCONTRADO, "Miembro no encontrado"));
    }

    private static int adminsActivos(Connection c, Principal principal) throws SQLException {
        return Bd.uno(c, "select count(*) as n from account where organization_id = ? and role = 'ADMIN' and active",
                f -> f.getInt("n"), principal.organizacionId()).orElse(0);
    }

    private static Member miembro(Cuenta cuenta) {
        return Member.newBuilder().setAccount(Cuentas.contrato(cuenta)).setActive(cuenta.activa())
                .setLastSignInAt(Cuentas.marca(cuenta.ultimoIngreso())).build();
    }

    private static void exigirAdmin(Principal principal) {
        if (!principal.esAdmin()) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Solo un administrador puede hacer esto");
        }
    }

    private static void exigirInterno(Principal principal) {
        if (principal.rol() == Rol.EXTERNAL) {
            throw new FalloRpc(Tipo.SIN_PERMISO, "Las cuentas externas no ven la lista de miembros");
        }
    }

    static String token(int desplazamiento) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("o:" + desplazamiento).getBytes(StandardCharsets.US_ASCII));
    }

    static int desplazamiento(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            String texto = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            int valor = Integer.parseInt(texto.substring(2));
            if (!texto.startsWith("o:") || valor < 0) {
                throw new NumberFormatException();
            }
            return valor;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new FalloRpc(Tipo.ARGUMENTO_INVALIDO, "page_token no valido",
                    List.of(new FalloRpc.Violacion("page.page_token", "page_token.format",
                            "value is not a token returned by this service")));
        }
    }
}
