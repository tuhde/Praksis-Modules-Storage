# Praksis-Modules-Storage

The entity storages of [Praksis](https://github.com/tuhde/Praksis), as module archives.

Praksis keeps its entities (the typed data that rules, screens and a person work with) in the
SQLite database of the daemon unless a type is bound to another **storage**. A module can provide
a storage; this repository has them.

| Module | Backend | Keeps the entities of a type in |
|---|---|---|
| `json-file` | `json-file` | one readable JSON file, `<type>.json`; a file changed by hand is read again |

(`csv-file` and `jdbc` follow.)

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

Times are ISO-8601 text and bytes are Base64. The file is written whole to a temporary file that
then replaces the old one, so a crash leaves the old or the new file, never half of one; it is
readable by its owner only. A file changed by hand or by another program while Praksis runs is read
again before the next operation, which makes the file a way to import and to export. Praksis is the
only writer that is safe to run at the same time. A file that is not right (not JSON, another
type, an undeclared field, a value of the wrong type, an id twice) stops the start with the file
and the entity named.

Settings: `directory` (default: the module's own directory in the Praksis home, `data/storage/<name>`)
and `pretty` (indented; default `true`).

## For authors of a storage

Implement `it.uhde.praksis.capability.EntityStorage`, provide it as a service, list its name in
`backends = [...]` of `module.toml`, and extend `EntityStorageContract` from
`praksis-storage-testkit` in your tests: the same ten tests hold for every storage.
`FilterMatcher` evaluates filters and sorts in memory for a storage that cannot push them down.
