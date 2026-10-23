# Rydd skjemaer i preprod

Sletter skjemaer og tilhørende historikk fysisk fra databasen som preprod og preprod-alt deler.

Fra repoets rot:

```bash
nais login
nais cloudsql psql forms-api \
  --environment dev-gcp \
  --team fyllut-sendinn \
  --reason "Manuell opprydding av skjemaer i preprod"
```

Fortsett i samme psql-skall. Bytt eksempel-pathen, eller bruk `[]` uten ekstra unntak.

```sql
SELECT current_database();
\set cleanup_database 'forms-api-preprod-db'
\set keep_paths '["skjema-som-skal-beholdes"]'
\set keep_test_forms true
\set keep_locked_forms true
\i scripts/preprod-cleanup/preview.sql
```

Kontroller behold-listen og slettelisten før du fortsetter. Etter commit er slettingen irreversibel.

```sql
\i scripts/preprod-cleanup/execute.sql
```

Skriv `DELETE preprod and preprod-alt` når skriptet ber om bekreftelse.

## Viktig før kjøring

- Nais CLI, `psql` 18 og personlig dev-tilgang med SELECT-, DELETE-, TEMP- og låserettigheter.
- Varsle testere og avtal skrivepause i begge preprod-miljøene. Avklar backup og gjenoppretting.
- Bruk en egen psql-tilkobling med AUTOCOMMIT på, uten åpen transaksjon. Ikke pipe SQL til Nais CLI.
- En treffende behold-regel bevarer hele skjemaet. Sett flagg til `false` for å slå av regelen. Ugyldige paths gir feil.
- `cleanup_database` angir forventet navn, ikke en ny tilkobling. Standard er `forms-api-preprod-db`. Lokalt brukes `forms-api-local`.
- `forms-api-db` avvises alltid, også som eksplisitt forventet navn. Oppdater sperren ved navneendring i prod.
- Navnesperren beviser ikke GCP-prosjekt eller cluster. Bruk den faste dev-kommandoen og dev-tilgang, også ved annet forventet databasenavn.
- Skrivelåser under gjennomføring. Endrede data eller behold-regler krever ny preview.
- Kjør `ROLLBACK;` ved SQL-feil, og ta ny preview.
- Ved forbindelsesbrudd under commit er utfallet ukjent. Kontroller databasen før nytt forsøk.
- Globale oversettelser, mottakere, GCS-filer og filer i publiseringsrepoet blir liggende.

## Tester

Docker kreves, lokal `psql` trengs ikke.

```bash
mise exec -- mvn clean install

# Valgfri volumtest med 300 skjemaer, utelatt fra vanlig install
mise exec -- mvn -Pcleanup-volume -Dtest=PreprodCleanupSqlTest test
```
