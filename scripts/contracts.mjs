#!/usr/bin/env node
// Sincroniza los contratos de ActaFlow en un repositorio consumidor.
//
// Copia exacta en actaflow-frontend, actaflow-backend y actaflow-ai-engine (scripts/contracts.mjs).
// La fuente original y sus tests viven en actaflow-contracts/tools.
//
//   node scripts/contracts.mjs sync v0.1.0     trae esa version y actualiza contracts.lock.json
//   node scripts/contracts.mjs check           los archivos de contracts/ coinciden con el lock
//   node scripts/contracts.mjs check --remote  ademas, el lock coincide con la etiqueta publicada
//
// Solo usa Node y git (sin consola de por medio), asi funciona igual en Windows y Linux
// aunque la ruta tenga espacios.
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

export const REPOSITORIO_POR_DEFECTO = 'https://github.com/dramirezdlp99/actaflow-contracts.git'
export const CARPETA = 'contracts'
export const LOCK = 'contracts.lock.json'
const VERSION_VALIDA = /^v\d+\.\d+\.\d+$/

/** SHA-256 del archivo con saltos de linea normalizados (Git en Windows puede usar CRLF). */
export function huella(ruta) {
  const texto = readFileSync(ruta, 'utf8').replace(/\r\n/g, '\n')
  return createHash('sha256').update(texto, 'utf8').digest('hex')
}

/** Mapa ruta-relativa -> huella de todos los .proto bajo `raiz`, ordenado. */
export function huellas(raiz) {
  const resultado = {}
  const recorrer = (carpeta) => {
    for (const nombre of readdirSync(carpeta).sort()) {
      const ruta = join(carpeta, nombre)
      if (statSync(ruta).isDirectory()) recorrer(ruta)
      else if (nombre.endsWith('.proto')) resultado[relative(raiz, ruta).split(sep).join('/')] = huella(ruta)
    }
  }
  if (existsSync(raiz)) recorrer(raiz)
  return resultado
}

/** Diferencias entre dos mapas de huellas, como mensajes legibles. */
export function diferencias(esperado, actual) {
  const problemas = []
  for (const [archivo, h] of Object.entries(esperado)) {
    if (!(archivo in actual)) problemas.push(`falta ${archivo}`)
    else if (actual[archivo] !== h) problemas.push(`modificado ${archivo}`)
  }
  for (const archivo of Object.keys(actual)) {
    if (!(archivo in esperado)) problemas.push(`sobra ${archivo}`)
  }
  return problemas
}

function clonar(repositorio, version) {
  const temporal = mkdtempSync(join(tmpdir(), 'actaflow-contracts-'))
  execFileSync('git', ['-c', 'advice.detachedHead=false', 'clone', '--quiet', '--depth', '1', '--branch', version, repositorio, temporal], {
    stdio: ['ignore', 'ignore', 'inherit'],
  })
  return temporal
}

export function sincronizar(raizConsumidor, version, repositorio = REPOSITORIO_POR_DEFECTO) {
  if (!VERSION_VALIDA.test(version)) throw new Error(`Version invalida: ${version} (usa vX.Y.Z)`)
  const temporal = clonar(repositorio, version)
  try {
    const destino = join(raizConsumidor, CARPETA)
    rmSync(destino, { recursive: true, force: true })
    cpSync(join(temporal, 'proto'), destino, { recursive: true })
    const lock = { repository: repositorio, version, files: huellas(destino) }
    writeFileSync(join(raizConsumidor, LOCK), `${JSON.stringify(lock, null, 2)}\n`)
    return lock
  } finally {
    rmSync(temporal, { recursive: true, force: true })
  }
}

export function leerLock(raizConsumidor) {
  return JSON.parse(readFileSync(join(raizConsumidor, LOCK), 'utf8'))
}

export function comprobar(raizConsumidor, { remoto = false } = {}) {
  const lock = leerLock(raizConsumidor)
  const problemas = diferencias(lock.files, huellas(join(raizConsumidor, CARPETA)))
  if (remoto && problemas.length === 0) {
    const temporal = clonar(lock.repository, lock.version)
    try {
      for (const p of diferencias(huellas(join(temporal, 'proto')), lock.files)) {
        problemas.push(`${p} respecto a ${lock.version} publicado`)
      }
    } finally {
      rmSync(temporal, { recursive: true, force: true })
    }
  }
  return { lock, problemas }
}

function principal(argumentos) {
  const [orden, ...resto] = argumentos
  const raiz = process.cwd()
  if (orden === 'sync' && resto[0]) {
    const lock = sincronizar(raiz, resto[0], resto[1])
    console.log(`Contratos ${lock.version}: ${Object.keys(lock.files).length} archivos en ${CARPETA}/`)
    return 0
  }
  if (orden === 'check') {
    const { lock, problemas } = comprobar(raiz, { remoto: resto.includes('--remote') })
    if (problemas.length > 0) {
      console.error(`Los contratos no coinciden con ${lock.version}:\n  ${problemas.join('\n  ')}`)
      console.error(`Ejecuta: node scripts/contracts.mjs sync ${lock.version}`)
      return 1
    }
    console.log(`Contratos ${lock.version} verificados (${Object.keys(lock.files).length} archivos)`)
    return 0
  }
  console.error('Uso: node scripts/contracts.mjs sync vX.Y.Z | check [--remote]')
  return 2
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(principal(process.argv.slice(2)))
}
