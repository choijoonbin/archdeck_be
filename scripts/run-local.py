#!/usr/bin/env python3
"""Launch one local service without echoing credentials or changing other projects."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def read_env(path):
    result = {}
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                result[key.strip()] = value.strip().strip('\"\'')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('service', choices=['backend', 'agent', 'frontend'])
    parser.add_argument('--dwp-env', type=Path, help='Explicit DWP Azure config file; only provider variables are injected')
    args = parser.parse_args()
    env = {**read_env(ROOT / '.env'), **os.environ}
    if args.service == 'agent':
        folder = ROOT.parent / 'ArchDeck_Agent'
        env.update(read_env(folder / '.env'))
        env['ARCHDECK_AGENT_SERVICE_TOKEN'] = env.get('ARCHDECK_AGENT_SERVICE_TOKEN', '')
        if args.dwp_env:
            values = read_env(args.dwp_env)
            approved = {'DWP_MODEL_PROVIDER', 'AZURE_OPENAI_ENDPOINT', 'AZURE_OPENAI_API_KEY', 'DWP_OPENAI_MODEL', 'DWP_OPENAI_BASE_URL', 'DWP_OPENAI_TIMEOUT_SECONDS', 'DWP_OPENAI_MAX_OUTPUT_TOKENS'}
            env.update({key: value for key, value in values.items() if key in approved})
        command = [str(folder / '.venv/bin/uvicorn'), 'archdeck_agent.main:app', '--host', '127.0.0.1', '--port', '4010']
    elif args.service == 'frontend':
        folder = ROOT.parent / 'ArchDeck_FE'
        command = ['npm', 'run', 'dev', '--', '--host', '127.0.0.1']
    else:
        folder = ROOT
        command = ['./mvnw', 'spring-boot:run']
    try:
        return subprocess.call(command, cwd=folder, env=env)
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    sys.exit(main())
