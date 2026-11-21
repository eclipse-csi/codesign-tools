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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Uploads a file, as is, as an artifact of the running GitHub Actions workflow job.
 *
 * <p>This mirrors what {@code actions/upload-artifact} does through the {@code @actions/artifact}
 * toolkit package: the artifact is created and finalized through the Actions results service (Twirp
 * {@code ArtifactService}), and its content is uploaded to the signed blob storage URL that {@code
 * CreateArtifact} returns. The workflow run and job backend IDs come from the {@code scp} claim of
 * the Actions runtime token.
 *
 * <p>An artifact holding a zip file is what {@code upload-artifact} produces by default; any other
 * file is what it produces with {@code archive: false}, in which case the artifact must be named
 * after the file.
 *
 * <p>The signed upload URL embeds a credential: it is never logged nor put in exception messages.
 */
class GitHubArtifactUploader {

  private static final String ARTIFACT_SERVICE =
      "/twirp/github.actions.results.api.v1.ArtifactService/";
  private static final MediaType JSON = MediaType.get("application/json");

  /** Characters {@code @actions/artifact} rejects in artifact names. */
  private static final String INVALID_NAME_CHARACTERS = "\":<>|*?\r\n\\/";

  private static final String RESULTS_SCOPE = "Actions.Results";

  /** Artifact version written by {@code @actions/artifact} 2.x and later. */
  private static final int ARTIFACT_VERSION = 7;

  private final OkHttpClient httpClient;

