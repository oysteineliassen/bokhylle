# Bokhylle

Android-app som gjør fotograferte, fysiske bøker om til lesbare e-bøker. Kotlin, Jetpack Compose (Material 3),
Room, WorkManager, ML Kit og Claude API. Brukergrensesnittet er på norsk. Krever Android 8.0 (API 26) eller nyere.

## Installere

GitHub Actions bygger, tester og publiserer appen under **Releases** (`bokhylle-v1.0.N`) ved hver endring.
Last ned `Bokhylle-1.0.N.apk` på telefonen og åpne den. Nye versjoner installeres over den gamle, og bøkene beholdes.

## Slik brukes den

1. **Ta bilder** av alle sidene med telefonens vanlige kamera. Rekkefølgen spiller ingen rolle. Ett bilde kan vise
   én side eller et helt oppslag (to sider).
2. **Ny bok** → skriv tittel og forfatter → **Velg bilder** (Photo Picker), **Velg fra Filer** (ingen grense på antall)
   eller **Skann sider i appen** (ML Kits dokumentskanner med kantretting). Eller marker bildene i galleriet og velg
   **Del → Bokhylle**.
3. Appen jobber i bakgrunnen (også om den lukkes): bildet komprimeres, siden finnes og rettes opp, oppslag deles i to,
   teksten leses (OCR) og sidene sorteres etter sidetall.
4. **Kontroller rekkefølge** viser alle sidene. Flaggede sider (mangler sidetall, duplikat, hopp/tilbakegang) er uthevet,
   og manglende sider vises («Mangler 48»), med knapp for å legge dem inn. Hold inne og dra for å flytte en side.
   Trykk på en side for å sette sidetall, godkjenne, slette, bruke som omslag eller justere hjørnene.
5. **Send til Claude** (valgfritt, krever API-nøkkel) for riktige avsnitt, overskrifter, faktabokser, figurer og fotnoter.
6. **Les**: trykk på høyre tredjedel for neste side, venstre for forrige, midten for menyen. Sveip virker også.

### Leseren

- Én bokside per skjerm; får ikke teksten plass, deles den over flere skjermsider. Ingen scrolling.
- Faktabokser vises i farget boks, figurer på riktig plass (trykk for fullskjerm med zoom), fotnoter som hevet skrift
  (trykk for å lese fotnoten).
- Menyen: skriftstørrelse, linjeavstand, serif/sans, lys/sepia/mørk, gå til side, fremdrift.
- **Vis original**: sidebildet med zoom. Her kan du også markere et område og si «dette er en figur» eller «en faktaboks».
- **Rediger tekst**: rett OCR-feil (lagres i `redigertTekst`; råteksten beholdes).
- Siste leste side huskes per bok.

## Claude API

Uten API-nøkkel (eller uten nett) brukes ML Kit på telefonen. Med nøkkel er Claude primær sideanalyse:

- Det beskårne sidebildet (maks 1568 px) sendes, og Claude svarer med JSON etter et fast skjema: ordnede blokker
  (`heading`, `paragraph`, `box`, `figure` med bbox og beskrivelse, `caption`, `footnote`), sidetall og løpende
  topp-/bunntekst for seg, fotnotereferanser som `[^n]`, og om avsnitt fortsetter over sideskift. Svaret lagres i
  `blocksJson`.
- Modell per bok: **Claude Sonnet 5.5** (standard) eller **Claude Haiku 5.5** (mye billigere, holder for enkle romaner).
- Hele bøker sendes med **Message Batches API** (halv pris). Statusen sjekkes i bakgrunnen; batcher tar vanligvis
  minutter, men kan ta opptil et døgn. Enkeltsider kan kjøres på nytt med vanlige kall fra leseren.
- **Kostnadsestimat** vises før sending. Faktisk forbruk (fra `usage` i API-svaret) logges per bok og vises totalt i
  Innstillinger. Anslag med batch-pris: Sonnet ca. 1 USD per 100 sider, Haiku ca. 0,05 USD per 100 sider.
