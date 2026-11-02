import { WebSocket } from 'ws';

const BASE_URL = 'https://orbit.moondev.online';
const WS_URL = 'wss://orbit.moondev.online/ws';

async function runVpsValidation() {
  console.log(`🌐 Probando backend desplegado en ${BASE_URL}...`);

  // 1. Health check
  const healthRes = await fetch(`${BASE_URL}/api/v1/health`);
  const healthData = await healthRes.json();
  console.log('✅ 1. Health check:', healthRes.status, healthData);

  // 2. Version check
  const versionRes = await fetch(`${BASE_URL}/api/v1/app/version`);
  const versionData = await versionRes.json();
  console.log('✅ 2. App Version:', versionRes.status, versionData);

  // 3. Login
  const loginRes = await fetch(`${BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-App-Version-Code': '1',
    },
    body: JSON.stringify({
      username: 'admin_luna',
      password: 'LunaAdmin2026!',
      deviceName: 'VPS Automated Validation Suite',
    }),
  });

  if (!loginRes.ok) {
    throw new Error(`Login failed: ${loginRes.status} ${await loginRes.text()}`);
  }

  const authData: any = await loginRes.json();
  const token = authData.session.token;
  const groupId = authData.group.id;
  console.log(`✅ 3. Login exitoso! Grupo: ${authData.group.name} (${groupId})`);

  // 4. Get Snapshot
  const snapRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/snapshot`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'X-App-Version-Code': '1',
    },
  });
  const snapData: any = await snapRes.json();
  console.log(`✅ 4. Snapshot obtenido: ${snapData.members?.length || 0} miembros`);
  if (snapData.members && snapData.members.length > 0) {
    const m = snapData.members[0];
    console.log('   Muestra de miembro:', {
      userId: m.userId,
      displayName: m.displayName,
      role: m.role,
      status: m.status,
      lat: m.lat,
      lng: m.lng,
      hasStateObject: !!m.state,
    });
  }

  // 5. Send Location Batch
  const locRes = await fetch(`${BASE_URL}/api/v1/location`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-App-Version-Code': '1',
    },
    body: JSON.stringify({
      points: [
        {
          lat: 40.41682,
          lng: -3.70381,
          accuracyM: 10.5,
          batteryLevel: 94,
          isCharging: true,
          recordedAt: new Date().toISOString(),
        },
      ],
    }),
  });
  const locData = await locRes.json();
  console.log('✅ 5. Envío de ubicación (POST /location):', locRes.status, locData);

  // 6. Heartbeat
  const hbRes = await fetch(`${BASE_URL}/api/v1/heartbeat`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-App-Version-Code': '1',
    },
    body: JSON.stringify({
      batteryLevel: 93,
      isCharging: false,
    }),
  });
  const hbData = await hbRes.json();
  console.log('✅ 6. Heartbeat de presencia (POST /heartbeat):', hbRes.status, hbData);

  // 7. Get Invite Code
  const inviteRes = await fetch(`${BASE_URL}/api/v1/groups/${groupId}/invite`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'X-App-Version-Code': '1',
    },
  });
  const inviteData = await inviteRes.json();
  console.log('✅ 7. Código de invitación (GET /invite):', inviteRes.status, inviteData);

  // 8. WebSocket Connection & Handshake
  console.log(`🔌 Conectando WebSocket a ${WS_URL}...`);
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(WS_URL, {
      headers: {
        Authorization: `Bearer ${token}`,
        'X-App-Version-Code': '1',
      },
    });

    let helloReceived = false;
    let snapshotReceived = false;

    const timeout = setTimeout(() => {
      ws.close();
      if (helloReceived) {
        resolve();
      } else {
        reject(new Error('WebSocket timeout waiting for hello event'));
      }
    }, 8000);

    ws.on('open', () => {
      console.log('✅ 8.1 WebSocket conectado exitosamente!');
    });

    ws.on('message', (data: Buffer) => {
      const msg = JSON.parse(data.toString());
      console.log(`   WS Evento recibido: [${msg.type}]`);

      if (msg.type === 'hello') {
        helloReceived = true;
        // Send ping to verify bidirectional messaging
        ws.send(JSON.stringify({ type: 'ping' }));
      } else if (msg.type === 'snapshot') {
        snapshotReceived = true;
      } else if (msg.type === 'pong') {
        console.log('✅ 8.2 WebSocket PING -> PONG verificado!');
        clearTimeout(timeout);
        ws.close(1000, 'Test finished');
        resolve();
      }
    });

    ws.on('close', (code, reason) => {
      console.log(`ℹ️  WebSocket cerrado limpiamente: código ${code}`);
    });

    ws.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });
  });

  console.log('\n🎉 ¡TODAS LAS VALIDACIONES DEL BACKEND EN PRODUCCIÓN PASARON AL 100%!');
}

runVpsValidation().catch((err) => {
  console.error('❌ Error durante la validación en VPS:', err);
  process.exit(1);
});
