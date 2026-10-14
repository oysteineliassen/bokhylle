// Røyktest av Bokhylle på emulator (kjøres i GitHub Actions). Tar skjermbilder underveis (skjerm-*.png).
// Testes på qa-bygget (krympet som release, med testmottakeren). QUICK=1: bare oppstart og bibliotek (release-bygget).
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
  try { console.error('UI:\n' + nodes(dump()).map((n) => `${JSON.stringify(n.text)} ${JSON.stringify(n.desc)} [${n.b}]`).join('\n')); } catch {}
  try { console.error(sh('adb shell wm size') + ' ' + sh('adb shell wm density')); } catch {}
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
tap(await need(/^Opprett bok uten sider$/, 'Opprett bok'));
await need(/^0 sider$/, 'bokskjermen');
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

// 3. Import og OCR av syntetiske boksider (testmottakeren i debug-bygget).
async function report() {
  sh('adb logcat -c');
  sh(`adb shell am broadcast -n ${PKG}/.TestReceiver -a ${PKG}.TEST_REPORT`);
  for (let i = 0; i < 20; i++) {
    await sleep(500);
    const l = log();
    const end = l.match(/RAPPORT-SLUTT (\d+)/);
    if (!end) continue;
    const parts = [...l.matchAll(/RAPPORT\[(\d+)\] (.*)$/gm)].sort((a, b) => a[1] - b[1]).map((m) => m[2]);
    try { return JSON.parse(parts.join('')); } catch (e) { console.log('kunne ikke lese rapport', e.message); }
  }
  return null;
}
sh(`adb shell am broadcast -n ${PKG}/.TestReceiver -a ${PKG}.TEST_IMPORT`);
let rep = null;
const end = Date.now() + 240000;
while (Date.now() < end) {
  await sleep(4000);
  rep = await report();
  const pages = rep?.pages ?? [];
  console.log(`  ${pages.filter((p) => p.status !== 0).length} av ${pages.length} sider behandlet`);
  if (pages.length > 0 && pages.every((p) => p.status !== 0)) break;
}
if (!rep || !rep.pages.length) fail('fikk ingen rapport fra testmottakeren');
console.log(JSON.stringify(rep, null, 1).slice(0, 6000));
const pages = rep.pages;
if (pages.length !== 7) fail(`ventet 7 sider (duplikatet skal hoppes over), fikk ${pages.length}`);
if (pages.some((p) => p.status !== 1)) fail('noen sider feilet: ' + pages.map((p) => p.status).join(','));
const numbers = pages.map((p) => p.number).filter(Boolean);
if (numbers.length < 6) fail('fant for få sidetall: ' + numbers.join(','));
if (pages.some((p) => !p.crop || p.crop === '0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0')) fail('siden ble ikke beskåret');
if (!pages.some((p) => /hyllen|bladd/.test(p.text))) fail('OCR-teksten mangler forventede ord');
assertAlive();
console.log('Import og OCR OK');

// 4. Sortering: sidetallene skal stå i stigende rekkefølge, og side 5 mangler.
const order = pages.map((p) => parseInt(p.number ?? p.inferred?.replace(/[()]/g, ''), 10)).filter((n) => !isNaN(n));
for (let i = 1; i < order.length; i++) if (order[i] < order[i - 1]) fail('feil rekkefølge: ' + order.join(','));
const six = pages.find((p) => p.number === '6');
if (six && !/HOPP/.test(six.flags)) fail('side 6 burde vært flagget for hopp (side 5 mangler): ' + six.flags);
// Kontrollskjermen viser manglende side.
sh(`adb shell am force-stop ${PKG}`);
sh(`adb shell am start -W -n ${PKG}/.MainActivity`);
tap(await need(/^Testbok OCR$/, 'testboka i biblioteket', 20000));
tap(await need(/^Kontroller rekkefølge/, 'Kontroller rekkefølge'));
await need(/^Mangler 5$/, 'manglende side 5');
shot('4-kontroll');
assertAlive();
sh('adb shell input keyevent KEYCODE_BACK');
console.log('Sortering og kontrollskjerm OK');

// 4b. Tekstopprydding (ML Kit): orddeling slått sammen, topptekst fjernet, faktaboks funnet.
const rep4 = await report();
const texts = rep4.pages.map((p) => p.text).join('\n');
if (!/bokhandel/.test(texts)) fail('orddelingen «bok-/handel» ble ikke slått sammen: ' + texts.slice(0, 1500));
if (/TESTBOKA|KAPITTEL EN/.test(texts)) fail('løpende topptekst står fortsatt i teksten');
if (!rep4.pages.some((p) => /"type":"box"/.test(p.layout))) fail('faktaboksen (farget bakgrunn) ble ikke funnet');
console.log('Tekstopprydding OK');

console.log('Alt OK');
