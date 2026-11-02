import { FastifyReply, FastifyRequest } from 'fastify';
import crypto from 'crypto';

export interface AppErrorDetails {
  [key: string]: any;
}

export class AppError extends Error {
  public statusCode: number;
  public code: string;
  public details?: AppErrorDetails;

  constructor(statusCode: number, code: string, message: string, details?: AppErrorDetails) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
  }
}

export function sendError(
  reply: FastifyReply,
  statusCode: number,
  code: string,
  message: string,
  details?: AppErrorDetails,
  requestId?: string
) {
  const reqId = requestId || (reply.request as any)?.id || crypto.randomUUID();
  reply.header('X-Request-Id', reqId);
  return reply.status(statusCode).send({
    error: {
      code,
      message,
      ...(details ? { details } : {}),
      requestId: reqId,
    },
  });
}
