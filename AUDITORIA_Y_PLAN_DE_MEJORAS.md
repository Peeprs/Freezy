# Auditoría técnica y plan de mejoras de Freezy

Fecha de revisión: 22 de agosto de 2026
Alcance: aplicación Android, autenticación, `LoginActivity`, `MainActivity`, servicios, `LagController`, JNI/C++, configuración de Android, compilación y repositorio.

> Esta auditoría se concentra en seguridad, estabilidad, mantenibilidad y cumplimiento de Android. No propone técnicas para ocultar la aplicación, eludir sistemas antitrampas ni reducir la detección de lectura/inyección sobre procesos ajenos. Root se mantiene como requisito confirmado del proyecto, pero debe aislarse, auditarse y tratar sus fallos explícitamente. La manipulación de memoria de terceros y la identidad engañosa siguen siendo riesgos separados de la decisión de conservar root.

### Decisiones confirmadas del proyecto

- **Root se conserva.** El plan ya no propone retirarlo; propone centralizarlo y hacerlo predecible.
- **La documentación se conserva.** Archivos como `Ghost.txt`, `POSIBLES_OFFSETS.md`, `AQUITECTURA.md`, `PROGRESS.md`, `Fake.txt` y `Teleport.txt` no se consideran basura por su extensión.
- **El HWID se conserva en GET KEY.** Debe funcionar como vínculo y límite de abuso en servidor, pero el navegador no debe poder elegir o reemplazar el HWID mediante la URL.
- **La limpieza del repositorio no se ejecuta automáticamente.** Primero se clasifica cada archivo; cualquier eliminación se realiza después de confirmar que no sea documentación, evidencia necesaria o artefacto de entrega que deba archivarse.

## 1. Resumen ejecutivo

El proyecto compila tanto en `debug` como en `release`, pero aún no está listo para considerarse robusto o seguro. La prioridad no es retocar la interfaz: primero hay que corregir el modelo de autenticación, el ciclo de vida de los servicios y varios fallos nativos.

Los cinco problemas más importantes son:

1. **El secreto HMAC está comprometido.** Está reconstruido dentro del APK y su valor original aparece en un comentario de [`native-lib.cpp`](app/src/main/cpp/native-lib.cpp#L2746). Un secreto compartido incluido en el cliente siempre puede recuperarse; la ofuscación XOR no cambia esto.
2. **El `session_token` se guarda, pero no se usa como sesión real.** Las verificaciones continúan enviando licencia/HWID y firmándose desde el cliente. Además, cerrar sesión no elimina siempre el token.
3. **El pinning TLS puede fallar en modo abierto.** Si configurar el `TrustManager` lanza una excepción, [`WebSecurity.open`](app/src/main/java/com/freezy/WebSecurity.kt#L104) devuelve la conexión normal y continúa.
4. **Los servicios tienen estados y tareas que pueden quedar desincronizados.** `LagController` devuelve éxito aun cuando la operación falle, `BubbleService` confunde errores de red con licencia inválida y existen carreras al iniciar el motor VPN.
5. **Hay un riesgo de cierre fatal en JNI por codificación de texto.** El generador produce UTF-8 estándar y el nativo lo entrega con `NewStringUTF`, que espera Modified UTF-8. Los emoji o caracteres suplementarios pueden provocar un aborto fatal de JNI.

La recomendación general es conservar una aplicación Android convencional y verificable: UI separada de dominio y red, sesión por tokens cortos emitidos por el servidor, TLS que falle cerrado, servicios con una máquina de estados explícita y código nativo limitado a funciones que realmente lo necesiten.

## 2. Resultado verificable de la revisión

### Compilación

- `./gradlew assembleRelease --offline`: **correcto**.
- `./gradlew testDebugUnitTest lintDebug assembleDebug --offline`:
  - APK `debug`: **correcto**.
  - pruebas: `NO-SOURCE`; actualmente no existen pruebas unitarias ejecutables.
  - Lint: **falló**, con **2 errores y 394 advertencias**.
- Los dos errores de Lint están en [`bubble_layout.xml`](app/src/main/res/layout/bubble_layout.xml#L64) y [`bubble_layout.xml`](app/src/main/res/layout/bubble_layout.xml#L81): se usa `android:tint` donde corresponde `app:tint`.
- La compilación nativa de 32 bits avisa de una comparación imposible en [`native_bridge.cpp`](app/src/main/cpp/native_bridge.cpp#L578), porque el límite de 64 bits no cabe en `uintptr_t` de 32 bits.
- R8 avisa que `-repackageclasses` sobreescribe `-flattenpackagehierarchy` en [`proguard-rules.pro`](app/proguard-rules.pro#L89).

### Deuda indicada por Lint

Las categorías más numerosas son:

| Categoría | Cantidad | Lectura práctica |
|---|---:|---|
| `UseKtx` | 148 | Código Android verboso; no es bloqueo, pero dificulta mantenerlo. |
| `HardcodedText` | 64 | Texto fuera de recursos; impide localización y consistencia. |
| `SetTextI18n` | 38 | Construcción manual de texto visible. |
| `SmallSp` | 28 | Tamaños difíciles de leer/accesibilidad. |
| `CutPasteId` | 16 | Repetición de búsquedas de vistas y posible código copiado. |
| `ContentDescription` | 7 | Controles no accesibles. |
| `UnusedResources` | 6 | Recursos muertos. |

Lint también señala permisos sensibles, un archivo temporal legible globalmente, identificación de hardware, un `TrustManager` personalizado, ejecutables nativos dentro de `assets` y asignaciones durante `draw()`.

## 3. Priorización

| Prioridad | Trabajo | Razón |
|---|---|---|
| P0 | Rotar/revocar el secreto HMAC expuesto | Cualquier APK distribuido debe tratarlo como ya extraído. |
| P0 | Sustituir firma compartida en cliente por sesiones emitidas por servidor | La autenticación actual puede falsificarse si el secreto se recupera. |
| P0 | Hacer TLS/pinning `fail closed` | Un fallo local no debe degradar silenciosamente la seguridad del canal. |
| P0 | Corregir cierre de sesión y revocación | El token y el payload nativo pueden sobrevivir a un logout. |
| P0 | Corregir conversión UTF-8/JNI | Puede cerrar el proceso de forma fatal con determinados textos. |
| P0 | No expulsar al usuario por una caída de red | Un error de transporte no demuestra ban, expiración ni revocación. |
| P1 | Centralizar API, repositorio de sesión y errores tipados | Hoy Login, Main y Bubble duplican lógica sensible. |
| P1 | Rediseñar los estados de servicios/controladores | Actualmente se informa éxito antes de conocer el resultado real. |
| P1 | Reemplazar deep link personalizado por App Link verificado | Evita secuestro del esquema y exposición de la licencia. |
| P1 | Aislar y endurecer root; revisar por separado memoria e identidad engañosa | Root seguirá soportado, pero no debe contaminar UI, sesión ni cleanup. |
| P2 | Dividir Activities/Service, usar ViewBinding y coroutines | Reduce errores de ciclo de vida y facilita pruebas. |
| P2 | Resolver Lint, accesibilidad y recursos | Mejora calidad y prepara distribución. |
| P2 | Limpiar repositorio y añadir CI/pruebas | Evita regresiones y artefactos binarios accidentales. |

## 4. Autenticación y seguridad de sesión

### 4.1 Secreto HMAC dentro del APK — P0

**Evidencia**

- [`native-lib.cpp`](app/src/main/cpp/native-lib.cpp#L2745) reconstruye el secreto con XOR.
- En la línea siguiente existe un comentario que contiene el valor original completo.
- Login, Main y Bubble generan HMAC desde el dispositivo.

**Problema**

Un secreto compartido enviado dentro de una aplicación cliente no es secreto. Moverlo a C++, dividirlo o aplicarle XOR solo retrasa su lectura. Después de publicarse, un atacante puede firmar solicitudes sin ejecutar la aplicación.

**Quitar**

- El valor y el comentario en claro.
- `NativeBridge.getHmacSecret()` como autoridad de autenticación.
- La expectativa de que R8, JNI u ofuscación protegen una clave de servidor.

**Implementar**

1. Revocar y rotar inmediatamente la clave actual en el backend.
2. Hacer que el servidor valide credenciales/licencia una sola vez y emita:
   - access token corto;
   - refresh token rotatorio o una sesión renovable;
   - identificador de sesión revocable.
3. Enviar el token con `Authorization: Bearer …`; no volver a firmar desde el APK con una clave compartida.
4. Validar en servidor expiración, audiencia, emisor, revocación y rotación.
5. Añadir rate limiting y registro de eventos de seguridad en servidor, sin guardar licencias completas.
6. Si el canal de distribución lo permite, usar una señal de integridad/atestación como información adicional de riesgo, nunca como único factor.

**Criterio de aceptación**

- Extraer todas las constantes del APK no permite crear una solicitud válida nueva.
- Revocar una sesión en servidor invalida solo esa sesión.
- Rotar claves del servidor no requiere publicar otro APK.

### 4.2 El token de sesión no gobierna la sesión — P0

**Evidencia**

- Login guarda `session_token` en [`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt#L335) y [`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt#L373).
- Main vuelve a guardarlo en [`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt#L956).
- `BubbleService` no lo usa para verificar la sesión.
- [`SessionGuard.clearSession`](app/src/main/java/com/freezy/SessionGuard.kt#L24) elimina varios campos, pero no `session_token`.
- El logout manual de [`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt#L727) conserva credenciales y tampoco limpia todo el estado sensible.

**Mejorar**

- Crear un único `SessionRepository` responsable de leer, escribir, renovar y borrar la sesión.
- Definir estados: `LoggedOut`, `Authenticating`, `Authenticated`, `Refreshing`, `Offline`, `Revoked`.
- Borrar de manera atómica token, licencia recordada, fechas, endpoint antiguo y payload nativo al cerrar sesión.
- Separar “cerrar sesión” de “recordar usuario”. Si existe “recordarme”, debe ser una decisión visible; nunca debe retenerse una licencia por accidente.
- Añadir una función nativa explícita para limpiar y sobrescribir `g_secure_payload`, protegida por mutex.

**Criterio de aceptación**

- Tras logout no quedan `session_token`, licencia, payload ni estado `is_logged_in` utilizable.
- Cerrar o reiniciar una pantalla no crea otra fuente de verdad.
- La UI observa un único flujo de estado de sesión.

### 4.3 Errores de autenticación basados en texto — P1

[`SessionGuard`](app/src/main/java/com/freezy/SessionGuard.kt#L16) decide si hay ban o expiración buscando fragmentos como `bane`, `bloquead` o `expir` dentro de un mensaje humano. Esto es frágil, dependiente del idioma y permite clasificaciones accidentales.

**Implementar**

- Una respuesta común, por ejemplo: `code`, `message`, `request_id`, `server_time` y `data`.
- Códigos cerrados: `INVALID_CREDENTIALS`, `SESSION_REVOKED`, `LICENSE_EXPIRED`, `RATE_LIMITED`, `SERVER_UNAVAILABLE`.
- Mapear códigos a un `sealed interface AuthError`; el mensaje visible no decide el comportamiento.
- Conservar `request_id` en logs para soporte, nunca credenciales.

### 4.4 Almacenamiento seguro — P1

[`SecurePrefs.kt`](app/src/main/java/com/freezy/SecurePrefs.kt) utiliza AES-GCM y Android Keystore, una base razonable, pero necesita ajustes:

- Validar longitud y versión del blob antes de separar IV/ciphertext.
- No silenciar todos los errores devolviendo una cadena vacía; diferenciar “no existe”, “corrupto” y “clave invalidada”.
- Si Keystore invalida la clave, limpiar la sesión de forma controlada y pedir autenticación nuevamente.
- Incluir `hwid_salt` en la migración o retirar ese mecanismo; actualmente se escribe cifrado, pero el nombre no está en el conjunto de migración y `getOrCreateHwidSalt()` no se consume.
- Sacar comprobaciones de hosts concretos de la capa de preferencias. Almacenamiento no debe decidir qué endpoint es válido.
- Hacer que las escrituras críticas devuelvan un resultado. No se debe marcar login correcto si persistir el token falló.

### 4.5 HWID y control de abuso en GET KEY — P0/P1

[`NativeBridge.kt`](app/src/main/java/com/freezy/NativeBridge.kt#L21) usa `ANDROID_ID` y datos variables de `Build`; [`native-lib.cpp`](app/src/main/cpp/native-lib.cpp#L2719) los combina con una sal fija.

Este identificador es frágil ante cambios del dispositivo/ROM, tiene implicaciones de privacidad y, por sí solo, no prueba que la petición provenga de una aplicación íntegra. Aun así, puede conservarse como clave de vínculo y control de frecuencia para GET KEY.

**Recomendación**

- No tratar el HWID como contraseña ni como prueba suficiente de que la petición es legítima.
- Mantenerlo como vínculo de servidor para una operación GET KEY concreta y para aplicar cooldown/cuotas.
- Complementarlo con un identificador de instalación aleatorio y una clave de instalación generada en Android Keystore.
- Permitir recuperación/cambio controlado de dispositivo desde el backend.
- Documentar finalidad, componentes, retención y procedimiento de soporte.

#### Flujo GET KEY recomendado

El flujo actual de [`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt#L157) hace `POST /api/ads/begin`, recibe un token y abre una URL con `token` y `hwid`. El primer paso es adecuado; el problema es volver a aceptar el HWID desde la URL del navegador.

```text
Aplicación                         Servidor                         Navegador
    | POST /ads/begin                 |                                |
    | hwid + installation_id          |                                |
    |-------------------------------->|                                |
    |                  valida cuota, crea registro:                    |
    |                  token_hash -> HWID + instalación + expiración   |
    |<--------------------------------|                                |
    | token opaco, corto y de un uso  |                                |
    | GET /getkey?token=... ------------------------------------------>|
    |                                 |<----- solo presenta token ------|
    |                                 | recupera el HWID guardado        |
    |                                 | completa/rechaza atómicamente    |
```

Reglas de servidor:

1. La página `/getkey` recibe **solo un token opaco**; no recibe `hwid`, licencia ni secretos en la query.
2. Al ejecutar `/api/ads/begin`, el backend guarda el hash del token asociado al HWID, instalación, fecha de expiración, campaña y estado.
3. El token debe tener entropía criptográfica suficiente, vida corta, ser de un solo uso y almacenarse hasheado.
4. El backend nunca reemplaza el HWID vinculado usando parámetros posteriores del navegador.
5. Aplicar una restricción atómica/única por `HWID + campaña/ventana`, además de cooldown y límites por instalación e IP. La UI deshabilitada no es un control de seguridad.
6. Rechazar otro `/ads/begin` mientras exista un flujo vigente o ya completado dentro de la ventana; responder `429` con `retry_after` cuando corresponda.
7. Al completar los pasos, marcar el registro como consumido en una transacción. Dos peticiones simultáneas no deben producir dos keys.
8. Si el servidor devuelve una key o código, mostrarlo en una página autenticada o devolver un código de activación de un solo uso; no incluirlo en callbacks o logs.
9. Opcionalmente, la aplicación firma el desafío con una clave de instalación de Keystore. Esto aporta prueba de posesión de esa instalación, aunque no sustituye rate limiting ni una señal de integridad.

**Por qué esto resuelve la edición manual de URL**

Una URL inventada como `...?hwid=OTRO&token=...` deja de tener efecto porque el servidor ignora cualquier HWID de la página y recupera el original desde el registro del token. Cambiar solo el texto de la URL no cambia la vinculación guardada.

**Criterios de aceptación de GET KEY**

- Alterar o agregar `hwid` en la URL no modifica el destinatario de la key.
- Reutilizar el token devuelve error y no genera otra key.
- Dos solicitudes concurrentes para el mismo HWID producen como máximo una concesión.
- Un token expirado, inexistente o de otra campaña no puede completar el flujo.
- Los logs muestran `request_id`/estado, nunca token completo, HWID completo ni key.

## 5. Capa de red y TLS

### 5.1 Pinning que degrada silenciosamente — P0

En [`WebSecurity.kt`](app/src/main/java/com/freezy/WebSecurity.kt#L104), cualquier excepción durante la preparación de pinning se captura y la conexión sigue con la configuración predeterminada. Si se esperaba pinning, eso es un fallo abierto.

**Implementar**

- Rechazar esquemas que no sean HTTPS en producción.
- Permitir únicamente hosts de backend definidos por configuración de build.
- Si pinning es obligatorio y no puede inicializarse, abortar antes de transmitir datos.
- Mantener como mínimo un pin primario y uno de respaldo, junto con un procedimiento probado de rotación.
- No construir un `SSLContext` nuevo en cada petición; mantener un cliente configurado una vez.
- Probar certificado correcto, pin incorrecto, pin vacío, certificado rotado y reloj incorrecto.

El pinning es defensa adicional; no repara un protocolo que incorpora el secreto del servidor dentro del cliente.

### 5.2 Código de red duplicado — P1

[`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt), [`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt) y [`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt) construyen manualmente conexiones, cabeceras, JSON, timeouts y manejo de errores.

Consecuencias actuales:

- cambios de protocolo deben repetirse tres veces;
- respuestas equivalentes reciben comportamientos distintos;
- hay Threads sin relación con el ciclo de vida;
- el cierre de streams/conexiones no es uniforme;
- se mezclan red, persistencia, navegación y diálogos.

**Implementar**

```text
UI / Service
    -> caso de uso
        -> SessionRepository
            -> LicenseApi / AuthApi
                -> cliente HTTPS único
            -> SessionStore
```

- `AuthApi`: serialización, cabeceras, límites de tamaño, timeouts y respuesta cruda.
- `SessionRepository`: reglas de sesión y renovación.
- Activities/ViewModels: solo estados visibles y acciones del usuario.
- Service: observa la sesión; no implementa otro cliente de licencia.
- Coroutines con scopes cancelables; `Dispatchers.IO` para red y procesos.
- `use {}`/`finally` para streams y `disconnect()`.

### 5.3 JSON construido por interpolación — P0/P1

Hay JSON manual con valores externos en Login (por ejemplo [`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt#L276)), Main ([`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt#L903)) y Bubble ([`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt#L271)). Comillas, barras o caracteres de control en usuario/dispositivo pueden romper la petición.

**Cambiar por** `JSONObject().put(...)` o una biblioteca de serialización con DTOs. Añadir pruebas con comillas, Unicode, cadena vacía y payload grande.

### 5.4 Error de red no equivale a revocación — P0

[`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt#L275) cuenta fallos de verificación y termina llamando al flujo de licencia expirada. Tres errores de transporte pueden cerrar la sesión aunque el servidor nunca haya declarado que la licencia sea inválida.

**Implementar**

- Solo `SESSION_REVOKED`, `LICENSE_EXPIRED` o equivalente, dentro de una respuesta HTTPS autenticada, deben cerrar la sesión.
- `IOException`, timeout, DNS y HTTP 5xx deben producir estado `Offline`/`ServerUnavailable`.
- Reintento con backoff exponencial y jitter, respetando `Retry-After`.
- Definir explícitamente una ventana de gracia segura según el producto.
- Reiniciar el contador únicamente tras una respuesta válida, no ante cualquier callback de red.

### 5.5 Deep link de activación — P1

El manifest exporta `freezy://activate` en [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml#L32), y [`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt#L117) acepta `?key=` con una licencia completa.

Un esquema personalizado puede ser reclamado por otra aplicación. Además, poner licencia/HWID/token en una URL los expone a historial, logs y aplicaciones intermediarias.

**Sustituir por**

- App Link HTTPS con `android:autoVerify="true"` y `assetlinks.json`;
- código opaco de un solo uso y vida corta, no la licencia;
- `state` aleatorio vinculado al intento iniciado por la aplicación;
- validación estricta de esquema, host, ruta y parámetros;
- manejo de intents nuevos mediante `onNewIntent` o Navigation, según `launchMode`.

## 6. LoginActivity

[`LoginActivity.kt`](app/src/main/java/com/freezy/LoginActivity.kt) tiene alrededor de 845 líneas y actualmente combina UI, animaciones, deep links, red, criptografía, persistencia, versión de app, navegación y recuperación de sesión.

### Mantener

- El usuario recibe estados de carga y errores.
- Las credenciales no se guardan directamente como texto plano mediante una escritura nueva.
- Existe una separación inicial de TLS (`WebSecurity`) y almacenamiento (`SecurePrefs`).

### Mejorar

- Crear `LoginViewModel` con un `StateFlow<LoginUiState>`.
- Mover login, revalidación y activación a casos de uso/repositorio.
- Usar ViewBinding y eliminar búsquedas repetidas de `findViewById` señaladas por Lint.
- Deshabilitar el botón durante una operación, pero permitir cancelar/reintentar.
- Validar longitud/formato en cliente solo para UX; el servidor sigue siendo autoridad.
- Usar mensajes desde `strings.xml`; no obtener textos visibles del binario nativo.
- Cancelar animaciones/callbacks cuando la Activity se detiene o destruye.
- No imprimir excepciones ni respuestas completas en producción.

### Quitar

- Threads manuales iniciados desde la Activity.
- JSON interpolado.
- lectura directa de la clave desde un deep link.
- decisiones de ban/expiración basadas en frases.
- múltiples rutas que guardan la sesión por separado.

## 7. MainActivity

[`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt) supera las 1,450 líneas. Es una clase “Dios”: controla vistas, permisos especiales, selección de modo, servicios, sesión, red, fechas, botones, animaciones y estado del proceso.

### Problemas concretos

- Usa APIs obsoletas: `getRunningServices`, `startActivityForResult` y `checkOpNoThrow`.
- Mantiene varias copias del estado de los servicios y consulta si están activos de forma frágil.
- El contador de expiración en [`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt#L1332) confía en el reloj del dispositivo y puede forzar logout local.
- El logout en [`MainActivity.kt`](app/src/main/java/com/freezy/MainActivity.kt#L727) no elimina todo el material de sesión.
- Solicita exclusión de optimización de batería; Lint señala posible incompatibilidad con políticas de Play.
- Repite lógica de verificación ya presente en Login y Bubble.

### Implementar

- `MainViewModel`: estado de sesión, permisos y control visible.
- `PermissionCoordinator`: overlay, VPN, notificaciones y cualquier permiso especial, solicitado justo antes de necesitarlo.
- Activity Result APIs para permisos/actividades.
- `ServiceController` con estado observable, en lugar de `getRunningServices`.
- Tiempo de expiración devuelto como epoch UTC por servidor. Para mostrar cuenta regresiva, combinar última hora del servidor con `SystemClock.elapsedRealtime()`; el backend decide validez.
- Una única acción `logout()` en `SessionRepository`.

### División sugerida

```text
MainActivity                 renderiza y enruta eventos
MainViewModel                produce MainUiState
PermissionCoordinator       permisos especiales
OverlayController           iniciar/detener overlay
NetworkModeController       iniciar/detener modo VPN permitido
SessionRepository           sesión y expiración
```

## 8. BubbleService y overlay

[`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt) tiene alrededor de 1,360 líneas y concentra notificación, WindowManager, controles, preferencias, licencia, root, VPN y limpieza.

### Fallos de ciclo de vida — P0/P1

- Devuelve `START_STICKY` en [`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt#L161). Android puede recrearlo con un intent nulo y sin el paquete objetivo, dejando estado parcial.
- `licenseCheckRunnable` se programa repetidamente desde [`BubbleService.kt`](app/src/main/java/com/freezy/BubbleService.kt#L226), pero `onDestroy()` no retira todos los callbacks.
- [`onDestroy`](app/src/main/java/com/freezy/BubbleService.kt#L1220) llama primero a `super.onDestroy()` y después realiza limpieza asíncrona.
- La limpieza invoca operaciones root aunque el modo no estuviera activo, lo que puede iniciar `su` durante el cierre.
- [`isNetworkConnected`](app/src/main/java/com/freezy/BubbleService.kt#L1335) devuelve `true` cuando ocurre una excepción.
- Hay numerosas excepciones ignoradas, lo que oculta estados incompletos.

### Mejorar

- Elegir `START_NOT_STICKY` si el servicio no puede reconstruir de forma segura todo su estado; o persistir únicamente la configuración mínima y validar cada precondición al reiniciar.
- Tener un `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` propio y cancelarlo en `onDestroy`.
- Eliminar todos los callbacks del Handler antes de `super.onDestroy()`.
- Separar `OverlayWindow`, `OverlayPreferences`, `NotificationFactory`, `SessionMonitor` y controlador de función.
- Hacer que `stop()` sea idempotente: dos cierres deben tener el mismo resultado que uno.
- No arrancar `su` ni VPN durante cleanup si nunca se activaron.
- Reportar un estado explícito a la UI: `Stopped`, `Starting`, `Running`, `Stopping`, `Failed(reason)`.
- Añadir `POST_NOTIFICATIONS` y su flujo de permiso si se conserva una notificación visible en Android 13+.

### Interfaz y accesibilidad

- El panel usa tamaño fijo de aproximadamente 450 × 280 dp y no se adapta bien a pantallas pequeñas, densidades ni orientación.
- Sustituir `Switch` por `SwitchMaterial`/`SwitchCompat`.
- Añadir `contentDescription`, áreas táctiles mínimas y texto legible.
- Mover todo texto a recursos y crear variantes de tamaño/orientación cuando sean necesarias.
- No asignar objetos dentro de `onDraw`; Lint marca este problema en [`CyberBubbleView.kt`](app/src/main/java/com/freezy/ui/CyberBubbleView.kt#L218).

## 9. LagController y AntigravityFirewall

### 9.1 LagController informa éxitos falsos — P1

[`LagController.toggleFakeLag`](app/src/main/java/com/freezy/LagController.kt#L30) inicia una operación y luego siempre asigna el estado solicitado y devuelve `true`, incluso si JNI no está disponible, `su` falla o el proceso devuelve un código distinto de cero.

También usa variables globales mutables no sincronizadas y Threads sin timeout. Los streams y procesos no se gestionan de forma estructurada.

**Implementar, solo para funciones autorizadas sobre el propio dispositivo/tráfico**

- Resultado tipado: `Success`, `PermissionDenied`, `Timeout`, `NativeUnavailable`, `CommandFailed(exitCode)`.
- Estado observable inmutable mediante `StateFlow`.
- Una sola operación a la vez protegida con `Mutex`.
- Transiciones idempotentes y confirmadas después del resultado real.
- Timeout, cancelación y `finally` para liberar streams/procesos.
- No mantener días de licencia dentro de este controlador; es responsabilidad de sesión.

**Conservar root, pero aislarlo**

- Crear un único `RootCommandExecutor`; `BubbleService`, Activities y controladores no deben abrir shells por separado.
- Mantener una lista cerrada de comandos construidos por código. Nunca concatenar texto introducido por el usuario.
- Solicitar root únicamente cuando el usuario active explícitamente una función root, no durante arranque, comprobación pasiva o cleanup innecesario.
- Capturar `stdout`, `stderr`, código de salida y timeout; actualizar el estado solo después de confirmar el resultado.
- Registrar el nombre de la operación y su resultado, sin secretos ni comando sensible completo.
- Guardar qué reglas aplicó la app para revertir solo esas reglas. El cleanup debe ser idempotente.
- Separar `RootUnavailable`, `RootDenied`, `Timeout` y `CommandFailed` en lugar de devolver siempre éxito.
- Conservar una ruta sin root cuando la función lo permita, sin mezclar estados entre ambos modos.

**Quitar aunque root permanezca**

- shells `su` duplicados y Threads sueltos;
- ejecución de `su` durante el cierre cuando ninguna operación root estuvo activa;
- estado optimista que indica activación antes de recibir el resultado;
- comandos dinámicos sin lista permitida;
- comentarios o namespace orientados a camuflar el proceso frente a otros sistemas.

### 9.2 Carrera al iniciar VPN/motor nativo — P1

[`AntigravityFirewall.kt`](app/src/main/java/com/freezy/AntigravityFirewall.kt#L128) llama al motor nativo, mientras [`native-lib.cpp`](app/src/main/cpp/native-lib.cpp#L1326) reinicia `gLagActive = false`. Si otro componente solicita `setLagActive(true)` durante el arranque, esa activación puede perderse.

Además:

- el método Kotlin crea un Thread aunque el nativo ya crea su propio hilo;
- `shutdown()` contiene una espera y se invoca desde `onStartCommand`, potencialmente en el hilo principal;
- callbacks de red pueden reiniciar estado mientras el motor arranca;
- el MTU configurado en 65535 es atípico y debería justificarse con pruebas;
- el tipo/uso de foreground service debe validarse para el target SDK vigente.

**Rediseño**

```text
Stopped -> Starting -> Running -> Stopping -> Stopped
              |          |
              v          v
            Failed <-----+
```

- Un único actor/controlador serializa `start`, `configure` y `stop`.
- La configuración deseada se aplica después de confirmar `Running`.
- JNI informa éxito/error, no solo lanza un hilo desprendido.
- El descriptor, callback de red e hilo nativo pertenecen al mismo ciclo de vida.
- `onDestroy()` detiene y espera de manera acotada, sin bloquear el main thread.

## 10. JNI, C++ y herramientas nativas

### 10.1 Conversión incorrecta de texto — P0

[`encrypt_strings.py`](app/src/main/cpp/encrypt_strings.py#L9) codifica cadenas con UTF-8 estándar y el código generado usa `NewStringUTF` ([`encrypt_strings.py`](app/src/main/cpp/encrypt_strings.py#L294)). JNI espera Modified UTF-8. Un carácter suplementario como un emoji puede generar `JNI DETECTED ERROR` y abortar toda la aplicación; el log existente es consistente con ese fallo.

**Solución preferida**

- Mover todos los textos de UI a `res/values/strings.xml`.
- Usar recursos localizados y dejar en nativo únicamente datos que necesitan ser nativos.
- Cuando C++ deba devolver UTF-8 arbitrario, convertirlo de forma validada a UTF-16 y crear la cadena con `NewString`, nunca pasar bytes arbitrarios a `NewStringUTF`.
- Añadir pruebas instrumentadas con ASCII, acentos, CJK, emoji, NUL y secuencias inválidas.

El cast de `unsigned char*` a `char*` no corrige la codificación.

### 10.2 Lectura/escritura de memoria y helpers — retirar

[`native_bridge.cpp`](app/src/main/cpp/native_bridge.cpp#L1524) busca procesos y expone lectura/escritura de memoria; [`ffmem.c`](app/src/main/cpp/ffmem.c#L29) cambia el nombre del proceso a `logd`; los binarios `ffmem*` están empacados como ejecutables en `assets`.

Esto crea una superficie de riesgo elevada: privilegios, procesos huérfanos, archivos temporalmente ejecutables/legibles, arquitectura específica, comportamiento difícil de probar y conflicto con políticas o términos de terceros.

**Quitar**

- `ffmem`, `ffmem_32`, `ffmem_64` y su extracción.
- APIs `readGameMemory`, `writeGameMemory`, búsqueda de PID y parches.
- cambio de nombre a procesos del sistema.
- código muerto de snapshots duplicados y APIs JNI no usadas.

No se recomienda intentar hacer estas operaciones “menos detectables”; la mejora sostenible es eliminarlas y diseñar la función usando APIs autorizadas.

### 10.3 Estado global nativo — P1

- `g_secure_payload` debe estar protegido por mutex, tener propietario claro y limpiarse con sobreescritura en logout.
- Los threads `detach()` dificultan parada, resultado y pruebas; preferir handles que puedan unirse o una capa nativa con ciclo de vida explícito.
- Un bucle permanente que inspecciona `/proc` cada pocos segundos aumenta batería/falsos positivos y no protege secretos que ya están en el cliente; debe retirarse.
- Las colas nativas reservan buffers de tamaño máximo por entrada. Dos conjuntos aproximados de 256 × 65,536 bytes pueden consumir alrededor de 32 MiB estáticos antes de otros objetos. Usar un pool acotado por memoria y almacenar solo la longitud real.
- Corregir validación de punteros por ABI usando límites de `uintptr_t`; añadir pruebas arm64 y armeabi-v7a o retirar ABI que no se soporte.

### 10.4 Build nativo

- Actualizar la versión mínima de CMake usada por el proyecto.
- Habilitar advertencias estrictas en CI y decidir cuáles son error.
- Usar sanitizers en builds internos de prueba cuando el dispositivo/NDK lo permita.
- Generar símbolos nativos por separado para diagnóstico, sin incluirlos dentro del APK público.
- Aplicar visibilidad oculta y exportar solo JNI requerido, como reducción de superficie, no como sistema de autenticación.

## 11. AndroidManifest, permisos y distribución

### Permisos a revisar

[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) declara overlay, Usage Stats, exclusión de batería, VPN y foreground services.

- `PACKAGE_USAGE_STATS`: retirar si la función principal puede funcionar sin inspeccionar la aplicación en primer plano. Es un acceso sensible y frágil.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Lint advierte de restricciones de política. Solicitarlo solo si el caso permitido está plenamente documentado; en otro caso, quitarlo.
- `SYSTEM_ALERT_WINDOW`: explicar al usuario por qué se necesita y degradar correctamente si no lo concede.
- `POST_NOTIFICATIONS`: incorporar para Android 13+ si la notificación del servicio es parte de la experiencia.
- Foreground service `specialUse`: verificar que subtipo, finalidad y declaración correspondan realmente a las reglas del target y canal de distribución. No etiquetar una VPN con un tipo impropio.

### Configuración de aplicación

- [`app/build.gradle.kts`](app/build.gradle.kts#L24) usa un namespace aparentemente elegido para camuflaje. Adoptar un identificador legítimo, estable y propio de la organización.
- Alinear `compileSdk` con `targetSdk` después de una revisión de compatibilidad; actualmente compila con 34 y apunta a 36.
- Definir filtros ABI si solo se publican arquitecturas de dispositivo soportadas. No compilar x86/x86_64 sin necesidad.
- Hacer que CI falle de forma explícita al crear `release` si falta la configuración de firma, en vez de dejar un resultado ambiguo.
- Mantener `allowBackup=false` si se almacenan sesiones; como defensa adicional, excluir todos los dominios sensibles en reglas de backup.
- Igualar temas día/noche: el tema diurno es `NoActionBar`, mientras la variante nocturna introduce ActionBar.

## 12. R8/ProGuard

[`proguard-rules.pro`](app/proguard-rules.pro) tiene reglas excesivas y contradictorias:

- conserva prácticamente todas las clases/interfaces de AndroidX, reduciendo la utilidad del shrinker;
- aplica tanto `-flattenpackagehierarchy` como `-repackageclasses`, y R8 confirma que una sobreescribe la otra;
- elimina llamadas a `kotlin.jvm.internal.Intrinsics` mediante `-assumenosideeffects`, lo que puede quitar comprobaciones de nulidad y convertir errores claros en fallos difíciles de diagnosticar;
- contiene keeps para anotaciones/bibliotecas que no parecen estar en las dependencias actuales.

**Mejorar**

- Eliminar `assumenosideeffects` de `Intrinsics`.
- Conservar solo componentes Android, JNI y reflexión realmente necesarios.
- Preferir `@Keep` localizado o registro explícito de métodos nativos.
- Mantener un mapping por release en un almacenamiento seguro de CI.
- Añadir una prueba de humo sobre el APK minificado: arranque, login, navegación, inicio/parada del servicio y logout.

## 13. UI, recursos y mantenibilidad

### Quitar deuda inmediata

- Corregir los dos `android:tint` que bloquean Lint.
- Mover los 64 textos hardcoded y concatenaciones visibles a `strings.xml` con placeholders.
- Corregir textos menores de tamaño accesible.
- Añadir descripciones a iconos y asociación `labelFor` a campos.
- Sustituir `Switch` de plataforma por Material/AppCompat.
- Eliminar recursos sin uso y jerarquías innecesarias.

### Arquitectura recomendada

```text
app/
  data/
    auth/AuthApi.kt
    auth/SessionStore.kt
    auth/SessionRepositoryImpl.kt
  domain/
    auth/AuthRepository.kt
    auth/LoginUseCase.kt
    auth/LogoutUseCase.kt
    service/FeatureController.kt
  ui/
    login/LoginActivity.kt
    login/LoginViewModel.kt
    main/MainActivity.kt
    main/MainViewModel.kt
    overlay/OverlayService.kt
  platform/
    network/SecureHttpClient.kt
    permission/PermissionCoordinator.kt
    vpn/VpnEngineService.kt
  native/
    Solo las funciones justificadas y autorizadas
```

No es necesario migrar todo de una vez. Primero extraer autenticación sin cambiar la UI; luego servicio/controladores; finalmente simplificar Activities.

## 14. Repositorio y proceso de entrega

La limpieza debe basarse en la función del archivo y no solamente en su extensión. Un `.txt` puede ser documentación esencial y un `.log` puede ser evidencia temporal útil; ninguno debe borrarse de manera indiscriminada.

### Inventario observado y tratamiento sugerido

| Clase | Ejemplos observados | Tratamiento |
|---|---|---|
| Documentación que se conserva | `Ghost.txt`, `POSIBLES_OFFSETS.md`, `AQUITECTURA.md`, `PROGRESS.md`, `Fake.txt`, `Teleport.txt` | Versionar y no borrar durante limpieza. Revisar únicamente que no contenga secretos reales. |
| APK histórico | `app/release/Freezy(3.0.0).apk` | No borrar sin aprobación. Preferiblemente archivar en Releases/artefactos y después quitarlo de Git. |
| APK actual en raíz | `Freezy-v4.0.0-release-signed.apk` | Ya aparece eliminado en el worktree antes de esta auditoría; no se tocó ni se restauró. Confirmar si vive en Releases antes de cerrar ese cambio. |
| Logs de errores Kotlin | `.kotlin/errors/*.log` | Normalmente regenerables; conservar temporalmente si documentan un fallo activo y después sacarlos de Git. |
| Log funcional/diagnóstico | `log.txt`, `ff_capture_logcat.txt` | Revisar y redactar secretos/HWID/tokens. Archivar como evidencia si sigue siendo útil; si no, eliminar solo con confirmación. |
| Cache generada | `app/src/main/cpp/__pycache__/*.pyc` | Regenerable; candidata clara a salir de Git. |
| Configuración IDE | `.idea/*`, `.vscode/settings.json` | Decidir por archivo: compartir estilo/configuración útil, ignorar estado local de usuario y dispositivo. |
| Outputs de build | `app/build`, `app/.cxx` | Regenerables e ignorados; no versionar. |
| Helpers nativos empaquetados | `app/src/main/assets/ffmem*` | No son basura accidental: forman parte de la función actual. Su retiro es una decisión de producto/seguridad, no una limpieza automática. |

**Regla segura de limpieza**

1. Generar inventario con tamaño, tracking y propósito.
2. Clasificar cada archivo como `documentación`, `fuente`, `evidencia`, `entregable`, `secreto` o `regenerable`.
3. Mover entregables necesarios a Releases/almacenamiento de artefactos y verificar hash/copia.
4. Pedir confirmación antes de borrar evidencia, documentación o APKs históricos.
5. Eliminar únicamente regenerables confirmados y después ajustar `.gitignore`.
6. Si un archivo tuvo un secreto, rotar el secreto; borrar el archivo no basta.

**Implementar**

- Mantener excepciones explícitas en `.gitignore` para la documentación raíz importante.
- Ignorar por patrón caches, outputs y logs generados, pero permitir excepciones nominadas cuando un log sea evidencia deliberada.
- Publicar APK/AAB como artefactos de CI/Releases. Si se decide conservar un APK en Git, documentar por qué, su checksum y quién lo firma.
- No usar `git clean`, borrados recursivos ni reglas genéricas `*.txt`/`*.md` como procedimiento de limpieza.
- Añadir escaneo de secretos al historial y al pipeline. Si un secreto estuvo en Git, borrarlo del último commit no sustituye su rotación.
- Añadir un `README.md` con requisitos, sabores, configuración local y procedimiento de release.
- Mantener decisiones importantes como ADRs: modelo de sesión, pin rotation y ciclo de vida del servicio.

### Pipeline mínimo de CI

1. Compilar `debug` y `release` sin firmar o con credencial efímera de CI.
2. Ejecutar Lint y tratar errores como bloqueo.
3. Ejecutar pruebas unitarias Kotlin/JVM.
4. Ejecutar pruebas instrumentadas de sesión, deep link y servicio.
5. Compilar C++ para cada ABI soportada con warnings visibles.
6. Ejecutar escaneo de secretos y dependencias.
7. Ejecutar prueba de humo sobre APK minificado.
8. Firmar solo en un job protegido y conservar mapping/símbolos.

## 15. Plan de implementación por fases

### Fase 0 — contención y fallos críticos

- [ ] Rotar/revocar secreto HMAC del backend.
- [ ] Eliminar secreto y comentario del cliente.
- [ ] Usar tokens emitidos por el servidor en todas las verificaciones.
- [ ] Hacer que pinning falle cerrado y limitar HTTPS/hosts.
- [ ] Borrar token/payload en logout.
- [ ] Diferenciar caída de red de sesión revocada.
- [ ] Sustituir JSON interpolado.
- [ ] Corregir UTF-8/JNI.
- [ ] Corregir los 2 errores de Lint.
- [ ] Mantener root, pero impedir shells duplicados y estados de éxito falsos.
- [ ] Revisar por separado helpers/memoria y desactivar únicamente lo que no sea una función aprobada.

**Salida de fase:** no hay secreto reutilizable dentro del APK, logout borra la sesión completa, una caída de red no expulsa al usuario y textos Unicode no abortan el proceso.

### Fase 1 — autenticación y red

- [ ] Crear `AuthApi`, `SessionStore` y `SessionRepository` únicos.
- [ ] Introducir errores/códigos tipados.
- [ ] Implementar access/refresh token, renovación y revocación.
- [ ] Migrar deep link a App Link con código de un solo uso.
- [ ] Definir política de HWID/instalación y privacidad.
- [ ] Vincular GET KEY en servidor a `token -> HWID`, sin aceptar el HWID desde la URL del navegador.
- [ ] Añadir token de un uso, expiración, cooldown, rate limiting y protección de concurrencia.
- [ ] Añadir pruebas del protocolo y rotación de certificados.

**Salida de fase:** Login, Main y Bubble consumen la misma sesión y no duplican protocolo.

### Fase 2 — servicios y controladores

- [ ] Dividir `BubbleService`.
- [ ] Implementar máquina de estados de servicio.
- [ ] Rehacer `LagController` con resultados reales y operaciones serializadas.
- [ ] Corregir carrera de `gLagActive` y lifecycle JNI.
- [ ] Cancelar callbacks/scopes al destruir servicios.
- [ ] Centralizar root en `RootCommandExecutor` con allowlist, timeout, salida y cleanup idempotente.
- [ ] Decidir helpers/memoria por separado; root no obliga a mantener todas las funciones nativas actuales.

**Salida de fase:** iniciar/detener es idempotente, observable y no deja procesos, callbacks o descriptores vivos.

### Fase 3 — UI, calidad y entrega

- [ ] Dividir Login/Main mediante ViewModels.
- [ ] Migrar a ViewBinding y Activity Result APIs.
- [ ] Resolver advertencias de accesibilidad/textos/layout.
- [ ] Simplificar R8.
- [ ] Limpiar repositorio.
- [ ] Activar CI, tests y smoke test minificado.

**Salida de fase:** Lint sin errores, pruebas reales, release reproducible y componentes mantenibles.

## 16. Matriz mínima de pruebas

| Área | Casos obligatorios |
|---|---|
| Login | éxito, credencial inválida, timeout, 5xx, JSON inválido, caracteres Unicode, doble toque, cancelación. |
| Sesión | reinicio de proceso, token expirado, refresh correcto, refresh revocado, logout, Keystore invalidado. |
| TLS | pin correcto, pin incorrecto, pin de respaldo, pins ausentes, host no permitido, HTTP bloqueado. |
| Deep link | host/ruta inválidos, código reutilizado, `state` incorrecto, intent nuevo con Activity existente. |
| Servicio | start/stop repetido, proceso recreado, intent nulo, permiso revocado, red cambia, cierre durante arranque. |
| JNI | arm64/arm32, Unicode completo, input inválido, start/stop repetido, error nativo propagado. |
| UI | pantalla pequeña, fuente grande, rotación, modo oscuro, TalkBack, permiso denegado. |
| Release | APK minificado, firma válida, ausencia de secretos, ausencia de logs sensibles y binarios no previstos. |

## 17. Qué conservar

No todo debe reescribirse. Son buenas bases:

- AES-GCM con clave de Android Keystore en `SecurePrefs`.
- `allowBackup=false` y configuración release que confía en certificados del sistema.
- uso de notificación para servicios en primer plano.
- separación inicial de `WebSecurity`, `SessionGuard`, `SecureLogger` y `LagController`, aunque sus responsabilidades todavía deban corregirse.
- construcción `release` minificada y compilación multi-ABI actualmente funcional.

## 18. Orden recomendado de decisión

Antes de tocar colores, animaciones o rendimiento visual:

1. conservar root bajo un ejecutor central y decidir por separado qué helpers/memoria forman parte aprobada del producto;
2. rotar el secreto y arreglar el protocolo de sesión;
3. corregir TLS, logout y errores de red;
4. estabilizar JNI y servicios;
5. dividir clases grandes;
6. cerrar Lint, accesibilidad, pruebas y CI.

Este orden evita invertir tiempo en pulir componentes que luego deberían desaparecer y reduce primero los riesgos con mayor impacto.
