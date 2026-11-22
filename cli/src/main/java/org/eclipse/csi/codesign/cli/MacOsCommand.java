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

import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Groups the macOS commands: {@code macos sign} signs apps, binaries, disk images and installer
 * packages with the SignPath CryptoTokenKit; {@code macos notarize} notarizes and staples them.
 */
@Command(
    name = "macos",
    description = {
      "Sign and notarize macOS apps, binaries, disk images and installer packages.",
      "Runs on macOS only: it drives codesign, productsign and notarytool."
    },
    subcommands = {
      MacOsSignCommand.class,
      MacOsNotarizeCommand.class,
      CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true,
    sortOptions = false,
    usageHelpAutoWidth = true)
class MacOsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getErr());
  }

  /** Whether this process runs on macOS. */
  static boolean runningOnMacOs() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
  }
}
