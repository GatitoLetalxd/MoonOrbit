# MoonOrbit — Decisiones v2 (fuente de verdad)

> Fecha: 2026-11-01. Este documento manda sobre `ubicacion-familiar-requerimientos.md`, `moonorbit-protocolo.md` y `moonorbit-schema.sql`.
> Leyenda: ✅ decidido · 💡 propuesto · ⏳ pendiente

## 1. Producto

- ✅ Android nativo, Kotlin, Jetpack Compose + Material 3.
- ✅ Uso privado familiar; distribución por APK; 6 miembros iniciales, máximo 12 miembros activos por grupo.
- ✅ **Un grupo por usuario**. No hay multi-familia ni selector de grupos.
- ✅ **Un solo administrador por grupo**. No hay cambios de rol.
- ✅ Registro solo con código de invitación y aprobación del administrador.
- ✅ Sin historial de rutas: solo la última ubicación conocida.
- ✅ Sin notificaciones push. Los avisos llegan al abrir la app o por WebSocket con la app abierta.
- ✅ Avatares en WebP, con limpieza de fotos huérfanas.
- ✅ Tema claro y oscuro premium, con opción “seguir al sistema”.
- ✅ Sin panel web de administración: avisos y versiones se publican por SQL/CLI.
- ✅ El grupo lo crea el propietario del servidor con un script. No hay endpoint para crear grupos.

## 2. Autenticación y sesiones

- ✅ Token de sesión **opaco** de 256 bits, codificado en Base64URL sin relleno (43 caracteres). **No hay JWT ni refresh token.**
- ✅ El servidor guarda solo `SHA-256(token)` en `sessions.token_hash`.
- ✅ `Authorization: Bearer <sessionToken>` en REST y en el handshake WebSocket.
- ✅ Sesión válida durante 90 días; `last_used_at` se actualiza al usarla, con escritura agrupada como máximo cada 5 min por sesión.
- ✅ Revocación inmediata: `revoked_at`; el WebSocket de esa sesión se cierra con `4403`. Al vencer los 90 días, el WebSocket se cierra con `4401`.
- ✅ `GET /auth/sessions`, `DELETE /auth/sessions/{id}`, `POST /auth/logout`, `POST /auth/logout-others`.
- ✅ Contraseñas con `argon2id`.
- ✅ Validación de cuenta: `username` de 3–24 caracteres (minúsculas, números y `_`; único sin distinguir mayúsculas e inmutable); `displayName` de 2–40 caracteres; contraseña de 10–128 caracteres, distinta del `username` y del `displayName`.
- ✅ Perfil: `displayName` editable; `phone` opcional, editable con `PATCH /users/me` (`null` lo borra), visible en `GET /users/me`, snapshot y `profile_updated`; no se valida como número internacional en v1.
- ✅ `must_change_password` se aplica en el servidor: solo permite `GET /users/me`, `POST /auth/change-password` y `POST /auth/logout`.
- ✅ Reset de contraseña: solo el administrador puede resetear a un miembro no administrador. Genera contraseña temporal de un uso, revoca todas las sesiones y obliga a cambiarla.
- ✅ Si el administrador olvida su contraseña, el propietario del servidor la resetea con un script.
- ✅ Máximo 5 sesiones activas por usuario; al superar el límite se revoca la más antigua.
- ✅ Rate limiting en login, registro, avatares y WebSocket.

## 3. Grupo y administrador

- ✅ Tabla `group_members` con `role` (`admin`, `member`) y `status` (`pending`, `active`, `rejected`, `left`).
- ✅ Índice único parcial: como máximo una membresía `pending` o `active` por usuario.
- ✅ Índice único parcial: como máximo un `admin` por grupo; el admin debe estar `active`.
- ✅ El administrador puede: renombrar el grupo, regenerar el código, ver pendientes, aprobar/rechazar, expulsar miembros y resetear contraseñas de miembros.
- ✅ El administrador **no puede** salir del grupo ni eliminar su cuenta mientras sea admin. Recibe `409 admin_transfer_required`. La transferencia o eliminación del grupo la hace el propietario del servidor con un script.
- ✅ Código de invitación: 8 caracteres sin `0/O/1/I`, 48 h de vigencia, reutilizable hasta que se regenere o venza. Regenerar invalida el anterior.
- ✅ Rechazado: queda `rejected`; puede unirse a otro grupo o volver a postular al mismo (la fila se actualiza a `pending`).
- ✅ Salida voluntaria: queda `left`; puede unirse a otro grupo después.
- ✅ El nombre del grupo tiene entre 2 y 40 caracteres.
- ✅ Límite de 12 miembros activos por grupo, validado en el backend. Las solicitudes `pending` no ocupan plaza, pero no se aprueban si ya hay 12 activos.

