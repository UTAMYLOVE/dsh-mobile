// Temporary verification plugin for Task 0 (dsh internals recon). Delete before Task 6.
// Registers: tool `hello` -> "hi"; GET /dshm/health/hello -> {"ok":true} (no auth);
// GET /dshm/health/hello-auth (dsh cookie auth via connection.requestRejection);
// a runtime-context probe (session-start injection) and an index <style> probe.
// No imports: ctx.tools.register accepts a raw ToolDefinition object.

export const name = 'dshm-hello'
export const inject = ['tools', 'webServer', 'systemPrompt']

export function apply(ctx) {
  // Session-start context injection probe: a global runtime-context entry is
  // materialized into the first user-role message of every agent request.
  // (systemPrompt.context returns an effect disposer owned by ctx.)
  ctx.systemPrompt.context({
    name: 'dshm-hello-probe',
    order: 9000,
    interpolate: false,
    text: () => 'DSHM_HELLO_CONTEXT_PROBE: hello plugin context is present.',
  })

  // ctx.tools.register is itself an effect owned by ctx (auto-unregistered on unload).
  ctx.tools.register({
    name: 'hello',
    description: 'Health-check tool for DSH Mobile. Takes no arguments and returns "hi".',
    parameters: { type: 'object', properties: {} },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return 'hi'
    },
  })

  // ctx.webServer.register returns a disposer; ctx.effect ties it to the plugin lifetime.
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/dshm/health/hello',
    handler(req, res) {
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        res.writeHead(405, { allow: 'GET, HEAD' })
        res.end()
        return
      }
      const body = JSON.stringify({ ok: true })
      res.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(body),
        'cache-control': 'no-store',
      })
      res.end(req.method === 'HEAD' ? undefined : body)
    },
  }), 'dshm-hello.route')

  // Same answer, but behind dsh's own Host/Origin fence + browser-cookie auth.
  // ctx.connection exists only in the Web composition, so read it lazily.
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/dshm/health/hello-auth',
    handler(req, res) {
      const connection = ctx.get('connection')
      const rejection = connection === undefined ? 503 : connection.requestRejection(req)
      if (rejection !== undefined) {
        res.writeHead(rejection)
        res.end()
        return
      }
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      res.end(JSON.stringify({ ok: true }))
    },
  }), 'dshm-hello.route-auth')

  // Theming hook probe: host-side index injection (no client bundle needed).
  // A later theme plugin pushes real --dsw-alias-* overrides the same way.
  ctx.on('webserver/index-inject', (table) => {
    table.push({ kind: 'style', text: '/* dshm-hello theme probe */ body { --dshm-probe: 1; }' })
  })

  ctx.logger.info('[dshm-hello] loaded')
}
