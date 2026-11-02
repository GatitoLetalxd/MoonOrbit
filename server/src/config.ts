import dotenv from 'dotenv';
import path from 'path';

dotenv.config();

export const config = {
  port: parseInt(process.env.PORT || '3000', 10),
  host: process.env.HOST || '0.0.0.0',
  databaseUrl: process.env.DATABASE_URL || 'postgresql://postgres:1234@localhost:5432/moonorbit',
  databaseSsl: process.env.DATABASE_SSL === 'true',
  trustProxy: process.env.TRUST_PROXY === 'false' ? false : true,
  avatarRoot: path.resolve(process.env.AVATAR_ROOT || './avatars'),
  nodeEnv: process.env.NODE_ENV || 'development',
  maxActiveSessionsPerUser: 5,
  sessionLifetimeDays: 90,
  maxPointsPerLocationBatch: 50,
  maxActiveMembersPerGroup: 12,
  noSignalThresholdMinutes: 25,
};
