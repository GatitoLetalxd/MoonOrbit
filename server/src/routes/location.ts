import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { query } from '../db.js';
import { sendError } from '../utils/errors.js';
import { authenticate, requireActiveMembership } from '../middleware/auth.js';
import { wsManager } from '../websocket/manager.js';
import { config } from '../config.js';

interface LocationPoint {
  lat: number;
  lng: number;
  accuracyM?: number | null;
  batteryLevel?: number | null;
  isCharging?: boolean | null;
  recordedAt: string;
}

export async function locationRoutes(fastify: FastifyInstance) {
  // -------------------------------------------------------------------
  // POST /api/v1/location (Batch location points)
  // -------------------------------------------------------------------
  fastify.post(
    '/location',
    { preHandler: [authenticate, requireActiveMembership] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const membership = request.membership!;
      const body = (request.body as any) || {};
      const rawPoints: LocationPoint[] = Array.isArray(body.points) ? body.points : [];

      if (rawPoints.length === 0) {
        return sendError(reply, 400, 'validation_error', 'El lote debe contener al menos 1 punto.');
      }

      if (rawPoints.length > config.maxPointsPerLocationBatch) {
        return sendError(reply, 400, 'too_many_points', 'El lote no puede superar los 50 puntos.');
      }

      const now = new Date();
      const cutoff24h = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      const maxFuture5m = new Date(now.getTime() + 5 * 60 * 1000);

      let acceptedCount = 0;
      let ignoredCount = 0;
      const validPoints: Array<{
        lat: number;
        lng: number;
        accuracyM: number | null;
        batteryLevel: number | null;
        isCharging: boolean | null;
        recordedAt: Date;
      }> = [];

      for (const p of rawPoints) {
        const lat = Number(p.lat);
        const lng = Number(p.lng);

        if (isNaN(lat) || lat < -90 || lat > 90 || isNaN(lng) || lng < -180 || lng > 180) {
          return sendError(reply, 400, 'validation_error', 'Coordenadas lat/lng fuera de rango válido.');
        }

        const accuracyM = p.accuracyM !== undefined && p.accuracyM !== null ? Number(p.accuracyM) : null;
        if (accuracyM !== null && (isNaN(accuracyM) || accuracyM < 0)) {
          return sendError(reply, 400, 'validation_error', 'accuracyM no puede ser negativo.');
        }

        const batteryLevel = p.batteryLevel !== undefined && p.batteryLevel !== null ? Number(p.batteryLevel) : null;
        if (batteryLevel !== null && (isNaN(batteryLevel) || batteryLevel < 0 || batteryLevel > 100)) {
          return sendError(reply, 400, 'validation_error', 'batteryLevel debe estar entre 0 y 100.');
        }

        let recordedAt = new Date(p.recordedAt);
        if (isNaN(recordedAt.getTime())) {
          return sendError(reply, 400, 'validation_error', 'recordedAt debe ser una fecha ISO 8601 válida.');
        }

        // Ignore points older than 24h
        if (recordedAt < cutoff24h) {
          ignoredCount++;
          continue;
        }

        // Clamp points more than 5m in the future to server now
        if (recordedAt > maxFuture5m) {
          recordedAt = now;
        }

        acceptedCount++;
        validPoints.push({
          lat,
          lng,
          accuracyM,
          batteryLevel,
          isCharging: p.isCharging !== undefined && p.isCharging !== null ? Boolean(p.isCharging) : null,
          recordedAt,
        });
      }

      if (validPoints.length === 0) {
        return reply.status(200).send({
          accepted: 0,
          ignored: ignoredCount,
          effectiveRecordedAt: null,
          status: 'active',
          serverTime: now.toISOString(),
        });
      }

      // Pick the point with the most recent recordedAt
      validPoints.sort((a, b) => b.recordedAt.getTime() - a.recordedAt.getTime());
      const best = validPoints[0];
      const lowAccuracy = best.accuracyM !== null && best.accuracyM > 100;

      // Execute atomic SQL upsert (Query C.1)
      const res = await query(
        `INSERT INTO user_state AS s (
           user_id, latitude, longitude, accuracy_m, low_accuracy,
           battery_level, is_charging, recorded_at, received_at,
           last_seen_at, status, status_changed_at
         )
         VALUES (
           $1, $2, $3, $4, $5, $6, $7, $8, now(), now(), 'active', now()
         )
         ON CONFLICT (user_id) DO UPDATE SET
           latitude = EXCLUDED.latitude,
           longitude = EXCLUDED.longitude,
           accuracy_m = EXCLUDED.accuracy_m,
           low_accuracy = EXCLUDED.low_accuracy,
           battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
           is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging),
           recorded_at = EXCLUDED.recorded_at,
           received_at = now(),
           last_seen_at = now(),
           status = CASE
                      WHEN s.status IN ('paused', 'location_disabled') THEN s.status
                      ELSE 'active'
                    END,
           status_changed_at = CASE
                      WHEN s.status IN ('paused', 'location_disabled') THEN s.status_changed_at
                      WHEN s.status <> 'active' THEN now()
                      ELSE s.status_changed_at
                    END
         WHERE s.status NOT IN ('paused', 'location_disabled')
           AND (s.recorded_at IS NULL OR EXCLUDED.recorded_at >= s.recorded_at)
         RETURNING status, recorded_at, last_seen_at;`,
        [
          user.id,
          best.lat,
          best.lng,
          best.accuracyM,
          lowAccuracy,
          best.batteryLevel,
          best.isCharging,
          best.recordedAt,
        ]
      );

      let effectiveStatus = 'active';
      if (res.rowCount && res.rowCount > 0) {
        effectiveStatus = res.rows[0].status;

        // Broadcast to group members via WS
        wsManager.broadcastToGroup(membership.groupId, {
          type: 'member_state',
          userId: user.id,
          status: effectiveStatus,
          lat: best.lat,
          lng: best.lng,
          accuracyM: best.accuracyM,
          lowAccuracy,
          batteryLevel: best.batteryLevel,
          isCharging: best.isCharging,
          recordedAt: best.recordedAt.toISOString(),
          lastSeenAt: res.rows[0].last_seen_at.toISOString(),
          serverTime: new Date().toISOString(),
        });
      } else {
        // If row was not updated because user was paused, location_disabled, or point was older than current, check current status
        const cur = await query(`SELECT status FROM user_state WHERE user_id = $1;`, [user.id]);
        if (cur.rowCount && cur.rowCount > 0) {
          effectiveStatus = cur.rows[0].status;
        }
        ignoredCount += acceptedCount;
        acceptedCount = 0;
      }

      return reply.status(200).send({
        accepted: acceptedCount,
        ignored: ignoredCount,
        effectiveRecordedAt: best.recordedAt.toISOString(),
        status: effectiveStatus,
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/heartbeat
  // -------------------------------------------------------------------
  fastify.post(
    '/heartbeat',
    { preHandler: [authenticate, requireActiveMembership] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const membership = request.membership!;
      const body = (request.body as any) || {};

      const batteryLevel = body.batteryLevel !== undefined && body.batteryLevel !== null ? Number(body.batteryLevel) : null;
      const isCharging = body.isCharging !== undefined && body.isCharging !== null ? Boolean(body.isCharging) : null;

      const res = await query(
        `INSERT INTO user_state AS s (
           user_id, battery_level, is_charging, last_seen_at, status, status_changed_at
         )
         VALUES ($1, $2, $3, now(), 'active', now())
         ON CONFLICT (user_id) DO UPDATE SET
           battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
           is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging),
           last_seen_at = now(),
           status = CASE WHEN s.status = 'no_signal' THEN 'active' ELSE s.status END,
           status_changed_at = CASE
                                 WHEN s.status = 'no_signal' THEN now()
                                 ELSE s.status_changed_at
                               END
         RETURNING status, last_seen_at, latitude, longitude, accuracy_m, low_accuracy, recorded_at;`,
        [user.id, batteryLevel, isCharging]
      );

      const row = res.rows[0];

      // Broadcast state update
      wsManager.broadcastToGroup(membership.groupId, {
        type: 'member_state',
        userId: user.id,
        status: row.status,
        lat: row.latitude !== null ? Number(row.latitude) : null,
        lng: row.longitude !== null ? Number(row.longitude) : null,
        accuracyM: row.accuracy_m !== null ? Number(row.accuracy_m) : null,
        lowAccuracy: !!row.low_accuracy,
        batteryLevel,
        isCharging,
        recordedAt: row.recorded_at ? row.recorded_at.toISOString() : null,
        lastSeenAt: row.last_seen_at.toISOString(),
        serverTime: new Date().toISOString(),
      });

      return reply.status(200).send({
        status: row.status,
        lastSeenAt: row.last_seen_at.toISOString(),
        serverTime: new Date().toISOString(),
      });
    }
  );

  // -------------------------------------------------------------------
  // POST /api/v1/me/status (Update explicit status)
  // -------------------------------------------------------------------
  fastify.post(
    '/me/status',
    { preHandler: [authenticate, requireActiveMembership] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = request.user!;
      const membership = request.membership!;
      const body = (request.body as any) || {};
      const newStatus = String(body.status || '').trim();

      if (!['active', 'paused', 'location_disabled'].includes(newStatus)) {
        return sendError(
          reply,
          400,
          'validation_error',
          'El estado debe ser active, paused o location_disabled (no_signal solo lo calcula el servidor).'
        );
      }

      const batteryLevel = body.batteryLevel !== undefined && body.batteryLevel !== null ? Number(body.batteryLevel) : null;
      const isCharging = body.isCharging !== undefined && body.isCharging !== null ? Boolean(body.isCharging) : null;

      const res = await query(
        `INSERT INTO user_state AS s (
           user_id, status, status_changed_at, last_seen_at, battery_level, is_charging
         )
         VALUES ($1, $2, now(), now(), $3, $4)
         ON CONFLICT (user_id) DO UPDATE SET
           status = EXCLUDED.status,
           status_changed_at = CASE
                                 WHEN s.status <> EXCLUDED.status THEN now()
                                 ELSE s.status_changed_at
                               END,
           last_seen_at = now(),
           battery_level = COALESCE(EXCLUDED.battery_level, s.battery_level),
           is_charging = COALESCE(EXCLUDED.is_charging, s.is_charging)
         RETURNING status, status_changed_at, latitude, longitude, accuracy_m, low_accuracy, recorded_at, last_seen_at;`,
        [user.id, newStatus, batteryLevel, isCharging]
      );

      const row = res.rows[0];

      // Broadcast state update
      wsManager.broadcastToGroup(membership.groupId, {
        type: 'member_state',
        userId: user.id,
        status: row.status,
        lat: row.latitude !== null ? Number(row.latitude) : null,
        lng: row.longitude !== null ? Number(row.longitude) : null,
        accuracyM: row.accuracy_m !== null ? Number(row.accuracy_m) : null,
        lowAccuracy: !!row.low_accuracy,
        batteryLevel,
        isCharging,
        recordedAt: row.recorded_at ? row.recorded_at.toISOString() : null,
        lastSeenAt: row.last_seen_at.toISOString(),
        serverTime: new Date().toISOString(),
      });

      return reply.status(200).send({
        status: row.status,
        statusChangedAt: row.status_changed_at.toISOString(),
        serverTime: new Date().toISOString(),
      });
    }
  );
}
