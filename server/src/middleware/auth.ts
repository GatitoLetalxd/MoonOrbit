import { FastifyRequest, FastifyReply } from 'fastify';
import { query } from '../db.js';
import { hashToken } from '../utils/crypto.js';
import { sendError } from '../utils/errors.js';
import { MembershipStatus, UserRole } from '../types/index.js';

export interface AuthenticatedUser {
  id: string;
  username: string;
  displayName: string;
  phone: string | null;
  avatarPath: string | null;
  avatarVersion: number;
  mustChangePassword: boolean;
  acceptedTermsVersion: string | null;
  acceptedTermsAt: Date | null;
  createdAt: Date;
}

export interface AuthenticatedSession {
  id: string;
  userId: string;
  deviceName: string | null;
  appVersionCode: number | null;
  lastUsedAt: Date;
  expiresAt: Date;
}

export interface AuthenticatedMembership {
  groupId: string;
  groupName: string;
  role: UserRole;
  status: MembershipStatus;
  joinedAt: Date;
}

declare module 'fastify' {
  interface FastifyRequest {
    user?: AuthenticatedUser;
    session?: AuthenticatedSession;
    membership?: AuthenticatedMembership;
  }
}

export async function getCurrentTermsVersion(): Promise<string | null> {
  const res = await query<{ version: string }>(
    `SELECT version FROM terms_versions WHERE effective_at <= now() ORDER BY effective_at DESC, version DESC LIMIT 1;`
  );
  return res.rows[0]?.version || null;
}

export async function authenticate(request: FastifyRequest, reply: FastifyReply) {
  const authHeader = request.headers.authorization;
  let token: string | undefined;

  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.slice(7).trim();
  } else if ((request.query as any)?.token) {
    token = String((request.query as any).token).trim();
  }

  if (!token || token.length < 32) {
    reply.header('WWW-Authenticate', 'Bearer');
    return sendError(reply, 401, 'invalid_token', 'Token de sesión ausente o con formato incorrecto.');
  }

  const tokenHash = hashToken(token);

  // Look up session + user
  const sessionRes = await query(
    `SELECT s.id AS session_id, s.user_id, s.device_name, s.app_version_code,
            s.created_at AS session_created_at, s.last_used_at, s.expires_at, s.revoked_at,
            u.id AS u_id, u.username, u.display_name, u.phone, u.avatar_path,
            u.avatar_version, u.must_change_password, u.accepted_terms_version,
            u.accepted_terms_at, u.created_at AS user_created_at
       FROM sessions s
       JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = $1;`,
    [tokenHash]
  );

  if (sessionRes.rowCount === 0) {
    reply.header('WWW-Authenticate', 'Bearer');
    return sendError(reply, 401, 'invalid_token', 'Token de sesión inexistente.');
  }

  const row = sessionRes.rows[0];

  if (row.revoked_at) {
    return sendError(reply, 401, 'session_revoked', 'La sesión ha sido revocada.');
  }

  if (new Date(row.expires_at) <= new Date()) {
    return sendError(reply, 401, 'session_expired', 'La sesión ha vencido.');
  }

  // Update last_used_at throttled to max once every 5 minutes
  const lastUsed = new Date(row.last_used_at).getTime();
  const now = Date.now();
  if (now - lastUsed > 5 * 60 * 1000) {
    query(`UPDATE sessions SET last_used_at = now() WHERE id = $1;`, [row.session_id]).catch((err) =>
      console.error('[DB] Failed to update session last_used_at', err)
    );
  }

  const user: AuthenticatedUser = {
    id: row.u_id,
    username: row.username,
    displayName: row.display_name,
    phone: row.phone,
    avatarPath: row.avatar_path,
    avatarVersion: row.avatar_version,
    mustChangePassword: row.must_change_password,
    acceptedTermsVersion: row.accepted_terms_version,
    acceptedTermsAt: row.accepted_terms_at,
    createdAt: row.user_created_at,
  };

  const session: AuthenticatedSession = {
    id: row.session_id,
    userId: row.user_id,
    deviceName: row.device_name,
    appVersionCode: row.app_version_code,
    lastUsedAt: row.last_used_at,
    expiresAt: row.expires_at,
  };

  request.user = user;
  request.session = session;

  const url = request.url.split('?')[0];

  // 4. must_change_password check
  if (user.mustChangePassword) {
    const allowed = ['/api/v1/users/me', '/api/v1/auth/change-password', '/api/v1/auth/logout'];
    if (!allowed.includes(url)) {
      return sendError(
        reply,
        403,
        'password_change_required',
        'Debes cambiar tu contraseña antes de utilizar la aplicación.'
      );
    }
  }

  // 5. Terms check
  const currentTerms = await getCurrentTermsVersion();
  if (currentTerms && user.acceptedTermsVersion !== currentTerms) {
    const allowed = [
      '/api/v1/users/me',
      '/api/v1/users/me/terms',
      '/api/v1/app/terms',
      '/api/v1/auth/logout',
      '/api/v1/auth/change-password',
    ];
    if (!allowed.includes(url)) {
      return sendError(
        reply,
        403,
        'terms_required',
        'Debes aceptar la versión vigente de los términos para continuar.',
        { currentVersion: currentTerms }
      );
    }
  }

  // Query group membership (pending or active)
  const memberRes = await query(
    `SELECT gm.group_id, g.name AS group_name, gm.role, gm.status, gm.joined_at
       FROM group_members gm
       JOIN groups g ON g.id = gm.group_id
      WHERE gm.user_id = $1
        AND gm.status IN ('pending', 'active')
      LIMIT 1;`,
    [user.id]
  );

  if (memberRes.rowCount && memberRes.rowCount > 0) {
    const m = memberRes.rows[0];
    request.membership = {
      groupId: m.group_id,
      groupName: m.group_name,
      role: m.role,
      status: m.status,
      joinedAt: m.joined_at,
    };
  }
}

/**
 * Ensures user has an active membership in the group.
 */
export async function requireActiveMembership(request: FastifyRequest, reply: FastifyReply) {
  if (!request.membership || request.membership.status !== 'active') {
    if (request.membership?.status === 'pending') {
      return sendError(reply, 403, 'membership_pending', 'Tu solicitud de unión al grupo aún está pendiente de aprobación.');
    }
    return sendError(reply, 403, 'group_membership_required', 'No perteneces a ningún grupo activo.');
  }
}

/**
 * Ensures user is active admin of their group.
 */
export async function requireGroupAdmin(request: FastifyRequest, reply: FastifyReply) {
  if (!request.membership || request.membership.status !== 'active' || request.membership.role !== 'admin') {
    return sendError(reply, 403, 'admin_required', 'Esta acción requiere privilegios de administrador del grupo.');
  }
}
