# Publications

A publication is the unit that makes a form definition publicly available. Each
one gets a `publicationId`, a TypeId with prefix `pub`, e.g.
`pub_01kzbh1czmevdah0yg3kebpk1s`.

## What a publicationId identifies

A publication ties together, at one point in time:

- one form revision
- the published form translations
- the published global translations

So `publicationId` identifies **everything needed to render that form**, which a
form revision alone does not.

## Consumer rules

**Publishing global translations creates a new publication for all affected
forms without changing any form revision.** A consumer that identifies rendered
output by `revision` therefore under-identifies it: the same revision can render
differently before and after a global translation publish. Use `publicationId`
where the identity of rendered output matters.

**`publicationId` is only valid when `status` is `published`.** Endpoints that
return the current revision return the *latest* publication's `publicationId`
even when that revision is a draft ahead of it (`status: pending`). The id then
describes different components than the ones returned. Consumers must check
`status` before trusting it:

```
status == "published" && publicationId != null
```

Fetching a specific published snapshot does not have this problem.

**`publicationId` is opt-in via `select`.** Like other fields it is omitted
unless requested, and omission is not an error — consumers see `null`, not a
failure.

## Backfill

`publicationId` was introduced after many forms had already been published.
Forms published before it exists have none; they acquire one on their next
publication. Consumers must handle its absence on published forms, and coverage
grows only as forms are republished.
