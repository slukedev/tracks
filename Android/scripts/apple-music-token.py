#!/usr/bin/env python3
"""Prints an Apple Music developer token (ES256 JWT) for a personal Android build.

Signs with your MusicKit private key on this computer; the key never goes into the app.
Needs the `cryptography` package. Put the output in secrets.properties as
appleMusic.developerToken. Apple allows 180 days at most.
"""
import argparse
import base64
import json
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

MAX_DAYS = 180


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key", required=True, help="the AuthKey_<id>.p8 file")
    parser.add_argument("--key-id", required=True, help="the key's 10-character id")
    parser.add_argument("--team-id", required=True, help="your 10-character Team ID")
    parser.add_argument("--days", type=int, default=MAX_DAYS, help=f"lifetime, at most {MAX_DAYS}")
    args = parser.parse_args()
    if not 1 <= args.days <= MAX_DAYS:
        parser.error(f"--days must be between 1 and {MAX_DAYS}")

    with open(args.key, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    now = int(time.time())
    header = {"alg": "ES256", "kid": args.key_id}
    claims = {"iss": args.team_id, "iat": now, "exp": now + args.days * 86400}
    signing_input = f"{b64(json.dumps(header).encode())}.{b64(json.dumps(claims).encode())}"
    r, s = decode_dss_signature(key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
    print(f"{signing_input}.{b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}")


if __name__ == "__main__":
    main()