## 4. Ubicación, latido y estado

- ✅ Las escrituras de ubicación van **siempre por REST**. El WebSocket es solo para recibir.
- ✅ `POST /location`: lote de 1 a 50 puntos, máximo 64 KB. El servidor usa el punto con `recordedAt` más reciente.
- ✅ Se ignoran puntos más viejos que el ya guardado o con más de 24 h de antigüedad.
- ✅ Un `recordedAt` más de 5 minutos en el futuro se ajusta a la hora del servidor.
- ✅ `lowAccuracy` si `accuracyM > 100`; no se descartan ubicaciones imprecisas.
- ✅ Sin detección de “salto imposible” en v1.
- ✅ `POST /heartbeat` cada ~10 min si no se envió ubicación: actualiza batería y `last_seen_at`.
- ✅ Si llega un heartbeat y el estado era `no_signal`, vuelve a `active`.
- ✅ `paused` y `location_disabled` no se reactivan con heartbeats ni ubicaciones en cola: solo con `POST /me/status` y `status = active`. Mientras duren, los puntos de ubicación se ignoran.
- ✅ `POST /me/status`: `active`, `paused`, `location_disabled`. `no_signal` lo calcula solo el servidor.
- ✅ Trabajo `no_signal`: cada minuto, `active` con `last_seen_at < now() - 25 min` pasa a `no_signal`.
- ✅ Precedencia visual: `paused` > `location_disabled` > `no_signal` > `active`.
- ✅ Estado inicial: `no_signal` con `last_seen_at = null`; la app muestra “Sin datos todavía”.
- ✅ La app calcula el desfase de reloj con `serverTime` y lo usa para “hace X min”.
- ✅ Batería baja a partir de 15 %.
- ✅ Sin Activity Recognition ni geofence en v1. El ahorro se basa en `BALANCED_POWER_ACCURACY`, distancia mínima y lotes.

## 5. Avatares WebP

- ✅ `PUT /users/me/avatar` con `Content-Type: image/webp` y cuerpo binario, máximo 1 MB.
- ✅ Validación en servidor: tipo real, dimensiones y tamaño; re-codificación con `sharp` a 512×512, calidad 80, sin metadatos.
- ✅ Guardado fuera de la raíz pública en `avatars/{userId}/{version}.webp`.
- ✅ `avatar_version` se incrementa al reemplazar o eliminar la foto. `avatar_path` puede ser `null`.
- ✅ `GET /users/{userId}/avatar` exige sesión; el propio avatar se permite incluso en `pending`, `left` o `rejected`, y el de otro miembro exige grupo activo en común. Devuelve `ETag` y admite `If-None-Match` (304).
- ✅ Se puede servir con `X-Accel-Redirect` de Nginx para no cargar el proceso Node.
- ✅ Al reemplazar: se escribe el archivo nuevo, se actualiza la BD y después se borra el anterior. Si algo falla, el trabajo de limpieza elimina el huérfano.
- ✅ Limpieza diaria de fotos huérfanas: archivos sin referencia en `users.avatar_path` y con más de 24 h de antigüedad.
- ✅ Límite de 5 subidas por hora por usuario.
- ✅ Android: Photo Picker o cámara, recorte cuadrado, vista previa y compresión a WebP antes de subir. Sin foto: iniciales con color determinista.
- ✅ Coil con cabecera de autorización y caché por `avatarVersion`; limpiar caché al cerrar sesión.

## 6. Tema y experiencia de usuario

- ✅ Material 3, español, tema claro y oscuro premium. Modo: sistema, claro u oscuro, persistido en DataStore.
- ✅ Sin color dinámico de Android: la marca manda.
- ✅ Paleta base:
  - Oscuro: fondo `#060913`, superficie `#0D1524`, superficie variante `#16233A`, primario `#22D3EE`, secundario `#67E8F9`, terciario `#8B5CF6`, texto `#F8FAFC`, texto secundario `#94A3B8`, borde `#334155`, éxito `#34D399`, aviso `#FBBF24`, error `#FB7185`.
  - Claro: fondo `#F5F8FF`, superficie `#FFFFFF`, superficie variante `#E8EEF9`, primario `#0891B2`, secundario `#0E7490`, terciario `#7C3AED`, texto `#0B1220`, texto secundario `#475569`, borde `#CBD5E1`, éxito `#059669`, aviso `#D97706`, error `#DC2626`.
