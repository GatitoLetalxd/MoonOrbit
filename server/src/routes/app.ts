import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { query } from '../db.js';

export async function appRoutes(fastify: FastifyInstance) {
  // -------------------------------------------------------------------
  // GET /api/v1/health
  // -------------------------------------------------------------------
  fastify.get('/health', async (request: FastifyRequest, reply: FastifyReply) => {
    reply.header('Cache-Control', 'no-store');
    try {
      await query('SELECT 1;');
      return reply.status(200).send({
        status: 'pass',
        db: 'connected',
        version: '1.0.0',
        serverTime: new Date().toISOString(),
      });
    } catch (err) {
      request.log.error(err, 'Health check database failure');
      return reply.status(503).send({
        status: 'fail',
        db: 'disconnected',
        version: '1.0.0',
        serverTime: new Date().toISOString(),
      });
    }
  });

  // -------------------------------------------------------------------
  // GET /api/v1/app/version
  // -------------------------------------------------------------------
  fastify.get('/app/version', async (request: FastifyRequest, reply: FastifyReply) => {
    reply.header('Cache-Control', 'no-cache');

    const res = await query(
      `SELECT
         latest.version_code,
         latest.version_name,
         latest.apk_url,
         latest.apk_sha256,
         latest.size_bytes,
         latest.release_notes,
         latest.published_at,
         COALESCE(floor.min_supported, 1) AS min_supported_version_code
       FROM (
         SELECT *
           FROM app_releases
          ORDER BY version_code DESC
          LIMIT 1
       ) AS latest
       CROSS JOIN (
         SELECT max(version_code) AS min_supported
           FROM app_releases
          WHERE is_min_supported = true
       ) AS floor;`
    );

    if (res.rowCount === 0) {
      // Fallback if no releases yet
      return reply.status(200).send({
        latest: null,
        minSupportedVersionCode: 1,
        serverTime: new Date().toISOString(),
      });
    }

    const row = res.rows[0];
    return reply.status(200).send({
      latest: {
        versionCode: row.version_code,
        versionName: row.version_name,
        apkUrl: row.apk_url,
        apkSha256: row.apk_sha256,
        sizeBytes: Number(row.size_bytes),
        releaseNotes: row.release_notes,
        publishedAt: row.published_at.toISOString(),
      },
      minSupportedVersionCode: row.min_supported_version_code,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // GET /api/v1/app/notices
  // -------------------------------------------------------------------
  fastify.get('/app/notices', async (request: FastifyRequest, reply: FastifyReply) => {
    reply.header('Cache-Control', 'no-cache');

    const res = await query(
      `SELECT id, title, message, severity, dismissible, starts_at, ends_at
         FROM notices
        WHERE starts_at <= now()
          AND (ends_at IS NULL OR ends_at > now())
        ORDER BY (severity = 'important') DESC, starts_at DESC;`
    );

    const notices = res.rows.map((row) => ({
      id: row.id,
      title: row.title,
      message: row.message,
      severity: row.severity,
      dismissible: row.dismissible,
      startsAt: row.starts_at.toISOString(),
      endsAt: row.ends_at ? row.ends_at.toISOString() : null,
    }));

    return reply.status(200).send({
      notices,
      serverTime: new Date().toISOString(),
    });
  });

  // -------------------------------------------------------------------
  // GET /api/v1/app/terms
  // -------------------------------------------------------------------
  fastify.get('/app/terms', async (request: FastifyRequest, reply: FastifyReply) => {
    reply.header('Cache-Control', 'no-cache');

    const res = await query(
      `SELECT version, url, text_hash, effective_at
         FROM terms_versions
        WHERE effective_at <= now()
        ORDER BY effective_at DESC, version DESC
        LIMIT 1;`
    );

    if (res.rowCount === 0) {
      return reply.status(200).send({
        terms: null,
        serverTime: new Date().toISOString(),
      });
    }

    const row = res.rows[0];
    return reply.status(200).send({
      terms: {
        version: row.version,
        url: row.url,
        textHash: row.text_hash,
        effectiveAt: row.effective_at.toISOString(),
      },
      serverTime: new Date().toISOString(),
    });
  });
}
