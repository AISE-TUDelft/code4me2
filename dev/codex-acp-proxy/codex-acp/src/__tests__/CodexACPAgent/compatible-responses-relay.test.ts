import {createServer, type Server} from "node:http";
import {describe, expect, it} from "vitest";
import {normalizeCompatibleResponsesBody, startCompatibleResponsesRelay} from "../../CompatibleResponsesRelay";

const close = (server: Server) => new Promise<void>(resolve => {
    server.closeAllConnections();
    server.close(() => resolve());
});

describe("compatible Responses relay", () => {
    it("preserves a smaller caller budget and removes only incompatible metadata", () => {
        const body = normalizeCompatibleResponsesBody({
            input: [{type: "additional_tools"}, {type: "message", role: "user", content: "hello"}],
            max_output_tokens: 128,
            client_metadata: {private: true},
        }, 512);
        expect(body).toEqual({
            input: [{type: "message", role: "user", content: "hello"}],
            max_output_tokens: 128,
        });
    });

    it("does not bypass the Code4Me inference proxy", async () => {
        expect(await startCompatibleResponsesRelay({
            CODEX_PROXY_URL: "http://127.0.0.1:9000/v1",
            CODEX_UPSTREAM_URL: "https://openrouter.ai/api/v1",
        })).toBeNull();
    });

    it("forwards through loopback with a bounded request and participant key", async () => {
        let observed: {path: string | undefined; auth: string | undefined; body: any} | null = null;
        const upstream = createServer(async (request, response) => {
            const chunks: Buffer[] = [];
            for await (const chunk of request) chunks.push(Buffer.from(chunk));
            observed = {
                path: request.url,
                auth: request.headers.authorization,
                body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
            };
            response.writeHead(200, {"Content-Type": "application/json"}).end('{"ok":true}');
        });
        await new Promise<void>(resolve => upstream.listen(0, "127.0.0.1", resolve));
        const address = upstream.address();
        if (!address || typeof address === "string") throw new Error("test upstream did not bind");
        const relay = await startCompatibleResponsesRelay({
            CODEX_UPSTREAM_URL: `http://127.0.0.1:${address.port}/v1`,
            CODEX_UPSTREAM_API_KEY: "test-secret",
            CODEX_MAX_OUTPUT_TOKENS: "512",
        });
        if (!relay) throw new Error("relay did not start");
        try {
            const response = await fetch(`${relay.url}/responses`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({input: [{type: "additional_tools"}, {type: "message", content: "hello"}], max_output_tokens: 65536}),
            });
            expect(response.status).toBe(200);
            expect(await response.json()).toEqual({ok: true});
            expect(observed).toEqual({
                path: "/v1/responses",
                auth: "Bearer test-secret",
                body: {input: [{type: "message", content: "hello"}], max_output_tokens: 512},
            });
        } finally {
            await close(relay.server);
            await close(upstream);
        }
    });
});
