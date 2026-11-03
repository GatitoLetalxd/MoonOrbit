-- =====================================================================
-- MoonOrbit — Esquema PostgreSQL v2
-- Fuente de verdad: moonorbit-decisiones-v2.md (2026-11-01)
-- Requiere PostgreSQL 13+ (gen_random_uuid() es nativo; no hace falta pgcrypto).
--
-- Este esquema sustituye por completo la propuesta anterior:
--   * Un solo grupo por usuario (sin multi-familia ni selector de grupos).
--   * Un solo administrador activo por grupo (sin cambios de rol).
--   * Sin refresh tokens: token de sesión opaco y solo su SHA-256.
--   * Sin location_history: únicamente la última ubicación conocida.
--   * Sin sharing_enabled: no se comparte "por grupo".
--   * Sin email ni contacto de emergencia en v1.
--
-- Ejecutar sobre una base vacía. No borra datos existentes.
-- Las tablas se crean dentro de una transacción: si algo falla, no queda
-- un esquema a medias.
-- =====================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS citext;

-- ---------------------------------------------------------------------
-- 1. users
--    Cuenta, perfil, avatar y aceptación de términos.
--    No hay email ni contacto de emergencia en v1.
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Login. citext hace que el nombre de usuario no distinga mayúsculas.
  username                citext NOT NULL,
  password_hash           text NOT NULL,                  -- argon2id; nunca la contraseña
  must_change_password    boolean NOT NULL DEFAULT false, -- true tras un reset del administrador

  -- Perfil visible para los miembros del mismo grupo.
  display_name            text NOT NULL,
  phone                   text,                           -- opcional; se muestra en el detalle
  avatar_path             text,                           -- ruta relativa a AVATAR_ROOT, nunca URL pública
  avatar_version          integer NOT NULL DEFAULT 0,     -- se incrementa al reemplazar o borrar la foto

  -- Términos aceptados. La FK se añade después de crear terms_versions.
  accepted_terms_version  text,
  accepted_terms_at       timestamptz,

  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT users_username_key UNIQUE (username),
  CONSTRAINT users_username_format
    CHECK (username::text ~ '^[a-z0-9_]{3,24}$'),
  CONSTRAINT users_display_name_len
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 40),
  CONSTRAINT users_phone_not_blank
    CHECK (phone IS NULL OR btrim(phone) <> ''),
  CONSTRAINT users_avatar_version_nonneg
    CHECK (avatar_version >= 0),
  CONSTRAINT users_terms_pair
    CHECK ((accepted_terms_version IS NULL) = (accepted_terms_at IS NULL))
);

COMMENT ON COLUMN users.username IS
  '3–24 caracteres: minúsculas, números y guion bajo. Inmutable y único sin distinguir mayúsculas.';
COMMENT ON COLUMN users.password_hash IS
  'Hash argon2id. La política de 10–128 caracteres se valida en el servidor; nunca se guarda ni se registra la contraseña en claro.';
COMMENT ON COLUMN users.display_name IS
  'Nombre visible de 2–40 caracteres, sin espacios al inicio/fin.';
COMMENT ON COLUMN users.avatar_path IS
  'Ruta del WebP bajo AVATAR_ROOT (fuera de la raíz pública). NULL = sin foto.';
COMMENT ON COLUMN users.avatar_version IS
  'Versión para caché (Coil/ETag). Se incrementa al reemplazar y al eliminar.';
COMMENT ON COLUMN users.must_change_password IS
  'Si es true, el servidor solo permite GET /users/me, POST /auth/change-password y POST /auth/logout.';

-- ---------------------------------------------------------------------
-- 2. groups
--    El grupo lo crea el propietario del servidor con un script/SQL.
--    No existe endpoint para crear grupos.
--
--    Código de invitación: 8 caracteres en mayúsculas, sin 0/O/1/I.
--    Se guarda sin guion (ABCD2345); la API puede mostrarlo como ABCD-2345.
--    Vence a las 48 h; regenerarlo invalida el anterior de inmediato.
-- ---------------------------------------------------------------------
CREATE TABLE groups (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name                   text NOT NULL,
  invite_code            text NOT NULL,
  invite_code_expires_at timestamptz NOT NULL,            -- siempre obligatorio
  created_by             uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT groups_name_len
    CHECK (char_length(btrim(name)) BETWEEN 2 AND 40),
  CONSTRAINT groups_invite_code_key UNIQUE (invite_code),
  CONSTRAINT groups_invite_code_format
    CHECK (invite_code ~ '^[A-HJ-NP-Z2-9]{8}$'),
  CONSTRAINT groups_invite_expiry
    CHECK (invite_code_expires_at > created_at)
);

COMMENT ON TABLE groups IS
  'Grupo familiar. Un usuario pertenece como máximo a un grupo (ver índice uq_group_members_current_user).';
COMMENT ON COLUMN groups.invite_code IS
  '8 caracteres sin 0/O/1/I. Reutilizable hasta que venza o se regenere.';
COMMENT ON COLUMN groups.created_by IS
  'Administrador que creó el grupo desde el script del servidor; NULL si se creó sin usuario.';

