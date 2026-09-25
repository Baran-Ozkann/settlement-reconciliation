# settlement-reconciliation

Three-way reconciliation of `ledger-payment-core` postings, PSP settlement reports and bank
statements. Under construction: the full README is written when the service is complete. The
design is in [`docs/settlement-reconciliation-tdd.md`](docs/settlement-reconciliation-tdd.md).

## Run locally (PowerShell)

```powershell
Copy-Item .env.example .env        # then replace every placeholder value in .env
docker compose up -d               # PostgreSQL on 127.0.0.1:5434, Kafka on 127.0.0.1:9094
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
Invoke-RestMethod http://127.0.0.1:8091/actuator/health
```

## Test

```powershell
.\mvnw.cmd verify                  # needs Docker running: integration tests use Testcontainers
bash ci/check-rules.sh             # project rules the compiler and the tests do not see
```

Test containers are removed when the test JVM exits. Ryuk, the Testcontainers reaper, is switched
off because it publishes its port on every interface, so a JVM that is killed outright leaves its
containers running. Remove them with:

```powershell
docker ps -aq --filter "label=org.testcontainers=true" | ForEach-Object { docker rm -f $_ }
```