- API-nøkkelen lagres kryptert (AES-GCM med nøkkel i Android Keystore) og logges aldri.
- Ved feil eller uten nett beholdes ML Kit-teksten, og sidene merkes så de kan sendes senere.

## Lagring

- Bildene lagres som WebP (80 %) i appens interne lagring; databasen lagrer bare filstier.
- Full oppløsning (~2000 px) beholdes til boka er kontrollert. **Merk som ferdig kontrollert** skalerer sidebildene
  ned til ~1600 px og sletter bildene fra før beskjæring. **Slett originalbilder** beholder bare tekst og figurer.
  Utklipte figurer lagres alltid i full oppløsning.
- Lagringsbruken vises per bok i biblioteket og på bokskjermen.

## Sikkerhetskopi, eksport og import

- **Eksporter** en bok som `.bokhylle`-fil (zip med `book.json`, `pages.json` og bildene), med eller uten
  originalbilder, eller som **EPUB** for andre lesere. Lagre med «Lagre som …» eller del via Android-delingsmenyen.
- **Send til Kindle** lager en Kindle-vennlig EPUB 3 (enkel CSS, faktabokser med kantlinje og farge, ekte
  popup-fotnoter, figurer på maks 1200 px / JPEG 80 %, omslag fra valgt forside, innholdsfortegnelse fra
  overskriftene, språk «nb») og åpner delingsmenyen med filen.
- **Hele biblioteket** som `.bokhylle-backup` (alle bøkene + leserinnstillinger, aldri API-nøkkelen).
- **Automatisk sikkerhetskopi** (Innstillinger): velg en mappe én gang, velg daglig eller ukentlig. Kopien tas når
  telefonen lader og er på wifi, og de 3 siste beholdes. Dato for siste vellykkede kopi vises, og du får varsel hvis
  den feiler.
- **Åpne `.bokhylle`-filer** fra filbehandler, nedlastinger eller «Del». Finnes boka fra før (samme uuid), vises begge
  endringsdatoene og du velger «Erstatt», «Behold begge» eller «Avbryt»; den nyeste foreslås.
- **Gjenoppretting** viser først dato, antall bøker og en sammenligning (nye bøker, bøker som mangler i kopien,
  bøker som er eldre i kopien) og varsler hvis kopien er eldre enn biblioteket. «Slå sammen» er standard;
  «Erstatt alt» må bekreftes ved å skrive ERSTATT. Før noe erstattes tas det automatisk en kopi
  («Før gjenoppretting – dato», teller ikke blant de 3), og **Angre siste gjenoppretting** i Innstillinger tar den i bruk.
- Formatversjonen sjekkes, og skadde filer gir en feilmelding i stedet for krasj.

## Utvikling

```bash
cd bokhylle
gradle :core:test                  # kjernelogikken (ren Kotlin, ingen Android SDK)
gradle assembleDebug               # krever Android SDK
gradle assembleQa                  # som release (R8), med testmottakeren som røyktesten bruker
```

| Modul | Innhold |
|---|---|
| `core/` | Sidetall (også romertall) i øverste/nederste 12 %, sortering og flagging, kantdeteksjon og deling av oppslag, sideoppsett fra ML Kit (topptekst, orddeling, avsnitt, faktabokser, figurer, fotnoter), JSON. Enhetstester. |
| `app/` | Room (`Book`, `Page`, forbruk, batcher), WorkManager-jobber (behandling, Claude, komprimering, eksport, sikkerhetskopi), Compose-skjermer. |
| `app/src/testkit/` | Testmottaker for røyktesten (bare i debug/qa). |
| `test/smoke.mjs` | Røyktest på Android-emulator i CI: lager syntetiske, skjevt fotograferte sider og et oppslag, og sjekker import, OCR, sidetall, sortering, opprydding, Claude (mot `test/mock-claude.mjs`), leseren og sikkerhetskopi. |
