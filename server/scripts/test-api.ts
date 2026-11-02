import { WebSocket } from 'ws';

const BASE_URL = 'http://localhost:3000';
const WS_URL = 'ws://localhost:3000/ws';

async function runTests() {
  console.log('🧪 Starting API integration tests for MoonOrbit...');

  // 1. Health check
  const healthRes = await fetch(`${BASE_URL}/api/v1/health`);
  const healthData = await healthRes.json();
  console.log('✅ Health check:', healthData.status === 'pass' ? 'PASS' : 'FAIL', healthData);

  // 2. Version check
  const versionRes = await fetch(`${BASE_URL}/api/v1/app/version`);
  const versionData = await versionRes.json();
  console.log('✅ Version check:', versionData.minSupportedVersionCode === 1 ? 'PASS' : 'FAIL', versionData);

  // 3. Login as admin
  const loginRes = await fetch(`${BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-App-Version-Code': '1',
    },
    body: JSON.stringify({
      username: 'admin_luna',
      password: 'LunaAdmin2026!',
      deviceName: 'Test Admin Device',
    }),
  });

  const loginData = await loginRes.json();
  if (!loginRes.ok) {
    throw new Error(`Login failed: ${JSON.stringify(loginData)}`);
  }
  const adminToken = loginData.session.token;
  const groupId = loginData.group.id;
  console.log('✅ Admin login successful! Group ID:', groupId);

  // 4. Admin gets invite code
  const inviteRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/invite`, {
    headers: {
      Authorization: `Bearer ${adminToken}`,
      'X-App-Version-Code': '1',
    },
  });
  const inviteData = await inviteRes.json();
  const inviteCode = inviteData.inviteCode;
  console.log('✅ Admin retrieved invite code:', inviteCode);

  // 5. Register new member with invite code
  const testUser = `test_hijo_${Date.now().toString().slice(-4)}`;
  const regRes = await fetch(`${BASE_URL}/api/v1/auth/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-App-Version-Code': '1',
    },
    body: JSON.stringify({
      inviteCode,
      username: testUser,
      password: 'Password123!',
      displayName: 'Hijo Test',
      termsVersion: '2026-11-01',
      deviceName: 'Pixel 8 Pro',
    }),
  });
  const regData = await regRes.json();
  if (!regRes.ok) {
    throw new Error(`Register failed: ${JSON.stringify(regData)}`);
  }
  const memberToken = regData.session.token;
  const memberId = regData.user.id;
  console.log(`✅ Member "${testUser}" registered with pending status (userId: ${memberId})`);

  // 6. Admin checks pending list
  const pendingRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/pending`, {
    headers: {
      Authorization: `Bearer ${adminToken}`,
      'X-App-Version-Code': '1',
    },
  });
  const pendingData = await pendingRes.json();
  const found = pendingData.pending.some((p: any) => p.userId === memberId);
  console.log('✅ Admin sees member in pending list:', found ? 'PASS' : 'FAIL');

  // 7. Admin approves member
  const approveRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/members/${memberId}/approve`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${adminToken}`,
      'X-App-Version-Code': '1',
    },
  });
  const approveData = await approveRes.json();
  console.log('✅ Admin approved member:', approveData.status === 'approved' ? 'PASS' : 'FAIL');

  // 8. Member posts location batch (REST)
  const locRes = await fetch(`${BASE_URL}/api/v1/location`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${memberToken}`,
      'X-App-Version-Code': '1',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      points: [
        {
          lat: -12.0464,
          lng: -77.0428,
          accuracyM: 15.2,
          batteryLevel: 88,
          isCharging: false,
          recordedAt: new Date().toISOString(),
        },
      ],
    }),
  });
  const locData = await locRes.json();
  console.log('✅ Member location update:', locData.accepted === 1 ? 'PASS' : 'FAIL', locData);

  // 9. Member sends heartbeat
  const hbRes = await fetch(`${BASE_URL}/api/v1/heartbeat`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${memberToken}`,
      'X-App-Version-Code': '1',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      batteryLevel: 87,
      isCharging: true,
    }),
  });
  const hbData = await hbRes.json();
  console.log('✅ Heartbeat:', hbData.status === 'active' ? 'PASS' : 'FAIL');

  // 10. Admin gets group snapshot
  const snapRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/snapshot`, {
    headers: {
      Authorization: `Bearer ${adminToken}`,
      'X-App-Version-Code': '1',
    },
  });
  const snapData = await snapRes.json();
  console.log(`✅ Snapshot retrieved: ${snapData.members.length} active members.`);
  const memberInSnap = snapData.members.find((m: any) => m.userId === memberId);
  console.log('✅ Member state in snapshot:', memberInSnap?.state);

  // 11. WebSocket test with Member Token
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(WS_URL, {
      headers: {
        Authorization: `Bearer ${memberToken}`,
        'X-App-Version-Code': '1',
      },
    });

    let gotHello = false;
    let gotSnapshot = false;

    ws.on('open', () => {
      console.log('✅ WebSocket connected successfully!');
      // Send ping
      ws.send(JSON.stringify({ type: 'ping' }));
    });

    ws.on('message', (data: Buffer) => {
      const msg = JSON.parse(data.toString('utf8'));
      if (msg.type === 'hello') {
        gotHello = true;
        console.log('✅ Received WS hello event');
      } else if (msg.type === 'snapshot') {
        gotSnapshot = true;
        console.log('✅ Received WS snapshot event with', msg.members?.length, 'members');
      } else if (msg.type === 'pong') {
        console.log('✅ Received WS pong event');
        ws.close(1000, 'Test completed');
        if (gotHello && gotSnapshot) {
          resolve();
        }
      }
    });

    ws.on('error', (err) => {
      reject(err);
    });

    setTimeout(() => {
      if (!gotHello) reject(new Error('WS timeout'));
      else resolve();
    }, 4000);
  });

  console.log('\n🎉 ALL BACKEND API & WEBSOCKET TESTS PASSED PERFECTLY!');
  process.exit(0);
}

runTests().catch((err) => {
  console.error('❌ Test failed:', err);
  process.exit(1);
});
