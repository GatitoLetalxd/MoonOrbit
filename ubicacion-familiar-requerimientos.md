# MoonOrbit — Especificación funcional v2

> **Versión:** 2.0 · **Fecha:** 2026-11-01
> **Fuente de verdad:** `moonorbit-decisiones-v2.md`. Si este documento y la fuente difieren, manda la fuente.
> **Reemplaza:** la versión anterior de este archivo. Los IDs `RF`/`RNF` se conservan cuando siguen aplicando; los retirados se listan en el anexo A.
> **Leyenda:** ✅ decidido · 💡 propuesto o por validar · ⏳ pendiente

---

## 1. Resumen y alcance

**MoonOrbit** es una app Android privada para que una familia comparta su ubicación en tiempo real. No es una red social, no es un producto público y no está pensada para vigilancia encubierta: cada persona ve quién está en su grupo, puede pausar su ubicación y siempre tiene visible la notificación del servicio de ubicación.

### 1.1 Definición del producto

| Dimensión | Definición | Estado |
|---|---|---|
| Plataforma | Android nativo, Kotlin, Jetpack Compose + Material 3 | ✅ |
| Distribución | APK privado, instalación manual; sin Google Play en v1 | ✅ |
| Grupo | Uno por usuario; familia inicial de 6, máximo 12 miembros activos | ✅ |
| Administrador | Exactamente uno por grupo; no hay cambios de rol desde la app | ✅ |
| Registro | Solo con código de invitación y aprobación del administrador | ✅ |
| Ubicación | Solo la última conocida; sin historial de rutas | ✅ |
| Avatares | WebP, recorte cuadrado, 512×512, calidad 80, máximo 1 MB | ✅ |
| Tema | Claro y oscuro premium, con opción “seguir al sistema” | ✅ |
| Avisos | Sin push; se ven al abrir la app o por WebSocket con la app abierta | ✅ |
| Administración | Sin panel web; grupos, avisos, versiones y términos por SQL/CLI | ✅ |
| Idiomas | Español | ✅ |
| iOS | Fuera de alcance | ✅ |

### 1.2 Alcance de la v1

- Una familia, un grupo, un administrador y hasta 12 personas.
- Ubicación en segundo plano con bajo consumo, latido periódico y cola local para pérdidas de conexión.
- Mapa con la última ubicación de cada miembro, estado, batería, precisión y hora de actualización.
- Pausa global del uso compartido y estados claros para ubicación desactivada y sin señal.
- Perfil mínimo con nombre, teléfono opcional, avatar y cambio de contraseña.
- Sesiones revocables por dispositivo, sin refresh tokens ni recuperación por correo.
- Términos versionados, avisos internos y actualización obligatoria cuando corresponda.
- Operación en un solo VPS con Nginx, PostgreSQL, Node/Fastify y PM2.

### 1.3 Fuera de alcance

| Excluido | Motivo |
|---|---|
| Notificaciones push/FCM | Decisión de v1; los avisos llegan al abrir la app o por WebSocket |
| Historial de rutas | Solo se guarda la última ubicación en `user_state` |
| Multi-grupo y selector de grupos | Un usuario pertenece a un solo grupo |
| Múltiples administradores o cambio de rol | Un solo admin; la transferencia la hace el propietario del servidor |
| Panel web de administración | Avisos y versiones se publican por SQL/CLI |
| Instalación del APK dentro de la app | La descarga se abre en el navegador |
| Activity Recognition y geofence | El ahorro se basa en precisión balanceada, distancia mínima y lotes |
| Detección de “salto imposible” | No se implementa en v1 |
| Correo y contacto de emergencia | No forman parte del perfil v1 |
| Llamadas dentro de la app | Solo se abre el marcador del teléfono |
| Registro abierto o lanzamiento público | Solo invitación; sin `REGISTRATION_MODE` |
| iOS | Solo Android |

### 1.4 Principios de producto

1. **Privacidad por diseño:** sin historial, sin modo oculto, sin datos de más.
2. **Claridad antes que densidad:** textos grandes, estados explicados y acciones destructivas confirmadas.
3. **Batería primero:** precisión balanceada, distancia mínima, lotes y latido en lugar de GPS continuo.
4. **Premium y liviano:** gradientes suaves, animaciones cortas y APK menor a 30 MB.
5. **Una sola fuente de verdad:** si algo no está aquí ni en `moonorbit-decisiones-v2.md`, no se implementa sin decidirlo.

---

## 2. Decisiones técnicas

| Área | Decisión | Estado |
|---|---|---|
| App | Android nativo, Kotlin, Jetpack Compose, Material 3 | ✅ |
| Mapa | Google Maps con `maps-compose`, mapa básico sin *Map ID*; estilo claro/oscuro por JSON | ✅ |
| Backend | VPS propio, Nginx + Certbot, Node.js + Fastify, PostgreSQL | ✅ |
| Proceso | Una instancia Node/Fastify con PM2 en modo `fork`; sin clúster y sin Redis | ✅ |
| Autenticación | Token de sesión **opaco** de 256 bits; sin JWT y sin refresh token | ✅ |
| Sesiones | 90 días, máximo 5 activas, revocables de inmediato | ✅ |
| Escrituras | Todas por REST (`POST`/`PATCH`/`PUT`/`DELETE`) | ✅ |
| Tiempo real | WebSocket **solo recepción**; la app solo envía `resync` y `ping` | ✅ |
| Respaldo de tiempo real | Polling de snapshot cada 30 s solo en primer plano, con `ETag`/`If-None-Match` | ✅ |
| Base local | Room para cola de ubicaciones; DataStore para ajustes y token cifrado | ✅ |
| Avatares | WebP binario, máximo 1 MB; `sharp` re-codifica a 512×512, calidad 80 | ✅ |
| Tema | Claro/oscuro/sistema, persistido en DataStore; sin color dinámico | ✅ |
| Distribución | APK por HTTPS, descarga en navegador, SHA-256 publicado | ✅ |
| Operación | Nginx delante, PostgreSQL local, respaldos cifrados fuera del VPS | ✅ |
| URL REST | `https://orbit.moondev.online/api/v1` | ✅ |
| URL WebSocket | `wss://orbit.moondev.online/ws` | ✅ |
| Paquete Android | `online.moondev.orbit` | 💡 |
| Versión mínima | API 26 (Android 8.0), a confirmar con los dispositivos reales | ⏳ |

**Notas de implementación:**

- El token opaco se genera con 32 bytes aleatorios y se codifica en Base64URL sin relleno (43 caracteres). No tiene *claims* ni se puede validar sin consultar la base de datos.
- El servidor guarda solo `SHA-256(token)`; nunca el token en claro.
- El WebSocket se autentica con `Authorization: Bearer <sessionToken>` en el handshake. No se aceptan tokens en la URL.
- El polling no compite con el WebSocket: si el WebSocket está conectado, no se hace polling.
- Google Maps requiere cuenta de facturación y API key restringida al paquete y a la huella SHA-1. Sin *Map ID* ni APIs adicionales (Directions, Places, Geocoding).

---

## 3. Requerimientos funcionales

> Los requerimientos se agrupan por área. Cada uno debe poder verificarse con una prueba manual o automatizada. Los IDs nuevos continúan desde `RF-51`; los retirados no se reutilizan.

### 3.1 Cuentas, registro y grupo

| ID | Requerimiento | Estado |
|---|---|---|
| RF-01 | Inicio de sesión con usuario y contraseña; el servidor emite un token de sesión opaco, sin JWT ni refresh token. | ✅ |
| RF-02 | El primer grupo lo crea el propietario del servidor con un script/CLI; no existe endpoint para crear grupos. | ✅ |
| RF-03 | Registro y unión solo con código de invitación; sin registro abierto. | ✅ |
| RF-04 | Cada usuario solo ve ubicaciones, perfiles y avatares ajenos de miembros activos de su único grupo; su propio perfil y avatar siempre están disponibles. | ✅ |
| RF-43 | El administrador puede restablecer la contraseña de un miembro no administrador. | ✅ |
| RF-44 | Tras un restablecimiento, el miembro debe cambiar la contraseña en su siguiente inicio de sesión. | ✅ |
| RF-45 | El administrador genera y regenera el código de invitación: 8 caracteres sin `0/O/1/I`, 48 h de vigencia, reutilizable hasta regenerar o vencer; el anterior queda inválido. | ✅ |
| RF-46 | El administrador aprueba o rechaza a los nuevos miembros antes de que vean datos del grupo. | ✅ |
| RF-51 | Un usuario pertenece como máximo a un grupo (`pending` o `active`); no hay selector ni multi-grupo. | ✅ |
| RF-52 | Un grupo tiene exactamente un administrador `active`; no hay cambios de rol desde la app. | ✅ |
| RF-53 | El nombre del grupo tiene 2–40 caracteres; el administrador puede cambiarlo. | ✅ |
| RF-54 | El grupo admite como máximo 12 miembros `active`; las solicitudes `pending` no ocupan plaza, pero unirse y aprobar fallan con `409 group_full` si ya hay 12 activos. | ✅ |
| RF-55 | Un rechazado puede volver a postular al mismo grupo o unirse a otro; un miembro que salió (`left`) puede unirse a otro. | ✅ |
| RF-56 | El administrador no puede salir del grupo ni eliminar su cuenta mientras sea admin; recibe `409 admin_transfer_required`. | ✅ |
| RF-57 | El administrador puede expulsar miembros activos; la membresía pasa a `left`, se corta el acceso al grupo y se emite `member_removed`. | ✅ |

**Flujo de registro:**

1. El administrador abre **Administración → Invitación**, genera el código y lo comparte por fuera de la app.
2. La persona instala el APK, acepta los términos y elige **Registrarme**.
3. Ingresa código, nombre de usuario, contraseña, nombre para mostrar y nombre del dispositivo.
4. El servidor valida el código y la vigencia, crea la cuenta, la membresía `pending` y la sesión; registra la versión de términos aceptada.
5. La app muestra **Pendiente de aprobación** y solo permite perfil propio, sesiones propias, términos y eventos de membresía.
6. El administrador aprueba o rechaza. Al aprobar, la persona recibe `member_approved`, obtiene acceso al snapshot y entra al mapa.

**Reglas del grupo:**

- El nombre se recorta y se valida entre 2 y 40 caracteres.
- El código se guarda sin guiones; se puede mostrar agrupado en 4-4 para leerlo mejor.
- Regenerar el código invalida el anterior de inmediato.
- El administrador puede renombrar el grupo, pero no cambiar roles ni crear otro grupo.
- La expulsión no borra la cuenta: la persona queda sin grupo y puede volver a postular con un código válido.
- Un miembro rechazado o que salió no ve el snapshot, ubicaciones, avatares ajenos ni eventos internos del grupo.

### 3.2 Autenticación, sesiones y dispositivos

| ID | Requerimiento | Estado |
|---|---|---|
| RF-58 | Token de sesión opaco de 256 bits; el servidor guarda solo `SHA-256(token)` en `sessions.token_hash`. | ✅ |
| RF-59 | La sesión dura 90 días; `last_used_at` se actualiza al usarla (con escritura agrupada como máximo cada 5 min). | ✅ |
| RF-60 | Máximo 5 sesiones activas por usuario; al crear la sexta se revoca la más antigua. | ✅ |
| RF-61 | `GET /auth/sessions` lista dispositivos, fechas y sesión actual; `DELETE /auth/sessions/{id}` revoca una sesión propia. | ✅ |
| RF-62 | `POST /auth/logout` revoca la sesión actual; `POST /auth/logout-others` revoca las demás. | ✅ |
| RF-63 | La revocación es inmediata: el WebSocket de esa sesión se cierra con `4403` y las siguientes peticiones REST reciben `401`. | ✅ |
| RF-64 | `must_change_password` se aplica en el servidor: solo permite `GET /users/me`, `POST /auth/change-password` y `POST /auth/logout`. | ✅ |
| RF-65 | El reset del administrador genera una contraseña temporal de un uso, revoca todas las sesiones y activa `must_change_password`. | ✅ |
| RF-66 | Si el administrador olvida su contraseña, el propietario del servidor la restablece con un script. | ✅ |
| RF-67 | El token se guarda cifrado en el dispositivo con Android Keystore, `allowBackup=false`, y se borra al cerrar sesión. | ✅ |
| RF-68 | Login con error genérico y rate limit; sin recuperación por correo. | ✅ |
| RF-69 | `POST /auth/change-password` exige la contraseña actual; se recomienda revocar las demás sesiones. | 💡 |
| RF-70 | Una sesión vencida o revocada lleva a login con mensaje claro, sin perder el lugar en el grupo. | ✅ |

**Contrato de sesión:**

- `POST /auth/login` devuelve `{ user, membership, session: { sessionToken, expiresAt }, mustChangePassword, terms, serverTime }`.
- El `sessionToken` se devuelve una sola vez. No hay endpoint de refresh.
- Cada petición autenticada usa `Authorization: Bearer <sessionToken>`.
- `GET /auth/sessions` devuelve `sessionId`, `deviceName`, `appVersionCode`, `createdAt`, `lastUsedAt`, `expiresAt` y `current`.
- `DELETE /auth/sessions/{id}` solo permite revocar sesiones del propio usuario. La sesión actual también puede revocarse.
- `POST /auth/logout-others` conserva la sesión actual y revoca las demás.
- Al revocar una sesión, el servidor emite `session_revoked` por el WebSocket de esa sesión y luego la cierra con `4403`.
- Si el token venció, el WebSocket se cierra con `4401`; la app no reintenta con el mismo token y muestra login.
- Si la app está cerrada cuando se revoca la sesión, no hay push: el siguiente request recibe `401 session_revoked` y la app limpia la sesión.
- Si `must_change_password` está activo, cualquier otro endpoint responde `403 password_change_required`.
- El cambio de contraseña invalida la contraseña temporal; la nueva debe cumplir la política mínima (mínimo 10 caracteres, máximo 128, sin exigir composición obligatoria). 💡
- No hay preguntas de seguridad, correo de recuperación ni enlaces mágicos.

### 3.3 Ubicación, latido y estados

