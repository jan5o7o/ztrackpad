/**
 * vdisplay - drive ztrackpad's Android virtual display from the agent.
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
	join(homedir(), "ztrackpad", "skills", "ztrackpad-vdisplay", "scripts", "vdisplay"),
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

async function vdisplay(op: string, extra: string[]): Promise<string> {
	const args = [op, ...extra];
	const { stdout, stderr } = await run(scriptPath(), args, { timeout: 60_000 });
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
		"half of the phone screen (with width/height it sizes the display instead of the " +
		"default); with headless=true it opens one with no render target that can still " +
		"host apps off-screen but cannot be seen or driven. 'launch' starts an app on the " +
		"display (package or component, optionally with a URL) through ztrackpad's shell " +
		"bridge, so it also works for headless displays. 'target' points the trackpad's " +
		"input at a display (by id or by package), or reads it back. 'shot' captures the " +
		"display's own pixels to /data/local/tmp/<name>.png, free of the phone-screen " +
		"composite - pull that file with adb. 'hide' drops the window while keeping the " +
		"display and its apps running, 'show' brings it back, and 'destroy' releases the " +
		"display and whatever was running on it. Requires ztrackpad's accessibility " +
		"service plus Shizuku, and an adb connection.",
	parameters: Type.Object({
		op: Type.Union(
			[
				Type.Literal("status"),
				Type.Literal("create"),
				Type.Literal("show"),
				Type.Literal("hide"),
				Type.Literal("destroy"),
				Type.Literal("launch"),
				Type.Literal("target"),
				Type.Literal("shot"),
			],
			{ description: "Which action to perform." },
		),
		headless: Type.Optional(
			Type.Boolean({
				description:
					"For op=create only: make a headless display (no render target; hosts apps but cannot be seen or driven). Defaults to false.",
			}),
		),
		width: Type.Optional(
			Type.Integer({
				description:
					"For op=create only: display width in px (e.g. 1812). Use with height. Default stays 1920x1080.",
			}),
		),
		height: Type.Optional(
			Type.Integer({
				description:
					"For op=create only: display height in px (e.g. 2176). Use with width.",
			}),
		),
		target: Type.Optional(
			Type.String({
				description:
					"For op=launch: the package name or component (com.android.settings, or com.android.settings/.Settings) to start on the display. For op=target: the display id (0 = phone screen) or a package name to point the trackpad's input at.",
			}),
		),
		url: Type.Optional(
			Type.String({
				description:
					"For op=launch only: a URL to open on the display (launched with the VIEW action).",
			}),
		),
		shotName: Type.Optional(
			Type.String({
				description:
					"For op=shot only: the PNG file name (no extension) under /data/local/tmp. Defaults to vdisplay-<id>.",
			}),
		),
	}),

	async execute(_toolCallId, params) {
		const extra: string[] = [];
		if (params.headless) extra.push("--headless");
		if (params.width != null && params.height != null) {
			extra.push("--w", String(params.width), "--h", String(params.height));
		}
		if (params.target) extra.push(params.target);
		if (params.url) extra.push("--url", params.url);
		if (params.shotName) extra.push("--name", params.shotName);
		const status = await vdisplay(params.op, extra);
		return {
			content: [{ type: "text", text: status }],
			details: { op: params.op, status, params },
		};
	},
});

export default function (pi: ExtensionAPI) {
	pi.registerTool(vdisplayTool);
}