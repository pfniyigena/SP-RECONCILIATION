# Tax Reporter

Spring Boot + PostgreSQL + JPA + Thymeleaf app for browsing `card_transaction` records,
with a sidebar-navigated ledger UI and username/password login.

## Reconciliation export (Excel/PDF), with its full set of card transactions

`ReconciliationExportService` — new export, keyed by a single reconciliation id rather than a
filter (unlike the Transactions/Invoices list exports). Same background-job pattern as those two
(`ExportJobService`/`ExportTask`), same reason: a batch built from the bulk "reconcile all
matching" action can hold up to `MAX_BULK_RECONCILE` = 25,000 transactions, too many for a
blocking synchronous request.

**Excel**: two sheets — "Summary" (date, reference, created, transaction count, total amount) and
"Transactions" (same columns as the main ledger export, including `ebmNumber` via the same
batched lookup, avoiding the same N+1 lazy-load risk on `depositAllocations`). **PDF**: a summary
block at the top, transaction table below, capped at 5,000 rows like the other PDF exports.

Reuses `CardTransactionRepository.findByReconciliation_Id(id, Pageable)` — the exact same
paginated method the reconciliation detail page itself now uses — looping through pages
internally rather than loading the whole batch into memory at once.

**Security, not an afterthought:** the export endpoint runs the identical batch-wide
accessibility check the view page uses (`ReconciliationService.hasOutOfScopeTransaction`) before
starting the job. Without this, a `ROLE_CUSTOMER_SCOPED` user blocked from *viewing* a mixed
batch could still have pulled its full transaction list by hitting the export endpoint directly
— the view page's guard doesn't protect a separate endpoint on its own.

## Reconciliation detail page: child transactions now paginated

`CardTransactionRepository.findByReconciliation_Id` switched from returning a full unpaginated
`List` to a `Page`, default 50 per page. Straightforward on its own, but it exposed two things
that needed fixing at the same time, not after — both were computing something over "all child
transactions" by summing/iterating the loaded list, which silently becomes wrong the moment that
list is only one page instead of everything:

1. **The batch total** (`ReconciliationController.view`) used to be summed manually from the
   loaded transactions. Once that's paginated, a manual sum silently becomes a *page* subtotal
   instead of the batch's actual total. Fixed by switching to
   `ReconciliationService.childTransactionTotal` — an existing aggregate-query method that was
   sitting right there unused, computing the correct thing the whole time, just not being called.
2. **The customer-scoping accessibility check** (added last time, for `ROLE_CUSTOMER_SCOPED`)
   used to check whether *the loaded transaction list* contained anything out of scope. Once
   that list is paginated, checking only the current page would miss an out-of-scope transaction
   sitting on some other page of the same batch — a mixed batch could leak through as long as
   whichever page loaded first happened to look clean. Fixed with a dedicated, pagination-
   independent existence query
   (`CardTransactionRepository.existsOutOfScopeTransactionInReconciliation`) that checks the
   whole batch regardless of which page is being viewed. Also guards against an empty
   `allowedCustomerIds` set short-circuiting to "inaccessible" without even querying, both for
   correctness (zero accessible customers really should mean zero accessible reconciliations)
   and because an empty `NOT IN (...)` clause isn't something to rely on Hibernate handling
   consistently.

## `ROLE_CUSTOMER_SCOPED` can now reconcile too

This reversed an earlier, deliberate design decision (the previous code had an explicit comment
saying customer-scoped users should *not* get reconciliation) — done carefully, since
`Reconciliation` batches have no customer relation of their own; they're just groupings of
`CardTransaction` rows, which do. Nothing previously stopped a batch from mixing customers, so
opening this up needed real scoping, not just a URL rule change.

**Four layers, each closing a different gap:**

1. **Creating a batch** (`ReconciliationService.reconcile`) now takes an optional
   `restrictToCustomerIds` — when set, every selected transaction ID is validated against it
   before anything is created. Stops a scoped user from reconciling (or even confirming the
   existence of) a transaction outside their assigned customers by tampering with the
   `selectedIds` form field — the list page's own filtering only governs what's *offered*, not
   what this endpoint accepts.
2. **The "reconcile all matching filter" path** scopes the underlying `CardTransactionFilter`
   itself before the query runs (same `restrictToCustomerIds` mechanism the transaction list
   already used) — the query can't return anything out of scope in the first place.
3. **Viewing the reconciliation list** (`ReconciliationSpecifications`) — a batch is only visible
   if **every** transaction in it belongs to the caller's assigned customers, not just some. A
   `NOT EXISTS` subquery checks for any out-of-scope transaction in the batch; a batch mixing a
   scoped customer's transactions with anyone else's stays entirely invisible, not partially
   shown with only the accessible transactions filtered out — showing it at all would leak the
   existence of, and running totals for, other customers' transactions.
4. **Direct URL access to a batch's detail page** (`ReconciliationController.view`) — same "any
   out-of-scope transaction makes the whole batch invisible" check, since the list's filtering
   doesn't stop someone from guessing or bookmarking `/reconciliations/{id}` directly.

**Also fixed while wiring this up:** `transactions.html`'s empty-state and footer colspans used
to vary by role (`th:colspan="...hasAnyRole('ADMIN','ANALYST')... ? 10 : 9"`) because the
checkbox column only rendered for those two roles. Now that `CUSTOMER_SCOPED` gets it too, every
role reaching that page sees the same column count — simplified to fixed values rather than
leaving dead conditional logic that always evaluates the same way now.

## `application-sp.yml` / `application-oracle.yml` added — honestly, not fully

