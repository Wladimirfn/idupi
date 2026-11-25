# Guía de configuración de IDUPI

Esta guía deja IDUPI funcionando de punta a punta: la PC, la app del teléfono, Alertas (WhatsApp, correo y otras apps), el asistente del teléfono, el calendario y Salud. Está escrita a partir de la configuración real que usamos, incluidos los errores que encontramos y cómo se resolvieron.

> Si solo quieres terminales y escritorio remoto, basta con el [README](../README.md). Esta guía es para las funciones de gestión del teléfono.

## Cómo se conecta todo

```
 Teléfono Android (app IDUPI)                       PC con Windows (servidor IDUPI, puerto 8788)
 ─────────────────────────────                      ──────────────────────────────────────────────
 Notificaciones (WhatsApp, Gmail…) ── HTTPS/HTTP ──▶ Bandeja de Alertas ──▶ Agente de IA (OpenCode,
 Estado del teléfono y uso de apps ────────────────▶ Estado del teléfono      Claude, Codex o Pi)
 Calendario del teléfono ──────────────────────────▶ Agenda y propuestas
 Health Connect + bienestar digital ───────────────▶ Salud ──▶ Resumen diario con IA
 Accesibilidad (leer, tocar, escribir) ◀── comandos ─ idupi-phone (CLI para agentes)
                                       ◀── ADB inalámbrico + scrcpy ── Lector de Huawei Health
                                           (pantalla virtual escondida, funciona con el
                                            teléfono bloqueado)
```

- **La app** envía al PC lo que captura (notificaciones, estado, calendario, salud) y ejecuta los comandos que el PC deja en cola.
- **El servidor** guarda todo en `idupi-server/data/` (no se sube a Git) y usa el agente de IA que configures para analizar mensajes y armar resúmenes.
- **ADB inalámbrico + scrcpy** solo se usan para leer apps que no comparten datos con Android, como Huawei Health.
- Teléfono y PC se hablan por la **misma red Wi-Fi** o por **Tailscale** (fuera de casa). Nunca se abre un puerto del router.

## Programas que se necesitan

### En la PC

