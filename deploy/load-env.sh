#!/usr/bin/env bash

load_env() {
    local file="$1" line key value
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
        key="${line%%=*}"
        value="${line#*=}"
        if [[ ! "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]]; then
            printf 'Invalid environment variable name in %s\n' "$file" >&2
            return 1
        fi
        if [[ "$value" == \"*\" ]]; then
            value="${value:1:${#value}-2}"
            value="${value//\\\"/\"}"
            value="${value//\\\\/\\}"
        elif [[ "$value" == \'*\' ]]; then
            value="${value:1:${#value}-2}"
            value="${value//\\\'/\'}"
            value="${value//\\\\/\\}"
        fi
        printf -v "$key" '%s' "$value"
    done < "$file"
}
