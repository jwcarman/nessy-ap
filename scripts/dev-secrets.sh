#!/usr/bin/env bash
#
# Copyright © 2026 James Carman
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Writes fresh random secrets for a development stack to .env in the repository root.
# The desk loads .env at startup (spring.config.import), so nothing needs to be sourced.
# It never overwrites an existing .env: that file holds the keys your stored data is
# encrypted and signed under. Pass --force to replace it, and then recreate the databases.
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env && "${1:-}" != "--force" ]]; then
  echo ".env exists; keeping it. Use --force to replace it (then recreate the databases)."
  exit 0
fi

key() { openssl rand -base64 "$1"; }

umask 077
cat > .env <<SECRETS
# Development secrets, generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by scripts/dev-secrets.sh.
# Never commit this file. Each value is random; production supplies its own.
AP_REPLY_KEY=$(key 32)
OCCLUDE_KEK=$(key 32)
OCCLUDE_ROOT=$(key 48)
SECRETS
echo "Wrote .env with new development secrets."
