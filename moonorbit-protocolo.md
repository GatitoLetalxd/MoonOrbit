# MoonOrbit — Protocolo REST y WebSocket v2

> **Fuente de verdad:** `moonorbit-decisiones-v2.md` (2026-11-01). Si este documento y la fuente de verdad difieren, manda la fuente de verdad.
> **Ámbito:** Android nativo, uso privado familiar, un grupo por usuario, un solo administrador por grupo, sin panel web.
> **Leyenda:** ✅ decidido · 💡 detalle de implementación propuesto · ⏳ pendiente

Este documento define el contrato v2 entre la app Android y el servidor. No hay JWT, no hay refresh token, no hay `/auth/refresh`, no hay multi-grupo, no hay múltiples administradores, no hay `sharing_enabled`, no hay `location_history`, no hay escrituras de ubicación por WebSocket y no hay panel web de administración.

Los ejemplos comparten identificadores para facilitar la lectura, pero las listas están abreviadas y no representan una única línea temporal.

---

## 1. Convenciones

| Tema | Convención | Estado |
|---|---|---|
| Base REST | `https://orbit.moondev.online/api/v1` | ✅ |
| WebSocket | `wss://orbit.moondev.online/ws` | ✅ |
| Transporte | Solo HTTPS y WSS; nunca HTTP/WS en claro | ✅ |
| Formato | JSON UTF-8 (`application/json; charset=utf-8`) | ✅ |
| Fechas | ISO 8601 en UTC, con `Z` (`2026-11-01T14:03:00Z`) | ✅ |
| Identificadores | UUID v4 en `id`, `userId`, `groupId`, `sessionId`, `requestId` | ✅ |
| Autenticación | `Authorization: Bearer <sessionToken>` en REST y en el handshake WebSocket | ✅ |
| Token | Opaco de 256 bits; no es JWT; no contiene claims | ✅ |
| Versión de app | Cabecera `X-App-Version-Code: <entero>` en REST y handshake | ✅ |
| Idioma de mensajes | Español; los códigos (`code`) son estables y en inglés | ✅ |
| Paginación | No hay; las listas están acotadas por los límites del producto | ✅ |
| CORS | No aplica: cliente Android nativo, sin navegador | ✅ |

### 1.1 Cabeceras

Toda petición REST autenticada lleva:

```http
Authorization: Bearer xiEsqC7sIYlLivxp1Tqmnf3chKz_5XezhrfShHD-jU4
X-App-Version-Code: 5
Accept: application/json
```

- `X-App-Version-Code` es obligatorio en todos los endpoints REST y en el handshake WebSocket, **salvo** `GET /health` y `GET /app/version`.
- Si falta o no es un entero válido: `400 app_version_required`.
- Si es menor que `minSupportedVersionCode`: `426 app_update_required`.
- La app puede enviar `X-Request-Id` para trazabilidad; si no lo hace, el servidor genera uno.
- Todas las respuestas incluyen `X-Request-Id`. En errores, el mismo valor aparece en `error.requestId`.
- No se acepta el token en la URL ni en cookies. El WebSocket usa la cabecera `Authorization` del handshake.

### 1.2 Envoltura de error

