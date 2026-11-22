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

/**
 * An external tool (e.g. {@code codesign}, {@code notarytool}) failed, or did not produce the
 * expected result. The message is meant for the end user.
 */
class ToolException extends Exception {

  private static final long serialVersionUID = 1L;

  ToolException(String message) {
    super(message);
  }
}
