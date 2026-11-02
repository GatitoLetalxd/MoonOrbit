import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { query, getClient } from '../db.js';
import {
  generateSessionToken,
  hashToken,
  hashPassword,
  verifyPassword,
} from '../utils/crypto.js';
import { sendError } from '../utils/errors.js';
import { authenticate, getCurrentTermsVersion } from '../middleware/auth.js';
import { wsManager } from '../websocket/manager.js';
import { config } from '../config.js';

export async function authRoutes(fastify: FastifyInstance) {
  // -------------------------------------------------------------------
  // POST /api/v1/auth/register
  // -------------------------------------------------------------------
  fastify.post('/register', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = (request.body as any) || {};
    const rawInviteCode = String(body.inviteCode || '').toUpperCase().replace(/[\s-]/g, '');
    const username = String(body.username || '').toLowerCase().trim();
    const displayName = String(body.displayName || '').trim();
    const password = String(body.password || '');
    const termsVersion = String(body.termsVersion || '').trim();
    const deviceName = body.deviceName ? String(body.deviceName).slice(0, 100) : null;
    const appVersionCode = (request as any).appVersionCode || null;

    // 1. Validation
    if (!/^[A-HJ-NP-Z2-9]{8}$/.test(rawInviteCode)) {
      return sendError(reply, 400, 'invalid_invite_code', 'El código de invitación no tiene el formato esperado.');
    }
    if (!/^[a-z0-9_]{3,24}$/.test(username)) {
      return sendError(reply, 400, 'validation_error', 'El nombre de usuario debe tener entre 3 y 24 caracteres (minúsculas, números y guion bajo).');
    }
    if (displayName.length < 2 || displayName.length > 40) {
      return sendError(reply, 400, 'validation_error', 'El nombre visible debe tener entre 2 y 40 caracteres.');
    }
    if (password.length < 10 || password.length > 128) {
      return sendError(reply, 400, 'validation_error', 'La contraseña debe tener entre 10 y 128 caracteres.');
    }
    if (password.toLowerCase() === username.toLowerCase() || password.toLowerCase() === displayName.toLowerCase()) {
      return sendError(reply, 400, 'validation_error', 'La contraseña no puede coincidir con el nombre de usuario ni el nombre visible.');
    }

    const currentTerms = await getCurrentTermsVersion();
    if (!currentTerms || termsVersion !== currentTerms) {
      return sendError(reply, 400, 'terms_required', 'Debes aceptar la versión vigente de los términos.', {
        currentVersion: currentTerms,
      });
    }

    const client = await getClient();
    try {
      await client.query('BEGIN;');

      // 2. Validate invite code
      const groupRes = await client.query(
        `SELECT id, name FROM groups WHERE invite_code = $1 AND invite_code_expires_at > now() FOR UPDATE;`,
        [rawInviteCode]
      );
      if (groupRes.rowCount === 0) {
        await client.query('ROLLBACK;');
        return sendError(reply, 400, 'invalid_invite_code', 'El código de invitación no es válido o ha vencido.');
      }
      const group = groupRes.rows[0];

      // 3. Capacity check (max 12 active members)
      const countRes = await client.query(
        `SELECT count(*)::int AS active_count FROM group_members WHERE group_id = $1 AND status = 'active';`,
        [group.id]
      );
      if (countRes.rows[0].active_count >= config.maxActiveMembersPerGroup) {
        await client.query('ROLLBACK;');
        return sendError(reply, 409, 'group_full', 'El grupo ha alcanzado el límite máximo de miembros activos (12).');
      }

      // 4. Create user
      const passwordHashed = await hashPassword(password);
      let userRes;
      try {
        userRes = await client.query(
          `INSERT INTO users (username, password_hash, display_name, accepted_terms_version, accepted_terms_at)
           VALUES ($1, $2, $3, $4, now())
           RETURNING id, username, display_name, avatar_version, created_at;`,
          [username, passwordHashed, displayName, termsVersion]
        );
      } catch (err: any) {
        if (err.code === '23505') {
          await client.query('ROLLBACK;');
          return sendError(reply, 409, 'username_taken', 'El nombre de usuario ya está registrado.');
        }
        throw err;
      }
      const user = userRes.rows[0];

      // 5. Create user_state
      await client.query(`INSERT INTO user_state (user_id) VALUES ($1);`, [user.id]);

      // 6. Create group_member as pending
      await client.query(
        `INSERT INTO group_members (group_id, user_id, role, status) VALUES ($1, $2, 'member', 'pending');`,
        [group.id, user.id]
      );

      // 7. Create session
      const sessionToken = generateSessionToken();
      const tokenHash = hashToken(sessionToken);
      const sessionRes = await client.query(
        `INSERT INTO sessions (user_id, token_hash, device_name, app_version_code)
         VALUES ($1, $2, $3, $4)
         RETURNING id, expires_at;`,
        [user.id, tokenHash, deviceName, appVersionCode]
      );
      const session = sessionRes.rows[0];

      await client.query('COMMIT;');

      // 8. Notify group admin via WebSocket
      wsManager.broadcastToGroup(group.id, {
        type: 'member_pending',
        user: {
          userId: user.id,
          username: user.username,
          displayName: user.display_name,
          avatarVersion: user.avatar_version,
          joinedAt: new Date().toISOString(),
        },
        serverTime: new Date().toISOString(),
      });

      return reply.status(201).send({
        user: {
          id: user.id,
          username: user.username,
          displayName: user.display_name,
          avatarVersion: user.avatar_version,
        },
        group: {
          id: group.id,
          name: group.name,
          status: 'pending',
        },
        session: {
          token: sessionToken,
          expiresAt: session.expires_at.toISOString(),
        },
        serverTime: new Date().toISOString(),
      });
    } catch (err) {
      await client.query('ROLLBACK;');
      throw err;
    } finally {
      client.release();
    }
  });

  // -------------------------------------------------------------------
  // POST /api/v1/auth/login
  // -------------------------------------------------------------------
  fastify.post('/login', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = (request.body as any) || {};
    const username = String(body.username || '').toLowerCase().trim();
    const password = String(body.password || '');
    const deviceName = body.deviceName ? String(body.deviceName).slice(0, 100) : null;
    const appVersionCode = (request as any).appVersionCode || null;

    if (!username || !password) {
      return sendError(reply, 401, 'invalid_credentials', 'Nombre de usuario o contraseña incorrectos.');
    }

    const userRes = await query(
      `SELECT id, username, display_name, phone, password_hash, avatar_version,
              must_change_password, accepted_terms_version, accepted_terms_at, created_at
         FROM users
        WHERE username = $1;`,
      [username]
    );

    if (userRes.rowCount === 0) {
      return sendError(reply, 401, 'invalid_credentials', 'Nombre de usuario o contraseña incorrectos.');
    }

    const user = userRes.rows[0];
    const passwordValid = await verifyPassword(user.password_hash, password);
    if (!passwordValid) {
      return sendError(reply, 401, 'invalid_credentials', 'Nombre de usuario o contraseña incorrectos.');
    }

    // Create session
    const sessionToken = generateSessionToken();
    const tokenHash = hashToken(sessionToken);

    const client = await getClient();
    let session: any;
    let revokedSessionIds: string[] = [];

    try {
      await client.query('BEGIN;');

      const sessInsert = await client.query(
        `INSERT INTO sessions (user_id, token_hash, device_name, app_version_code)
         VALUES ($1, $2, $3, $4)
         RETURNING id, expires_at;`,
        [user.id, tokenHash, deviceName, appVersionCode]
      );
      session = sessInsert.rows[0];

      // Enforce max 5 sessions: revoke older excess sessions
      const revokeRes = await client.query(
        `WITH keep AS (
           SELECT id
             FROM sessions
            WHERE user_id = $1
              AND revoked_at IS NULL
              AND expires_at > now()
            ORDER BY last_used_at DESC, created_at DESC
            LIMIT 5
         )
         UPDATE sessions
            SET revoked_at = now()
          WHERE user_id = $1
            AND revoked_at IS NULL
            AND expires_at > now()
            AND id NOT IN (SELECT id FROM keep)
          RETURNING id;`,
        [user.id]
      );
      revokedSessionIds = revokeRes.rows.map((r: any) => r.id);

      await client.query('COMMIT;');
    } catch (err) {
      await client.query('ROLLBACK;');
      throw err;
    } finally {
      client.release();
    }

    // Notify & close revoked sessions via WS
    for (const rId of revokedSessionIds) {
      wsManager.sendToSession(rId, {
        type: 'session_revoked',
        sessionId: rId,
        serverTime: new Date().toISOString(),
      });
      wsManager.closeSession(rId, 4403, 'Session revoked by limit');
    }

    // Get current membership
    const memberRes = await query(
      `SELECT gm.group_id, g.name AS group_name, gm.role, gm.status
         FROM group_members gm
         JOIN groups g ON g.id = gm.group_id
        WHERE gm.user_id = $1 AND gm.status IN ('pending', 'active')
        LIMIT 1;`,
      [user.id]
    );

    const m = memberRes.rows[0];

    return reply.status(200).send({
      user: {
        id: user.id,
        username: user.username,
        displayName: user.display_name,
        phone: user.phone,
        avatarVersion: user.avatar_version,
        mustChangePassword: user.must_change_password,
        acceptedTermsVersion: user.accepted_terms_version,
      },
      session: {
        token: sessionToken,
        expiresAt: session.expires_at.toISOString(),
      },
      group: m ? {
        id: m.group_id,
        name: m.group_name,
        role: m.role,
        status: m.status,
      } : null,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // POST /api/v1/auth/logout
  // -------------------------------------------------------------------
  fastify.post('/logout', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const session = request.session!;

    await query(`UPDATE sessions SET revoked_at = now() WHERE id = $1;`, [session.id]);
    wsManager.closeSession(session.id, 1000, 'Logged out');

    return reply.status(204).send();
  });

  // -------------------------------------------------------------------
  // POST /api/v1/auth/logout-others
  // -------------------------------------------------------------------
  fastify.post('/logout-others', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const session = request.session!;

    const res = await query(
      `UPDATE sessions
          SET revoked_at = now()
        WHERE user_id = $1
          AND id <> $2
          AND revoked_at IS NULL
        RETURNING id;`,
      [user.id, session.id]
    );

    for (const row of res.rows) {
      wsManager.sendToSession(row.id, {
        type: 'session_revoked',
        sessionId: row.id,
        serverTime: new Date().toISOString(),
      });
      wsManager.closeSession(row.id, 4403, 'Session revoked by logout-others');
    }

    return reply.status(204).send();
  });

  // -------------------------------------------------------------------
  // POST /api/v1/auth/change-password
  // -------------------------------------------------------------------
  fastify.post('/change-password', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const currentSession = request.session!;
    const body = (request.body as any) || {};
    const currentPassword = String(body.currentPassword || '');
    const newPassword = String(body.newPassword || '');
    const revokeOthers = body.revokeOthers !== false; // default true

    if (!currentPassword || !newPassword) {
      return sendError(reply, 400, 'validation_error', 'Debes ingresar tu contraseña actual y la nueva contraseña.');
    }

    // Verify current password
    const uRes = await query(`SELECT password_hash FROM users WHERE id = $1;`, [user.id]);
    const valid = await verifyPassword(uRes.rows[0].password_hash, currentPassword);
    if (!valid) {
      return sendError(reply, 401, 'invalid_credentials', 'La contraseña actual no es correcta.');
    }

    // Validate new password
    if (newPassword.length < 10 || newPassword.length > 128) {
      return sendError(reply, 400, 'validation_error', 'La nueva contraseña debe tener entre 10 y 128 caracteres.');
    }
    if (newPassword.toLowerCase() === user.username.toLowerCase() || newPassword.toLowerCase() === user.displayName.toLowerCase()) {
      return sendError(reply, 400, 'validation_error', 'La nueva contraseña no puede coincidir con tu nombre de usuario ni nombre visible.');
    }
    if (currentPassword === newPassword) {
      return sendError(reply, 400, 'validation_error', 'La nueva contraseña no puede ser idéntica a la anterior.');
    }

    const newHashed = await hashPassword(newPassword);

    await query(
      `UPDATE users
          SET password_hash = $1,
              must_change_password = false
        WHERE id = $2;`,
      [newHashed, user.id]
    );

    if (revokeOthers) {
      const revoked = await query(
        `UPDATE sessions
            SET revoked_at = now()
          WHERE user_id = $1
            AND id <> $2
            AND revoked_at IS NULL
          RETURNING id;`,
        [user.id, currentSession.id]
      );

      for (const row of revoked.rows) {
        wsManager.sendToSession(row.id, {
          type: 'session_revoked',
          sessionId: row.id,
          serverTime: new Date().toISOString(),
        });
        wsManager.closeSession(row.id, 4403, 'Password changed');
      }
    }

    return reply.status(204).send();
  });

  // -------------------------------------------------------------------
  // GET /api/v1/auth/sessions
  // -------------------------------------------------------------------
  fastify.get('/sessions', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const currentSessionId = request.session!.id;

    const res = await query(
      `SELECT id, device_name, app_version_code, created_at, last_used_at, expires_at
         FROM sessions
        WHERE user_id = $1
          AND revoked_at IS NULL
          AND expires_at > now()
        ORDER BY last_used_at DESC;`,
      [user.id]
    );

    const sessions = res.rows.map((row) => ({
      id: row.id,
      deviceName: row.device_name,
      appVersionCode: row.app_version_code,
      createdAt: row.created_at.toISOString(),
      lastUsedAt: row.last_used_at.toISOString(),
      expiresAt: row.expires_at.toISOString(),
      isCurrent: row.id === currentSessionId,
    }));

    return reply.status(200).send({
      sessions,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // DELETE /api/v1/auth/sessions/:id
  // -------------------------------------------------------------------
  fastify.delete('/sessions/:id', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const currentSession = request.session!;
    const targetSessionId = (request.params as any).id;

    const res = await query(
      `UPDATE sessions
          SET revoked_at = now()
        WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
        RETURNING id;`,
      [targetSessionId, user.id]
    );

    if (res.rowCount === 0) {
      return sendError(reply, 404, 'not_found', 'Sesión no encontrada.');
    }

    wsManager.sendToSession(targetSessionId, {
      type: 'session_revoked',
      sessionId: targetSessionId,
      serverTime: new Date().toISOString(),
    });

    const isCurrent = targetSessionId === currentSession.id;
    wsManager.closeSession(targetSessionId, 4403, 'Session terminated');

    return reply.status(204).send();
  });
}
