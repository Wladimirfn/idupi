# Huawei Health Kit — registration guide (future connector)

Huawei Health does not write to Android Health Connect. The official route is
**Health Kit (Health Service Kit)**, which needs a HUAWEI Developer account, an
app reviewed by Huawei, OAuth 2.0 with the owner's HUAWEI ID and cloud REST
calls. This guide prepares that registration; nothing here is integrated yet.

Status: **not registered, not enabled**. `lib/huawei-health-kit.mjs` only reads
a disabled config shape and reports it in `GET /api/v1/health/sources`. No
token request, no OAuth redirect and no health API call runs today.

The steps below are owner-facing (neutral Latin American Spanish, "tú"). The
official pages are cited at the end; anything this repository could not verify
is marked **sin verificar**.

## 1. Create the HUAWEI Developer account

1. Entra a <https://developer.huawei.com/consumer/en/> y crea una cuenta
   HUAWEI ID (o usa la que ya tienes).
2. Completa la verificación de identidad del desarrollador. Huawei pide datos
   personales o de empresa según el tipo de cuenta; sin verificación no se
   pueden publicar apps ni solicitar kits.
3. Acepta los términos de desarrollador.

Tiempo típico de la verificación de la cuenta HUAWEI ID: **unos 3 días
hábiles** (dato del propietario; puede variar). **Sin verificar** contra la
documentación oficial en esta sesión.

## 2. Create the project and the app in AppGallery Connect

1. En <https://developer.huawei.com/consumer/en/service/josp/agc/index.html>
   crea un proyecto.
2. Dentro del proyecto crea una app Android con el `applicationId` real de
   IDUPI (`com.idupi.app`, o el que se decida al integrar).
3. Registra la huella SHA-256 del keystore de firma. Health Kit valida la
   firma de la app; un keystore distinto cambia el identificador del cliente.
4. Anota el **App ID**, el **Client ID** y el **Client Secret** que genera
   AppGallery Connect. El secreto no se guarda en el repositorio: va en la
   variable de entorno `IDUPI_HUAWEI_CLIENT_SECRET`.

## 3. Enable HUAWEI ID and apply for Health Kit

1. En el proyecto, habilita **HUAWEI ID** (es el proveedor de identidad del
   flujo OAuth).
2. Abre **API Management / Health Kit** y solicita acceso a **Health Service
   Kit**.
3. Solicita los permisos de datos que IDUPI necesita:
   - pasos (steps)
   - sueño (sleep)
   - frecuencia cardíaca (heart rate)
   - estrés (stress)
   - oxígeno en sangre (SpO₂)
   - peso (weight)

   Los nombres y las cadenas exactas de los scopes **cambian entre versiones**
   y deben copiarse de la referencia oficial de scopes en el momento del
   registro: <https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/scope-0000001050048974>
   (**sin verificar** en esta sesión; la URL puede haber cambiado).
4. Guarda los scopes concedidos en `data/huawei-health-kit.json` (ver más
   abajo). No inventes cadenas de scopes: si no aparecen en la consola, no se
   usan.

## 4. What the review asks for

Huawei revisa el uso real de los datos antes de habilitar Health Kit en
producción. Prepara:

- una **política de privacidad** pública (URL accesible, en español o inglés)
  que describa qué datos de salud se leen, dónde se guardan (el PC del
  propietario) y con quién se comparten (nadie más);
- una **descripción del caso de uso**: resumen diario de salud y bienestar para
  el propietario, sin diagnóstico ni consejo médico;
- **capturas de pantalla** de la app donde se vea la pantalla "Salud" y el
  resumen generado;
- el detalle de los scopes pedidos y por qué cada uno es necesario.

Huawei puede pedir cambios o rechazar scopes que no estén justificados. El
tiempo típico del trámite de Health Kit es de **hasta unos 15 días hábiles**
(dato del propietario). **Sin verificar** contra la documentación oficial.

