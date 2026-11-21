/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The GitHub Actions job a signing request is submitted from, as required by SignPath's GitHub
 * trusted build system.
 *
 * <p>SignPath signing policies with <em>trusted build system verification</em> only accept requests
 * submitted through the SignPath GitHub connector: the artifact is first stored as a workflow
 * artifact of the running job, and SignPath downloads it from GitHub together with the origin of
 * the build. This record carries what both steps need.
 *
 * @param resultsUrl the Actions results service URL ({@code ACTIONS_RESULTS_URL}), used to upload
 *     the workflow artifact
 * @param runtimeToken the Actions runtime token ({@code ACTIONS_RUNTIME_TOKEN}), used to upload the
 *     workflow artifact
 * @param runId the workflow run ID ({@code GITHUB_RUN_ID})
 * @param repository the {@code owner/name} of the repository ({@code GITHUB_REPOSITORY})
 * @param gitHubToken a GitHub token ({@code GITHUB_TOKEN}) SignPath uses to read the workflow run
 *     and download the artifact; it needs the {@code actions: read} permission
 */
public record GitHubActionsContext(
    String resultsUrl, String runtimeToken, String runId, String repository, String gitHubToken) {

  private static final String ENV_RESULTS_URL = "ACTIONS_RESULTS_URL";

  private static final String ENV_RUNTIME_TOKEN = "ACTIONS_RUNTIME_TOKEN";

  private static final String ENV_RUN_ID = "GITHUB_RUN_ID";

  private static final String ENV_REPOSITORY = "GITHUB_REPOSITORY";

  private static final String ENV_GITHUB_TOKEN = "GITHUB_TOKEN";

  /**
   * Reads the context from environment variables.
   *
   * <p>{@code GITHUB_RUN_ID} and {@code GITHUB_REPOSITORY} are set for every step of a GitHub
   * Actions job. {@code ACTIONS_RESULTS_URL} and {@code ACTIONS_RUNTIME_TOKEN} are only given to
   * JavaScript and container actions, so a {@code run:} step has to receive them from such an
   * action. {@code GITHUB_TOKEN} has to be passed explicitly, e.g. {@code GITHUB_TOKEN: ${{
   * github.token }}}.
   *
   * @param environment looks up an environment variable, returning {@code null} when unset (e.g.
   *     {@code System::getenv})
   * @return the context
   * @throws CodesignException if any of the variables is unset or blank; the message names all of
   *     them
   */
  public static GitHubActionsContext fromEnvironment(Function<String, String> environment)
      throws CodesignException {
    List<String> missing = new ArrayList<>();
    String resultsUrl = lookup(environment, ENV_RESULTS_URL, missing);
    String runtimeToken = lookup(environment, ENV_RUNTIME_TOKEN, missing);
    String runId = lookup(environment, ENV_RUN_ID, missing);
    String repository = lookup(environment, ENV_REPOSITORY, missing);
    String gitHubToken = lookup(environment, ENV_GITHUB_TOKEN, missing);
    if (!missing.isEmpty()) {
      throw new CodesignException(
          "Submitting through the SignPath GitHub connector requires a GitHub Actions job, but"
              + " these environment variables are not set: "
              + String.join(", ", missing)
              + ". "
              + ENV_RESULTS_URL
              + " and "
              + ENV_RUNTIME_TOKEN
              + " are only available to JavaScript actions: expose them to this step from such"
              + " an action. Pass "
              + ENV_GITHUB_TOKEN
              + " explicitly (e.g. GITHUB_TOKEN: ${{ github.token }}); the job needs the"
              + " 'actions: read' permission.",
          null);
    }
    return new GitHubActionsContext(resultsUrl, runtimeToken, runId, repository, gitHubToken);
  }

  private static String lookup(
      Function<String, String> environment, String name, List<String> missing) {
    String value = environment.apply(name);
    if (value == null || value.isBlank()) {
      missing.add(name);
      return null;
    }
    return value.strip();
  }

  /** Renders the context without its tokens. */
  @Override
  public String toString() {
    return "GitHubActionsContext[resultsUrl="
        + resultsUrl
        + ", runId="
        + runId
        + ", repository="
        + repository
        + "]";
  }
}