- ✅ Gradientes suaves y estáticos; sin desenfoques pesados. Esquinas de 20–28 dp, elevación sutil.
- ✅ Tipografía Manrope o Plus Jakarta Sans con fallback del sistema; escala tipográfica Material 3.
- ✅ Mapa oscuro y claro con JSON acorde a la paleta. Marcadores con avatar, anillo de estado y batería; círculo de precisión.
- ✅ Animaciones de 150–250 ms, pulso suave del marcador y transiciones del panel inferior. Respetar “reducir movimiento”.
- ✅ Accesibilidad: contraste AA, objetivos de 48 dp, escala de fuente hasta 200 %, descripciones de contenido y lista de miembros como alternativa al mapa.
- ✅ UX: cache-first, skeletons, banner offline, errores claros con reintento, confirmación en acciones destructivas, háptica ligera y textos grandes para familiares mayores.
- ✅ Pantallas: bienvenida, términos, login, registro, pendiente, rechazado, permisos, mapa, detalle de miembro, perfil, ajustes, administración, invitación, sesiones, avisos, actualización y estados vacíos/error/offline.
- ✅ Onboarding: bienvenida → términos → registro/login → permisos → pendiente (si aplica) → mapa. Si ya está activo: login → permisos faltantes → mapa.
- ✅ Términos versionados en servidor: `GET /app/terms`, `POST /users/me/terms`; si la versión aceptada no es la vigente, bloquear funciones hasta aceptar.
- ✅ Actualización: `GET /app/version`; si la versión instalada es menor a la mínima, `426` con datos de descarga. Descarga en navegador, no instalación interna.
- ✅ Avisos: `GET /app/notices` y evento `notice`; publicación por SQL/CLI; descartados en DataStore.
- ✅ Rendimiento objetivo: arranque en frío < 2 s, primer mapa < 3 s, 60 fps, APK < 30 MB.
- ✅ Sin notificaciones push, sin panel web, sin historial, sin multi-grupo, sin múltiples admins, sin Activity Recognition/geofence y sin instalación interna del APK en v1.

## 7. Protección de recursos del servidor

- ✅ Una sola instancia Node.js/Fastify con PM2 en modo fork. Sin clúster y sin Redis.
- ✅ Nginx delante: TLS, HTTP/2, gzip/brotli, límites de cuerpo, rate limiting, APK estático y `X-Accel-Redirect` para avatares.
- ✅ WebSocket solo recepción; una conexión por sesión; ping cada 30 s; inactividad 90 s; mensajes de máximo 4 KB y 5 por segundo.
- ✅ Si el WebSocket está conectado, la app **no hace polling**. Si se cae, polling de snapshot cada 30 s solo con la app en primer plano, con `ETag`/`If-None-Match`.
- ✅ Snapshot con compresión y `Cache-Control: private, no-cache`. Avatares con caché larga y versión.
- ✅ Sin historial de ubicaciones; `user_state` tiene una fila por usuario.
- ✅ Índices para sesiones, estado, avisos y membresías. Pool de conexiones máximo 10.
- ✅ Límites:
  - 12 miembros activos por grupo; las solicitudes `pending` no ocupan plaza.
  - 5 sesiones por usuario.
  - 1 MB por avatar; 5 subidas/hora.
  - 50 puntos o 64 KB por lote de ubicación; 1 petición cada 5 s por usuario.
  - Heartbeat 1 por minuto por usuario.
  - Login 10 intentos/15 min por IP y usuario.
  - Registro/unión 5 intentos/hora por IP.
  - WebSocket 5 mensajes/s por conexión.
  - Snapshot de respaldo 1 petición/5 s por usuario.
- ✅ Trabajos: `no_signal` cada minuto; limpieza diaria de sesiones vencidas/revocadas, avisos expirados, membresías rechazadas/salidas antiguas y avatares huérfanos; limpieza mensual de APKs antiguos.
- ✅ Respaldos: `pg_dump` diario cifrado fuera del VPS, retención 7 diarios/4 semanales/12 mensuales, restauración probada cada trimestre. Keystore de firma respaldada offline.
- ✅ Monitoreo: `GET /health`, Uptime Kuma, espacio en disco, certificado TLS, éxito de respaldos y reinicios de PM2.
- ✅ Seguridad: HTTPS, argon2id, validación de entrada, autorización por grupo, logs sin tokens/contraseñas/ubicaciones, `allowBackup=false` en Android, token cifrado en el dispositivo y APK con SHA-256 publicado.

## 8. Protocolo