```json
{
  "error": {
    "code": "validation_error",
    "message": "El campo displayName es obligatorio.",
    "details": {
      "displayName": "Requerido"
    },
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

- `code`: identificador estable, en `snake_case`.
- `message`: texto legible en español; no contiene datos sensibles.
- `details`: opcional; objeto o array con el detalle del fallo.
- `requestId`: UUID; siempre presente, también en errores 5xx.
- El estado HTTP va fuera del cuerpo. Un `200`/`201`/`204`/`304` no lleva envoltura de error.
- `429` incluye `Retry-After` en segundos.
- `426` incluye en `details` el objeto `latest` y `minSupportedVersionCode`.
- `401` por token ausente/inválido incluye `WWW-Authenticate: Bearer`.

### 1.3 Orden de validación

El servidor aplica este orden para que las respuestas sean deterministas:

1. Formato de cabeceras y `X-App-Version-Code` ausente o inválido → `400`.
2. Versión instalada menor que la mínima → `426` (excepto `GET /health` y `GET /app/version`).
3. Autenticación → `401`.
4. `must_change_password` → `403 password_change_required`.
5. Términos desactualizados → `403 terms_required`.
6. Pertenencia, rol y estado del grupo → `403`, `404` o `409` según corresponda.
7. Validación del cuerpo → `400`.
8. Límites de tasa → `429`.

Los endpoints públicos (`register`, `login`, `app/version`, `app/notices`, `app/terms`, `health`) no pasan por los pasos 3–6, salvo que se indique lo contrario.

### 1.4 Respuestas y caché

| Recurso | `Cache-Control` | Validación |
|---|---|---|
| Snapshot de grupo | `private, no-cache` | `ETag` / `If-None-Match` → `304` |
| Avatar | `private, max-age=31536000, immutable` si `?v` coincide; si no, `private, no-cache` | `ETag` / `If-None-Match` → `304` |
| Avisos | `no-cache` | Sin ETag; lista pequeña |
| Versión de app | `no-cache` | Sin ETag |
| Términos | `no-cache` | Sin ETag |
| `GET /health` | `no-store` | Sin caché |

Nginx aplica gzip/brotli al snapshot y a las respuestas JSON. Las imágenes WebP no se recomprimen en tránsito.

### 1.5 Zona horaria y reloj

- El servidor es la autoridad temporal. Todas las fechas que emite están en UTC.
- Las respuestas `login`, `snapshot`, `hello`, `POST /location`, `POST /heartbeat`, `POST /me/status` y los errores incluyen `serverTime`.
- La app calcula `clockOffset = serverTime - nowDevice` y lo usa para mostrar “hace X min”.
- `recordedAt` es la hora del dispositivo; el servidor la corrige si está más de 5 minutos en el futuro.

---

## 2. Autenticación y sesiones

### 2.1 Modelo de sesión

- El token de sesión es **opaco**: 256 bits aleatorios (32 bytes), codificados en Base64URL sin relleno (43 caracteres). **No es JWT** y no contiene identidad ni permisos.
- El servidor guarda únicamente `SHA-256(token)` en `sessions.token_hash`. Nunca guarda el token en claro ni lo escribe en logs.
- La sesión vive **90 días fijos** desde `created_at`; no se extiende por uso. `last_used_at` se actualiza al usar la sesión, incluido el handshake WebSocket, con escritura agrupada como máximo cada **5 min** por sesión para no castigar la base.
- Máximo **5 sesiones activas por usuario**. Si un login crea la sexta, se revoca la más antigua y su WebSocket se cierra con `4403`.
- La revocación es inmediata: se escribe `revoked_at`; la siguiente petición REST responde `401 session_revoked` y el WebSocket abierto se cierra con `4403`.
- Contraseñas con `argon2id`. El login devuelve siempre el mismo error genérico para usuario inexistente o contraseña incorrecta.
- **No hay refresh token.** No existe `POST /auth/refresh`; si se llama, responde `404 not_found`. La app vuelve a hacer login cuando la sesión vence o se revoca.

Validación de cuenta (💡, salvo lo indicado en la fuente de verdad):

- `username`: 3–24 caracteres, minúsculas, números y `_`; único sin distinguir mayúsculas; inmutable.
- `displayName`: 2–40 caracteres; editable.
- `password`: 10–128 caracteres; no puede ser igual al `username` ni al `displayName`.
- El nombre del grupo tiene 2–40 caracteres (✅).

### 2.2 `POST /auth/register` — registro con invitación

Público. Siempre crea una membresía **`pending`**; no existe alta directa como `active`. En una transacción valida el código, crea el usuario, crea la sesión, registra la versión de términos y deja la membresía pendiente.

```json
// Petición
{
  "inviteCode": "K7Q9XM3D",
  "username": "mamaluna",
  "password": "Sol-Luna-2026",
  "displayName": "Mamá",
  "termsVersion": "2026-11-01",
  "deviceName": "Samsung Galaxy A54"
}
```

```json
// Respuesta 201
{
  "user": {
    "id": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
    "username": "mamaluna",
    "displayName": "Mamá",
    "avatarVersion": 0,
    "termsAcceptedVersion": "2026-11-01",
    "createdAt": "2026-11-01T14:03:00Z"
  },
  "membership": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "groupName": "Familia Luna",
    "role": "member",
    "status": "pending"
  },
  "session": {
    "sessionId": "5c4b3a29-8f7e-4d6c-b5a4-39281706f5e4",
    "sessionToken": "xiEsqC7sIYlLivxp1Tqmnf3chKz_5XezhrfShHD-jU4",
    "expiresAt": "2027-01-30T14:03:00Z",
    "deviceName": "Samsung Galaxy A54"
  },
  "mustChangePassword": false,
  "serverTime": "2026-11-01T14:03:00Z"
}
```

Reglas:

- El código es genérico: si no existe, venció o fue regenerado, responde `400 invalid_invite_code` sin revelar cuál fue el motivo.
- Si el grupo ya tiene 12 miembros activos: `409 group_full`.
- Si el `username` ya existe: `409 username_taken`.
- Si `termsVersion` no es la vigente: `403 terms_required` con `details.currentVersion`.
- El código de invitación es reutilizable hasta que venza (48 h) o se regenere (✅).
- Tras el registro, la app puede abrir el WebSocket en estado `pending`, pero solo recibirá eventos de membresía.

### 2.3 `POST /auth/login`

Público. Rate limit: 10 intentos por 15 minutos por IP y por usuario.

```json
// Petición
{
  "username": "mamaluna",
  "password": "Sol-Luna-2026",
  "deviceName": "Samsung Galaxy A54"
}
```

```json
// Respuesta 200
{
  "user": {
    "id": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
    "username": "mamaluna",
    "displayName": "Mamá Luna",
    "avatarVersion": 5,
    "termsAcceptedVersion": "2026-11-01"
  },
  "membership": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "groupName": "Familia Luna",
    "role": "member",
    "status": "active"
  },
  "session": {
    "sessionId": "c9d8e7f6-a5b4-4321-9876-0a1b2c3d4e5f",
    "sessionToken": "AuQkv7_qcCeJ1XSmDeUn4luoYkkl2D0wM1f1-USp_6M",
    "expiresAt": "2027-01-30T19:10:00Z",
    "deviceName": "Samsung Galaxy A54"
  },
  "mustChangePassword": false,
  "terms": {
    "acceptedVersion": "2026-11-01",
    "currentVersion": "2026-11-01",
    "upToDate": true
  },
  "serverTime": "2026-11-01T19:10:00Z"
}
```

- `membership` puede ser `pending`, `active` o `null` si el usuario quedó `left`/`rejected` y no tiene membresía vigente. En esos casos `GET /groups` devolverá `[]`.
- `mustChangePassword: true` se devuelve tras un reset administrativo. La sesión se crea igualmente, pero queda restringida (ver 2.7).
- `terms.upToDate: false` obliga a aceptar términos antes de usar el grupo (ver sección 8).
- Credenciales incorrectas: `401 invalid_credentials` (sin distinguir usuario inexistente).
- Si la cuenta no tiene membresía activa/pendiente, el login es válido; podrá unirse con `POST /groups/join`.

### 2.4 `POST /auth/logout`

Autenticado. Revoca la sesión actual.

```http
POST /api/v1/auth/logout
Authorization: Bearer <sessionToken>
```

- Respuesta `204 No Content`.
- El WebSocket de esa sesión se cierra con `4403`.
- Llamarlo desde una sesión ya revocada devuelve `401 session_revoked`.

### 2.5 `POST /auth/logout-others`

Autenticado. Revoca todas las sesiones del usuario **excepto la actual**.

```json
// Respuesta 200
{
  "revoked": 2,
  "serverTime": "2026-11-01T14:03:00Z"
}
```

Cada sesión revocada recibe `session_revoked` y cierre `4403`.

### 2.6 `GET /auth/sessions` y `DELETE /auth/sessions/{id}`

```json
// GET /auth/sessions → 200
{
  "sessions": [
    {
      "sessionId": "c9d8e7f6-a5b4-4321-9876-0a1b2c3d4e5f",
      "deviceName": "Samsung Galaxy A54",
      "appVersionCode": 5,
      "createdAt": "2026-11-01T19:10:00Z",
      "lastUsedAt": "2026-11-01T19:20:00Z",
      "expiresAt": "2027-01-30T19:10:00Z",
      "current": true
    },
    {
      "sessionId": "8e7d6c5b-4a39-4281-9f0e-1d2c3b4a5968",
      "deviceName": "Xiaomi Redmi Note 12",
      "appVersionCode": 4,
      "createdAt": "2026-10-20T09:10:00Z",
      "lastUsedAt": "2026-10-30T21:05:00Z",
      "expiresAt": "2027-01-18T09:10:00Z",
      "current": false
    }
  ],
  "serverTime": "2026-11-01T19:21:00Z"
}
```

- `DELETE /auth/sessions/{id}`: solo sesiones propias; `204`. Si la sesión no existe o es de otro usuario: `404 not_found` (no revelar). Si se elimina la sesión actual, el WebSocket se cierra con `4403`.
- No se pueden listar ni revocar sesiones de otros usuarios, ni siquiera siendo administrador del grupo.

### 2.7 `POST /auth/change-password` y `must_change_password`

```json
// Petición
{
  "currentPassword": "Sol-Luna-2026",
  "newPassword": "Luna-Nueva-2026"
}
```

- Respuesta `204 No Content`.
- Actualiza el hash `argon2id` y limpia `must_change_password`.
- No revoca automáticamente las demás sesiones; para eso existe `POST /auth/logout-others`.
- Si la contraseña actual no coincide: `401 invalid_credentials`.
- Si la nueva no cumple la política: `400 weak_password` con `details`.

**Aplicación en servidor.** Mientras `must_change_password = true`, el servidor solo permite:

| Permitido | Bloqueado |
|---|---|
| `GET /users/me` | Cualquier otro endpoint autenticado |
| `POST /auth/change-password` | `GET /auth/sessions`, `POST /auth/logout-others`, avatares, grupos, ubicación, heartbeat, estados, términos |
| `POST /auth/logout` | Handshake WebSocket (HTTP `403 password_change_required`) |

El bloqueo devuelve `403 password_change_required` con `details.allowedEndpoints`. No es una restricción solo de la app: se valida en el servidor en cada petición.

**Reset por administrador.** `POST /groups/{groupId}/members/{userId}/reset-password` (ver 4.9):

- Solo el administrador del grupo; el objetivo debe ser un miembro **activo no administrador**.
- Genera una contraseña temporal, la devuelve una sola vez en la respuesta y marca `must_change_password = true`.
- Revoca **todas** las sesiones del miembro objetivo; cada WebSocket recibe `session_revoked` y cierre `4403`.
- La contraseña temporal sirve para el primer login y queda invalidada al cambiarla. Mientras no se cambie, la cuenta sigue restringida.
- El administrador no puede resetear su propia contraseña por esta vía (`403 reset_not_allowed`); usa `POST /auth/change-password`.
- Si el administrador olvida su contraseña, la resetea el propietario del servidor con un script. No hay endpoint para eso.

---

## 3. Perfil y avatares

El perfil v2 no incluye email ni contacto de emergencia. La cuenta se identifica por `username` y se muestra por `displayName`; el teléfono es opcional y solo se comparte con el grupo activo. El avatar es opcional.

### 3.1 `GET /users/me`

```json
{
  "user": {
    "id": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
    "username": "mamaluna",
    "displayName": "Mamá",
    "phone": "+51 987 654 321",
    "avatarVersion": 4,
    "avatarUrl": "/api/v1/users/7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f/avatar?v=4",
    "termsAcceptedVersion": "2026-11-01",
    "mustChangePassword": false,
    "createdAt": "2026-11-01T14:03:00Z"
  },
  "membership": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "groupName": "Familia Luna",
    "role": "member",
    "status": "active"
  },
  "serverTime": "2026-11-01T18:21:00Z"
}
```

- `avatarUrl: null` significa “sin foto”; `avatarVersion` es `0` solo hasta la primera subida y sigue incrementándose al reemplazar o eliminar la foto.
- `membership` puede ser `null` para `left`/`rejected`.
- Este endpoint está permitido con `must_change_password = true`.

### 3.2 `PATCH /users/me`

Permite cambiar `displayName` y `phone`. `username` es inmutable y el rol/estado no se cambia por aquí.

```json
// Petición
{
  "displayName": "Mamá Luna",
  "phone": "+51 987 654 321"
}
```

```json
// Respuesta 200
{
  "user": {
    "id": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
    "username": "mamaluna",
    "displayName": "Mamá Luna",
    "phone": "+51 987 654 321",
    "avatarVersion": 4,
    "avatarUrl": "/api/v1/users/7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f/avatar?v=4",
    "termsAcceptedVersion": "2026-11-01",
    "mustChangePassword": false,
    "createdAt": "2026-11-01T14:03:00Z"
  },
  "serverTime": "2026-11-01T18:25:00Z"
}
```

- Si el cuerpo intenta cambiar `username`, `role`, `status`, `avatarVersion` o `termsAcceptedVersion`: `400 validation_error`.
- El cambio se emite al grupo como `profile_updated` (sección 6).
- `displayName`: 2–40 caracteres, sin espacios al inicio/fin (💡).
- `phone`: opcional; `null` lo borra; se guarda tal cual y no se valida como número internacional en v1 (💡).

### 3.3 `DELETE /users/me`

- `204 No Content`.
- Solo para usuarios **no administradores**. Un administrador recibe `409 admin_transfer_required`; primero debe transferir el grupo con el script del propietario del servidor o eliminar el grupo.
- Revoca todas las sesiones, elimina avatar, estado y membresía. Si la membresía era `active`, emite `member_left` con `reason: "account_deleted"` a los miembros activos; si era `pending`, solo desaparece de la lista de pendientes.
- Es irreversible. La app debe pedir confirmación explícita.

### 3.4 `PUT /users/me/avatar`

Cuerpo binario WebP, **sin multipart**. Máximo **1 MB**. Límite de **5 subidas por hora por usuario**.

```http
PUT /api/v1/users/me/avatar
Authorization: Bearer <sessionToken>
X-App-Version-Code: 5
Content-Type: image/webp
Content-Length: 184320

