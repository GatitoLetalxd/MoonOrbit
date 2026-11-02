import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import path from 'path';
import fs from 'fs/promises';
import sharp from 'sharp';
import { query } from '../db.js';
import { sendError } from '../utils/errors.js';
import { authenticate } from '../middleware/auth.js';
import { wsManager } from '../websocket/manager.js';
import { config } from '../config.js';

export async function userRoutes(fastify: FastifyInstance) {
  // -------------------------------------------------------------------
  // GET /api/v1/users/me
  // -------------------------------------------------------------------
  fastify.get('/me', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const membership = request.membership;

    return reply.status(200).send({
      user: {
        id: user.id,
        username: user.username,
        displayName: user.displayName,
        phone: user.phone,
        avatarVersion: user.avatarVersion,
        mustChangePassword: user.mustChangePassword,
        acceptedTermsVersion: user.acceptedTermsVersion,
        createdAt: user.createdAt.toISOString(),
      },
      group: membership ? {
        id: membership.groupId,
        name: membership.groupName,
        role: membership.role,
        status: membership.status,
      } : null,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // PATCH /api/v1/users/me
  // -------------------------------------------------------------------
  fastify.patch('/me', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const body = (request.body as any) || {};

    let newDisplayName = user.displayName;
    let newPhone = user.phone;
    let changed = false;

    if (body.displayName !== undefined) {
      const trimmed = String(body.displayName).trim();
      if (trimmed.length < 2 || trimmed.length > 40) {
        return sendError(reply, 400, 'validation_error', 'El nombre visible debe tener entre 2 y 40 caracteres.');
      }
      newDisplayName = trimmed;
      changed = true;
    }

    if (body.phone !== undefined) {
      if (body.phone === null || String(body.phone).trim() === '') {
        newPhone = null;
      } else {
        newPhone = String(body.phone).trim().slice(0, 30);
      }
      changed = true;
    }

    if (!changed) {
      return reply.status(200).send({
        user: {
          id: user.id,
          username: user.username,
          displayName: user.displayName,
          phone: user.phone,
          avatarVersion: user.avatarVersion,
        },
        serverTime: new Date().toISOString(),
      });
    }

    const res = await query(
      `UPDATE users
          SET display_name = $1,
              phone = $2
        WHERE id = $3
        RETURNING id, username, display_name, phone, avatar_version;`,
      [newDisplayName, newPhone, user.id]
    );

    const updated = res.rows[0];

    // Broadcast to group members if user is in an active group
    if (request.membership && request.membership.status === 'active') {
      wsManager.broadcastToGroup(request.membership.groupId, {
        type: 'profile_updated',
        user: {
          userId: updated.id,
          displayName: updated.display_name,
          phone: updated.phone,
          avatarVersion: updated.avatar_version,
        },
        serverTime: new Date().toISOString(),
      });
    }

    return reply.status(200).send({
      user: {
        id: updated.id,
        username: updated.username,
        displayName: updated.display_name,
        phone: updated.phone,
        avatarVersion: updated.avatar_version,
      },
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // PUT /api/v1/users/me/avatar
  // -------------------------------------------------------------------
  fastify.put(
    '/me/avatar',
    {
      preHandler: [authenticate],
    },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const contentType = request.headers['content-type'] || '';

      if (!contentType.includes('image/webp')) {
        return sendError(reply, 415, 'unsupported_media_type', 'El avatar debe enviarse como Content-Type: image/webp.');
      }

      const rawBuffer = request.body as Buffer;
      if (!rawBuffer || !Buffer.isBuffer(rawBuffer) || rawBuffer.length === 0) {
        return sendError(reply, 400, 'validation_error', 'Cuerpo de avatar vacío.');
      }

      if (rawBuffer.length > 1024 * 1024) {
        return sendError(reply, 413, 'payload_too_large', 'El avatar no puede superar 1 MB.');
      }

      // Re-encode with sharp (512x512, quality 80, strip metadata)
      let processed: Buffer;
      try {
        processed = await sharp(rawBuffer)
          .resize(512, 512, { fit: 'cover', position: 'center' })
          .webp({ quality: 80 })
          .toBuffer();
      } catch (err) {
        return sendError(reply, 400, 'invalid_image', 'La imagen no pudo ser procesada como WebP.');
      }

      const nextVersion = user.avatarVersion + 1;
      const relativePath = path.join(user.id, `${nextVersion}.webp`).replace(/\\/g, '/');
      const fullDir = path.join(config.avatarRoot, user.id);
      const fullPath = path.join(config.avatarRoot, relativePath);

      await fs.mkdir(fullDir, { recursive: true });
      await fs.writeFile(fullPath, processed);

      // Update DB
      const res = await query(
        `UPDATE users
            SET avatar_path = $1,
                avatar_version = $2
          WHERE id = $3
          RETURNING avatar_version;`,
        [relativePath, nextVersion, user.id]
      );

      // Clean up previous file if any
      if (user.avatarPath) {
        const oldFullPath = path.join(config.avatarRoot, user.avatarPath);
        fs.unlink(oldFullPath).catch(() => {});
      }

      // Broadcast profile_updated
      if (request.membership && request.membership.status === 'active') {
        wsManager.broadcastToGroup(request.membership.groupId, {
          type: 'profile_updated',
          user: {
            userId: user.id,
            displayName: user.displayName,
            phone: user.phone,
            avatarVersion: nextVersion,
          },
          serverTime: new Date().toISOString(),
        });
      }

      return reply.status(200).send({
        avatarVersion: nextVersion,
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // DELETE /api/v1/users/me/avatar
  // -------------------------------------------------------------------
  fastify.delete('/me/avatar', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;

    if (!user.avatarPath) {
      return reply.status(200).send({
        avatarVersion: user.avatarVersion,
        serverTime: new Date().toISOString(),
      });
    }

    const nextVersion = user.avatarVersion + 1;
    await query(
      `UPDATE users
          SET avatar_path = NULL,
              avatar_version = $1
        WHERE id = $2;`,
      [nextVersion, user.id]
    );

    const oldFullPath = path.join(config.avatarRoot, user.avatarPath);
    fs.unlink(oldFullPath).catch(() => {});

    // Broadcast profile_updated
    if (request.membership && request.membership.status === 'active') {
      wsManager.broadcastToGroup(request.membership.groupId, {
        type: 'profile_updated',
        user: {
          userId: user.id,
          displayName: user.displayName,
          phone: user.phone,
          avatarVersion: nextVersion,
        },
        serverTime: new Date().toISOString(),
      });
    }

    return reply.status(200).send({
      avatarVersion: nextVersion,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // GET /api/v1/users/:userId/avatar
  // -------------------------------------------------------------------
  fastify.get('/:userId/avatar', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const viewer = request.user!;
    const targetUserId = (request.params as any).userId;

    // Authorization: viewer is target OR viewer shares active group with target
    if (viewer.id !== targetUserId) {
      const shareRes = await query(
        `SELECT 1
           FROM group_members viewer
           JOIN group_members target ON target.group_id = viewer.group_id
          WHERE viewer.user_id = $1 AND viewer.status = 'active'
            AND target.user_id = $2 AND target.status = 'active'
          LIMIT 1;`,
        [viewer.id, targetUserId]
      );
      if (shareRes.rowCount === 0) {
        return sendError(reply, 404, 'not_found', 'Avatar no disponible.');
      }
    }

    const uRes = await query(`SELECT avatar_path, avatar_version FROM users WHERE id = $1;`, [targetUserId]);
    if (uRes.rowCount === 0 || !uRes.rows[0].avatar_path) {
      return sendError(reply, 404, 'not_found', 'El usuario no tiene avatar configurado.');
    }

    const { avatar_path, avatar_version } = uRes.rows[0];
    const fullPath = path.join(config.avatarRoot, avatar_path);

    let stat;
    try {
      stat = await fs.stat(fullPath);
    } catch {
      return sendError(reply, 404, 'not_found', 'Archivo de avatar no encontrado.');
    }

    const etag = `W/"${avatar_version}-${stat.size}"`;
    if (request.headers['if-none-match'] === etag) {
      return reply.status(304).send();
    }

    const reqVersion = (request.query as any)?.v;
    if (reqVersion && String(reqVersion) === String(avatar_version)) {
      reply.header('Cache-Control', 'private, max-age=31536000, immutable');
    } else {
      reply.header('Cache-Control', 'private, no-cache');
    }

    reply.header('ETag', etag);
    reply.header('Content-Type', 'image/webp');
    const fileStream = await fs.readFile(fullPath);
    return reply.status(200).send(fileStream);
  });

  // -------------------------------------------------------------------
  // DELETE /api/v1/users/me (Delete account)
  // -------------------------------------------------------------------
  fastify.delete('/me', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;

    // Check if user is active admin
    const adminCheck = await query(
      `SELECT 1 FROM group_members WHERE user_id = $1 AND role = 'admin' AND status = 'active';`,
      [user.id]
    );
    if (adminCheck.rowCount && adminCheck.rowCount > 0) {
      return sendError(
        reply,
        409,
        'admin_transfer_required',
        'El administrador no puede eliminar su cuenta mientras lo sea. Debe transferir el rol primero.'
      );
    }

    // Delete user cascades to group_members, sessions, user_state
    await query(`DELETE FROM users WHERE id = $1;`, [user.id]);

    wsManager.closeUserSessions(user.id, 1000, 'Account deleted');

    return reply.status(204).send();
  });

  // -------------------------------------------------------------------
  // POST /api/v1/users/me/terms (Accept terms)
  // -------------------------------------------------------------------
  fastify.post('/me/terms', { preHandler: [authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const user = request.user!;
    const body = (request.body as any) || {};
    const version = String(body.version || '').trim();

    if (!version) {
      return sendError(reply, 400, 'validation_error', 'Debes especificar la versión de términos a aceptar.');
    }

    const tRes = await query(
      `UPDATE users
          SET accepted_terms_version = $1,
              accepted_terms_at = now()
        WHERE id = $2
          AND EXISTS (
            SELECT 1 FROM terms_versions WHERE version = $1 AND effective_at <= now()
          )
        RETURNING accepted_terms_version, accepted_terms_at;`,
      [version, user.id]
    );

    if (tRes.rowCount === 0) {
      return sendError(reply, 400, 'invalid_terms_version', 'La versión de términos especificada no existe o no está vigente.');
    }

    return reply.status(200).send({
      acceptedTermsVersion: tRes.rows[0].accepted_terms_version,
      acceptedTermsAt: tRes.rows[0].accepted_terms_at.toISOString(),
      serverTime: new Date().toISOString(),
    });
  });
}
