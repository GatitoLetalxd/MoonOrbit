import { getClient } from '../src/db.js';

async function transferAdmin() {
  const groupId = process.argv[2];
  const oldAdminUsername = (process.argv[3] || '').toLowerCase().trim();
  const newAdminUsername = (process.argv[4] || '').toLowerCase().trim();

  if (!groupId || !oldAdminUsername || !newAdminUsername) {
    console.error('Usage: npm run transfer-admin <groupId> <oldAdminUsername> <newAdminUsername>');
    process.exit(1);
  }

  const client = await getClient();
  try {
    await client.query('BEGIN;');

    const oldRes = await client.query(`SELECT id FROM users WHERE username = $1;`, [oldAdminUsername]);
    const newRes = await client.query(`SELECT id FROM users WHERE username = $1;`, [newAdminUsername]);

    if (oldRes.rowCount === 0 || newRes.rowCount === 0) {
      await client.query('ROLLBACK;');
      console.error('One or both usernames not found.');
      process.exit(1);
    }

    const oldAdminId = oldRes.rows[0].id;
    const newAdminId = newRes.rows[0].id;

    // 1. Demote old admin
    const demoteRes = await client.query(
      `UPDATE group_members
          SET role = 'member'
        WHERE group_id = $1 AND user_id = $2 AND role = 'admin' AND status = 'active';`,
      [groupId, oldAdminId]
    );

    if (demoteRes.rowCount === 0) {
      await client.query('ROLLBACK;');
      console.error(`User "${oldAdminUsername}" is not the active admin of group "${groupId}".`);
      process.exit(1);
    }

    // 2. Promote new admin
    const promoteRes = await client.query(
      `UPDATE group_members
          SET role = 'admin'
        WHERE group_id = $1 AND user_id = $2 AND role = 'member' AND status = 'active';`,
      [groupId, newAdminId]
    );

    if (promoteRes.rowCount === 0) {
      await client.query('ROLLBACK;');
      console.error(`User "${newAdminUsername}" is not an active member of group "${groupId}".`);
      process.exit(1);
    }

    await client.query('COMMIT;');

    console.log('----------------------------------------------------');
    console.log(`✅ Admin transferred successfully in group: ${groupId}`);
    console.log(`   Old Admin: ${oldAdminUsername} -> now member`);
    console.log(`   New Admin: ${newAdminUsername} -> now admin`);
    console.log('----------------------------------------------------');
    process.exit(0);
  } catch (err) {
    await client.query('ROLLBACK;');
    console.error('Error transferring admin:', err);
    process.exit(1);
  } finally {
    client.release();
  }
}

transferAdmin();
