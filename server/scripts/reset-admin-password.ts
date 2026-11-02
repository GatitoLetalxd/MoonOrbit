import { getClient } from '../src/db.js';
import { hashPassword, generateTemporaryPassword } from '../src/utils/crypto.js';

async function resetAdminPassword() {
  const adminUsername = (process.argv[2] || '').toLowerCase().trim();
  const explicitPassword = process.argv[3];

  if (!adminUsername) {
    console.error('Usage: npm run reset-admin <username> [newPassword]');
    process.exit(1);
  }

  const newPassword = explicitPassword || generateTemporaryPassword();
  const newHash = await hashPassword(newPassword);

  const client = await getClient();
  try {
    await client.query('BEGIN;');

    const uRes = await client.query(`SELECT id FROM users WHERE username = $1;`, [adminUsername]);
    if (uRes.rowCount === 0) {
      await client.query('ROLLBACK;');
      console.error(`User "${adminUsername}" not found.`);
      process.exit(1);
    }
    const userId = uRes.rows[0].id;

    await client.query(
      `UPDATE users
          SET password_hash = $1,
              must_change_password = true
        WHERE id = $2;`,
      [newHash, userId]
    );

    // Revoke all sessions
    await client.query(`UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL;`, [userId]);

    await client.query('COMMIT;');

    console.log('----------------------------------------------------');
    console.log(`✅ Password reset successfully for admin: ${adminUsername}`);
    console.log(`   New Temporary Password: ${newPassword}`);
    console.log(`   (User must change password on next login)`);
    console.log('----------------------------------------------------');
    process.exit(0);
  } catch (err) {
    await client.query('ROLLBACK;');
    console.error('Error resetting password:', err);
    process.exit(1);
  } finally {
    client.release();
  }
}

resetAdminPassword();
