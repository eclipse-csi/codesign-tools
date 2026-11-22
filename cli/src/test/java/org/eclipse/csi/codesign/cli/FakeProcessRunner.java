/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Records the commands it is asked to run and answers them from rules; a command no rule matches
 * succeeds with no output. The rule added last wins.
 */
final class FakeProcessRunner implements ProcessRunner {

  /** A recorded command. */
  record Call(List<String> command, Map<String, String> environment) {}

  /** Answers a command. */
  @FunctionalInterface
  interface Responder {
    Result respond(List<String> command) throws IOException;
  }

  private record Rule(List<String> prefix, Responder responder) {}

  private final List<Call> calls = new ArrayList<>();
  private final List<Rule> rules = new ArrayList<>();

  /** Answers the commands that start with {@code prefix}. */
  FakeProcessRunner on(List<String> prefix, Responder responder) {
    rules.add(new Rule(prefix, responder));
    return this;
  }

  /** Answers the commands that start with {@code prefix} with a fixed result. */
  FakeProcessRunner on(List<String> prefix, int exitCode, String output) {
    return on(prefix, command -> new Result(exitCode, output));
  }

  @Override
  public Result run(List<String> command, Map<String, String> environment) throws IOException {
    calls.add(new Call(List.copyOf(command), Map.copyOf(environment)));
    for (int i = rules.size() - 1; i >= 0; i--) {
      Rule rule = rules.get(i);
      if (startsWith(command, rule.prefix())) {
        return rule.responder().respond(command);
      }
    }
    return new Result(0, "");
  }

  List<Call> calls() {
    return calls;
  }

  List<List<String>> commands() {
    return calls.stream().map(Call::command).toList();
  }

  /** The recorded commands that start with {@code prefix}. */
  List<List<String>> commands(String... prefix) {
    return commands().stream()
        .filter(command -> startsWith(command, Arrays.asList(prefix)))
        .toList();
  }

  static boolean startsWith(List<String> command, List<String> prefix) {
    return command.size() >= prefix.size() && command.subList(0, prefix.size()).equals(prefix);
  }
}
