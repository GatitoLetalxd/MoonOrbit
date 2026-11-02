import { WebSocket } from 'ws';

export interface WsClient {
  ws: WebSocket;
  sessionId: string;
  userId: string;
  groupId: string | null;
  lastActivity: number;
}

export class WebSocketManager {
  private clientsBySession = new Map<string, WsClient>();
  private sessionIdsByGroup = new Map<string, Set<string>>();
  private sessionIdsByUser = new Map<string, Set<string>>();
  private pingInterval: NodeJS.Timeout | null = null;

  constructor() {
    this.startPingInterval();
  }

  public addConnection(sessionId: string, userId: string, groupId: string | null, ws: WebSocket) {
    // If session already had a connection, close the previous one cleanly (code 1000)
    const existing = this.clientsBySession.get(sessionId);
    if (existing && existing.ws !== ws) {
      try {
        existing.ws.close(1000, 'Replaced by new connection');
      } catch {}
      this.removeConnection(sessionId);
    }

    const client: WsClient = {
      ws,
      sessionId,
      userId,
      groupId,
      lastActivity: Date.now(),
    };

    this.clientsBySession.set(sessionId, client);

    // Track by user
    if (!this.sessionIdsByUser.has(userId)) {
      this.sessionIdsByUser.set(userId, new Set());
    }
    this.sessionIdsByUser.get(userId)!.add(sessionId);

    // Track by group
    if (groupId) {
      if (!this.sessionIdsByGroup.has(groupId)) {
        this.sessionIdsByGroup.set(groupId, new Set());
      }
      this.sessionIdsByGroup.get(groupId)!.add(sessionId);
    }

    console.log(`[WS] Client connected: user=${userId}, session=${sessionId}, group=${groupId}`);
  }

  public updateClientGroup(sessionId: string, newGroupId: string | null) {
    const client = this.clientsBySession.get(sessionId);
    if (!client) return;

    if (client.groupId && this.sessionIdsByGroup.has(client.groupId)) {
      this.sessionIdsByGroup.get(client.groupId)!.delete(sessionId);
    }

    client.groupId = newGroupId;
    if (newGroupId) {
      if (!this.sessionIdsByGroup.has(newGroupId)) {
        this.sessionIdsByGroup.set(newGroupId, new Set());
      }
      this.sessionIdsByGroup.get(newGroupId)!.add(sessionId);
    }
  }

  public updateUserGroup(userId: string, newGroupId: string | null) {
    const sessionIds = this.sessionIdsByUser.get(userId);
    if (sessionIds) {
      for (const sId of sessionIds) {
        this.updateClientGroup(sId, newGroupId);
      }
    }
  }

  public getClientGroup(sessionId: string): string | null {
    return this.clientsBySession.get(sessionId)?.groupId || null;
  }

  public removeConnection(sessionId: string) {
    const client = this.clientsBySession.get(sessionId);
    if (!client) return;

    this.clientsBySession.delete(sessionId);

    const userSessions = this.sessionIdsByUser.get(client.userId);
    if (userSessions) {
      userSessions.delete(sessionId);
      if (userSessions.size === 0) {
        this.sessionIdsByUser.delete(client.userId);
      }
    }

    if (client.groupId) {
      const groupSessions = this.sessionIdsByGroup.get(client.groupId);
      if (groupSessions) {
        groupSessions.delete(sessionId);
        if (groupSessions.size === 0) {
          this.sessionIdsByGroup.delete(client.groupId);
        }
      }
    }

    console.log(`[WS] Client disconnected: session=${sessionId}`);
  }

  public updateActivity(sessionId: string) {
    const client = this.clientsBySession.get(sessionId);
    if (client) {
      client.lastActivity = Date.now();
    }
  }

  public sendToSession(sessionId: string, data: object) {
    const client = this.clientsBySession.get(sessionId);
    if (client && client.ws.readyState === WebSocket.OPEN) {
      try {
        client.ws.send(JSON.stringify(data));
      } catch (err) {
        console.error(`[WS] Error sending to session ${sessionId}`, err);
      }
    }
  }

  public sendToUser(userId: string, data: object) {
    const sessionIds = this.sessionIdsByUser.get(userId);
    if (sessionIds) {
      for (const sId of sessionIds) {
        this.sendToSession(sId, data);
      }
    }
  }

  public broadcastToGroup(groupId: string, data: object, excludeSessionId?: string) {
    const sessionIds = this.sessionIdsByGroup.get(groupId);
    if (sessionIds) {
      for (const sId of sessionIds) {
        if (sId !== excludeSessionId) {
          this.sendToSession(sId, data);
        }
      }
    }
  }

  public broadcastAll(data: object) {
    for (const sId of this.clientsBySession.keys()) {
      this.sendToSession(sId, data);
    }
  }

  public closeSession(sessionId: string, code: number, reason: string) {
    const client = this.clientsBySession.get(sessionId);
    if (client) {
      try {
        client.ws.close(code, reason);
      } catch {}
      this.removeConnection(sessionId);
    }
  }

  public closeUserSessions(userId: string, code: number, reason: string) {
    const sessionIds = this.sessionIdsByUser.get(userId);
    if (sessionIds) {
      const copy = Array.from(sessionIds);
      for (const sId of copy) {
        this.closeSession(sId, code, reason);
      }
    }
  }

  private startPingInterval() {
    this.pingInterval = setInterval(() => {
      const now = Date.now();
      for (const [sessionId, client] of this.clientsBySession.entries()) {
        // If inactive for more than 90 seconds, close with 1000
        if (now - client.lastActivity > 90000) {
          console.log(`[WS] Inactivity timeout (90s) for session ${sessionId}`);
          try {
            client.ws.close(1000, 'Inactivity timeout');
          } catch {}
          this.removeConnection(sessionId);
          continue;
        }

        // Send ping frame or custom ping message
        if (client.ws.readyState === WebSocket.OPEN) {
          try {
            client.ws.ping();
          } catch {}
        }
      }
    }, 30000); // Check every 30 seconds
  }

  public stop() {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
    }
  }
}

export const wsManager = new WebSocketManager();
