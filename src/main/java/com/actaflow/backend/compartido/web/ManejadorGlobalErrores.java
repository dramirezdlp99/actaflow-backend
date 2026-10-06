package com.actaflow.backend.compartido.web;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Traduce cualquier excepcion a un {@link ApiError}; nunca expone trazas al cliente. */
@RestControllerAdvice
public class ManejadorGlobalErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorGlobalErrores.class);

    private final Clock reloj;

    public ManejadorGlobalErrores(Clock reloj) {
        this.reloj = reloj;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> negocio(ApiException e, HttpServletRequest peticion) {
        return responder(e.estado(), e.codigo(), e.getMessage(), peticion);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noEncontrado(NoResourceFoundException e, HttpServletRequest peticion) {
        return responder(404, "no_encontrado", "Recurso no encontrado", peticion);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> metodo(HttpRequestMethodNotSupportedException e, HttpServletRequest peticion) {
        return responder(405, "metodo_no_permitido", "Metodo no permitido", peticion);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> inesperado(Exception e, HttpServletRequest peticion) {
        LOG.error("Error inesperado en {} {}", peticion.getMethod(), peticion.getRequestURI(), e);
        return responder(500, "error_interno", "Error interno del servidor", peticion);
    }

    private ResponseEntity<ApiError> responder(int estado, String codigo, String mensaje, HttpServletRequest peticion) {
        ApiError error = ApiError.de(estado, codigo, mensaje, peticion.getRequestURI(), reloj);
        return ResponseEntity.status(estado).body(error);
    }
}