-- ---------------------------------------------------------------------
-- 3. group_members
--    Una fila por (grupo, usuario). Estados:
--      pending  → espera aprobación del administrador
--      active   → miembro activo
--      rejected → rechazado (puede volver a postular al mismo grupo)
--      left     → salió voluntariamente o fue expulsado
--
--    Invariantes:
--      * Un usuario solo puede tener una membresía pending/active a la vez.
--      * Un grupo solo puede tener un admin, y ese admin debe estar active.
--      * El admin no puede quedar en otro estado (CHECK), por lo que no
--        puede salir del grupo ni ser expulsado sin transferir antes.
--      * El backend impide borrar la cuenta del admin mientras lo sea. Si el
--        propietario del servidor necesita hacerlo, primero transfiere el
--        grupo o lo elimina; el script no debe dejar un grupo sin admin.
--      * El límite de 12 miembros activos se valida en el backend, no aquí.
-- ---------------------------------------------------------------------
CREATE TABLE group_members (
  group_id    uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
  role        text NOT NULL DEFAULT 'member',
  status      text NOT NULL DEFAULT 'pending',

  -- joined_at: alta de la solicitud/membresía.
  -- decided_at: aprobación o rechazo; en salidas/expulsiones, cuándo ocurrió.
  joined_at   timestamptz NOT NULL DEFAULT now(),
  decided_at  timestamptz,
  decided_by  uuid REFERENCES users(id) ON DELETE SET NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (group_id, user_id),

  CONSTRAINT group_members_role_check
    CHECK (role IN ('admin', 'member')),
  CONSTRAINT group_members_status_check
    CHECK (status IN ('pending', 'active', 'rejected', 'left')),
  -- El único admin del grupo debe estar activo. Un 'member' puede estar
  -- en cualquier estado (pending, active, rejected, left).
  CONSTRAINT group_members_admin_active
    CHECK (role <> 'admin' OR status = 'active')
);

-- Un grupo por usuario: como máximo una membresía vigente (pending o active)
-- por usuario, aunque tenga filas históricas rejected/left de otros grupos.
CREATE UNIQUE INDEX uq_group_members_current_user
  ON group_members (user_id)
  WHERE status IN ('pending', 'active');

-- Un solo administrador por grupo. El CHECK anterior garantiza que todo
-- admin está active, así que el índice parcial es suficiente.
CREATE UNIQUE INDEX uq_group_members_one_admin
  ON group_members (group_id)
  WHERE role = 'admin' AND status = 'active';

CREATE INDEX idx_group_members_group_status
  ON group_members (group_id, status);
CREATE INDEX idx_group_members_pending
  ON group_members (group_id, joined_at)
  WHERE status = 'pending';
CREATE INDEX idx_group_members_user
  ON group_members (user_id);
CREATE INDEX idx_group_members_cleanup
  ON group_members (updated_at)
  WHERE status IN ('rejected', 'left');

COMMENT ON TABLE group_members IS
  'Membresías. El índice uq_group_members_current_user implementa "un grupo por usuario".';
COMMENT ON COLUMN group_members.role IS
  'admin o member. No hay endpoint de cambio de rol: la transferencia la hace el propietario del servidor.';
COMMENT ON COLUMN group_members.status IS
  'pending, active, rejected o left. left incluye salida voluntaria y expulsión; decided_by distingue quién la decidió.';
COMMENT ON COLUMN group_members.joined_at IS
  'Fecha de alta/solicitud. Para active se conserva la fecha en que se pidió el ingreso.';

-- ---------------------------------------------------------------------
-- 4. sessions
--    Token opaco de 256 bits codificado en Base64URL sin relleno
--    (43 caracteres). El servidor guarda solo SHA-256(token) en
--    hexadecimal minúsculas. Sin JWT y sin refresh tokens.
--    Sesión válida 90 días; last_used_at se actualiza al usar el token,
--    con escritura agrupada como máximo cada 5 min por sesión.
-- ---------------------------------------------------------------------
CREATE TABLE sessions (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash        text NOT NULL,                        -- SHA-256 hex, nunca el token
  device_name       text,
  app_version_code  integer,

  created_at        timestamptz NOT NULL DEFAULT now(),
  last_used_at      timestamptz NOT NULL DEFAULT now(),
  expires_at        timestamptz NOT NULL DEFAULT (now() + interval '90 days'),
  revoked_at        timestamptz,

  CONSTRAINT sessions_token_hash_key UNIQUE (token_hash),
  CONSTRAINT sessions_token_hash_format
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT sessions_expiry
    CHECK (expires_at > created_at),
  CONSTRAINT sessions_app_version
    CHECK (app_version_code IS NULL OR app_version_code > 0)
);

CREATE INDEX idx_sessions_user_active
  ON sessions (user_id, last_used_at DESC)
  WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires
  ON sessions (expires_at);
CREATE INDEX idx_sessions_revoked
  ON sessions (revoked_at)
  WHERE revoked_at IS NOT NULL;

COMMENT ON TABLE sessions IS
  'Sesiones activas/revocadas. Máximo 5 activas por usuario; la más antigua se revoca al superar el límite.';
COMMENT ON COLUMN sessions.token_hash IS
  'SHA-256(token) en hex minúsculas. El token en claro solo existe en el dispositivo.';
COMMENT ON COLUMN sessions.revoked_at IS
  'Revocación inmediata. El WebSocket de esa sesión se cierra con 4403.';

-- ---------------------------------------------------------------------
-- 5. user_state
--    Una sola fila por usuario: última ubicación, batería y estado.
--    No hay historial de rutas.
--
--    status:
--      active            → comparte y hay señal
--      paused            → el usuario pausó desde la app
--      location_disabled → GPS apagado o permiso revocado
--      no_signal         → lo calcula únicamente el servidor
-- ---------------------------------------------------------------------
CREATE TABLE user_state (
  user_id           uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,

  latitude          double precision,
  longitude         double precision,
  accuracy_m        real,
  low_accuracy      boolean NOT NULL DEFAULT false,       -- accuracy_m > 100 m; no se descarta

  battery_level     smallint,
  is_charging       boolean,

  recorded_at       timestamptz,                          -- hora de la medición (UTC), del último punto
  received_at       timestamptz,                          -- hora en que llegó al servidor
  last_seen_at      timestamptz,                          -- último punto o latido; NULL = sin datos todavía

  status            text NOT NULL DEFAULT 'no_signal',
  status_changed_at timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT user_state_lat_range
    CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT user_state_lng_range
    CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  CONSTRAINT user_state_coords_pair
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
  CONSTRAINT user_state_accuracy_nonneg
    CHECK (accuracy_m IS NULL OR accuracy_m >= 0),
  CONSTRAINT user_state_accuracy_requires_coords
    CHECK (accuracy_m IS NULL OR latitude IS NOT NULL),
  CONSTRAINT user_state_recorded_requires_coords
    CHECK (recorded_at IS NULL OR latitude IS NOT NULL),
  CONSTRAINT user_state_battery_range
    CHECK (battery_level IS NULL OR battery_level BETWEEN 0 AND 100),
  CONSTRAINT user_state_status_check
    CHECK (status IN ('active', 'paused', 'location_disabled', 'no_signal', 'sos'))
);

