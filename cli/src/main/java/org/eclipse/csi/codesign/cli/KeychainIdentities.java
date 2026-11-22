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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Looks up signing identities (certificate + private key) in the keychains, as {@code security
 * find-identity} lists them. The SignPath CryptoTokenKit publishes its certificates there.
 *
 * <p>The lookup includes identities that macOS does not trust, which {@code security find-identity
 * -v} hides: the self-signed certificates of SignPath test signing policies are such identities,
 * and {@code codesign} signs with them.
 */
final class KeychainIdentities {

  /**
   * The problem {@code security} reports for a certificate that does not chain to a trusted root.
   */
  static final String NOT_TRUSTED = "CSSMERR_TP_NOT_TRUSTED";

  /**
   * A line of {@code security find-identity}: {@code 1) <SHA-1> "<name>"}, followed by the problem
   * in parentheses for invalid identities, e.g. {@code (CSSMERR_TP_CERT_EXPIRED)}.
   */
  private static final Pattern IDENTITY_LINE =
      Pattern.compile(
          "^\\s*\\d+\\)\\s+([0-9A-Fa-f]{40})\\s+\"(.*)\"(?:\\s+\\(([A-Za-z0-9_]+)\\))?\\s*$",
          Pattern.MULTILINE);

  private static final Pattern SHA1 = Pattern.compile("[0-9A-Fa-f]{40}");

  private static final Duration FIRST_PAUSE = Duration.ofSeconds(1);
  private static final Duration MAX_PAUSE = Duration.ofSeconds(5);

  /**
   * A signing identity.
   *
   * @param sha1 SHA-1 hash of the certificate, in upper case
   * @param name name of the identity, e.g. {@code Developer ID Application: Example (TEAMID)}
   * @param problem why macOS considers the identity invalid, e.g. {@code CSSMERR_TP_NOT_TRUSTED};
   *     {@code null} for a valid identity
   */
  record Identity(String sha1, String name, String problem) {

    /** A valid identity. */
    Identity(String sha1, String name) {
      this(sha1, name, null);
    }

    /** Whether macOS considers the identity valid. */
    boolean valid() {
      return problem == null;
    }

    /** Whether the only problem of the identity is that its certificate is not trusted. */
    boolean untrusted() {
      return NOT_TRUSTED.equals(problem);
    }

    /** Whether {@code codesign} can sign with the identity: it is valid or only untrusted. */
    boolean usable() {
      return valid() || untrusted();
    }

    String describe() {
      return "\"" + name + "\" (" + sha1 + (valid() ? "" : ", " + problem) + ")";
    }
  }

  /** Waits between two lookups. Tests replace it to avoid sleeping. */
  @FunctionalInterface
  interface Pauser {
    void pause(Duration duration) throws InterruptedException;
  }

  private KeychainIdentities() {}

