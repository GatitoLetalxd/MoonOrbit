import { FastifyRequest, FastifyReply } from 'fastify';
import { query } from '../db.js';
import { sendError } from '../utils/errors.js';

let cachedMinSupported: number | null = null;
let lastMinSupportedCheck = 0;

export async function getMinSupportedVersion(): Promise<number> {
  const now = Date.now();
  if (cachedMinSupported !== null && now - lastMinSupportedCheck < 60000) {
    return cachedMinSupported;
  }
  const res = await query<{ min_supported: number | null }>(
    `SELECT max(version_code) AS min_supported FROM app_releases WHERE is_min_supported = true;`
  );
  cachedMinSupported = res.rows[0]?.min_supported ?? 1;
  lastMinSupportedCheck = now;
  return cachedMinSupported;
}

export function clearMinSupportedCache() {
  cachedMinSupported = null;
}

export async function checkAppVersion(request: FastifyRequest, reply: FastifyReply) {
  // Public bypass endpoints
  const url = request.url.split('?')[0];
  if (
    url === '/api/v1/health' ||
    url === '/api/v1/app/version' ||
    url.startsWith('/api/v1/users/') && url.endsWith('/avatar') && request.method === 'GET'
  ) {
    return;
  }

  const rawVersion = request.headers['x-app-version-code'];
  if (!rawVersion || Array.isArray(rawVersion)) {
    return sendError(reply, 400, 'app_version_required', 'La cabecera X-App-Version-Code es obligatoria.');
  }

  const versionCode = parseInt(rawVersion, 10);
  if (isNaN(versionCode) || versionCode <= 0) {
    return sendError(reply, 400, 'app_version_required', 'X-App-Version-Code debe ser un entero positivo válido.');
  }

  const minSupported = await getMinSupportedVersion();
  if (versionCode < minSupported) {
    // Fetch latest release details for 426
    const latestRes = await query(
      `SELECT version_code, version_name, apk_url, apk_sha256, size_bytes, release_notes, published_at
         FROM app_releases
        ORDER BY version_code DESC
        LIMIT 1;`
    );
    const latest = latestRes.rows[0] ? {
      versionCode: latestRes.rows[0].version_code,
      versionName: latestRes.rows[0].version_name,
      apkUrl: latestRes.rows[0].apk_url,
      apkSha256: latestRes.rows[0].apk_sha256,
      sizeBytes: Number(latestRes.rows[0].size_bytes),
      releaseNotes: latestRes.rows[0].release_notes,
      publishedAt: latestRes.rows[0].published_at.toISOString(),
    } : null;

    return sendError(reply, 426, 'app_update_required', 'Es necesario actualizar la aplicación para continuar.', {
      minSupportedVersionCode: minSupported,
      latest,
    });
  }

  (request as any).appVersionCode = versionCode;
}
