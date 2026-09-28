from __future__ import annotations

import base64
import hashlib
import logging
import ssl
from datetime import datetime, timezone
from functools import lru_cache
from urllib.parse import urlparse
from urllib.request import urlopen

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed
from cryptography.x509.oid import ExtensionOID

logger = logging.getLogger("echo_gateway.alexa_verify")

MAX_TIMESTAMP_SKEW_SECONDS = 150
ECHO_API_SAN = "echo-api.amazon.com"


class AlexaVerificationError(Exception):
    def __init__(self, category: str, message: str) -> None:
        super().__init__(message)
        self.category = category
        self.message = message


def normalize_cert_url(url: str) -> str:
    parsed = urlparse(url)
    parts: list[str] = []
    for segment in parsed.path.split("/"):
        if segment in ("", "."):
            continue
        if segment == "..":
            if parts:
                parts.pop()
            continue
        parts.append(segment)
    path = "/" + "/".join(parts)
    netloc = parsed.hostname or ""
    if parsed.port:
        netloc = f"{netloc}:{parsed.port}"
    return f"{parsed.scheme}://{netloc}{path}"


def validate_cert_chain_url(url: str) -> str:
    normalized = normalize_cert_url(url)
    parsed = urlparse(normalized)
    if parsed.scheme.lower() != "https":
        raise AlexaVerificationError("cert_url", "Certificate URL must use https")
    if (parsed.hostname or "").lower() != "s3.amazonaws.com":
        raise AlexaVerificationError("cert_url", "Certificate URL host is not allowed")
    if parsed.port not in (None, 443):
        raise AlexaVerificationError("cert_url", "Certificate URL port must be 443")
    if not (parsed.path or "").startswith("/echo.api/"):
        raise AlexaVerificationError("cert_url", "Certificate URL path is not allowed")
    return normalized


@lru_cache(maxsize=32)
def _download_cert_chain(url: str) -> bytes:
    context = ssl.create_default_context()
    with urlopen(url, context=context, timeout=5) as response:  # noqa: S310 - URL validated first
        return response.read()


def _load_certs(pem_data: bytes) -> list[x509.Certificate]:
    if hasattr(x509, "load_pem_x509_certificates"):
        return list(x509.load_pem_x509_certificates(pem_data))
    return [x509.load_pem_x509_certificate(pem_data)]


def _cert_not_before(cert: x509.Certificate) -> datetime:
    if hasattr(cert, "not_valid_before_utc"):
        return cert.not_valid_before_utc
    return cert.not_valid_before.replace(tzinfo=timezone.utc)


def _cert_not_after(cert: x509.Certificate) -> datetime:
    if hasattr(cert, "not_valid_after_utc"):
        return cert.not_valid_after_utc
    return cert.not_valid_after.replace(tzinfo=timezone.utc)


def _leaf_has_echo_san(cert: x509.Certificate) -> bool:
    try:
        ext = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
    except x509.ExtensionNotFound:
        return False
    names = ext.value.get_values_for_type(x509.DNSName)
    return ECHO_API_SAN in names


def verify_alexa_signature(
    *,
    body: bytes,
    signature_cert_chain_url: str | None,
    signature_256: str | None,
) -> None:
    if not signature_cert_chain_url or not signature_256:
        raise AlexaVerificationError("signature_headers", "Missing Alexa signature headers")

    cert_url = validate_cert_chain_url(signature_cert_chain_url)
    try:
        pem_data = _download_cert_chain(cert_url)
    except Exception as exc:  # noqa: BLE001
        raise AlexaVerificationError("cert_download", "Unable to download Alexa certificate") from exc

    try:
        certs = _load_certs(pem_data)
    except ValueError as exc:
        raise AlexaVerificationError("cert_parse", "Unable to parse Alexa certificate") from exc

    if not certs:
        raise AlexaVerificationError("cert_parse", "Empty certificate chain")

    signing_cert = certs[0]
    now = datetime.now(timezone.utc)
    if _cert_not_before(signing_cert) > now or _cert_not_after(signing_cert) < now:
        raise AlexaVerificationError("cert_expired", "Alexa signing certificate is not currently valid")
    if not _leaf_has_echo_san(signing_cert):
        raise AlexaVerificationError("cert_san", "Alexa signing certificate SAN mismatch")

    try:
        signature = base64.b64decode(signature_256, validate=True)
    except Exception as exc:  # noqa: BLE001
        raise AlexaVerificationError("signature_b64", "Invalid Signature-256 encoding") from exc

    digest = hashlib.sha256(body).digest()
    public_key = signing_cert.public_key()
    try:
        public_key.verify(
            signature,
            digest,
            padding.PKCS1v15(),
            Prehashed(hashes.SHA256()),
        )
    except InvalidSignature as exc:
        raise AlexaVerificationError("signature_mismatch", "Request signature mismatch") from exc
    except Exception as exc:  # noqa: BLE001
        raise AlexaVerificationError("signature_verify", "Unable to verify request signature") from exc


def verify_timestamp(timestamp: str | None) -> None:
    if not timestamp:
        raise AlexaVerificationError("timestamp_missing", "Request timestamp missing")
    try:
        normalized = timestamp.replace("Z", "+00:00")
        request_time = datetime.fromisoformat(normalized)
        if request_time.tzinfo is None:
            request_time = request_time.replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise AlexaVerificationError("timestamp_parse", "Request timestamp is invalid") from exc

    skew = abs((datetime.now(timezone.utc) - request_time).total_seconds())
    if skew > MAX_TIMESTAMP_SKEW_SECONDS:
        raise AlexaVerificationError("timestamp_stale", "Request timestamp is outside allowed skew")


def verify_application_id(envelope: dict, expected_skill_id: str) -> None:
    if not expected_skill_id:
        logger.warning("alexa_skill_id_unset category=config")
        return
    context = envelope.get("context") or {}
    system = context.get("System") or {}
    application = system.get("application") or {}
    app_id = application.get("applicationId")
    if not app_id:
        session = envelope.get("session") or {}
        application = session.get("application") or {}
        app_id = application.get("applicationId")
    if app_id != expected_skill_id:
        raise AlexaVerificationError("application_id", "Alexa applicationId mismatch")
