import { query } from '../db.js';

export async function getGroupSnapshotData(groupId: string, viewerId: string) {
  // 1. Get group info and viewer's role/status
  const groupRes = await query(
    `SELECT g.id, g.name, gm.role, gm.status
       FROM group_members gm
       JOIN groups g ON g.id = gm.group_id
      WHERE gm.group_id = $1 AND gm.user_id = $2 AND gm.status = 'active';`,
    [groupId, viewerId]
  );

  if (groupRes.rowCount === 0) {
    return null;
  }

  const g = groupRes.rows[0];

  // 2. Get all active members + their user_state
  const membersRes = await query(
    `SELECT u.id AS user_id, u.username, u.display_name, u.phone, u.avatar_version,
            gm.role, gm.joined_at,
            s.latitude, s.longitude, s.accuracy_m, s.low_accuracy,
            s.battery_level, s.is_charging, s.recorded_at, s.last_seen_at,
            s.status, s.status_changed_at
       FROM group_members gm
       JOIN users u ON u.id = gm.user_id
       LEFT JOIN user_state s ON s.user_id = u.id
      WHERE gm.group_id = $1 AND gm.status = 'active'
      ORDER BY gm.joined_at ASC;`,
    [groupId]
  );

  const members = membersRes.rows.map((row) => ({
    userId: row.user_id,
    username: row.username,
    displayName: row.display_name,
    phone: row.phone,
    role: row.role,
    avatarVersion: row.avatar_version,
    joinedAt: row.joined_at.toISOString(),
    // Flattened fields (matching documentation)
    status: row.status || 'no_signal',
    lat: row.latitude !== null ? Number(row.latitude) : null,
    lng: row.longitude !== null ? Number(row.longitude) : null,
    accuracyM: row.accuracy_m !== null ? Number(row.accuracy_m) : null,
    lowAccuracy: !!row.low_accuracy,
    batteryLevel: row.battery_level !== null ? Number(row.battery_level) : null,
    isCharging: row.is_charging !== null ? !!row.is_charging : null,
    recordedAt: row.recorded_at ? row.recorded_at.toISOString() : null,
    lastSeenAt: row.last_seen_at ? row.last_seen_at.toISOString() : null,
    statusChangedAt: row.status_changed_at ? row.status_changed_at.toISOString() : null,
    // Nested state (for backward compatibility)
    state: {
      status: row.status || 'no_signal',
      lat: row.latitude !== null ? Number(row.latitude) : null,
      lng: row.longitude !== null ? Number(row.longitude) : null,
      accuracyM: row.accuracy_m !== null ? Number(row.accuracy_m) : null,
      lowAccuracy: !!row.low_accuracy,
      batteryLevel: row.battery_level !== null ? Number(row.battery_level) : null,
      isCharging: row.is_charging !== null ? !!row.is_charging : null,
      recordedAt: row.recorded_at ? row.recorded_at.toISOString() : null,
      lastSeenAt: row.last_seen_at ? row.last_seen_at.toISOString() : null,
      statusChangedAt: row.status_changed_at ? row.status_changed_at.toISOString() : null,
    },
  }));

  return {
    group: {
      id: g.id,
      name: g.name,
      myRole: g.role,
      myStatus: g.status,
    },
    members,
    serverTime: new Date().toISOString(),
  };
}
