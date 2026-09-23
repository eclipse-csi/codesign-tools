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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root command for the {@code codesign} CLI.
 *
 * <p>Dispatches to subcommands. When called with no subcommand, prints usage to stderr and exits
 * with code 0.
 */
@Command(
    name = "codesign",
    description = "Sign artifacts via the SignPath REST API.",
    subcommands = {SignCommand.class, CommandLine.HelpCommand.class},
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    sortOptions = false,
    usageHelpAutoWidth = true)
public class CodesignCli implements Runnable {

  /**
   * Entry point for the {@code codesign} CLI.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    int exitCode =
        new CommandLine(new CodesignCli())
            .setExecutionExceptionHandler(new PrintExceptionMessageHandler())
            .execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    // Print usage when called without a subcommand
    new CommandLine(this).usage(System.err);
  }
}
