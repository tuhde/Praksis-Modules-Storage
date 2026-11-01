# Praksis-Modules-Storage

The entity storages of [Praksis](https://github.com/tuhde/Praksis), as module archives.

Praksis keeps its entities (the typed data that rules, screens and a person work with) in the
SQLite database of the daemon unless a type is bound to another **storage**. A module can provide
a storage; this repository has them.

| Module | Backend | Keeps the entities of a type in |
|---|---|---|
| `json-file` | `json-file` | one readable JSON file, `<type>.json` |
| `csv-file` | `csv-file` | one CSV file, `<type>.csv`, that a spreadsheet opens |
| `jdbc` | `jdbc` | a table of a database that a JDBC driver reaches (SQLite, H2 and PostgreSQL are known) |

The two file storages share how they keep their files (module `praksis-storage-files`,
inside their archives): the files are written whole to a temporary file that replaces the old one,
so a crash leaves the old or the new file; they are readable by their owner only; and a file that is
changed by hand or by another program while Praksis runs is read again before the next operation,
which makes a file a way to import and to export. Praksis is the only writer that is safe to run at
the same time. A file that is not right stops the start with the file and the entity or row named.

## Build and install

Build the core first (`./mvnw install` in `../Praksis`: it makes the runtime image and the
artifacts these modules compile against), then:

```sh
./mvnw verify        # unit tests (the contract every storage passes), the archives, integration tests
```

Each module directory then has its archive in `target/<name>.praksis-module`. Install it with the
command line of Praksis:

```sh
praksis modules install praksis-module-json-file/target/json-file.praksis-module
```

## Use

Where data lives is a decision of the person, so it is in `praksis.toml` and never in a ruleset:

```toml
[entities.storage.notes]          # a storage, named by you
backend = "json-file"             # the backend a module provides
directory = "/home/me/notes"      # its settings (here: where the files are; optional)

[entities.bind]                   # which type is kept where
note = "notes"                    # every type that is not bound stays in the daemon's database
```

The module that provides a configured storage is used without a `[module.*]` section. Storages
that need a password take it from a credential binding in `policy.toml` (the component is
`entity-storage:<name>`).

What a storage does not do: Praksis checks types, required and unique values, sets the id, the
times and who made a change, and writes to one storage one change at a time. A storage only keeps
and finds. With a storage outside the daemon's database a change is written first and its event
after it, so a crash in between loses the event but never the change.

## json-file

`<directory>/<type>.json`:

```json
{ "format": 1, "type": "note", "entities": [
  { "id": "…", "created": "2026-11-01T10:00:00Z", "updated": "…",
    "created_by": "user", "updated_by": "user",
    "fields": { "subject": "Buy milk", "pinned": true } } ] }
```

Times are ISO-8601 text and bytes are Base64. Settings: `directory` (default: the module's own
directory in the Praksis home, `data/storage/<name>`; a relative path is below that) and `pretty`
(indented; default `true`).

## csv-file

`<directory>/<type>.csv`, UTF-8:

```csv
id,created,updated,created_by,updated_by,name,email,age
7f3c…,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,"Mei, Ling",mei@example.org,41
```

The first line names the columns: `id`, `created`, `updated`, `created_by`, `updated_by`, then the
fields of the type. A missing value is an empty cell and an empty text is `""`; times are ISO-8601
text, bytes are Base64, a list or a map is JSON in the cell. When the file is read the columns may
be in any order, a field without a column is missing, a byte order mark and any line end are
accepted, and `true` may be `TRUE` (a spreadsheet's habit); the file is written with all columns in
the order above.

**Formulas.** A spreadsheet runs a cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage
return as a formula, and the texts of entities can come from mail. Such a text is therefore written
with a `'` in front and read back without it. It round-trips exactly; `escape_formulas = false`
turns it off.

Settings: `directory` (as above), `delimiter` (one character; default `,`; `;` suits a German
spreadsheet) and `escape_formulas` (default `true`).

## jdbc

One table per type, `<prefix><type>` (default `praksis_note`; dots in a type id become underscores),
with the columns `id`, `created`, `updated`, `created_by`, `updated_by` and one per field:

| Field type | Column |
|---|---|
| `string`, `list`, `map` | text (a list or a map as JSON) |
| `int` | `BIGINT` |
| `double` | `DOUBLE PRECISION` |
| `bool` | `BOOLEAN` |
| `timestamp`, and `created`, `updated` | `BIGINT`: milliseconds since 1970 |
| `bytes` | binary |

A unique field has a unique index. Names are quoted, so a field may be called `order`. Filters,
sorting and paging are done by the database, so other programs can read and write the table: a row
that another program inserts is an entity, as long as its `id`, times and `created_by` and
`updated_by` are filled. A table that exists is checked (a column that is not a field of the type
stops the start; a field without a column gets one); the module makes no other change to a
database.

```toml
[entities.storage.shop]
backend = "jdbc"
url = "jdbc:postgresql://db.example.org/shop"
user = "praksis"
password_credential = "shop-db"     # the name of a credential binding in policy.toml
table_prefix = "praksis_"           # optional

[entities.bind]
order = "shop"
```

```toml
# policy.toml: the binding that holds the password, for this storage only
[credential."shop-db"]
command = ["secret-tool", "lookup", "service", "shop-db"]
capabilities = ["entity-storage:shop"]
```

**The driver.** The module ships none. The daemon has the driver of SQLite
(`url = "jdbc:sqlite:/path/to/file"`); for another database put the jar of its driver into the
archive next to the module jar and install the archive (or unzip the archive, add the jar, zip it
again). If several drivers are there, `driver = "org.postgresql.Driver"` names the one. A driver
may need JDK modules that the runtime image of Praksis lacks; the image has `java.sql`,
`java.logging`, `java.naming` and `java.transaction.xa`.

**Dialects.** SQLite and H2 are tested here (the same twelve tests pass for both). PostgreSQL has
a dialect (`dialect = "postgresql"` if the driver reports another product name) that has **not been
run against a live server by the author**: `JdbcStoragePostgresTest` runs the contract against one
if `PRAKSIS_TEST_PG_URL` (and `PRAKSIS_TEST_PG_USER`, `PRAKSIS_TEST_PG_PASSWORD`) name an empty
database, and runs nothing otherwise. MariaDB and MySQL have no dialect yet (their default
collation does not tell `a` from `A`, which a storage must).

**Offline.** With `offline = true` only a database in a file or in memory (`jdbc:sqlite:`,
`jdbc:h2:file:`, `jdbc:h2:mem:`) is used; any other url stops the start.

**Transactions.** Each change is its own transaction. Praksis writes the change first and its event
after it (see above), so a crash between the two loses the event, never the change.

## For authors of a storage

Implement `it.uhde.praksis.capability.EntityStorage`, provide it as a service, list its name in
`backends = [...]` of `module.toml`, and extend `EntityStorageContract` from
`praksis-storage-testkit` in your tests: the same twelve tests hold for every storage (SQLite, the files, JDBC).
`FilterMatcher` evaluates filters and sorts in memory for a storage that cannot push them down.
