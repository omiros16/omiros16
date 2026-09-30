"""Add classes.dex to the aapt2-linked APK, keeping stored entries 4-byte aligned (zipalign)."""
import sys
import zipfile

src, dex, out = sys.argv[1:4]


def write_aligned(zout, info, data):
    info = zipfile.ZipInfo(info.filename, date_time=(2008, 1, 1, 0, 0, 0))
    info.compress_type = compress
    info.external_attr = 0o644 << 16
    if compress == zipfile.ZIP_STORED:
        offset = zout.fp.tell() + 30 + len(info.filename.encode())
        info.extra = b"\0" * ((-offset) % 4)
    zout.writestr(info, data)


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w") as zout:
    for item in zin.infolist():
        compress = item.compress_type
        write_aligned(zout, item, zin.read(item.filename))
    compress = zipfile.ZIP_DEFLATED
    with open(dex, "rb") as f:
        write_aligned(zout, zipfile.ZipInfo("classes.dex"), f.read())