| ID | Requerimiento | Estado |
|---|---|---|
| RF-05 | Compartir la ubicación en segundo plano con bajo consumo de batería. | ✅ |
| RF-06 | Al abrir la app, mostrar el mapa con los miembros en tiempo real. | ✅ |
| RF-07 | Cargar primero la última ubicación conocida (caché/snapshot) y luego los cambios en vivo. | ✅ |
| RF-08 | Marcadores con avatar o iniciales, nombre, anillo de estado y batería. | ✅ |
| RF-09 | Mostrar la hora de la última actualización (“hace X min”) usando el desfase de reloj calculado con `serverTime`. | ✅ |
| RF-10 | Panel inferior con la lista de miembros y acceso al detalle. | ✅ |
| RF-11 | Mostrar el nivel de batería de cada miembro; si no hay señal, la última conocida. | ✅ |
| RF-12 | No descartar ubicaciones imprecisas: `lowAccuracy` si `accuracyM > 100`; mostrar círculo de precisión y aviso. | ✅ |
| RF-13 | Mostrar un mensaje cuando un miembro desactiva su ubicación. | ✅ |
| RF-14 | Distinguir `paused`, `location_disabled`, `no_signal` y `active`. | ✅ |
| RF-15 | El usuario puede pausar y reanudar su uso compartido global desde la app. | ✅ |
| RF-16 | Mostrar al propio usuario un aviso si su ubicación no se comparte, con botón para activarla. | ✅ |
| RF-17 | Mantener visible la última posición conocida, en gris, cuando un miembro no está activo. | ✅ |
| RF-50 | Enviar un heartbeat cada ~10 min si no se envió ubicación; actualiza batería y `last_seen_at`. | ✅ |
| RF-71 | Todas las escrituras de ubicación y estado van por REST; el WebSocket nunca transporta ubicación. | ✅ |
| RF-72 | `POST /location` acepta 1–50 puntos o 64 KB; el servidor usa el punto con `recordedAt` más reciente. | ✅ |
| RF-73 | Se ignoran puntos más viejos que el guardado o con más de 24 h; un `recordedAt` más de 5 min en el futuro se ajusta a la hora del servidor. | ✅ |
| RF-74 | `POST /me/status` acepta `active`, `paused` y `location_disabled`; `no_signal` solo lo calcula el servidor. | ✅ |
| RF-75 | Trabajo `no_signal` cada minuto: un `active` con `last_seen_at < now() - 25 min` pasa a `no_signal`. | ✅ |
| RF-76 | Un heartbeat desde `no_signal` vuelve a `active`; `paused` y `location_disabled` no se reactivan con heartbeats. | ✅ |
| RF-77 | Una ubicación válida solo reactiva `no_signal` → `active`; si el estado es `paused` o `location_disabled`, el lote se ignora y no se difunde. Para reanudar hay que enviar `POST /me/status` con `active`. | ✅ |
| RF-78 | Estado inicial: `no_signal` con `last_seen_at = null`; la app muestra “Sin datos todavía”, no “sin señal desde hace X”. | ✅ |
| RF-79 | La app calcula el desfase de reloj con `serverTime` y lo aplica a “hace X min” y a las horas relativas. | ✅ |
| RF-80 | Batería baja a partir de 15 %, con indicador visual y sin ocultar el porcentaje. | ✅ |
| RF-81 | Precedencia visual: `paused` > `location_disabled` > `no_signal` > `active`. | ✅ |
| RF-82 | Sin Activity Recognition, sin geofence y sin detección de “salto imposible” en v1. | ✅ |

**Contrato de ubicación:**

- El lote se envía por `POST /location` con `points: [{ lat, lng, accuracyM, recordedAt, batteryLevel, isCharging }]`.
- No se envía `activity` ni datos de actividad física.
- El servidor valida rangos de latitud/longitud, tamaño y cantidad; rechaza el lote completo con `400` si la estructura es inválida.
- El servidor actualiza `user_state` con el punto más reciente y devuelve `{ accepted, ignored, effectiveRecordedAt, status, serverTime }`.
- Si el punto más reciente es más viejo que el guardado, no se actualiza nada y cuenta como `ignored`; el heartbeat sigue siendo la señal de vida.
- La cola local de Room se vacía por lotes al recuperar conexión; los puntos con más de 24 h se descartan.
- El heartbeat se envía por `POST /heartbeat` con `{ batteryLevel, isCharging }`; no incluye coordenadas.
- `POST /me/status` se usa para pausar, reanudar o informar que la ubicación se desactivó. La app no inventa `no_signal`.
- Los puntos en cola no reactivan `paused` ni `location_disabled`: mientras dure ese estado se cuentan como `ignored`. La app debe enviar `POST /me/status` con `active` antes de reanudar el tracking.

### 3.4 Onboarding, permisos y términos

| ID | Requerimiento | Estado |
|---|---|---|
| RF-18 | Flujo inicial: bienvenida → términos → registro/login → permisos → pendiente (si aplica) → mapa. | ✅ |
| RF-19 | Pantalla de términos y privacidad con aceptación explícita antes de crear la cuenta. | ✅ |
| RF-20 | Banner permanente en el mapa si falta un permiso crítico, con acceso directo a ajustes. | ✅ |
| RF-21 | Si se rechaza el permiso de segundo plano, permitir uso limitado y explicar exactamente qué se pierde. | ✅ |
| RF-22 | Si el sistema ya no muestra el diálogo, cambiar el botón a “Abrir ajustes”. | ✅ |
| RF-23 | Re-verificar permisos al volver a la app (`ON_RESUME`) y retomar el flujo donde quedó. | ✅ |
| RF-83 | Android 11+: el permiso de ubicación en segundo plano se concede desde ajustes, no con un diálogo conjunto. | ✅ |
| RF-84 | Android 12+: detectar ubicación aproximada y mostrar el paso para solicitar ubicación precisa. | ✅ |
| RF-85 | Android 13+: solicitar `POST_NOTIFICATIONS` como permiso de ejecución y explicar su impacto. | ✅ |
| RF-86 | Android 14+: declarar `FOREGROUND_SERVICE_LOCATION` y `foregroundServiceType="location"`; iniciar el servicio con la app visible. | ✅ |
| RF-87 | Guiar la exención de optimización de batería y explicar que sin ella el sistema puede retrasar o detener el envío. | ✅ |
| RF-88 | Guiar el inicio automático y las restricciones específicas en Xiaomi, Huawei, Oppo, Samsung y similares. | ✅ |
| RF-89 | Mostrar pantallas distintas para membresía `pending` y `rejected`, con acciones claras. | ✅ |
| RF-90 | Resumen final con checklist del estado de cada permiso y acceso a ajustes. | ✅ |
| RF-91 | Términos versionados en servidor: `GET /app/terms` y `POST /users/me/terms`; si la versión aceptada no es la vigente, bloquear funciones hasta aceptar. | ✅ |
| RF-92 | Registrar por usuario la versión de términos aceptada y la fecha/hora UTC. | ✅ |

**Orden canónico del onboarding:**

