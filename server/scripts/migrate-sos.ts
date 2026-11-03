import { query } from '../src/db.js';

async function migrateSosConstraint() {
  console.log('[Migration] Updating user_state table constraint to support "sos" status...');

  try {
    // 1. Drop existing constraint
    await query(`ALTER TABLE user_state DROP CONSTRAINT IF EXISTS user_state_status_check;`);
    console.log('✅ Dropped existing user_state_status_check');

    // 2. Add updated constraint including 'sos'
    await query(`
      ALTER TABLE user_state
        ADD CONSTRAINT user_state_status_check
        CHECK (status IN ('active', 'paused', 'location_disabled', 'no_signal', 'sos'));
    `);
    console.log('✅ Added updated user_state_status_check constraint with "sos" support.');

    console.log('🎉 Migración completada exitosamente.');
    process.exit(0);
  } catch (err) {
    console.error('❌ Error ejecutando migración:', err);
    process.exit(1);
  }
}

migrateSosConstraint();
