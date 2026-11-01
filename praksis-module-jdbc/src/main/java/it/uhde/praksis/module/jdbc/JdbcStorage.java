// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.capability.ValueType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * An entity storage in a database that a JDBC driver reaches, one table per type, a column per
 * field. The table is {@code <prefix><type>} (default {@code praksis_note}; dots in a type id
 * become underscores) with the columns {@code id}, {@code created}, {@code updated}, {@code
 * created_by} and {@code updated_by} and one column per field: text as text, whole numbers as
 * {@code BIGINT}, numbers as {@code DOUBLE PRECISION}, true and false as {@code BOOLEAN}, bytes as
 * binary, a list or a map as JSON text, and times (also {@code created} and {@code updated}) as
 * milliseconds since 1970. A unique field has a unique index. Filters, sorting and paging are done
 * by the database, so the table can be used by other programs too.
 *
 * <p>The module ships no driver. A driver jar in the module's own directory is found, as is the
 * driver of the daemon (SQLite); {@code driver} names its class if there are several. SQLite, H2
 * and PostgreSQL are known (the {@code dialect} setting names one if the driver reports another
 * product). Settings: {@code url} (required), {@code user}, {@code password_credential} (the name
 * of a credential binding that holds the password), {@code driver}, {@code dialect} and {@code
 * table_prefix}.
 */
public final class JdbcStorage implements EntityStorage {
  /** The name of the backend in {@code praksis.toml}. */
  public static final String NAME = "jdbc";

  private static final Set<String> SETTINGS =
      Set.of("url", "user", "password_credential", "driver", "dialect", "table_prefix");
  private static final List<String> BUILT_IN =
      List.of("id", "created", "updated", "created_by", "updated_by");

  private final Map<String, Table> tables = new HashMap<>();
  private String url;
  private String user;
  private String passwordCredential;
  private String driverName;
  private String dialectName;
  private String prefix = "praksis_";
  private Credentials credentials;
  private boolean offline;
  private boolean inTransaction;
  private Connection connection;
  private Dialect dialect;
  private String quote = "\"";

  /** One type: its table and its fields. */
  private record Table(String name, StorageType type) {}

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public synchronized void useOffline() {
    offline = true;
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    this.credentials = credentials;
  }

