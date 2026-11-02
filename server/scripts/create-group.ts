import { getClient } from '../src/db.js';
import { generateInviteCode, hashPassword } from '../src/utils/crypto.js';

async function createGroup() {
  const args = process.argv.slice(2);
  const groupName = args[0] || 'Familia Luna';
  const adminUsername = (args[1] || 'admin_luna').toLowerCase().trim();
  const adminDisplayName = args[2] || 'Administrador';
  const adminPassword = args[3] || 'LunaAdmin2026!';

  console.log(`[Create-Group] Creating group "${groupName}" with admin "${adminUsername}"...`);

  const client = await getClient();
  try {
    await client.query('BEGIN;');

    // 1. Create or find Admin User
    let adminUserId: string;
    const userRes = await client.query(`SELECT id FROM users WHERE username = $1;`, [adminUsername]);
    if (userRes.rowCount && userRes.rowCount > 0) {
      adminUserId = userRes.rows[0].id;
      console.log(`[Create-Group] User "${adminUsername}" already exists (id: ${adminUserId})`);
    } else {
      const passHash = await hashPassword(adminPassword);
      const newU = await client.query(
        `INSERT INTO users (username, password_hash, display_name, accepted_terms_version, accepted_terms_at)
         VALUES ($1, $2, $3, '2026-11-01', now())
         RETURNING id;`,
        [adminUsername, passHash, adminDisplayName]
      );
      adminUserId = newU.rows[0].id;
      await client.query(`INSERT INTO user_state (user_id) VALUES ($1);`, [adminUserId]);
      console.log(`[Create-Group] Created user "${adminUsername}" (id: ${adminUserId}) with password "${adminPassword}"`);
    }

    // 2. Create Group
    const inviteCode = generateInviteCode();
    const gRes = await client.query(
      `INSERT INTO groups (name, invite_code, invite_code_expires_at, created_by)
       VALUES ($1, $2, now() + interval '48 hours', $3)
       RETURNING id, name, invite_code, invite_code_expires_at;`,
      [groupName, inviteCode, adminUserId]
    );
    const group = gRes.rows[0];

    // 3. Add user as active admin in group_members
    await client.query(
      `INSERT INTO group_members (group_id, user_id, role, status, joined_at, decided_at, decided_by)
       VALUES ($1, $2, 'admin', 'active', now(), now(), $2);`,
      [group.id, adminUserId]
    );

    await client.query('COMMIT;');

    console.log('----------------------------------------------------');
    console.log('✅ Group created successfully!');
    console.log(`   Group ID:     ${group.id}`);
    console.log(`   Group Name:   ${group.name}`);
    console.log(`   Invite Code:  ${group.invite_code}`);
    console.log(`   Expires At:   ${group.invite_code_expires_at}`);
    console.log(`   Admin User:   ${adminUsername}`);
    console.log(`   Admin Pass:   ${adminPassword}`);
    console.log('----------------------------------------------------');
    process.exit(0);
  } catch (err) {
    await client.query('ROLLBACK;');
    console.error('[Create-Group] Error creating group:', err);
    process.exit(1);
  } finally {
    client.release();
  }
}

createGroup();