1. **Bienvenida** con propuesta de valor y privacidad.
2. **Términos y privacidad** con versión vigente y aceptación explícita.
3. **Registro** con código o **login** si ya tiene cuenta.
4. **Ubicación en primer plano** (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`).
5. **Ubicación precisa** solo si el sistema concedió únicamente aproximada.
6. **Ubicación en segundo plano** (`ACCESS_BACKGROUND_LOCATION`).
7. **Notificaciones** en Android 13+ (`POST_NOTIFICATIONS`).
8. **Batería sin restricciones** (exención de optimización).
9. **Resumen de permisos** con checklist.
10. **Pendiente de aprobación**, si la membresía aún no está activa.
11. **Mapa**.

Si la persona ya está activa y solo cambió de dispositivo: **login → permisos faltantes → mapa**. Si hay una versión nueva de términos: se muestra después del login y antes del mapa. Si hay actualización obligatoria: se muestra la pantalla de actualización antes de cualquier otra función.

### 3.5 Mapa y tiempo real

| ID | Requerimiento | Estado |
|---|---|---|
| RF-93 | Al abrir o volver a primer plano, obtener un `snapshot` completo del grupo antes de dibujar el mapa. | ✅ |
| RF-94 | Aplicar eventos WebSocket (`member_state`, `member_joined`, `member_left`, etc.) sin recargar toda la pantalla. | ✅ |
| RF-95 | Si el WebSocket está caído, hacer polling de snapshot cada 30 s solo con la app en primer plano, usando `ETag`/`If-None-Match`. | ✅ |
| RF-96 | Si el WebSocket está conectado, no hacer polling. | ✅ |
| RF-97 | Reconectar el WebSocket con espera exponencial y jitter: 1, 2, 4… hasta 60 s. | ✅ |
| RF-98 | Ignorar eventos más viejos que el estado ya conocido, comparando `recordedAt`/`statusChangedAt`. | ✅ |
| RF-99 | Controles de mapa: recentrar en mí, recentrar en el grupo, abrir lista de miembros y leyenda de estados. | ✅ |
| RF-100 | Marcador con avatar circular, nombre, anillo de estado, batería y selección ampliada. | ✅ |
| RF-101 | Círculo de precisión visible cuando `lowAccuracy` o cuando el usuario lo activa en el detalle. | ✅ |
| RF-102 | Estilo de mapa claro y oscuro coherente con la paleta; cambio inmediato al cambiar el tema. | ✅ |
| RF-103 | Estados vacíos: sin ubicación todavía, sin miembros visibles, sin avisos, sin otras sesiones y sin conexión. | ✅ |
| RF-104 | Cache-first: mostrar el último snapshot local mientras llega el nuevo; skeletons solo en la primera carga. | ✅ |
| RF-105 | Banner offline persistente con “Reintentar” y contador discreto de puntos pendientes de envío. | ✅ |
| RF-106 | Confirmación en acciones destructivas (salir, expulsar, eliminar cuenta, revocar sesión) y háptica ligera. | ✅ |
| RF-107 | Textos grandes por defecto en listas y detalle; no bajar de 14 sp en datos críticos. | ✅ |
| RF-108 | La lista de miembros es una alternativa completa al mapa para accesibilidad y para pantallas pequeñas. | ✅ |

**Reglas del snapshot:**

- `GET /groups/{groupId}/snapshot` exige membresía `active` en ese grupo.
- Devuelve `groupId`, `name`, `serverTime` y `members[]` con perfil básico, `role`, `avatarVersion` y `state`.
- `state.location` y `state.battery` pueden ser `null`.
- Los miembros `pending` no aparecen en el snapshot; solo el administrador los ve en `GET /groups/{groupId}/pending`.
- El snapshot se comprime con gzip/brotli y lleva `Cache-Control: private, no-cache` y `ETag` débil.
- Si `If-None-Match` coincide, el servidor responde `304` sin cuerpo.
- La app guarda el último snapshot válido en Room/DataStore para arranque cache-first.

**Reglas de tiempo real:**

- El WebSocket es solo para recibir; la app no envía ubicación, perfil ni estados por ese canal.
- Una conexión por sesión. Si la misma sesión abre otra, el servidor cierra la anterior con `1000`.
- El servidor hace ping cada 30 s y cierra por inactividad a los 90 s.
- Mensajes de servidor a app de máximo 4 KB y 5 mensajes por segundo por conexión.
- La app solo envía `resync` y `ping` de aplicación; el ping/pong del protocolo WebSocket es automático.
- Al reconectar o volver a primer plano, la app envía `resync` y el servidor responde `snapshot`.
- Si el WebSocket se cae, la app no hace polling en segundo plano; al volver a primer plano, resincroniza y, si el WebSocket no está disponible, activa polling cada 30 s.

### 3.6 Perfil y avatares

| ID | Requerimiento | Estado |
|---|---|---|
| RF-32 | Pantalla de perfil para editar nombre para mostrar y teléfono opcional. | ✅ |
| RF-33 | Foto de perfil editable, comprimida a WebP antes de subirla. | ✅ |
| RF-34 | Elegir foto desde Photo Picker o cámara del sistema, con recorte cuadrado y vista previa. | ✅ |
| RF-35 | Sin foto: avatar de iniciales con color determinista y contraste accesible. | ✅ |
| RF-36 | Los miembros del grupo ven foto, nombre y teléfono opcional en el detalle; el botón “Llamar” abre el marcador. | ✅ |
| RF-37 | Al reemplazar o eliminar la foto, se borra la anterior del servidor; si falla, la limpieza de huérfanos la elimina. | ✅ |
| RF-109 | `PUT /users/me/avatar` recibe `Content-Type: image/webp` binario, máximo 1 MB; no se acepta multipart en v1. | ✅ |
| RF-110 | El servidor valida tipo real, dimensiones y tamaño; re-codifica con `sharp` a 512×512, calidad 80, sin metadatos. | ✅ |
| RF-111 | El archivo se guarda fuera de la raíz pública en `avatars/{userId}/{version}.webp`. | ✅ |
| RF-112 | `avatar_version` se incrementa al reemplazar o eliminar; `avatar_path` puede ser `null`. | ✅ |
| RF-113 | `GET /users/{userId}/avatar` exige sesión; permite el avatar propio incluso en `pending`, `left` o `rejected`, y el de otro usuario solo con grupo activo en común. Devuelve `ETag` y admite `If-None-Match` (304). | ✅ |
| RF-114 | Nginx puede servir el avatar con `X-Accel-Redirect` para no cargar el proceso Node. | ✅ |
| RF-115 | Limpieza diaria de fotos huérfanas: archivos sin referencia en `users.avatar_path` y con más de 24 h. | ✅ |
| RF-116 | Límite de 5 subidas de avatar por hora por usuario. | ✅ |
| RF-117 | Coil usa cabecera de autorización, caché por `avatarVersion` y limpia su caché al cerrar sesión. | ✅ |
| RF-118 | Sin URL pública, sin EXIF y sin original: solo se conserva el WebP re-codificado. | ✅ |

### 3.7 Diseño premium, tema y accesibilidad

| ID | Requerimiento | Estado |
|---|---|---|
| RF-38 | Interfaz atractiva y coherente con la marca, con ilustraciones propias o recoloreadas. | ✅ |
| RF-39 | Ilustración en bienvenida, términos, permisos y resumen; mismo estilo en todas. | ✅ |
| RF-40 | Estados vacíos, de error y sin conexión ilustrados, con texto útil y acción siguiente. | ✅ |
| RF-41 | Animaciones suaves: transiciones, pulso del marcador y panel inferior. | ✅ |
| RF-42 | Tema oscuro y claro premium basados en la identidad moondev. | ✅ |
| RF-119 | Modo de tema: sistema, claro u oscuro, persistido en DataStore y aplicado sin reiniciar. | ✅ |
| RF-120 | Sin color dinámico de Android: la marca manda. | ✅ |
| RF-121 | Paleta exacta de la sección 9, con tokens semánticos y contraste AA. | ✅ |
| RF-122 | Tipografía Manrope o Plus Jakarta Sans con fallback del sistema; escala Material 3. | ✅ |
| RF-123 | Esquinas de 20–28 dp, elevación sutil y gradientes estáticos; sin desenfoques pesados. | ✅ |
| RF-124 | Mapa y marcadores con estilo claro/oscuro, avatar, anillo de estado y batería. | ✅ |
| RF-125 | Animaciones de 150–250 ms y respeto a “reducir movimiento”. | ✅ |
| RF-126 | Accesibilidad: contraste AA, objetivos de 48 dp, escala de fuente hasta 200 %, descripciones de contenido. | ✅ |
| RF-127 | Háptica ligera, textos grandes y confirmaciones para familiares mayores. | ✅ |
| RF-128 | Ilustraciones en SVG/`VectorDrawable` y Lottie liviano; el APK no debe crecer por recursos. | ✅ |

### 3.8 Administración del grupo

| ID | Requerimiento | Estado |
|---|---|---|
| RF-129 | Pantalla de administración con nombre del grupo, código, pendientes, miembros y acciones. | ✅ |
| RF-130 | Lista de pendientes con perfil básico y botones Aprobar/Rechazar; solo el admin la ve. | ✅ |
| RF-131 | Reset de contraseña con contraseña temporal visible una sola vez, copiable y con advertencia de un solo uso. | ✅ |
| RF-132 | Indicador de plazas ocupadas (`active`/12; las solicitudes `pending` se muestran aparte) y bloqueo de aprobaciones cuando hay 12 activos. | ✅ |
| RF-133 | Renombrar el grupo y regenerar el código con confirmación y explicación de consecuencias. | ✅ |
| RF-134 | Expulsar miembro con confirmación; no se puede expulsar al propio admin. | ✅ |
| RF-135 | El admin no puede transferir el rol desde la app: debe pedirlo al propietario del servidor. | ✅ |
| RF-136 | Si el admin intenta salir o eliminar su cuenta, explicar que primero debe transferir el grupo. | ✅ |

### 3.9 Actualizaciones y avisos

| ID | Requerimiento | Estado |
|---|---|---|
| RF-24 | Endpoint público `GET /app/version` con la última versión y la mínima soportada. | ✅ |
| RF-25 | Aviso dentro de la app cuando hay una versión nueva, con enlace de descarga. | ✅ |
| RF-26 | Avisos generales dentro de la app, publicados desde el servidor. | ✅ |
| RF-27 | Actualización obligatoria si la versión instalada es menor a la mínima soportada. | ✅ |
| RF-137 | La respuesta `426` incluye `latest` y `minSupportedVersionCode` para que la app muestre la descarga. | ✅ |
| RF-138 | La descarga se abre en el navegador; no se usa `REQUEST_INSTALL_PACKAGES` ni instalación interna. | ✅ |
| RF-139 | Los avisos se descartan en DataStore y se vuelven a mostrar solo si cambia su contenido o vigencia. | ✅ |
| RF-140 | Un aviso nuevo se emite por WebSocket (`notice`) con la app abierta; sin push. | ✅ |
| RF-141 | Versiones, avisos y términos se publican por SQL/CLI; no hay panel web ni endpoint de administración. | ✅ |
| RF-142 | El APK publicado tiene SHA-256 y tamaño visibles para verificación manual. | ✅ |

### 3.10 Resiliencia, estados vacíos y offline

| ID | Requerimiento | Estado |
|---|---|---|
| RF-28 | Retomar el tracking tras reiniciar el teléfono, respetando las restricciones de cada versión de Android. | ✅ |
| RF-29 | Cambio o pérdida de teléfono: cerrar sesión de forma remota y volver a entrar sin perder el lugar en el grupo. | ✅ |
| RF-30 | Resincronizar al reconectar el WebSocket o volver a primer plano. | ✅ |
| RF-31 | Reconexión del WebSocket con reintentos de espera creciente y jitter. | ✅ |
| RF-143 | Cola local en Room para ubicaciones sin internet; reenvío por lotes al recuperar conexión. | ✅ |
| RF-144 | Banner offline claro, sin bloquear el mapa si ya hay datos en caché. | ✅ |
| RF-145 | Errores con mensaje comprensible, `requestId` copiable y botón “Reintentar”. | ✅ |
| RF-146 | Si nunca hubo ubicación, mostrar “Sin datos todavía” y no una hora inventada. | ✅ |
| RF-147 | Si falla una actualización de avatar o perfil, conservar el último dato válido y permitir reintentar. | ✅ |
| RF-148 | Si la sesión se revoca mientras la app está abierta, cerrar el WebSocket y llevar a login con explicación. | ✅ |

### 3.11 Protección de recursos y servidor

| ID | Requerimiento | Estado |
|---|---|---|
| RF-149 | Una sola instancia Node/Fastify con PM2 en modo `fork`; sin clúster y sin Redis. | ✅ |
| RF-150 | Nginx delante con TLS, HTTP/2, gzip/brotli, límites de cuerpo, rate limiting, APK estático y `X-Accel-Redirect` para avatares. | ✅ |
| RF-151 | WebSocket de una conexión por sesión, ping cada 30 s, inactividad 90 s, mensajes de 4 KB y 5 por segundo. | ✅ |
| RF-152 | Snapshot comprimido, `Cache-Control: private, no-cache` y `ETag` débil. | ✅ |
| RF-153 | Polling de respaldo limitado a 1 petición cada 5 s por usuario. | ✅ |
| RF-154 | Recursos no accesibles responden `404` para no revelar existencia; solo errores de estado explícitos usan `403`/`409`. | ✅ |
| RF-155 | `429` incluye `Retry-After` en segundos y la app respeta la espera. | ✅ |
| RF-156 | Trabajos: `no_signal` cada minuto; limpieza diaria de sesiones, avisos, membresías y avatares huérfanos; limpieza mensual de APKs. | ✅ |
| RF-157 | Respaldos `pg_dump` diarios, cifrados y fuera del VPS; retención 7 diarios/4 semanales/12 mensuales. | ✅ |
| RF-158 | Monitoreo de `/health`, Uptime Kuma, disco, TLS, respaldos y reinicios de PM2. | ✅ |
| RF-159 | Logs sin tokens, contraseñas, coordenadas ni cuerpos de avatar. | ✅ |
| RF-160 | Pool de conexiones PostgreSQL máximo 10 e índices para sesiones, estado, avisos y membresías. | ✅ |
| RF-161 | `GET /health` público y sin datos sensibles; responde `503` si la base no está disponible. | ✅ |

### 3.12 Seguridad y privacidad

| ID | Requerimiento | Estado |
|---|---|---|
| RF-162 | HTTPS/WSS en todo el tráfico; contraseñas con `argon2id`; validación de entrada y consultas parametrizadas. | ✅ |
| RF-163 | Sin modo oculto: notificación fija del servicio de ubicación y aviso al grupo cuando alguien se une. | ✅ |
| RF-164 | El usuario puede eliminar su cuenta y sus datos desde la app; el admin debe transferir el grupo primero. | ✅ |
| RF-165 | Si hay menores, el administrador es su padre/madre/tutor y acepta los términos en su nombre; revisar con legal. | ⏳ |
| RF-166 | API key de Google Maps restringida al paquete y SHA-1, con alertas de presupuesto y sin APIs extra. | ✅ |
| RF-167 | Sin correo ni contacto de emergencia en el perfil v1. | ✅ |
| RF-168 | Keystore de firma respaldada offline; APK con SHA-256 publicado. | ✅ |
| RF-169 | Términos y privacidad alineados con la Ley 29733 y su reglamento; texto legal pendiente de revisión. | ⏳ |
| RF-170 | Minimización de datos: solo ubicación actual, batería, perfil mínimo, sesiones y avatares. | ✅ |
| RF-171 | Sin historial de rutas ni telemetría de actividad física. | ✅ |

---

## 4. Requerimientos no funcionales

### 4.1 App Android

| ID | Requerimiento | Estado |
|---|---|---|
| RNF-01 | Bajo consumo de batería en segundo plano; precisión `BALANCED_POWER_ACCURACY`, distancia mínima y lotes. | ✅ |
| RNF-02 | Comunicación cifrada con HTTPS y WSS; sin tráfico en claro. | ✅ |
| RNF-03 | Autenticación con token opaco y verificación de pertenencia al grupo en cada recurso. | ✅ |
| RNF-04 | Sin historial de ubicaciones; solo la última posición conocida. | ✅ |
| RNF-05 | Funcionar con conexión intermitente: cola local en Room y reenvío por lotes. | ✅ |
| RNF-06 | Fechas en UTC y contraste con la hora del servidor. | ✅ |
| RNF-07 | Versión mínima de Android: API 26 (Android 8.0), a confirmar con los dispositivos reales. | ⏳ |
| RNF-20 | Arranque en frío < 2 s, primer mapa < 3 s y 60 fps en desplazamiento y animaciones. | ✅ |
| RNF-21 | Consumo objetivo orientativo ≤ 5 % de batería en 24 h con uso normal; medir y ajustar con pruebas reales. | 💡 |
| RNF-22 | Accesibilidad: contraste AA, objetivos de 48 dp, escala de fuente hasta 200 %, descripciones y alternativa al mapa. | ✅ |
| RNF-23 | APK menor a 30 MB, sin recursos duplicados ni animaciones pesadas. | ✅ |
| RNF-24 | La app debe seguir siendo usable con una mano y en pantallas de 5" a 6.7". | ✅ |

### 4.2 Backend y operación

| ID | Requerimiento | Estado |
|---|---|---|
| RNF-08 | Soportar 6 usuarios iniciales y hasta 12 miembros activos por grupo sin impacto notable en el VPS actual. | ✅ |
| RNF-09 | Reinicio automático del servicio ante fallos y tras reinicio del VPS (PM2 con arranque automático). | ✅ |
| RNF-10 | Rate limiting en REST y WebSocket, en Nginx y en la aplicación. | ✅ |
| RNF-11 | Limpieza automática de sesiones vencidas/revocadas, avisos expirados, membresías antiguas y avatares huérfanos. | ✅ |
| RNF-12 | Respaldo diario de PostgreSQL, cifrado y guardado fuera del VPS. | ✅ |
| RNF-13 | Monitoreo de disponibilidad, disco, TLS, respaldos y reinicios. | ✅ |
| RNF-14 | Subida de avatar limitada a 1 MB y validada en el servidor. | ✅ |
| RNF-15 | Avatares accesibles solo para miembros activos del mismo grupo, sin URLs públicas. | ✅ |
| RNF-16 | Contraseñas con `argon2id`; token opaco de 256 bits guardado con hash; sin JWT ni refresh tokens. | ✅ |
| RNF-17 | Límite de intentos de login: 10 cada 15 min por IP y usuario, con bloqueo temporal. | ✅ |
| RNF-18 | Límite de registro/unión: 5 intentos por hora por IP, con error genérico. | ✅ |
| RNF-25 | Una sola instancia Node/Fastify; consumo esperado de 100–150 MB de RAM y CPU prácticamente nula. | 💡 |
| RNF-26 | Sin alta disponibilidad en v1: recuperación automática y ventana de mantenimiento aceptable para uso familiar. | ✅ |
| RNF-27 | Límites de recursos de la sección 14 aplicados en base de datos, aplicación y Nginx. | ✅ |
| RNF-28 | Retención: ubicación solo actual; sesiones vencidas/revocadas y avisos depurados; logs con retención exacta pendiente. | ⏳ |
| RNF-29 | Logs sin tokens, contraseñas, coordenadas, cuerpos de avatar ni datos personales innecesarios. | ✅ |
| RNF-30 | Restauración de respaldos probada cada trimestre, con registro de la prueba. | ✅ |
| RNF-31 | Certificado TLS renovado automáticamente y monitoreado antes de vencer. | ✅ |
| RNF-32 | Respaldos cifrados con clave fuera del VPS; la keystore de firma se guarda offline. | ✅ |

> RNF-19 (`REGISTRATION_MODE`) queda retirado: en v2 no hay registro abierto. No se implementa una bandera para abrirlo.

---

## 5. Estados, ubicación y mensajes

### 5.1 Estados de miembro

El servidor mantiene un solo estado por usuario en `user_state`. La app no calcula `no_signal`; solo lo hace el servidor.

| Estado | Cuándo ocurre | Qué muestra la app |
|---|---|---|
| `active` | Llegó una ubicación o un heartbeat reciente y el usuario no está pausado ni con ubicación desactivada. | Marcador normal. Si aún no hay ubicación: “Sin datos todavía”. |
| `paused` | El usuario pausó explícitamente el uso compartido desde la app. | Última posición en gris y mensaje de pausa. |
| `location_disabled` | El GPS está apagado, el permiso de ubicación fue revocado o la app no puede obtener ubicación. | Última posición en gris y mensaje de ubicación desactivada. |
| `no_signal` | El servidor no recibe ubicación ni heartbeat durante 25 min, o es el estado inicial sin datos. | Última posición en gris y “Sin señal desde hace X min” o “Sin datos todavía”. |

**Precedencia visual:** `paused` > `location_disabled` > `no_signal` > `active`.

Esta precedencia evita mensajes contradictorios: si alguien pausó y además perdió señal, se muestra “pausó”, no “sin señal”. Si desactivó la ubicación y además no hay datos recientes, se muestra “desactivó su ubicación”.

### 5.2 Transiciones

| Desde | Evento | Hacia | Regla |
|---|---|---|---|
| `active` | Sin ubicación ni heartbeat por 25 min | `no_signal` | Trabajo del servidor cada minuto. |
| `no_signal` | Heartbeat | `active` | Solo si el estado era `no_signal`. |
| `no_signal` | Ubicación válida | `active` | Solo si el estado era `no_signal`. |
| `active` | El usuario pausa | `paused` | `POST /me/status`. |
| `paused` | El usuario reanuda | `active` | Solo con `POST /me/status`; el próximo dato confirma. |
| `active` | GPS apagado o permiso revocado | `location_disabled` | `POST /me/status` desde la app. |
| `location_disabled` | El usuario reanuda | `active` | Solo con `POST /me/status`; un lote de ubicación no cambia el estado. |
| `paused` / `location_disabled` | Llega una ubicación en cola | Sin cambio | Se ignora y cuenta como `ignored`; no se difunde. |
| `paused` / `location_disabled` | Pasan 25 min sin datos | Sin cambio | El trabajo `no_signal` solo actúa sobre `active`. |

**Heartbeat:** se envía cada ~10 min si no se envió ubicación. Actualiza batería, `is_charging` y `last_seen_at`; no cambia la posición. Si el estado era `no_signal`, lo devuelve a `active`; si es `paused` o `location_disabled`, no cambia el estado.

**Estado inicial:** `no_signal` con `last_seen_at = null`. La app muestra **“Sin datos todavía”** y no inventa “hace X min”. Si luego llega un heartbeat pero no una ubicación, el estado pasa a `active` y la app sigue mostrando “Sin datos todavía” hasta que exista una posición.

### 5.3 Precisión

- `lowAccuracy = true` cuando `accuracyM > 100`.
- No se descartan ubicaciones imprecisas: se guardan y se muestran.
- El marcador muestra un círculo de precisión con radio `accuracyM`; si el círculo es enorme, se limita visualmente sin mentir sobre el dato.
- Mensaje: **“Ubicación aproximada de {Nombre} (±X m).”**
- En el detalle se muestra la precisión exacta y la fuente (“GPS”, “red” o “aproximada”) si está disponible.
- No hay detección de “salto imposible” en v1.

### 5.4 Batería

- Se envía con cada ubicación y con cada heartbeat.
- Se muestra en la lista, en el marcador y en el detalle.
- **Batería baja:** 15 % o menos. Se usa el token de aviso (`#FBBF24` en oscuro, `#D97706` en claro) y un ícono, no solo color.
- Si está cargando, se muestra el ícono de carga junto al porcentaje.
- Si no hay batería reportada, se muestra “Sin dato” y no un 0 % engañoso.
- Si el miembro está `no_signal`, se muestra la última batería conocida con la etiqueta “última conocida”.

### 5.5 Tiempo y desfase de reloj

- Todas las fechas viajan en ISO 8601 UTC.
- El servidor incluye `serverTime` en login, snapshot, heartbeat y respuestas de ubicación.
- La app calcula `offset = serverTime - now()` y lo usa para “hace X min”.
- Si el reloj del teléfono está muy desviado, la app prioriza el offset del servidor.
- `statusChangedAt` indica desde cuándo está el estado actual; `recordedAt` indica cuándo se midió la ubicación.
- No se muestran horas futuras: si un punto quedó en el futuro, el servidor lo ajusta a su hora.

### 5.6 Mensajes canónicos

| Situación | Mensaje para otros | Mensaje para el propio usuario |
|---|---|---|
| `active` con ubicación | Sin mensaje; marcador normal | “Compartiendo tu ubicación” |
| `active` sin ubicación | “Sin datos todavía.” | “Aún no compartes tu ubicación.” |
| `paused` | “{Nombre} pausó el uso compartido de su ubicación.” | “Tu familia no puede ver tu ubicación. Toca para activarla.” |
| `location_disabled` | “{Nombre} desactivó su ubicación.” | “Tu ubicación está desactivada. Toca para activarla.” |
| `no_signal` con última vez | “Sin señal de {Nombre} desde hace X min.” | “No estamos recibiendo tu ubicación. Revisa la conexión y los permisos.” |
| `no_signal` inicial | “Sin datos todavía.” | “Aún no hay datos de tu ubicación.” |
| Precisión baja | “Ubicación aproximada de {Nombre} (±X m).” | “Tu ubicación es aproximada (±X m).” |
| Batería baja | “Batería baja ({Y} %).” | “Batería baja ({Y} %).” |
| Sin conexión | “Sin conexión. Mostrando la última información disponible.” | Igual, con botón Reintentar |
| Actualización obligatoria | “Actualiza MoonOrbit para seguir usando la app.” | Igual, con botón Descargar |
| Membresía pendiente | — | “Tu solicitud está pendiente de aprobación.” |
| Membresía rechazada | — | “Tu solicitud fue rechazada. Puedes intentar con otro código.” |

**Regla de honestidad:** nunca se afirma que alguien desactivó su ubicación si solo se perdió la señal. Para eso existen `no_signal` y `location_disabled` como estados distintos.

---

## 6. Onboarding y permisos

### 6.1 Orden y comportamiento

| Paso | Pantalla | Acción | Si falla o se omite |
|---|---|---|---|
| 1 | Bienvenida | Explica qué hace la app y qué no hace; botón “Comenzar”. | No bloquea. |
| 2 | Términos y privacidad | Muestra versión vigente y exige aceptación explícita. | Sin aceptar no se puede registrar ni entrar al mapa. |
| 3 | Registro o login | Código de invitación, usuario, contraseña, nombre y dispositivo. | Error claro; el código es genérico (“no válido o vencido”). |
| 4 | Ubicación | Solicita `ACCESS_FINE_LOCATION` y `ACCESS_COARSE_LOCATION` juntos. | Sin ubicación no se comparte; se puede ver el grupo con banner. |
| 5 | Ubicación precisa | Solo si quedó aproximada; explica el cambio y abre ajustes si hace falta. | Se comparte aproximada, con aviso de precisión baja. |
| 6 | Segundo plano | Explica “todo el tiempo” y lleva a ajustes en Android 11+. | Se comparte solo con la app abierta; banner y explicación. |
| 7 | Notificaciones | Android 13+: solicita `POST_NOTIFICATIONS`. | El servicio sigue, pero el aviso puede no verse; se advierte. |
| 8 | Batería | Explica la optimización y ofrece abrir ajustes o solicitar exención. | El sistema puede retrasar el envío; banner informativo. |
| 9 | Resumen | Checklist con estado y botón para corregir cada punto. | Se puede continuar con limitaciones visibles. |
| 10 | Pendiente | Solo si `membership = pending`; muestra el nombre del grupo y “Esperando aprobación”. | No hay mapa ni datos del grupo. |
| 11 | Mapa | Snapshot, permisos y estado propio. | Si falta algo, banner persistente. |

### 6.2 Permisos y versiones de Android

| Permiso / ajuste | Android | Obligatorio | Si falta |
|---|---|---|---|
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | 6.0+ | Sí para compartir | La app funciona en modo lectura; no envía ubicación. |
| `ACCESS_BACKGROUND_LOCATION` | 10+ | Sí para segundo plano | Solo comparte con la app abierta; al cerrarla se detiene el envío. |
| `POST_NOTIFICATIONS` | 13+ | Recomendado | El foreground service puede seguir, pero el aviso fijo puede no verse en la bandeja. |
| `FOREGROUND_SERVICE` | 9+ | Sí | No se puede iniciar el servicio de ubicación. |
| `FOREGROUND_SERVICE_LOCATION` + `foregroundServiceType="location"` | 14+ | Sí | El sistema rechaza el servicio; hay que declararlo en el manifest. |
| `RECEIVE_BOOT_COMPLETED` | 6.0+ | Normal, para reanudar | No se retoma tras reiniciar; se avisa al abrir. |
| Exención de optimización de batería | 6.0+ | Recomendado | El sistema puede matar o retrasar el servicio. |
| Inicio automático del fabricante | OEM | Recomendado | Xiaomi/Huawei/Oppo/Samsung pueden bloquear el segundo plano. |

**Detalles por versión:**

- **Android 10 (API 29):** `ACCESS_BACKGROUND_LOCATION` se puede solicitar en el flujo; explicar claramente “Permitir todo el tiempo”.
- **Android 11 (API 30):** el permiso de segundo plano ya no se concede con un diálogo; hay que abrir los ajustes de la app y elegir **Ubicación → Permitir todo el tiempo**. La app debe detectar la ausencia y llevar directo a esa pantalla.
- **Android 12 (API 31):** el usuario puede conceder solo ubicación aproximada. Hay que detectar `FINE` denegado + `COARSE` concedido y mostrar el paso de ubicación precisa; si el sistema no vuelve a mostrar el diálogo, abrir ajustes.
- **Android 13 (API 33):** `POST_NOTIFICATIONS` es permiso de ejecución. Se pide después de ubicación y antes de batería. Si se deniega, no se bloquea el registro, pero se advierte que el aviso del servicio puede no verse.
- **Android 14 (API 34):** declarar `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE` y `android:foregroundServiceType="location"`. Iniciar el servicio con la app visible; no confiar en iniciarlo desde segundo plano.
- **Android 15+:** verificar restricciones adicionales de foreground services y permisos al momento de implementar; no asumir que el comportamiento de Android 14 se mantiene.

### 6.3 Qué pasa si falta un permiso

| Escenario | Comportamiento | Aviso |
|---|---|---|
| Ubicación denegada por completo | La app abre en modo lectura si la membresía está activa; no comparte ubicación. | Banner rojo: “Tu familia no puede ver tu ubicación” + “Activar”. |
| Solo ubicación aproximada | Comparte con precisión de red; puede marcar `lowAccuracy`. | Banner: “Activa la ubicación precisa para mejorar el mapa”. |
| Segundo plano denegado | Comparte solo mientras la app está abierta. Al salir, se detiene el envío. | Banner: “Solo se comparte con la app abierta” + “Permitir todo el tiempo”. |
| Notificaciones denegadas | El servicio de ubicación sigue; el aviso puede no aparecer en la bandeja. | Aviso en Ajustes: “Activa las notificaciones para ver el aviso del servicio”. |
| Batería sin exención | El sistema puede retrasar o detener el envío. | Banner: “Permite que MoonOrbit funcione sin restricciones”. |
| GPS apagado | La app informa `location_disabled` al servidor. | Banner: “Activa la ubicación del teléfono” + abrir ajustes. |
| Diálogo ya no disponible | El botón cambia a “Abrir ajustes”. | Se explica que el permiso se denegó de forma permanente. |

**Re-verificación:** al volver de ajustes o de otro flujo, la app comprueba permisos en `ON_RESUME`, actualiza el checklist y retoma el paso pendiente. No se vuelve a pedir un permiso que el sistema ya no puede mostrar.

**Textos sugeridos:**

- **Bienvenida:** *Tu familia, siempre en tu órbita.* Comparte tu ubicación solo con las personas de tu grupo. Puedes pausarla cuando quieras.
- **Ubicación:** *Para que tu familia sepa dónde estás.* Solo la ven los miembros activos de tu grupo.
- **Segundo plano:** *Para que te vean aunque cierres la app.* Elige “Permitir todo el tiempo”. Puedes pausar cuando quieras.
- **Notificaciones:** *Un aviso para que sepas cuándo se comparte.* Android muestra una notificación fija mientras el servicio está activo.
- **Batería:** *Evita que el sistema detenga la app.* Algunos teléfonos cierran apps en segundo plano; permite que MoonOrbit funcione sin restricciones.

---

## 7. Pantallas

| Pantalla | Propósito | Contenido y acciones |
|---|---|---|
| **Bienvenida** | Explicar el producto y la privacidad. | Ilustración, propuesta de valor, “Comenzar”, enlace a términos. |
| **Términos y privacidad** | Consentimiento informado. | Versión vigente, resumen de datos, enlace al texto completo, checkbox y “Aceptar y continuar”. |
| **Login** | Entrar con usuario y contraseña. | Usuario, contraseña, mostrar/ocultar, “Entrar”, error genérico, enlace a registro. |
| **Registro** | Crear cuenta con código. | Código, usuario, contraseña, nombre para mostrar, nombre del dispositivo, términos aceptados. |
| **Pendiente** | Esperar aprobación. | Nombre del grupo, estado, “Actualizar”, acceso a perfil y sesiones; sin mapa. |
| **Rechazado** | Explicar el rechazo. | Motivo genérico, “Intentar con otro código”, “Cerrar sesión”; sin datos del grupo. |
| **Permisos** | Conseguir ubicación, segundo plano, notificaciones y batería. | Un paso por pantalla, ilustración, explicación, botón de acción y “Ahora no” cuando aplique. |
| **Resumen de permisos** | Ver el estado antes del mapa. | Checklist con íconos, “Corregir”, “Continuar”. |
| **Mapa** | Ver al grupo en tiempo real. | Google Maps, marcadores, panel inferior, banner de permisos/offline, avisos, actualización. |
| **Detalle de miembro** | Ver estado y contacto. | Avatar, nombre, estado, “hace X”, batería, precisión, última ubicación, “Llamar” si hay teléfono. |
| **Perfil** | Editar datos propios. | Avatar, nombre, teléfono, usuario, cambio de contraseña, eliminar cuenta. |
| **Ajustes** | Control y transparencia. | Pausar/reanudar, permisos, tema, avisos, sesiones, versión, términos, cerrar sesión. |
| **Administración** | Gestionar el grupo. | Nombre, plazas, pendientes, miembros, código, reset de contraseñas, expulsar. |
| **Invitación** | Compartir el código. | Código grande, copiar, regenerar, vencimiento, advertencia de que el anterior deja de servir. |
| **Sesiones** | Ver y revocar dispositivos. | Lista con dispositivo, última actividad, “Esta sesión”, revocar, “Cerrar las demás”. |
| **Avisos** | Leer novedades internas. | Lista de avisos vigentes, severidad, descartar, vacío ilustrado. |
| **Actualización** | Resolver versión nueva u obligatoria. | Versión instalada, última, notas, tamaño, SHA-256, “Descargar” (navegador), “Reintentar”. |
| **Cambio de contraseña** | Cumplir `must_change_password`. | Contraseña actual, nueva, confirmación, requisitos; no se puede saltar. |
| **Sin grupo / Unirse** | Reingresar tras salir o rechazo. | Campo de código, explicación, “Unirme”, cerrar sesión. |
| **Estados vacíos / error / offline** | Evitar pantallas en blanco. | Ilustración, título, explicación, acción siguiente; variantes para sin datos, sin conexión, error y sin avisos. |

**Reglas de navegación:**

- Si hay actualización obligatoria, la pantalla de actualización bloquea el resto salvo “Descargar” y “Reintentar”.
- Si `must_change_password`, el cambio de contraseña bloquea el resto salvo cerrar sesión.
- Si la membresía es `pending`, no se muestra el mapa ni datos de miembros.
- Si la membresía es `rejected` o `left`, se muestra “Sin grupo / Unirse”.
- Si el usuario está activo pero sin permisos, el mapa se muestra en modo lectura con banner.
- El botón atrás nunca debe llevar a una pantalla de un grupo al que ya no se tiene acceso.

---

## 8. Perfil y avatares WebP

### 8.1 Datos del perfil

| Campo | Obligatorio | Visibilidad | Notas |
|---|---|---|---|
| Nombre para mostrar | Sí | Grupo activo | 2–40 caracteres; se recorta y se valida. |
| Teléfono | No | Grupo activo | Solo para el botón “Llamar”; se abre el marcador, no se llama dentro de la app. |
| Nombre de usuario | Sí | Propio | 3–24 caracteres, minúsculas, números y `_`; inmutable y único. No se usa para recuperar contraseña. |
| Avatar | No | Grupo activo | WebP 512×512; sin foto se usan iniciales. |
| Correo | — | — | No existe en v1. |
| Contacto de emergencia | — | — | No existe en v1. |

El perfil no incluye email, contacto de emergencia, dirección, fecha de nacimiento ni redes sociales. La app no pide esos datos y el servidor no los guarda.

### 8.2 Flujo de selección y subida

1. **Origen:** Photo Picker (`PickVisualMedia`) para galería, sin permiso de almacenamiento; cámara mediante el contrato del sistema, sin declarar `CAMERA`.
2. **Recorte:** cuadrado, con zoom y encuadre; vista previa circular como se verá en la app.
3. **Orientación:** se corrige la rotación EXIF antes de comprimir.
4. **Compresión en el dispositivo:** redimensionar a 512×512 y comprimir a WebP con calidad 80; objetivo menor a 100 KB y límite duro de 1 MB.
5. **Vista previa:** mostrar el resultado, peso aproximado y botones “Guardar” / “Cancelar”.
6. **Subida:** `PUT /users/me/avatar` con `Content-Type: image/webp` y el binario en el cuerpo. Sin multipart.
7. **Validación del servidor:** tipo real (RIFF/WEBP), dimensiones, tamaño máximo 1 MB y decodificación correcta.
8. **Re-codificación:** `sharp` a 512×512, calidad 80, sin metadatos, sin EXIF y sin orientación pendiente.
9. **Confirmación:** la app recibe `{ avatarVersion }`, actualiza la URL de Coil y muestra el nuevo avatar.

**Errores frecuentes:**

| Error | Respuesta | Qué muestra la app |
|---|---|---|
| Archivo mayor a 1 MB | `413 payload_too_large` | “La imagen es demasiado grande; elige otra.” |
| Tipo no soportado | `415 unsupported_media_type` | “Formato no válido; usa WebP.” |
| Imagen corrupta | `400 invalid_image` | “No pudimos procesar la imagen.” |
| Límite de subidas | `429 rate_limited` + `Retry-After` | “Espera un momento antes de volver a intentar.” |
| Sesión vencida | `401` | Login con mensaje. |
| Sin grupo activo | `403 membership_pending` o `404` | Explicación según el estado. |

### 8.3 Procesamiento en Android

- API 30+: `Bitmap.CompressFormat.WEBP_LOSSY` con calidad 80.
- API 26–29: `Bitmap.CompressFormat.WEBP` (obsoleto desde API 30) con la misma calidad.
- Se recomprime desde un `Bitmap` ya rotado, por lo que se pierden metadatos EXIF, incluida cualquier ubicación de la foto original.
- No se sube el original; no se guarda una copia local permanente fuera de la caché de Coil.
- El recorte y la compresión se hacen fuera del hilo principal; mostrar progreso si tarda.
- Si el resultado supera 1 MB, bajar calidad de forma iterativa hasta 60 o rechazar localmente antes de subir.

### 8.4 Almacenamiento y entrega

| Aspecto | Regla |
|---|---|
| Ruta | `avatars/{userId}/{version}.webp`, fuera de la raíz pública de Nginx. |
| Base de datos | `users.avatar_path` y `users.avatar_version`; `avatar_path` puede ser `null`. |
| Reemplazo | Se escribe el archivo nuevo, se actualiza la BD y después se borra el anterior. Si falla el borrado, queda huérfano y lo limpia el job. |
| Eliminación | `DELETE /users/me/avatar` borra el archivo, pone `avatar_path = null` e incrementa `avatar_version`. |
| Acceso | `GET /users/{userId}/avatar` exige sesión. El propio avatar siempre se puede ver, incluso en `pending`, `left` o `rejected`; el de otro usuario exige grupo activo en común. Si no hay acceso, `404`. |
| ETag | `ETag: "u{userId}-v{version}"`; si `If-None-Match` coincide, `304` sin cuerpo. |
| Caché | `Cache-Control: private, max-age=31536000, immutable`; la app añade `?v={avatarVersion}` como clave de Coil. |
| Nginx | Node valida, responde `X-Accel-Redirect` a una location interna y Nginx sirve el archivo. |
| Privacidad | Sin URL pública, sin listado de directorios, sin indexación. |

**Nota sobre ETag y `X-Accel-Redirect`:** Node comprueba `If-None-Match` con el ETag de versión antes de delegar. Si coincide, responde `304` y no hay redirección interna. Si no coincide, responde `X-Accel-Redirect`; Nginx sirve el WebP con `Content-Type: image/webp` y la caché configurada. La app usa `?v={avatarVersion}` como clave principal, de modo que un cambio de versión no depende del ETag que genere Nginx; el ETag de versión de Node se usa para el `304` temprano.

### 8.5 Coil y caché en Android

- El cliente HTTP de Coil usa el mismo interceptor de autorización que Retrofit; el avatar no se puede pedir sin token.
- La clave de caché incluye `userId` y `avatarVersion`; al cambiar la versión, Coil pide la nueva imagen.
- Al cerrar sesión se limpian la caché de memoria y la de disco de Coil para no mostrar avatares de otra cuenta.
- Si el avatar devuelve `404`, se usa el fallback de iniciales; no se reintenta en bucle.
- Si el avatar devuelve `304`, Coil conserva la imagen cacheada.
- No se cachean avatares ajenos de usuarios fuera del grupo activo; el propio avatar sí puede cachearse.

### 8.6 Fallback de iniciales

- Se toma la primera letra del nombre para mostrar; si está vacío, la primera del usuario.
- El color se deriva de un hash estable de `userId` sobre una paleta accesible; el mismo usuario siempre tiene el mismo color.
- El texto usa blanco o negro según el contraste calculado, no un valor fijo.
- El avatar circular mantiene el anillo de estado; las iniciales no dependen de red.
- No se usan servicios externos de avatares generados.

### 8.7 Limpieza de huérfanos

- Job diario: recorre `avatars/{userId}/`, compara con `users.avatar_path` y elimina archivos sin referencia con más de 24 h.
- También elimina carpetas vacías y archivos temporales de subida.
- Al eliminar una cuenta, se borra su carpeta completa y se revocan sus sesiones.
- El job registra solo el número de archivos eliminados y los `userId` afectados; no registra rutas completas ni contenido.
- Si el job falla, se reintenta al día siguiente y se alerta si hay más de un fallo consecutivo.

### 8.8 Límites y privacidad

- Máximo 1 MB por subida; 5 subidas por hora por usuario.
- Solo se conserva el WebP re-codificado; nunca el original.
- Sin EXIF, sin geolocalización de la foto, sin nombre de archivo original.
- Solo lo ven miembros activos del mismo grupo; el administrador no tiene un permiso especial para ver avatares de otros grupos.
- El avatar de un miembro `pending`, `rejected` o `left` no es accesible para el grupo; la única excepción es el propio usuario viendo su avatar.
- La eliminación de la foto es inmediata para el usuario; la caché de otros dispositivos se invalida al cambiar `avatarVersion`.

---

## 9. Diseño premium claro/oscuro

### 9.1 Tokens de color

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `background` | `#060913` | `#F5F8FF` | Fondo general |
| `surface` | `#0D1524` | `#FFFFFF` | Tarjetas, hojas, campos |
| `surfaceVariant` | `#16233A` | `#E8EEF9` | Superficie secundaria, chips, skeletons |
| `primary` | `#22D3EE` | `#0891B2` | Acciones principales, selección, enlaces |
| `secondary` | `#67E8F9` | `#0E7490` | Acentos suaves, iconos secundarios |
| `tertiary` | `#8B5CF6` | `#7C3AED` | Acentos de marca, ilustraciones |
| `textPrimary` | `#F8FAFC` | `#0B1220` | Texto principal |
| `textSecondary` | `#94A3B8` | `#475569` | Texto secundario, metadatos |
| `border` | `#334155` | `#CBD5E1` | Bordes, divisores, inputs |
| `success` | `#34D399` | `#059669` | Activo, confirmaciones |
| `warning` | `#FBBF24` | `#D97706` | Pausa, batería baja, advertencias |
| `error` | `#FB7185` | `#DC2626` | Errores, ubicación desactivada, acciones destructivas |

**Reglas de uso:**

- No se usa color dinámico de Android; la marca manda.
- El estado nunca se comunica solo con color: siempre hay ícono, texto o forma.
- El texto sobre `primary` debe verificarse con contraste AA; no se asume blanco sobre cian. Si no alcanza, se usa `textPrimary` o una variante más oscura.
- Los estados usan tokens semánticos: `active` → `success`/`primary`; `paused` → `warning`; `location_disabled` → `error`; `no_signal` → `textSecondary`.
- Los mapas y las ilustraciones se adaptan a ambos temas; no se invierte una imagen con filtros.

### 9.2 Tipografía

- **Familias:** Manrope o Plus Jakarta Sans, con fallback a la fuente del sistema.
- **Escala:** Material 3, con `body` base de 16 sp y mínimo 12 sp para metadatos.
- **Pesos:** 400 regular, 500 medium, 600 semibold, 700 bold; evitar más de tres pesos por pantalla.
- **Títulos:** 24–32 sp, interlineado 1.2; cuerpo 16 sp, interlineado 1.5.
- **Números y horas:** tabulares cuando sea posible para evitar saltos.
- **Textos largos:** ancho máximo de 60–70 caracteres; no justificar.
- **Escalado:** la app debe soportar hasta 200 % sin cortar acciones ni superponer el marcador.

### 9.3 Formas, elevación y gradientes

- Esquinas: tarjetas 24 dp, hojas inferiores 28 dp arriba, botones 20 dp, campos 20 dp, chips 999 dp.
- Elevación: 0 dp para fondos, 1 dp para tarjetas, 2 dp para hojas y elementos flotantes; sombras suaves, nunca duras.
- Bordes: 1 dp con `border`; no usar bordes de 2 dp salvo foco.
- Gradiente de fondo oscuro: `#060913 → #0D1524` a 135°.
- Gradiente de fondo claro: `#F5F8FF → #E8EEF9` a 135°.
- Acento radial cian con 6–8 % de opacidad en bienvenida y estados vacíos; estático.
- Sin desenfoques pesados, sin glassmorphism costoso, sin animaciones de fondo continuas.
- Las tarjetas no deben tener sombra y borde al mismo tiempo si compiten; elegir uno.

### 9.4 Mapa, marcadores y precisión

| Elemento | Oscuro | Claro |
|---|---|---|
| Fondo del mapa | `#060913` | `#F5F8FF` |
| Calles | `#16233A` | `#FFFFFF` |
| Agua | `#0A1A2F` | `#DCEBFF` |
| Parques | `#0E2A24` | `#DFF5E8` |
| Etiquetas | `#94A3B8` | `#475569` |

> Los hex del JSON del mapa son una propuesta de implementación 💡; se validan visualmente con la paleta y el contraste real en dispositivos.

- **Marcador:** avatar circular de 40 dp visuales con objetivo táctil de 48 dp, anillo de 3 dp según estado, nombre debajo y chip de batería.
- **Selección:** escala 1.15, elevación 2 dp y panel inferior con el detalle.
- **Precisión:** círculo con relleno `primary` al 10 % y borde al 30 %; si `lowAccuracy`, se muestra además el texto “±X m”.
- **Pulso:** solo en `active`, ciclo de 2 s, opacidad 0.2–0.5; se desactiva con “reducir movimiento”.
- **Sin agrupación de marcadores:** el máximo es 12; no se necesita clustering.
- **Mapa vacío:** si no hay ubicaciones, se muestra una ilustración y “Sin datos todavía”, no un mapa en blanco.
- **Cambio de tema:** el estilo del mapa se actualiza al cambiar claro/oscuro; no se recrea la actividad.

### 9.5 Movimiento

- Duración: 150–250 ms para transiciones y cambios de estado.
- Easing estándar de Material 3; evitar rebotes exagerados.
- Panel inferior: 220 ms; marcador seleccionado: 180 ms; aparición de banner: 200 ms.
- Pulso del marcador: 2 s, solo si el miembro está `active` y la app está en primer plano.
- “Reducir movimiento” del sistema: sin pulso, sin parallax y transiciones en fundido.
- No usar animaciones para ocultar latencia; si algo tarda, mostrar skeleton o progreso.

### 9.6 Accesibilidad

- Contraste AA: 4.5:1 para texto normal y 3:1 para texto grande e íconos.
- Objetivos táctiles mínimos de 48×48 dp, con separación de 8 dp.
- Escala de fuente hasta 200 % sin pérdida de contenido; probar con “Fuente grande” del sistema.
- Descripciones de contenido en avatar, estado, batería, botones de ícono y mapa.
- La lista de miembros es una alternativa completa al mapa; no se obliga a interactuar con el mapa.
- El estado se anuncia como texto (“Mamá está sin señal desde hace 10 minutos”), no como “gris”.
- Orden de foco lógico y TalkBack probado en mapa, lista y detalle.
- No usar texto en imágenes; las ilustraciones son decorativas y tienen descripción o se ocultan al lector.

### 9.7 Tema y persistencia

- Modos: **sistema**, **claro**, **oscuro**; persistido en DataStore.
- Al cambiar el modo, se actualizan colores, mapa, ilustraciones y barras del sistema sin reiniciar.
- Sin color dinámico de Android (Material You).
- La barra de estado usa íconos claros en oscuro y oscuros en claro; `edge-to-edge` con insets correctos.
- Los skeletons y las imágenes usan `surfaceVariant` para no brillar en oscuro.
- El tema se aplica antes de mostrar el contenido para evitar un destello blanco en arranque frío.

### 9.8 Ilustraciones y recursos

- Un solo estilo de ilustración, recoloreado a la paleta; SVG/`VectorDrawable` para estáticos y Lottie liviano para lo imprescindible.
- Bienvenida: familia conectada por puntos de luz, luna y órbita.
- Términos: escudo con pin de ubicación.
- Permisos: pin, círculo de precisión, órbita, campana y batería.
- Estados vacíos: luna con antena, mapa vacío, nube sin conexión.
- Perfil: marco circular con cámara.
- No usar fotos de stock ni recursos con licencia dudosa; verificar licencia de cada ícono, ilustración y animación.

---

## 10. Arquitectura Android y backend

### 10.1 App Android

```
app/src/main/java/online/moondev/orbit/
├── MoonOrbitApp.kt
├── core/
│   ├── designsystem/       # tema, tokens, componentes, ilustraciones
│   ├── network/            # Retrofit, OkHttp, AuthInterceptor, ETag, errores
│   ├── database/           # Room: cola de ubicaciones y caché de snapshot
│   ├── datastore/          # sesión cifrada, tema, avisos descartados, términos
│   └── util/               # reloj, formato de fechas, compresión WebP
├── data/                   # repositorios, API REST, WebSocket, mappers
├── domain/                 # modelos y casos de uso
├── feature/
│   ├── onboarding/         # bienvenida, términos, permisos, resumen
│   ├── auth/               # login, registro, cambio de contraseña
│   ├── group/              # pendiente, rechazado, unirse
│   ├── map/                # mapa, marcadores, panel inferior, banners
│   ├── members/            # lista y detalle de miembro
│   ├── profile/            # perfil, recorte y compresión de avatar
│   ├── admin/              # grupo, invitación, pendientes, miembros, reset
│   ├── sessions/           # dispositivos y revocación
│   ├── notices/            # avisos internos
│   ├── update/             # versión y actualización obligatoria
│   └── settings/           # pausa, permisos, tema, cuenta
├── tracking/
│   ├── LocationService.kt  # foreground service tipo location
│   ├── LocationQueue.kt    # Room + envío por lotes
│   ├── HeartbeatScheduler.kt
│   ├── BootReceiver.kt
│   ├── ProviderChangedReceiver.kt
│   └── TrackingNotifier.kt
└── ui/                     # navegación, scaffold, tema de la app
```

**Decisiones de implementación:**

- **DI:** Hilt. **Red:** Retrofit + OkHttp; el mismo cliente autentica REST y WebSocket.
- **WebSocket:** una conexión por sesión, con reconexión exponencial y `resync` al volver a primer plano. Solo recibe; la app no envía ubicación por ese canal.
- **Polling:** repositorio con `Lifecycle` de primer plano; cada 30 s, `If-None-Match` y `304` sin actualizar UI.
- **Cola:** Room guarda lotes pendientes; se envían al recuperar conexión y se descartan los puntos con más de 24 h.
- **Tracking:** `FusedLocationProviderClient` con `PRIORITY_BALANCED_POWER_ACCURACY`, distancia mínima 50–100 m y `setMaxUpdateDelayMillis` para agrupar. Sin Activity Recognition ni geofence.
- **Foreground service:** tipo `location`, notificación fija e intransferible; se inicia con la app visible y se reanuda tras reinicio si el sistema lo permite.
- **Heartbeat:** cada ~10 min si no se envió ubicación; se programa dentro del servicio, no con alarmas exactas.
- **Permisos:** se comprueban en `ON_RESUME`; si el diálogo ya no está disponible, se abre ajustes.
- **Token:** DataStore con cifrado AES-GCM usando Android Keystore; `allowBackup=false`, `fullBackupContent=false` y reglas de extracción que excluyen el token.
- **Seguridad de red:** `usesCleartextTraffic=false`; solo HTTPS/WSS.
- **Mapa:** `maps-compose` con `MapStyleOptions` claro/oscuro; sin *Map ID*.
- **Imágenes:** Coil con cabecera de autorización y caché por `avatarVersion`.
- **Fuera de la app:** no hay módulo de historial, geofence, Activity Recognition, push ni panel web.

### 10.2 Backend

```
backend/
├── src/
│   ├── server.js
│   ├── config.js
│   ├── routes/             # auth, users, groups, location, app, health
│   ├── ws/                 # hub, autenticación, eventos, snapshot
│   ├── services/           # auth, sesiones, grupos, ubicación, estado, avatares, términos, avisos
│   ├── jobs/               # no_signal, limpieza diaria, limpieza de APKs
│   ├── middleware/         # auth, must_change_password, membership, rate limit, errores
│   ├── db/                 # pool, migraciones, consultas
│   └── storage/            # avatares y X-Accel-Redirect
├── scripts/                # crear grupo, transferir admin, reset admin, publicar
├── migrations/
├── ecosystem.config.js     # PM2 fork
└── .env.example
```

**Stack y reglas:**

- Node.js LTS + Fastify; `@fastify/websocket` o `ws` para el canal; `pg` con pool máximo 10.
- `argon2id` para contraseñas; `sharp` para avatares; `zod` para validación; `pino` para logs estructurados.
- Una sola instancia con PM2 `fork`; sin clúster, sin Redis y sin colas externas.
- Migraciones SQL versionadas; no se modifica el esquema a mano en producción.
- Jobs con `setInterval`/`node-cron` y bloqueo `pg_try_advisory_lock` para evitar solapamientos si se reinicia el proceso.
- Logs con `requestId`, método, ruta, estado y duración; se redactan `Authorization`, cookies, coordenadas y cuerpos.
- Nginx termina TLS, comprime, limita cuerpos y sirve APK/avatares; Node no expone archivos directamente.

### 10.3 Despliegue

1. DNS `orbit.moondev.online` apuntando al VPS.
2. Nginx con TLS de Certbot, HTTP/2, gzip/brotli, `client_max_body_size` por ruta y *upgrade* de WebSocket.
3. PostgreSQL local con usuario dedicado y contraseña fuerte.
4. PM2 en modo fork con `pm2 startup` y `pm2 save`; reinicio automático.
5. Carpeta `avatars/` fuera de `public/`, con permisos del usuario de Node.
6. Carpeta `download/` para APKs, servida por Nginx con caché y sin listado.
7. Respaldos programados y monitoreo externo.

**Variables de entorno principales:**

| Variable | Uso |
|---|---|
| `DATABASE_URL` | Conexión PostgreSQL. |
| `PORT` | Puerto interno de Fastify (detrás de Nginx). |
| `SESSION_TTL_DAYS` | 90 por defecto. |
| `MAX_SESSIONS` | 5 por defecto. |
| `AVATAR_DIR` | Ruta absoluta fuera de la raíz pública. |
| `PUBLIC_BASE_URL` | `https://orbit.moondev.online`. |
| `APK_DIR` | Carpeta de APKs servida por Nginx. |
| `LOG_LEVEL` | `info` en producción. |

---

## 11. Autenticación, sesiones y esquema

### 11.1 Contrato de autenticación

| Método y ruta | Auth | Descripción |
|---|---|---|
| `POST /auth/register` | No | Código, usuario, contraseña, nombre, dispositivo y versión de términos. Crea usuario, membresía `pending` y sesión. |
| `POST /auth/login` | No | Usuario y contraseña; devuelve `session` con `sessionToken` y `expiresAt`, membresía y flags. |
| `POST /auth/logout` | Sí | Revoca la sesión actual. |
| `POST /auth/logout-others` | Sí | Revoca todas las sesiones menos la actual. |
| `POST /auth/change-password` | Sí | Contraseña actual y nueva; se usa también con `must_change_password`. |
| `GET /auth/sessions` | Sí | Lista sesiones propias con dispositivo y fechas. |
| `DELETE /auth/sessions/{id}` | Sí | Revoca una sesión propia. |

**Ciclo de vida de una sesión:**

1. En login/registro se generan 32 bytes aleatorios (256 bits) y se codifican en Base64URL sin relleno (43 caracteres).
2. Se calcula `SHA-256(token)` y se inserta en `sessions.token_hash`; el token en claro solo viaja al cliente.
3. Si el usuario ya tiene 5 sesiones activas, se revoca la más antigua por `last_used_at`.
4. En cada petición, el servidor hashea el bearer, busca una sesión no revocada y no vencida, y actualiza `last_used_at` (como máximo una escritura cada 5 min por sesión para no castigar la base).
5. Al revocar, se setea `revoked_at`, se emite `session_revoked` y se cierra el WebSocket con `4403`.
6. A los 90 días la sesión vence; el cliente recibe `401 session_expired` y vuelve a login. Si el WebSocket está abierto, se cierra con `4401`. No hay refresh.

**Reglas de cuenta y contraseña (💡):**

- `username`: 3–24 caracteres, solo minúsculas, números y `_`; único sin distinguir mayúsculas e inmutable.
- `displayName`: 2–40 caracteres, sin espacios al inicio ni al final; se puede editar.
- Contraseña: mínimo 10 caracteres, máximo 128; se permite cualquier carácter Unicode.
- La contraseña no puede ser igual al `username` ni al `displayName`.
- No se exige composición obligatoria; sí se rechazan las contraseñas más comunes si se mantiene una lista local.
- `argon2id` con parámetros razonables para el VPS; nunca SHA-256 simple.
- La contraseña temporal del reset se genera aleatoria, se muestra una vez y es de un solo uso.
- El login responde siempre “Usuario o contraseña incorrectos”, sin revelar si el usuario existe.

**Reset de contraseña:**

- El administrador puede resetear a un miembro no administrador desde **Administración → Miembros**.
- El servidor genera una contraseña temporal, la devuelve una sola vez, revoca todas las sesiones del miembro y activa `must_change_password`.
- El miembro inicia sesión con la temporal, cambia la contraseña y recién entonces recupera el acceso al mapa.
- El administrador no puede resetear su propia contraseña desde la app; si la olvida, el propietario del servidor usa un script.
- No hay recuperación por correo ni preguntas de seguridad.

### 11.2 Grupo, membresías y administrador

- **Un grupo por usuario:** índice único parcial sobre `group_members(user_id)` para `status IN ('pending','active')`.
- **Un administrador por grupo:** índice único parcial sobre `group_members(group_id)` para `role = 'admin' AND status = 'active'`.
- **Admin activo:** una restricción adicional impide `role = 'admin'` con `status <> 'active'`.
- **Plazas:** máximo 12 miembros `active`; las solicitudes `pending` no ocupan plaza, pero unirse o aprobar falla con `409 group_full` si ya hay 12 activos. La validación se hace en una transacción con bloqueo del grupo.
- **Creación:** solo por script del propietario; el script crea el grupo, el usuario admin si no existe y la membresía `admin/active`.
- **Transferencia:** solo por script del propietario; actualiza el admin anterior a `member` y el nuevo a `admin` en una transacción.
- **Eliminación del grupo:** solo por script del propietario; borra membresías y datos asociados, nunca las cuentas de los miembros.
- **Salida del admin:** bloqueada con `409 admin_transfer_required`.
- **Expulsión:** el admin puede expulsar a un miembro activo; la membresía pasa a `left` y se emite `member_removed`.
- **`GET /groups`:** devuelve 0 o 1 grupo. Para `pending` incluye estado y nombre, pero nunca snapshot ni miembros; para `rejected`/`left` devuelve `groups: []` sin datos del grupo. No existe selector de grupos.

### 11.3 Esquema resumido

| Tabla | Campos principales | Reglas |
|---|---|---|
| `users` | `id`, `username`, `password_hash`, `must_change_password`, `display_name`, `phone`, `avatar_path`, `avatar_version`, `accepted_terms_version`, `accepted_terms_at`, timestamps | `username` `citext` único, 3–24 minúsculas/números/`_`; `display_name` 2–40; sin email ni contacto de emergencia. |
| `groups` | `id`, `name`, `invite_code`, `invite_code_expires_at`, `created_by`, `created_at`, `updated_at` | Nombre 2–40; código único de 8 caracteres; vencimiento obligatorio. |
| `group_members` | `group_id`, `user_id`, `role`, `status`, `joined_at`, `decided_at`, `decided_by`, `updated_at` | `role` en `admin/member`; `status` en `pending/active/rejected/left`; PK compuesta. |
| `sessions` | `id`, `user_id`, `token_hash`, `device_name`, `app_version_code`, `created_at`, `last_used_at`, `expires_at`, `revoked_at` | `token_hash` único; sin refresh token. |
| `user_state` | `user_id`, `latitude`, `longitude`, `accuracy_m`, `low_accuracy`, `battery_level`, `is_charging`, `recorded_at`, `received_at`, `last_seen_at`, `status`, `status_changed_at` | Una fila por usuario; `status` por defecto `no_signal`. |
| `app_releases` | `version_code`, `version_name`, `apk_url`, `apk_sha256`, `size_bytes`, `release_notes`, `is_min_supported`, `published_at` | Última = mayor `version_code`; mínima = mayor con `is_min_supported`; si no hay, 1. |
| `notices` | `id`, `title`, `message`, `severity`, `dismissible`, `starts_at`, `ends_at`, `created_at` | `severity` en `info/important`; vigencia por fechas. |
| `terms_versions` | `version`, `url`, `text_hash`, `effective_at`, `created_at` | La vigente es la de mayor `effective_at` que ya entró en vigor; las anteriores no se borran. |

**Restricciones e índices clave:**

```sql
CREATE UNIQUE INDEX uq_group_members_current_user
  ON group_members (user_id)
  WHERE status IN ('pending', 'active');

CREATE UNIQUE INDEX uq_group_members_one_admin
  ON group_members (group_id)
  WHERE role = 'admin' AND status = 'active';

CREATE UNIQUE INDEX uq_app_releases_min_supported
  ON app_releases (is_min_supported)
  WHERE is_min_supported;

CREATE INDEX idx_sessions_user_active
  ON sessions (user_id, last_used_at DESC)
  WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires ON sessions (expires_at);
CREATE INDEX idx_user_state_active_seen
  ON user_state (last_seen_at)
  WHERE status = 'active';
CREATE INDEX idx_notices_window ON notices (starts_at, ends_at);
CREATE INDEX idx_terms_versions_effective ON terms_versions (effective_at DESC);
CREATE INDEX idx_group_members_group_status ON group_members (group_id, status);
```

**Prohibiciones del esquema:**

- Sin `refresh_tokens`.
- Sin `location_history`.
- Sin `sharing_enabled`.
- Sin `email` ni `emergency_contact_*`.
- Sin tablas de multi-grupo, roles múltiples o historial de estados.

### 11.4 Matriz de autorización

| Rol / estado | Puede | No puede |
|---|---|---|
| Anónimo | Login, registro, `GET /app/version`, `GET /app/terms`, avisos públicos, `GET /health`. | Todo lo demás. |
| `pending` | Perfil propio, avatar propio, sesiones propias, términos, `GET /groups` con estado pendiente, WebSocket solo eventos de membresía. | Snapshot, ubicación, heartbeat, estados, avatares ajenos, mapa. |
| `active` | Snapshot de su grupo, ubicación, heartbeat, estado, perfil, avatar propio, avatares del grupo, salir del grupo. | Administración, datos de otros grupos. |
| `admin` | Todo lo de `active` más renombrar, invitar, ver pendientes, aprobar/rechazar, expulsar y resetear contraseñas de miembros. | Salir del grupo, eliminar su cuenta mientras sea admin, transferir rol desde la app. |
| `left` / `rejected` | Login, perfil propio, avatar propio, sesiones, términos, unirse con un código válido. | Snapshot, ubicaciones, avatares ajenos, eventos internos. |

**Regla de respuestas:** los recursos de otro grupo o de un usuario sin relación responden `404` para no revelar existencia. Solo se usan errores de estado explícitos: `403 membership_pending`, `409 admin_transfer_required`, `409 last_admin` (invariante del servidor), `426 app_update_required` y `429 rate_limited`.

### 11.5 Endpoints

| Área | Endpoints |
|---|---|
| Autenticación | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `POST /auth/logout-others`, `POST /auth/change-password`, `GET /auth/sessions`, `DELETE /auth/sessions/{id}` |
| Usuarios | `GET /users/me`, `PATCH /users/me`, `PUT /users/me/avatar`, `DELETE /users/me/avatar`, `GET /users/{userId}/avatar`, `DELETE /users/me`, `POST /users/me/terms` |
| Grupos | `GET /groups`, `POST /groups/join`, `GET /groups/{groupId}/snapshot`, `PATCH /groups/{groupId}`, `GET /groups/{groupId}/invite`, `POST /groups/{groupId}/invite/regenerate`, `GET /groups/{groupId}/pending`, `POST /groups/{groupId}/members/{userId}/approve`, `POST /groups/{groupId}/members/{userId}/reject`, `DELETE /groups/{groupId}/members/{userId}`, `DELETE /groups/{groupId}/members/me`, `POST /groups/{groupId}/members/{userId}/reset-password` |
| Ubicación | `POST /location`, `POST /heartbeat`, `POST /me/status` |
| App | `GET /app/version`, `GET /app/notices`, `GET /app/terms`, `GET /health` |

**Endpoints que no existen en v2:** `POST /auth/refresh`, `POST /groups` (crear grupo), `PATCH /groups/{id}/members/{id}` (cambiar rol), `PATCH /groups/{id}/members/me` (compartir por grupo), cualquier endpoint de historial, panel web o push.

### 11.6 Formato de errores

```json
{
  "error": {
    "code": "validation_error",
    "message": "El código de invitación no es válido o venció",
    "details": {},
    "requestId": "req_01J..."
  }
}
```

| HTTP | Códigos | Uso |
|---|---|---|
| 400 | `validation_error`, `weak_password`, `invalid_invite_code`, `invalid_status`, `invalid_image`, `too_many_points`, `app_version_required` | Datos inválidos o incompletos. |
| 401 | `unauthorized`, `invalid_credentials`, `session_expired`, `session_revoked` | Token ausente, vencido o revocado. |
| 403 | `forbidden`, `membership_pending`, `password_change_required`, `terms_required`, `reset_not_allowed` | Estado que impide la acción. |
| 404 | `not_found` | Recurso inexistente o de otro grupo. |
| 409 | `username_taken`, `already_member`, `group_full`, `admin_transfer_required`, `last_admin` | Conflicto de estado. |
| 413 | `payload_too_large` | Lote o avatar demasiado grande. |
| 415 | `unsupported_media_type` | Avatar que no es WebP. |
| 426 | `app_update_required` | Versión menor a la mínima; incluye `latest` y `minSupportedVersionCode`. |
| 429 | `rate_limited` | Incluye `Retry-After` en segundos. |
| 5xx | `server_error`, `service_unavailable` | Error interno o servicio no disponible; no revela detalles. |

- El error de código de invitación es siempre genérico: no distingue entre inexistente, vencido o de otro grupo.
- `requestId` se muestra en la pantalla de error y se puede copiar para soporte.
- Los mensajes al usuario están en español y no incluyen detalles técnicos.

### 11.7 WebSocket

**Servidor → app:**

| Evento | Cuándo | Datos |
|---|---|---|
| `hello` | Al conectar | `serverTime`, intervalo de ping. |
| `snapshot` | Después de `hello` y de cada `resync` | Grupo, miembros y estados. |
| `member_state` | Cambia ubicación, batería o estado | Estado completo del miembro. |
| `member_joined` / `member_left` | Entra o sale un miembro | Perfil básico y `userId`. |
| `member_pending` | Alguien espera aprobación | Perfil básico; solo al admin. |
| `member_approved` / `member_rejected` | Cambia la membresía | `groupId`, `userId`, estado. |
| `member_removed` | Expulsión | `groupId`, `userId`. |
| `profile_updated` | Cambia nombre, teléfono o avatar | `userId`, nombre, `phone`, `avatarVersion`. |
| `group_updated` | Cambia el nombre del grupo | `groupId`, nombre, `updatedAt`. |
| `notice` | Aviso nuevo | Aviso completo. |
| `session_revoked` | Sesión cerrada de forma remota | `sessionId`. |
| `error` | Mensaje inválido o límite | `code`, `message`. |

**App → servidor:** solo `resync` y `ping`. Nunca ubicación, perfil, estado ni avatar.

**Códigos de cierre:**

| Código | Significado | Acción de la app |
|---|---|---|
| `1000` | Cierre normal | No reconectar automáticamente. |
| `4401` | Token inválido o vencido | Limpiar token y volver a login. |
| `4403` | Sesión revocada | Limpiar token, mostrar explicación, login. |
| `4426` | Actualización obligatoria | Pantalla de actualización. |
| `4429` | Límite de mensajes | Esperar y reconectar con backoff. |
| `1012` | Reinicio del servidor | Reconectar con backoff. |

---

## 12. Actualizaciones, avisos y términos versionados

### 12.1 Actualizaciones

`GET /app/version` es público y devuelve:

```json
{
  "latest": {
    "versionCode": 7,
    "versionName": "1.3.0",
    "apkUrl": "https://orbit.moondev.online/download/moonorbit-1.3.0.apk",
    "apkSha256": "…",
    "apkSizeBytes": 18432123,
    "releaseNotes": "Mejoras de batería y mapa.",
    "publishedAt": "2026-11-01T10:00:00Z"
  },
  "minSupportedVersionCode": 5
}
```

- La app envía `X-App-Version-Code` en cada petición.
- Si ninguna fila de `app_releases` tiene `is_min_supported = true`, el servidor usa `1` como mínima soportada; no usa la última versión.
- Si la versión instalada es menor que `minSupportedVersionCode`, el servidor responde `426` en los endpoints autenticados y la app muestra una pantalla bloqueante con “Descargar” y “Reintentar”.
- Si hay una versión nueva pero sigue siendo soportada, se muestra un aviso no bloqueante.
- La descarga se abre en el navegador; la app no instala APKs ni pide `REQUEST_INSTALL_PACKAGES`.
- El APK se sirve desde Nginx en `/download/`; se publica SHA-256 y tamaño para verificación manual.
- Se conserva la última versión y la mínima soportada; la limpieza mensual elimina APKs antiguos que ya no se necesitan.
- La keystore de firma se respalda offline; si se pierde, no se pueden instalar actualizaciones sobre la app existente.

### 12.2 Avisos

`GET /app/notices` devuelve los avisos vigentes:

```json
{
  "notices": [
    {
      "id": "uuid",
      "title": "Mantenimiento",
      "message": "El servidor se reiniciará el sábado a las 22:00.",
      "severity": "important",
      "dismissible": true,
      "startsAt": "2026-11-01T00:00:00Z",
      "endsAt": "2026-11-03T00:00:00Z"
    }
  ]
}
```

- `severity`: `info` o `important`.
- `dismissible`: si el usuario puede ocultarlo.
- La app guarda en DataStore los IDs descartados y no los vuelve a mostrar mientras no cambie su contenido o vigencia.
- Un aviso nuevo se emite por WebSocket (`notice`) con la app abierta.
- No hay push ni FCM; si la app está cerrada, el aviso se ve al abrirla.
- Los avisos vencidos se limpian del servidor en el job diario.

### 12.3 Términos versionados

- `GET /app/terms` devuelve la versión vigente, fecha de entrada en vigor, URL del texto y hash del contenido. La vigente es la de mayor `effective_at` que ya entró en vigor; no existe una columna `is_current`.
- `POST /users/me/terms` recibe `{ version }` y registra `accepted_terms_version` y `accepted_terms_at`.
- Si la versión aceptada por el usuario no es la vigente, el servidor responde `403 terms_required` y la app muestra la pantalla de términos.
- Mientras hay términos pendientes, solo se permite `GET /users/me`, `GET /app/terms`, `POST /users/me/terms`, `POST /auth/logout`, `GET /app/notices`, `GET /app/version` y `GET /health`.
- Las versiones anteriores no se borran; cada usuario conserva la versión aceptada y la fecha en `users.accepted_terms_version` y `users.accepted_terms_at`.
- El texto legal debe revisarse con la Ley 29733 y su reglamento antes de publicar una versión.

### 12.4 Publicación por SQL/CLI

No hay panel web ni endpoints de administración. El propietario del servidor usa scripts o `psql`:

| Acción | Herramienta | Efecto |
|---|---|---|
| Crear grupo y admin | `node scripts/create-group.js --name "Familia Luna" --admin mama` | Crea grupo, usuario si falta, membresía `admin/active` y código de 48 h. |
| Transferir admin | `node scripts/transfer-admin.js --group <id> --to <userId>` | Cambia el admin anterior a `member` y el nuevo a `admin` en una transacción. |
| Reset del admin | `node scripts/reset-admin-password.js --username mama` | Genera contraseña temporal, revoca sesiones y activa `must_change_password`. |
| Publicar versión | `node scripts/publish-release.js --code 7 --name 1.3.0 --apk … --apk-sha256 … --min` | Inserta/actualiza `app_releases`; `--min` marca `is_min_supported`. |
| Publicar aviso | `node scripts/publish-notice.js --title "…" --message "…" --severity important --ends 2026-11-03` | Inserta en `notices`. |
| Publicar términos | `node scripts/publish-terms.js --version 2026-12-01 --file terms/2026-12-01.md` | Inserta la versión con `effective_at`; las anteriores se conservan. |
| Eliminar grupo | `node scripts/delete-group.js --group <id>` | Borra el grupo y sus membresías; no borra cuentas. |

Los scripts leen `DATABASE_URL` del entorno, validan los datos y no imprimen tokens ni contraseñas. `publish-release.js` con `--min` desmarca la mínima anterior y marca la nueva en la misma transacción; `publish-terms.js` inserta una versión nueva con su `effective_at` y no modifica las anteriores. Para cambios menores se puede usar `psql` con una transacción y una revisión previa; nunca se edita la base directamente sin respaldo.

---

## 13. Seguridad, privacidad y legal

### 13.1 Seguridad técnica

| Control | Implementación |
|---|---|
| Transporte | HTTPS y WSS con TLS 1.2+; HSTS; sin tráfico en claro. |
| Contraseñas | `argon2id`, nunca hash simple ni contraseña reversible. |
| Token | 256 bits aleatorios, opaco, guardado como `SHA-256` en servidor y cifrado con Keystore en el dispositivo. |
| Sesiones | 90 días, máximo 5, revocación inmediata, `last_used_at`, cierre de WebSocket con `4403` (revocación) o `4401` (vencimiento). |
| Autorización | Cada consulta verifica usuario, sesión y membresía `active` en el grupo; sin confiar en IDs del cliente. |
| Validación | Esquemas `zod`, límites de tamaño, rangos de latitud/longitud, tipo real de imagen y códigos de invitación. |
| SQL | Consultas parametrizadas; sin concatenación de entrada. |
| Rate limiting | Nginx + aplicación; `429` con `Retry-After`. |
| CSRF | No aplica: no hay cookies de sesión; se usa bearer token. |
| Logs | Sin tokens, contraseñas, coordenadas, cuerpos de avatar ni datos personales innecesarios. |
| Respaldos | Cifrados, fuera del VPS, con restauración probada. |
| APK | Firmado, SHA-256 publicado, keystore offline; sin instalación interna. |
| Dependencias | Actualizaciones de seguridad revisadas antes de cada despliegue. |
| Google Maps | API key restringida a paquete + SHA-1, con presupuesto y alertas; sin APIs extra. |

**Reglas adicionales:**

- El servidor no confía en `userId`, `groupId` ni `role` enviados por el cliente; los obtiene de la sesión y la base.
- Los avatares se validan por contenido real, no solo por `Content-Type`.
- Los errores no revelan si un usuario, grupo o avatar existe.
- Los scripts administrativos requieren acceso al VPS y a `DATABASE_URL`; no se exponen por HTTP.
- La consola de PM2 y los logs no deben imprimir tokens ni contraseñas temporales.

### 13.2 Privacidad y datos

**Datos que se recopilan:**

| Dato | Finalidad | Visibilidad | Retención |
|---|---|---|---|
| Nombre de usuario | Identificar la cuenta | Propia | Mientras exista la cuenta |
| Nombre para mostrar | Mostrar en el grupo | Grupo activo | Mientras exista la cuenta |
| Teléfono (opcional) | Botón “Llamar” | Grupo activo | Mientras exista la cuenta |
| Avatar WebP | Identificación visual | Grupo activo | Hasta reemplazo/eliminación; huérfanos 24 h |
| Última ubicación, precisión y hora | Mostrar el mapa | Grupo activo | Solo actual; se sobrescribe |
| Batería y carga | Contexto de disponibilidad | Grupo activo | Solo actual |
| Estado y `last_seen_at` | Explicar pausa/sin señal | Grupo activo | Solo actual |
| Nombre del dispositivo y versión | Gestionar sesiones | Propia | Hasta revocación/vencimiento |
| Términos aceptados | Consentimiento | Propia | Mientras exista la cuenta |
| Avisos descartados | No repetir avisos | Local en el dispositivo | Hasta borrar datos locales |

**No se recopila:** email, contacto de emergencia, historial de rutas, actividad física, contactos del teléfono, ubicación en segundo plano histórica, publicidad ni identificadores de advertising.

**Derechos y control:**

- El usuario puede ver y editar su perfil, cambiar su contraseña, revocar sesiones y eliminar su cuenta.
- La eliminación de cuenta borra perfil, avatar, estado, sesiones y membresía; no se puede deshacer.
- El administrador no puede eliminar su cuenta mientras sea admin; debe pedir la transferencia al propietario del servidor.
- Los derechos de acceso, rectificación, cancelación y oposición (Ley 29733) se ejercen desde la app o por el canal de contacto indicado en la política de privacidad.
- Las solicitudes legales se atienden dentro del plazo que exija la normativa vigente.

**Menores:**

- Si hay menores en el grupo, el administrador debe ser su padre, madre o tutor legal.
- El tutor acepta los términos y supervisa el uso; la cuenta del menor no se crea sin su consentimiento.
- No hay modo oculto: el menor ve que su ubicación se comparte y puede pausarla.
- Este punto debe validarse con asesoría legal antes de publicar los términos. ⏳

**Transparencia:**

- La notificación del servicio de ubicación es fija y visible mientras se comparte.
- Al unirse un miembro, el grupo recibe el evento correspondiente.
- La app explica qué se comparte, con quién y cómo pausarlo.
- No se usa la app para vigilar empleados, parejas ni personas sin consentimiento.

### 13.3 Legal

- **Normativa:** Ley N.º 29733, Protección de Datos Personales, y su reglamento; verificar la versión vigente y las obligaciones aplicables.
- **Texto legal:** términos y política de privacidad con finalidad, base legal, datos, destinatarios, transferencias, retención, derechos y canal de contacto.
- **Consentimiento:** aceptación explícita, versionada y registrada con fecha/hora; no se puede usar la app sin aceptar.
- **Retención:** ubicación solo actual; sesiones y avisos depurados; logs con retención exacta pendiente; respaldos 7/4/12.
- **Seguridad:** medidas técnicas y organizativas razonables; acceso al VPS limitado al propietario.
- **Google Maps:** el uso del SDK está sujeto a los términos de Google; la API key se restringe y se monitorea el consumo.
- **Distribución:** APK privado; no hay ficha pública ni promoción. El propietario del servidor es el responsable del tratamiento.
- **Texto pendiente:** la redacción y revisión legal es un bloqueo real para publicar la v1. ⏳

---

## 14. Protección de recursos del servidor

### 14.1 Límites

| Recurso | Límite | Dónde se aplica |
|---|---|---|
| Miembros por grupo | 12 `pending` + `active` | Backend, transacción con bloqueo |
| Sesiones por usuario | 5 activas | Backend; revoca la más antigua |
| Avatar | 1 MB; 5 subidas/hora | Nginx + backend + rate limit |
| Lote de ubicación | 50 puntos o 64 KB; 1 petición/5 s | Nginx + backend |
| Heartbeat | 1 por minuto | Backend |
| Login | 10 intentos/15 min por IP y usuario | Nginx + backend |
| Registro/unión | 5 intentos/hora por IP | Nginx + backend |
| WebSocket | 4 KB por mensaje; 5 mensajes/s; ping 30 s; inactividad 90 s | Servidor WS |
| Snapshot de respaldo | 1 petición/5 s por usuario; polling cada 30 s | Backend + app |
| Cuerpo general | 16 KB por defecto | Nginx |
| Pool PostgreSQL | 10 conexiones | Node |
| APK | < 30 MB | Build + Nginx |

### 14.2 Nginx

- TLS 1.2/1.3 con Certbot; HTTP/2; HSTS.
- gzip/brotli para JSON, CSS y JS; no comprimir imágenes ya comprimidas.
- `client_max_body_size` específico: 64k en `/api/v1/location`, 1m en `/api/v1/users/me/avatar`, 16k en el resto.
- Rate limiting por IP y por ruta; `limit_req_status 429` y `Retry-After`.
- *Upgrade* de WebSocket en `/ws` con timeouts mayores a 90 s.
- `/download/` sirve APKs estáticos con `Content-Disposition: attachment`, sin listado de directorios.
- Location interna `/__avatars/` con `alias` a la carpeta real; solo accesible vía `X-Accel-Redirect`.
- `deny` a dotfiles y a la carpeta de respaldos; la base de datos no expuesta a internet.
- Logs de acceso sin cuerpos ni tokens; el token viaja en cabecera, no en la URL.

### 14.3 Node, PM2 y base de datos

- Una sola instancia Fastify con PM2 `fork`; `instances: 1`, `exec_mode: "fork"`, `autorestart: true`.
- `max_memory_restart: "300M"`; reinicio si el proceso crece de forma anormal.
- `pm2 startup` + `pm2 save` para reanudar tras reinicio del VPS.
- Sin clúster, sin Redis, sin workers externos; los jobs corren en el mismo proceso con advisory lock.
- Pool PostgreSQL máximo 10; timeouts de conexión y consulta; cierre ordenado.
- Migraciones antes de arrancar; si fallan, el proceso no sirve tráfico.

### 14.4 Snapshot, polling y WebSocket

- Snapshot comprimido con gzip/brotli, `Cache-Control: private, no-cache` y `ETag` débil calculado sobre el contenido.
- `If-None-Match` se compara antes de serializar; si coincide, `304` sin cuerpo.
- El polling solo se activa con la app en primer plano y WebSocket caído; cada 30 s.
- El backend limita el polling a 1 petición/5 s por usuario; si se excede, `429` con `Retry-After`.
- El WebSocket acepta una conexión por sesión; si se abre otra, cierra la anterior con `1000`.
- El servidor hace ping cada 30 s; si no hay actividad en 90 s, cierra.
- Mensajes de más de 4 KB o más de 5/s se responden con `error` y, si persiste, `4429`.
- El WebSocket nunca acepta ubicación; si llega un mensaje de escritura, responde `error` y no lo procesa.

### 14.5 Avatares con `X-Accel-Redirect`

1. Node valida sesión, grupo activo y `If-None-Match`.
2. Si el ETag coincide, responde `304` sin tocar el archivo.
3. Si no, responde `200` con `X-Accel-Redirect: /__avatars/{userId}/{version}.webp`.
4. Nginx sirve el WebP desde la carpeta interna con `Content-Type: image/webp`, `Cache-Control` privado y ETag.
5. La carpeta no tiene listado ni acceso directo desde fuera.

### 14.6 Trabajos periódicos

| Trabajo | Frecuencia | Acción |
|---|---|---|
| `no_signal` | Cada minuto | `active` con `last_seen_at < now() - 25 min` pasa a `no_signal` y emite `member_state`. |
| Limpieza de sesiones | Diaria | Elimina vencidas y revocadas con más de 30 días. 💡 |
| Limpieza de avisos | Diaria | Elimina avisos con `ends_at` vencido hace más de 30 días. 💡 |
| Limpieza de membresías | Diaria | Elimina filas `rejected`/`left` con más de 90 días. 💡 |
| Avatares huérfanos | Diaria | Borra archivos sin referencia con más de 24 h. ✅ |
| APKs antiguos | Mensual | Conserva la última versión y la mínima soportada. 💡 |

Los jobs usan `pg_try_advisory_lock` para no solaparse y registran solo conteos. Si un job falla, se registra el error y se reintenta en la siguiente ejecución.

### 14.7 Respaldos

- `pg_dump -Fc` diario, cifrado con `age` o GPG antes de salir del VPS.
- Destino fuera del VPS (otro proveedor o almacenamiento de objetos).
- Retención: 7 diarios, 4 semanales y 12 mensuales.
- Restauración de prueba cada trimestre en una base temporal; registrar fecha, duración y resultado.
- Keystore de firma en dos copias offline, con contraseña en gestor de secretos.
- Avatares: incluir en el respaldo semanal o aceptar que se pierden; se recomienda incluirlos. 💡
- Monitorear el éxito del respaldo; un fallo dos días seguidos es alerta.

### 14.8 Monitoreo

- `GET /health` cada 60 s con Uptime Kuma; responde `503` si la base no está disponible.
- Espacio en disco > 80 %: alerta.
- Certificado TLS a menos de 14 días: alerta.
- Respaldo fallido o no ejecutado: alerta.
- Reinicios de PM2 en las últimas 24 h: alerta si supera 2.
- Tasa de `5xx` y latencia del snapshot: revisar semanalmente.
- Las alertas van al propietario por un canal externo (correo o mensajería); no se usan notificaciones push de la app.

### 14.9 Logs

- Formato JSON con `timestamp`, `level`, `requestId`, `method`, `path`, `status`, `durationMs`, `userId` (si aplica).
- Redactar `Authorization`, `Cookie`, `Set-Cookie`, `password`, `token`, coordenadas y cuerpos de avatar.
- No registrar la ubicación exacta ni la IP completa si no es necesaria; si se registra IP, se trunca.
- Retención propuesta: 30 días; confirmar con legal/operación. ⏳
- Rotación diaria y compresión; no llenar el disco.
- Los errores 5xx incluyen `requestId` para correlación, no stack traces al cliente.

---

## 15. Fases de construcción

| Fase | Entregable | Criterio de salida |
|---|---|---|
| **0. Alinear documentación** | Reescribir `moonorbit-protocolo.md` y `moonorbit-schema.sql` a v2; eliminar JWT, refresh, multi-grupo, `sharing_enabled`, historial, ubicación por WebSocket, cambios de rol y contacto de emergencia. | `grep` no encuentra contradicciones; el esquema y el protocolo reflejan este documento. |
| **1. Backend base** | Migraciones PostgreSQL, Fastify, auth con token opaco, sesiones, grupos, invitación, admin único, términos, scripts de creación/reset, Nginx y PM2 en staging. | Login, registro con código, aprobación, revocación y límites funcionan; no existe `/auth/refresh`. |
| **2. App esqueleto** | Proyecto Android, tema claro/oscuro, navegación, bienvenida, términos, login, registro, pendiente, rechazado, onboarding de permisos, DataStore y token cifrado. | El onboarding respeta el orden; Android 11/12/13/14 se comportan según la matriz; no hay color dinámico. |
| **3. Tracking** | Foreground service tipo `location`, FusedLocation balanceado, cola Room, lotes REST, heartbeat, `BootReceiver`, `ProviderChangedReceiver`, notificación fija. | La ubicación llega en segundo plano; la cola sobrevive sin internet; el heartbeat evita `no_signal`; el servicio se reanuda tras reinicio cuando el sistema lo permite. |
| **4. Mapa y tiempo real** | Snapshot, WebSocket de recepción, polling con ETag, marcadores, panel de miembros, detalle, estados, precisión y offline. | Con WebSocket conectado no hay polling; con WebSocket caído el polling es cada 30 s y respeta 304; la precedencia de estados es correcta. |
| **5. Perfil y avatares** | Perfil, Photo Picker/cámara, recorte, compresión WebP, subida binaria, `sharp`, ETag, `X-Accel-Redirect`, Coil y limpieza de huérfanos. | 1 MB, 512×512, calidad 80, sin EXIF, 304 y limpieza diaria verificados. |
| **6. Administración y sesiones** | Pantallas de administración, invitación, pendientes, reset, expulsión, sesiones, eliminación de cuenta y errores. | Un solo admin; no puede salir; el límite de 12 se respeta; revocar sesión cierra el WebSocket. |
| **7. Actualizaciones, avisos y términos** | `/app/version`, `/app/notices`, `/app/terms`, pantalla de actualización, avisos, aceptación versionada y scripts de publicación. | 426 bloquea; la descarga abre el navegador; los avisos se descartan; los términos nuevos bloquean hasta aceptar. |
| **8. Endurecimiento y piloto** | Accesibilidad, rendimiento, batería real, respaldos/restauración, monitoreo, keystore, APK SHA-256, texto legal y prueba con 6 familiares. | Arranque < 2 s, mapa < 3 s, APK < 30 MB, AA, restauración probada y batería medida en al menos tres teléfonos. |

**Orden recomendado:** no empezar por el mapa. Primero backend y autenticación, luego onboarding, tracking y recién después tiempo real. El mapa sin datos correctos solo produce una demo bonita y frágil.

**Futuro, no v1:** FCM para avisos con la app cerrada, historial de rutas, multi-grupo, múltiples administradores, panel web, iOS, detección de salto imposible. Cualquiera de estos cambios requiere una nueva versión de la fuente de verdad.

---

## 16. Pendientes reales

- [ ] Reescribir `moonorbit-protocolo.md` y `moonorbit-schema.sql` para que no contradigan v2. ⏳
- [ ] Validar paleta, tipografía y JSON del mapa con pruebas visuales en dispositivos reales. ⏳
- [ ] Redactar y revisar términos, privacidad y tratamiento de menores conforme a la Ley 29733. ⏳
- [ ] Definir la retención exacta de logs y la política de respaldo de avatares. ⏳
- [ ] Confirmar API 26 como mínimo y probar en los teléfonos reales de la familia. ⏳
- [ ] Medir batería real y ajustar intervalo, distancia mínima y heartbeat. ⏳
- [ ] Decidir si se añade FCM en una versión futura. ⏳
- [ ] Confirmar el nombre de paquete `online.moondev.orbit`. ⏳
- [ ] Elegir la librería de recorte de imagen compatible con Compose. ⏳
- [ ] Definir la página de descarga del APK y cómo se muestra el SHA-256. ⏳
- [ ] Documentar el runbook de transferencia de admin y eliminación de grupo. ⏳
- [ ] Validar la política de contraseñas (mínimo 10, máximo 128) con los miembros. ⏳
- [ ] Revisar precios, cuotas y términos vigentes de Google Maps. ⏳
- [ ] Ajustar los límites de rate limiting después de una prueba de carga real. ⏳

---

## Anexo A — IDs retirados

| ID | Motivo |
|---|---|
| `RF-47` | Permitía varias familias; v2 tiene un solo grupo por usuario. |
| `RF-48` | Interruptor “compartir con esta familia”; v2 usa una pausa global. |
| `RF-49` | Reset de contraseña con varias familias; ya no aplica. |
| `RNF-19` | `REGISTRATION_MODE`; v2 es solo por invitación y no se abre el registro. |

No se reutilizan estos IDs. `RF-36` se mantiene, pero el “contacto” se limita a teléfono opcional; no hay correo ni contacto de emergencia. `RF-42` se mantiene y ahora el tema claro está decidido, no pendiente.

## Anexo B — Checklist de consistencia

- [x] Sin JWT, sin refresh token y sin recuperación por correo.
- [x] Token opaco de 256 bits, hash en servidor y cifrado en el dispositivo.
- [x] Un grupo por usuario y un solo administrador por grupo.
- [x] Sin `sharing_enabled`, sin `location_history` y sin multi-grupo.
- [x] Escrituras por REST; WebSocket solo recepción.
- [x] Polling de respaldo cada 30 s con ETag, solo en primer plano.
- [x] Sin push, sin panel web, sin instalación interna del APK.
- [x] Sin Activity Recognition, geofence ni detección de salto imposible.
- [x] Avatares WebP con límite, re-codificación, ETag, privacidad y limpieza de huérfanos.
- [x] Tema claro/oscuro/sistema con tokens exactos y accesibilidad AA.
- [x] Términos versionados, avisos y actualizaciones publicados por SQL/CLI.
- [x] Límites, respaldos, monitoreo y jobs definidos.
- [x] Estados `active`, `paused`, `location_disabled`, `no_signal` con precedencia y mensajes claros.