  GitHubArtifactUploader(OkHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /**
   * Uploads {@code file} as a workflow artifact.
   *
   * @param context the job to attach the artifact to
   * @param name the artifact name, unique within the workflow run
   * @param file the file to upload
   * @param mimeType the media type of the file, {@code application/zip} for a zipped artifact
   * @param expiresAt when GitHub may delete the artifact
   * @return the artifact ID, as accepted by the GitHub REST API and the SignPath connector
   * @throws CodesignException if the results service or the blob storage rejects a request
   * @throws IOException on transport-level or I/O failures
   */
  long upload(
      GitHubActionsContext context, String name, Path file, String mimeType, Instant expiresAt)
      throws CodesignException, IOException {
    for (char c : name.toCharArray()) {
      if (INVALID_NAME_CHARACTERS.indexOf(c) >= 0) {
        throw new CodesignException(
            "'"
                + name
                + "' is not a valid GitHub artifact name: it must not contain any of"
                + " \" : < > | * ? \\ / or line breaks",
            null);
      }
    }
    MediaType mediaType = MediaType.get(mimeType);
    String[] backendIds = backendIds(context.runtimeToken());
    String serviceUrl = resultsOrigin(context.resultsUrl()) + ARTIFACT_SERVICE;

    JsonObject create = new JsonObject();
    create.addProperty("workflow_run_backend_id", backendIds[0]);
    create.addProperty("workflow_job_run_backend_id", backendIds[1]);
    create.addProperty("name", name);
    create.addProperty("version", ARTIFACT_VERSION);
    create.addProperty("mime_type", mimeType);
    create.addProperty("expires_at", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
    JsonObject created;
    try {
      created = callTwirp(serviceUrl, "CreateArtifact", context, create);
    } catch (CodesignException e) {
      if (e.getMessage().contains("HTTP 409") || e.getMessage().contains("(already_exists)")) {
        throw new CodesignException(
            e.getMessage()
                + ". This workflow run already has an artifact named '"
                + name
                + "'. A single file is uploaded as an artifact named after it, so each file name"
                + " can only be signed this way once per workflow run",
            e);
      }
      throw e;
    }
    String uploadUrl = protoMember(created, "signed_upload_url", "signedUploadUrl");
    if (!isOk(created) || uploadUrl == null) {
      throw new CodesignException(
          "GitHub artifact upload failed: CreateArtifact did not return an upload URL", null);
    }

    uploadContent(uploadUrl, file, mediaType);

    JsonObject finalize = new JsonObject();
    finalize.addProperty("workflow_run_backend_id", backendIds[0]);
    finalize.addProperty("workflow_job_run_backend_id", backendIds[1]);
    finalize.addProperty("name", name);
    finalize.addProperty("size", Long.toString(Files.size(file)));
    finalize.addProperty("hash", "sha256:" + sha256Hex(file));
    JsonObject finalized = callTwirp(serviceUrl, "FinalizeArtifact", context, finalize);
    String artifactId = protoMember(finalized, "artifact_id", "artifactId");
    if (!isOk(finalized) || artifactId == null) {
      throw new CodesignException(
          "GitHub artifact upload failed: FinalizeArtifact did not return an artifact ID", null);
    }
    try {
      return Long.parseLong(artifactId);
    } catch (NumberFormatException e) {
      throw new CodesignException(
          "GitHub artifact upload failed: unexpected artifact ID '" + artifactId + "'", e);
    }
  }

  private JsonObject callTwirp(
      String serviceUrl, String method, GitHubActionsContext context, JsonObject body)
      throws CodesignException, IOException {
    Request request =
        new Request.Builder()
            .url(serviceUrl + method)
            .header("Authorization", "Bearer " + context.runtimeToken())
            .post(RequestBody.create(body.toString(), JSON))
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      String responseBody = readBody(response);
      if (!response.isSuccessful()) {
        throw new CodesignException(
            "GitHub artifact upload failed: "
                + method
                + " returned HTTP "
                + response.code()
                + describeTwirpError(responseBody),
            null);
      }
      try {
        JsonElement json = JsonParser.parseString(responseBody);
        if (json.isJsonObject()) {
          return json.getAsJsonObject();
        }
      } catch (JsonParseException e) {
        // reported below
      }
      throw new CodesignException(
          "GitHub artifact upload failed: " + method + " returned an unexpected response", null);
    }
  }

  private void uploadContent(String uploadUrl, Path file, MediaType mediaType)
      throws CodesignException, IOException {
    Request request =
        new Request.Builder()
            .url(uploadUrl)
            .header("x-ms-blob-type", "BlockBlob")
            .put(RequestBody.create(file.toFile(), mediaType))
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        // Azure answers with an XML error document; its <Code> is enough to diagnose, and the
        // rest of the body may echo the signed URL.
        throw new CodesignException(
            "GitHub artifact upload failed: the artifact storage returned HTTP "
                + response.code()
                + describeBlobError(readBody(response)),
            null);
      }
    } catch (IOException e) {
      // OkHttp redacts URLs in its own messages, but be strict: the URL holds a credential.
      throw new IOException(
          "GitHub artifact upload failed while sending the content: " + redact(e.getMessage()), e);
    }
  }

  /**
   * Extracts the workflow run and job backend IDs from the {@code scp} claim of the runtime token,
   * e.g. {@code "Actions.ExampleScope Actions.Results:<run backend id>:<job backend id>"}.
   */
  static String[] backendIds(String runtimeToken) throws CodesignException {
    CodesignException invalid =
        new CodesignException(
            "The GitHub Actions runtime token (ACTIONS_RUNTIME_TOKEN) is invalid or lacks the "
                + RESULTS_SCOPE
                + " scope",
            null);
    String[] parts = runtimeToken.split("\\.");
    if (parts.length < 2) {
      throw invalid;
    }
    JsonObject claims;
    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      JsonElement json = JsonParser.parseString(payload);
      if (!json.isJsonObject()) {
        throw invalid;
      }
      claims = json.getAsJsonObject();
    } catch (IllegalArgumentException | JsonParseException e) {
      throw invalid;
    }
    String scopes = stringMember(claims, "scp");
    if (scopes == null) {
      throw invalid;
    }
    for (String scope : scopes.split(" ")) {
      String[] scopeParts = scope.split(":");
      if (!RESULTS_SCOPE.equals(scopeParts[0])) {
        continue;
      }
      if (scopeParts.length != 3 || scopeParts[1].isEmpty() || scopeParts[2].isEmpty()) {
        throw invalid;
      }
      return new String[] {scopeParts[1], scopeParts[2]};
    }
    throw invalid;
  }

  private static String resultsOrigin(String resultsUrl) throws CodesignException {
    URI uri;
    try {
      uri = URI.create(resultsUrl);
    } catch (IllegalArgumentException e) {
      throw new CodesignException("Invalid ACTIONS_RESULTS_URL: " + resultsUrl, e);
    }
    String host = uri.getHost();
    boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
    if (host == null
        || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
      throw new CodesignException("ACTIONS_RESULTS_URL must use HTTPS. Got: " + resultsUrl, null);
    }
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static String describeTwirpError(String body) {
    try {
      JsonElement json = JsonParser.parseString(body);
      if (json.isJsonObject()) {
        String code = stringMember(json.getAsJsonObject(), "code");
        String msg = stringMember(json.getAsJsonObject(), "msg");
        if (msg != null) {
          return ": " + msg + (code != null ? " (" + code + ")" : "");
        }
      }
    } catch (JsonParseException e) {
      // not a Twirp error document
    }
    return "";
  }

  private static String describeBlobError(String body) {
    int start = body.indexOf("<Code>");
    int end = body.indexOf("</Code>");
    if (start >= 0 && end > start) {
      return " (" + body.substring(start + "<Code>".length(), end) + ")";
    }
    return "";
  }

  private static String redact(String message) {
    return message == null ? null : message.replaceAll("\\?\\S*", "?<redacted>");
  }

  /** Reads a response field, which proto JSON may spell with its proto or its JSON name. */
  private static String protoMember(JsonObject json, String protoName, String jsonName) {
    String value = stringMember(json, protoName);
    return value != null ? value : stringMember(json, jsonName);
  }

  private static boolean isOk(JsonObject response) {
    JsonElement ok = response.get("ok");
    return ok != null && ok.isJsonPrimitive() && ok.getAsBoolean();
  }

  private static String stringMember(JsonObject json, String name) {
    JsonElement value = json.get(name);
    if (value == null || !value.isJsonPrimitive()) {
      return null;
    }
    String text = value.getAsString();
    return text.isBlank() ? null : text;
  }

  private static String readBody(Response response) throws IOException {
    ResponseBody body = response.body();
    return body != null ? body.string() : "";
  }

  static String sha256Hex(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is guaranteed by the JDK spec", e);
    }
    try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
