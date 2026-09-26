/**
 * vdisplay - drive ztrackpad's virtual display from the agent.
 *
 * This wraps skills/ztrackpad-vdisplay/scripts/vdisplay, which broadcasts to ztrackpad.
 * That script is the single implementation (it is also what a human runs by hand), so
 * this extension only locates and invokes it rather than reimplementing the adb call.
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

import { Type } from "@earendil-works/pi-ai";
import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";

const run = promisify(execFile);

const CANDIDATES = [
	process.env.VDISPLAY_SCRIPT,
	join(homedir(), ".pi", "skills", "ztrackpad-vdisplay", "scripts", "vdisplay"),
	join(homedir(), "trackpad", "skills", "ztrackpad-vdisplay", "scripts", "vdisplay"),
].filter((p): p is string => typeof p === "string" && p.length > 0);

function scriptPath(): string {
	for (const p of CANDIDATES) {
		if (existsSync(p)) return p;
	}
	throw new Error(
		`vdisplay script not found. Looked in:\n  ${CANDIDATES.join("\n  ")}\n` +
			"Set VDISPLAY_SCRIPT to its path.",
	);
}

async function vdisplay(op: string, headless: boolean): Promise<string> {
	const args = [op];
	if (headless) args.push("--headless");
	const { stdout, stderr } = await run(scriptPath(), args, { timeout: 30_000 });
	const line = stdout.trim();
	if (!line) throw new Error(stderr.trim() || "vdisplay produced no output");
	return line;
}

const vdisplayTool = defineTool({
	name: "vdisplay",
	label: "Virtual display",
	description:
		"Control ztrackpad's Android virtual display. 'status' reports its state as one " +
		"line of key=value pairs. 'create' opens a floating display rendered in the top " +
		"half of the phone screen; with headless=true it opens one with no render target " +
		"that can still host apps off-screen but cannot be seen or driven. 'hide' drops " +
		"the window while keeping the display and its apps running, 'show' brings it back, " +
		"and 'destroy' releases the display and whatever was running on it. Requires " +
		"ztrackpad's accessibility service plus Shizuku, and an adb connection.",
	parameters: Type.Object({
		op: Type.Union(
			[
				Type.Literal("status"),
				Type.Literal("create"),
				Type.Literal("show"),
				Type.Literal("hide"),
				Type.Literal("destroy"),
			],
			{ description: "Which action to perform." },
		),
		headless: Type.Optional(
			Type.Boolean({
				description:
					"For op=create only: make a headless display (no render target; hosts apps but cannot be seen or driven). Defaults to false.",
			}),
		),
	}),

	async execute(_toolCallId, params) {
		const headless = params.headless ?? false;
		const status = await vdisplay(params.op, headless);
		return {
			content: [{ type: "text", text: status }],
			details: { op: params.op, headless, status },
		};
	},
});

export default function (pi: ExtensionAPI) {
	pi.registerTool(vdisplayTool);
}
