// SPDX-License-Identifier: Apache-2.0
/** An entity storage in a database that a JDBC driver reaches. */
module it.uhde.praksis.module.jdbc {
  requires it.uhde.praksis.capability;
  requires java.sql;

  uses java.sql.Driver;

  provides it.uhde.praksis.capability.EntityStorage with
      it.uhde.praksis.module.jdbc.JdbcStorage;
}
