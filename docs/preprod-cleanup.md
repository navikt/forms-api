# Rydd skjemaer i preprod

Skriptet sletter skjemaer fysisk fra databasen som preprod og preprod-alt deler.
Det fjerner også revisjoner, skjemaspesifikke oversettelser og publiseringshistorikk.
Etter commit kan slettingen ikke angres med rollback.

Varsle dem som tester i begge miljøene, og avtal et vedlikeholdsvindu uten skriving.
Avklar backup og eventuell gjenoppretting med teamet før kjøring.
Skriptet kjøres manuelt fra maskinen din, aldri ved deploy eller oppstart.

## Før du starter

Du trenger Nais CLI, `psql` 18 og personlig databasetilgang til dev med
SELECT-, DELETE-, TEMP- og nødvendige låserettigheter. Innlogging gir ikke
skriverettigheter. Bruk teamets tilgangsoppsett, ikke applikasjonens databasepassord
eller `prepare --all-privileges`.

Kjør fra roten av forms-api-repoet. Kommandoen velger alltid `dev-gcp`,
`fyllut-sendinn` og `forms-api`, uavhengig av lokale standardvalg.
Syntaksen er kontrollert med Nais CLI 6.5.0.

```bash
nais login
nais cloudsql psql forms-api \
  --environment dev-gcp \
  --team fyllut-sendinn \
  --reason "Manuell opprydding av skjemaer i preprod"
```

Resten av kommandoene kjøres i dette psql-skallet, i samme tilkobling.
Ikke pipe SQL til Nais CLI eller legg til `psql -f`-flagg i Nais-kommandoen.
Bruk en egen tilkobling uten en pågående transaksjon og med AUTOCOMMIT på.

## Velg hva du beholder

```sql
SELECT current_database();
\set cleanup_database 'forms-api-preprod-db'
\set keep_paths '["skjema-som-skal-beholdes"]'
\set keep_test_forms true
\set keep_locked_forms true
\i scripts/preprod-cleanup/preview.sql
```

`cleanup_database` er navnet tilkoblingen må ha, ikke en kommando for å bytte
database. Standardverdien er `forms-api-preprod-db`. For en lokal database kan du
sette `\set cleanup_database 'forms-api-local'`. Tom verdi eller feil navn gir
avbrudd før preview eller sletting.

Bytt ut eksempel-pathen med eksisterende paths. Bruk `\set keep_paths '[]'`
uten eksplisitte unntak. I et nytt psql-skall er standardverdiene tom path-liste
og `true` for begge flagg. Sett et flagg til `false` for å slå av regelen.
Verdiene blir stående mellom kjøringer i samme skall.

Minst én behold-regel er nok til å bevare hele skjemaet uendret. Teststatus kommer
fra `isTestForm` i siste revisjons properties. Låst betyr at `form.lock` ikke er
NULL. Soft-slettede skjemaer inngår også i utvalget.
Ukjente, tomme eller dupliserte paths gir feil. NULL-path og ugyldig testmerking
gir også feil, i stedet for at skriptet gjetter hva som kan slettes.

Preview viser sletteliste og behold-liste med antall og skjema-ID-er, samt antall
rader per tabell. Sammenlign volumet med den lokale volumtesten før gjennomføring.
Den endrer ikke applikasjonens data og holder ikke skrivelåser mens du vurderer
listene. Planen finnes bare i tilkoblingen. Gjennomføring bruker denne planen,
ikke senere endringer i variablene. Endrer du behold-reglene, må du kjøre preview
på nytt.

## Gjennomfør slettingen

Kontroller begge listene før du kjører:

```sql
\i scripts/preprod-cleanup/execute.sql
```

Skriptet ber deg bekrefte slettingen i preprod og preprod-alt. Skriv nøyaktig:

```text
DELETE preprod and preprod-alt
```

Slettingen skjer i én transaksjon. Skriptet låser skjematabellene mot skriving,
beregner utvalget på nytt og avviser endringer siden preview. Også endringer i
beholdte skjemaer eller delte attributter kan kreve ny preview.
Låsene kan blokkere vanlig API-skriving i vedlikeholdsvinduet.
Skriptet venter maksimalt 5 sekunder på en lås. Hver SQL-setning har en grense på
120 sekunder, og slettetransaksjonen har en grense på 180 sekunder.
FK-kontroller uten indeks skanner revisjoner for hvert slettet attributt.
Ved større datamengder enn volumtesten må du måle lokalt før kjøring.
Ikke øk tidsgrensene eller innfør batcher uten ny avklaring.

SQL krever at databasenavnet matcher `cleanup_database`. Produksjonsnavnet
`forms-api-db` avvises alltid, også når det oppgis som forventet navn. Denne
sperren har ingen konfigurerbar overstyring. Oppdater sperren dersom
produksjonsdatabasen får nytt navn.

Bruk alltid den faste dev-kommandoen og personlig dev-tilgang. SQL kan ikke bevise
hvilket GCP-prosjekt eller cluster en tilkobling tilhører. Et produksjonssystem
med et annet databasenavn kan derfor ikke identifiseres av navnesperren alene.

## Ved feil eller avbrudd

Ved SQL-feil stopper filkjøringen uten commit. Kjør `ROLLBACK;` straks i psql,
eller avslutt tilkoblingen med `\q`, slik at transaksjonen og låsene frigjøres.
Rett årsaken, kjør preview på nytt og vurder listene før et nytt forsøk.
Det er trygt å ta ny preview etter en vellykket sletting også.

Hvis forbindelsen brytes før commit, ruller databasen tilbake transaksjonen når
forbindelsen avsluttes. Ved forbindelsesbrudd under commit kan utfallet være
ukjent. Koble til på nytt og kontroller hvilke skjema-ID-er som fortsatt finnes
før du forsøker igjen. En pod-restart gjenopptar ikke denne lokale kjøringen.

## Hva skriptet ikke rydder

Globale oversettelser og deres publiserte snapshots, mottakere og statiske PDF-er
i GCS beholdes. Delte attributter beholdes så lenge en gjenværende revisjon bruker
dem. Skriptet rydder ikke andre ubrukte attributter.

Filer i `navikt/skjemautfylling-formio` på `test-publishing` blir liggende.
Kontroller gjeldende `forms-source` og konsumenter før kjøring, og planlegg
filopprydding separat. Ikke bruk vanlig unpublish etter fysisk sletting.
Skriptet kopierer ikke produksjonsdata. Bruk eksisterende import ved behov.

## Kjør testene lokalt

Docker må kjøre. Testene bruker `ApplicationTest` og PostgreSQL 18 i en isolert
container med repoets migrasjoner. SQL-filene kjøres med containerens `psql`.
Du trenger ikke installere `psql` lokalt for å kjøre testene.
Test og `local` bruker samme `EmbeddedDb`-konfigurasjon med databasen
`forms-api-local`. Testene oppgir dette navnet gjennom `cleanup_database`.
Volumtesten er utelatt som standard, også ved `mvn install`.

```bash
mise exec -- mvn -Dtest=PreprodCleanupSqlTest test
mise exec -- mvn clean install
```

Kjør volumtesten separat ved behov:

```bash
mise exec -- mvn -Pcleanup-volume -Dtest=PreprodCleanupSqlTest test
```

Profilen kjører bare testen merket `volume`. Den bruker 300 skjemaer,
900 revisjoner, 2 400 attributter og 4 KiB komponentinnhold per revisjon,
med oversettelser og publiseringer. Halvparten beholdes. Testen skriver
målt tid, ikke en garanti for kjøretiden i preprod.
