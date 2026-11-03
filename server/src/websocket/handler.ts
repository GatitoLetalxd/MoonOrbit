import { FastifyInstance, FastifyRequest } from 'fastify';
import { WebSocket } from 'ws';
import { query } from '../db.js';
import { hashToken } from '../utils/crypto.js';
import { getMinSupportedVersion } from '../middleware/version.js';
import { wsManager } from './manager.js';
import { getGroupSnapshotData } from '../utils/snapshot.js';

interface MessageRateTracker {
  count: number;
  resetAt: number;
}

export function registerWebSocket(fastify: FastifyInstance) {
  fastify.get('/ws', { websocket: true }, async (socket: WebSocket, req: FastifyRequest) => {
    const rawVersion = req.headers['x-app-version-code'];
    const authHeader = req.headers.authorization;

    // 1. Version check
    const minSupported = await getMinSupportedVersion();
    const versionCode = rawVersion ? parseInt(String(rawVersion), 10) : 0;
    if (!versionCode || versionCode < minSupported) {
      console.warn(`[WS] Rejecting connection: outdated version (${versionCode} < ${minSupported})`);
      socket.close(4426, 'Update required');
      return;
    }

    // 2. Auth check
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      console.warn('[WS] Rejecting connection: missing Authorization header');
      socket.close(4401, 'Unauthorized');
      return;
    }

    const token = authHeader.slice(7).trim();
    const tokenHash = hashToken(token);

    const sessionRes = await query(
      `SELECT s.id AS session_id, s.user_id, s.expires_at, s.revoked_at,
              u.id AS u_id, u.username, u.display_name, u.must_change_password
         FROM sessions s
         JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = $1;`,
      [tokenHash]
    );

    if (sessionRes.rowCount === 0) {
      console.warn('[WS] Rejecting connection: invalid token');
      socket.close(4401, 'Invalid token');
      return;
    }

    const s = sessionRes.rows[0];

    if (s.revoked_at) {
      console.warn(`[WS] Rejecting connection: revoked session ${s.session_id}`);
      socket.close(4403, 'Session revoked');
      return;
    }

    if (new Date(s.expires_at) <= new Date()) {
      console.warn(`[WS] Rejecting connection: expired session ${s.session_id}`);
      socket.close(4401, 'Session expired');
      return;
    }

    const userId = s.user_id;
    const sessionId = s.session_id;

    // Check membership
    const memberRes = await query(
      `SELECT gm.group_id, g.name AS group_name, gm.role, gm.status
         FROM group_members gm
         JOIN groups g ON g.id = gm.group_id
        WHERE gm.user_id = $1 AND gm.status IN ('pending', 'active')
        LIMIT 1;`,
      [userId]
    );

    const membership = memberRes.rowCount && memberRes.rowCount > 0 ? memberRes.rows[0] : null;
    const groupId = membership?.status === 'active' ? membership.group_id : null;

    wsManager.addConnection(sessionId, userId, groupId, socket);

    // Send hello event
    const helloPayload = {
      type: 'hello',
      serverTime: new Date().toISOString(),
      session: {
        id: sessionId,
        expiresAt: s.expires_at.toISOString(),
      },
      group: membership && membership.status === 'active' ? {
        id: membership.group_id,
        name: membership.group_name,
        role: membership.role,
      } : null,
    };
    socket.send(JSON.stringify(helloPayload));

    // If active member, send initial snapshot
    if (groupId) {
      try {
        const snapshot = await getGroupSnapshotData(groupId, userId);
        if (snapshot) {
          socket.send(JSON.stringify({ type: 'snapshot', ...snapshot }));
        }
      } catch (err) {
        console.error('[WS] Error loading initial snapshot', err);
      }
    }

    // Rate limiting per connection (5 msgs / second)
    const rateTracker: MessageRateTracker = {
      count: 0,
      resetAt: Date.now() + 1000,
    };

    socket.on('pong', () => {
      wsManager.updateActivity(sessionId);
    });

    socket.on('ping', () => {
      wsManager.updateActivity(sessionId);
    });

    socket.on('message', async (raw: Buffer) => {
      wsManager.updateActivity(sessionId);

      // Check payload size (max 4 KB)
      if (raw.length > 4096) {
        socket.send(JSON.stringify({
          type: 'error',
          code: 'message_too_large',
          message: 'El mensaje supera los 4 KB permitidos.',
          serverTime: new Date().toISOString(),
        }));
        return;
      }

      // Check rate limit
      const now = Date.now();
      if (now > rateTracker.resetAt) {
        rateTracker.count = 0;
        rateTracker.resetAt = now + 1000;
      }
      rateTracker.count++;
      if (rateTracker.count > 5) {
        socket.send(JSON.stringify({
          type: 'error',
          code: 'rate_limit_exceeded',
          message: 'Límite de mensajes por segundo excedido.',
          serverTime: new Date().toISOString(),
        }));
        return;
      }

      let parsed: any;
      try {
        parsed = JSON.parse(raw.toString('utf8'));
      } catch {
        socket.send(JSON.stringify({
          type: 'error',
          code: 'invalid_json',
          message: 'JSON no válido.',
          serverTime: new Date().toISOString(),
        }));
        return;
      }

      if (parsed.type === 'ping') {
        socket.send(JSON.stringify({
          type: 'pong',
          serverTime: new Date().toISOString(),
        }));
        return;
      }

      if (parsed.type === 'resync') {
        let currentGroupId = wsManager.getClientGroup(sessionId);
        if (!currentGroupId) {
          // Check DB in case user was approved after handshake
          const memCheck = await query(
            `SELECT group_id FROM group_members WHERE user_id = $1 AND status = 'active' LIMIT 1;`,
            [userId]
          );
          if (memCheck.rowCount && memCheck.rowCount > 0) {
            currentGroupId = memCheck.rows[0].group_id;
            wsManager.updateClientGroup(sessionId, currentGroupId);
          }
        }

        if (!currentGroupId) {
          socket.send(JSON.stringify({
            type: 'error',
            code: 'no_active_group',
            message: 'No perteneces a ningún grupo activo.',
            serverTime: new Date().toISOString(),
          }));
          return;
        }

        const snapshot = await getGroupSnapshotData(currentGroupId, userId);
        if (snapshot) {
          socket.send(JSON.stringify({ type: 'snapshot', ...snapshot }));
        }
        return;
      }

      // Unsupported message type (e.g. attempting to send location via WS)
      socket.send(JSON.stringify({
        type: 'error',
        code: 'unsupported_message',
        message: 'Tipo de mensaje no admitido. Las escrituras de ubicación deben enviarse vía REST.',
        serverTime: new Date().toISOString(),
      }));
    });

    socket.on('close', (code: number, reason: Buffer) => {
      console.log(`[WS] Connection closed: session=${sessionId}, code=${code}, reason=${reason}`);
      wsManager.removeConnection(sessionId);
    });

    socket.on('error', (err: Error) => {
      console.error(`[WS] Connection error: session=${sessionId}`, err);
      wsManager.removeConnection(sessionId);
    });
  });
}