<bytes del WebP>
```

```json
// Respuesta 200
{
  "avatarVersion": 5,
  "avatarUrl": "/api/v1/users/7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f/avatar?v=5",
  "updatedAt": "2026-11-01T18:30:00Z",
  "serverTime": "2026-11-01T18:30:00Z"
}
```

Validación y procesamiento en servidor:

- `Content-Type` debe ser exactamente `image/webp`; si no, `415 unsupported_media_type`.
- Se valida el tipo real por *magic bytes*, no solo la cabecera; si no es WebP decodificable: `400 invalid_image`.
- Se validan dimensiones (> 0; máximo de entrada 4096×4096 para evitar bombas de descompresión, 💡).
- Se re-codifica con `sharp` a **512×512**, calidad **80**, sin metadatos (✅). El resultado puede pesar menos de 1 MB.
- Si el cuerpo supera 1 MB: `413 payload_too_large`.
- Si se supera el límite de 5/hora: `429 rate_limited` con `Retry-After`.
- `avatar_version` se incrementa al reemplazar. La foto anterior se elimina después de actualizar la base de datos.

### 3.5 `DELETE /users/me/avatar`

```json
// Respuesta 200
{
  "avatarVersion": 6,
  "avatarUrl": null,
  "updatedAt": "2026-11-01T19:35:00Z",
  "serverTime": "2026-11-01T19:35:00Z"
}
```

- Si existía foto, incrementa `avatar_version` y borra el archivo.
- Si no existía, es idempotente y devuelve la versión actual sin incrementar.
- Emite `profile_updated` al grupo.

### 3.6 `GET /users/{userId}/avatar`

Requiere sesión. Permitido para:

- El propio usuario (`userId` igual al autenticado), incluso si está `pending`, `left` o `rejected`.
- Miembros con un **grupo activo en común**.
- Un usuario `pending` que intenta ver un avatar ajeno: `403 membership_pending`.
- Avatar ajeno sin grupo activo en común, usuario inexistente o sin avatar: `404 not_found` (no revelar si existe).

```http
GET /api/v1/users/7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f/avatar?v=5
Authorization: Bearer <sessionToken>
If-None-Match: "u7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f-v5"
```

```http
HTTP/1.1 200 OK
Content-Type: image/webp
Content-Length: 184320
ETag: "u7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f-v5"
Cache-Control: private, max-age=31536000, immutable
```

- Si `If-None-Match` coincide: `304 Not Modified`, sin cuerpo, con el mismo `ETag`.
- Si el usuario no tiene avatar o no hay acceso: `404 not_found`; la excepción es `pending` mirando un avatar ajeno, que recibe `403 membership_pending`.
- `?v` es opcional. Si coincide con `avatar_version`, la respuesta puede cachearse de forma inmutable; si no coincide o se omite, se responde `Cache-Control: private, no-cache`.
- En producción, la aplicación puede responder con `X-Accel-Redirect` a una ruta interna de Nginx; el cliente sigue viendo la misma URL, `ETag` y `304`.
- El administrador **no** puede ver el avatar de un miembro `pending` porque no comparte grupo activo con él; la app muestra iniciales.

### 3.7 Almacenamiento y limpieza

- Los archivos viven fuera de la raíz pública: `avatars/{userId}/{version}.webp` (✅).
- `avatar_path` puede ser `null`. `avatar_version` es la fuente de verdad para caché.
- Reemplazo: se escribe el archivo nuevo, se actualiza la base de datos y después se borra el anterior. Si falla el borrado, el archivo viejo queda huérfano y lo elimina el trabajo diario.
- Limpieza diaria: archivos sin referencia en `users.avatar_path` y con más de **24 h** de antigüedad (✅).
- Los avatares se sirven con caché larga y versión; el snapshot no incluye binarios.

### 3.8 Comportamiento Android

- Photo Picker o cámara, recorte cuadrado, vista previa y compresión a WebP antes de subir (✅).
- Sin foto: iniciales con color determinista a partir del `userId`.
- Coil envía la cabecera `Authorization` y cachea por `avatarVersion`; al cerrar sesión se limpia la caché de imágenes (✅).
- No se sube la foto original sin recortar; el servidor igualmente re-codifica a 512×512.

---

## 4. Grupos

### 4.1 Modelo

- **Un grupo por usuario.** `GET /groups` devuelve como máximo un elemento. No hay selector de grupos ni multi-familia.
- **Un solo administrador por grupo**, con rol `admin` y estado `active`. No hay cambios de rol ni transferencia por API.
- El grupo lo crea el propietario del servidor con un script. **No existe `POST /groups`**; si se llama, `404 not_found`.
- La transferencia de administración o la eliminación del grupo también las hace el propietario del servidor con un script.
- Estados de membresía: `pending`, `active`, `rejected`, `left`.
- Índice único parcial: como máximo una membresía `pending` o `active` por usuario.
- Índice único parcial: como máximo un `admin` por grupo, y debe estar `active`.
- Límite: **12 miembros activos por grupo** (el administrador cuenta). Las solicitudes `pending` no cuentan para el límite, pero la aprobación falla con `409 group_full` si ya hay 12 activos.
- Nombre del grupo: 2–40 caracteres.
- Código de invitación: **8 caracteres** sin `0`, `O`, `1` ni `I`; vigencia **48 h**; reutilizable hasta que venza o se regenere. Regenerar invalida el anterior.
- `rejected` puede volver a postular al mismo grupo o unirse a otro; la fila se actualiza a `pending`.
- `left` puede unirse a otro grupo después.
- Un administrador **no puede** salir del grupo ni eliminar su cuenta mientras sea admin: `409 admin_transfer_required`.

### 4.2 `GET /groups`

```json
// Usuario active
{
  "groups": [
    {
      "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
      "name": "Familia Luna",
      "role": "member",
      "status": "active",
      "memberCount": 6,
      "createdAt": "2026-10-01T12:00:00Z"
    }
  ],
  "serverTime": "2026-11-01T18:40:00Z"
}
```

```json
// Usuario pending
{
  "groups": [
    {
      "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
      "name": "Familia Luna",
      "role": "member",
      "status": "pending",
      "memberCount": null,
      "createdAt": "2026-10-01T12:00:00Z"
    }
  ],
  "serverTime": "2026-11-01T18:40:00Z"
}
```

- `left`/`rejected`: `{ "groups": [], "serverTime": "..." }`.
- No incluye lista de miembros, ubicaciones ni código de invitación.
- `memberCount` solo se expone a `active`/`admin`. Para `pending` es `null`.

### 4.3 `POST /groups/join`

Para un usuario ya registrado que no tiene membresía `active` ni `pending`.

```json
// Petición
{ "inviteCode": "K7Q9XM3D" }
```

```json
// Respuesta 200
{
  "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
  "groupName": "Familia Luna",
  "status": "pending",
  "requestedAt": "2026-11-01T18:42:00Z",
  "serverTime": "2026-11-01T18:42:00Z"
}
```

- Siempre queda `pending`; requiere aprobación del administrador.
- Si ya tiene membresía `active` o `pending`: `409 already_member`.
- Si estaba `rejected` o `left` del mismo grupo, la fila se actualiza a `pending`.
- Si estaba `left`/`rejected` de otro grupo, se crea una nueva fila `pending`; la anterior queda como histórico.
- Código inválido, vencido o regenerado: `400 invalid_invite_code`, siempre genérico.
- Grupo con 12 activos: `409 group_full`.
- Rate limit: 5 intentos por hora por IP.

### 4.4 `GET /groups/{groupId}/snapshot`

Solo `active`/`admin`. Es la fuente de la verdad para abrir la app y para el polling de respaldo.

```http
GET /api/v1/groups/2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b/snapshot
Authorization: Bearer <sessionToken>
X-App-Version-Code: 5
If-None-Match: W/"2d9e8c7b-gs-20261101T184500Z-a1b2c3"
```

```json
// 200 OK
{
  "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
  "name": "Familia Luna",
  "serverTime": "2026-11-01T18:45:00Z",
  "etag": "2d9e8c7b-gs-20261101T184500Z-a1b2c3",
  "members": [
    {
      "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
      "displayName": "Mamá Luna",
      "phone": "+51 987 654 321",
      "avatarVersion": 5,
      "role": "member",
      "joinedAt": "2026-11-01T14:05:00Z",
      "state": {
        "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
        "status": "active",
        "statusChangedAt": "2026-11-01T14:05:00Z",
        "location": {
          "lat": -12.0464,
          "lng": -77.0428,
          "accuracyM": 18.5,
          "lowAccuracy": false,
          "recordedAt": "2026-11-01T18:44:50Z"
        },
        "battery": { "level": 72, "isCharging": false },
        "lastSeenAt": "2026-11-01T18:44:52Z"
      }
    }
  ]
}
```

- `members`: solo miembros `active`. Los `pending` no se incluyen en el snapshot; el administrador los consulta en `GET /groups/{groupId}/pending`.
- El valor de `etag` se envía en la cabecera como validador débil: `ETag: W/"<etag>"`.
- `If-None-Match` coincidente → `304 Not Modified`, sin cuerpo.
- `Cache-Control: private, no-cache`.
- `pending` que intenta snapshot: `403 membership_pending`.
- `left`/`rejected` o usuario sin grupo: `404 not_found`.
- Rate limit: 1 petición cada 5 s por usuario (el polling de respaldo usa 30 s; ver sección 7).

### 4.5 `PATCH /groups/{groupId}` — renombrar

Solo administrador.

```json
// Petición
{ "name": "Familia Luna Pérez" }
```

```json
// Respuesta 200
{
  "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
  "name": "Familia Luna Pérez",
  "updatedAt": "2026-11-01T18:50:00Z",
  "serverTime": "2026-11-01T18:50:00Z"
}
```

- Nombre 2–40 caracteres; si no: `400 validation_error`.
- No administrador: `403 forbidden`.
- Emite `group_updated` a los miembros activos.

### 4.6 Invitación: `GET /groups/{groupId}/invite` y regenerar

Solo administrador.

```json
// GET /groups/{groupId}/invite → 200
{
  "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
  "inviteCode": "K7Q9XM3D",
  "createdAt": "2026-11-01T14:03:00Z",
  "expiresAt": "2026-11-03T14:03:00Z",
  "expired": false,
  "reusable": true,
  "serverTime": "2026-11-01T18:52:00Z"
}
```

```json
// POST /groups/{groupId}/invite/regenerate → 200
{
  "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
  "inviteCode": "M4PX7R2T",
  "createdAt": "2026-11-01T18:53:00Z",
  "expiresAt": "2026-11-03T18:53:00Z",
  "expired": false,
  "reusable": true,
  "serverTime": "2026-11-01T18:53:00Z"
}
```

- El código es reutilizable hasta que venza o se regenere; no es de un solo uso.
- Regenerar invalida inmediatamente el código anterior.
- Si el código está vencido, `GET` lo muestra con `expired: true`; el administrador debe regenerarlo.
- Miembro no administrador: `403 forbidden`.
- No se emite evento de grupo al regenerar: el código es secreto del administrador.

### 4.7 `GET /groups/{groupId}/pending`

Solo administrador. Lista solicitudes `pending`, ordenadas por `requestedAt`.

```json
{
  "pending": [
    {
      "userId": "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
      "username": "tioluna",
      "displayName": "Tío Luna",
      "avatarVersion": 0,
      "requestedAt": "2026-11-01T18:30:00Z"
    }
  ],
  "serverTime": "2026-11-01T18:55:00Z"
}
```

- Miembro no administrador: `403 forbidden`.
- `pending` no puede ver esta lista.

### 4.8 Aprobar y rechazar

Solo administrador.

```http
POST /api/v1/groups/{groupId}/members/{userId}/approve
```

```json
// Respuesta 200
{
  "userId": "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "status": "active",
  "approvedAt": "2026-11-01T19:00:00Z",
  "serverTime": "2026-11-01T19:00:00Z"
}
```

- El objetivo debe estar `pending`; si no, `404 not_found`.
- Si ya hay 12 activos: `409 group_full`; la solicitud sigue `pending`.
- Efectos: el objetivo recibe `member_approved` y luego su primer `snapshot`; los demás reciben `member_joined`.
- El nuevo miembro ya puede usar snapshot, ubicación, heartbeat, estados y avatares del grupo.

```http
POST /api/v1/groups/{groupId}/members/{userId}/reject
```

```json
// Respuesta 200
{
  "userId": "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "status": "rejected",
  "rejectedAt": "2026-11-01T19:02:00Z",
  "serverTime": "2026-11-01T19:02:00Z"
}
```

- El objetivo recibe `member_rejected`.
- Puede volver a postular al mismo grupo con un código vigente (la fila pasa a `pending`) o unirse a otro.

### 4.9 Expulsar, salir y resetear contraseña

**Expulsar** — `DELETE /groups/{groupId}/members/{userId}`:

- Solo administrador; el objetivo debe ser `active` y no administrador.
- `204 No Content`.
- La membresía pasa a `left`. El objetivo recibe `member_removed`; el grupo recibe `member_removed`.
- Si el objetivo es el propio administrador: `409 last_admin` (la operación dejaría al grupo sin administrador).
- Si no es miembro: `404 not_found`.

**Salir** — `DELETE /groups/{groupId}/members/me`:

- Solo miembro `active` no administrador.
- `204 No Content`.
- La membresía pasa a `left`; el grupo recibe `member_left`.
- Su propia conexión WebSocket se cierra con `1000`; la sesión REST sigue válida para unirse a otro grupo.
- El usuario puede unirse a otro grupo con un código.
- Si es administrador: `409 admin_transfer_required`. No puede salir mientras sea admin.

**Resetear contraseña** — `POST /groups/{groupId}/members/{userId}/reset-password`:

- Solo administrador; objetivo `active` no administrador.
- Genera una contraseña temporal y la devuelve una sola vez.

```json
// Respuesta 200
{
  "userId": "d4c3b2a1-0f9e-4d8c-9b7a-6f5e4d3c2b1a",
  "temporaryPassword": "Temp-7K9Q-XM3D",
  "mustChangePassword": true,
  "sessionsRevoked": 3,
  "serverTime": "2026-11-01T19:05:00Z"
}
```

- Revoca todas las sesiones del objetivo; cada WebSocket recibe `session_revoked` y cierre `4403`.
- El objetivo debe iniciar sesión con la temporal y cambiarla; mientras no lo haga, solo puede `GET /users/me`, `POST /auth/change-password` y `POST /auth/logout`.
- Si el objetivo es administrador, es uno mismo o no está `active`: `403 reset_not_allowed`.
- Si no pertenece al grupo: `404 not_found`.
- El administrador que olvidó su contraseña no usa este endpoint; lo resuelve el propietario del servidor con un script.

### 4.10 Transiciones de membresía

| Desde | Evento | Hacia | Quién |
|---|---|---|---|
| — | `POST /auth/register` o `POST /groups/join` | `pending` | Usuario con código |
| `pending` | Aprobar | `active` | Administrador |
| `pending` | Rechazar | `rejected` | Administrador |
| `active` | Salir | `left` | Miembro no admin |
| `active` | Expulsar | `left` | Administrador |
| `active` | Eliminar cuenta | `left` (y cuenta eliminada) | Miembro no admin |
| `rejected` | Volver a postular | `pending` | Usuario con código |
| `left` | Unirse a otro grupo | `pending` | Usuario con código |
| `active` (admin) | Salir o eliminar cuenta | — | Bloqueado: `409 admin_transfer_required` |

No existe cambio de rol, ascenso a administrador, degradación, transferencia ni grupos múltiples por API.

---

## 5. Ubicación, heartbeat y estado

### 5.1 Principios

- **Todas las escrituras de ubicación van por REST.** El WebSocket es solo de recepción; la app nunca envía ubicación por WebSocket.
- Solo se guarda la **última ubicación conocida** en `user_state`, una fila por usuario. No hay historial ni endpoints de rutas.
- Sin Activity Recognition ni geofence en v1. El ahorro se basa en `BALANCED_POWER_ACCURACY`, distancia mínima y lotes.
- El servidor es la autoridad de tiempo; la app corrige su desfase con `serverTime`.
- Estados: `active`, `paused`, `location_disabled` y `no_signal`. La app solo puede reportar los tres primeros; `no_signal` lo calcula el servidor.

### 5.2 `POST /location`

Lote de **1 a 50 puntos** y máximo **64 KB**. Rate limit: 1 petición cada 5 s por usuario.

```json
// Petición
{
  "points": [
    {
      "lat": -12.0464,
      "lng": -77.0428,
      "accuracyM": 18.5,
      "recordedAt": "2026-11-01T18:44:30Z",
      "batteryLevel": 72,
      "isCharging": false
    },
    {
      "lat": -12.0467,
      "lng": -77.0431,
      "accuracyM": 22.0,
      "recordedAt": "2026-11-01T18:44:50Z",
      "batteryLevel": 72,
      "isCharging": false
    }
  ]
}
```

```json
// Respuesta 200
{
  "accepted": 2,
  "ignored": 0,
  "effectiveRecordedAt": "2026-11-01T18:44:50Z",
  "status": "active",
  "serverTime": "2026-11-01T18:44:52Z"
}
```

Reglas del servidor:

- El estado se actualiza con el punto de `recordedAt` **más reciente** del lote; los demás puntos del mismo lote no crean historial.
- Se ignoran puntos más viejos que la ubicación ya guardada o con más de **24 h** de antigüedad. Cuentan en `ignored`.
- Un `recordedAt` más de **5 minutos en el futuro** se ajusta a la hora del servidor; no se rechaza.
- `lat` debe estar en `[-90, 90]`, `lng` en `[-180, 180]` y `accuracyM >= 0`. Un punto inválido rechaza el lote completo con `400 validation_error`.
- Más de 50 puntos: `400 too_many_points`. Más de 64 KB: `413 payload_too_large`.
- `lowAccuracy` es `true` cuando `accuracyM > 100`; **no se descarta** la ubicación imprecisa (✅). No hay detección de “salto imposible” en v1.
- Si el estado era `no_signal`, un lote válido lo devuelve a `active`.
- Mientras el estado sea `paused` o `location_disabled`, el servidor ignora el lote completo (0 filas, `ignored`) y no actualiza la ubicación ni la difunde. Esos estados solo se abandonan con `POST /me/status` y `status: "active"` (💡).
- `last_seen_at` se actualiza con la recepción del lote aceptado; `batteryLevel`/`isCharging` actualizan la batería.
- No hay `activity`, ni `sharing_enabled`, ni historial.

### 5.3 `POST /heartbeat`

Se envía cada ~10 minutos si no se envió ubicación. Rate limit: 1 por minuto por usuario.

```json
// Petición
{
  "batteryLevel": 71,
  "isCharging": false
}
```

```json
// Respuesta 200
{
  "status": "active",
  "lastSeenAt": "2026-11-01T18:50:00Z",
  "serverTime": "2026-11-01T18:50:00Z"
}
```

- Actualiza `battery` y `last_seen_at` sin mover la ubicación.
- Si el estado era `no_signal`, vuelve a `active`.
- Si el estado es `paused` o `location_disabled`, el heartbeat actualiza batería y `last_seen_at`, pero **no** cambia el estado.
- No actualiza la ubicación ni crea historial.

### 5.4 `POST /me/status`

La app reporta cambios que el servidor no puede deducir.

```json
// Petición
{
  "status": "paused",
  "batteryLevel": 70,
  "isCharging": false
}
```

```json
// Respuesta 200
{
  "status": "paused",
  "statusChangedAt": "2026-11-01T18:55:00Z",
  "serverTime": "2026-11-01T18:55:00Z"
}
```

- Valores aceptados: `active`, `paused`, `location_disabled`.
- `no_signal` no se acepta: `400 invalid_status`. Solo lo calcula el servidor.
- `paused`: el usuario dejó de compartir desde la app; se detiene el tracking y no se difunde ubicación.
- `location_disabled`: GPS apagado o permiso revocado; se detiene el tracking y no se difunde ubicación.
- `active`: vuelve a compartir; la app reanuda el foreground service y el próximo punto se acepta.
- Un cambio al mismo estado es idempotente y conserva `statusChangedAt`.
- El servidor emite `member_state` a los miembros activos del grupo.

### 5.5 Modelo de estado de un miembro

```json
{
  "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
  "status": "active",
  "statusChangedAt": "2026-11-01T14:05:00Z",
  "location": {
    "lat": -12.0464,
    "lng": -77.0428,
    "accuracyM": 18.5,
    "lowAccuracy": false,
    "recordedAt": "2026-11-01T18:44:50Z"
  },
  "battery": {
    "level": 72,
    "isCharging": false
  },
  "lastSeenAt": "2026-11-01T18:44:52Z"
}
```

- `location` es `null` solo si nunca hubo una ubicación conocida. En `paused`, `location_disabled` y `no_signal` se conserva la última posición; la UI la muestra atenuada con el mensaje del estado.
- `battery` es `null` si nunca se reportó.
- `lastSeenAt` es `null` en el estado inicial; el resto de fechas son UTC.
- `statusChangedAt` puede ser `null` antes del primer cambio.
- `lowAccuracy` se calcula con `accuracyM > 100`.
- Batería baja cuando el nivel es **≤ 15 %**; la app lo indica visualmente.
- El desfase de reloj se calcula con `serverTime` y se usa para “hace X min”.

### 5.6 Estado inicial y `no_signal`

- Estado inicial de un usuario sin datos: `no_signal` con `lastSeenAt: null`. La app muestra **“Sin datos todavía”**, no “hace X min”.
- Trabajo `no_signal`: cada minuto, todo usuario `active` con `last_seen_at < now() - 25 min` pasa a `no_signal`.
- Al pasar a `no_signal`, el servidor emite `member_state` a los miembros activos.
- Si el usuario estaba `paused` o `location_disabled`, el trabajo no lo marca `no_signal`.
- Solo desde `no_signal`, un heartbeat o una ubicación válida lo devuelven a `active`. Los estados `paused`/`location_disabled` no se reactivan con datos: requieren `POST /me/status` con `active`.
- En `no_signal` se conserva la última ubicación conocida, pero la UI la muestra atenuada y con la etiqueta de sin señal; no se actualiza hasta que llegue un punto nuevo.

### 5.7 Precedencia visual

`paused` > `location_disabled` > `no_signal` > `active`

| Estado efectivo | Mensaje en la app |
|---|---|
| `paused` | “{Nombre} pausó el uso compartido de su ubicación.” |
| `location_disabled` | “{Nombre} desactivó su ubicación.” |
| `no_signal` + `lastSeenAt` | “Sin señal de {Nombre} desde hace X min.” + última batería |
| `no_signal` + `lastSeenAt: null` | “Sin datos todavía de {Nombre}.” |
| `active` + `lowAccuracy` | “Ubicación aproximada de {Nombre} (precisión baja, ±X m).” |
| `active` | Sin aviso especial |

La precedencia es visual: el servidor guarda un solo estado por usuario, pero la app no muestra una ubicación vieja como si fuera actual.

---

## 6. WebSocket

### 6.1 Conexión y autenticación

- URL: `wss://orbit.moondev.online/ws`.
- El handshake lleva `Authorization: Bearer <sessionToken>` y `X-App-Version-Code`; **nunca** el token en la URL.
- Una conexión por sesión. Si la misma sesión abre otra, el servidor cierra la anterior con `1000`.
- Si la sesión alcanza `expires_at` con el WebSocket abierto, el servidor cierra con `4401`; la app vuelve a login.
- El servidor hace ping cada **30 s**; si no hay actividad en **90 s**, cierra con `1000`.
- Mensajes de cliente: máximo **4 KB** y **5 por segundo**.
- El WebSocket es **solo recepción** para datos de grupo. La app no envía ubicación, heartbeat ni estados por aquí.