Both load correctly as Spring profiles (`-Dspring.profiles.active=sp`/`oracle`, or the env var —
same caveat as before: the Maven `sp`/`oracle` build profiles don't activate these on their own).

- **`application-oracle.yml`** scaffolds `spring.datasource.oracle.*` properties, mirroring the
  established `spring.datasource.stamp.*` pattern exactly (same Hikari tuning shape, same
  `initialization-fail-timeout: -1` for the same reason). **Properties only — there's no
  `OracleJdbcConfig.java` in this project to actually read them.** This file existing does
  nothing at runtime by itself; wiring it up would need a config class shaped like
  `stamp.config.StampJdbcConfig` (same `DataSourceBuilder` pattern, same `jdbc-url`-not-`url`
  caveat). The connection values are placeholders — I don't have real Oracle host/credentials.
- **`application-sp.yml` is an intentionally empty placeholder.** Unlike `oracle`, "sp" doesn't
  tell me what it should configure, and I'd rather leave it honestly blank with a comment
  explaining why than invent settings that might be wrong or misleading. Spring Boot handles an
  active profile with an empty/no matching properties file fine — this just gives the profile a
  concrete place to grow into once it's clear what should actually differ.

## `dev` profile, active by default

`application-dev.yml` — more verbose logging only (`org.hibernate.SQL` and the Hibernate 6
bind-parameter logger at `DEBUG`/`TRACE`, this project's own package at `DEBUG`). Deliberately
small: nothing here changes the datasource, `ddl-auto`, or any actual behavior, just visibility
into what the app is doing locally.

**`spring.profiles.default: dev`, not `spring.profiles.active: dev`** — the distinction matters:
`profiles.default` is a fallback used only when nothing else specifies an active profile, so it's
what you get with zero setup, but an explicit `-Dspring.profiles.active=...` or
`SPRING_PROFILES_ACTIVE` env var still overrides it. `profiles.active` would have hard-coded
"dev" and fought any other profile choice, including the `sp`/`oracle` Maven build profiles added
earlier — this way "dev" only applies when nothing else says otherwise.

**Same caveat as the `sp`/`oracle` Maven profiles still applies**: building with `-Psp` or
`-Poracle` doesn't automatically make its `spring.profiles.active` Maven property override this
default at runtime — Maven properties aren't wired into the running app on their own. Without an
explicit `-Dspring.profiles.active=sp` (or the env var) when actually *running* the jar, `dev`
stays active regardless of which Maven profile built it.

## `Specification.where(null)` → `Specification.allOf()`, project-wide

All 8 occurrences (`AppUserService`, `CustomerService`, `StampMachineService`,
`InstitutionService`, `TerminalMachineService`, `CustomerDepositSpecifications`,
`TaxReporterInvoiceSpecifications`, `ReconciliationSpecifications`) — every place this project
built a "start with an always-true specification, then `.and(...)` filters onto it" base. Same
purpose either way; `allOf()` is the modern Spring Data JPA idiom, `where(null)` relied on
`where()` tolerating a null argument as a no-op starting point. Confirmed no other usages or
phrasings (`.where(`) remain anywhere in the project.

This closes out a discrepancy flagged much earlier in this project's history, when the reference
project was first built using `where(null)` while the real app was already on `allOf()`.

**Worth a real build to confirm, not just assumed:** `allOf()`'s exact availability depends on
the Spring Data JPA version pulled in transitively — should be fine given this project is now on
Spring Boot 3.5.16, but I can't compile in this sandbox to verify, same caveat as the version
upgrade itself.

## Spring Boot upgraded to 3.5.16, two build profiles added

**Spring Boot 3.3.4 → 3.5.16** — a real minor-version jump (3.3 → 3.5), pulling in newer
Hibernate/Hikari/etc. via Spring's dependency management. I can't actually compile this project
in my sandbox (no Maven Central access), so this is verified structurally (well-formed `pom.xml`,
correct version string) only — not compiled or run. Do a clean `mvn clean package` before relying
on this; if anything in the 3.4/3.5 changelog affects this project's specific dependencies
(OpenPDF, mssql-jdbc, POI — none of which are Spring-authored, so their exact behavior under the
new Boot-managed versions isn't something I can verify without actually building it), that would
only surface at compile or runtime, not from anything I can check here.

**`sp` and `oracle` Maven profiles added** — `mvn clean package -Psp` / `-Poracle`. What's fully
wired: each profile overrides the build's `finalName` to include the profile id, so you get
`target/tax-reporter-sp.jar` / `target/tax-reporter-oracle.jar` instead of the default
`target/tax-reporter-0.1.0.jar`. What's **scaffold only**: each profile also sets a
`spring.profiles.active` *Maven* property, but that's a label, not automatic wiring — Maven
properties aren't picked up by the running app on their own. Actually activating a Spring profile
at runtime still needs either an `application-sp.yml`/`application-oracle.yml` plus
`-Dspring.profiles.active=sp` (or the `SPRING_PROFILES_ACTIVE` env var) when running the jar, or
resource filtering enabled on `application.yml` to bake the Maven property in at build time
(neither set up here). I don't know what should actually differ between an `sp` build and an
`oracle` build beyond the jar name — say what each should configure and I'll wire it properly
rather than guessing.

## Invoice ingestion controller renamed and relocated

`web.InvoiceIngestController` → `api.InvoiceApiV1Controller`, new top-level `api` package
alongside `web`/`service`/`repository`. `SecurityConfig`'s `/api/v1/invoices/**` rule matches by
URL path, not by controller class or package, so nothing there needed to change.

**Response contract also changed, not just the name/location** — always `HTTP 200` now, with
`SUCCESS`/`FAILED` indicated only in the response body's `InvoiceResponseDto.status` field,
rather than the previous version's `201`/`422` status codes. Worth flagging plainly: a caller
checking only the HTTP status code can no longer distinguish success from failure at all — it
has to parse the body. Implemented exactly as given, not a design choice made here.

Fixed two now-stale comment references to the old class name (in `InvoiceDto` and
`TaxReporterInvoiceService`) while making this change, including one that described the old
422-based error behavior — left uncorrected, that comment would have actively misdescribed how
the endpoint behaves now.

## Invoice ingestion API — the missing piece behind an existing `SecurityConfig` rule

`SecurityConfig` has had `/api/v1/invoices/**` configured `permitAll` and CSRF-exempt since much
earlier in this project — anticipating a public ingestion endpoint that never actually had a
controller behind it. `InvoiceDto` + `TaxReporterInvoiceService.createInvoice`/`validStampMachine`/
`validInstitution`/`toInvoice` are that missing logic; **`InvoiceIngestController`
(`POST /api/v1/invoices`) was added alongside them** so this is actually reachable rather than
dead code with no caller — not part of the literal request, but there wasn't a reasonable way to
use any of this without it.

**`StampMachine.institution` added** — `validStampMachine` constructs a `StampMachine` with an
`institution`, which didn't exist on this project's `StampMachine` yet. Same minimal, targeted
pattern as the earlier `sdcId` addition: just what this specific request required to compile,
not the full deferred rebuild (`location`, `totalInvoices` are still absent). Carries
`@ToString.Exclude`, consistent with every other relationship field in this project.

**Worth knowing about `createInvoice`'s error handling, preserved exactly as given:** it
swallows all exceptions internally and returns `null` on any failure — a duplicate `stamp_data`,
a DB error, anything. The caller can't distinguish those cases without checking server logs.
`InvoiceIngestController` treats a `null` result as `422 Unprocessable Entity`, since silently
returning `200` with nothing to show for it would be a worse contract for the external caller —
but the underlying ambiguity (why did it fail?) is inherent to the logic as pasted, not something
the controller can recover.

**One redundancy caught and fixed while wiring this up:** the pasted code uses
`taxReporterInvoiceRepository` as the field name, but this service already had an identically-typed
`repository` field used throughout every other method. Rather than carry two fields injecting the
same bean, consolidated to the one already established in this file.

## Three repository lookups added, one of them needed more than a one-liner

- `InstitutionRepository.getByTinNumber` — added as requested. `tinNumber` has no `unique`
  constraint on the real entity, so this can throw `IncorrectResultSizeDataAccessException` if
  two institutions ever share a TIN — flagged, not silently assumed safe.
- `TaxReporterInvoiceRepository.getByStampData` — straightforward, `stampData` is already
  `unique = true`, guaranteed 0 or 1 results.
- `StampMachineRepository.getBySdcId` — **this one needed a real field added first.**
  `StampMachine` is still an explicitly-documented placeholder (`serialNumber`/`model`/`enabled`,
  not the real app's shape) — a derived query can't be built against a property that doesn't
  exist, so it would have failed at application *startup* with `PropertyReferenceException`, not
  just at call time. Added `sdcId` (`unique = true`) — a minimal, targeted addition, not the full
  `StampMachine` rebuild (institution relation, location, `totalInvoices`) still deferred
  elsewhere in this project.

**Since `sdcId` is now a real persisted field, it needed to actually be settable through the
app**, or it could only ever be populated externally, making the new lookup permanently return
`null` in practice. Wired through `StampMachineForm`/`Mapper`/list+form templates.

**Caught while doing that:** `StampMachine` now has *two* unique constraints (`serialNumber`,
`sdcId`), but the existing duplicate-handling only ever blamed `serialNumber` — a wrong message
and a wrong highlighted field if an `sdcId` conflict was what actually happened. Fixed by
inspecting which constraint the underlying SQL error actually names, and extended
`DuplicateValueException` with an optional `field` property (new two-arg constructor, existing
single-arg one untouched — verified every other caller, `Customer`/`AppUser`/`TaxReporterInvoice`,
still compiles unchanged) so the controller can attach the error to the right input instead of
always assuming `serialNumber`.

## `Institution` — new entity, full CRUD (Admin-only)

Entity matches the real app exactly. Full stack: repository, form DTO, mapper, service,
controller, list + form templates (no separate view page — same shape as `Customer`/
`StampMachine`, this project's other simple master-data entities). Falls under the existing
Admin-only catch-all in `SecurityConfig`, same as `stamp-machines` — no new security rule needed.

**`totalInvoices` left genuinely unpopulated, not faked.** Computing it for real needs
Institution → StampMachine → TaxReporterInvoice, and this project's `StampMachine` doesn't have
an institution relation yet — it's still `serialNumber`/`model`/`enabled`, not the real app's
`sdcId`/`location`/`institution`/`totalInvoices` shape. That's the "Institution + StampMachine/
TerminalMachine (org structure)" work explicitly deferred earlier in favor of the
CustomerDeposit/CardTransaction/DepositCardTransaction realignment. Rather than fabricate a
count against a relationship that doesn't exist in this project, the field is left at its
natural `null` — same as `TerminalMachine.totalInvoices`, which has been sitting unpopulated the
same way since before this entity was added. Deliberately **not shown as a column** on the
Institutions list, since a column that's always "—" isn't useful UI.

**No duplicate-value handling on create/update**, unlike `StampMachine`'s serial number —
`tinNumber` has `nullable = false` but no `unique = true` on the real entity, so there's no DB
constraint to catch. Delete still guards against `DataIntegrityViolationException` defensively,
even though nothing references `Institution` yet — ready for when `StampMachine`'s institution
FK exists, rather than needing to remember to add the guard retroactively at that point.

## `@ToString.Exclude` added to every `@OneToMany`/`@ManyToOne` field

10 fields across 4 entities: `CardTransaction` (`machine`, `customer`, `reconciliation`,
`depositAllocations`), `CustomerDeposit` (`customer`, `allocations`), `DepositCardTransaction`
(`deposit`, `transaction`, `customer`), `TerminalMachine` (`stampMachine`). Prevents Lombok's
generated `toString()` from triggering lazy-loading on these associations (a `toString()` call
anywhere — a log statement, a debugger, an exception message — could otherwise force a query, or
throw `LazyInitializationException` outside a session) and from walking into bidirectional
cycles (e.g. `CardTransaction.customer` → some collection back to `CardTransaction` →
`StackOverflowError`). Verified every match has the exclusion directly above it, not just added
somewhere in each file.

`AbstractEntity`, `AppUser`, `Customer`, `Reconciliation`, `StampMachine`, and
`TaxReporterInvoice` have no `@OneToMany`/`@ManyToOne` fields, so nothing needed changing there.
`AppUser.accessibleCustomers` is `@ManyToMany`, not covered by this — left as asked, though it
has the identical rationale for the same treatment if that's wanted too.

## Deposit matching now also runs on a schedule

`CustomerDepositMatchingService.scheduledMatchAll()` — `@Scheduled(cron = "${deposit-matching.cron.expression}")`,
default every 15 minutes (`deposit-matching.cron.expression` in `application.yml`, overridable
via `DEPOSIT_MATCHING_CRON`). Same `matchAll()` call as both the "Run Now" button on the Jobs
page and the "Run Matching" button on the Deposits list — same reentrancy guard, same outcome,
just triggered by a timer instead of a click. Deliberately a thin wrapper: `matchAll()` and its
internal `runMatchAll()` already log both outcomes (transactions matched, or skipped because a
previous run was still in progress) — nothing further needed for the scheduled path, since
there's no request/response here to build a flash message for the way the two button endpoints do.

## `TaxReporterInvoice` corrected, and given `CardTransaction`'s "Mark as Processed" parity

**Entity fix, not just a re-paste:** the pasted version had two real differences from what this
project had — `@Lob` was gone (this project's own documented `@Lob`-on-PostgreSQL bug had been
fixed on `CardTransaction`/`CustomerDeposit` but, it turns out, never actually applied to this
project's own `TaxReporterInvoice`, despite being fully documented as the correct fix) and
`getStampNumber()` was the original unguarded form (same reversion pattern as
`CustomerDeposit.getStampNumber()` earlier — will throw on malformed `stampData` again, by
request). Both applied.

**Feature parity, scoped to what's actually analogous:** added "Mark as Processed"
(`TaxReporterInvoiceService.markProcessed`, a button on the invoice detail page) mirroring
`CardTransaction`'s existing one-click action — justified directly, since this entity already
has its own `processed` field. Adapted for the fields that actually exist here: no separate
`success` flag on this entity, so the equivalent of "mark successfully processed" is
`processed = true` plus clearing any stale `failureReason`, not a second boolean.

**Deliberately not added:** Reconciliation-style batch grouping, or any deposit-allocation
involvement. Both are `CardTransaction`-specific concepts (settlement batching, deposit-funded
spend) without an obvious analog for a tax invoice record — adding either would mean inventing
new domain concepts for invoices rather than porting an existing pattern, so it wasn't done
speculatively. Say if either is actually wanted and what it should mean for invoices
specifically, since "reconciliation" or "allocation" could mean something different here than it
does for card transactions.

## Fixed: `LazyInitializationException` on `ebmNumber` in exports

`DepositCardTransactionRepository.findByTransaction_IdIn` now eagerly fetches `deposit`
(`@EntityGraph(attributePaths = {"deposit"})`). Without it, `CardTransactionExportService`'s
`buildEbmNumberMap` called `allocation.getDeposit().getStampNumber()` on a lazy proxy after that
query's own short-lived session had already closed — exports run asynchronously on
`ExportJobService`'s background thread pool, well past the point where a per-request session
would still be open, so the proxy had nothing left to resolve against (`could not initialize
proxy ... - no Session`).

Fixed by fetching the data eagerly rather than trying to keep a session open across the whole
(potentially slow, streamed) export — this sidesteps needing to reason about transaction
boundaries across a background thread entirely. Checked the other call site
(`CardTransaction.getEbmNumber()`, used by the transaction view page) for the same risk — already
safe, since `CardTransactionRepository.findWithAllocationsById`'s existing `@EntityGraph` already
covers `depositAllocations.deposit` for that path.

## Fixed: error banner outside form binding context

`${#fields.hasErrors('*')}` requires being inside the DOM subtree of the `<form th:object="...">`
it's checking — Thymeleaf's `#fields` helper has no binding context otherwise, and throws
`Could not bind form errors using expression "*"`. The standardization pass that introduced the
`alert-error-banner` div across all 8 forms placed it *before* `<form>` opened, following the
position of the older `crud-alert-error` pattern it replaced — which had the identical bug the
whole time, it just happened not to surface until the actual error path was exercised, since a
`th:if` that only ever needs to render on a form-with-errors round-trip is easy to never actually
hit during normal use.

Fixed in all 8 forms (`profile`, `users`, `invoice`, `machines`, `stamp-machines`, `customers`,
`transaction`, `customer-deposit`) — the banner now sits as the first child inside `<form>`,
immediately after it opens, in every case. Verified with a `<div>`-balance check and a JS syntax
check across all 8 files after the edits, not just visually inspected.

## `CustomerDeposit.getStampNumber()` reverted to its original, unguarded form

At explicit request — this now throws `ArrayIndexOutOfBoundsException` on any `stampData` with
fewer than 4 comma-separated segments again, the exact bug this method's guarded version
(`split(",", 4)` + a length check) was written to fix earlier in this project. Worth restating
plainly since it's a direct reversal, not new behavior: this method is called from
`CardTransaction.getEbmNumber()`, the transaction and deposit view pages, and both Excel/PDF
exports — a malformed `stampData` anywhere in that chain now surfaces as a hard failure at
whichever of those touches it, rather than the graceful `null` the guarded version returned.

`CardTransaction.getEbmNumber()` was already implemented exactly as requested — no change was
needed there, it already matched.

**`ebmNumber` added to the transaction detail page**, placed directly next to "Stamp number" in
the Stamp & SDC section for side-by-side comparison — this transaction's own stamp vs. the
fiscal/deposit-side stamp it's actually accounted under. Relies on the `@EntityGraph` already
wired into `CardTransactionService.getOrThrow` (eagerly fetching `depositAllocations` and
`depositAllocations.deposit`), so no additional data-loading changes were needed for this to
render safely.

## Five requested changes

1. **Standardized the form validation error banner.** `transaction-form.html`/`invoice-form.html`
   already used `class="alert-error-banner"` with this exact text; the other 6 forms
   (`profile`, `users`, `machines`, `stamp-machines`, `customers`, `customer-deposit`) used an
   older `crud-alert-error` class with slightly different text. Same styling either way — moved
   `.alert-error-banner` into the shared `fragments/crud-styles.html` (was duplicated inline in
   the first two files) and updated all 6 to match, rather than leaving two names for one thing.

2. **`AbstractEntity` now matches the real app exactly** — `@JsonIgnore` on internal/audit
   fields, `createdBy`/`updatedBy` via `@CreatedBy`/`@LastModifiedBy`, optimistic-locking
   `version`, `@UuidGenerator`. **New `AuditorAwareConfig` bean added alongside this** —
   `@CreatedBy`/`@LastModifiedBy` do nothing without an `AuditorAware<String>` bean supplying
   "who," and this project had `@EnableJpaAuditing` but no such bean, which would have left
   `createdBy`/`updatedBy` silently stuck at `""` forever. Pulls the username from
   `SecurityContextHolder`; falls back to `"system"` for saves that happen outside an
   authenticated request (the startup `CommandLineRunner`s, `CustomerDepositMatchingService`'s
   background runs).

3. **`CardTransactionRepository.findWithAllocationsById`** — `@EntityGraph` eagerly fetching
   `depositAllocations` and `depositAllocations.deposit` in one query, avoiding the
   `LazyInitializationException` that plain `findById` would hit once the view template tries
   to read that collection (same class of bug as the earlier `AppUser.accessibleCustomers` fix,
   different mechanism — `@EntityGraph` instead of a JOIN FETCH JPQL query, same effect).

4. **`CardTransactionService.getOrThrow`** now uses `findWithAllocationsById` +
   `EntityNotFoundException`. **A new `GlobalExceptionHandler` was needed alongside this** —
   unlike `ResponseStatusException`, `EntityNotFoundException` isn't natively understood by
   Spring MVC's exception resolution; with no handler it would have produced a 500 instead of a
   404 for a missing transaction. The handler just re-throws it as the `ResponseStatusException`
   Spring already renders correctly — reusing existing behavior rather than building new
   error-page templates that weren't asked for. Not applied to any other entity's `getOrThrow` —
   only this one changed exception types.
   **Worth knowing:** `getOrThrow` is shared by `view()`, `update()`, and `markProcessed()` —
   all three now carry the extra `depositAllocations` join, including the two that don't
   actually need it. Minor overhead, not a correctness issue, flagged in case it matters at scale.

5. **`ebmNumber` added to both Excel and PDF transaction exports.** Deliberately *not* done by
   calling `CardTransaction.getEbmNumber()` per row — that walks a LAZY `@OneToMany` collection,
   and export queries don't go through the `@EntityGraph` from #3, so per-row calls would mean
   one extra query per transaction (N+1) across what can be a large export. Instead, one bulk
   query per batch (`DepositCardTransactionRepository.findByTransaction_IdIn`) builds a lookup
   map up front, mirroring `getEbmNumber()`'s own "first allocation with a stamped deposit"
   logic for a whole page at once.

## Critical fix: SQL Server being unreachable was taking down the whole app

**This took two attempts to fully diagnose — worth understanding both, since the first fix was
real but incomplete.**

**The actual root cause:** Spring Boot's own primary-datasource auto-configuration
(`DataSourceAutoConfiguration`) is guarded by `@ConditionalOnMissingBean(DataSource.class)` — a
**type-based** check, not a name-based one. The moment `StampJdbcConfig` defines *any* bean of
type `DataSource` — regardless of what it's named — Spring Boot sees that condition as already
satisfied and **never creates its own primary Postgres datasource bean at all.**

That produced two different failures, in sequence:
1. **First symptom:** with only `stampDataSource` in the context and no primary bean ever
   created, Hibernate had exactly one `DataSource` available and used it — so a downed SQL
   Server took the whole app down, even though Postgres itself was fine. First fix attempt
   (`@Bean(autowireCandidate = false)` on `stampDataSource`) correctly stopped it from being
   chosen by ambiguous autowiring — but that assumed a second, valid Postgres bean existed to
   fall back to. It didn't.
2. **Second symptom, after that fix:** `No qualifying bean of type 'DataSource' available` —
   because excluding `stampDataSource` from autowiring left **zero** candidates. The primary
   bean still didn't exist; the first fix changed *which way* it failed, not *whether* it failed.

**The complete fix:** `PrimaryDataSourceConfig` explicitly defines the primary Postgres
`DataSource` bean, `@Primary` and named `"dataSource"`, using the `DataSourceProperties`
intermediary pattern (`properties.initializeDataSourceBuilder().type(HikariDataSource.class)`)
rather than a raw `@ConfigurationProperties` binding directly onto `HikariDataSource` the way
the secondary datasources are built. That distinction matters concretely here:
`application.yml`'s `spring.datasource.*` already uses `url:` (correct for Spring's own
`DataSourceProperties`, which translates it to whatever the target implementation needs); a raw
binding onto `HikariDataSource` would need `jdbc-url:` instead (as `StampJdbcConfig` correctly
does) — using the wrong pattern here would have silently required renaming an already-working
property.

**Now protected two ways, deliberately redundant:** `stampDataSource` is excluded from ambiguous
autowiring (`autowireCandidate = false`) *and* the real primary bean is explicitly marked
`@Primary`. Either alone would fix this; having both means it can't silently regress if one is
ever changed without the other.

**Also relevant to your real app:** if `OracleJdbcConfig` defines its own `DataSource` bean the
same way `StampJdbcConfig` does, your real app has the identical exposure — Spring Boot's
primary-datasource auto-configuration will have backed off there too, for the same
type-based-`@ConditionalOnMissingBean` reason. Worth checking whether a primary datasource bean
is defined explicitly there, or whether it's been silently missing the whole time.

`initialization-fail-timeout: -1` (the very first fix, for the SQL Server pool itself not
failing fast during pool creation) is still correct and still needed — it, this, and the
`autowireCandidate` fix are three genuinely different problems that happened to surface through
the same symptom, not one problem fixed three times.

## Third correction: `autowireCandidate = false` broke `@Qualifier` injection

**I was wrong about this.** The belt-and-suspenders `@Bean(autowireCandidate = false)` added to
`stampDataSource`/`stampJdbcTemplate` in the previous fix, on the claim that explicit
`@Qualifier` references would be unaffected — that claim was incorrect, and it broke
`StampLookupService`'s own startup (`No qualifying bean of type 'JdbcTemplate' available`,
`Qualifier("stampJdbcTemplate")`). `@Autowired` + `@Qualifier` goes through the same
`isAutowireCandidate()` filtering that `autowireCandidate = false` gates — only bean-lookup-by-name
mechanisms that bypass that pipeline entirely (`@Resource(name = ...)`, not `@Qualifier`) are
actually unaffected by it, and that's not what this app uses anywhere.

**Confirmed by real testing, not just re-reasoning:** the person running this against their
actual environment converged on the identical fix independently — remove
`autowireCandidate = false`, keep explicit `@Bean(name = "...")` for clarity. This project's
`StampJdbcConfig` now matches that exact confirmed-working version.

`@Primary` on the real datasource (`PrimaryDataSourceConfig`, previous fix) is sufficient on its
own to win any ambiguous/unqualified resolution — it doesn't carry this side effect, so there
was never a need for both mechanisms together.

**Net result across all three corrections:** `initialization-fail-timeout: -1` (pool doesn't
fail fast), `PrimaryDataSourceConfig` with `@Primary` (the actual primary datasource now gets
created at all), and *not* using `autowireCandidate = false` anywhere (so explicit `@Qualifier`
access to the secondary datasource keeps working). All three were genuinely necessary; none of
them alone would have been sufficient.

## SQL Server unavailability no longer blocks app startup

`spring.datasource.stamp.initialization-fail-timeout: -1` added to `application.yml`. By
default, HikariCP tries to establish one real connection while a pool is being created, and
throws `PoolInitializationException` — which kills the whole Spring application context — if
that fails. Postgres is the primary datasource here; SQL Server (`StampLookupService`) is a
secondary, optional integration, so its unavailability was never supposed to be able to take
the whole app down with it. A negative `initializationFailTimeout` tells Hikari to skip that
startup connection probe entirely — the pool comes up regardless of whether SQL Server is
reachable, and a connection is only actually attempted the first time something really uses it
(the "Fetch stamp data" button), which `StampLookupService` already catches and surfaces as a
clear `StampLookupException` message rather than a raw connection failure.

**The tradeoff, worth knowing:** connectivity problems with SQL Server won't be caught at
startup anymore, only when someone actually triggers a lookup — which is exactly what "ignore
this" means in practice, but it does mean a misconfigured `STAMP_DB_*` env var could sit
unnoticed until the first real attempt to use it, rather than failing loud and immediately at
boot the way it used to.

## Startup: customers accessible by ROLE_CUSTOMER_SCOPED marked allocated=true

`DataInitializer.markCustomerScopedAccessibleCustomersAllocated` runs on **every** startup (not
gated behind a one-time check like the admin-account seed above it) and bulk-marks every
`allocated=false`/`null` customer that appears in **at least one `ROLE_CUSTOMER_SCOPED` user's
`accessibleCustomers` grant** as `allocated=true` — one `UPDATE` statement, not a `findAll()` +
loop + `save()`, which at this app's stated scale would mean loading potentially millions of
rows just to flip one flag on each.

**This replaced an earlier version scoped to `ROLE_ANALYST` instead** — corrected after
reconsidering the actual RBAC model: `ROLE_ANALYST` has no customer-scoping concept at all (it
sees every customer, unrestricted), so "customers accessible by Analyst" never encoded a real
signal beyond "all of them." `ROLE_CUSTOMER_SCOPED` is the only role with an actual, explicit,
computable "which customers" list (`AppUser.accessibleCustomers`), so that's what this is now
keyed to. A customer not granted to any Customer-Scoped user is deliberately left alone — Admin
can still flip it manually via the Customer form/list if it should be eligible anyway.

**A real bug caught before this shipped, not after:** the first draft of the query read
`where c.allocated = false or c.allocated is null and c.id in (...)` — without parentheses,
`AND` binds tighter than `OR` in JPQL (same as SQL), so that actually parsed as
`c.allocated = false OR (c.allocated is null AND c.id in (...))` — meaning *any* customer with
`allocated = false` would get marked, regardless of whether they were actually in a
Customer-Scoped grant. Fixed with explicit parentheses:
`where (c.allocated = false or c.allocated is null) and c.id in (...)`.

## Two small fixes

- **`com.lowagie.text.Color` → `java.awt.Color`** in `TaxReporterInvoiceExportService` and
  `CardTransactionExportService` (PDF export). `com.lowagie.text.Color` almost certainly wasn't
  a real class — OpenPDF's `Font.setColor(...)`/`PdfPCell.setBackgroundColor(...)` APIs take
  `java.awt.Color` throughout; this was likely a genuine compile error from when this code was
  first written. No naming collision with POI's own `Font` import (which is why the PDF-side
  `Font` stayed fully-qualified as `com.lowagie.text.Font` rather than getting its own import) —
  `Color` had no such conflict, so it's a clean top-level import in both files now.
- **`StampMachineRepository` and `AppUserRepository` now extend `JpaSpecificationExecutor`**,
  matching every other repository in this project. Interface-level change only — their services
  still use plain `findAll()`/derived queries for now; wiring either onto `Specification`-based
  filtering is a separate task if/when it's needed.

## Data model realignment (CustomerDeposit / CardTransaction / DepositCardTransaction)

This project's deposit-allocation model was rebuilt from scratch after reading the real app's
actual entities — it previously assumed a direct `CardTransaction.customerDeposit` foreign key
(one deposit per transaction, no splitting), which doesn't match reality. The real model routes
through `DepositCardTransaction`, a join entity recording exactly how much of one transaction
came from one deposit — a transaction can be split across several deposits if a single one's
balance isn't enough to cover it.

**What changed:**
- `CardTransaction` — removed `customerDeposit`; added `depositAllocations` (`@OneToMany`), the
  `allocated` flag (true only once FULLY covered, possibly by several deposits together), and
  `getEbmNumber()` (the stamp number of whichever deposit first funded this transaction — the
  fiscal/deposit-side stamp, distinct from this transaction's own `stampData`).
- `CustomerDeposit` — added `currentBalance`, a live denormalized running balance decremented
  directly as allocations happen (not computed from a `SUM` on demand), and the `allocations`
  reverse-mapping.
- `DepositCardTransaction` — new entity: deposit + transaction + customer + `allocatedAmount`.
- `Customer` — added `clientId` and `allocated` (eligibility flag for the matching job — now
  wired through the Customers form/list, not just sitting unused on the entity).
- `CustomerDepositMatchingService` — full rewrite. Fixes a real double-allocation bug found in
  the process: a transaction only partially covered in one run was being reprocessed from its
  *full original amount* on the next run instead of resuming from what was already allocated,
  silently over-allocating against deposit balances. Now bulk-fetches each transaction's
  already-allocated total up front (one aggregate query, not one per transaction) before
  computing what's still owed.

**A bug fixed along the way, not part of the plan going in:** `CustomerDepositService.create()`
constructed a new deposit via `new CustomerDeposit()`, and `@Builder.Default` field initializers
don't apply outside the `.builder().build()` path — so `currentBalance` would have silently been
left `null`. `findAvailableDeposits`'s `currentBalance > minBalance` check would then have
excluded every newly-created deposit from matching forever, since `NULL` is never `>` anything
in SQL, with nothing anywhere to explain why. Every other entity's mapper was checked for the
same pattern (`Customer`, `TerminalMachine`, `StampMachine`, `CardTransaction`,
`TaxReporterInvoice`, `AppUser`) — all already handled their `@Builder.Default` Boolean fields
correctly via explicit null-coalescing in their mappers; only `CustomerDeposit`'s
business-logic-derived `currentBalance` had been missed.

**Also fixed:** the real `CardTransaction.depositAllocations` field was annotated with both
`@ManyToOne` and `@OneToMany(mappedBy = "transaction")` simultaneously — an invalid combination
(a field can't be both a to-one and a to-many relationship), almost certainly a leftover from
copy-adapting another field. This project's version has only the correct `@OneToMany`.

**Not yet realigned** (deferred, per prioritization): `Institution`, `StampMachine`'s and
`TerminalMachine`'s institution relationship, `CardRecharge`, `CardInformation`, `HelpTicket`,
`ApplicationModule` — none of these exist in this project yet. See prior conversation for the
full gap analysis.

## Jobs page

`/jobs` (Admin-only) — a list of background jobs with a "Run Now" button per job, built as a list
rather than a single hardcoded page so a second job has an obvious place to go later without
restructuring. Currently just Customer Deposit Matching, showing an Idle/Running status pill and
disabling the button while a run is already in progress.

**A real correctness issue caught and fixed while wiring this up, not before:** giving
`CustomerDepositMatchingService.matchAll()` a second entry point (this page, alongside the existing
button on the Deposits list) made accidental double-triggering more likely, so it now has the same
`AtomicBoolean` reentrancy guard as `PostgresJob`'s scheduled matching — `matchAll()` returns
`Optional<MatchingResult>`, empty specifically meaning "skipped, a previous run was still in progress"
so the UI can say that rather than implying nothing needed matching.

**While adding that guard, found the annotations weren't doing what they claimed:** the original
`@Transactional` on the per-run and per-customer methods looked like it gave the whole run (or at
least each customer's work) a real transaction boundary — but both are only ever called via
self-invocation (`this.method(...)` from within the same class), and Spring's proxy-based
`@Transactional` does not apply to self-invoked calls. Rather than leave a `@Transactional` that
silently does nothing for its only actual caller, the current code says so directly in both methods'
javadoc, and the aggregate-query rewrite below meant most of what that transaction boundary would have
protected (multiple queries needing to see a consistent state) collapsed to a single query anyway.

**Also rewrote the per-customer allocation query pattern** while touching this: it was doing one
`sumTotalAmountByCustomerDepositId` query *per deposit*; now it's one aggregate `GROUP BY` query per
*customer* covering every deposit at once (`CardTransactionRepository.sumAllocatedAmountByDepositForCustomer`).
Same fix, bigger stakes, applied to the real production job this was modeled on — see the standalone
`DepositAllocationJob.java` handed over separately, which rewrites the original
`allocateCardTransactions()` for the same reasons at a scale where the original's per-transaction
`allocateTransactionToCustomerDeposit()` calls would have been the dominant cost, not just an
avoidable one.

## CustomerDeposit ↔ CardTransaction matching

`CustomerDepositMatchingService` FIFO-allocates unmatched CardTransactions against a customer's
CustomerDeposits, oldest-first on both sides: a deposit is consumed by transactions until the running
total reaches its `totalAmount`, then matching moves on to that customer's next-oldest deposit.
`CardTransaction.customerDeposit` (nullable `@ManyToOne`) records the result — unlike `reconciliation`,
being matched does **not** remove a transaction from the normal ledger; this is an allocation
relationship, not an archival one.

**One real ambiguity in the request, resolved and documented rather than guessed at silently:**
"map each transaction, once the deposit is completed move to the next" doesn't say whether a
transaction that would *overshoot* a deposit's remaining balance should still be assigned to it (and
push the total past `totalAmount`), or should instead be held back for the next deposit to keep each
deposit's total from exceeding its `totalAmount`. Implemented as **plain sequential consumption —
overshoot allowed**: transactions are never split or reordered to fit, whichever one crosses the
threshold is simply the last one assigned to that deposit. If the "hold back to avoid overshoot"
reading is what's actually wanted, that's a real change to the allocation loop in
`CustomerDepositMatchingService`, not a setting to flip — said here so it's an easy thing to revisit,
not a design decision made once and buried.

**Idempotent / safe to re-run:** only ever touches transactions with `customerDeposit IS NULL`, and
re-derives each deposit's already-allocated total from what's actually matched to it (not from
assuming a fresh run) — so running it again after new deposits or transactions arrive continues
correctly instead of double-allocating or restarting from zero.

**Triggered manually** — a "Run Matching" button on the Customer Deposits list, not a scheduled job.
`matchAll()` runs as one transaction covering every customer with unmatched transactions; for a
genuinely large number of unmatched transactions/customers this could mean a long-running transaction.
At real production volume this should likely become a scheduled, batched job (mirroring
`PostgresJob`'s existing matching engine) rather than one synchronous request — the same scale caveat
that already applies to Reconciliation's bulk action elsewhere in this app; not built here since
scheduling wasn't asked for this time.

**Visible from both directions:**
- Customer Deposits list: a "Matched" column (transaction count per deposit).
- Deposit detail page: Allocated Amount, Remaining Balance (goes negative on an overshoot-completed
  deposit — shown in red, not clamped to zero, since a negative value here is a correct outcome of the
  overshoot behavior above, not a bug), and the full list of matched transactions.
- Transaction detail page: a new "Reconciliation & Matching" section showing which deposit (if any)
  a transaction was matched to, alongside the existing reconciliation-batch status.

**Two edge cases handled explicitly, not left to throw or silently misbehave:**
- A deposit with `totalAmount == null` is skipped entirely during matching — permanently unfillable
  until its amount is set, rather than guessing a capacity for it.
- A transaction with `totalAmount == null` is still assigned to the current deposit (it doesn't
  consume any of the deposit's balance, treated as zero for the running total) rather than being
  left permanently unmatched over a data-quality gap.

## SQL Server Stamp lookup (secondary datasource)

A second, independent datasource connects to an external SQL Server database holding a `Stamp` table,
used to fetch `stamp_data` for a `CustomerDeposit` by its `sapReference` — a "Fetch stamp data (SQL
Server)" button on the deposit detail page, mirroring the existing "Mark as Processed" one-click-action
pattern from the Transactions page.

**Deliberately shaped to match the app's existing Oracle datasource config**
(`nika.tax.reporter.oracle.config.OracleJdbcConfig`), not built as a one-off — same package convention
(`nika.tax.reporter.stamp.config.StampJdbcConfig`), same `DataSourceBuilder.create().type(HikariDataSource.class).build()`
pattern, same nesting of properties under `spring.datasource.<name>.*` rather than a standalone top-level
key, `DataSource` as the declared bean type rather than a concrete Hikari type, no `@Bean(name = ...)`
(method name is the bean name), neither bean `@Primary`. Three `JdbcTemplate` beans now exist in the
app — the default one Spring Boot auto-configures for the primary Postgres datasource, the existing
`oracleJdbcTemplate`, and this `stampJdbcTemplate` — `StampLookupService`'s constructor is written by
hand (no `@RequiredArgsConstructor`) specifically so `@Qualifier("stampJdbcTemplate")` can pin down
exactly which one it gets.

**Configuration** — `spring.datasource.stamp.*` in `application.yml`, a sibling block next to
`spring.datasource.oracle.*` (if present) under the same `spring.datasource:` key as the primary
Postgres config — Spring's primary-datasource auto-binding only recognizes its own known field names,
so extra sibling blocks like this one are silently ignored by it rather than conflicting. Real values
go through env vars: `STAMP_DB_URL`, `STAMP_DB_USERNAME`, `STAMP_DB_PASSWORD`. The pool is deliberately
tiny — 3 connections max, 0 minimum idle — since this is a single-row lookup path triggered by a person
clicking a button, not a bulk/high-throughput one.

**A real footgun worth knowing about, in both this config and the existing Oracle one:** because
`@ConfigurationProperties` is applied directly to the bean-producing method (binding straight onto the
`HikariDataSource` `DataSourceBuilder` constructs) rather than going through Spring's own
`DataSourceProperties`/`initializeDataSourceBuilder()` indirection, there's no "url → jdbc-url"
translation happening anywhere — `HikariDataSource` only has `setJdbcUrl()`, not `setUrl()`, so a
property named `url` here silently fails to bind with **no error at startup**; the datasource would just
come up with no JDBC URL configured. This config correctly uses `jdbc-url` — worth double-checking
`spring.datasource.oracle.*` does too, since `OracleJdbcConfig` binds exactly the same way and would
have the identical failure mode if it doesn't.

**Assumptions I made about your actual SQL Server schema** (I don't have it) — table `dbo.Stamp`,
columns `sap_reference` and `stamp_data`. One line to change in `StampLookupService.STAMP_QUERY` if
your real table/column names differ; nothing else in the integration depends on what that SQL says.

**Failure handling:** a `StampLookupException` (SQL Server unreachable, auth failure, etc.) is kept
distinct from "no matching row found" (`Optional.empty()`, a normal outcome — not every deposit has a
stamp yet) — the flash message tells you which of the two actually happened rather than one generic
failure message covering both. Because the lookup only runs on this one explicit button-click action,
a slow or down SQL Server can never block or slow down normal deposit create/edit, which stay entirely
on the primary Postgres datasource.

**Not done, worth knowing:** no retry logic, no circuit breaker, no caching of lookups, and no
automatic fetch-on-save — all deliberately left out since none were asked for and each is a real design
decision (e.g. how long to cache, how many retries) rather than something to default silently.

## Customer Deposits (CRUD + Filter)

New entity added from a real pasted `CustomerDeposit` class — a "deposit" record shaped very like
`CardTransaction` (date/time, client, service, amount, a `stampData`-derived stamp number, a `Customer`
relation) but simpler: only a `processed` boolean, no `success` field, so status is two-state
(Processed/Pending) rather than three. Full stack: entity, repository, filter DTO, specification,
form DTO, mapper, service, controller, and three templates (list+filter, form, view) — same shape as
`TaxReporterInvoice`/`CardTransaction`.

**Two deviations from the pasted code, both deliberate:**

1. **`Customer` import path fixed** — the original imported `nika.tax.reporter.postgres.domain.v2.Customer`; this project's `Customer` lives at `nika.tax.reporter.postgres.domain.Customer` (no `.v2`), so the import was adjusted to match. If your real codebase does have a `.v2` Customer, that's the one line to change back.
2. **`getStampNumber()` bug fixed** — the original called `stampData.split(",")` with no limit, then indexed `parts[3]` unconditionally, throwing `ArrayIndexOutOfBoundsException` on any `stampData` with fewer than 4 comma-separated segments. Same bug class already found and fixed on `CardTransaction`/`TaxReporterInvoice` earlier in this project; fixed here the same way (`split(",", 4)` plus a length guard) before it ever shipped.

**Scope, deliberately narrower than Transactions:** no bulk reconciliation, no "mark as processed"
one-click action, no async export, and `ROLE_CUSTOMER_SCOPED` doesn't get any access at all (not even
scoped) — none of that was asked for this time. `CustomerDeposit` does have a `customer` relation, so
extending customer-scoped access to it later is possible, but it would need the same deliberate
treatment `CardTransaction` got (a query-level exclusion plus a direct-URL guard), not just flipping
on a `SecurityConfig` rule — flagged rather than done speculatively.

Reused `fragments/dropdown.html` for the customer filter's checkbox-dropdown (styles + script) instead
of embedding another inline copy — this is the second consumer of that shared fragment after the Users
form, exactly the reason it was extracted into a fragment in the first place instead of copy-pasted
a third time.

**"Select all" now covers every matching page, not just the current one — with guardrails.**
A "Select all N matching this filter (every page)" checkbox in the toolbar switches to a distinct
server-side mode: it does **not** try to check every row across every page in the browser (which
doesn't scale) and does **not** submit a list of individual transaction IDs at all. Instead it sets a
`reconcileAllMatching=true` flag plus hidden copies of the current filter, and
`ReconciliationService.reconcileMatching(...)` re-derives the matching set server-side from that same
filter via `CardTransactionSpecifications` — the exact query the ledger list itself uses, so it can
never touch more than what was actually being looked at. Two guardrails: it refuses an empty match
(nothing to do), and it refuses anything over 25,000 transactions in one action (`MAX_BULK_RECONCILE`)
with a message asking to narrow the filter — a broad or empty filter can't silently reconcile an
enormous, unreviewed batch in one click. Per-page checkboxes are disabled while this mode is active,
since they'd otherwise misleadingly suggest only the visible rows matter.

## Reconciliation

A `Reconciliation` batch (date + optional reference) acts as a "parent" for a set of CardTransactions
marked reconciled together. Once a transaction is linked to one, it's gone from the normal ledger
everywhere — list, export, customer-scoped views — and only visible again via that batch's own detail
page. Admin/Analyst only; ROLE_CUSTOMER_SCOPED can't reach this feature at all, even though it can
reach `/transactions/**` for normal viewing/filtering.

**How the disappearance is enforced:** `CardTransactionSpecifications.build()` now applies
`WHERE reconciliation IS NULL` unconditionally, before any of the filter-driven predicates — not tied
to any filter field, so there's no toggle to accidentally show reconciled transactions in the normal
list, and it can't be bypassed by a filter combination. The Reconciliation detail page deliberately
doesn't go through this specification at all — it queries `CardTransactionRepository.findByReconciliation_Id`
directly, which is the one place reconciled transactions are meant to still show up.

**How the workflow works:** on `/transactions`, Admin/Analyst see a checkbox column and a small toolbar
(reconciliation date, optional reference, live selected-count, "Reconcile selected"). Per-page selection
is the default; a separate "select all matching this filter" mode covers every page too — see below for
how that's kept safe at scale rather than just checking every box in the browser.
`ReconciliationService.reconcile(...)` validates the selection isn't empty and that none of the selected
transactions are already part of a different reconciliation (defensive — stops a transaction being
silently re-parented if two people act on overlapping selections around the same time) before creating
the batch and linking all selected transactions to it in one call.

**Known gap, not addressed here:** a reconciled transaction can still be edited or re-marked processed
via its direct detail-view URL — reconciliation only removes it from the *list*, it doesn't make the
transaction itself read-only. If "reconciled" should mean "locked," that's a separate change to
`CardTransactionController.update()`/`process()` checking `tx.getReconciliation() != null` — not
included since it wasn't asked for and changes existing edit behavior, worth confirming before adding.

## Invoice plate number filter & column

`TaxReporterInvoice.plateNumber` was already searchable as part of the general free-text search (`q`),
but is now also a dedicated filter field (`/invoices?plateNumber=...`, partial/case-insensitive match,
same `LIKE` approach as the other text filters) and a visible column in the list table, between
"SDC / Stamp" and "Amount". Included in sort/pagination link passthrough, the jump-to-page form, the
active-filter chips, and both Excel/PDF exports (Excel already had it from the original build; PDF's
condensed column set was left as-is, matching the existing print-width tradeoff for that format).

## Mark as Processed (Transaction Detail)

The transaction detail page (`/transactions/{id}`) shows a **"Mark as Processed"** button whenever a
transaction's status is Pending (`processed != true`). It's a one-click shortcut for what's already
possible via the edit form's Processed/Success checkboxes — not a new capability — and always marks the
transaction both `processed = true` and `success = true`, since this button represents a human manually
confirming "this is fine," distinct from what the automated matching engine can do (which can also mark
something processed-but-failed). Marking something as manually *failed* is still an edit-form action,
not a one-click button.

Guarded by the same `assertAccessible` check used everywhere else on this controller — a
`ROLE_CUSTOMER_SCOPED` user can't process a transaction outside their assigned customers by hitting
this endpoint directly, same as they can't view or edit one outside their scope.

## Project-wide CSP safety cleanup

Every inline event-handler attribute (`onclick`, `oninput`, `onchange`, `onsubmit`) across the entire
template set has been converted to delegated `addEventListener` wiring — this started as a fix for
`transaction-view.html`'s new "Mark as Processed" button, then expanded once the same pre-existing
pattern turned up in nine other files: `transactions.html`, `invoices.html`, `transaction-form.html`,
`invoice-form.html`, `invoice-view.html`, and the four simple CRUD list pages
(`users/list.html`, `machines/list.html`, `stamp-machines/list.html`, `customers/list.html`).

Confirmed via `grep -rl "onclick=\|oninput=\|onchange=\|onsubmit="` across the whole `templates/`
directory returning nothing.

Two recurring patterns, fixed consistently everywhere they appeared:

- **Mobile sidebar toggle** (`.menu-toggle`) — was `onclick="document.getElementById('sidebar')..."`
  on every page that had one; now a single delegated `click` listener per page checking
  `e.target.closest('.menu-toggle')`.
- **Destructive-action confirmations** (Delete buttons on the four CRUD list pages) — was
  `onsubmit="return confirm('...')"` inline on each form; now `class="confirm-submit" data-confirm="..."`
  on the form, with one shared delegated `submit` listener per page reading `data-confirm` for the
  message. Same approach as the dropdown filters and the export buttons on the ledger pages, all of
  which went through this exact fix earlier after a real CSP-related bug in production.

Also re-applied while touching `transactions.html` again: the `.dropdown-option[hidden]{display:none;}`
CSS-specificity fix (browsers don't hide `[hidden]` elements if an author stylesheet sets `display`
on the same selector at equal-or-higher specificity) — this file had regressed to the pre-fix version
independent of the inline-handler issue.

## Stack

- Java 17, Spring Boot 3.3.4
- Spring Data JPA + PostgreSQL (Hikari pool)
- Thymeleaf + Spring Security (form login)
- Lombok

## Run it

**1. Start Postgres** (or point at an existing instance):

```bash
docker compose up -d
```

This starts Postgres on `localhost:5434`, database `tax_reporter`, user `postgres` / password `changeme`
(matches `application.yml` defaults — override with the `DB_PASSWORD` env var in real environments).

**2. Run the app:**

```bash
export DB_PASSWORD=changeme
./mvnw spring-boot:run
```

The app starts on **http://localhost:6060/tax-reporter**, redirects to `/login`.

**3. Log in.**

On first boot, since the `app_user` table is empty, a default admin account is created automatically:

- username: `admin`
- password: `admin123` (or whatever `DEFAULT_ADMIN_PASSWORD` env var you set)

**Change this password (or add a real user) immediately** — see "Managing users" below.

## Schema

`spring.jpa.hibernate.ddl-auto=update` is set so Hibernate creates/updates `card_transaction` and
`app_user` tables automatically against the `CardTransaction`/`AppUser` entities on startup — convenient
to get going, but **not safe for a production system with real data**. Once this app is handling real
data, switch to:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

and manage schema changes explicitly with Flyway or Liquibase.

## Managing users

There's no user-management UI yet — add users directly, e.g. via `psql`:

```sql
-- password hash must be a BCrypt hash; generate one with:
-- new BCryptPasswordEncoder().encode("your-password") from a scratch Java snippet,
-- or an online bcrypt generator for local/dev use only.
insert into app_user (username, password, full_name, role, enabled, created_at, updated_at)
values ('j.uwimana', '$2a$10$...bcrypt-hash...', 'J. Uwimana', 'ROLE_ANALYST', true, now(), now());
```

A future iteration could add a `/settings/users` admin screen for this instead.

## Project layout

```
src/main/java/nika/tax/reporter/
  TaxReporterApplication.java
  config/           SecurityConfig, DataInitializer
  security/         UserDetailsServiceImpl
  web/              AuthController, HomeController, CardTransactionController
  service/          CardTransactionService, CardTransactionFilter, CardTransactionSpecifications
  repository/       CardTransactionRepository, AppUserRepository
  postgres/domain/  AbstractEntity, CardTransaction, AppUser

src/main/resources/
  application.yml
  templates/
    login.html
    transactions.html
    fragments/
      styles.html   shared design tokens + sidebar/topbar CSS
      nav.html      sidebar navigation (parameterized by active page)
```

## Export to Excel / PDF

Exports run as **background jobs**, not a direct download over one HTTP connection. The earlier
synchronous version streamed the whole file over whatever connection asked for it — fine for small
exports, but a 1,000,000-row Excel export could take minutes, and depended on one uninterrupted
client connection the entire time. A laptop going to sleep (or wifi dropping, or a tab closing) killed
the network stack mid-transfer (`ERR_NETWORK_IO_SUSPENDED`) and the whole export was lost with no way
to resume.

Now:

1. `POST /transactions/export/{xlsx|pdf}/jobs` — starts generation on a background thread pool
   (`ExportJobService`, 2 workers) and immediately returns a `jobId`. The HTTP request this uses is
   just the start signal — it returns instantly, it isn't held open for the duration of generation.
2. `GET /transactions/export/jobs/{jobId}` — poll this for status (`QUEUED`/`RUNNING`/`COMPLETED`/`FAILED`),
   row count, and (for PDF) whether it was truncated. The page polls this every 1.5s and shows progress
   next to the Excel/PDF buttons.
3. `GET /transactions/export/jobs/{jobId}/download` — once `COMPLETED`, a short-lived download of the
   finished file. If this particular request gets interrupted, the file is still sitting there — just
   re-request the same download URL.

Generation itself is completely decoupled from any browser connection now: if the network drops or the
laptop sleeps mid-export, the job keeps running server-side regardless, and the browser just picks up
wherever it left off (or the person can come back later) as long as it's within the retention window.

**Implementation notes / limitations:**
- Jobs are held in an **in-memory map** (`ExportJobService`), not a database table — simplest thing
  that works for a single-instance deployment. This means jobs don't survive an app restart, and won't
  be visible across instances if this app is ever scaled horizontally. If that becomes a real
  requirement, back this with Redis or a `export_job` table instead.
- Finished files live as temp files on disk and are cleaned up automatically **30 minutes** after
  creation (`ExportJobService.cleanupOldJobs`, a `@Scheduled` task — needs `@EnableScheduling`, already
  on the main application class). Roughly-sized `min-1M`-row Excel exports are fine to sit around for a
  half hour; if your disk is tight or exports are huge and frequent, shorten the retention window.
- PDF stays capped at 5,000 rows for the same reason as before — it's for reading/printing, not a data
  dump. Excel handles the full filtered set at any size via POI's streaming `SXSSFWorkbook`.
- CSRF: the export "start" call is a `POST`, so the page reads the CSRF token/header name out of
  `<meta name="_csrf">` / `<meta name="_csrf_header">` tags in `transactions.html` and sends it as a
  request header — normal Spring Security + Thymeleaf pattern, not anything export-specific.

Dependencies added for this: Apache POI (`poi`, `poi-ooxml`) for Excel, OpenPDF (`com.github.librepdf:openpdf`)
for PDF. Versions are pinned in `pom.xml` — worth running `mvn versions:display-dependency-updates`
once you're set up locally, since I can't verify against Maven Central from here.

## ⚠️ Primary keys are UUIDs, not sequential Longs

Every entity's `id` (`AbstractEntity.id`) is a `UUID`, generated automatically by Hibernate 6 — no
explicit generator annotation needed, just `@Id @GeneratedValue private UUID id;`. Postgres stores
these as native `uuid` columns.

**This was a breaking change from an earlier `Long`/`bigint identity` id**, and `ddl-auto: update`
cannot migrate it safely — it won't drop and retype an existing primary key column, nor the foreign
keys pointing at it (`card_transaction.machine_id`, `terminal_machine.stamp_machine_id`). If you're
running this against a database that already has data in it:

- **Fresh/dev database, no real data to keep:** easiest path — drop the affected tables (or the whole
  schema) and let Hibernate recreate them on next boot.
- **Real data you need to keep:** this needs an actual migration — add a new `uuid` column, backfill
  it (`gen_random_uuid()` per row via Postgres's `pgcrypto`/`uuid-ossp` extension), repoint every
  foreign key at the new column, drop the old `bigint` column, then rename. Worth doing with Flyway/
  Liquibase once you're at that stage rather than by hand — see the earlier note about switching
  `ddl-auto` to `validate` once this app holds real data.

Why UUIDs at all: they don't leak a sequential count of your row volume through the URL
(`/transactions/1024` vs `/transactions/7f3e...`), and they're safe to generate client-side or across
multiple app instances without coordinating on a sequence. Trade-off is they're bulkier as index keys
than a `bigint` — not a concern at this app's scale, but worth knowing if this table ever gets huge.

## CRUD for every entity

Beyond Transactions, there's now full CRUD (list + search + pagination, create/edit form, delete) for:

- **Terminal Machines** (`/machines`) — includes a dropdown to link a machine to a Stamp Machine.
- **Customers** (`/customers`) — `clientName` has a unique DB constraint; a duplicate name comes back
  as a normal field-level form error rather than a raw 500, via `DuplicateValueException`.
- **Stamp Machines** (`/stamp-machines`) — built against the **placeholder** `StampMachine` entity
  (`serialNumber`, `model`, `enabled`). Once the real definition arrives, this whole screen (form +
  list + mapper) needs updating to match — it's clearly labeled as a placeholder in the UI itself as a
  reminder.
- **Users** (`/users`) — see the dedicated section below; this one needed extra care.

All four follow the same shape: `dto/<Entity>Form`, `service/<Entity>Mapper`, `service/<Entity>Service`,
`web/<Entity>Controller`, `templates/<entities>/list.html` + `form.html`. Deliberately simpler than the
Transactions ledger — a single search box instead of the multi-filter panel, no Excel/PDF export, no
separate read-only detail page (edit doubles as view). If any of these need the full Transactions-level
treatment later, the pattern to copy is already established there.

**Delete behavior:** Terminal Machines, Customers, and Stamp Machines can all be referenced by existing
`CardTransaction` rows (or, for Stamp Machines, by a Terminal Machine). Deleting a row that's still
referenced fails at the database FK-constraint level; the service layer catches that
(`DataIntegrityViolationException`) and turns it into a plain-English flash message instead of a stack
trace — nothing is silently cascaded or force-deleted.

### Users deserves its own note

This is the one screen handling actual credentials, so a few things were deliberately built in rather
than left as an afterthought:

- Passwords are **never** sent back to the browser. `AppUserMapper.toForm()` leaves the password field
  blank; on edit, a blank password field means "leave it unchanged" (checked manually in
  `AppUserService`, since "required on create, optional on edit" can't be expressed with a single bean
  validation annotation on one DTO).
- New passwords are hashed with the existing `PasswordEncoder` bean (BCrypt) before hitting the
  database — same encoder `SecurityConfig` already uses for login.
- Minimum password length (8 chars) is enforced in the service layer, not just the browser.
- **You can't delete your own account while signed in as it**, and **you can't delete the last
  remaining user** — both checked in `AppUserService.delete()`. The delete button is also hidden for
  your own row in the list (defense in depth: the server-side check is what actually matters, the UI
  hiding is just to avoid the confusing "why did that fail" moment).

**What's *not* handled, worth knowing:** there's no role-based access control on any of this yet — any
authenticated user (regardless of `ROLE_ANALYST` vs `ROLE_ADMIN`) can currently reach `/users` and
create, edit, or delete other accounts. `SecurityConfig` only checks *is authenticated*, not *has the
right role*. If Users management should be admin-only, that needs
`.requestMatchers("/users/**").hasRole("ADMIN")` added to the filter chain (and similarly for
Machines/Customers/Stamp Machines if those should be restricted too) — flagging this rather than
guessing at what your actual role boundaries should be.

## Invoices — full Transactions-ledger treatment

`TaxReporterInvoice` (`/invoices`) got the same treatment as Transactions rather than the lighter
Machines/Customers pattern: multi-field filters (search, status, date range, amount range), sortable
columns, pagination, a page subtotal, async Excel/PDF export, and a full create/view/edit flow
(`invoices.html`, `invoice-form.html`, `invoice-view.html`). No delete — same reasoning as
`CardTransaction`: this is financial/audit data.

**Two things worth knowing:**

- `stampData` has a unique DB constraint (like `CardTransaction.transactionGuid`); a duplicate is
  surfaced as a form field error via `DuplicateValueException`, not a raw 500.
- **`getStampNumber()` had a real bug in the version provided** — `stampData.split(",")[1]` throws
  `ArrayIndexOutOfBoundsException` on any `stampData` without a comma, which would have crashed the
  entire invoice list page (not just exports) on the first malformed row. Fixed to match the safer
  pattern your own `CardTransaction.getStampNumber()` already uses (checks `parts.length < 2` first).
  Flagging this prominently since it's a behavior change from what was pasted in, not just a style fix.

### Export infrastructure was generalized, not duplicated

Building a second entity's async export was the moment to fix a shortcut from earlier: the original
`ExportJobService` was hardcoded to `CardTransaction`. Rather than copy the whole job-tracking/
threading/cleanup mechanism a second time, it's now driven by a small `ExportTask` interface
(`countMatching()` + `writeTo(OutputStream)`), with each controller building the small entity-specific
lambda:

```java
public interface ExportTask {
    long countMatching();
    boolean writeTo(OutputStream out) throws IOException; // returns true if truncated
}
```

`ExportJobService` itself now knows nothing about CardTransaction or TaxReporterInvoice. One consequence:
**the export URLs changed shape.** Starting a job is still entity-specific (different filter params per
entity), but polling status and downloading the finished file are identical regardless of what's being
exported, so those moved to a neutral path:

- `POST /transactions/export/{format}/jobs` and `POST /invoices/export/{format}/jobs` — start (entity-specific)
- `GET /exports/jobs/{jobId}` and `GET /exports/jobs/{jobId}/download` — poll/download (shared)

If you had anything bookmarked or scripted against the old `/transactions/export/jobs/{id}` poll/download
paths, it'll need updating to `/exports/jobs/{id}`.

## Role-based access: Analysts vs Admins

`ROLE_ANALYST` accounts are now restricted to `/transactions/**`, `/invoices/**`, and `/exports/**`
(the shared export polling/download endpoints those two screens rely on) — enforced in
`SecurityConfig.filterChain()` at the URL level, not just hidden in the UI:

```java
.requestMatchers("/", "/transactions/**", "/invoices/**", "/exports/**").hasAnyRole("ADMIN", "ANALYST")
.anyRequest().hasRole("ADMIN")
```

Everything else — Terminal Machines, Customers, Stamp Machines, Users, Reports, Maintenance, Settings,
Reconciliation, Dashboard — requires `ROLE_ADMIN`. An Analyst hitting one of those URLs directly (typed,
bookmarked, or otherwise) gets a themed 403 (`/access-denied`), not Spring Boot's default whitelabel
error page.

The sidebar also hides links an Analyst can't use (`sec:authorize="hasRole('ADMIN')"` on the relevant
nav items/sections in `fragments/nav.html`) — but that's just to avoid dead-end clicks. **The actual
enforcement is the `SecurityConfig` rule above**; the nav hiding is cosmetic and would do nothing on
its own if someone typed the URL directly.

This is URL-pattern-based authorization, not method-level (`@PreAuthorize`) — consistent with how the
rest of this app's security has been built so far. If individual actions ever need finer-grained rules
than "which URLs can this role reach at all" (e.g. an Analyst who can view invoices but not edit them),
that's the point to introduce `@PreAuthorize` on specific controller methods instead.

## Self-service password change (My Profile)

Every authenticated user — Admin, Analyst, Customer-Scoped, any future role — can change their own
password at `/profile`, without needing an Admin to do it for them via the Users screen. This is a
deliberately separate path from `UserController`'s Admin-only "edit any user" form:

- `AppUserService.changeOwnPassword(username, form)` always resolves the target user from the
  **authenticated principal**, never from a caller-supplied ID — there's no way to reach this endpoint
  and change anyone's password but your own.
- Requires the correct **current password** (`PasswordEncoder.matches(...)` against the stored hash)
  before allowing a change — this endpoint can't be used to silently reset someone else's password even
  if they're already logged in as themselves (i.e. it still asks, rather than trusting the session alone).
- Rejects a new password that's the same as the current one, and enforces the same minimum length
  (8 chars) as account creation.
- `SecurityConfig` opens `/profile/**` with `.authenticated()` — deliberately not `.hasAnyRole(...)`,
  so this doesn't need touching again if a new role is ever added later; being logged in at all is the
  only requirement.

Linked from the sidebar ("My Profile", visible to every role) rather than being buried in a menu only
Admins can see.

## Customer-scoped access: ROLE_CUSTOMER_SCOPED

A new role, `ROLE_CUSTOMER_SCOPED`, restricts a user to seeing Card Transactions for only the
customers explicitly assigned to their account. It's opt-in per user (assigned via the Users form,
not automatic for existing Analysts) and Admin always sees everything regardless of any assignment.

**How it's enforced — twice, deliberately:**

1. **Query level** (`CardTransactionFilter.restrictToCustomerIds` → `CardTransactionSpecifications`):
   a security-only field, separate from the user's own `customerIds` filter picks, set exclusively by
   the controller from the authenticated user's `AppUser.accessibleCustomers` — never from a request
   parameter. It's AND'd against whatever the user filters by, so their own filter choices can only
   narrow further within their allowed set, never escape it. A scoped user with **zero** customers
   assigned sees **zero** transactions (`cb.disjunction()` — an explicit always-false predicate), not
   "everything" — an empty restriction defaults to denial, not to unrestricted access.
2. **Direct-URL level** (`CardTransactionController.assertAccessible`): guards `/transactions/{id}`,
   `/transactions/{id}/edit`, and the update POST — list-page filtering alone doesn't stop a scoped
   user from typing or bookmarking a transaction ID directly. A transaction with no customer at all is
   treated as inaccessible to scoped users (same safe-by-default choice as the empty-assignment case).

Exports (`ExportJobController.startTransactionsExport`) get the identical `restrictToCustomerIds`
treatment — an export is just another way to read the same data, so it needed the same restriction,
not a separate one that could drift out of sync.

**What's *not* scoped:** Invoices — `TaxReporterInvoice` has no customer relation to restrict by, so
`ROLE_CUSTOMER_SCOPED` doesn't get `/invoices/**` access at all (`SecurityConfig`), rather than being
granted unrestricted access to a screen this role's whole premise doesn't apply to. The sidebar hides
the Invoices link for this role accordingly.

**Admin UI:** the Users form (`/users/new`, `/users/{id}/edit`) gained an "Accessible customers"
multi-select — same checkbox-dropdown component as the Transactions filter, now extracted into
`fragments/dropdown.html` so it's defined once and reused rather than duplicated a second time (it was
duplicated once already, for the Customer filter on Transactions — this was the point to stop and
share it instead). The field is always visible on the form regardless of selected role, with a hint
that it's only enforced for Customer-Scoped — hiding/showing it based on the role dropdown's live value
would need a bit of JS this iteration didn't add, so for now an Admin could assign customers to an
Analyst account and those assignments would simply sit unused until/unless that account's role is later
changed to Customer-Scoped.

**Data model:** `AppUser.accessibleCustomers` is a lazy `@ManyToMany` to `Customer`
(`app_user_customer_access` join table). `CustomerAccessScopeService` — the single place that resolves
"what can this user see" — is `@Transactional(readOnly = true)`, required because that lazy collection
would otherwise throw `LazyInitializationException` once the repository call that loaded it returns and
its transaction closes.

## CardTransaction: processed/failed → processed/success

`CardTransaction` was updated to match the real V2 entity shape from later in this project's history:
the `failed` boolean is gone, replaced with `success`. Status derivation across the whole app now reads
`processed` + `success` instead of `processed` + `failed`:

- **Processed** = `processed = true AND success = true`
- **Failed** = `processed = true AND success = false`
- **Pending** = `processed = false`

Updated everywhere this mattered: `CardTransactionForm`, `CardTransactionMapper`,
`CardTransactionSpecifications` (status filter), `CardTransactionExportService` (Excel/PDF status
column), and the three templates that render a status pill or the create/edit checkboxes
(`transactions.html`, `transaction-view.html`, `transaction-form.html`).

**`getStampNumber()` also changed** to match: it's now a 3-part format
(`stampData.split(",", 4)` → `parts[0] + "/" + parts[1] + "/" + parts[3]`, skipping segment index 2)
instead of the earlier 2-part version. The live JS preview in `transaction-form.html` was rewritten to
match — worth knowing that JS's `split(separator, limit)` does **not** behave like Java's: Java's
version folds everything past the limit into the last segment, JS's just truncates the array. The
preview finds comma positions manually instead of relying on `split()` to match Java's semantics.

`TaxReporterInvoice` was **not** touched — it keeps its own separate `failureReason` field and 2-part
stamp number, since nothing indicated those two entities' formats should be unified.

## Create, view, and edit transactions

- `GET /transactions/new` — blank create form
- `POST /transactions` — creates the record, redirects to its detail page
- `GET /transactions/{id}` — read-only detail view
- `GET /transactions/{id}/edit` — edit form, pre-filled
- `POST /transactions/{id}/edit` — saves changes, redirects back to the detail page

Only `dateTimeTransaction` and `totalAmount` are required (matching the entity, where every other
column is nullable). If you leave `transactionGuid` blank on create, one is generated automatically —
the column has a unique constraint in the entity, so leaving it blank consistently on manual entries
is the simplest way to avoid collisions. There's no delete endpoint yet — add one if you need it
(`DELETE`/`POST /transactions/{id}/delete` + a confirmation step, since this is financial data).

## Filtering & pagination

The Terminal/POS machine filter is a multi-select (`<select multiple>`) — pick more than one with
ctrl/cmd-click. The query string carries it as repeated `machineId=...&machineId=...` params, and
`CardTransactionSpecifications` matches with a SQL `IN (...)` rather than a single equality check.

`/transactions` supports query params: `q`, `status` (`PROCESSED`/`PENDING`/`FAILED`), `dateFrom`,
`dateTo`, `minAmount`, `maxAmount`, `sort` (e.g. `dateTimeTransaction,desc`), `page`, `size`.
All filtering happens server-side via a `Specification` — nothing is loaded into memory beyond the
current page, so this scales to large tables. If query performance becomes a concern at high volume,
add indexes on whatever columns you filter/sort by most, e.g.:

```sql
create index idx_card_transaction_date on card_transaction (date_time_txs);
create index idx_card_transaction_status on card_transaction (processed, failed);
```

## Sidebar links that aren't wired up yet

Dashboard, Cards, Reconciliation, Reports, Maintenance, and Settings are present in the sidebar
but don't have controllers/views yet — only Transactions is functional. Clicking the others will
404 until those pages are built.
