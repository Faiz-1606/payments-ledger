# Payments Ledger

A wallet/ledger backend in Java 21 + Spring Boot 4: INR wallets, transfers, and a double-entry
ledger, built to stay correct under concurrency and retries.

Work in progress. See [docs/PROGRESS.md](docs/PROGRESS.md) for status and
[docs/LEARNING.md](docs/LEARNING.md) for a guided tour.

## Run the tests
Needs a JDK 21 and Docker (for Testcontainers).
```bash
./mvnw test
```
