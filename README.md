forms-api
================

Skjemadefinisjoner, oversettelser og mottaksadresser.

# Komme i gang

`mvn clean install`

Du må ha Docker kjørende for å kjøre testene og for å starte applikasjonen.

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

## Kjente utfordringer ved kjøring lokalt

### Colima

Hvis du bruker [Colima](https://github.com/abiosoft/colima) kan det være nødvendig å sette følgende env-variabler for at
testcontainers skal fungere:

    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
    export DOCKER_HOST="unix://${HOME}/.colima/docker.sock"

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
