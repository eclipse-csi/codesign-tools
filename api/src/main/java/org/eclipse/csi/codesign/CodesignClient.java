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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** HTTP client for all SignPath API operations: submit, poll status, and download. */
public class CodesignClient implements AutoCloseable {

  private static final Gson GSON = new Gson();
  private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
  private static final MediaType JSON = MediaType.get("application/json");
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String organizationId;
  private final String apiToken;

  /**
   * Configuration for the codesign client.
   *
   * @param baseUrl the SignPath API base URL
   * @param organizationId the SignPath organization identifier
   * @param apiToken the Bearer API token used for authentication
   * @param connectTimeout the HTTP connection timeout
   * @param httpTimeout the HTTP read/write timeout
   * @param retryTimeout the maximum time window for retry attempts
   * @param retryInterval the delay between retry attempts
   * @param maxRetries the maximum number of retry attempts
   */
  public record Config(
      String baseUrl,
      String organizationId,
      String apiToken,
      Duration connectTimeout,
      Duration httpTimeout,
      Duration retryTimeout,
      Duration retryInterval,
      int maxRetries) {}

  /**
   * Creates a new client from the given configuration.
   *
   * @param config the client configuration
   */
  public CodesignClient(Config config) {
    requireHttps("baseUrl", config.baseUrl());
    this.baseUrl = config.baseUrl();
    this.organizationId = config.organizationId();
    this.apiToken = config.apiToken();

    RetryInterceptor retryInterceptor =
        new RetryInterceptor(config.retryTimeout(), config.retryInterval(), config.maxRetries());

    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .addInterceptor(retryInterceptor)
            .build();
  }

