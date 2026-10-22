# GMC Lanka – Integration Tests

[![Tests CI](https://github.com/test-gmc-lanka/tests/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/test-gmc-lanka/tests/actions/workflows/ci.yml)

Integration test suites for the [test-gmc-lanka](https://github.com/test-gmc-lanka) project. Unit tests
stay next to the code in [frontend](https://github.com/test-gmc-lanka/frontend) and
[backend](https://github.com/test-gmc-lanka/backend); the pipelines that run these suites are in
[infra](https://github.com/test-gmc-lanka/infra).

## Layout

```
tests/
├── backend/src/test/java/com/gmc/backend/it/   *IT.java – every REST endpoint through MockMvc
│                                               (JUnit 5, Spring Boot test, H2 locally / MySQL in CI)
└── frontend/src/api/                           *.int.test.js – the Axios client and its
                                                token-refresh interceptors (Vitest)
```

The suites are compiled and run by the project they test, so they use its dependencies, test
configuration (`backend/src/test/resources`, `frontend/src/test/setup.js`) and coverage settings:

- **backend** – `pom.xml` adds `../tests/backend/src/test/java` as a test source root
  (property `integration-tests.dir`). Failsafe runs `*IT.java` in `./mvnw verify`, and their coverage
  is merged with the unit tests for the 80% line-coverage gate.
- **frontend** – `vite.config.js` includes `../tests/frontend/**/*.int.test.{js,jsx}` (environment
  variable `INTEGRATION_TESTS_DIR`). Tests import app code through the alias `@frontend` → `frontend/src`.

## Running locally

Clone the repositories side by side:

```
<workspace>/
├── backend/
├── frontend/
└── tests/
```

```bash
cd backend && ./mvnw verify              # unit + *IT.java, merged coverage and the 80% gate
cd frontend && npm run test:integration  # *.int.test.js
cd frontend && npm run test:coverage     # all frontend tests, including these
```

Backend suites use an in-memory H2 database unless `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` point
at MySQL.

## Writing tests

- Backend: extend `IntegrationTestBase` (or `ShopIntegrationTestBase` for cart and order data). Each
  test runs in a rolled-back transaction; email and Stripe are mocked. Name the class `<Controller>IT`
  and keep the package `com.gmc.backend.it`.
- Frontend: name files `*.int.test.js` and import app code with `@frontend/...`.
- Same style as the service: Checkstyle + Palantir Java Format (`./mvnw spotless:apply` in backend
  formats files copied into it), ESLint + Prettier for JavaScript. CI checks both.
- When a feature needs new integration tests, use the same branch name here as in backend/frontend;
  each pipeline picks up the matching branch.

## CI

[.github/workflows/ci.yml](.github/workflows/ci.yml) calls
[`tests-ci.yml`](https://github.com/test-gmc-lanka/infra/blob/main/.github/workflows/tests-ci.yml) on
every push and PR: lint and format check, backend integration tests against MySQL 8 with the coverage
gate, and frontend integration tests. The backend and frontend pipelines also check this repository
out and run the suites as their own Integration Tests stage.

## Branching and commits

- `main` – production, protected; `develop` – integration branch (default)
- `feature/<issue>-<slug>`, `fix/<issue>-<slug>`
- Conventional Commits, e.g. `test(order): cover invoice download` with `Closes #42` in the body