-- Trabajo no_signal: solo mira filas active.
CREATE INDEX idx_user_state_active_seen
  ON user_state (last_seen_at)
  WHERE status = 'active';
-- Orden por actividad reciente (panel/diagnóstico).
CREATE INDEX idx_user_state_last_seen
  ON user_state (last_seen_at DESC NULLS LAST);

COMMENT ON TABLE user_state IS
  'Estado actual, una fila por usuario. Sustituye a location_history, que no existe en v2.';
COMMENT ON COLUMN user_state.low_accuracy IS
  'Calculado por el servidor: accuracy_m > 100. La ubicación imprecisa se conserva y se muestra con aviso.';
COMMENT ON COLUMN user_state.battery_level IS
  'NULL = desconocido. Batería baja a partir de 15 %.';
COMMENT ON COLUMN user_state.status IS
  'no_signal solo lo escribe el trabajo del servidor; la app solo puede enviar active, paused o location_disabled.';

-- ---------------------------------------------------------------------
-- 6. app_releases
--    GET /app/version:
--      latest                 = mayor version_code
--      minSupportedVersionCode = mayor version_code con is_min_supported = true
--    Si no hay ninguna fila marcada, minSupportedVersionCode = 1.
--    Una fila con is_min_supported = true marca el piso mínimo soportado.
--    Las filas con is_min_supported = false son versiones publicadas opcionales.
-- ---------------------------------------------------------------------
CREATE TABLE app_releases (
  version_code     integer PRIMARY KEY,
  version_name     text NOT NULL,
  apk_url          text NOT NULL,
  apk_sha256       text NOT NULL,                         -- SHA-256 hex del APK
  size_bytes       bigint NOT NULL,
  release_notes    text,
  is_min_supported boolean NOT NULL DEFAULT false,
  published_at     timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT app_releases_version_positive
    CHECK (version_code > 0),
  CONSTRAINT app_releases_sha256_format
    CHECK (apk_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT app_releases_size_positive
    CHECK (size_bytes > 0),
  CONSTRAINT app_releases_apk_url_not_blank
    CHECK (btrim(apk_url) <> '')
);

-- Como máximo una fila puede ser el piso mínimo. Para subir el piso se
-- desmarca la anterior y se marca la nueva dentro de la misma transacción.
CREATE UNIQUE INDEX uq_app_releases_min_supported
  ON app_releases (is_min_supported)
  WHERE is_min_supported;
CREATE INDEX idx_app_releases_published
  ON app_releases (published_at DESC);

COMMENT ON COLUMN app_releases.is_min_supported IS
  'Marca la versión mínima soportada (el mayor version_code con true). No significa "esta actualización es obligatoria" por sí sola.';

-- ---------------------------------------------------------------------
-- 7. notices
--    Avisos generales de GET /app/notices. Se publican por SQL/CLI.
--    La app recuerda en DataStore cuáles descartó.
-- ---------------------------------------------------------------------
CREATE TABLE notices (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title       text NOT NULL,
  message     text NOT NULL,
  severity    text NOT NULL DEFAULT 'info',
  dismissible boolean NOT NULL DEFAULT true,
  starts_at   timestamptz NOT NULL DEFAULT now(),
  ends_at     timestamptz,                                -- NULL = sin fecha de fin
  created_at  timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT notices_severity_check
    CHECK (severity IN ('info', 'important')),
  CONSTRAINT notices_title_not_blank
    CHECK (btrim(title) <> ''),
  CONSTRAINT notices_message_not_blank
    CHECK (btrim(message) <> ''),
  CONSTRAINT notices_window
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_notices_window
  ON notices (starts_at, ends_at);
CREATE INDEX idx_notices_cleanup
  ON notices (ends_at)
  WHERE ends_at IS NOT NULL;

COMMENT ON TABLE notices IS
  'Avisos generales. Activo si starts_at <= now() y (ends_at IS NULL OR ends_at > now()).';

-- ---------------------------------------------------------------------
-- 8. terms_versions
--    Términos versionados. La versión vigente es la de mayor effective_at
--    que ya haya entrado en vigor. No modificar una versión publicada:
--    crear una nueva.
-- ---------------------------------------------------------------------
CREATE TABLE terms_versions (
  version      text PRIMARY KEY,                          -- p. ej. '2026-11-01'
  url          text NOT NULL,                             -- texto legal publicado
  text_hash    text NOT NULL,                             -- SHA-256 hex del texto
  effective_at timestamptz NOT NULL DEFAULT now(),
  created_at   timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT terms_versions_version_not_blank
    CHECK (btrim(version) <> '' AND char_length(version) <= 32),
  CONSTRAINT terms_versions_url_not_blank
    CHECK (btrim(url) <> ''),
  CONSTRAINT terms_versions_hash_format
    CHECK (text_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_terms_versions_effective
  ON terms_versions (effective_at DESC);

COMMENT ON TABLE terms_versions IS
  'Versiones de términos. Si la versión aceptada por un usuario no es la vigente, el servidor bloquea funciones hasta que acepte.';

-- La versión aceptada debe existir. No se puede borrar una versión aceptada.
ALTER TABLE users
  ADD CONSTRAINT users_accepted_terms_fk
  FOREIGN KEY (accepted_terms_version)
  REFERENCES terms_versions (version)
  ON UPDATE CASCADE
  ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- 9. updated_at automático
--    Solo users, groups y group_members tienen updated_at.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION moonorbit_set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER users_set_updated_at
  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION moonorbit_set_updated_at();

CREATE TRIGGER groups_set_updated_at
  BEFORE UPDATE ON groups
  FOR EACH ROW EXECUTE FUNCTION moonorbit_set_updated_at();

CREATE TRIGGER group_members_set_updated_at
  BEFORE UPDATE ON group_members
  FOR EACH ROW EXECUTE FUNCTION moonorbit_set_updated_at();

COMMIT;

-- =====================================================================
-- CONSULTAS DE EJEMPLO PARA EL BACKEND
-- No se ejecutan al aplicar el esquema; son referencia y usan $1, $2…
-- =====================================================================


-- =====================================================================
-- A. AUTENTICACIÓN Y SESIONES
-- =====================================================================

-- A.1) Login: buscar por usuario (citext, sin distinguir mayúsculas).
--      El backend verifica argon2id; nunca compara contraseñas en SQL.
--
-- SELECT id, password_hash, must_change_password
--   FROM users
--  WHERE username = $1;
-- -- Sin fila → 401 invalid_credentials (mismo mensaje que contraseña incorrecta).

-- A.2) Validar una petición REST o el handshake WebSocket.
--      El backend calcula SHA-256(token) y lo pasa como $1.
--
-- SELECT s.id AS session_id, s.user_id, s.expires_at,
--        u.username, u.must_change_password
--   FROM sessions s
--   JOIN users u ON u.id = s.user_id
--  WHERE s.token_hash = $1
--    AND s.revoked_at IS NULL
--    AND s.expires_at > now();
-- -- Sin fila → 401. Si revoked_at no es NULL → 4403 en WebSocket.
--
-- UPDATE sessions
--    SET last_used_at = now()
--  WHERE id = $session_id
--    AND last_used_at < now() - interval '5 minutes';

-- A.3) Crear sesión tras login/registro. expires_at usa el DEFAULT de 90 días.
--
-- INSERT INTO sessions (user_id, token_hash, device_name, app_version_code)
-- VALUES ($1, $2, $3, $4)
-- RETURNING id, expires_at;
--
-- -- Máximo 5 sesiones activas: revocar las más antiguas sobrantes.
-- WITH keep AS (
--   SELECT id
--     FROM sessions
--    WHERE user_id = $1
--      AND revoked_at IS NULL
--      AND expires_at > now()
--    ORDER BY last_used_at DESC, created_at DESC
--    LIMIT 5
-- )
-- UPDATE sessions
--    SET revoked_at = now()
--  WHERE user_id = $1
--    AND revoked_at IS NULL
--    AND expires_at > now()
--    AND id NOT IN (SELECT id FROM keep);

-- A.4) GET /auth/sessions
--
-- SELECT id, device_name, app_version_code,
--        created_at, last_used_at, expires_at
--   FROM sessions
--  WHERE user_id = $1
--    AND revoked_at IS NULL
--    AND expires_at > now()
--  ORDER BY last_used_at DESC;

