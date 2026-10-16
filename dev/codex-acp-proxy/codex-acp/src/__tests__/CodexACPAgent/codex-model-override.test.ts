import {describe, expect, it, vi} from "vitest";
import type * as acp from "@agentclientprotocol/sdk";
import {AgentMode} from "../../AgentMode";
import {CodexAcpClient} from "../../CodexAcpClient";
import type {CodexAppServerClient} from "../../CodexAppServerClient";
import {ModelId} from "../../ModelId";

describe("Codex study model override", () => {
    it("uses the frozen external model and no fallback reasoning effort on the actual turn", async () => {
        const runTurn = vi.fn().mockResolvedValue({});
        const client = new CodexAcpClient(
            {runTurn} as unknown as CodexAppServerClient,
            undefined,
            undefined,
            "qwen2.5-coder:1.5b",
        );
        vi.spyOn(client as any, "refreshSkills").mockResolvedValue(undefined);

        await client.sendPrompt(
            {sessionId: "study-session", prompt: [{type: "text", text: "hello"}]} as acp.PromptRequest,
            AgentMode.ReadOnly,
            ModelId.create("gpt-6-astra", "high"),
            null,
            false,
            "/private/tmp",
        );

        expect(runTurn).toHaveBeenCalledWith(expect.objectContaining({
            model: "qwen2.5-coder:1.5b",
            effort: null,
        }));
    });
});
