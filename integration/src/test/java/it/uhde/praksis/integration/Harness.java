// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A Praksis home directory, and real daemon and CLI processes run from the runtime image. */
final class Harness implements AutoCloseable {
  /** The jlink image from praksis-dist: the daemon and the CLI run exactly as shipped. */
  private final Path image =
      Path.of(System.getProperty("praksis.image")).toAbsolutePath().normalize();

  private final List<Process> processes = new ArrayList<>();

  /** Short, because Unix socket paths are. */
  final Path home;

  /** A home with a ruleset and the storage modules installed from their archives. */
  Harness(String... modules) throws Exception {
    home = Files.createTempDirectory("pk");
    assertThat(cli("modules", "lock").exit()).isZero();
    // the storage modules are not in the image: they come from their archives, as for a person
    for (String module : modules) {
      CliResult installed = cli("modules", "install", archive(module).toString());
      assertThat(installed.exit()).as(installed.output()).isZero();
    }
    Files.createDirectories(home.resolve("ruleset/rules"));
    Files.writeString(
        home.resolve("ruleset/ruleset.toml"), "format_version = 1\nname = \"storage\"\n");
    Files.writeString(home.resolve("policy.toml"), "format_version = 1\n");
  }

  /** The archive that the build of a module made. */
  static Path archive(String module) {
    return Path.of(System.getProperty("praksis.modules"))
        .resolve("praksis-module-" + module + "/target/" + module + ".praksis-module")
        .toAbsolutePath()
        .normalize();
  }

  Path socket() {
    return home.resolve("praksis.sock");
  }

  void write(String relative, String text) throws IOException {
    Path file = home.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private List<String> java(String module, String... args) {
    List<String> command =
        new ArrayList<>(List.of(image.resolve("bin/java").toString(), "--module", module));
    command.addAll(List.of(args));
    return command;
  }

  /** Starts the daemon itself (not through the CLI) and waits until it answers. */
  Process startDaemon() throws Exception {
    List<String> command =
        java("it.uhde.praksis.daemon/it.uhde.praksis.daemon.Main", "--home", home.toString());
    command.add(1, "--enable-native-access=org.xerial.sqlitejdbc");
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("daemon.log").toFile()));
    Process p = builder.start();
    processes.add(p);
    await(
        "the daemon to answer",
        () -> {
          if (!p.isAlive()) {
            throw new AssertionError(
                "the daemon exited with " + p.exitValue() + ":\n" + daemonLog());
          }
          return status() != null;
        });
    return p;
  }

  /** Kills with SIGKILL, so nothing gets a chance to clean up, and waits for the exit. */
  void kill(Process p) throws InterruptedException {
    p.destroyForcibly();
    p.waitFor();
  }

  /** Runs the CLI and returns its exit code and everything it printed. */
  record CliResult(int exit, String output) {}

  CliResult cli(String... args) throws Exception {
    List<String> command =
        java("it.uhde.praksis.cli/it.uhde.praksis.cli.Praksis", args[0], "--home", home.toString());
    command.addAll(List.of(args).subList(1, args.length));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Process p = builder.start();
    p.getOutputStream().close();
    String output = new String(p.getInputStream().readAllBytes());
    return new CliResult(p.waitFor(), output);
  }

  /** The daemon's status, or null if it does not answer. */
  Response.StatusReport status() {
    try (Client client = Client.connect(socket())) {
      return client.send(new Request.Status()) instanceof Response.StatusReport s ? s : null;
    } catch (IOException e) {
      return null;
    }
  }

  Response ask(Request request) throws IOException {
    try (Client client = Client.connect(socket())) {
      return client.send(request);
    }
  }

  /** Asks the daemon to stop and waits until the process is gone. */
  void stop(Process p) throws Exception {
    assertThat(ask(new Request.Stop())).isEqualTo(new Response.Stopped());
    if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      new ProcessBuilder("kill", "-3", String.valueOf(p.pid())).start().waitFor();
      Thread.sleep(1000);
      throw new AssertionError(
          "the daemon did not exit within 30 seconds; its threads:\n" + daemonLog());
    }
  }

  String daemonLog() {
    try {
      return Files.readString(home.resolve("daemon.log"));
    } catch (IOException e) {
      return "(no daemon.log)";
    }
  }

  /** A condition that may fail with an exception; the exception fails the test. */
  @FunctionalInterface
  interface Condition {
    boolean holds() throws Exception;
  }

  static void await(String what, Condition condition) throws Exception {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.holds()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  @Override
  public void close() throws IOException {
    Response.StatusReport s = status();
    if (s != null) {
      ProcessHandle.of(s.pid()).ifPresent(ProcessHandle::destroyForcibly);
    }
    processes.forEach(Process::destroyForcibly);
    try (Stream<Path> files = Files.walk(home)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    }
  }
}
