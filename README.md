forms-api
================

Skjemadefinisjoner, oversettelser og mottaksadresser.

# Komme i gang

`mvn clean install`

Du må ha Docker kjørende for å kjøre testene og for å starte applikasjonen.

## Mise

Prosjektet bruker [Mise](https://mise.jdx.dev/) til å administrere Java- og Maven-versjoner. Etter at Mise er installert og aktivert i skallet, installer verktøyene som er angitt i `mise.toml`:

`mise install`

Kjør kommandoer gjennom Mise uten å aktivere det i skallet med for eksempel:

`mise exec -- mvn clean install`

## Kjøre opp applikasjonen lokalt

Det finnes ulike muligheter for hvordan man kan starte applikasjonen lokalt.

### 1. Spring Boot med profil `local`

Kjøre `no/nav/forms/FormsApiApplication.kt` (profil `local` aktiveres default), f.eks. i IntelliJ.

Dette er den raskeste måten, men med profil `local` brukes en inmemory database, så data mistes ved restart.

### 2. Docker compose up

`docker compose up --build`

Her må man kjøre `mvn install` og kjøre kommandoen over på nytt for å få med endringer. Fordelen er at man får
testet docker image for applikasjonen.

### 3. Spring Boot med profil `docker`

Dette er en hybrid måte hvor applikasjonen kobler seg til database som kjører i en docker container. Data i databasen
overlever restart av applikasjonen.

Starte databasen og cloud storage med docker compose:

`docker compose up -d db cloud-storage`

Deretter kjøres `no/nav/forms/FormsApiApplication.kt` med profil `docker` og `DATABASE_PORT=5442`.

## URLer lokalt

Swagger url: http://localhost:8082/swagger-ui/index.html

OpenAPI descriptions: http://localhost:8082/v3/api-docs

## Chainguard og vedlikeholdsbygg

Dockerfile bruker Navs Chainguard JRE med taggen `openjdk-21`. Før lokale Docker-bygg må du sette opp registry-autentisering og logge inn med Nav-kontoen din:

```sh
gcloud auth configure-docker europe-north1-docker.pkg.dev
gcloud auth login
```

GitHub Actions bruker `nais/docker-build-push` til autentisering og henter oppdatert baseimage ved hvert bygg. Repoet må være registrert på teamet i Nais Console.

Workflowen **Rebuild and deploy** bygger med tester og deployer til preprod daglig kl. 05.23 UTC. Tidsstyrte kjøringer bruker `main` og kan bli forsinket av GitHub. De overskriver dermed eventuelle branch-bygg som er deployet til preprod.

For å bygge og deploye uten kodeendring åpner du workflowen under Actions og velger **Run workflow**. Velg `preprod` eller `prod` og branchen som skal bygges. Prod krever `main`. Prod får ikke tidsstyrt deploy, så teamet må starte prod-bygg etter kontroll i preprod. Push til `main` bygger, tester og deployer fortsatt til prod.

Preprod og prod bygger hvert sitt image. En manuell prod-kjøring henter baseimaget på nytt og garanterer derfor ikke samme image som ble testet i preprod. Hvis dere trenger identiske images i begge miljøer, må dere i stedet innføre en flyt som bygger én gang og deployer samme image til begge.

Deploy-jobber til samme miljø bruker en felles concurrency-gruppe. GitHub lar én jobb kjøre og én vente. En ny deploy-jobb kan erstatte den ventende, men avbryter ikke deployen som pågår. Bygg og tester omfattes ikke av denne køen.

Før prod-deploy kontrollerer workflowen at byggets commit fortsatt er toppen av `main`. Hvis `main` har gått videre under bygg eller venting, feiler deploy-jobben før den sender noe til Nais. Start et nytt bygg hvis det ikke allerede kjører et for den nye commiten.

Et nytt baseimage kommer ikke med ved restart eller deploy av et eksisterende app-image. Major-taggen gir oppdateringer innen Java 21 ved nye bygg, men gjør at samme kildekode kan gi et annet image senere. Digest-pinning med [Digestabot](https://github.com/navikt/digestabot) er alternativet for reproduserbare bygg. Se [Navs Chainguard-dokumentasjon](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages/).

## Kjente utfordringer ved kjøring lokalt

### Colima

Hvis du bruker [Colima](https://github.com/abiosoft/colima) kan det være nødvendig å sette følgende env-variabler for at
testcontainers skal fungere:

    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
    export DOCKER_HOST="unix://${HOME}/.colima/docker.sock"
    unset TESTCONTAINERS_HOST_OVERRIDE

For flere detaljer, se
[Customizing Docker host detection](https://java.testcontainers.org/features/configuration/#customizing-docker-host-detection)
i dokumentasjonen til [Testcontainers](https://java.testcontainers.org/).

### Flyway

Ved bruk av db i docker container under utvikling lokalt er det lett å gjøre Flyway sur dersom man endrer på
migreringsskriptene underveis. I slike tilfeller er det enklest å slette docker containere og volumes:

    docker rm forms-api forms-api-db
    docker volume rm forms-api_db

# Autentisering

Endepunkter som henter data er i utgangspunktet åpne, men de som endrer på data er sikret
med [Entra ID](https://doc.nais.io/auth/entra-id/) (tidligere Azure AD). Det er to grupper som bestemmer hvilken
funksjonalitet brukeren får tilgang til:

|       | Prod (nav.no)       | Preprod (trygdeetaten.no)  |
|-------|---------------------|----------------------------|
| User  | Skjemabygging       | SkjemabyggingPreprod       |
| Admin | Skjemabygging-Admin | SkjemabyggingPreprod-Admin |

## Generere access token for testing i preprod

Access token for å teste api'et i preprod kan genereres på følgende
side: [azure-token-generator](https://azure-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp.fyllut-sendinn.forms-api).
Logg på med din personlige trygdeetaten-bruker. For mer informasjon,
se [NAIS Doc - Generate a token](https://doc.nais.io/auth/entra-id/how-to/generate/).

---

# Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.

# Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub

## For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #team-fyllut-sendinn
