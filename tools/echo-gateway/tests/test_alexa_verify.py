from __future__ import annotations

import base64
import datetime as dt
import hashlib

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed
from cryptography.x509.oid import ExtensionOID, NameOID

from echo_gateway import alexa_verify
from echo_gateway.alexa_verify import AlexaVerificationError, verify_alexa_signature

URL = "https://s3.amazonaws.com/echo.api/echo-api-cert.pem"
BODY = b'{"hello": "alexa"}'


def _key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def _cert(subject_key, cn, issuer_key, issuer_cn, *, ca, san=None):
    now = dt.datetime.now(dt.timezone.utc)
    b = (
        x509.CertificateBuilder()
        .subject_name(_name(cn))
        .issuer_name(_name(issuer_cn))
        .public_key(subject_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
    )
    b = b.add_extension(
        x509.SubjectKeyIdentifier.from_public_key(subject_key.public_key()), critical=False
    ).add_extension(
        x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), critical=False
    )
    if ca:
        b = b.add_extension(
            x509.KeyUsage(False, False, False, False, False, True, True, False, False),
            critical=True,
        )
    if san:
        b = b.add_extension(x509.SubjectAlternativeName([x509.DNSName(san)]), critical=False)
        b = b.add_extension(
            x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False
        )
    return b.sign(issuer_key, hashes.SHA256())


def _pem(*certs):
    return b"".join(c.public_bytes(serialization.Encoding.PEM) for c in certs)


def _sig(key):
    sig = key.sign(hashlib.sha256(BODY).digest(), padding.PKCS1v15(), Prehashed(hashes.SHA256()))
    return base64.b64encode(sig).decode()


@pytest.fixture
def pki():
    root_k, inter_k, leaf_k = _key(), _key(), _key()
    root = _cert(root_k, "Root", root_k, "Root", ca=True)
    inter = _cert(inter_k, "Inter", root_k, "Root", ca=True)
    leaf = _cert(leaf_k, "leaf", inter_k, "Inter", ca=False, san="echo-api.amazon.com")
    return root, inter, leaf, leaf_k


def _setup(monkeypatch, chain_pem, roots):
    monkeypatch.setattr(alexa_verify, "_download_cert_chain", lambda url: chain_pem)
    monkeypatch.setattr(alexa_verify, "_trusted_roots", lambda: tuple(roots))


def _call(sig):
    verify_alexa_signature(body=BODY, signature_cert_chain_url=URL, signature_256=sig)


def test_valid_chain_accepted(monkeypatch, pki):
    root, inter, leaf, leaf_k = pki
    _setup(monkeypatch, _pem(leaf, inter), [root])
    _call(_sig(leaf_k))


def test_leaf_not_signed_by_intermediate_rejected(monkeypatch, pki):
    root, inter, _leaf, _ = pki
    rogue_k, leaf_k = _key(), _key()
    leaf = _cert(leaf_k, "leaf", rogue_k, "Inter", ca=False, san="echo-api.amazon.com")
    _setup(monkeypatch, _pem(leaf, inter), [root])
    with pytest.raises(AlexaVerificationError) as exc:
        _call(_sig(leaf_k))
    assert exc.value.category == "cert_chain"


def test_untrusted_self_signed_with_correct_san_rejected(monkeypatch, pki):
    root, *_ = pki
    k = _key()
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(_name("evil"))
        .issuer_name(_name("evil"))
        .public_key(k.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=30))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("echo-api.amazon.com")]), critical=False)
        .sign(k, hashes.SHA256())
    )
    _setup(monkeypatch, _pem(cert), [root])
    with pytest.raises(AlexaVerificationError) as exc:
        _call(_sig(k))
    assert exc.value.category == "cert_chain"