**Modo de prueba:** Huawei permite probar con un número limitado de cuentas
(el propietario reporta **100 usuarios** de prueba). **Sin verificar**; confirma
el límite en la consola al registrar la app.

## 5. OAuth 2.0 authorization-code flow

El flujo previsto, con la cuenta HUAWEI ID del propietario:

1. La app abre la pantalla de autorización de Huawei con `client_id`,
   `redirect_uri` y los scopes concedidos.
2. El propietario inicia sesión y aprueba los scopes.
3. Huawei redirige a `redirect_uri` con un `code` de corta vida.
4. El servidor (o la app, según dónde se guarde el secreto) canjea el `code`
   por un `access_token` y un `refresh_token`.
5. Los tokens se renuevan con el `refresh_token`; el `access_token` caduca y
   **nunca** se escribe en un log ni en el repositorio.

Detalles exactos de endpoints, parámetros y vigencia de tokens: consulta la
guía oficial de acceso en el momento de la integración
(<https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/access-process-0000001624467736>).
No se implementa el flujo todavía.

## 6. Cloud REST endpoint family

La lectura de datos va por la familia de endpoints en la nube:

```
https://health-api.cloud.huawei.com/healthkit/v1/...
```

Un ejemplo citado en la investigación previa es `sampleSet:polymerize`
(consulta agregada de muestras). La ruta exacta, el método y el cuerpo
**deben confirmarse** contra la referencia oficial de la API al integrar:
**sin verificar** en esta sesión. No hay ninguna llamada de red en este
repositorio todavía.

## 7. Disabled connector config shape

`idupi-server/data/huawei-health-kit.json` (gitignored, nunca se commitea):

```json
{
  "enabled": false,
  "clientId": "<AppGallery Connect client id>",
  "redirectUri": "idupi://oauth/huawei-health",
  "scopes": [
    "<scope de pasos, copiado de la consola>",
    "<scope de sueño, copiado de la consola>"
  ],
  "clientSecretEnv": "IDUPI_HUAWEI_CLIENT_SECRET"
}
```

- `enabled` queda en `false` hasta que el registro esté aprobado.
- `clientId`, `redirectUri` y `scopes` son la configuración no secreta.
- El **client secret** no va en el archivo: se lee de
  `IDUPI_HUAWEI_CLIENT_SECRET` (o de la variable que nombre
  `clientSecretEnv`).
- `lib/huawei-health-kit.mjs` expone
  `huaweiHealthKitStatus()` → `{ enabled, configured, reason }`:
  - `disabled`: no hay configuración y/o está apagado;
  - `registered_but_disabled`: la configuración está completa pero
    `enabled` sigue en `false`;
  - `missing_config:<campos>`: falta `clientId`, `redirectUri`, `scopes` o la
    variable del secreto;
  - `ready`: habilitado y completo (todavía sin llamadas de red).
- `GET /api/v1/health/sources` lo publica en `futureSources` con
  `{id: "huawei_health_kit", label, enabled, configured, reason}` y sin
  capturas.

## 8. What IDUPI does and does not do today

- **Sí**: muestra la fuente futura en `GET /api/v1/health/sources`, lee el
  archivo de configuración y reporta su estado sin hacer red.
- **No**: no pide tokens, no abre el flujo OAuth, no llama a
  `health-api.cloud.huawei.com`, no guarda datos de Huawei Health Kit y no
  escribe nada en el teléfono.
- El camino activo para Huawei Health hoy es el lector de pantalla oculta por
  ADB (ver [`phone.md`](phone.md), "Hidden-display refresh").

## Official references

- Overview: <https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/overview-0000001177423513>
- Access process: <https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/access-process-0000001624467736>
- Verification / review: <https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/verification-0000001211587947>

Las URLs y los tiempos se consultaron el 2026-11-24 desde la investigación del
propietario; la consola de Huawei es la fuente definitiva en el momento del
registro. Todo lo marcado **sin verificar** debe confirmarse ahí.
