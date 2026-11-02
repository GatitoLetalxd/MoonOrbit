import { query } from '../db.js';
import { wsManager } from '../websocket/manager.js';
import { config } from '../config.js';

let intervalTimer: NodeJS.Timeout | null = null;

export async function runNoSignalCheck() {
  try {
    const res = await query(
      `UPDATE user_state
          SET status = 'no_signal',
              status_changed_at = now()
        WHERE status = 'active'
          AND last_seen_at < now() - ($1 || ' minutes')::interval
        RETURNING user_id, latitude, longitude, accuracy_m, low_accuracy,
                  battery_level, is_charging, recorded_at, last_seen_at;`,
      [config.noSignalThresholdMinutes]
    );

    if (res.rowCount && res.rowCount > 0) {
      console.log(`[Job:no_signal] Marked ${res.rowCount} users as no_signal`);

      for (const row of res.rows) {
        // Find user's active group
        const gRes = await query(
          `SELECT group_id FROM group_members WHERE user_id = $1 AND status = 'active' LIMIT 1;`,
          [row.user_id]
        );

        if (gRes.rowCount && gRes.rowCount > 0) {
          const groupId = gRes.rows[0].group_id;
          wsManager.broadcastToGroup(groupId, {
            type: 'member_state',
            userId: row.user_id,
            status: 'no_signal',
            lat: row.latitude !== null ? Number(row.latitude) : null,
            lng: row.longitude !== null ? Number(row.longitude) : null,
            accuracyM: row.accuracy_m !== null ? Number(row.accuracy_m) : null,
            lowAccuracy: !!row.low_accuracy,
            batteryLevel: row.battery_level !== null ? Number(row.battery_level) : null,
            isCharging: row.is_charging !== null ? !!row.is_charging : null,
            recordedAt: row.recorded_at ? row.recorded_at.toISOString() : null,
            lastSeenAt: row.last_seen_at ? row.last_seen_at.toISOString() : null,
            serverTime: new Date().toISOString(),
          });
        }
      }
    }
  } catch (err) {
    console.error('[Job:no_signal] Error checking no_signal state', err);
  }
}

export function startNoSignalJob() {
  // Run once every minute
  intervalTimer = setInterval(runNoSignalCheck, 60 * 1000);
}

export function stopNoSignalJob() {
  if (intervalTimer) {
    clearInterval(intervalTimer);
  }
}
