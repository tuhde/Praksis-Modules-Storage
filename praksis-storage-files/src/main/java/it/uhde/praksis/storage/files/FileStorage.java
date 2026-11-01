// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.storage.files;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.FilterMatcher;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * An entity storage that keeps the entities of each type in one file, {@code <type>.<extension>},
 * in a format that a subclass reads and writes. The files are read into memory and written whole
 * after every change, to a temporary file that replaces the old one, so a crash leaves the old or
 * the new file and never half of one; they are readable by their owner only. A file that is changed
 * by hand (or by another program) while Praksis runs is read again before the next operation, so a
 * file is also a way to import and to export. Praksis is the only writer that is safe to run at the
 * same time.
 *
 * <p>Settings: {@code directory} (default: the module's own directory in the Praksis home; a
 * relative path is below that) and whatever {@link #ownSettings} names.
 */
public abstract class FileStorage implements EntityStorage {
  private static final String DIRECTORY = "directory";

  private final Map<String, TypeFile> files = new HashMap<>();
  private String directorySetting;
  private Path dataDirectory;

  protected FileStorage() {}

  /** The extension of the files, without a dot, for example {@code json}. */
  protected abstract String extension();

  /** The names of the settings of the format, besides {@code directory}. */
  protected Set<String> ownSettings() {
    return Set.of();
  }

  /** Reads the settings of the format, after the names were checked. */
  protected void configureFormat(Map<String, Object> settings) throws CapabilityException {}

  /**
   * Reads the content of a file of a type. The message of an exception is put after the name of the
   * file by the caller, so it needs not name the file.
   */
  protected abstract List<StoredEntity> read(StorageType type, byte[] content)
      throws StorageException;

  /** The content of the file for these entities. */
  protected abstract byte[] write(StorageType type, Collection<StoredEntity> entities)
      throws StorageException;

  @Override
  public synchronized void configure(Map<String, Object> settings) throws CapabilityException {
    Set<String> allowed = new TreeSet<>(ownSettings());
    allowed.add(DIRECTORY);
    for (String key : settings.keySet()) {
      if (!allowed.contains(key)) {
        throw new CapabilityException(
            "unknown setting '" + key + "'; the settings are " + allowed, false);
      }
    }
    Object dir = settings.get(DIRECTORY);
    if (dir != null && !(dir instanceof String s && !s.isBlank())) {
      throw new CapabilityException("'directory' must be a path", false);
    }
    directorySetting = (String) dir;
    configureFormat(settings);
  }

  @Override
  public synchronized void useDataDirectory(Path dir) {
    this.dataDirectory = dir;
  }

  /** One type: its file, its entities in memory, and how the file looked when they were read. */
  private static final class TypeFile {
    final StorageType type;
    final Path file;
    final Map<String, StoredEntity> entities = new LinkedHashMap<>();
    long modified = -1;
    long size = -1;

    TypeFile(StorageType type, Path file) {
      this.type = type;
      this.file = file;
    }
  }

  @Override
  public synchronized void open(List<StorageType> types) throws StorageException {
    Path directory;
    if (directorySetting != null) {
      Path d = Path.of(directorySetting);
      directory = d.isAbsolute() || dataDirectory == null ? d : dataDirectory.resolve(d);
    } else if (dataDirectory != null) {
      directory = dataDirectory;
    } else {
      throw new StorageException("no directory: set 'directory' for this storage");
    }
    directory = directory.toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new StorageException(
          "cannot make the directory " + directory + ": " + e.getMessage(), e);
    }
    for (StorageType type : types) {
      TypeFile f = new TypeFile(type, directory.resolve(type.id() + "." + extension()));
      load(f);
      files.put(type.id(), f);
    }
  }

  private TypeFile file(String type) throws StorageException {
    TypeFile f = files.get(type);
    if (f == null) {
      throw new StorageException(
          "the storage '" + name() + "' was not opened for type '" + type + "'");
    }
    refresh(f);
    return f;
  }

  /** Reads the file again if it is not as it was when it was read. */
  private void refresh(TypeFile f) throws StorageException {
    try {
      boolean exists = Files.exists(f.file);
      long modified = exists ? Files.getLastModifiedTime(f.file).toMillis() : -1;
      long size = exists ? Files.size(f.file) : -1;
      if (modified != f.modified || size != f.size) {
        load(f);
      }
    } catch (IOException e) {
      throw new StorageException("cannot look at " + f.file + ": " + e.getMessage(), e);
    }
  }

  private void load(TypeFile f) throws StorageException {
    f.entities.clear();
    if (!Files.exists(f.file)) {
      f.modified = -1;
      f.size = -1;
      return;
    }
    byte[] content;
    try {
      f.modified = Files.getLastModifiedTime(f.file).toMillis();
      f.size = Files.size(f.file);
      content = Files.readAllBytes(f.file);
    } catch (IOException e) {
      throw new StorageException("cannot read " + f.file + ": " + e.getMessage(), e);
    }
    try {
      for (StoredEntity e : read(f.type, content)) {
        if (f.entities.put(e.id(), e) != null) {
          throw new StorageException("the id " + e.id() + " is used twice");
        }
      }
    } catch (StorageException e) {
      f.entities.clear();
      f.modified = -1;
      f.size = -1;
      throw new StorageException(f.file + ": " + e.getMessage(), e);
    }
  }

  /** Writes the whole file to a temporary file in the same directory and puts it in place. */
  private void save(TypeFile f) throws StorageException {
    byte[] bytes = write(f.type, f.entities.values());
    Path temp = f.file.resolveSibling(f.file.getFileName() + ".tmp");
    try {
      Files.deleteIfExists(temp);
      try {
        Files.createFile(
            temp,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException e) {
        Files.createFile(temp);
      }
      try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        c.write(ByteBuffer.wrap(bytes));
        c.force(true);
      }
      try {
        Files.move(
            temp, f.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, f.file, StandardCopyOption.REPLACE_EXISTING);
      }
      f.modified = Files.getLastModifiedTime(f.file).toMillis();
      f.size = Files.size(f.file);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
        // the write failed already; the temporary file is harmless
      }
      throw new StorageException("cannot write " + f.file + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized Optional<StoredEntity> get(String type, String id) throws StorageException {
    return Optional.ofNullable(file(type).entities.get(id));
  }

  @Override
  public synchronized void insert(String type, StoredEntity entity) throws StorageException {
    TypeFile f = file(type);
    if (f.entities.containsKey(entity.id())) {
      throw new StorageException("the id " + entity.id() + " is in use");
    }
    f.entities.put(entity.id(), entity);
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.remove(entity.id());
      throw e;
    }
  }

  @Override
  public synchronized void replace(String type, StoredEntity entity) throws StorageException {
    TypeFile f = file(type);
    StoredEntity before = f.entities.get(entity.id());
    if (before == null) {
      throw new StorageException("there is no " + type + " " + entity.id());
    }
    f.entities.put(entity.id(), entity);
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.put(entity.id(), before);
      throw e;
    }
  }

  @Override
  public synchronized boolean delete(String type, String id) throws StorageException {
    TypeFile f = file(type);
    StoredEntity before = f.entities.remove(id);
    if (before == null) {
      return false;
    }
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.put(id, before);
      throw e;
    }
    return true;
  }

  @Override
  public synchronized Page query(String type, Query query) throws StorageException {
    return FilterMatcher.page(new ArrayList<>(file(type).entities.values()), query);
  }

  @Override
  public synchronized Optional<String> findUnique(String type, String field, Object value)
      throws StorageException {
    Filter equals = new Filter.Equals(field, value);
    return file(type).entities.values().stream()
        .filter(e -> FilterMatcher.matches(equals, e))
        .map(StoredEntity::id)
        .findFirst();
  }
}
