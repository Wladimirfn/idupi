# IDUPI — Tu PC entera, desde el celular

IDUPI es un servidor que vive en tu PC y una app Android que se conecta a él. Desde el teléfono podés **chatear con agentes de IA** (Pi, Claude, OpenCode) que trabajan sobre tus proyectos reales, y además **ver y controlar la pantalla de tu PC**: mover el mouse, tipear, hacer scroll y zoom, como un VNC pero liviano.

Todo corre en TU hardware: ningún dato pasa por servidores de terceros. Fuera de casa se conecta por Tailscale, así que no abrís puertos del router.

## Requisitos

| # | Necesitás | Para qué |
|---|-----------|----------|
| 1 | [Go](https://go.dev/dl/) (1.21+) | Compila el helper de captura de pantalla la primera vez, automáticamente |
| 2 | [Node.js](https://nodejs.org/) (20+) | Corre el servidor |
| 3 | [Tailscale](https://tailscale.com/) (gratis) en la PC **y** el celular | Conecta los dos sin abrir puertos del router |
| 4 | Un celular Android | Donde corre la app |

## Puesta en marcha

### 1. Descargá el repo

```bash
git clone https://github.com/Wladimirfn/idupi.git
cd idupi
```

### 2. Levantá el server (en la PC)

```bash
node idupi-server/index.mjs
```

Al arrancar muestra:

- El **token de acceso** (64 caracteres): es la llave única de TU instalación, se genera sola la primera vez.
- La **IP Tailscale** de tu PC (ej. `100.x.y.z`) y el **puerto**, por defecto `8788`.

> Puerto distinto: `PORT=9000 node idupi-server/index.mjs`

> El helper de pantalla (`idupi-screen.exe`) se compila solo al primer uso. Si querés desactivar el control remoto de mouse/teclado: `IDUPI_REMOTE_INPUT=0` (viene activado).

### 3. Instalá la app

Compilá la APK desde este repo (Android Studio → Run, o `gradlew :app:assembleDebug`; queda en `app/build/outputs/apk/debug/`) e instalala en el celular.

### 4. Conectá

En la app: modo **Tailscale** → host = la IP Tailscale de tu PC → puerto `8788` → pegá el token completo → Conectar.

En casa, si los dos están en el mismo WiFi, podés usar la IP local (ej. `192.168.1.x`) sin Tailscale.

## Verificación

- [ ] El server imprime "listo y escuchando" y tu token
- [ ] La app dice "Conectado"
- [ ] Chateás con un agente y responde
- [ ] En *Pantalla Remota* elegís monitor → "Ver pantalla" y ves tu escritorio en vivo
- [ ] El botón **Trackpad** mueve el cursor de tu PC

## Cómo se desarrolla: Gentle AI + SDD

Este repo se construye con **desarrollo dirigido por especificaciones** (Spec-Driven Development), orquestado por [Gentle AI](https://github.com/Gentleman-Programming/gentle-ai) dentro de OpenCode. Nada entra al código sin pasar por artefactos revisables.

**El flujo de cada cambio** (`openspec/changes/<nombre>/`):

```
exploración → propuesta → spec → diseño → tareas → implementación → verificación → archivo
```

| Fase | Qué produce |
|------|-------------|
| Propuesta | Qué se cambia y por qué |
| Spec | Requisitos y escenarios, en lenguaje verificable |
| Diseño | Decisiones técnicas antes del código |
| Tareas | Pasos chicos, cada uno testeable |
| Verificación | Prueba de que la implementación cumple la spec |

Los cambios terminados quedan archivados en `openspec/changes/archive/` como historia auditable.

**Reglas innegociables del repo**, aplicadas en cada hito:

1. Tests **antes** del código, probados en ROJO.
2. Lógica decidible sin pantalla va a `domain/model/` con test propio.
3. Cero dependencias npm en `idupi-server`; Go stdlib only.
4. Conventional commits, sin atribución a IA.

Para contribuir con el mismo flujo necesitás OpenCode con gentle-ai instalado: los comandos `/sdd-new`, `/sdd-continue`, `/sdd-apply` y `/sdd-verify` manejan el ciclo completo.

## Seguridad

- El token es la única barrera de entrada: no lo compartas, no pegues capturas donde se lea.
- Exponé el server **solo por Tailscale** (o tu LAN). Nunca hagas port-forwarding directo desde el router.
- El control remoto de input viene **activado** porque así se usa; si tu PC la usa otra persona y no querés ese poder activo: `IDUPI_REMOTE_INPUT=0`.
