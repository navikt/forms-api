---
name: test-writing-conventions
description: Use when writing or modifying tests in this repository. Collects repository-specific testing guidelines and conventions, including controller/API test boundaries.
---

# Test writing conventions

Use this skill when writing or updating tests in this repository.

It is intentionally broad so more repository-specific testing conventions can be added over time.

## Current convention: controller test boundaries

In this repository, **controller tests must communicate only through the REST API** exposed by `forms-api`.

That means controller tests should:

- call the application through `TestFormsApi`, `TestRestTemplate`, or equivalent HTTP-level helpers
- assert on request/response behavior, status codes, headers, and response bodies
- treat the application as a black box

Controller tests must **not**:

- autowire services
- autowire repositories
- autowire `EntityManager`, `EntityManagerFactory`, or Hibernate statistics
- inspect database state directly
- assert on query counts or other implementation details

## Where internal tests belong

If a test needs access to services, repositories, Hibernate statistics, query counts, entity managers, or other internals, it is **not** a controller test.

Place that test in a separate file with a name that makes the scope explicit, for example:

- `*ImplementationTest`
- `*ServiceTest`
- `*RepositoryTest`
- `*ReadPathImplementationTest`

Do not mix these internal tests into `*ControllerTest` files.

## Guidance

1. For endpoint behavior, prefer extending the existing controller tests and keep all setup/assertions at the REST layer.
2. For query-shape or performance-regression checks, create a separate internal test class.
3. If a test starts needing application internals, move it rather than expanding the controller test boundary.

## Checklist

- `*ControllerTest` files only use REST-level interactions
- internal implementation checks live in a separate non-controller test file
- test names and file names reflect whether the test is black-box API behavior or internal implementation verification
