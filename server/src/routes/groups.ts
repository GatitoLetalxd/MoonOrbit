import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import crypto from 'crypto';
import { query, getClient } from '../db.js';
import { sendError } from '../utils/errors.js';
import { authenticate, requireActiveMembership, requireGroupAdmin } from '../middleware/auth.js';
import { wsManager } from '../websocket/manager.js';
import { generateInviteCode, generateTemporaryPassword, hashPassword } from '../utils/crypto.js';
import { getGroupSnapshotData } from '../utils/snapshot.js';
import { config } from '../config.js';

export async function groupRoutes(fastify: FastifyInstance) {
  // -------------------------------------------------------------------
  // GET /api/v1/groups (Current user group)
  // -------------------------------------------------------------------
  fastify.get('/', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;

    const res = await query(
      `SELECT g.id, g.name, gm.role, gm.status, g.created_at,
              CASE WHEN gm.status = 'active' THEN (
                SELECT count(*)::int
                  FROM group_members m
                 WHERE m.group_id = g.id AND m.status = 'active'
              ) END AS member_count
         FROM group_members gm
         JOIN groups g ON g.id = gm.group_id
        WHERE gm.user_id = $1
          AND gm.status IN ('pending', 'active');`,
      [user.id]
    );

    if (res.rowCount === 0) {
      return reply.status(200).send({
        group: null,
        serverTime: new Date().toISOString(),
      });
    }

    const row = res.rows[0];
    return reply.status(200).send({
      group: {
        id: row.id,
        name: row.name,
        role: row.role,
        status: row.status,
        memberCount: row.member_count ?? null,
        createdAt: row.created_at.toISOString(),
      },
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // POST /api/v1/groups/join (Join with invite code for existing user)
  // -------------------------------------------------------------------
  fastify.post('/join', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const body = (request.body as any) || {};
    const rawInviteCode = String(body.inviteCode || '').toUpperCase().replace(/[\s-]/g, '');

    if (!/^[A-HJ-NP-Z2-9]{8}$/.test(rawInviteCode)) {
      return sendError(reply, 400, 'invalid_invite_code', 'Código de invitación no válido.');
    }

    // Check if user already has pending/active membership
    if (request.membership) {
      return sendError(reply, 409, 'already_member', 'Ya tienes una solicitud pendiente o eres miembro activo de un grupo.');
    }

    const client = await getClient();
    try {
      await client.query('BEGIN;');

      const gRes = await client.query(
        `SELECT id, name FROM groups WHERE invite_code = $1 AND invite_code_expires_at > now() FOR UPDATE;`,
        [rawInviteCode]
      );

      if (gRes.rowCount === 0) {
        await client.query('ROLLBACK;');
        return sendError(reply, 400, 'invalid_invite_code', 'El código de invitación no existe o ha vencido.');
      }
      const group = gRes.rows[0];

      // Check group capacity
      const cRes = await client.query(
        `SELECT count(*)::int AS count FROM group_members WHERE group_id = $1 AND status = 'active';`,
        [group.id]
      );
      if (cRes.rows[0].count >= config.maxActiveMembersPerGroup) {
        await client.query('ROLLBACK;');
        return sendError(reply, 409, 'group_full', 'El grupo está lleno (máximo 12 miembros).');
      }

      // Upsert membership into pending
      await client.query(
        `INSERT INTO group_members (group_id, user_id, role, status, joined_at)
         VALUES ($1, $2, 'member', 'pending', now())
         ON CONFLICT (group_id, user_id) DO UPDATE
            SET status = 'pending',
                role = 'member',
                joined_at = now(),
                decided_at = NULL,
                decided_by = NULL
          WHERE group_members.status IN ('rejected', 'left');`,
        [group.id, user.id]
      );

      await client.query('COMMIT;');

      // Notify admin via WS
      wsManager.broadcastToGroup(group.id, {
        type: 'member_pending',
        user: {
          userId: user.id,
          username: user.username,
          displayName: user.displayName,
          avatarVersion: user.avatarVersion,
          joinedAt: new Date().toISOString(),
        },
        serverTime: new Date().toISOString(),
      });

      return reply.status(200).send({
        group: {
          id: group.id,
          name: group.name,
          status: 'pending',
        },
        serverTime: new Date().toISOString(),
      });
    } catch (err: any) {
      await client.query('ROLLBACK;');
      if (err.code === '23505') {
        return sendError(reply, 409, 'already_member', 'Ya tienes una solicitud pendiente o eres miembro activo de un grupo.');
      }
      throw err;
    } finally {
      client.release();
    }
  });

  // -------------------------------------------------------------------
  // GET /api/v1/groups/:groupId/snapshot
  // -------------------------------------------------------------------
  fastify.get(
    '/:groupId/snapshot',
    { preHandler: [authenticate, requireActiveMembership] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const targetGroupId = (request.params as any).groupId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const snapshot = await getGroupSnapshotData(targetGroupId, user.id);
      if (!snapshot) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const etag = `W/"${crypto.createHash('md5').update(JSON.stringify(snapshot.members)).digest('hex')}"`;
      if (request.headers['if-none-match'] === etag) {
        return reply.status(304).send();
      }

      reply.header('Cache-Control', 'private, no-cache');
      reply.header('ETag', etag);
      return reply.status(200).send(snapshot);
    }
  );

  // -------------------------------------------------------------------
  // PATCH /api/v1/groups/:groupId (Rename group - Admin only)
  // -------------------------------------------------------------------
  fastify.patch(
    '/:groupId',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const targetGroupId = (request.params as any).groupId;
      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const body = (request.body as any) || {};
      const newName = String(body.name || '').trim();
      if (newName.length < 2 || newName.length > 40) {
        return sendError(reply, 400, 'validation_error', 'El nombre del grupo debe tener entre 2 y 40 caracteres.');
      }

      const res = await query(
        `UPDATE groups SET name = $1 WHERE id = $2 RETURNING id, name;`,
        [newName, targetGroupId]
      );

      wsManager.broadcastToGroup(targetGroupId, {
        type: 'group_updated',
        group: {
          id: targetGroupId,
          name: newName,
        },
        serverTime: new Date().toISOString(),
      });

      return reply.status(200).send({
        group: {
          id: res.rows[0].id,
          name: res.rows[0].name,
        },
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // GET /api/v1/groups/:groupId/invite (Get invite code - Admin only)
  // -------------------------------------------------------------------
  fastify.get(
    '/:groupId/invite',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const targetGroupId = (request.params as any).groupId;
      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const res = await query(`SELECT invite_code, invite_code_expires_at FROM groups WHERE id = $1;`, [targetGroupId]);
      return reply.status(200).send({
        inviteCode: res.rows[0].invite_code,
        expiresAt: res.rows[0].invite_code_expires_at.toISOString(),
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/groups/:groupId/invite/regenerate (Admin only)
  // -------------------------------------------------------------------
  fastify.post(
    '/:groupId/invite/regenerate',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const targetGroupId = (request.params as any).groupId;
      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      let newCode = generateInviteCode();
      let updated = false;

      // Retry up to 3 times in case of collision
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const res = await query(
            `UPDATE groups
                SET invite_code = $1,
                    invite_code_expires_at = now() + interval '48 hours'
              WHERE id = $2
              RETURNING invite_code, invite_code_expires_at;`,
            [newCode, targetGroupId]
          );
          updated = true;
          return reply.status(200).send({
            inviteCode: res.rows[0].invite_code,
            expiresAt: res.rows[0].invite_code_expires_at.toISOString(),
            serverTime: new Date().toISOString(),
          });
        } catch (err: any) {
          if (err.code === '23505') {
            newCode = generateInviteCode();
            continue;
          }
          throw err;
        }
      }

      return sendError(reply, 500, 'server_error', 'No se pudo generar un código único.');
    }
  );

  // -------------------------------------------------------------------
  // GET /api/v1/groups/:groupId/pending (Admin only)
  // -------------------------------------------------------------------
  fastify.get(
    '/:groupId/pending',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const targetGroupId = (request.params as any).groupId;
      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const res = await query(
        `SELECT u.id AS user_id, u.username, u.display_name, u.avatar_version, gm.joined_at
           FROM group_members gm
           JOIN users u ON u.id = gm.user_id
          WHERE gm.group_id = $1 AND gm.status = 'pending'
          ORDER BY gm.joined_at ASC;`,
        [targetGroupId]
      );

      const pending = res.rows.map((row) => ({
        userId: row.user_id,
        username: row.username,
        displayName: row.display_name,
        avatarVersion: row.avatar_version,
        joinedAt: row.joined_at.toISOString(),
      }));

      return reply.status(200).send({
        pending,
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/groups/:groupId/members/:userId/approve (Admin only)
  // -------------------------------------------------------------------
  fastify.post(
    '/:groupId/members/:userId/approve',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const admin = request.user!;
      const targetGroupId = (request.params as any).groupId;
      const targetUserId = (request.params as any).userId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const client = await getClient();
      try {
        await client.query('BEGIN;');

        await client.query(`SELECT id FROM groups WHERE id = $1 FOR UPDATE;`, [targetGroupId]);

        // Capacity check
        const countRes = await client.query(
          `SELECT count(*)::int AS count FROM group_members WHERE group_id = $1 AND status = 'active';`,
          [targetGroupId]
        );
        if (countRes.rows[0].count >= config.maxActiveMembersPerGroup) {
          await client.query('ROLLBACK;');
          return sendError(reply, 409, 'group_full', 'No se puede aprobar: el grupo ya tiene 12 miembros activos.');
        }

        const updateRes = await client.query(
          `UPDATE group_members
              SET status = 'active',
                  decided_at = now(),
                  decided_by = $1
            WHERE group_id = $2 AND user_id = $3 AND status = 'pending'
            RETURNING user_id, joined_at;`,
          [admin.id, targetGroupId, targetUserId]
        );

        if (updateRes.rowCount === 0) {
          await client.query('ROLLBACK;');
          return sendError(reply, 404, 'not_found', 'Solicitud pendiente no encontrada.');
        }

        const uRes = await client.query(`SELECT display_name, avatar_version FROM users WHERE id = $1;`, [targetUserId]);
        const targetUser = uRes.rows[0];

        await client.query('COMMIT;');

        // Update WS group for target user sessions in wsManager
        wsManager.updateUserGroup(targetUserId, targetGroupId);

        wsManager.sendToUser(targetUserId, {
          type: 'member_approved',
          group: { id: targetGroupId },
          serverTime: new Date().toISOString(),
        });

        // Broadcast to group members
        wsManager.broadcastToGroup(targetGroupId, {
          type: 'member_joined',
          member: {
            userId: targetUserId,
            displayName: targetUser.display_name,
            avatarVersion: targetUser.avatar_version,
            joinedAt: updateRes.rows[0].joined_at.toISOString(),
          },
          serverTime: new Date().toISOString(),
        });

        return reply.status(200).send({
          status: 'approved',
          serverTime: new Date().toISOString(),
        });
      } catch (err) {
        await client.query('ROLLBACK;');
        throw err;
      } finally {
        client.release();
      }
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/groups/:groupId/members/:userId/reject (Admin only)
  // -------------------------------------------------------------------
  fastify.post(
    '/:groupId/members/:userId/reject',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const admin = request.user!;
      const targetGroupId = (request.params as any).groupId;
      const targetUserId = (request.params as any).userId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      const updateRes = await query(
        `UPDATE group_members
            SET status = 'rejected',
                decided_at = now(),
                decided_by = $1
          WHERE group_id = $2 AND user_id = $3 AND status = 'pending'
          RETURNING user_id;`,
        [admin.id, targetGroupId, targetUserId]
      );

      if (updateRes.rowCount === 0) {
        return sendError(reply, 404, 'not_found', 'Solicitud pendiente no encontrada.');
      }

      wsManager.sendToUser(targetUserId, {
        type: 'member_rejected',
        serverTime: new Date().toISOString(),
      });

      return reply.status(200).send({
        status: 'rejected',
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // DELETE /api/v1/groups/:groupId/members/:userId (Kick member - Admin only)
  // -------------------------------------------------------------------
  fastify.delete(
    '/:groupId/members/:userId',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const admin = request.user!;
      const targetGroupId = (request.params as any).groupId;
      const targetUserId = (request.params as any).userId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      if (targetUserId === admin.id) {
        return sendError(reply, 409, 'admin_transfer_required', 'El administrador no puede ser expulsado ni salir sin transferir el rol.');
      }

      const client = await getClient();
      try {
        await client.query('BEGIN;');

        const updateRes = await client.query(
          `UPDATE group_members
              SET status = 'left',
                  decided_at = now(),
                  decided_by = $1
            WHERE group_id = $2 AND user_id = $3 AND status = 'active' AND role = 'member'
            RETURNING user_id;`,
          [admin.id, targetGroupId, targetUserId]
        );

        if (updateRes.rowCount === 0) {
          await client.query('ROLLBACK;');
          return sendError(reply, 404, 'not_found', 'Miembro activo no encontrado.');
        }

        // Revoke all sessions of expelled member
        await client.query(`UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL;`, [targetUserId]);

        await client.query('COMMIT;');

        // Update WS group in wsManager
        wsManager.updateUserGroup(targetUserId, null);

        // Broadcast to group
        wsManager.broadcastToGroup(targetGroupId, {
          type: 'member_left',
          userId: targetUserId,
          serverTime: new Date().toISOString(),
        });

        // Close expelled user sessions with 4403
        wsManager.closeUserSessions(targetUserId, 4403, 'Expelled from group');

        return reply.status(204).send();
      } catch (err) {
        await client.query('ROLLBACK;');
        throw err;
      } finally {
        client.release();
      }
    }
  );

  // -------------------------------------------------------------------
  // DELETE /api/v1/groups/:groupId/members/me (Leave group voluntarily)
  // -------------------------------------------------------------------
  fastify.delete(
    '/:groupId/members/me',
    { preHandler: [authenticate, requireActiveMembership] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const targetGroupId = (request.params as any).groupId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      if (request.membership!.role === 'admin') {
        return sendError(reply, 409, 'admin_transfer_required', 'El administrador no puede salir del grupo sin transferir su rol.');
      }

      await query(
        `UPDATE group_members
            SET status = 'left',
                decided_at = now(),
                decided_by = $1
          WHERE group_id = $2 AND user_id = $1 AND status = 'active';`,
        [user.id, targetGroupId]
      );

      // Update WS group in wsManager
      wsManager.updateUserGroup(user.id, null);

      // Broadcast member_left to remaining members
      wsManager.broadcastToGroup(targetGroupId, {
        type: 'member_left',
        userId: user.id,
        serverTime: new Date().toISOString(),
      });

      // Close own WebSocket with 1000 (REST session remains valid to join another group)
      wsManager.closeSession(request.session!.id, 1000, 'Left group');

      return reply.status(204).send();
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/groups/:groupId/members/:userId/reset-password (Admin only)
  // -------------------------------------------------------------------
  fastify.post(
    '/:groupId/members/:userId/reset-password',
    { preHandler: [authenticate, requireActiveMembership, requireGroupAdmin] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const admin = request.user!;
      const targetGroupId = (request.params as any).groupId;
      const targetUserId = (request.params as any).userId;

      if (request.membership!.groupId !== targetGroupId) {
        return sendError(reply, 404, 'not_found', 'Grupo no encontrado.');
      }

      if (targetUserId === admin.id) {
        return sendError(reply, 400, 'validation_error', 'No puedes resetear tu propia contraseña con este endpoint.');
      }

      const tempPassword = generateTemporaryPassword();
      const tempHash = await hashPassword(tempPassword);

      const client = await getClient();
      try {
        await client.query('BEGIN;');

        const targetRes = await client.query(
          `SELECT u.id
             FROM users u
             JOIN group_members gm ON gm.user_id = u.id
            WHERE gm.group_id = $1 AND gm.user_id = $2 AND gm.status = 'active' AND gm.role = 'member';`,
          [targetGroupId, targetUserId]
        );

        if (targetRes.rowCount === 0) {
          await client.query('ROLLBACK;');
          return sendError(reply, 404, 'not_found', 'Miembro activo no encontrado.');
        }

        await client.query(
          `UPDATE users
              SET password_hash = $1,
                  must_change_password = true
            WHERE id = $2;`,
          [tempHash, targetUserId]
        );

        // Revoke all sessions of the target member
        await client.query(`UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL;`, [targetUserId]);

        await client.query('COMMIT;');

        // Close member's WS with 4403
        wsManager.closeUserSessions(targetUserId, 4403, 'Password reset by admin');

        return reply.status(200).send({
          temporaryPassword: tempPassword,
          serverTime: new Date().toISOString(),
        });
      } catch (err) {
        await client.query('ROLLBACK;');
        throw err;
      } finally {
        client.release();
      }
    }
  );
}
