import { readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const envPath = resolve(desktopDirectory, ".env");
const envText = await readFile(envPath, "utf8").catch((error) => {
  if (error.code === "ENOENT") {
    console.error("Missing desktop/.env. Copy desktop/.env.example to desktop/.env and configure it.");
    process.exit(1);
  }
  throw error;
});

for (const line of envText.split(/\r?\n/)) {
  const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
  if (!match || Object.hasOwn(process.env, match[1])) continue;
  const [, name] = match;
  let value = match[2];
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    value = value.slice(1, -1);
  } else {
    value = value.replace(/\s+#.*$/, "");
  }
  process.env[name] = value;
}

for (const name of ["VITE_RESUMEKIT_API_URL", "RESUMEKIT_API_KEY"]) {
  if (!process.env[name]?.trim()) {
    console.error(`Missing ${name} in desktop/.env.`);
    process.exit(1);
  }
}

const tauriCli = resolve(desktopDirectory, "node_modules", "@tauri-apps", "cli", "tauri.js");
const child = spawn(process.execPath, [tauriCli, ...process.argv.slice(2)], {
  cwd: desktopDirectory,
  env: process.env,
  stdio: "inherit",
});

child.on("error", (error) => {
  console.error(`Could not start Tauri: ${error.message}`);
  process.exitCode = 1;
});
child.on("exit", (code, signal) => {
  process.exitCode = signal ? 1 : (code ?? 1);
});
