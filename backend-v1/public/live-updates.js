// Authenticated fetch streaming keeps installation credentials out of URLs.
export async function readEvents(body, onEvent, onChunk = () => {}) {
  const reader = body.getReader(), decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      const {value, done} = await reader.read();
      if (done) break;
      onChunk();
      buffer = (buffer + decoder.decode(value, {stream: true})).replace(/\r\n/g, '\n');
      if (buffer.length > 1000000) throw Error('Event stream frame too large');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
        const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (data) { let event; try { event = JSON.parse(data); } catch { continue; } onEvent(event); }
      }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export function startLiveUpdates({url, headers, onUpdate, fetcher = fetch, retryMs = 1000, idleMs = 45000}) {
  let stopped = false, connected = false, controller, reconnectTimer, idleTimer, attempt = 0;
  const touch = () => { clearTimeout(idleTimer); idleTimer = setTimeout(() => controller?.abort(), idleMs); };
  async function connect() {
    if (stopped) return;
    controller = new AbortController(); touch();
    try {
      const response = await fetcher(url, {headers: {...headers, Accept: 'text/event-stream'}, cache: 'no-store', signal: controller.signal});
      if (!response.ok || !response.body) throw Error('Live updates unavailable');
      connected = true; attempt = 0; onUpdate();
      await readEvents(response.body, () => onUpdate(), touch);
    } catch { /* Periodic state reads remain available while reconnecting. */ }
    finally {
      connected = false; clearTimeout(idleTimer);
      if (!stopped) reconnectTimer = setTimeout(connect, Math.min(15000, retryMs * 2 ** attempt++));
    }
  }
  connect();
  return {isConnected: () => connected, stop() { stopped = true; connected = false; clearTimeout(reconnectTimer); clearTimeout(idleTimer); controller?.abort(); }};
}
