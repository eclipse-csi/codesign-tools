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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exception carrying HTTP status code and response body from a failed SignPath API call. */
public class CodesignException extends Exception {

  private final int httpStatus;
  private final String responseBody;

  /**
   * Constructs a new exception with the HTTP status code and response body from a failed API call.
   *
   * <p>When the body is an RFC 9457 problem details document (as returned by the SignPath API), the
   * exception message is built from its {@code title}, {@code detail}, validation {@code errors}
   * and {@code traceId}; otherwise the raw body is used. {@link #responseBody()} always returns the
   * raw body.
   *
   * @param httpStatus the HTTP status code returned by the SignPath API
   * @param responseBody the response body returned by the SignPath API
   */
  public CodesignException(int httpStatus, String responseBody) {
    super(formatMessage(httpStatus, responseBody));
    this.httpStatus = httpStatus;
    this.responseBody = responseBody;
  }

  private static String formatMessage(int httpStatus, String responseBody) {
    String prefix = "SignPath API error (HTTP " + httpStatus + ")";
    if (responseBody == null || responseBody.isBlank()) {
      return prefix;
    }
    String problem = describeProblemDetails(responseBody);
    return prefix + ": " + (problem != null ? problem : responseBody);
  }

  /**
   * Renders a problem details JSON document as a single human-readable line.
   *
   * @return the rendered message, or {@code null} if the body is not a problem details document
   */
  private static String describeProblemDetails(String body) {
    JsonObject json;
    try {
      JsonElement element = JsonParser.parseString(body);
      if (!element.isJsonObject()) {
        return null;
      }
      json = element.getAsJsonObject();
    } catch (JsonParseException e) {
      return null;
    }

    List<String> sentences = new ArrayList<>();
    String title = stringMember(json, "title");
    String detail = stringMember(json, "detail");
    if (title != null) {
      sentences.add(title);
    }
    if (detail != null && !detail.equals(title)) {
      sentences.add(detail);
    }
    if (json.get("errors") instanceof JsonObject errors) {
      for (Map.Entry<String, JsonElement> entry : errors.entrySet()) {
        List<String> messages = new ArrayList<>();
        if (entry.getValue().isJsonArray()) {
          entry.getValue().getAsJsonArray().forEach(m -> messages.add(asText(m)));
        } else {
          messages.add(asText(entry.getValue()));
        }
        String joined = String.join(" ", messages);
        sentences.add(entry.getKey().isEmpty() ? joined : entry.getKey() + ": " + joined);
      }
    }
    if (sentences.isEmpty()) {
      return null;
    }

    StringBuilder message = new StringBuilder();
    for (String sentence : sentences) {
      if (!message.isEmpty()) {
        char last = message.charAt(message.length() - 1);
        message.append(last == '.' || last == '!' || last == '?' ? " " : ". ");
      }
      message.append(sentence.strip());
    }
    String traceId = stringMember(json, "traceId");
    if (traceId != null) {
      message.append(" (trace ID: ").append(traceId).append(')');
    }
    return message.toString();
  }

  private static String stringMember(JsonObject json, String name) {
    JsonElement value = json.get(name);
    if (value == null || !value.isJsonPrimitive()) {
      return null;
    }
    String text = value.getAsString();
    return text.isBlank() ? null : text;
  }

  private static String asText(JsonElement element) {
    return element.isJsonPrimitive() ? element.getAsString() : element.toString();
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public CodesignException(String message, Throwable cause) {
    super(message, cause);
    this.httpStatus = -1;
    this.responseBody = null;
  }

  /**
   * Returns the HTTP status code from the failed SignPath API call, or {@code -1} if not
   * applicable.
   *
   * @return the HTTP status code
   */
  public int httpStatus() {
    return httpStatus;
  }

  /**
   * Returns the response body from the failed SignPath API call, or {@code null} if not applicable.
   *
   * @return the response body
   */
  public String responseBody() {
    return responseBody;
  }
}
