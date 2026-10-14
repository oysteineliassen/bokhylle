// Røyktest av Bokhylle på emulator (kjøres i GitHub Actions). Tar skjermbilder underveis (skjerm-*.png).
// QUICK=1: bare oppstart og bibliotek (brukes for release-bygget, der testmottakeren ikke finnes).
import { execSync } from 'node:child_process';

const PKG = 'no.oystein.bokhylle';
const QUICK = process.env.QUICK === '1';
const sh = (c) => execSync(c, { encoding: 'utf8', maxBuffer: 64 << 20 }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const prefix = QUICK ? 'rel-' : '';

function shot(name) {
  try { sh(`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png skjerm-${prefix}${name}.png`); } catch {}
}
function log() { try { return sh(`adb logcat -d -s Bokhylle:D`); } catch { return ''; } }
function fail(msg) {
  console.error('FEIL: ' + msg);
  console.error('Bokhylle-logg:\n' + log());
  try { console.error(sh(`adb logcat -d -s AndroidRuntime:E | tail -80`)); } catch {}
  shot('feil');
  process.exit(1);
}
function dump() {
  for (let i = 0; i < 5; i++) {
    try {
      sh('adb shell uiautomator dump /sdcard/ui.xml');
      return sh('adb shell cat /sdcard/ui.xml');
    } catch { }
  }
  return '';
}
function nodes(xml) {
  const out = [];
  for (const m of xml.matchAll(/<node [^>]*>/g)) {
    const a = m[0];
    const text = (a.match(/ text="([^"]*)"/) || [])[1] || '';
    const desc = (a.match(/ content-desc="([^"]*)"/) || [])[1] || '';
    const b = (a.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || []).slice(1).map(Number);
    if (b.length === 4) out.push({ text: text.replace(/&amp;/g, '&').replace(/&quot;/g, '"'), desc, x: (b[0] + b[2]) >> 1, y: (b[1] + b[3]) >> 1, b });
  }
  return out;
}
const visible = () => nodes(dump()).map((n) => n.text || n.desc).filter(Boolean).join(' | ');
async function waitFor(pred, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const n = nodes(dump()).find(pred);
    if (n) return n;
    await sleep(700);
  }
  return null;
}
async function need(re, what, ms) {
  const n = await waitFor((n) => re.test(n.text) || re.test(n.desc), ms);
  if (!n) fail(`fant ikke ${what} (${re}). Synlig: ` + visible());
  return n;
}
const tap = (n) => sh(`adb shell input tap ${n.x} ${n.y}`);
const type = (t) => sh(`adb shell input text '${t.replace(/ /g, '%s')}'`);
function assertAlive() {
  let pid = '';
  try { pid = sh(`adb shell pidof ${PKG}`); } catch {}
  if (!pid) fail('appen kjører ikke (krasj?)');
  if (sh(`adb logcat -d -s AndroidRuntime:E`).includes(PKG)) fail('krasj i logcat');
}

sh('adb logcat -c');
sh('adb shell input keyevent KEYCODE_WAKEUP');
sh('adb shell wm dismiss-keyguard || true');
sh(`adb shell am start -W -n ${PKG}/.MainActivity`);

// 1. Tomt bibliotek
await need(/Ingen bøker ennå/, 'tomt bibliotek', 30000);
shot('1-bibliotek-tomt');
console.log('Bibliotek OK');

// 2. Ny bok via skjermen
tap(await need(/^Ny bok$/, 'knappen Ny bok'));
tap(await need(/^Tittel$/, 'tittelfelt'));
type('Testboka');
tap(await need(/^Forfatter$/, 'forfatterfelt'));
type('Ola Nordmann');
tap(await need(/^Opprett bok$/, 'Opprett bok'));
await need(/^0 sider$|Velg bilder/, 'bokskjermen');
shot('2-ny-bok');
// Tilbake til biblioteket (første «tilbake» kan gå med til å skjule tastaturet).
for (let i = 0; i < 3 && !(await waitFor((n) => n.text === 'Testboka' && /Ola/.test(visible()), 1500)); i++) {
  sh('adb shell input keyevent KEYCODE_BACK');
}
await need(/^Testboka$/, 'boka i biblioteket');
assertAlive();
shot('3-bibliotek');
console.log('Ny bok OK');

if (QUICK) {
  console.log('Hurtigtest OK');
  process.exit(0);
}

console.log('Alt OK');