Errores antes del *upgrade* (respuesta HTTP, no cierre WebSocket):

| Caso | HTTP |
|---|---|
| Falta `X-App-Version-Code` o no es entero | `400 app_version_required` |
| Token ausente/inválido/vencido | `401 unauthorized` / `session_expired` |
| Sesión revocada | `401 session_revoked` |
| `must_change_password` | `403 password_change_required` |
| Términos desactualizados | `403 terms_required` |
| `left`/`rejected` sin grupo | `403 forbidden` |
| Versión menor a la mínima | `426 app_update_required` |
| Límite de conexiones | `429 rate_limited` |

### 6.2 `hello` y `snapshot`

Formato de todos los mensajes:

```json
{ "type": "member_state", "data": { } }
```

Al conectar, el servidor envía `hello`:

```json
{
  "type": "hello",
  "data": {
    "protocolVersion": 2,
    "sessionId": "c9d8e7f6-a5b4-4321-9876-0a1b2c3d4e5f",
    "membershipStatus": "active",
    "serverTime": "2026-11-01T19:10:00Z",
    "heartbeatIntervalSec": 30,
    "inactivityTimeoutSec": 90
  }
}
```

- Los miembros `active`, incluido el `admin`, reciben inmediatamente después un `snapshot` completo de su único grupo.
- `pending` recibe `hello` con `membershipStatus: "pending"`, **no** recibe `snapshot` y solo recibe eventos de membresía.
- `left`/`rejected` no pueden conectar (HTTP `403`); pueden seguir usando REST para unirse a otro grupo.