-- A.5) POST /auth/logout y POST /auth/logout-others
--
-- UPDATE sessions SET revoked_at = now()
--  WHERE id = $session_id AND user_id = $user_id AND revoked_at IS NULL;
--
-- UPDATE sessions SET revoked_at = now()
--  WHERE user_id = $user_id
--    AND id <> $current_session_id
--    AND revoked_at IS NULL;

-- A.6) POST /auth/change-password (el usuario conoce su contraseña actual).
--
-- UPDATE users
--    SET password_hash = $2,
--        must_change_password = false
--  WHERE id = $1;

-- A.7) Si el administrador olvida su contraseña, no hay endpoint de
--      recuperación: el propietario del servidor ejecuta directamente el
--      UPDATE de A.6 con una contraseña temporal, marca
--      must_change_password = true y revoca todas sus sesiones (A.8).
--
-- BEGIN;
-- UPDATE users
--    SET password_hash = $temporary_password_hash,
--        must_change_password = true
--  WHERE id = $admin_user_id;
-- UPDATE sessions
--    SET revoked_at = now()
--  WHERE user_id = $admin_user_id
--    AND revoked_at IS NULL;
-- COMMIT;

-- A.8) POST /groups/{groupId}/members/{userId}/reset-password
--      Solo el admin del grupo, y solo sobre un member no admin.
--      La contraseña temporal se devuelve una vez; aquí solo se guarda su hash.
--
-- BEGIN;
--
-- -- Autorización: $admin es admin activo de $group y $target es member activo.
-- SELECT u.id
--   FROM users u
--   JOIN group_members gm
--     ON gm.user_id = u.id
--    AND gm.group_id = $group
--    AND gm.status = 'active'
--    AND gm.role = 'member'
--  WHERE u.id = $target
--    AND EXISTS (
--      SELECT 1
--        FROM group_members a
--       WHERE a.group_id = $group
--         AND a.user_id = $admin
--         AND a.role = 'admin'
--         AND a.status = 'active'
--    );
--
-- UPDATE users
--    SET password_hash = $temporary_password_hash,
--        must_change_password = true
--  WHERE id = $target;
--
-- -- Revocar todas las sesiones del miembro; el WebSocket recibe 4403.
-- UPDATE sessions
--    SET revoked_at = now()
--  WHERE user_id = $target
--    AND revoked_at IS NULL;
--
-- COMMIT;

