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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SigningRequestStatusTest {

  private static final String STATUS_COMPLETED = "Completed";
  private static final String STATUS_FAILED = "Failed";
  private static final String STATUS_DENIED = "Denied";
  private static final String STATUS_CANCELED = "Canceled";
  private static final String SIGNED_URL = "https://example.com/signed";
  private static final String UNSIGNED_URL = "https://example.com/unsigned";

  @Test
  void completedStatus() {
    SigningRequestStatus status =
        new SigningRequestStatus(
            STATUS_COMPLETED, "Completed", true, URI.create(SIGNED_URL), URI.create(UNSIGNED_URL));
    assertTrue(status.isCompleted());
    assertFalse(status.isFailed());
    assertFalse(status.isDenied());
    assertFalse(status.isCanceled());
    assertTrue(status.isFinalStatus());
  }

  @Test
  void failedStatus() {
    SigningRequestStatus status =
        new SigningRequestStatus(STATUS_FAILED, "ProcessingFailed", true, null, null);
    assertFalse(status.isCompleted());
    assertTrue(status.isFailed());
    assertFalse(status.isDenied());
    assertFalse(status.isCanceled());
  }

  @Test
  void deniedStatus() {
    SigningRequestStatus status =
        new SigningRequestStatus(STATUS_DENIED, "Denied", true, null, null);
    assertFalse(status.isCompleted());
    assertFalse(status.isFailed());
    assertTrue(status.isDenied());
    assertFalse(status.isCanceled());
  }

  @Test
  void canceledStatus() {
    SigningRequestStatus status =
        new SigningRequestStatus(STATUS_CANCELED, "Canceled", true, null, null);
    assertFalse(status.isCompleted());
    assertFalse(status.isFailed());
    assertFalse(status.isDenied());
    assertTrue(status.isCanceled());
  }

  @Test
  void inProgressStatus() {
    SigningRequestStatus status =
        new SigningRequestStatus("InProgress", "Processing", false, null, null);
    assertFalse(status.isCompleted());
    assertFalse(status.isFailed());
    assertFalse(status.isDenied());
    assertFalse(status.isCanceled());
    assertFalse(status.isFinalStatus());
  }

  @Test
  void fiveArgumentConstructorLeavesWebLinkUnset() {
    SigningRequestStatus status =
        new SigningRequestStatus(STATUS_COMPLETED, "Completed", true, null, null);
    assertNull(status.webLink());
  }

  @Test
  void webLinkIsExposed() {
    URI webLink = URI.create("https://app.signpath.io/Web/org/SigningRequests/id");
    SigningRequestStatus status =
        new SigningRequestStatus(STATUS_DENIED, "Denied", true, null, null, webLink);
    assertEquals(webLink, status.webLink());
  }
}
