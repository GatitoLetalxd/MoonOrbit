import fs from 'fs/promises';
import path from 'path';
import { query } from '../db.js';
import { config } from '../config.js';

let cleanupTimer: NodeJS.Timeout | null = null;

export async function runDailyCleanup() {
  console.log('[Job:cleanup] Starting daily cleanup tasks...');

  try {
    // 1. Delete sessions expired or revoked > 30 days ago
    const sessRes = await query(
      `DELETE FROM sessions
        WHERE expires_at < now() - interval '30 days'
           OR (revoked_at IS NOT NULL AND revoked_at < now() - interval '30 days');`
    );
    console.log(`[Job:cleanup] Deleted ${sessRes.rowCount} expired/revoked sessions`);

    // 2. Delete notices ended > 30 days ago
    const notRes = await query(
      `DELETE FROM notices
        WHERE ends_at IS NOT NULL AND ends_at < now() - interval '30 days';`
    );
    console.log(`[Job:cleanup] Deleted ${notRes.rowCount} expired notices`);

    // 3. Delete group memberships rejected/left > 90 days ago
    const memRes = await query(
      `DELETE FROM group_members
        WHERE status IN ('rejected', 'left')
          AND updated_at < now() - interval '90 days';`
    );
    console.log(`[Job:cleanup] Deleted ${memRes.rowCount} ancient left/rejected memberships`);

    // 4. Orphan avatars cleanup (> 24 hours old and not referenced in users.avatar_path)
    await cleanupOrphanAvatars();
  } catch (err) {
    console.error('[Job:cleanup] Error running daily cleanup', err);
  }
}

async function cleanupOrphanAvatars() {
  try {
    const res = await query(`SELECT avatar_path FROM users WHERE avatar_path IS NOT NULL;`);
    const validPaths = new Set(res.rows.map((r) => path.normalize(r.avatar_path)));

    const now = Date.now();
    const cutoff24h = now - 24 * 60 * 60 * 1000;

    let userDirs: string[] = [];
    try {
      userDirs = await fs.readdir(config.avatarRoot);
    } catch {
      return;
    }

    let deletedCount = 0;

    for (const dir of userDirs) {
      const userDirPath = path.join(config.avatarRoot, dir);
      let stat;
      try {
        stat = await fs.stat(userDirPath);
      } catch {
        continue;
      }
      if (!stat.isDirectory()) continue;

      const files = await fs.readdir(userDirPath);
      for (const file of files) {
        const filePath = path.join(userDirPath, file);
        const relPath = path.normalize(path.join(dir, file));

        if (!validPaths.has(relPath)) {
          const fileStat = await fs.stat(filePath);
          if (fileStat.mtimeMs < cutoff24h) {
            await fs.unlink(filePath).catch(() => {});
            deletedCount++;
          }
        }
      }
    }

    console.log(`[Job:cleanup] Deleted ${deletedCount} orphan avatar files`);
  } catch (err) {
    console.error('[Job:cleanup] Error in cleanupOrphanAvatars', err);
  }
}

export function startCleanupJob() {
  // Run every 24 hours
  cleanupTimer = setInterval(runDailyCleanup, 24 * 60 * 60 * 1000);
  // Also run on startup after a small delay
  setTimeout(runDailyCleanup, 10000);
}

export function stopCleanupJob() {
  if (cleanupTimer) {
    clearInterval(cleanupTimer);
  }
}
