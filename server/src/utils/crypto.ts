import crypto from 'crypto';
import argon2 from 'argon2';

/**
 * Generates an opaque 256-bit session token encoded in unpadded Base64URL (43 chars).
 */
export function generateSessionToken(): string {
  return crypto.randomBytes(32).toString('base64url');
}

/**
 * Calculates SHA-256 hex lowercase of a string or buffer.
 */
export function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex');
}

/**
 * Hashes a user password using argon2id.
 */
export async function hashPassword(password: string): Promise<string> {
  return await argon2.hash(password, {
    type: argon2.argon2id,
    memoryCost: 65536, // 64 MB
    timeCost: 3,
    parallelism: 1,
  });
}

/**
 * Verifies a password against an argon2id hash.
 */
export async function verifyPassword(hash: string, password: string): Promise<boolean> {
  try {
    return await argon2.verify(hash, password);
  } catch {
    return false;
  }
}

const INVITE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/**
 * Generates an 8-character uppercase invite code without 0, O, 1, I.
 */
export function generateInviteCode(): string {
  const bytes = crypto.randomBytes(8);
  let result = '';
  for (let i = 0; i < 8; i++) {
    result += INVITE_ALPHABET[bytes[i] % INVITE_ALPHABET.length];
  }
  return result;
}

/**
 * Generates a temporary readable password for admin resets (12 chars).
 */
export function generateTemporaryPassword(): string {
  const chars = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%';
  const bytes = crypto.randomBytes(12);
  let res = '';
  for (let i = 0; i < 12; i++) {
    res += chars[bytes[i] % chars.length];
  }
  return res;
}
