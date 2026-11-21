// Copyright (c) 2026 Eclipse Foundation AISBL and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0/
//
// SPDX-License-Identifier: EPL-2.0

// Deliberately dependency-free: it only copies two environment variables, which the runner sets
// for JavaScript actions but not for run steps, into the outputs of this step.
'use strict';

const fs = require('node:fs');
const os = require('node:os');

const resultsUrl = process.env.ACTIONS_RESULTS_URL;
const runtimeToken = process.env.ACTIONS_RUNTIME_TOKEN;
const outputFile = process.env.GITHUB_OUTPUT;

if (!resultsUrl || !runtimeToken || !outputFile) {
  console.log(
    '::error::ACTIONS_RESULTS_URL, ACTIONS_RUNTIME_TOKEN or GITHUB_OUTPUT is not set; ' +
      'this action must run in a GitHub Actions job.',
  );
  process.exit(1);
}
if (/[\r\n]/.test(resultsUrl) || /[\r\n]/.test(runtimeToken)) {
  console.log('::error::Unexpected line break in ACTIONS_RESULTS_URL or ACTIONS_RUNTIME_TOKEN.');
  process.exit(1);
}

console.log(`::add-mask::${runtimeToken}`);
fs.appendFileSync(
  outputFile,
  `actions-results-url=${resultsUrl}${os.EOL}actions-runtime-token=${runtimeToken}${os.EOL}`,
);