- ✅ Base REST: `https://orbit.moondev.online/api/v1`.
- ✅ WebSocket: `wss://orbit.moondev.online/ws`.
- ✅ Formato JSON UTF-8, fechas ISO 8601 UTC, UUID.
- ✅ Error: `{ "error": { "code", "message", "details?", "requestId" } }`.
- ✅ Endpoints:
  - `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `POST /auth/logout-others`, `POST /auth/change-password`, `GET /auth/sessions`, `DELETE /auth/sessions/{id}`.
  - `GET /users/me`, `PATCH /users/me`, `PUT /users/me/avatar`, `DELETE /users/me/avatar`, `GET /users/{userId}/avatar`, `DELETE /users/me`, `POST /users/me/terms`.
  - `GET /groups`, `POST /groups/join`, `GET /groups/{groupId}/snapshot`, `PATCH /groups/{groupId}`, `GET /groups/{groupId}/invite`, `POST /groups/{groupId}/invite/regenerate`, `GET /groups/{groupId}/pending`, `POST /groups/{groupId}/members/{userId}/approve`, `POST /groups/{groupId}/members/{userId}/reject`, `DELETE /groups/{groupId}/members/{userId}`, `DELETE /groups/{groupId}/members/me`, `POST /groups/{groupId}/members/{userId}/reset-password`.
  - `POST /location`, `POST /heartbeat`, `POST /me/status`.
  - `GET /app/version`, `GET /app/notices`, `GET /app/terms`, `GET /health`.
- ✅ WebSocket servidor → app: `hello`, `snapshot`, `member_state`, `member_joined`, `member_left`, `member_pending`, `member_approved`, `member_rejected`, `member_removed`, `profile_updated`, `group_updated`, `notice`, `session_revoked`, `error`.
- ✅ WebSocket app → servidor: solo `resync` y `ping`. Nunca ubicación.
- ✅ Códigos de cierre: `1000`, `4401` token inválido/vencido, `4403` sesión revocada, `4426` actualización obligatoria, `4429` límite excedido, `1012` reinicio del servidor.
- ✅ Matriz de autorización:
  - Anónimo: login, registro, versión, términos, avisos públicos, health.
  - `pending`: perfil propio, avatar propio, sesiones propias, términos, `GET /groups` con estado pendiente, WebSocket solo para eventos de membresía. No snapshot, no ubicación, no heartbeat, no estados, no avatares ajenos.
  - `active`: snapshot de su grupo, ubicación, heartbeat, estado, perfil, avatar propio, avatar de su grupo, salir del grupo.
  - `admin`: todo lo anterior más renombrar, invitar, pendientes, aprobar/rechazar, expulsar y resetear contraseñas de miembros no admin.
  - `left`/`rejected`: sin acceso al grupo; conservan perfil y avatar propio, y pueden unirse a otro con código.
- ✅ Respuestas de recursos no accesibles: `404` para no revelar existencia, salvo errores de estado explícitos (`403 membership_pending`, `409 admin_transfer_required`, `409 last_admin`).
- ✅ `426` incluye `latest` y `minSupportedVersionCode` para que la app muestre la descarga. Si no hay versión mínima marcada, se usa `1`.
- ✅ `429` incluye `Retry-After` en segundos.

## 9. Modelo de datos

- ✅ `users`: cuenta, perfil (`displayName`, `phone` opcional), avatar y términos aceptados. Sin email ni contacto de emergencia en v1.
- ✅ `groups`: un código de invitación con vencimiento obligatorio.
- ✅ `group_members`: una membresía actual por usuario; un admin por grupo; estados `pending`, `active`, `rejected`, `left`.
- ✅ `sessions`: token opaco hasheado, dispositivo, expiración y revocación.
- ✅ `user_state`: última ubicación, precisión, batería y estado. Una fila por usuario.
- ✅ `app_releases`: versión, APK, SHA-256, tamaño, notas y mínima soportada.
- ✅ `notices`: avisos generales.
- ✅ `terms_versions`: versión vigente y enlace al texto.
- ✅ Sin `refresh_tokens`, sin `location_history`, sin `sharing_enabled`.

## 10. Fuera de alcance de v1

- Multi-grupo y múltiples administradores.
- Historial de rutas.
- Notificaciones push/FCM.
- Panel web de administración.
- Instalación del APK dentro de la app.
- Activity Recognition y geofence.
- Detección de “salto imposible”.
- Correo y contacto de emergencia.
- Llamadas dentro de la app (solo abrir el marcador).
- iOS.

## 11. Pendientes reales

- ⏳ Validar la paleta y tipografía con pruebas visuales en dispositivos reales.
- ⏳ Redactar y revisar el texto legal de términos y privacidad (Ley 29733).
- ⏳ Definir retención exacta de logs y respaldos.
- ⏳ Confirmar versión mínima de Android (sugerido API 26) y dispositivos de la familia.
- ⏳ Medir batería real y ajustar intervalos/distancia mínima.
- ⏳ Decidir si se añade FCM en una versión futura.
