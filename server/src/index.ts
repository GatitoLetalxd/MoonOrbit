import Fastify from 'fastify';
import cors from '@fastify/cors';
import websocketPlugin from '@fastify/websocket';
import rateLimit from '@fastify/rate-limit';
import crypto from 'crypto';
import fs from 'fs/promises';
import { pool } from './db.js';
import { config } from './config.js';
import { checkAppVersion } from './middleware/version.js';
import { authRoutes } from './routes/auth.js';
import { userRoutes } from './routes/users.js';
import { groupRoutes } from './routes/groups.js';
import { locationRoutes } from './routes/location.js';
import { appRoutes } from './routes/app.js';
import { registerWebSocket } from './websocket/handler.js';
import { startNoSignalJob, stopNoSignalJob } from './jobs/noSignalJob.js';
import { startCleanupJob, stopCleanupJob } from './jobs/cleanupJob.js';
import { wsManager } from './websocket/manager.js';
import { sendError } from './utils/errors.js';

const fastify = Fastify({
  trustProxy: config.trustProxy,
  logger: {
    level: config.nodeEnv === 'development' ? 'info' : 'warn',
    serializers: {
      req(req) {
        return {
          method: req.method,
          url: req.url,
          id: req.id,
        };
      },
    },
  },
  bodyLimit: 65536, // 64 KB default limit for JSON
  genReqId: (req) => {
    return (req.headers['x-request-id'] as string) || crypto.randomUUID();
  },
});

// Binary parser for WebP avatar upload (up to 1 MB)
fastify.addContentTypeParser(
  ['image/webp', 'application/octet-stream'],
  { parseAs: 'buffer', bodyLimit: 1048576 },
  (_req, body, done) => {
    done(null, body);
  }
);

// Register Plugins
await fastify.register(cors, {
  origin: true,
  methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
});

await fastify.register(rateLimit, {
  max: 120,
  timeWindow: '1 minute',
  errorResponseBuilder: (req, context) => ({
    error: {
      code: 'rate_limit_exceeded',
      message: `Demasiadas peticiones. Intenta de nuevo en ${Math.ceil(context.ttl / 1000)} segundos.`,
      requestId: req.id,
    },
  }),
});

await fastify.register(websocketPlugin);

// Global Hook: add X-Request-Id header to all responses
fastify.addHook('onSend', async (request, reply) => {
  reply.header('X-Request-Id', request.id);
});

// Global Hook: check X-App-Version-Code
fastify.addHook('preHandler', async (request, reply) => {
  await checkAppVersion(request, reply);
});

// Register WebSocket
registerWebSocket(fastify);

// Register API Routes under /api/v1
await fastify.register(authRoutes, { prefix: '/api/v1/auth' });
await fastify.register(userRoutes, { prefix: '/api/v1/users' });
await fastify.register(groupRoutes, { prefix: '/api/v1/groups' });
await fastify.register(locationRoutes, { prefix: '/api/v1' });
await fastify.register(appRoutes, { prefix: '/api/v1' });

// Centralized error handler
fastify.setErrorHandler((error: any, request, reply) => {
  if (error.validation) {
    return sendError(reply, 400, 'validation_error', 'Los datos enviados no son válidos.', error.validation, request.id);
  }
  if (error.statusCode === 413) {
    return sendError(reply, 413, 'payload_too_large', 'El cuerpo de la petición excede el límite permitido.', undefined, request.id);
  }

  fastify.log.error(error);
  return sendError(reply, 500, 'internal_error', 'Ocurrió un error inesperado en el servidor.', undefined, request.id);
});

// 404 handler
fastify.setNotFoundHandler((request, reply) => {
  return sendError(reply, 404, 'not_found', 'El recurso solicitado no existe.', undefined, request.id);
});

// Start Server
async function start() {
  try {
    // Ensure avatar storage directory exists
    await fs.mkdir(config.avatarRoot, { recursive: true });

    await fastify.listen({ port: config.port, host: config.host });
    console.log(`[MoonOrbit] Server running on http://${config.host}:${config.port}`);
    console.log(`[MoonOrbit] WebSocket endpoint at ws://${config.host}:${config.port}/ws`);

    // Start background jobs
    startNoSignalJob();
    startCleanupJob();
  } catch (err) {
    fastify.log.error(err);
    process.exit(1);
  }
}

// Graceful shutdown
const shutdown = async () => {
  console.log('[MoonOrbit] Shutting down gracefully...');
  stopNoSignalJob();
  stopCleanupJob();
  wsManager.stop();
  await fastify.close();
  await pool.end();
  process.exit(0);
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

process.on('unhandledRejection', (reason) => {
  console.error('[MoonOrbit] Unhandled rejection:', reason);
});

process.on('uncaughtException', (err) => {
  console.error('[MoonOrbit] Uncaught exception:', err);
});

start();