  @Override
  public synchronized void configure(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!SETTINGS.contains(key)) {
        throw new CapabilityException(
            "unknown setting '" + key + "'; the settings are " + new TreeSet<>(SETTINGS), false);
      }
    }
    url = text(settings, "url", true);
    if (!url.startsWith("jdbc:")) {
      throw new CapabilityException("'url' must start with jdbc:", false);
    }
    user = text(settings, "user", false);
    passwordCredential = text(settings, "password_credential", false);
    driverName = text(settings, "driver", false);
    dialectName = text(settings, "dialect", false);
    if (dialectName != null && Dialect.named(dialectName).isEmpty()) {
      throw new CapabilityException(
          "'dialect' must be one of " + Dialect.ALL.stream().map(Dialect::name).toList(), false);
    }
    String p = text(settings, "table_prefix", false);
    if (p != null) {
      if (!p.matches("[a-z][a-z0-9_]*|")) {
        throw new CapabilityException(
            "'table_prefix' must be lower case letters, digits and _", false);
      }
      prefix = p;
    }
  }

  private static String text(Map<String, Object> settings, String key, boolean required)
      throws CapabilityException {
    Object v = settings.get(key);
    if (v == null) {
      if (required) {
        throw new CapabilityException("'" + key + "' is required", false);
      }
      return null;
    }
    if (!(v instanceof String s) || s.isBlank()) {
      throw new CapabilityException("'" + key + "' must be a text", false);
    }
    return s;
  }

  // --- opening ---

  /** Whether the database of the url is on this machine. */
  static boolean local(String url) {
    if (url.startsWith("jdbc:sqlite:")) {
      return true;
    }
    return url.startsWith("jdbc:h2:file:")
        || url.startsWith("jdbc:h2:mem:")
        || url.startsWith("jdbc:h2:/")
        || url.startsWith("jdbc:h2:./")
        || url.startsWith("jdbc:h2:~/");
  }

  @Override
  public synchronized void open(List<StorageType> types) throws StorageException {
    if (offline && !local(url)) {
      throw new StorageException(
          "offline = true: the database "
              + url
              + " may be on another machine; only a database in a file or in memory can be used");
    }
    connection = connect();
    try {
      DatabaseMetaData meta = connection.getMetaData();
      quote = meta.getIdentifierQuoteString();
      if (quote == null || quote.isBlank()) {
        throw new StorageException("the database cannot quote names");
      }
      quote = quote.strip();
      String product = meta.getDatabaseProductName();
      dialect =
          dialectName != null
              ? Dialect.named(dialectName).orElseThrow()
              : Dialect.of(product)
                  .orElseThrow(
                      () ->
                          new StorageException(
                              "no dialect for the database '"
                                  + product
                                  + "'; set 'dialect' to one of "
                                  + Dialect.ALL.stream().map(Dialect::name).toList()));
      for (StorageType type : types) {
        Table table = new Table(prefix + type.id().replace('.', '_'), type);
        prepare(table);
        tables.put(type.id(), table);
      }
    } catch (SQLException e) {
      close();
      throw new StorageException("cannot prepare the database: " + e.getMessage(), e);
    } catch (StorageException e) {
      close();
      throw e;
    }
  }

  private Connection connect() throws StorageException {
    Properties props = new Properties();
    if (user != null) {
      props.setProperty("user", user);
    }
    if (passwordCredential != null) {
      if (credentials == null) {
        throw new StorageException("'password_credential' is set, but there is no way to get it");
      }
      try {
        props.setProperty("password", credentials.secret(passwordCredential));
      } catch (CapabilityException e) {
        throw new StorageException("cannot get the password: " + e.getMessage(), e);
      }
    }
    Driver driver = driver();
    try {
      Connection c = driver.connect(url, props);
      if (c == null) {
        throw new StorageException(
            "the driver " + driver.getClass().getName() + " does not accept the url " + url);
      }
      c.setAutoCommit(true);
      return c;
    } catch (SQLException e) {
      throw new StorageException("cannot connect to " + url + ": " + e.getMessage(), e);
    }
  }

  /**
   * The driver for the url: the named one, or the first that accepts the url. Drivers are found in
   * the layers (the module's own directory and the daemon's, where the SQLite driver is) and on the
   * class path of the module's class loader.
   */
  private Driver driver() throws StorageException {
    ModuleLayer layer = JdbcStorage.class.getModule().getLayer();
    List<ServiceLoader.Provider<Driver>> providers = new ArrayList<>();
    if (layer != null) {
      providers.addAll(ServiceLoader.load(layer, Driver.class).stream().toList());
    }
    providers.addAll(
        ServiceLoader.load(Driver.class, JdbcStorage.class.getClassLoader()).stream().toList());
    Set<String> seen = new TreeSet<>();
    for (ServiceLoader.Provider<Driver> p : providers) {
      if (!seen.add(p.type().getName())) {
        continue;
      }
      if (driverName != null && !p.type().getName().equals(driverName)) {
        continue;
      }
      try {
        Driver d = p.get();
        if (driverName != null || d.acceptsURL(url)) {
          return d;
        }
      } catch (SQLException | java.util.ServiceConfigurationError e) {
        throw new StorageException(
            "cannot use the driver " + p.type().getName() + ": " + e.getMessage(), e);
      }
    }
    throw new StorageException(
        driverName != null
            ? "there is no driver '" + driverName + "' (drivers found: " + seen + ")"
            : "no driver accepts the url "
                + url
                + " (drivers found: "
                + seen
                + "); put the jar of the driver into the module's archive");
  }

  private String q(String name) {
    return quote + name + quote;
  }

  /** Makes the table, its missing columns and its unique indexes. */
  private void prepare(Table t) throws SQLException, StorageException {
    Set<String> existing = columns(t);
    try (Statement s = connection.createStatement()) {
      if (existing == null) {
        StringBuilder sql = new StringBuilder("CREATE TABLE " + q(t.name()) + " (");
        sql.append(q("id")).append(' ').append(dialect.id()).append(" PRIMARY KEY");
        sql.append(", ").append(q("created")).append(" BIGINT NOT NULL");
        sql.append(", ").append(q("updated")).append(" BIGINT NOT NULL");
        sql.append(", ")
            .append(q("created_by"))
            .append(' ')
            .append(dialect.text())
            .append(" NOT NULL");
        sql.append(", ")
            .append(q("updated_by"))
            .append(' ')
            .append(dialect.text())
            .append(" NOT NULL");
        for (StorageType.Field f : t.type().fields()) {
          sql.append(", ").append(q(f.name())).append(' ').append(dialect.column(f.type()));
        }
        s.execute(sql.append(')').toString());
      } else {
        for (String name : existing) {
          if (!BUILT_IN.contains(name) && t.type().field(name) == null) {
            throw new StorageException(
                "the table "
                    + t.name()
                    + " has a column '"
                    + name
                    + "' that is not a field of type '"
                    + t.type().id()
                    + "'");
          }
        }
        for (String name : BUILT_IN) {
          if (!existing.contains(name)) {
            throw new StorageException("the table " + t.name() + " has no column '" + name + "'");
          }
        }
        for (StorageType.Field f : t.type().fields()) {
          if (!existing.contains(f.name())) {
            s.execute(
                "ALTER TABLE "
                    + q(t.name())
                    + " ADD COLUMN "
                    + q(f.name())
                    + ' '
                    + dialect.column(f.type()));
          }
        }
      }
      for (StorageType.Field f : t.type().fields()) {
        if (f.unique()) {
          s.execute(
              "CREATE UNIQUE INDEX IF NOT EXISTS "
                  + q(t.name() + "_" + f.name() + "_uq")
                  + " ON "
                  + q(t.name())
                  + " ("
                  + q(f.name())
                  + ")");
        }
      }
    }
  }

  /** The column names of the table, or null if there is no such table. */
  private Set<String> columns(Table t) throws SQLException {
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM " + q(t.name()) + " WHERE 1 = 0")) {
      ResultSetMetaData m = r.getMetaData();
      Set<String> names = new HashSet<>();
      for (int i = 1; i <= m.getColumnCount(); i++) {
        names.add(m.getColumnLabel(i));
      }
      return names;
    } catch (SQLException e) {
      try (Statement s = connection.createStatement()) {
        s.executeQuery("SELECT 1").close();
        return null; // the connection works, so the table is not there
      }
    }
  }

  private Table table(String type) throws StorageException {
    Table t = tables.get(type);
    if (t == null) {
      throw new StorageException("the storage 'jdbc' was not opened for type '" + type + "'");
    }
    if (connection == null) {
      throw new StorageException("the storage 'jdbc' is closed");
    }
    return t;
  }

  // --- values ---

  private static void bind(PreparedStatement s, int i, ValueType type, Object v)
      throws SQLException {
    switch (type) {
      case STRING -> s.setString(i, (String) v);
      case INT -> s.setLong(i, (Long) v);
      case DOUBLE -> s.setDouble(i, (Double) v);
      case BOOL -> s.setBoolean(i, (Boolean) v);
      case TIMESTAMP -> s.setLong(i, ((Instant) v).toEpochMilli());
      case BYTES -> s.setBytes(i, (byte[]) v);
      case LIST, MAP -> s.setString(i, Json.write(v));
    }
  }

  private static int sqlType(ValueType type) {
    return switch (type) {
      case STRING, LIST, MAP -> java.sql.Types.VARCHAR;
      case INT, TIMESTAMP -> java.sql.Types.BIGINT;
      case DOUBLE -> java.sql.Types.DOUBLE;
      case BOOL -> java.sql.Types.BOOLEAN;
      case BYTES -> java.sql.Types.VARBINARY;
    };
  }

  private static Object read(ResultSet r, int i, ValueType type)
      throws SQLException, StorageException {
    Object v =
        switch (type) {
          case STRING -> r.getString(i);
          case INT -> r.getLong(i);
          case DOUBLE -> r.getDouble(i);
          case BOOL -> r.getBoolean(i);
          case TIMESTAMP -> {
            long ms = r.getLong(i);
            yield r.wasNull() ? null : Instant.ofEpochMilli(ms);
          }
          case BYTES -> r.getBytes(i);
          case LIST, MAP -> {
            String json = r.getString(i);
            yield json == null ? null : Json.read(json, type);
          }
        };
    return r.wasNull() ? null : v;
  }

  private StoredEntity row(Table t, ResultSet r) throws SQLException, StorageException {
    Map<String, Object> fields = new LinkedHashMap<>();
    int i = 6;
    for (StorageType.Field f : t.type().fields()) {
      Object v = read(r, i++, f.type());
      if (v != null) {
        fields.put(f.name(), v);
      }
    }
    return new StoredEntity(
        r.getString(1),
        Instant.ofEpochMilli(r.getLong(2)),
        Instant.ofEpochMilli(r.getLong(3)),
        r.getString(4),
        r.getString(5),
        Collections.unmodifiableMap(fields));
  }

  private String selectList(Table t) {
    StringBuilder sb = new StringBuilder();
    for (String c : BUILT_IN) {
      sb.append(q(c)).append(", ");
    }
    t.type().fields().forEach(f -> sb.append(q(f.name())).append(", "));
    return sb.substring(0, sb.length() - 2);
  }

  // --- the storage ---

  @Override
  public synchronized Optional<StoredEntity> get(String type, String id) throws StorageException {
    Table t = table(type);
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT " + selectList(t) + " FROM " + q(t.name()) + " WHERE " + q("id") + " = ?")) {
      s.setString(1, id);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(row(t, r)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failed("read", e);
    }
  }

  private StorageException failed(String what, SQLException e) {
    return new StorageException("cannot " + what + ": " + e.getMessage(), e);
  }

  @Override
  public synchronized void insert(String type, StoredEntity e) throws StorageException {
    Table t = table(type);
    StringBuilder names = new StringBuilder();
    StringBuilder marks = new StringBuilder();
    for (String c : BUILT_IN) {
      names.append(q(c)).append(", ");
      marks.append("?, ");
    }
    for (StorageType.Field f : t.type().fields()) {
      names.append(q(f.name())).append(", ");
      marks.append("?, ");
    }
    String sql =
        "INSERT INTO "
            + q(t.name())
            + " ("
            + names.substring(0, names.length() - 2)
            + ") VALUES ("
            + marks.substring(0, marks.length() - 2)
            + ")";
    try (PreparedStatement s = connection.prepareStatement(sql)) {
      bindEntity(s, t, e);
      s.executeUpdate();
    } catch (SQLException ex) {
      throw failed("insert", ex);
    }
  }

  @Override
  public synchronized void replace(String type, StoredEntity e) throws StorageException {
    Table t = table(type);
    StringBuilder set = new StringBuilder();
    for (String c : BUILT_IN.subList(1, BUILT_IN.size())) {
      set.append(q(c)).append(" = ?, ");
    }
    for (StorageType.Field f : t.type().fields()) {
      set.append(q(f.name())).append(" = ?, ");
    }
    String sql =
        "UPDATE "
            + q(t.name())
            + " SET "
            + set.substring(0, set.length() - 2)
            + " WHERE "
            + q("id")
            + " = ?";
    try (PreparedStatement s = connection.prepareStatement(sql)) {
      int i = bindEntity(s, t, e, false);
      s.setString(i, e.id());
      if (s.executeUpdate() == 0) {
        throw new StorageException("there is no " + type + " " + e.id());
      }
    } catch (SQLException ex) {
      throw failed("replace", ex);
    }
  }

  private int bindEntity(PreparedStatement s, Table t, StoredEntity e) throws SQLException {
    return bindEntity(s, t, e, true);
  }

  /** Binds the values; the id first if asked. Returns the index after the last one. */
  private int bindEntity(PreparedStatement s, Table t, StoredEntity e, boolean withId)
      throws SQLException {
    int i = 1;
    if (withId) {
      s.setString(i++, e.id());
    }
    s.setLong(i++, e.created().toEpochMilli());
    s.setLong(i++, e.updated().toEpochMilli());
    s.setString(i++, e.createdBy());
    s.setString(i++, e.updatedBy());
    for (StorageType.Field f : t.type().fields()) {
      Object v = e.fields().get(f.name());
      if (v == null) {
        s.setNull(i++, sqlType(f.type()));
      } else {
        bind(s, i++, f.type(), v);
      }
    }
    return i;
  }

  @Override
  public synchronized boolean delete(String type, String id) throws StorageException {
    Table t = table(type);
    try (PreparedStatement s =
        connection.prepareStatement("DELETE FROM " + q(t.name()) + " WHERE " + q("id") + " = ?")) {
      s.setString(1, id);
      return s.executeUpdate() > 0;
    } catch (SQLException e) {
      throw failed("delete", e);
    }
  }

  @Override
  public synchronized Optional<String> findUnique(String type, String field, Object value)
      throws StorageException {
    Table t = table(type);
    StorageType.Field f = t.type().field(field);
    if (f == null) {
      return Optional.empty();
    }
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT " + q("id") + " FROM " + q(t.name()) + " WHERE " + q(field) + " = ?")) {
      bind(s, 1, f.type(), value);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failed("look up a unique value", e);
    }
  }

  @Override
  public synchronized Page query(String type, Query query) throws StorageException {
    Table t = table(type);
    List<Object[]> params = new ArrayList<>(); // {ValueType, value}
    String where = query.filter().map(f -> where(t, f, params)).orElse("1 = 1");
    String sortColumn = q(query.sort());
    String order =
        "CASE WHEN "
            + sortColumn
            + " IS NULL THEN 0 ELSE 1 END "
            + (query.reverse() ? "DESC" : "ASC")
            + ", "
            + sortColumn
            + (query.reverse() ? " DESC" : " ASC")
            + ", "
            + q("id")
            + " ASC";
    try {
      long total;
      try (PreparedStatement s =
          connection.prepareStatement("SELECT COUNT(*) FROM " + q(t.name()) + " WHERE " + where)) {
        bindAll(s, params);
        try (ResultSet r = s.executeQuery()) {
          r.next();
          total = r.getLong(1);
        }
      }
      List<StoredEntity> items = new ArrayList<>();
      try (PreparedStatement s =
          connection.prepareStatement(
              "SELECT "
                  + selectList(t)
                  + " FROM "
                  + q(t.name())
                  + " WHERE "
                  + where
                  + " ORDER BY "
                  + order
                  + " LIMIT ? OFFSET ?")) {
        int i = bindAll(s, params);
        s.setInt(i++, query.limit());
        s.setInt(i, query.offset());
        try (ResultSet r = s.executeQuery()) {
          while (r.next()) {
            items.add(row(t, r));
          }
        }
      }
      return new Page(items, total);
    } catch (SQLException e) {
      throw failed("query", e);
    }
  }

  private static int bindAll(PreparedStatement s, List<Object[]> params) throws SQLException {
    int i = 1;
    for (Object[] p : params) {
      if (p[0] == null) {
        s.setLong(i++, (Long) p[1]); // a length
      } else {
        bind(s, i++, (ValueType) p[0], p[1]);
      }
    }
    return i;
  }

  /** The type of a column a filter names. */
  private static ValueType typeOf(Table t, String field) {
    return switch (field) {
      case "id", "created_by", "updated_by" -> ValueType.STRING;
      case "created", "updated" -> ValueType.TIMESTAMP;
      default -> t.type().field(field).type();
    };
  }

  private String where(Table t, Filter f, List<Object[]> params) {
    switch (f) {
      case Filter.Equals e -> {
        params.add(new Object[] {typeOf(t, e.field()), e.value()});
        return q(e.field()) + " = ?";
      }
      case Filter.OneOf o -> {
        ValueType type = typeOf(t, o.field());
        o.values().forEach(v -> params.add(new Object[] {type, v}));
        return q(o.field())
            + " IN ("
            + String.join(", ", Collections.nCopies(o.values().size(), "?"))
            + ")";
      }
      case Filter.Prefix p -> {
        params.add(new Object[] {null, (long) p.value().codePointCount(0, p.value().length())});
        params.add(new Object[] {ValueType.STRING, p.value()});
        return "substr(" + q(p.field()) + ", 1, ?) = ?";
      }
      case Filter.Range r -> {
        ValueType type = typeOf(t, r.field());
        List<String> parts = new ArrayList<>();
        bound(parts, params, q(r.field()), type, ">", r.gt());
        bound(parts, params, q(r.field()), type, ">=", r.gte());
        bound(parts, params, q(r.field()), type, "<", r.lt());
        bound(parts, params, q(r.field()), type, "<=", r.lte());
        return String.join(" AND ", parts);
      }
      case Filter.And a -> {
        return "("
            + String.join(" AND ", a.of().stream().map(x -> where(t, x, params)).toList())
            + ")";
      }
      case Filter.Or o -> {
        return "("
            + String.join(" OR ", o.of().stream().map(x -> where(t, x, params)).toList())
            + ")";
      }
      case Filter.Not n -> {
        return "NOT COALESCE(" + where(t, n.of(), params) + ", FALSE)";
      }
    }
  }

  private static void bound(
      List<String> parts,
      List<Object[]> params,
      String column,
      ValueType type,
      String op,
      Object v) {
    if (v != null) {
      params.add(new Object[] {type, v});
      parts.add(column + " " + op + " ?");
    }
  }

  @Override
  public synchronized <T> T atomically(Work<T> work) throws StorageException {
    if (inTransaction || connection == null) {
      return work.run();
    }
    try {
      connection.setAutoCommit(false);
      inTransaction = true;
      try {
        T result = work.run();
        connection.commit();
        return result;
      } catch (StorageException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        inTransaction = false;
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw failed("run a transaction", e);
    }
  }

  @Override
  public synchronized void close() {
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException e) {
        // the daemon is stopping
      }
      connection = null;
    }
  }
}
