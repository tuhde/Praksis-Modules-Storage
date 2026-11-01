// SPDX-License-Identifier: Apache-2.0
/** An entity storage that keeps the entities of a type in one JSON file. */
module it.uhde.praksis.module.jsonfile {
  requires it.uhde.praksis.capability;
  requires com.fasterxml.jackson.databind;

  provides it.uhde.praksis.capability.EntityStorage with
      it.uhde.praksis.module.jsonfile.JsonFileStorage;
}
