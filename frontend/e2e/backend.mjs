// Starts the Spring backend on a fresh temporary vault for the end-to-end tests (see playwright.config.ts).
// Needs a JDK 25: JAVA_HOME when it is one, otherwise the newest one under ~/.jdks (where IDEs keep theirs).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const port = process.env.KBMD_E2E_API_PORT ?? '8797';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function jdkVersion(dir) {
  try {
    const match = /JAVA_VERSION="(\d+)/.exec(readFileSync(path.join(dir, 'release'), 'utf8'));
    return match ? Number(match[1]) : 0;
  } catch {
    return 0;
  }
}

function findJdk() {
  if (process.env.JAVA_HOME && jdkVersion(process.env.JAVA_HOME) >= 25) return process.env.JAVA_HOME;
  const jdks = path.join(homedir(), '.jdks');
  if (!existsSync(jdks)) return null;
  return readdirSync(jdks)
    .map((name) => path.join(jdks, name))
    .filter((dir) => jdkVersion(dir) >= 25)
    .sort()
    .pop();
}

const jdk = findJdk();
if (!jdk) {
  console.error('kbmd e2e: no JDK 25 found. Point JAVA_HOME at one.');
  process.exit(1);
}

// a fresh vault for every run; what earlier runs left behind goes
for (const name of readdirSync(tmpdir())) {
  if (name.startsWith('kbmd-e2e-')) rmSync(path.join(tmpdir(), name), { recursive: true, force: true });
}
const vault = mkdtempSync(path.join(tmpdir(), 'kbmd-e2e-'));
console.log(`kbmd e2e backend: port ${port}, vault ${vault}, JDK ${jdk}`);

// the vault and the port go in as environment variables: Spring reads them, and nothing needs quoting
const mvnw = path.join(root, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
const child = spawn(`"${mvnw}" -q -DskipFrontend=true spring-boot:run`, {
  cwd: root,
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, JAVA_HOME: jdk, KBMD_VAULT: vault, SERVER_PORT: port },
});
child.on('exit', (code) => process.exit(code ?? 1));