-- A.9) Perfil y avatar.
--
-- -- GET /users/me
-- SELECT id, username, display_name, phone,
--        avatar_version, accepted_terms_version, accepted_terms_at,
--        must_change_password, created_at
--   FROM users
--  WHERE id = $user_id;
--
-- -- PATCH /users/me: el backend construye el SET solo con los campos enviados.
-- UPDATE users
--    SET display_name = $2,
--        phone = $3
--  WHERE id = $1
-- RETURNING id, username, display_name, phone, avatar_version;
--
-- -- PUT /users/me/avatar: después de escribir y validar el WebP nuevo.
-- -- Convención: avatars/<userId>/<avatarVersion+1>.webp (relativa a AVATAR_ROOT).
-- UPDATE users
--    SET avatar_path = $2,
--        avatar_version = avatar_version + 1
--  WHERE id = $1
-- RETURNING avatar_version;
-- -- Después de esta actualización se borra el archivo anterior.
--
-- -- DELETE /users/me/avatar
-- UPDATE users
--    SET avatar_path = NULL,
--        avatar_version = avatar_version + 1
--  WHERE id = $1
-- RETURNING avatar_version;
--
-- -- DELETE /users/me: el admin no puede borrar su cuenta mientras lo sea.
-- SELECT 1
--   FROM group_members
--  WHERE user_id = $user_id AND role = 'admin' AND status = 'active';
-- -- Si devuelve fila → 409 admin_transfer_required.
-- DELETE FROM users WHERE id = $user_id;
-- -- ON DELETE CASCADE borra sesiones, user_state y membresías; el archivo de
-- -- avatar queda huérfano y lo recoge la limpieza diaria (E.4).


-- =====================================================================
-- B. GRUPOS, INVITACIONES Y MEMBRESÍAS
-- =====================================================================

-- B.1) GET /groups: devuelve como máximo una fila (pending o active).
--
-- SELECT g.id AS group_id, g.name, gm.role, gm.status, g.created_at,
--        CASE WHEN gm.status = 'active' THEN (
--          SELECT count(*)
--            FROM group_members m
--           WHERE m.group_id = g.id AND m.status = 'active'
--        ) END AS member_count
--   FROM group_members gm
--   JOIN groups g ON g.id = gm.group_id
--  WHERE gm.user_id = $1
--    AND gm.status IN ('pending', 'active');

-- B.2) POST /auth/register con código (transacción completa).
--      El backend valida antes: formato, contraseña, términos vigentes.
--
-- BEGIN;
--
-- -- Código vigente. FOR UPDATE serializa registros concurrentes del grupo.
-- SELECT g.id
--   FROM groups g
--  WHERE g.invite_code = $invite_code
--    AND g.invite_code_expires_at > now()
--  FOR UPDATE;
-- -- Sin fila → 400 invalid_invite_code (mensaje genérico).
--
-- -- Capacidad: solo cuentan los activos. Si >= 12 → 409 group_full.
-- SELECT count(*) AS active_members
--   FROM group_members
--  WHERE group_id = $group_id
--    AND status = 'active';
--
-- -- Usuario nuevo. Un username repetido lo detecta users_username_key → 409.
-- INSERT INTO users (username, password_hash, display_name, phone,
--                    accepted_terms_version, accepted_terms_at)
-- VALUES ($username, $password_hash, $display_name, $phone, $terms_version, now())
-- RETURNING id;
--
-- -- Estado inicial: no_signal, sin ubicación ni last_seen_at.
-- INSERT INTO user_state (user_id) VALUES ($user_id);
--
-- -- Membresía pendiente. uq_group_members_current_user impide una segunda
-- -- membresía pending/active para el mismo usuario.
-- INSERT INTO group_members (group_id, user_id, role, status)
-- VALUES ($group_id, $user_id, 'member', 'pending');
--
-- -- Sesión inicial.
-- INSERT INTO sessions (user_id, token_hash, device_name, app_version_code)
-- VALUES ($user_id, $token_hash, $device_name, $app_version_code)
-- RETURNING id, expires_at;
--
-- COMMIT;

-- B.3) POST /groups/join para un usuario ya registrado.
--      Si la fila existía como rejected/left, se reutiliza y vuelve a pending.
--      Si ya estaba pending/active, no se actualiza (0 filas → 409 already_member).
--      Si tiene una membresía pending/active en otro grupo, el índice
--      uq_group_members_current_user lanza unique_violation → 409 already_member.
--
-- BEGIN;
--
-- SELECT g.id
--   FROM groups g
--  WHERE g.invite_code = $invite_code
--    AND g.invite_code_expires_at > now()
--  FOR UPDATE;
--
-- INSERT INTO group_members (group_id, user_id, role, status, joined_at)
-- VALUES ($group_id, $user_id, 'member', 'pending', now())
-- ON CONFLICT (group_id, user_id) DO UPDATE
--    SET status = 'pending',
--        role = 'member',
--        joined_at = now(),
--        decided_at = NULL,
--        decided_by = NULL
--  WHERE group_members.status IN ('rejected', 'left');
--
-- COMMIT;

-- B.4) GET /groups/{groupId}/invite
--
-- SELECT g.invite_code, g.invite_code_expires_at
--   FROM groups g
--  WHERE g.id = $group_id
--    AND EXISTS (
--      SELECT 1 FROM group_members
--       WHERE group_id = $group_id AND user_id = $admin
--         AND role = 'admin' AND status = 'active'
--    );

-- B.5) POST /groups/{groupId}/invite/regenerate
--      Generar un código nuevo y reintentar si choca con groups_invite_code_key.
--
-- UPDATE groups
--    SET invite_code = $new_code,
--        invite_code_expires_at = now() + interval '48 hours'
--  WHERE id = $group_id
--    AND EXISTS (
--      SELECT 1 FROM group_members
--       WHERE group_id = $group_id AND user_id = $admin
--         AND role = 'admin' AND status = 'active'
--    )
-- RETURNING invite_code, invite_code_expires_at;