  /**
   * Parses the output of {@code security find-identity}. A valid identity is listed twice, under
   * "Matching identities" and under "Valid identities only": each identity is returned once.
   */
  static List<Identity> parse(String findIdentityOutput) {
    Map<String, Identity> identities = new LinkedHashMap<>();
    Matcher matcher = IDENTITY_LINE.matcher(findIdentityOutput);
    while (matcher.find()) {
      Identity identity =
          new Identity(
              matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2), matcher.group(3));
      identities.merge(identity.sha1(), identity, (first, next) -> first.valid() ? first : next);
    }
    return new ArrayList<>(identities.values());
  }

  /**
   * Whether {@code identity} is selected by {@code wanted}, the way {@code codesign -s} selects
   * identities: by SHA-1 hash when {@code wanted} has 40 hexadecimal digits, by a substring of the
   * name otherwise.
   */
  static boolean matches(Identity identity, String wanted) {
    if (SHA1.matcher(wanted).matches()) {
      return identity.sha1().equalsIgnoreCase(wanted);
    }
    return identity.name().contains(wanted);
  }

  /**
   * The identities {@code wanted} selects among {@code identities}, as {@code codesign -s} does: an
   * exact name match is preferred over partial matches.
   */
  static List<Identity> select(List<Identity> identities, String wanted) {
    List<Identity> matching =
        identities.stream().filter(identity -> matches(identity, wanted)).toList();
    List<Identity> exact =
        matching.stream().filter(identity -> identity.name().equals(wanted)).toList();
    return exact.isEmpty() ? matching : exact;
  }

  /** Lists the signing identities of the keychain search list, including invalid ones. */
  static List<Identity> list(ProcessRunner runner)
      throws IOException, InterruptedException, ToolException {
    return parse(runner.runChecked(List.of("security", "find-identity")));
  }

  /**
   * Waits until every identity in {@code wanted} is available, polling with a growing pause (1s up
   * to 5s). An identity counts as available when exactly one usable identity (valid, or only
   * untrusted) matches it.
   *
   * @param timeout how long to wait; {@link Duration#ZERO} checks once
   * @return the identity each element of {@code wanted} selects, in the order of {@code wanted}
   * @throws ToolException when an identity is still missing after {@code timeout}, or when one
   *     matches several usable identities (which {@code codesign} rejects as ambiguous)
   */
  static Map<String, Identity> await(
      ProcessRunner runner,
      Pauser pauser,
      Collection<String> wanted,
      Duration timeout,
      Consumer<String> log)
      throws IOException, InterruptedException, ToolException {
    Duration waited = Duration.ZERO;
    Duration pause = FIRST_PAUSE;
    while (true) {
      Lookup lookup = Lookup.of(list(runner), wanted);
      if (lookup.missing().isEmpty()) {
        return lookup.found();
      }
      if (waited.compareTo(timeout) >= 0) {
        throw lookup.notFound(timeout);
      }
      Duration next = min(pause, timeout.minus(waited));
      log.accept("Waiting for signing identity: " + String.join(", ", lookup.missing()));
      pauser.pause(next);
      waited = waited.plus(next);
      pause = min(pause.multipliedBy(2), MAX_PAUSE);
    }
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) < 0 ? a : b;
  }

  /**
   * The result of looking up the wanted identities among the available ones.
   *
   * @param available the identities of the keychain
   * @param found the usable identity each wanted name selects
   * @param missing the wanted names that select no usable identity
   * @param unusable the identities the missing names select, which cannot sign
   */
  private record Lookup(
      List<Identity> available,
      Map<String, Identity> found,
      List<String> missing,
      List<Identity> unusable) {

    static Lookup of(List<Identity> available, Collection<String> wanted) throws ToolException {
      Lookup lookup =
          new Lookup(available, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
      for (String identity : wanted) {
        List<Identity> matching = select(available, identity);
        List<Identity> usable = matching.stream().filter(Identity::usable).toList();
        if (usable.size() > 1) {
          throw new ToolException(
              "Identity '"
                  + identity
                  + "' matches several signing identities: "
                  + describe(usable)
                  + ". Give the full name or the SHA-1 hash of the certificate.");
        }
        if (usable.isEmpty()) {
          lookup.missing().add(identity);
          lookup.unusable().addAll(matching);
        } else {
          lookup.found().put(identity, usable.get(0));
        }
      }
      return lookup;
    }

    ToolException notFound(Duration timeout) {
      return new ToolException(
          "Signing identity "
              + missing.stream().map(m -> "'" + m + "'").collect(Collectors.joining(", "))
              + " not found in the keychain"
              + (timeout.isZero() ? "" : " after " + timeout.toSeconds() + "s")
              + "."
              + (unusable.isEmpty()
                  ? ""
                  : " Matching identities that cannot sign: " + describe(unusable) + ".")
              + " Available identities: "
              + (available.isEmpty() ? "none" : describe(available))
              + ".");
    }
  }

  private static String describe(List<Identity> identities) {
    return identities.stream().map(Identity::describe).collect(Collectors.joining(", "));
  }
}