```json
{
  "type": "snapshot",
  "data": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "name": "Familia Luna",
    "serverTime": "2026-11-01T19:10:01Z",
    "members": [
      {
        "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
        "displayName": "Mamá Luna",
        "phone": "+51 987 654 321",
        "avatarVersion": 5,
        "role": "member",
        "joinedAt": "2026-11-01T14:05:00Z",
        "state": {
          "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
          "status": "active",
          "statusChangedAt": "2026-11-01T14:05:00Z",
          "location": {
            "lat": -12.0464,
            "lng": -77.0428,
            "accuracyM": 18.5,
            "lowAccuracy": false,
            "recordedAt": "2026-11-01T19:09:55Z"
          },
          "battery": { "level": 72, "isCharging": false },
          "lastSeenAt": "2026-11-01T19:09:57Z"
        }
      }
    ]
  }
}
```

### 6.3 Servidor → app

| `type` | Cuándo | Datos principales |
|---|---|---|
| `hello` | Al conectar | `protocolVersion`, `sessionId`, `membershipStatus`, `serverTime`, intervalos |
| `snapshot` | Tras `hello` y tras `resync` | Grupo, `serverTime`, miembros activos |
| `member_state` | Cambia ubicación, batería o estado | `groupId`, `state` completo |
| `member_joined` | Se aprueba o entra un miembro activo | `groupId`, perfil básico |
| `member_left` | Salida voluntaria o eliminación de cuenta | `groupId`, `userId`, `leftAt`, `reason` |
| `member_pending` | Nueva solicitud (solo admin) | `groupId`, perfil básico, `requestedAt` |
| `member_approved` | El usuario objetivo fue aprobado | `groupId`, `groupName`, `role`, `approvedAt` |
| `member_rejected` | El usuario objetivo fue rechazado | `groupId`, `rejectedAt` |
| `member_removed` | Expulsión | `groupId`, `userId`, `removedAt` |
| `profile_updated` | Cambia nombre, teléfono o avatar | `groupId`, `userId`, `displayName`, `phone`, `avatarVersion` |
| `group_updated` | Cambia el nombre del grupo | `groupId`, `name`, `updatedAt` |
| `notice` | Nuevo aviso general | Objeto aviso |
| `session_revoked` | La sesión actual fue revocada | `sessionId`, `reason`, `revokedAt` |
| `error` | Mensaje inválido o límite | `code`, `message`, `details`, `requestId` |

Reglas de cierre por evento:

- `member_approved` se sigue de un `snapshot`; la conexión continúa.
- `member_rejected` o `member_removed` dirigido a la propia sesión: el servidor envía el evento y cierra con `1000`. La cuenta y la sesión siguen siendo válidas para REST; puede unirse a otro grupo.
- `session_revoked`: el servidor cierra con `4403`; la app no debe reconectar.
- `notice`, `profile_updated`, `group_updated`, `member_joined`, `member_left` y `member_pending` no cierran la conexión.

Ejemplos:

```json
{
  "type": "member_state",
  "data": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "state": {
      "userId": "7f3c2a10-9b4d-4e21-8c6f-1a2b3c4d5e6f",
      "status": "no_signal",
      "statusChangedAt": "2026-11-01T19:10:00Z",
      "location": {
        "lat": -12.0464,
        "lng": -77.0428,
        "accuracyM": 18.5,
        "lowAccuracy": false,
        "recordedAt": "2026-11-01T18:44:50Z"
      },
      "battery": { "level": 15, "isCharging": false },
      "lastSeenAt": "2026-11-01T18:45:00Z"
    }
  }
}
```

```json
{
  "type": "member_approved",
  "data": {
    "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b",
    "groupName": "Familia Luna",
    "role": "member",
    "approvedAt": "2026-11-01T19:00:00Z"
  }
}
```

```json
{
  "type": "session_revoked",
  "data": {
    "sessionId": "f0e1d2c3-b4a5-4678-9abc-def012345678",
    "reason": "password_reset",
    "revokedAt": "2026-11-01T19:05:00Z"
  }
}
```

