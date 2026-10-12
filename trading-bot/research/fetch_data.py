"""Download the public datasets used by the research into research/data_raw/.

Sources are public GitHub repositories (pinned to the commits used in the
study) and data files bundled in the `arch` and `skfolio` Python packages.
Every file is checked against the SHA-256 digests in data_manifest.json, so a
changed or truncated download is refused instead of silently used.
Run:  python research/fetch_data.py
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

RAW = Path(__file__).resolve().parent / "data_raw"

GITHUB_FILES = {
    # name: (owner/repo, commit, path)
    "sp500_1927_2019.csv": ("fja05680/dow-sp500-100-years", "52a1ddd", "SP500.csv"),
    "ndx_daily.csv": ("Chief-rich/nasdaq100-data", "6483e4e", "data/ndx_daily/history.csv"),
    "nq_daily.csv": ("Chief-rich/nasdaq100-data", "6483e4e", "data/nq_daily/history.csv"),
    "nq_1m.csv": ("Chief-rich/nasdaq100-data", "6483e4e", "data/nq_1m/history.csv"),
    "ndx_1m.csv": ("Chief-rich/nasdaq100-data", "6483e4e", "data/ndx_1m/history.csv"),
    "vix_daily.csv": ("datasets/finance-vix", "3dbea23", "data/vix-daily.csv"),
    "us_market_data.csv": ("SteelCerberus/us-market-data", "f018e73", "data/us_market_data.csv"),
    "sp500_signals.json": ("jamesbens/sp500-signals", "fe677a4", "docs/data/sp500_signals.json"),
    "spy_5m.csv": ("vivek-v-rao/Intraday-Vol", "3a1b40f", "SPY.csv"),
    "nikkei225.csv": ("VatsalSin/Stock-Market-Indices-Dataset", "799aaff", "Nikkei%20225.csv"),
    "hangseng.csv": ("VatsalSin/Stock-Market-Indices-Dataset", "799aaff", "HSI.csv"),
    "djia.csv": ("VatsalSin/Stock-Market-Indices-Dataset", "799aaff", "DJIA.csv"),
    "nifty50.csv": ("VatsalSin/Stock-Market-Indices-Dataset", "799aaff", "NIFTY%2050.csv"),
}

PACKAGE_FILES = {
    # name: (package==version, path inside the wheel)
    "nasdaq_comp_1999_2018.csv.gz": ("arch==8.0.0", "arch/data/nasdaq/nasdaq.csv.gz"),
    "sp500_1999_2018.csv.gz": ("arch==8.0.0", "arch/data/sp500/sp500.csv.gz"),
    "sp500_close_1990_2022.csv.gz": ("skfolio==1.8.1", "skfolio/datasets/data/sp500_index.csv.gz"),
}


MANIFEST = Path(__file__).resolve().parent / "data_manifest.json"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _install(name: str, data: bytes, expected: str | None) -> None:
    """Write via a temporary file and keep it only if it matches the digest used in the study."""
    digest = hashlib.sha256(data).hexdigest()
    if expected is not None and digest != expected:
        raise SystemExit(f"{name}: sha256 {digest[:16]}... does not match the study's {expected[:16]}... "
                         "(the source changed); not installed")
    tmp = RAW / (name + ".part")
    tmp.write_bytes(data)
    tmp.replace(RAW / name)


def fetch_github(name: str, repo: str, commit: str, path: str, expected: str | None) -> None:
    url = f"https://raw.githubusercontent.com/{repo}/{commit}/{path}"
    with urllib.request.urlopen(url, timeout=60) as resp:
        data = resp.read()
    _install(name, data, expected)


def fetch_package(name: str, requirement: str, inner: str, expected: str | None) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            [sys.executable, "-m", "pip", "download", "--no-deps", "-q", "-d", tmp, requirement], check=True
        )
        wheel = next(Path(tmp).glob("*.whl"))
        with zipfile.ZipFile(wheel) as z:
            _install(name, z.read(inner), expected)


def main() -> None:
    """Fetch missing files and verify every file against data_manifest.json (the study's data).

    `--update-manifest` records the digests of the files present (only for a deliberate data update).
    """
    RAW.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    jobs = [(name, fetch_github, (repo, commit, path), f"github.com/{repo}@{commit}/{path}")
            for name, (repo, commit, path) in GITHUB_FILES.items()]
    jobs += [(name, fetch_package, (req, inner), f"pip {req}:{inner}") for name, (req, inner) in PACKAGE_FILES.items()]
    bad = []
    for name, fetch, fetch_args, source in jobs:
        dest = RAW / name
        if not dest.exists():
            print(f"{name:32s} <- {source}")
            fetch(name, *fetch_args, manifest.get(name))
        if name in manifest and _sha256(dest) != manifest[name]:
            bad.append(name)
    if "--update-manifest" in sys.argv:
        MANIFEST.write_text(json.dumps({f.name: _sha256(f) for f in sorted(RAW.iterdir())}, indent=2) + "\n")
        print(f"manifest updated: {MANIFEST}")
    elif bad:
        raise SystemExit(f"these files differ from the data used in the study: {bad}. Delete them and run again.")
    for f in sorted(RAW.iterdir()):
        print(f"  {f.name:32s} {f.stat().st_size:>10,d} bytes  sha256 {_sha256(f)[:16]}  "
              + ("ok" if manifest.get(f.name) == _sha256(f) else "NOT IN MANIFEST"))


if __name__ == "__main__":
    main()
