"""Download the public datasets used by the research into research/data_raw/.

Sources are public GitHub repositories (pinned to the commits used in the
study) and data files bundled in the `arch` and `skfolio` Python packages.
Run:  python research/fetch_data.py
"""

from __future__ import annotations

import hashlib
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
}

PACKAGE_FILES = {
    # name: (package==version, path inside the wheel)
    "nasdaq_comp_1999_2018.csv.gz": ("arch==8.0.0", "arch/data/nasdaq/nasdaq.csv.gz"),
    "sp500_1999_2018.csv.gz": ("arch==8.0.0", "arch/data/sp500/sp500.csv.gz"),
    "sp500_close_1990_2022.csv.gz": ("skfolio==1.8.1", "skfolio/datasets/data/sp500_index.csv.gz"),
}


def _resolve_commit(repo: str, short: str) -> str:
    out = subprocess.run(
        ["git", "ls-remote", f"https://github.com/{repo}"], capture_output=True, text=True, check=True
    ).stdout
    # The pinned short hash may no longer be a branch head; raw URLs accept short hashes too.
    for line in out.splitlines():
        sha = line.split()[0]
        if sha.startswith(short):
            return sha
    return short


def fetch_github(name: str, repo: str, commit: str, path: str) -> None:
    dest = RAW / name
    if dest.exists():
        return
    url = f"https://raw.githubusercontent.com/{repo}/{commit}/{path}"
    try:
        urllib.request.urlretrieve(url, dest)
    except Exception:
        # Fall back to a shallow git checkout of that file at the default branch.
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["git", "clone", "-q", "--depth", "1", f"https://github.com/{repo}", tmp], check=True)
            dest.write_bytes((Path(tmp) / path).read_bytes())
            print(f"  note: {name} taken from the current default branch, not {commit}")


def fetch_package(name: str, requirement: str, inner: str) -> None:
    dest = RAW / name
    if dest.exists():
        return
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            [sys.executable, "-m", "pip", "download", "--no-deps", "-q", "-d", tmp, requirement], check=True
        )
        wheel = next(Path(tmp).glob("*.whl"))
        with zipfile.ZipFile(wheel) as z:
            dest.write_bytes(z.read(inner))


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    for name, (repo, commit, path) in GITHUB_FILES.items():
        print(f"{name:32s} <- github.com/{repo}@{commit}/{path}")
        fetch_github(name, repo, commit, path)
    for name, (req, inner) in PACKAGE_FILES.items():
        print(f"{name:32s} <- pip {req}:{inner}")
        fetch_package(name, req, inner)
    for f in sorted(RAW.iterdir()):
        digest = hashlib.sha256(f.read_bytes()).hexdigest()[:16]
        print(f"  {f.name:32s} {f.stat().st_size:>10,d} bytes  sha256 {digest}")


if __name__ == "__main__":
    main()