-- B.6) GET /groups/{groupId}/pending (solo admin)
--
-- SELECT u.id AS user_id, u.username, u.display_name,
--        u.avatar_version, gm.joined_at
--   FROM group_members gm
--   JOIN users u ON u.id = gm.user_id
--  WHERE gm.group_id = $group_id
--    AND gm.status = 'pending'
--    AND EXISTS (
--      SELECT 1 FROM group_members a
--       WHERE a.group_id = $group_id AND a.user_id = $admin
--         AND a.role = 'admin' AND a.status = 'active'
--    )
--  ORDER BY gm.joined_at;

-- B.7) Aprobar miembro. Bloquear el grupo serializa aprobaciones y respeta el tope.
--
-- BEGIN;
--
-- -- Autorización: $admin debe ser el admin activo del grupo.
-- SELECT 1
--   FROM group_members
--  WHERE group_id = $group_id
--    AND user_id = $admin
--    AND role = 'admin'
--    AND status = 'active';
--
-- SELECT id FROM groups WHERE id = $group_id FOR UPDATE;
--
-- SELECT count(*) AS active_members
--   FROM group_members
--  WHERE group_id = $group_id AND status = 'active';
-- -- Si >= 12 → ROLLBACK y 409 group_full.
--
-- UPDATE group_members
--    SET status = 'active',
--        decided_at = now(),
--        decided_by = $admin
--  WHERE group_id = $group_id
--    AND user_id = $target
--    AND status = 'pending'
--    AND role = 'member';
-- -- 0 filas → 404 not_found (no revelar si existe).
--
-- COMMIT;

-- B.8) Rechazar miembro. La fila queda rejected y libera al usuario para
--      unirse a otro grupo o volver a postular al mismo.
--
-- UPDATE group_members
--    SET status = 'rejected',
--        decided_at = now(),
--        decided_by = $admin
--  WHERE group_id = $group_id
--    AND user_id = $target
--    AND status = 'pending'
--    AND role = 'member'
--    AND EXISTS (
--      SELECT 1 FROM group_members a
--       WHERE a.group_id = $group_id AND a.user_id = $admin
--         AND a.role = 'admin' AND a.status = 'active'
--    );

-- B.9) Salir del grupo (DELETE /groups/{groupId}/members/me).
--      El admin no puede salir: su CHECK exige status = active.
--
-- SELECT role
--   FROM group_members
--  WHERE group_id = $group_id AND user_id = $user_id AND status = 'active';
-- -- Si role = 'admin' → 409 admin_transfer_required.
--
-- UPDATE group_members
--    SET status = 'left',
--        decided_at = now(),
--        decided_by = $user_id
--  WHERE group_id = $group_id
--    AND user_id = $user_id
--    AND role = 'member'
--    AND status = 'active';

-- B.10) Expulsar miembro (DELETE /groups/{groupId}/members/{userId}).
--       Se conserva la fila como left para auditoría/limpieza.
--
-- UPDATE group_members
--    SET status = 'left',
--        decided_at = now(),
--        decided_by = $admin
--  WHERE group_id = $group_id
--    AND user_id = $target
--    AND role = 'member'
--    AND status = 'active'
--    AND EXISTS (
--      SELECT 1 FROM group_members a
--       WHERE a.group_id = $group_id AND a.user_id = $admin
--         AND a.role = 'admin' AND a.status = 'active'
--    );

-- B.11) PATCH /groups/{groupId}: renombrar (solo admin).
--
-- UPDATE groups
--    SET name = $name
--  WHERE id = $group_id
--    AND EXISTS (
--      SELECT 1 FROM group_members
--       WHERE group_id = $group_id AND user_id = $admin
--         AND role = 'admin' AND status = 'active'
--    )
-- RETURNING id, name;

-- B.12) Script del propietario del servidor: crear el primer grupo y su admin.
--       No hay endpoint para esto. El usuario admin debe existir antes.
--       Ejecutar en una transacción.
--
-- BEGIN;
-- INSERT INTO groups (name, invite_code, invite_code_expires_at, created_by)
-- VALUES ($name, $invite_code, now() + interval '48 hours', $admin_user_id)
-- RETURNING id;
--
-- INSERT INTO group_members (group_id, user_id, role, status, joined_at,
--                            decided_at, decided_by)
-- VALUES ($group_id, $admin_user_id, 'admin', 'active', now(), now(), $admin_user_id);
--
-- INSERT INTO user_state (user_id) VALUES ($admin_user_id)
-- ON CONFLICT (user_id) DO NOTHING;
-- COMMIT;

-- B.13) Transferencia de administrador (solo script del propietario del
--       servidor; no hay endpoint). El orden importa: primero se degrada al
--       admin actual y después se promueve al nuevo, porque el índice
--       uq_group_members_one_admin no admite dos admins ni por un instante.
--
-- BEGIN;
-- UPDATE group_members
--    SET role = 'member'
--  WHERE group_id = $group_id
--    AND user_id = $old_admin
--    AND role = 'admin'
--    AND status = 'active';
--
-- UPDATE group_members
--    SET role = 'admin'
--  WHERE group_id = $group_id
--    AND user_id = $new_admin
--    AND role = 'member'
--    AND status = 'active';
--
-- -- Verificar que ambos UPDATE hayan afectado 1 fila; si alguno afectó 0,
-- -- ROLLBACK para no dejar el grupo sin administrador.
-- COMMIT;


-- =====================================================================
-- C. UBICACIÓN, LATIDO Y ESTADO
-- =====================================================================
-- Todos estos endpoints exigen sesión válida y membresía active; el backend
-- lo verifica antes de tocar user_state. La app nunca escribe directo el
-- estado no_signal.

