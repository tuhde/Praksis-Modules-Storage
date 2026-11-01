// SPDX-License-Identifier: Apache-2.0
/** An entity storage that keeps the entities of a type in one CSV file. */
module it.uhde.praksis.module.csvfile {
  requires it.uhde.praksis.capability;
  requires it.uhde.praksis.storage.files;
  requires com.fasterxml.jackson.databind;

  provides it.uhde.praksis.capability.EntityStorage with
      it.uhde.praksis.module.csvfile.CsvFileStorage;
}
