/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.demo;

/** The code of the jar that the demo signs. */
public final class Hello {

  private Hello() {}

  public static void main(String[] args) {
    System.out.println("Hello from a signed jar!");
  }
}
