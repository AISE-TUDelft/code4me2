import {createServer, type Server} from "node:http";

/**
 * Optional participant-owned provider compatibility path for BYOA Codex.
 *
 * The legacy Code4Me inference proxy takes precedence: when CODEX_PROXY_URL is
 * already set, its server-side observation must not be bypassed. Otherwise a
 * participant can point CODEX_UPSTREAM_URL at an OpenAI-compatible Responses
 * endpoint. Codex talks only to this loopback server; the relay applies the
 * declared output budget and forwards with the participant's own credential.
 */
export async function startCompatibleResponsesRelay(
    environment: NodeJS.ProcessEnv = process.env,
): Promise<{server: Server; url: string} | null> {
    const baseUrl = environment["CODEX_UPSTREAM_URL"]?.trim();
    if (!baseUrl || environment["CODEX_PROXY_URL"]) return null;

    const destination = new URL(baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`);
    if (destination.protocol !== "https:" &&
        !(destination.protocol === "http:" && ["localhost", "127.0.0.1", "::1"].includes(destination.hostname))) {
        throw new Error("CODEX_UPSTREAM_URL must use HTTPS or loopback HTTP");
    }
    const apiKey = environment["CODEX_UPSTREAM_API_KEY"]?.trim() || environment["OPENAI_API_KEY"]?.trim();
    if (!apiKey) throw new Error("CODEX_UPSTREAM_API_KEY or OPENAI_API_KEY is required for the compatible provider");
    const rawLimit = environment["CODEX_MAX_OUTPUT_TOKENS"]?.trim();
    const outputLimit = rawLimit && /^[1-9][0-9]*$/.test(rawLimit) ? Number(rawLimit) : null;
    if (rawLimit && (!outputLimit || !Number.isSafeInteger(outputLimit))) {
        throw new Error("CODEX_MAX_OUTPUT_TOKENS must be a positive integer");
    }

    const server = createServer(async (request, response) => {
        if (request.method !== "POST" || request.url !== "/v1/responses") {
            response.writeHead(404).end();
            return;
        }
        try {
            const chunks: Buffer[] = [];
            let bytes = 0;
            for await (const chunk of request) {
                const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
                bytes += buffer.length;
                if (bytes > 32 * 1024 * 1024) {
                    response.writeHead(413).end();
                    return;
                }
                chunks.push(buffer);
            }
            const body = normalizeCompatibleResponsesBody(JSON.parse(Buffer.concat(chunks).toString("utf8")), outputLimit);
            const upstream = await fetch(new URL("responses", destination), {
                method: "POST",
                headers: {"Content-Type": "application/json", "Authorization": `Bearer ${apiKey}`},
                body: JSON.stringify(body),
            });
            response.writeHead(upstream.status, {"Content-Type": upstream.headers.get("content-type") ?? "application/json"});
            if (upstream.body) {
                for await (const chunk of upstream.body) response.write(chunk);
            }
            response.end();
        } catch {
            // Neither provider credentials nor request content belong in logs.
            if (!response.headersSent) response.writeHead(502);
            response.end();
        }
    });
    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("compatible relay did not bind to loopback");
    return {server, url: `http://127.0.0.1:${address.port}/v1`};
}

/** Strip non-standard Codex metadata rejected by compatible Responses providers. */
export function normalizeCompatibleResponsesBody(body: unknown, outputLimit: number | null): Record<string, unknown> {
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("Responses request must be an object");
    const normalized = {...body} as Record<string, unknown>;
    for (const field of ["reasoning", "include", "text", "prompt_cache_key", "store", "client_metadata", "service_tier", "parallel_tool_calls"]) {
        delete normalized[field];
    }
    if (Array.isArray(normalized["input"])) {
        normalized["input"] = normalized["input"].filter(
            item => !(item && typeof item === "object" && !Array.isArray(item) && (item as Record<string, unknown>)["type"] === "additional_tools"),
        );
    }
    if (outputLimit !== null) {
        const requested = normalized["max_output_tokens"];
        normalized["max_output_tokens"] = typeof requested === "number" && Number.isInteger(requested) && requested > 0
            ? Math.min(requested, outputLimit)
            : outputLimit;
    }
    return normalized;
}
