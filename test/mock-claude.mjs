// Etterligner Claude API (Messages og Message Batches) for røyktesten. Lytter på 127.0.0.1:8089;
// emulatoren når den via «adb reverse tcp:8089 tcp:8089».
import http from 'node:http';

const batches = new Map(); // id -> { ids, polls }
let calls = { batchCreate: 0, batchGet: 0, results: 0, messages: 0 };

function layout(id) {
  return JSON.stringify({
    page_number: null,
    running_header: 'TESTBOKA',
    running_footer: null,
    continues_from_previous: false,
    continues_to_next: false,
    blocks: [
      { type: 'heading', text: 'Fra Claude', title: null, description: null, number: null, bbox: [0.1, 0.05, 0.9, 0.1] },
      { type: 'paragraph', text: `Tekst fra Claude for side ${id}.[^1]`, title: null, description: null, number: null, bbox: [0.1, 0.12, 0.9, 0.4] },
      { type: 'box', text: 'Innhold i faktaboksen.', title: 'Visste du?', description: null, number: null, bbox: [0.1, 0.42, 0.9, 0.55] },
      { type: 'figure', text: null, title: null, description: 'En tegning av en bokhylle', kind: 'tegning', number: null, bbox: [0.2, 0.57, 0.8, 0.85] },
      { type: 'caption', text: 'Figur 1. Bokhylla.', title: null, description: null, number: null, bbox: [0.2, 0.86, 0.8, 0.88] },
      { type: 'footnote', text: 'Dette er fotnoten.', title: null, description: null, number: '1', bbox: [0.1, 0.9, 0.9, 0.93] },
    ],
  });
}

const SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 200"><rect x="5" y="5" width="290" height="190" rx="12" fill="#F4C766"/>' +
  '<path d="M20 100 Q 60 20 100 100 T 180 100 T 260 100" fill="none" stroke="#222" stroke-width="3"/>' +
  '<text x="150" y="40" text-anchor="middle" font-family="serif" font-size="18">Bokhylla</text></svg>';

function message(id, model, text) {
  return {
    id: `msg_test_${id}`,
    type: 'message',
    role: 'assistant',
    model,
    content: [{ type: 'text', text: text ?? layout(id), citations: null }],
    stop_reason: 'end_turn',
    stop_sequence: null,
    stop_details: null,
    container: null,
    usage: {
      input_tokens: 3100, output_tokens: 1200, cache_creation_input_tokens: 0, cache_read_input_tokens: 0,
      cache_creation: null, server_tool_use: null, service_tier: 'standard', inference_geo: null,
    },
  };
}

function batchObject(id) {
  const b = batches.get(id);
  const ended = b.polls >= 1 || b.created; // opprettelsen svarer «in_progress», første sjekk «ended»
  return {
    id, type: 'message_batch',
    processing_status: ended ? 'ended' : 'in_progress',
    request_counts: { processing: ended ? 0 : b.ids.length, succeeded: ended ? b.ids.length : 0, errored: 0, canceled: 0, expired: 0 },
    created_at: '2026-10-14T10:00:00Z', expires_at: '2026-10-15T10:00:00Z',
    ended_at: ended ? '2026-10-14T10:05:00Z' : null, cancel_initiated_at: null, archived_at: null,
    results_url: ended ? `http://127.0.0.1:8089/v1/messages/batches/${id}/results` : null,
  };
}

function send(res, status, body, type = 'application/json') {
  res.writeHead(status, { 'content-type': type, 'request-id': 'req_test' });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  let data = '';
  req.on('data', (c) => (data += c));
  req.on('end', () => {
    const url = req.url.split('?')[0];
    if (req.method === 'GET' && url === '/kall') return send(res, 200, calls);
    if (req.headers['x-api-key'] !== 'test-nokkel-1234') return send(res, 401, { type: 'error', error: { type: 'authentication_error', message: 'feil nøkkel' } });
    try {
      if (req.method === 'POST' && url === '/v1/messages/batches') {
        calls.batchCreate++;
        const body = JSON.parse(data);
        const bad = body.requests.find((r) => !r.params.messages?.[0]?.content?.some((c) => c.type === 'image' && c.source?.data?.length > 1000)
          || r.params.output_config?.format?.type !== 'json_schema');
        if (bad) return send(res, 400, { type: 'error', error: { type: 'invalid_request_error', message: 'mangler bilde eller skjema' } });
        const id = `msgbatch_test_${calls.batchCreate}`;
        const models = Object.fromEntries(body.requests.map((r) => [r.custom_id, r.params.model]));
        batches.set(id, { ids: body.requests.map((r) => r.custom_id), models, polls: 0 });
        calls.models = calls.models ?? {};
        for (const m of Object.values(models)) calls.models[m] = (calls.models[m] ?? 0) + 1;
        console.log(`mock: batch ${id} med ${body.requests.length} sider (${JSON.stringify(calls.models)})`);
        const out = batchObject(id);
        batches.get(id).created = true;
        return send(res, 200, out);
      }
      let m = url.match(/^\/v1\/messages\/batches\/([^/]+)$/);
      if (req.method === 'GET' && m && batches.has(m[1])) {
        calls.batchGet++;
        const out = batchObject(m[1]);
        batches.get(m[1]).polls++;
        return send(res, 200, out);
      }
      m = url.match(/^\/v1\/messages\/batches\/([^/]+)\/results$/);
      if (req.method === 'GET' && m && batches.has(m[1])) {
        calls.results++;
        const b = batches.get(m[1]);
        const lines = b.ids.map((id) => JSON.stringify({ custom_id: id, result: { type: 'succeeded', message: message(id, b.models[id]) } }));
        return send(res, 200, lines.join('\n') + '\n', 'application/binary');
      }
      if (req.method === 'POST' && url === '/v1/messages') {
        calls.messages++;
        const body = JSON.parse(data);
        const system = Array.isArray(body.system) ? body.system.map((s) => s.text).join(' ') : String(body.system ?? '');
        if (system.includes('SVG')) {
          calls.draw = (calls.draw ?? 0) + 1;
          if (!body.messages?.[0]?.content?.some((c) => c.type === 'image' && c.source?.data?.length > 500)) {
            return send(res, 400, { type: 'error', error: { type: 'invalid_request_error', message: 'mangler figurbildet' } });
          }
          console.log(`mock: tegning (${body.model})`);
          return send(res, 200, message('tegning', body.model, 'Her er tegningen:\n' + SVG));
        }
        console.log(`mock: enkeltkall (${body.model}), fallbacks=${JSON.stringify(body.fallbacks)}`);
        return send(res, 200, message('enkelt', body.model));
      }
      send(res, 404, { type: 'error', error: { type: 'not_found_error', message: url } });
    } catch (e) {
      send(res, 500, { type: 'error', error: { type: 'api_error', message: String(e) } });
    }
  });
});
server.listen(8089, '127.0.0.1', () => console.log('mock: lytter på 8089'));