  // Visible for testing
  CodesignClient(OkHttpClient httpClient, String baseUrl, String organizationId, String apiToken) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
    this.organizationId = organizationId;
    this.apiToken = apiToken;
  }

  /**
   * Submits an artifact for signing.
   *
   * @param projectId the SignPath project slug
   * @param signingPolicy the signing policy slug
   * @param artifactConfiguration optional artifact configuration slug, may be {@code null}
   * @param description optional signing request description, may be {@code null}
   * @param parameters optional custom key/value parameters, may be {@code null}
   * @param artifactPath path to the artifact file to sign
   * @return a {@link SigningRequest} containing the status polling URL
   * @throws CodesignException if the API returns a non-201 response
   * @throws IOException on transport-level failures
   */
  public SigningRequest submit(
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      String description,
      Map<String, String> parameters,
      Path artifactPath)
      throws CodesignException, IOException {
    String url = baseUrl + "/v1/" + organizationId + "/SigningRequests";

    MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ProjectSlug", projectId)
            .addFormDataPart("SigningPolicySlug", signingPolicy)
            .addFormDataPart(
                "Artifact",
                artifactPath.getFileName().toString(),
                RequestBody.create(artifactPath.toFile(), OCTET_STREAM));

    if (artifactConfiguration != null) {
      bodyBuilder.addFormDataPart("ArtifactConfigurationSlug", artifactConfiguration);
    }
    if (description != null) {
      bodyBuilder.addFormDataPart("Description", description);
    }
    if (parameters != null) {
      for (Map.Entry<String, String> entry : parameters.entrySet()) {
        bodyBuilder.addFormDataPart("Parameters[" + entry.getKey() + "]", entry.getValue());
      }
    }

    Request request =
        new Request.Builder()
            .url(url)
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .post(bodyBuilder.build())
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() != 201) {
        throw new CodesignException(response.code(), readBody(response));
      }
      String location = response.header("Location");
      if (location == null) {
        throw new CodesignException(response.code(), "Missing Location header in submit response");
      }
      return new SigningRequest(URI.create(location));
    }
  }

  /**
   * Submits a GitHub workflow artifact for signing through the SignPath GitHub connector.
   *
   * <p>This is the only way to satisfy signing policies with trusted build system verification:
   * SignPath downloads the artifact from GitHub and verifies the origin of the build itself. The
   * returned request is polled and downloaded through the regular API, like {@link #submit}.
   *
   * @param connectorUrl the GitHub Actions endpoint of the SignPath pipeline connector
   * @param context the GitHub Actions job the artifact belongs to
   * @param artifactId the ID of the workflow artifact to sign
   * @param projectId the SignPath project slug
   * @param signingPolicy the signing policy slug
   * @param artifactConfiguration optional artifact configuration slug, may be {@code null}
   * @param parameters optional user-defined parameters, may be {@code null}
   * @param logger receives the log messages of the connector
   * @return the submitted signing request
   * @throws CodesignException if the connector rejects the request, including policy violations
   * @throws IOException on transport-level failures
   */
  SigningRequest submitViaGitHubConnector(
      String connectorUrl,
      GitHubActionsContext context,
      long artifactId,
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      Map<String, String> parameters,
      Consumer<String> logger)
      throws CodesignException, IOException {
    requireHttps("connectorUrl", connectorUrl);
    String url =
        connectorUrl.replaceAll("/+$", "")
            + "/"
            + organizationId
            + "/SigningRequests?api-version=1.0";

    JsonObject payload = new JsonObject();
    payload.addProperty("artifactId", Long.toString(artifactId));
    payload.addProperty("gitHubWorkflowRunId", context.runId());
    payload.addProperty("gitHubRepository", context.repository());
    payload.addProperty("gitHubToken", context.gitHubToken());
    payload.addProperty("signPathProjectSlug", projectId);
    payload.addProperty("signPathSigningPolicySlug", signingPolicy);
    if (artifactConfiguration != null) {
      payload.addProperty("signPathArtifactConfigurationSlug", artifactConfiguration);
    }
    JsonArray parameterArray = new JsonArray();
    if (parameters != null) {
      for (Map.Entry<String, String> entry : parameters.entrySet()) {
        JsonObject parameter = new JsonObject();
        parameter.addProperty("name", entry.getKey());
        parameter.addProperty("value", entry.getValue());
        parameterArray.add(parameter);
      }
    }
    payload.add("parameters", parameterArray);

    Request request =
        new Request.Builder()
            .url(url)
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .post(RequestBody.create(payload.toString(), JSON))
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      String body = readBody(response);
      JsonObject json = parseObject(body);
      boolean clientError = response.code() >= 400 && response.code() < 500;
      if (!response.isSuccessful() && !(clientError && json != null)) {
        throw new CodesignException(response.code(), body);
      }
      if (json == null) {
        throw new CodesignException(
            response.code(), "Unexpected response from the SignPath connector: " + body);
      }

      forwardConnectorLogs(json, logger);
      List<String> problems = connectorProblems(json);
      if (!problems.isEmpty()) {
        throw new CodesignException(
            "The SignPath connector rejected the signing request: " + String.join(" ", problems),
            null);
      }
      if (!response.isSuccessful()) {
        throw new CodesignException(response.code(), body);
      }

      String signingRequestId = stringMember(json, "signingRequestId");
      if (signingRequestId == null) {
        throw new CodesignException(
            response.code(),
            "SignPath connector response has no signingRequestId. Is the connector URL right? "
                + body);
      }
      String webUrl = stringMember(json, "signingRequestUrl");
      if (webUrl != null) {
        logger.accept("Signing request: " + webUrl);
      }
      return new SigningRequest(
          URI.create(baseUrl + "/v1/" + organizationId + "/SigningRequests/" + signingRequestId));
    }
  }

  private static void forwardConnectorLogs(JsonObject json, Consumer<String> logger) {
    if (!(json.get("logs") instanceof JsonArray logs)) {
      return;
    }
    for (JsonElement log : logs) {
      if (log instanceof JsonObject entry) {
        String message = stringMember(entry, "message");
        if (message != null) {
          String level = stringMember(entry, "level");
          logger.accept("[SignPath connector] " + (level != null ? level + ": " : "") + message);
        }
      }
    }
  }

  /** Collects the connector error and policy violations (with their fix hints) of a response. */
  private static List<String> connectorProblems(JsonObject json) {
    List<String> problems = new ArrayList<>();
    String error = stringMember(json, "error");
    if (error != null) {
      problems.add(sentence(error));
    }
    if (json.get("validationResult") instanceof JsonObject validation
        && validation.get("errors") instanceof JsonArray errors) {
      for (JsonElement element : errors) {
        if (element instanceof JsonObject violation) {
          String message = stringMember(violation, "error");
          String howToFix = stringMember(violation, "howToFix");
          if (message != null) {
            problems.add(
                "Policy violation: "
                    + sentence(message)
                    + (howToFix != null ? " How to fix: " + sentence(howToFix) : ""));
          }
        }
      }
    }
    return problems;
  }

  private static String sentence(String text) {
    String stripped = text.strip();
    char last = stripped.charAt(stripped.length() - 1);
    return last == '.' || last == '!' || last == '?' ? stripped : stripped + ".";
  }

  private static JsonObject parseObject(String body) {
    try {
      JsonElement element = JsonParser.parseString(body);
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (JsonParseException e) {
      return null;
    }
  }

  private static String stringMember(JsonObject json, String name) {
    JsonElement value = json.get(name);
    if (value == null || !value.isJsonPrimitive()) {
      return null;
    }
    String text = value.getAsString();
    return text.isBlank() ? null : text;
  }

  /**
   * Polls the status of a signing request.
   *
   * @param signingRequest the signing request to poll
   * @return the current {@link SigningRequestStatus}
   * @throws CodesignException if the API returns an unsuccessful response
   * @throws IOException on transport-level failures
   */
  public SigningRequestStatus getStatus(SigningRequest signingRequest)
      throws CodesignException, IOException {
    Request request =
        new Request.Builder()
            .url(signingRequest.statusUrl().toString())
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .get()
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new CodesignException(response.code(), readBody(response));
      }
      String body = readBody(response);
      return GSON.fromJson(body, SigningRequestStatus.class);
    }
  }

  /**
   * Downloads the signed artifact to the given output path.
   *
   * @param status the final signing request status containing the artifact download link
   * @param outputPath the local path where the signed artifact will be written
   * @throws CodesignException if no signed artifact link is available or the API returns an error
   * @throws IOException on transport-level or I/O failures
   */
  public void downloadSignedArtifact(SigningRequestStatus status, Path outputPath)
      throws CodesignException, IOException {
    URI signedArtifactLink = status.signedArtifactLink();
    if (signedArtifactLink == null) {
      throw new CodesignException(-1, "No signed artifact link available");
    }

    Request request =
        new Request.Builder()
            .url(signedArtifactLink.toString())
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .get()
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new CodesignException(response.code(), readBody(response));
      }
      ResponseBody body = response.body();
      if (body == null) {
        throw new CodesignException(response.code(), "Empty response body");
      }
      try (OutputStream out = Files.newOutputStream(outputPath)) {
        body.byteStream().transferTo(out);
      }
    }
  }

  /** Releases HTTP resources held by this client. */
  @Override
  public void close() {
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
  }

  /** The underlying HTTP client, shared with {@link GitHubArtifactUploader}. */
  OkHttpClient httpClient() {
    return httpClient;
  }

  // localhost/127.0.0.1 are exempted: traffic never leaves the machine, and this allows
  // integration tests to connect to a local mock server over plain HTTP.
  private static void requireHttps(String name, String url) {
    if (url == null
        || !(url.startsWith("https://")
            || url.startsWith("http://localhost")
            || url.startsWith("http://127.0.0.1"))) {
      throw new IllegalArgumentException(name + " must use HTTPS. Got: " + url);
    }
  }

  private static String readBody(Response response) throws IOException {
    ResponseBody body = response.body();
    return body != null ? body.string() : "";
  }
}