-- C.1) UPSERT de la última ubicación.
--      El backend recibe el lote (1..50 puntos, <= 64 KB), descarta puntos con
--      más de 24 h, ajusta un recordedAt con más de 5 min en el futuro a now(),
--      elige el de mayor recordedAt y ejecuta esta consulta con ese punto.
--      Un punto más viejo que el guardado no actualiza nada (0 filas).
--      Si el estado es paused o location_disabled, el lote se ignora por
--      completo (0 filas): los puntos en cola no reactivan esos estados.
--      Para reanudar hace falta POST /me/status con status = active.
--
-- INSERT INTO user_state AS s (
--   user_id, latitude, longitude, accuracy_m, low_accuracy,
--   battery_level, is_charging, recorded_at, received_at,
--   last_seen_at, status, status_changed_at
-- )
-- VALUES (
--   $user_id, $latitude, $longitude, $accuracy_m,
--   ($accuracy_m IS NOT NULL AND $accuracy_m > 100),
--   $battery_level, $is_charging, $recorded_at, now(),
--   now(), 'active', now()
-- )
-- ON CONFLICT (user_id) DO UPDATE SET
--   latitude = EXCLUDED.latitude,
--   longitude = EXCLUDED.longitude,
--   accuracy_m = EXCLUDED.accuracy_m,
--   low_accuracy = EXCLUDED.low_accuracy,
--   battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
--   is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging),
--   recorded_at = EXCLUDED.recorded_at,
--   received_at = now(),
--   last_seen_at = now(),
--   -- Un paused/location_disabled explícito manda: los puntos en cola no lo pisan.
--   status = CASE
--              WHEN s.status IN ('paused', 'location_disabled') THEN s.status
--              ELSE 'active'
--            END,
--   status_changed_at = CASE
--              WHEN s.status IN ('paused', 'location_disabled') THEN s.status_changed_at
--              WHEN s.status <> 'active' THEN now()
--              ELSE s.status_changed_at
--            END
-- WHERE s.status NOT IN ('paused', 'location_disabled')
--   AND (s.recorded_at IS NULL OR EXCLUDED.recorded_at >= s.recorded_at);

-- C.2) POST /heartbeat: batería y last_seen_at sin ubicación.
--      Si el estado era no_signal, vuelve a active; nunca pisa paused/location_disabled.
--
-- INSERT INTO user_state AS s (
--   user_id, battery_level, is_charging, last_seen_at, status, status_changed_at
-- )
-- VALUES ($user_id, $battery_level, $is_charging, now(), 'active', now())
-- ON CONFLICT (user_id) DO UPDATE SET
--   battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
--   is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging),
--   last_seen_at = now(),
--   status = CASE WHEN s.status = 'no_signal' THEN 'active' ELSE s.status END,
--   status_changed_at = CASE
--                          WHEN s.status = 'no_signal' THEN now()
--                          ELSE s.status_changed_at
--                        END;

-- C.3) POST /me/status. La app solo puede enviar active, paused o
--      location_disabled; no_signal lo calcula el servidor.
--
-- INSERT INTO user_state AS s (
--   user_id, status, status_changed_at, last_seen_at, battery_level, is_charging
-- )
-- VALUES ($user_id, $status, now(), now(), $battery_level, $is_charging)
-- ON CONFLICT (user_id) DO UPDATE SET
--   status = EXCLUDED.status,
--   status_changed_at = CASE
--                          WHEN s.status <> EXCLUDED.status THEN now()
--                          ELSE s.status_changed_at
--                        END,
--   last_seen_at = now(),
--   battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
--   is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging);

-- C.4) Trabajo no_signal (cada minuto). Devuelve los usuarios afectados para
--      emitir member_state por WebSocket.
--
-- UPDATE user_state
--    SET status = 'no_signal',
--        status_changed_at = now()
--  WHERE status = 'active'
--    AND last_seen_at < now() - interval '25 minutes'
-- RETURNING user_id;

-- C.5) GET /groups/{groupId}/snapshot
--      Primero el backend verifica que quien consulta sea active en el grupo.
--      Solo se listan miembros active; los pending los ve el admin en /pending.
--
-- SELECT 1
--   FROM group_members
--  WHERE group_id = $group_id AND user_id = $viewer AND status = 'active';
--
-- SELECT
--   u.id AS user_id,
--   u.display_name,
--   u.phone,
--   u.avatar_version,
--   gm.role,
--   gm.joined_at,
--   s.latitude,
--   s.longitude,
--   s.accuracy_m,
--   s.low_accuracy,
--   s.battery_level,
--   s.is_charging,
--   s.recorded_at,
--   s.last_seen_at,
--   COALESCE(s.status, 'no_signal') AS status,
--   s.status_changed_at
-- FROM group_members gm
-- JOIN users u ON u.id = gm.user_id
-- LEFT JOIN user_state s ON s.user_id = u.id
-- WHERE gm.group_id = $group_id
--   AND gm.status = 'active'
-- ORDER BY CASE gm.role WHEN 'admin' THEN 0 ELSE 1 END, u.display_name;

-- C.6) Autorización de GET /users/{userId}/avatar.
--      El propio usuario siempre puede ver su avatar, incluso si está pending,
--      left o rejected. Para ver el avatar ajeno hace falta compartir el grupo
--      con ambos active (en v2 solo puede ser el mismo grupo).
--
-- SELECT 1
--  WHERE $viewer = $target
-- UNION ALL
-- SELECT 1
--   FROM group_members viewer
--   JOIN group_members target ON target.group_id = viewer.group_id
--  WHERE viewer.user_id = $viewer AND viewer.status = 'active'
--    AND target.user_id = $target AND target.status = 'active'
--    AND viewer.user_id <> target.user_id;
--
-- SELECT avatar_path, avatar_version
--   FROM users
--  WHERE id = $target;


-- =====================================================================
-- D. APP, TÉRMINOS Y AVISOS
-- =====================================================================

