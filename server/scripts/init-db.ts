import { query } from '../src/db.js';
import crypto from 'crypto';

async function initDb() {
  console.log('[Init-DB] Ensuring baseline data exists in moonorbit database...');

  // 1. Initial Terms Version
  const termsRes = await query(`SELECT version FROM terms_versions WHERE version = '2026-11-01';`);
  if (termsRes.rowCount === 0) {
    const textHash = crypto.createHash('sha256').update('MoonOrbit Términos y Condiciones Iniciales 2026-11-01').digest('hex');
    await query(
      `INSERT INTO terms_versions (version, url, text_hash, effective_at)
       VALUES ('2026-11-01', 'https://orbit.moondev.online/terminos/2026-11-01', $1, '2026-11-01T00:00:00Z');`,
      [textHash]
    );
    console.log('[Init-DB] Inserted terms_versions: 2026-11-01');
  } else {
    console.log('[Init-DB] terms_versions 2026-11-01 already exists.');
  }

  // 2. Initial App Release (Version 1)
  const releaseRes = await query(`SELECT version_code FROM app_releases WHERE version_code = 1;`);
  if (releaseRes.rowCount === 0) {
    const apkHash = crypto.createHash('sha256').update('moonorbit-v1.apk').digest('hex');
    await query(
      `INSERT INTO app_releases (version_code, version_name, apk_url, apk_sha256, size_bytes, release_notes, is_min_supported)
       VALUES (1, '1.0.0', 'https://orbit.moondev.online/downloads/moonorbit-1.0.0.apk', $1, 15000000, 'Lanzamiento inicial privado.', true);`,
      [apkHash]
    );
    console.log('[Init-DB] Inserted app_releases: Version 1 (1.0.0)');
  } else {
    console.log('[Init-DB] app_releases Version 1 already exists.');
  }

  console.log('[Init-DB] Baseline initialization complete.');
  process.exit(0);
}

initDb().catch((err) => {
  console.error('[Init-DB] Error initializing DB:', err);
  process.exit(1);
});
