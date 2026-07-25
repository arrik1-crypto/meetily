"""Read the signing certificate out of an APK's signing block.

Prints the leaf certificate's SHA-256 in the same colon-separated form
keytool uses, so an APK can be matched directly against the keystore that
was supposed to sign it, plus which signature schemes are present.
"""
import struct, hashlib, sys


def _pairs(data):
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        raise ValueError("no EOCD")
    cd_off = struct.unpack_from('<I', data, eocd + 16)[0]
    if data[cd_off - 16:cd_off] != b'APK Sig Block 42':
        raise ValueError("no APK signing block")
    size_end = struct.unpack_from('<Q', data, cd_off - 24)[0]
    pos, end = cd_off - size_end - 8 + 8, cd_off - 24
    out = {}
    while pos < end:
        pair_len = struct.unpack_from('<Q', data, pos)[0]
        pid = struct.unpack_from('<I', data, pos + 8)[0]
        out[pid] = data[pos + 12: pos + 8 + pair_len]
        pos += 8 + pair_len
    return out


def _leaf_cert(block):
    u32 = lambda b, o: struct.unpack_from('<I', b, o)[0]
    signer_len = u32(block, 4)
    signer = block[8:8 + signer_len]
    sd = signer[4:4 + u32(signer, 0)]
    q = 4 + u32(sd, 0)
    certs = sd[q + 4:q + 4 + u32(sd, q)]
    return certs[4:4 + u32(certs, 0)]


SCHEMES = {0x7109871a: 'v2', 0xf05368c0: 'v3', 0x1b93ad61: 'v3.1', 0x7109871b: 'v4'}


def describe(path):
    data = open(path, 'rb').read()
    try:
        pairs = _pairs(data)
    except ValueError as e:
        return f"UNSIGNED ({e})"
    present = [name for pid, name in sorted(SCHEMES.items(), key=lambda kv: kv[1])
               if pid in pairs]
    cert = None
    for pid in (0xf05368c0, 0x7109871a):   # prefer v3, fall back to v2
        if pid in pairs:
            cert = _leaf_cert(pairs[pid])
            break
    if cert is None:
        return "no v2/v3 signer"
    digest = hashlib.sha256(cert).hexdigest().upper()
    fp = ':'.join(digest[i:i + 2] for i in range(0, len(digest), 2))
    return f"{fp}  [{'+'.join(present) or 'none'}]"


for p in sys.argv[1:]:
    print(f"{p.split('/')[-1]}\n  {describe(p)}")
