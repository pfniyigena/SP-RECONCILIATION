# Flyway (enabled in 2.1.0)

Schema changes are now migrations in `src/main/resources/db/migration/`:
- `V1__baseline.sql` - the real production schema from the 2026-11-17 `pg_dump` (cleaned for Flyway).
- `V2__customer_deposit_stamp_lookup_attempts.sql` - the one column 2.1.0 adds.

How it behaves
- **Existing database (production, your dev copy):** no Flyway history table yet, so `baseline-on-migrate`
  marks it as version 1 without running V1, then runs V2 and later. Safe to start on the live schema.
- **Empty database:** V1 builds the whole schema, then V2.
- **sp:** `ddl-auto=validate` - Hibernate only checks that tables/columns match the entities.
- **dev:** `ddl-auto=update` is kept; Flyway still runs.
- Flyway uses the primary Postgres datasource only (never Oracle / SQL Server).
- The baseline keeps tables that belong to other modules (application_module, card_information,
  card_recharge, help_ticket) because they exist in production. `card_transaction.ebm_data` is also in the
  schema but not mapped by any entity - harmless, `validate` ignores extra columns.

## REHEARSE BEFORE PRODUCTION (important - `validate` was not run against a real database here)
1. Restore a production backup into a scratch Postgres database.
2. Start the app against it with validation switched on, even in dev:
   `java -jar tax-reporter-2.1.3-dev.jar --spring.jpa.hibernate.ddl-auto=validate`
3. Expect in the log: `Creating Schema History table`, `Successfully baselined schema with version: 1`,
   `Migrating schema "public" to version "2"`, then a normal startup.
4. If it stops with `Schema-validation: ...`, send me the message. Fix it with a new `V3__...sql`, never by
   editing V1/V2. As an emergency switch only, set `spring.jpa.hibernate.ddl-auto=none` in sp.

## Adding a change later
Create `V3__short_description.sql` (use `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`), rebuild,
deploy. Never edit a migration that has already run anywhere - Flyway checksums it and will refuse to start.