-- D.1) GET /app/version: última versión y versión mínima soportada.
--      Si no hay ninguna fila con is_min_supported = true, el piso es 1.
--      No se usa la última versión como piso: una versión recién publicada
--      no debe bloquear a los dispositivos que todavía no la instalaron.
--
-- SELECT
--   latest.version_code,
--   latest.version_name,
--   latest.apk_url,
--   latest.apk_sha256,
--   latest.size_bytes,
--   latest.release_notes,
--   latest.published_at,
--   COALESCE(floor.min_supported, 1) AS min_supported_version_code
-- FROM (
--   SELECT *
--     FROM app_releases
--    ORDER BY version_code DESC
--    LIMIT 1
-- ) AS latest
-- CROSS JOIN (
--   SELECT max(version_code) AS min_supported
--     FROM app_releases
--    WHERE is_min_supported
-- ) AS floor;

-- D.2) GET /app/terms: versión vigente (la última ya efectiva).
--
-- SELECT version, url, text_hash, effective_at
--   FROM terms_versions
--  WHERE effective_at <= now()
--  ORDER BY effective_at DESC, version DESC
--  LIMIT 1;

-- D.3) POST /users/me/terms: aceptar una versión que ya esté vigente.
--
-- UPDATE users u
--    SET accepted_terms_version = $version,
--        accepted_terms_at = now()
--  WHERE u.id = $user_id
--    AND EXISTS (
--      SELECT 1
--        FROM terms_versions t
--       WHERE t.version = $version
--         AND t.effective_at <= now()
--    )
-- RETURNING accepted_terms_version, accepted_terms_at;
-- -- 0 filas → 403 terms_required con details.currentVersion.

-- D.4) GET /app/notices: avisos activos (público, sin sesión).
--
-- SELECT id, title, message, severity, dismissible, starts_at, ends_at
--   FROM notices
--  WHERE starts_at <= now()
--    AND (ends_at IS NULL OR ends_at > now())
--  ORDER BY (severity = 'important') DESC, starts_at DESC;

-- D.5) Publicar un aviso por SQL/CLI (no hay panel web).
--
-- INSERT INTO notices (title, message, severity, dismissible, starts_at, ends_at)
-- VALUES ($title, $message, $severity, $dismissible, now(), $ends_at)
-- RETURNING id;

-- D.6) Publicar una versión de la app y/o mover el piso mínimo.
--      Para subir el piso: quitar is_min_supported de la fila anterior y
--      ponerlo en la nueva, en una transacción.
--
-- BEGIN;
-- UPDATE app_releases SET is_min_supported = false WHERE is_min_supported;
-- INSERT INTO app_releases (version_code, version_name, apk_url, apk_sha256,
--                           size_bytes, release_notes, is_min_supported)
-- VALUES ($version_code, $version_name, $apk_url, $apk_sha256,
--         $size_bytes, $release_notes, true);
-- COMMIT;

-- D.7) Dato mínimo antes del primer registro: una versión de términos.
--      El hash de ejemplo debe reemplazarse por el SHA-256 real del texto.
--
-- INSERT INTO terms_versions (version, url, text_hash, effective_at)
-- VALUES ('2026-11-01',
--         'https://orbit.moondev.online/terminos/2026-11-01',
--         repeat('0', 64),
--         '2026-11-01T00:00:00Z');


-- =====================================================================
-- E. TRABAJOS DE LIMPIEZA
-- =====================================================================

-- E.1) Diario: sesiones vencidas o revocadas hace más de 30 días.
--      La autenticación ya las rechaza aunque todavía existan.
--
-- DELETE FROM sessions
--  WHERE expires_at < now() - interval '30 days'
--     OR (revoked_at IS NOT NULL AND revoked_at < now() - interval '30 days');

-- E.2) Diario: avisos terminados hace más de 30 días.
--
-- DELETE FROM notices
--  WHERE ends_at IS NOT NULL
--    AND ends_at < now() - interval '30 days';

-- E.3) Diario: membresías rechazadas o salidas hace más de 90 días.
--      Borrarlas no afecta a miembros activos; el usuario conserva su cuenta
--      y puede volver a unirse con un código.
--
-- DELETE FROM group_members
--  WHERE status IN ('rejected', 'left')
--    AND updated_at < now() - interval '90 days';

-- E.4) Diario: avatares huérfanos (conceptual).
--      avatar_path apunta a archivos en disco, así que la limpieza combina SQL
--      y sistema de archivos:
--        1. Obtener las rutas referenciadas:
--             SELECT avatar_path FROM users WHERE avatar_path IS NOT NULL;
--        2. Recorrer AVATAR_ROOT y borrar los *.webp que no estén en ese
--           conjunto y tengan más de 24 h de antigüedad.
--      Al reemplazar una foto: escribir el archivo nuevo → actualizar la BD →
--      borrar el anterior. Si falla el último paso, este trabajo lo recoge.
--      Nunca borrar un archivo referenciado por users.avatar_path.
--
-- SELECT id, avatar_path, avatar_version
--   FROM users
--  WHERE avatar_path IS NOT NULL;

-- E.5) Mensual: APKs antiguos (conceptual).
--      Se conservan los archivos de la última versión y del piso mínimo;
--      el resto puede borrarse del disco. Las filas de app_releases se
--      conservan para historial y para /app/version.
--
-- SELECT version_code, apk_url
--   FROM app_releases
--  ORDER BY version_code DESC;

-- =====================================================================
-- F. VERIFICACIÓN DE INVARIANTES (diagnóstico; deben devolver 0 filas)
-- =====================================================================

-- F.1) Usuarios con más de una membresía pending/active (debe ser imposible).
--
-- SELECT user_id, count(*) AS vigentes
--   FROM group_members
--  WHERE status IN ('pending', 'active')
--  GROUP BY user_id
-- HAVING count(*) > 1;

-- F.2) Grupos con más de un admin (debe ser imposible).
--
-- SELECT group_id, count(*) AS admins
--   FROM group_members
--  WHERE role = 'admin' AND status = 'active'
--  GROUP BY group_id
-- HAVING count(*) > 1;

-- F.3) Administradores que no estén active (debe ser imposible).
--
-- SELECT group_id, user_id, status
--   FROM group_members
--  WHERE role = 'admin' AND status <> 'active';