```json
{
  "type": "error",
  "data": {
    "code": "unsupported_message",
    "message": "La app solo puede enviar resync o ping.",
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

### 6.4 App → servidor

Solo se aceptan dos mensajes:

| `type` | Uso | Datos |
|---|---|---|
| `resync` | Volver a primer plano o sospechar pérdida de eventos | `groupId` opcional |
| `ping` | Keepalive de aplicación | — |

```json
{ "type": "resync", "data": { "groupId": "2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b" } }
```

```json
{ "type": "ping" }
```

- `resync` responde con un `snapshot` completo.
- `ping` se responde con un pong de control WebSocket; no genera un evento JSON.
- Cualquier otro tipo, incluido `location`, `heartbeat` o `status`, produce `error` con `code: "unsupported_message"`. Si se repite, el servidor cierra con `4429`.
- No existe `ack` de ubicación porque la ubicación nunca viaja por WebSocket.

### 6.5 Códigos de cierre

| Código | Significado | Acción de la app |
|---|---|---|
| `1000` | Cierre normal, inactividad, reemplazo de conexión o servidor disponible | Reconectar con backoff si la app sigue en primer plano |
| `4401` | Token inválido o vencido | Borrar token y volver al login; no hay refresh |
| `4403` | Sesión revocada | Borrar token, limpiar caché y no reconectar |
| `4426` | Actualización obligatoria | Mostrar pantalla de actualización |
| `4429` | Límite de mensajes excedido | Backoff y reconectar; no enviar ráfagas |
| `1012` | Reinicio del servidor | Reconectar con backoff |

### 6.6 Reconexión y resincronización

1. Al conectar: `hello` y `snapshot` automático.
2. Al volver a primer plano: si el WebSocket sigue abierto, enviar `resync`; si está caído, usar polling de respaldo (sección 7).
3. Backoff exponencial con variación aleatoria: 1 s, 2 s, 4 s, 8 s… máximo 60 s.
4. La app ignora eventos más viejos que el estado que ya tiene, comparando `statusChangedAt` y `recordedAt`.
5. Tras `4403` no se reconecta. Tras `4401` se vuelve a login. Tras `4426` se actualiza.

---

## 7. Polling de respaldo

El polling **solo** se usa si se cumplen las tres condiciones:

1. El WebSocket está caído.
2. La app está en primer plano.
3. El usuario es `active` o `admin`.

Si el WebSocket está conectado, la app **no hace polling**.

- Intervalo: **30 s**.
- Al pasar a primer plano: snapshot inmediato y luego cada 30 s.
- Al pasar a segundo plano: se detiene el polling.
- Endpoint: `GET /groups/{groupId}/snapshot` con `If-None-Match`.
- `304 Not Modified`: no se redibuja el mapa; se conserva el estado.
- `200`: se reemplaza el snapshot y se actualiza el `ETag`.
- Límite de servidor: 1 petición cada 5 s por usuario; el intervalo de 30 s queda holgado. Si se supera: `429` con `Retry-After`.
- `Cache-Control: private, no-cache`; Nginx puede comprimir la respuesta.
- `pending` no puede hacer polling (`403 membership_pending`); `left`/`rejected` reciben `404`.

```http
GET /api/v1/groups/2d9e8c7b-6a5f-4e3d-9c2b-1a0f9e8d7c6b/snapshot
Authorization: Bearer <sessionToken>
X-App-Version-Code: 5
If-None-Match: W/"2d9e8c7b-gs-20261101T184500Z-a1b2c3"
```

```http
HTTP/1.1 304 Not Modified
ETag: W/"2d9e8c7b-gs-20261101T184500Z-a1b2c3"
Cache-Control: private, no-cache
```

---

## 8. Avisos, versión, términos y salud

### 8.1 `GET /app/version`

Público, sin autenticación y sin exigir `X-App-Version-Code` (es el endpoint que permite actualizar antes de iniciar sesión).

```json
{
  "latest": {
    "versionCode": 5,
    "versionName": "1.2.0",
    "apkUrl": "https://orbit.moondev.online/download/moonorbit-1.2.0.apk",
    "apkSha256": "a3f1c9e7b5d24680f1e3c5a7b9d0f2e4c6a8b0d2f4e6c8a0b2d4f6e8a0c2b4d6",
    "apkSizeBytes": 24857312,
    "releaseNotes": "Mejoras de batería y avisos.",
    "publishedAt": "2026-11-01T10:00:00Z"
  },
  "minSupportedVersionCode": 3,
  "serverTime": "2026-11-01T19:20:00Z"
}
```

- `apkSha256` es el SHA-256 del APK publicado; la app puede verificarlo antes de instalar.
- `apkSizeBytes` es el tamaño exacto del APK.
- Si ninguna fila de `app_releases` tiene `is_min_supported = true`, `minSupportedVersionCode` es `1`; no se usa la última versión como piso.
- La descarga se abre en el navegador; **no hay instalación interna del APK** en v1.
- Si `X-App-Version-Code` es menor que `minSupportedVersionCode`, el resto de endpoints responde `426 app_update_required` con `details.latest` y `details.minSupportedVersionCode`.

```json
{
  "error": {
    "code": "app_update_required",
    "message": "Esta versión ya no es compatible. Actualiza MoonOrbit.",
    "details": {
      "latest": {
        "versionCode": 5,
        "versionName": "1.2.0",
        "apkUrl": "https://orbit.moondev.online/download/moonorbit-1.2.0.apk",
        "apkSha256": "a3f1c9e7b5d24680f1e3c5a7b9d0f2e4c6a8b0d2f4e6c8a0b2d4f6e8a0c2b4d6",
        "apkSizeBytes": 24857312
      },
      "minSupportedVersionCode": 3
    },
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

### 8.2 `GET /app/notices`

Público. Devuelve los avisos vigentes en el momento de la consulta.

```json
{
  "notices": [
    {
      "id": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      "title": "Mantenimiento",
      "message": "El servidor se reiniciará el sábado de 02:00 a 02:15.",
      "severity": "important",
      "dismissible": true,
      "startsAt": "2026-11-01T00:00:00Z",
      "endsAt": "2026-11-08T00:00:00Z"
    }
  ],
  "serverTime": "2026-11-01T19:22:00Z"
}
```

- `severity`: `info` o `important`.
- `dismissible: false` obliga a mostrarlo hasta que venza.
- La app guarda en DataStore los IDs descartados; no se envía ese estado al servidor.
- Un aviso nuevo se emite por WebSocket como `notice` solo a sesiones `active`/`admin` con la app abierta.
- Los avisos se publican por SQL/CLI; **no hay endpoint de administración ni panel web**.

### 8.3 Términos versionados

`GET /app/terms` — público:

```json
{
  "version": "2026-11-01",
  "textUrl": "https://orbit.moondev.online/legal/terminos-2026-11-01",
  "textHash": "a3f1c9e7b5d24680f1e3c5a7b9d0f2e4c6a8b0d2f4e6c8a0b2d4f6e8a0c2b4d6",
  "effectiveAt": "2026-11-01T00:00:00Z"
}
```

`POST /users/me/terms` — autenticado:

```json
// Petición
{ "version": "2026-11-01" }
```

```json
// Respuesta 200
{
  "acceptedVersion": "2026-11-01",
  "acceptedAt": "2026-11-01T19:25:00Z",
  "serverTime": "2026-11-01T19:25:00Z"
}
```

- `textHash` es el SHA-256 hexadecimal del texto legal. La versión vigente es la de mayor `effective_at <= now()`; no existe una columna `is_current`.
- Solo se puede aceptar la versión vigente. Si no coincide: `403 terms_required` con `details.currentVersion`.
- En el registro, `termsVersion` debe ser la vigente; si no: `403 terms_required`.
- Si la versión aceptada por el usuario no es la vigente, el servidor bloquea las funciones de grupo, ubicación y WebSocket hasta que acepte.
- Endpoints permitidos con términos desactualizados: `GET /users/me`, `POST /users/me/terms`, `POST /auth/logout`, `GET /app/notices`, `GET /app/terms`, `GET /app/version` y `GET /health`. Los endpoints públicos siguen disponibles.
- El resto responde `403 terms_required`.
- El texto legal y la política de privacidad (Ley 29733) están pendientes de redacción y revisión (⏳).

### 8.4 `GET /health`

Público, sin autenticación, sin `X-App-Version-Code` y sin caché. Lo usa Uptime Kuma y el monitoreo externo.

```json
{
  "status": "ok",
  "version": "1.2.0",
  "database": "ok",
  "uptimeSeconds": 1234567,
  "serverTime": "2026-11-01T19:30:00Z"
}
```

- `200` si la base de datos responde y el proceso está sano.
- `503 service_unavailable` si la base de datos no responde o el servicio está en mantenimiento.
- No expone tokens, configuración, rutas internas ni datos de usuarios.

---

## 9. Catálogo de errores

### 9.1 Tabla completa

| HTTP | `code` | Cuándo | Acción de la app |
|---|---|---|---|
| `400` | `validation_error` | Campo ausente, tipo incorrecto, rango inválido, cuerpo malformado | Corregir; no reintentar igual |
| `400` | `invalid_invite_code` | Código inexistente, vencido o regenerado | Pedir código nuevo; mensaje genérico |
| `400` | `weak_password` | Contraseña no cumple la política | Mostrar requisitos |
| `400` | `invalid_status` | `POST /me/status` con `no_signal` o valor desconocido | Enviar solo `active`, `paused` o `location_disabled` |
| `400` | `invalid_image` | Avatar no decodificable, no WebP real o dimensiones inválidas | Elegir otra imagen |
| `400` | `too_many_points` | Lote de ubicación con más de 50 puntos | Reenviar en lotes de 50 |
| `400` | `app_version_required` | Falta `X-App-Version-Code` o no es entero | Enviar cabecera |
| `400` / WS | `unsupported_message` | Mensaje WebSocket distinto de `resync`/`ping`; se emite como evento `error`, no como HTTP | Enviar solo `resync`/`ping` |
| `401` | `unauthorized` | Token ausente, mal formado o no reconocido | Volver al login |
| `401` | `session_expired` | Sesión superó los 90 días | Volver al login; no hay refresh |
| `401` | `invalid_credentials` | Login con usuario/contraseña incorrectos, o contraseña actual incorrecta | Reintentar con cuidado; rate limit |
| `401` | `session_revoked` | Sesión revocada por logout, admin, límite o reset | Borrar token; no reconectar |
| `403` | `forbidden` | Rol insuficiente o operación no permitida | No reintentar |
| `403` | `membership_pending` | `pending` intenta snapshot, ubicación, heartbeat, estado, avatares ajenos o admin | Esperar aprobación |
| `403` | `password_change_required` | `must_change_password = true` y endpoint no permitido | Ir a cambio de contraseña |
| `403` | `terms_required` | Términos desactualizados o versión no vigente | Mostrar y aceptar términos |
| `403` | `reset_not_allowed` | Reset a admin, a uno mismo o a miembro no activo | Usar change-password o corregir objetivo |
| `404` | `not_found` | Recurso inexistente o sin acceso; grupo, miembro, avatar, sesión, ruta inexistente | No revelar; volver a estado seguro |
| `409` | `username_taken` | Registro con username ya usado | Elegir otro |
| `409` | `already_member` | `POST /groups/join` con membresía `active`/`pending` | No unirse a otro grupo |
| `409` | `group_full` | Ya hay 12 miembros activos | Esperar cupo o rechazar |
| `409` | `admin_transfer_required` | Admin intenta salir del grupo o eliminar su cuenta | Transferir con script del propietario |
| `409` | `last_admin` | Operación intentaría dejar el grupo sin administrador activo | No permitir; transferir con script |
| `413` | `payload_too_large` | Avatar > 1 MB o lote de ubicación > 64 KB | Reducir y reenviar |
| `415` | `unsupported_media_type` | Avatar con `Content-Type` distinto de `image/webp` | Enviar WebP |
| `426` | `app_update_required` | `X-App-Version-Code` menor que la mínima | Mostrar descarga; actualizar |
| `429` | `rate_limited` | Se superó un límite de tasa | Respetar `Retry-After` |
| `500` | `server_error` | Error inesperado del servidor | Reintentar con backoff; reportar `requestId` |
| `503` | `service_unavailable` | Base de datos o servicio no disponible | Reintentar más tarde; health lo refleja |

### 9.2 Reglas de uso

- `invalid_invite_code` es **siempre** genérico: no distingue entre código inexistente, vencido o regenerado.
- `not_found` se usa para recursos ajenos o inexistentes y evita revelar existencia. Excepciones de estado explícitas: `403 membership_pending`, `409 admin_transfer_required`, `409 last_admin`.
- `401 unauthorized` no dice si el usuario existe. `invalid_credentials` tampoco.
- `429` incluye `Retry-After` en segundos; la app no debe reintentar antes.
- `426` incluye `details.latest` y `details.minSupportedVersionCode`; la app no debe seguir usando el resto de endpoints hasta actualizar.
- Los errores 5xx incluyen `requestId`; los logs del servidor lo correlacionan sin guardar tokens, contraseñas ni ubicaciones.

### 9.3 Ejemplos

```json
// 401 sesión revocada
{
  "error": {
    "code": "session_revoked",
    "message": "La sesión fue cerrada. Inicia sesión de nuevo.",
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

```json
// 409 administrador
{
  "error": {
    "code": "admin_transfer_required",
    "message": "El administrador no puede salir del grupo mientras lo administre.",
    "details": {
      "allowedAction": "Transferir la administración con el script del propietario del servidor."
    },
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

```json
// 429 rate limit
{
  "error": {
    "code": "rate_limited",
    "message": "Demasiadas solicitudes. Intenta de nuevo en 42 segundos.",
    "details": { "retryAfterSeconds": 42 },
    "requestId": "e4d3c2b1-a0f9-4e8d-9c7b-6a5f4e3d2c1b"
  }
}
```

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 42
Content-Type: application/json; charset=utf-8
```

---

## 10. Matriz de autorización

Leyenda: ✅ permitido · ⚠️ permitido con restricción · — denegado · `409`/`403` indican el error esperado.

| Endpoint / capacidad | Anónimo | `pending` | `active` | `admin` | `left`/`rejected` |
|---|---|---|---|---|---|
| `POST /auth/register` | ✅ | — | — | — | — |
| `POST /auth/login` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `POST /auth/logout` | — | ✅ | ✅ | ✅ | ✅ |
| `POST /auth/logout-others` | — | ✅ | ✅ | ✅ | ✅ |
| `GET /auth/sessions` | — | ✅ | ✅ | ✅ | ✅ |
| `DELETE /auth/sessions/{id}` | — | ✅ propias | ✅ propias | ✅ propias | ✅ propias |
| `POST /auth/change-password` | — | ✅ | ✅ | ✅ | ✅ |
| `GET /users/me` | — | ✅ | ✅ | ✅ | ✅ |
| `PATCH /users/me` | — | ✅ | ✅ | ✅ | ✅ |
| `DELETE /users/me` | — | ✅ | ✅ | `409 admin_transfer_required` | ✅ |
| `PUT/DELETE /users/me/avatar` | — | ✅ | ✅ | ✅ | ✅ |
| `GET /users/{userId}/avatar` | — | ⚠️ solo el propio | ⚠️ propio o grupo activo | ⚠️ propio o grupo activo | ⚠️ solo el propio |
| `GET /groups` | — | ⚠️ estado `pending` | ✅ máx. 1 | ✅ máx. 1 | ⚠️ `[]` |
| `POST /groups/join` | — | `409 already_member` | `409 already_member` | `409 already_member` | ✅ |
| `GET /groups/{groupId}/snapshot` | — | `403 membership_pending` | ✅ | ✅ | `404 not_found` |
| `PATCH /groups/{groupId}` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `GET /groups/{groupId}/invite` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `POST /groups/{groupId}/invite/regenerate` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `GET /groups/{groupId}/pending` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `POST .../members/{userId}/approve` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `POST .../members/{userId}/reject` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `DELETE /groups/{groupId}/members/{userId}` | — | `403 forbidden` | `403 forbidden` | ✅ | `404 not_found` |
| `DELETE /groups/{groupId}/members/me` | — | `403 forbidden` | ✅ | `409 admin_transfer_required` | `404 not_found` |
| `POST .../members/{userId}/reset-password` | — | `403 forbidden` | `403 forbidden` | ✅ objetivo no admin | `404 not_found` |
| `POST /location` | — | `403 membership_pending` | ✅ | ✅ | `404 not_found` |
| `POST /heartbeat` | — | `403 membership_pending` | ✅ | ✅ | `404 not_found` |
| `POST /me/status` | — | `403 membership_pending` | ✅ | ✅ | `404 not_found` |
| `POST /users/me/terms` | — | ✅ | ✅ | ✅ | ✅ |
| `GET /app/version` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /app/notices` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /app/terms` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /health` | ✅ | ✅ | ✅ | ✅ | ✅ |
| WebSocket `wss://.../ws` | — | ⚠️ solo membresía | ✅ completo | ✅ completo | — HTTP `403` |

Notas:

1. `admin` incluye todas las capacidades de `active`; no es un rol adicional con acceso a otros grupos.
2. `pending` puede administrar su perfil, su avatar y sus sesiones, pero no ve miembros, ubicaciones, avatares ajenos ni snapshot.
3. `pending` en WebSocket recibe `hello`, `member_approved`, `member_rejected`, `member_removed`, `session_revoked` y `error`; no recibe `snapshot`, `member_state`, `notice` ni eventos de otros miembros.
4. `left`/`rejected` no tienen acceso al grupo; conservan cuenta, perfil, sesiones, términos y pueden `POST /groups/join`.
5. `must_change_password` anula esta matriz: solo `GET /users/me`, `POST /auth/change-password` y `POST /auth/logout`; el WebSocket se rechaza en el handshake.
6. Términos desactualizados bloquean grupo, ubicación, heartbeat, estados y WebSocket; permiten perfil, términos, logout, avisos, versión y health.
7. Recursos ajenos o inexistentes: `404 not_found`, salvo `403 membership_pending`, `409 admin_transfer_required` y `409 last_admin`.
8. No hay cambios de rol, múltiples administradores ni multi-grupo. El administrador no puede salir ni eliminar su cuenta mientras lo sea.

---

## 11. Rate limiting y límites de servidor

### 11.1 Límites de uso

| Acción | Límite | Ámbito | Respuesta |
|---|---|---|---|
| `POST /auth/login` | 10 intentos / 15 min | IP y usuario | `429 rate_limited` |
| `POST /auth/register` | 5 intentos / hora | IP | `429 rate_limited` |
| `POST /groups/join` | 5 intentos / hora | IP | `429 rate_limited` |
| `POST /location` | 1 petición / 5 s | Usuario | `429 rate_limited` |
| `POST /heartbeat` | 1 / min | Usuario | `429 rate_limited` |
| `PUT /users/me/avatar` | 5 / hora | Usuario | `429 rate_limited` |
| Snapshot de respaldo | 1 / 5 s | Usuario | `429 rate_limited` |
| WebSocket | 5 mensajes / s y 4 KB por mensaje | Conexión | `error` y/o cierre `4429` |

- `429` siempre incluye `Retry-After` en segundos y `details.retryAfterSeconds`.
- Los límites se aplican en Nginx y/o en Fastify; no dependen de la app.
- No hay límite específico de `POST /me/status` en la fuente de verdad; se aplican los límites generales y el WebSocket no lo transporta.

### 11.2 Límites de datos y sesiones

| Recurso | Límite |
|---|---|
| Miembros activos por grupo | 12 (el admin cuenta) |
| Sesiones activas por usuario | 5; el login revoca la más antigua |
| Avatar | 1 MB, WebP, 512×512, 5 subidas/hora |
| Lote de ubicación | 50 puntos o 64 KB |
| Código de invitación | 8 caracteres, 48 h, reutilizable |
| Nombre de grupo | 2–40 caracteres |
| Mensaje WebSocket | 4 KB, 5/s |
| Conexiones WebSocket | 1 por sesión |
| Cuerpo JSON general | 16 KB (💡) |
| Cuerpo de ubicación | 64 KB |
| Cuerpo de avatar | 1 MB |

### 11.3 Servidor y red

- Una sola instancia Node.js/Fastify con PM2 en modo **fork**. Sin clúster y sin Redis (✅).
- Nginx delante: TLS, HTTP/2, gzip/brotli, límites de cuerpo, rate limiting, APK estático y `X-Accel-Redirect` para avatares (✅).
- Pool de conexiones a PostgreSQL: máximo **10** (✅).
- WebSocket: ping cada 30 s, inactividad 90 s, mensajes de 4 KB y 5/s (✅).
- Si el WebSocket está conectado, la app no hace polling. Si se cae, snapshot cada 30 s solo en primer plano (✅).
- Snapshot con compresión y `Cache-Control: private, no-cache`; avatares con caché larga y versión (✅).
- Sin historial de ubicaciones: `user_state` tiene una fila por usuario (✅).
- Índices para sesiones, estado, avisos y membresías (✅).

### 11.4 Trabajos periódicos

| Trabajo | Frecuencia | Efecto |
|---|---|---|
| `no_signal` | Cada minuto | `active` con `last_seen_at < now() - 25 min` pasa a `no_signal` y emite `member_state` |
| Limpieza de sesiones | Diaria | Elimina vencidas y revocadas antiguas |
| Limpieza de avisos | Diaria | Elimina avisos vencidos |
| Limpieza de membresías | Diaria | Elimina `rejected`/`left` antiguas según retención |
| Limpieza de avatares | Diaria | Borra huérfanos sin referencia y con más de 24 h |
| Limpieza de APKs | Mensual | Elimina versiones antiguas del APK |

### 11.5 Operación y seguridad

- Respaldos: `pg_dump` diario cifrado fuera del VPS; retención 7 diarios, 4 semanales y 12 mensuales; restauración probada cada trimestre. Keystore de firma respaldada offline (✅).
- Monitoreo: `GET /health`, Uptime Kuma, espacio en disco, certificado TLS, éxito de respaldos y reinicios de PM2 (✅).
- Seguridad: HTTPS, `argon2id`, validación de entrada, autorización por grupo, logs sin tokens/contraseñas/ubicaciones, `allowBackup=false` en Android, token cifrado en el dispositivo y APK con SHA-256 publicado (✅).
- Sin notificaciones push, sin panel web, sin historial, sin multi-grupo, sin múltiples administradores, sin Activity Recognition/geofence y sin instalación interna del APK en v1 (✅).

---

## 12. Flujos clave

### 12.1 Registro y aprobación

1. El administrador consulta `GET /groups/{groupId}/invite` o genera uno nuevo con `POST /groups/{groupId}/invite/regenerate`. El código vale 48 h y es reutilizable.
2. La persona se registra con `POST /auth/register` usando el código. Queda `pending`, con sesión propia y términos aceptados.
3. La app abre el WebSocket en estado `pending`: recibe `hello`, no recibe `snapshot` y muestra la pantalla de espera.
4. El administrador ve la solicitud con `GET /groups/{groupId}/pending` o el evento `member_pending`.
5. El administrador aprueba con `POST .../approve`. El servidor pone la membresía `active`.
6. El nuevo miembro recibe `member_approved` y su primer `snapshot`; los demás reciben `member_joined`.
7. Si lo rechaza, el objetivo recibe `member_rejected`; puede volver a postular al mismo grupo o unirse a otro con un código.

No existe alta directa como `active`: el código y la aprobación son obligatorios.

### 12.2 Tracking en segundo plano

1. El foreground service usa `FusedLocationProvider` con `BALANCED_POWER_ACCURACY`, distancia mínima y lotes.
2. Acumula puntos y los envía por `POST /location` (máximo 50 puntos o 64 KB; 1 petición/5 s).
3. Si no hay internet, guarda los puntos en Room y los reenvía juntos al recuperar conexión.
4. Si no hubo ubicación, envía `POST /heartbeat` cada ~10 min con batería y estado de carga.
5. El servidor actualiza solo la última ubicación, batería y `last_seen_at`; no guarda historial.
6. No hay Activity Recognition ni geofence; el ahorro depende de la precisión balanceada, la distancia mínima y el tamaño del lote.

### 12.3 Abrir la app

1. `GET /app/version` (público). Si la versión es menor que la mínima, la app muestra actualización y no continúa.
2. `GET /app/notices` y `GET /app/terms`; se muestran avisos vigentes y, si hace falta, términos.
3. La app lee el token cifrado del dispositivo. Si está vencido o revocado, va a login.
4. Si `mustChangePassword` es `true`, va a cambio de contraseña.
5. Si los términos están desactualizados, va a la pantalla de términos.
6. Con sesión `active`/`admin`, abre el WebSocket: `hello` y `snapshot`. Desde ese momento **no hace polling**.
7. Si el WebSocket está caído, hace un snapshot inmediato y luego cada 30 s solo en primer plano.
8. Al volver a primer plano con WebSocket abierto, envía `resync`; si está caído, reanuda el polling.

### 12.4 GPS apagado

1. La app detecta `PROVIDERS_CHANGED_ACTION` o la revocación del permiso.
2. Envía `POST /me/status` con `status: "location_disabled"`.
3. El servidor actualiza el estado y emite `member_state` a los miembros activos.
4. La app detiene el tracking y no envía ubicación; los demás ven “{Nombre} desactivó su ubicación.”
5. Cuando el GPS vuelve, la app envía `status: "active"` y reanuda el servicio.

### 12.5 Pausa

1. El usuario pausa desde la app; se envía `POST /me/status` con `status: "paused"`.
2. El servidor emite `member_state`; la ubicación no se difunde y el estado `paused` tiene prioridad visual.
3. La app detiene el envío de ubicación y el foreground service; puede seguir enviando heartbeat de batería, pero el estado no cambia.
4. Al reanudar, envía `status: "active"`; el próximo punto de ubicación se acepta y se difunde.

### 12.6 Sin señal

1. El servidor ejecuta el trabajo cada minuto.
2. Si un usuario `active` tiene `last_seen_at < now() - 25 min`, pasa a `no_signal` y emite `member_state`.
3. La app muestra “Sin señal de {Nombre} desde hace X min.” y la última batería.
4. Si nunca hubo datos (`lastSeenAt: null`), muestra “Sin datos todavía”.
5. Solo desde `no_signal`, un heartbeat o una ubicación válida lo devuelven a `active`; `paused`/`location_disabled` solo cambian con `POST /me/status` y `active`.
6. La app nunca envía `no_signal`; es un estado calculado por el servidor.

### 12.7 Pérdida de teléfono o sesión revocada

1. La persona inicia sesión en el dispositivo nuevo.
2. Consulta `GET /auth/sessions` y revoca la sesión vieja con `DELETE /auth/sessions/{sessionId}`.
3. El dispositivo viejo recibe `session_revoked` y cierre `4403`; su siguiente petición REST responde `401 session_revoked`.
4. Su membresía, rol y grupo se conservan; solo se elimina la sesión.
5. Si el administrador resetea la contraseña, se revocan **todas** las sesiones del miembro, se genera una temporal y se marca `must_change_password`.
6. El miembro entra con la temporal; solo puede `GET /users/me`, cambiar contraseña y logout; al cambiarla, recupera el acceso.
7. Si el administrador olvida su contraseña, el propietario del servidor la resetea con un script; no hay endpoint para ese caso.

### 12.8 Avatar

1. La app abre Photo Picker o cámara, recorta en cuadrado, previsualiza y comprime a WebP.
2. Envía `PUT /users/me/avatar` con `Content-Type: image/webp` y cuerpo binario de máximo 1 MB.
3. El servidor valida tipo real, dimensiones y tamaño; re-codifica a 512×512, calidad 80, sin metadatos.
4. Incrementa `avatar_version`, guarda `avatars/{userId}/{version}.webp` y emite `profile_updated`.
5. Los demás piden `GET /users/{userId}/avatar?v=N`; reciben `ETag` y pueden obtener `304`.
6. Al reemplazar, se escribe el nuevo, se actualiza la base y se borra el anterior; la limpieza diaria elimina huérfanos con más de 24 h.
7. Al eliminar, `avatar_version` incrementa y la app vuelve a iniciales.

### 12.9 Actualización obligatoria

1. La app envía `X-App-Version-Code` en cada petición y handshake.
2. Si es menor que `minSupportedVersionCode`, el servidor responde `426 app_update_required` con `latest` y `minSupportedVersionCode`.
3. `latest` incluye `apkUrl`, `apkSha256`, `apkSizeBytes` y `versionName`.
4. La app muestra la pantalla de actualización y abre la descarga en el navegador; no instala el APK internamente.
5. `GET /app/version` y `GET /health` siguen disponibles sin la cabecera para poder actualizar.

### 12.10 Términos versionados

1. La app consulta `GET /app/terms`.
2. Si la versión aceptada no es la vigente, el servidor responde `403 terms_required` en las funciones de grupo, ubicación y WebSocket.
3. La app muestra el texto y envía `POST /users/me/terms` con la versión vigente.
4. El servidor registra la aceptación y desbloquea las funciones.
5. Si el usuario no acepta, puede seguir viendo su perfil y cerrar sesión, pero no usar el grupo.

---

## 13. Pendientes reales

Estos pendientes vienen de la fuente de verdad y **no cambian el contrato v2** descrito aquí. Si alguno se resuelve de otra forma, primero se actualiza `moonorbit-decisiones-v2.md`.

- [ ] ⏳ Validar la paleta y la tipografía con pruebas visuales en dispositivos reales.
- [ ] ⏳ Redactar y revisar el texto legal de términos y privacidad (Ley 29733).
- [ ] ⏳ Definir la retención exacta de logs y respaldos.
- [ ] ⏳ Confirmar la versión mínima de Android (sugerido API 26) y los dispositivos de la familia.
- [ ] ⏳ Medir el consumo real de batería y ajustar intervalos y distancia mínima.
- [ ] ⏳ Decidir si se añade FCM en una versión futura.

No quedan pendientes de JWT, refresh token, `/auth/refresh`, multi-grupo, múltiples administradores, `sharing_enabled`, `location_history`, ubicación por WebSocket ni panel web: todo eso está **fuera de alcance de v1/v2**.