| Programa | Para qué | Cómo instalarlo | ¿Obligatorio? |
|---|---|---|---|
| Windows 10 u 11 | El servidor y sus helpers son solo para Windows | — | Sí |
| [Node.js](https://nodejs.org/) 22 o superior (probado con 24) | Ejecuta el servidor (sin `npm install`) | Instalador de nodejs.org | Sí |
| [Go](https://go.dev/dl/) 1.21+ | Compila el helper de pantalla y terminales la primera vez | Instalador de go.dev | Sí, para terminales y pantalla |
| Un agente de IA en la línea de comandos: [OpenCode](https://opencode.ai/), Claude Code, Codex o Pi | Analiza los mensajes de Alertas y escribe los resúmenes | Según cada herramienta. Debe quedar con la sesión iniciada | Sí, para Alertas y resúmenes |
| Android SDK Platform-Tools (`adb`) | Conexión inalámbrica con el teléfono para leer Huawei Health | Android Studio (queda en `%LOCALAPPDATA%\Android\Sdk\platform-tools`) o `winget install --id Google.PlatformTools -e` | Solo si usas Huawei/Honor Health |
| [scrcpy](https://github.com/Genymobile/scrcpy) 3.0 o superior (probado con 5.0.1) | Crea la pantalla virtual escondida | `winget install --id Genymobile.scrcpy -e` | Solo si usas Huawei/Honor Health |
| [Tailscale](https://tailscale.com/) | Conectar teléfono y PC fuera de casa | Instalador de tailscale.com, con la misma cuenta en PC y teléfono | Recomendado |

El servidor encuentra `adb` y `scrcpy` solo. Si los instalaste en otra carpeta, indícala con las variables de entorno `IDUPI_ADB` e `IDUPI_SCRCPY`.

### En el teléfono

| App | Para qué |
|---|---|
| IDUPI | La app principal |
| Tailscale (opcional) | Conexión fuera de casa |
| Health Connect | Viene incluido en Android 14 o superior. En versiones anteriores se instala desde Play Store |
| Tus apps de salud | Samsung Health, Mi Fitness, Zepp, Fitbit, Garmin… o Huawei Health / Honor Health |
| Outlook (opcional) | Para que tu calendario de Outlook aparezca en la Agenda |

## Paso a paso

### 1. Servidor en la PC

1. Inicia el servidor:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\start-server.ps1
   ```

2. Para que arranque solo al iniciar Windows (recomendado, porque si la PC se reinicia y el servidor no arranca, el teléfono no puede enviar nada):

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-autostart.ps1
   ```

3. Comprueba que responde:

   ```powershell
   node idupi-server\bin\idupi-term.mjs ls
   ```

   Si dice "No hay terminales", el servidor está corriendo.

El token de acceso queda en `.idupi-token`, en la raíz del proyecto. No lo compartas.

### 2. App y conexión

1. Instala la app (ver [README](../README.md#2-instala-la-app)).
2. En la app, elige **Local / LAN** (misma Wi-Fi) o **Tailscale VPN**.
3. Host: la IP de la PC (`192.168.x.x` en casa o `100.x.y.z` con Tailscale). Puerto: `8788`.
4. Pega el token y toca **Conectar**.

Las actualizaciones se envían desde la PC con `idupi-push <archivo.apk>` y llegan como notificación al teléfono. **Instálalas siempre**: varias correcciones solo llegan con la versión nueva.

### 3. Alertas (WhatsApp, correo y otras apps)

1. En la app, entra a **Ajustes > ALERTAS** y toca **"Dar acceso a notificaciones"**.
2. Si Android no te deja activarlo (pasa con apps instaladas fuera de Play Store), entra a *Ajustes del teléfono > Apps > IDUPI > ⋮ (tres puntos) > "Permitir ajustes restringidos"*. Después vuelve a intentar.
3. En **Apps a seguir** quedan visibles las apps que sigues. Con **"Ver todas las apps"** eliges otras (Messenger, Telegram, Instagram…).
4. En **Estado de captura** revisa que diga **"Escuchando: sí"**. Si dice "no", toca **Reconectar**. Con **"Leer notificaciones ahora"** se envían las notificaciones que ya estaban en la bandeja.
5. Configura el agente de IA (agente, modelo y esfuerzo). Por defecto se usa OpenCode con `opencode-go/deepseek-v4.1-flash` y esfuerzo máximo.

Sobre la respuesta automática:

- Viene apagada.
- Cuando la activas, envía **solo tu texto fijo**, nunca un texto escrito por la IA.
- Funciona solo en WhatsApp, y como máximo una vez cada 6 horas por conversación.

**Grupos silenciados:** un chat silenciado dentro de WhatsApp, Telegram o Messenger no genera notificaciones, salvo cuando te mencionan, así que IDUPI no puede leerlo. Para que lo lea:

1. Quítale el silencio en la app original.
2. En IDUPI, márcalo como **"Silenciar en el teléfono (seguir leyendo)"**. IDUPI guarda cada mensaje y borra la notificación al instante.

### 4. Asistente del teléfono (control desde el PC)

En **Ajustes > ASISTENTE DEL TELÉFONO**:

| Ajuste | Para qué |
|---|---|
| Enviar estado del teléfono a la PC | Batería, señal, red, memoria, almacenamiento y uso de hoy |
| Acceso a datos de uso | Tiempo por app y app en primer plano. También lo usa Salud (bienestar digital) |
| Accesibilidad + **Control desde la PC** | El PC puede leer la pantalla, tocar, escribir y abrir apps. Viene apagado |
| Tipo de red (4G/5G) | Permiso opcional para informar la red móvil |

Accesibilidad también puede pedir "Permitir ajustes restringidos" (mismo camino que en Alertas). Después de permitirlo hay que **activar IDUPI dentro de Accesibilidad**: a veces aparece en "Apps instaladas" o "Servicios descargados".

Comandos en la PC:

```powershell
node idupi-server\bin\idupi-phone.mjs status     # estado del teléfono
node idupi-server\bin\idupi-phone.mjs screen     # lo que se ve en pantalla
node idupi-server\bin\idupi-phone.mjs help       # todos los comandos
```

> **Regla de seguridad:** IDUPI puede observar todo, pero las acciones de riesgo (enviar, aprobar, pagar, borrar) siempre piden tu aprobación en el teléfono.

### 5. Calendario

1. En **Ajustes > CALENDARIO**, da el permiso de calendario y deja activado **"Compartir mi agenda con el PC"**.
2. **Outlook:** abre la app de Outlook y activa **"Sincronizar calendarios"**. Así sus eventos quedan en el calendario del teléfono, que es el que lee IDUPI.
3. Toca **"Enviar ahora"** y comprueba en la PC:

   ```powershell
   node idupi-server\bin\idupi-phone.mjs calendar        # agenda de 7 días
   node idupi-server\bin\idupi-phone.mjs calendar free   # horas libres
   ```

Para tener en cuenta:

- IDUPI escribe en tu calendario **solo cuando tocas Guardar**. Las propuestas que haga un agente desde el PC llegan al teléfono para que las apruebes.
- Las reuniones que Outlook marca con "Cancelada:" se ignoran.

### 6. Salud y bienestar

IDUPI junta datos de salud desde tres caminos:

| Fuente | Camino | Qué hay que hacer |
|---|---|---|
| Samsung Health, Mi Fitness, Zepp, Fitbit, Garmin, Google Fit… | **Health Connect** | En **Ajustes > SALUD**, toca **"Dar permisos de salud"**. Además, en cada app activa el envío a Health Connect (por ejemplo, Mi Fitness > Ajustes > Health Connect) |
| Huawei Health, Honor Health | **Lectura desde el PC** (pantalla escondida) | Configurar ADB inalámbrico (paso 7) |
| Bienestar digital (tiempo en pantalla, desbloqueos, uso nocturno, apps más usadas) | Datos de uso de Android | Dar **Acceso a datos de uso** (paso 4) |

Después abre **Salud** en el menú y toca **"Actualizar ahora"**. El resumen diario lo escribe el agente de IA con tus datos reales y lo cruza con tu agenda.

Ese resumen **no es consejo médico**.

Comandos en la PC:

```powershell
node idupi-server\bin\idupi-phone.mjs health             # resumen de 7 días
node idupi-server\bin\idupi-phone.mjs health sources     # fuentes detectadas
node idupi-server\bin\idupi-phone.mjs health refresh --wait
node idupi-server\bin\idupi-phone.mjs health digest --refresh
```

La API oficial de Huawei (Health Kit) está preparada pero desactivada. Para inscribirla, sigue [`idupi-server/docs/huawei-health-kit.md`](../idupi-server/docs/huawei-health-kit.md).

### 7. ADB inalámbrico (para Huawei / Honor Health)

Se hace **una sola vez**. Con esto el PC abre Huawei Health en una pantalla virtual escondida, la lee y la cierra. Tu pantalla real no se entera, incluso con el teléfono bloqueado.

1. **Activa las opciones de desarrollador:** *Ajustes del teléfono > Acerca del teléfono > Información de software*, y toca 7 veces **"Número de compilación"**.
2. Entra a *Opciones de desarrollador* y activa **"Depuración inalámbrica"**. El teléfono y el PC tienen que estar en la misma Wi-Fi para vincularlos.
3. Dentro de Depuración inalámbrica, toca **"Vincular dispositivo con código de vinculación"**. Aparecen una **IP:puerto** y un **código de 6 dígitos**. Deja esa ventana abierta.
4. En la PC, vincula el teléfono (con la IP:puerto y el código de esa ventana):

   ```powershell
   node idupi-server\bin\idupi-phone.mjs health adb pair 192.168.1.x:PUERTO CODIGO
   ```

5. Conecta el teléfono con la **"Dirección IP y puerto"** que aparece arriba en la pantalla de Depuración inalámbrica. **Es otro puerto, distinto del de vinculación.** Si esa dirección es `100.x.y.z`, es la de Tailscale y también sirve:

   ```powershell
   node idupi-server\bin\idupi-phone.mjs health adb connect 192.168.1.x:PUERTO
   ```

6. Comprueba la conexión:

   ```powershell
   node idupi-server\bin\idupi-phone.mjs health adb status
   node idupi-server\bin\idupi-phone.mjs health refresh --wait
   ```

Desde ahí, el servidor lee Huawei Health cada 3 horas y reconecta solo, usando las direcciones guardadas.

## Verificación final

- [ ] `idupi-term ls` responde (servidor corriendo).
- [ ] La app conecta y muestra **Terminales**.
- [ ] **Ajustes > ALERTAS > Estado de captura** dice "Escuchando: sí", y un WhatsApp de prueba aparece en **Alertas**.
- [ ] `idupi-phone status` muestra el teléfono "en línea".
- [ ] `idupi-phone calendar` lista tu agenda.
- [ ] `idupi-phone health sources` muestra tus fuentes con captura.
- [ ] La pantalla **Salud** muestra tarjetas con datos y el resumen del día.

## Problemas conocidos y soluciones

| Síntoma | Causa | Solución |
|---|---|---|
| No llega nada al PC después de reiniciar la PC | El servidor no arrancó | Inicia `start-server.ps1` y deja instalado el arranque automático. Lo que llegó mientras el servidor estaba apagado se reenvía solo (queda en cola hasta 24 horas) |
| "Dar acceso a notificaciones" o Accesibilidad aparecen bloqueados | Android restringe ajustes de apps instaladas fuera de Play Store | *Ajustes > Apps > IDUPI > ⋮ > Permitir ajustes restringidos*, y después activa el permiso |
| El acceso a notificaciones dice "activado" pero no se captura nada | Android desconectó el lector tras una actualización | **Ajustes > ALERTAS > Reconectar**, y después **"Leer notificaciones ahora"** |
| Un grupo no aparece en Alertas | El chat está silenciado en la app original | Quítale el silencio y márcalo en IDUPI como "silenciar pero leer" |
| "No se pudo enviar la agenda" | Servidor desactualizado o apagado | Actualiza y reinicia el servidor, y toca "Enviar ahora" |
| No aparecen los eventos de Outlook | Outlook no sincroniza con el calendario del teléfono | Activa "Sincronizar calendarios" en Outlook |
| Salud dice "Aún no hay datos" aunque el resumen sí aparece | La app instalada es antigua (error corregido en 1.49) | Instala la última versión que envía la PC |
| De Health Connect solo llegan calorías | La app de salud no está escribiendo en Health Connect | Activa el envío a Health Connect dentro de Mi Fitness o Samsung Health, y da todos los permisos en **Ajustes > SALUD** |
| `health adb status` dice "sin teléfono conectado" | El puerto de Depuración inalámbrica cambia cada vez que se reactiva o se reinicia el teléfono | Revisa la nueva "Dirección IP y puerto" y ejecuta `health adb connect`. No hace falta volver a vincular |
| `adb mdns services` no muestra el teléfono | Muchas redes domésticas bloquean el descubrimiento automático | Usa la IP y el puerto que muestra el teléfono, como en el paso 7 |
| La lectura de Huawei falla con `app_not_visible` | La app tardó en abrir en la pantalla escondida | Repite `health refresh --wait`. El lector ya reintenta 3 veces |
| La IP que muestra Depuración inalámbrica empieza con `100.` | Es la IP de Tailscale | Sirve igual. También puedes usar la IP local de la Wi-Fi |

## Privacidad

- Los mensajes, el calendario y los datos de salud salen del teléfono **solo hacia tu PC** y hacia el agente de IA que configuraste.
- Los datos quedan en `idupi-server/data/`, que no se sube a Git.
- Los registros del servidor no guardan el contenido de mensajes, eventos ni valores de salud.
- La lectura de Huawei Health borra siempre sus archivos temporales.

Política completa: [`PRIVACY.md`](../PRIVACY.md).

## Más documentación

| Documento | Contenido |
|---|---|
| [`idupi-server/docs/README.md`](../idupi-server/docs/README.md) | Rutas de la API, scripts y CLIs |
| [`idupi-server/docs/phone.md`](../idupi-server/docs/phone.md) | Asistente del teléfono, calendario y salud, para agentes |
| [`idupi-server/docs/huawei-health-kit.md`](../idupi-server/docs/huawei-health-kit.md) | Inscripción futura de la API oficial de Huawei |
