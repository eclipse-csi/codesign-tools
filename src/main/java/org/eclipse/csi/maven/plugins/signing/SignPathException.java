/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.maven.plugins.signing;

/**
 * Exception carrying HTTP status code and response body from a failed SignPath API call.
 */
public class SignPathException extends Exception {

	private final int httpStatus;
	private final String responseBody;

	public SignPathException(int httpStatus, String responseBody) {
		super("SignPath API error (HTTP " + httpStatus + "): " + responseBody);
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
	}

	public SignPathException(String message, Throwable cause) {
		super(message, cause);
		this.httpStatus = -1;
		this.responseBody = null;
	}

	public int httpStatus() {
		return httpStatus;
	}

	public String responseBody() {
		return responseBody;
	}
}
